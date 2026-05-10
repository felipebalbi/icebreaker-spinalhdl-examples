package uart

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba3.apb.sim.Apb3Driver

/** APB-driven smoke test for [[UartController]].
  *
  * Drives the controller through its memory-mapped interface with a
  * software-style sequence:
  *
  *   1. Read CFG_INFO and CLKFREQ — sanity-check the introspection
  *      registers reflect the build parameters.
  *   2. Enable the controller and TX/RX paths via CTRL.
  *   3. Write a handful of bytes to TXDATA, with the TX line looped
  *      back to RX in the testbench.
  *   4. Poll STATUS.rx_data_avail and pop bytes from RXDATA, checking
  *      they round-trip unchanged.
  *
  * Pure black-box: the sim never reaches inside the controller. If
  * the address decode for TXDATA / RXDATA is wrong or the FIFO push
  * timing is off, a byte goes missing here and the sim fails.
  *
  * The whole exercise runs on the default 12 MHz / 115200 8N1
  * configuration — small enough to simulate end-to-end in a few
  * hundred microseconds of wall-clock SCL time.
  */
object UartControllerSim {

  // Register offsets — must match the address map in UartController.scala.
  private val CTRL = 0x00
  private val STATUS = 0x04
  private val ISR = 0x08
  private val IER = 0x0c
  private val TXDATA = 0x10
  private val RXDATA = 0x14
  private val BAUD = 0x18
  private val CFG_INFO = 0x1c
  private val CLKFREQ = 0x20

  // STATUS bits.
  private val STATUS_TX_BUSY = 0
  private val STATUS_TX_FIFO_FULL = 1
  private val STATUS_TX_FIFO_EMPTY = 2
  private val STATUS_RX_DATA_AVAIL = 3
  private val STATUS_RX_FIFO_FULL = 4

  def main(args: Array[String]): Unit = {
    val cfg = UartConfig(useCts = false, useRts = false)

    SimConfig.withWave.compile(UartController(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 1000000000L / cfg.clkFreqHz)

      // Loopback: continuously drive RX from TX, one cycle delayed.
      dut.io.rx #= true
      dut.clockDomain.onSamplings {
        dut.io.rx #= dut.io.tx.toBoolean
      }

      val apb = Apb3Driver(dut.io.apb, dut.clockDomain)
      apb.verbose = false

      dut.clockDomain.waitSampling(20)

      // ----- introspection registers -----
      val clkFreq = apb.read(CLKFREQ)
      assert(
        clkFreq == BigInt(cfg.clkFreqHz),
        s"CLKFREQ readback $clkFreq != cfg.clkFreqHz ${cfg.clkFreqHz}"
      )
      println(s"[ok] CLKFREQ = $clkFreq Hz")

      val cfgInfo = apb.read(CFG_INFO)
      println(f"[ok] CFG_INFO = 0x$cfgInfo%08x")
      // Bits [3:0] should be dataBits - 1.
      assert(
        (cfgInfo & 0xf) == BigInt(cfg.dataBits - 1),
        s"CFG_INFO.dataBits got ${cfgInfo & 0xf}, expected ${cfg.dataBits - 1}"
      )

      val baudReset = apb.read(BAUD)
      val expectedPhaseInc = BigInt(BaudGenerator.phaseIncFor(cfg, BaudGenerator.defaultAccWidth))
      assert(
        baudReset == expectedPhaseInc,
        s"BAUD reset $baudReset != phaseIncFor(cfg) $expectedPhaseInc"
      )
      println(s"[ok] BAUD reset = $baudReset")

      // ----- enable everything -----
      apb.write(CTRL, 0x7) // enable | tx_enable | rx_enable
      val ctrlReadback = apb.read(CTRL)
      assert(
        (ctrlReadback & 0x7) == 0x7,
        s"CTRL readback expected 0x7, got 0x${ctrlReadback.toString(16)}"
      )

      // ----- TX/RX loopback -----
      val testBytes = Seq(0x55, 0xa5, 0x5a, 0xff, 0x00, 0x42)

      // Push all bytes into TX FIFO up front; FIFO depth is 16 by default,
      // so 6 bytes always fit.
      for (b <- testBytes) {
        // Wait if FIFO full (shouldn't happen with default depth=16).
        var status = apb.read(STATUS)
        while (((status >> STATUS_TX_FIFO_FULL) & 1) == 1) {
          dut.clockDomain.waitSampling(10)
          status = apb.read(STATUS)
        }
        apb.write(TXDATA, b)
      }

      // Drain RX side: for each byte sent, poll until rx_data_avail and
      // read RXDATA.
      val received = scala.collection.mutable.ArrayBuffer[Int]()
      // Generous timeout: each frame is ~ clkFreqHz/baudRate * 10 cycles.
      val frameCycles = (cfg.clkFreqHz / cfg.baudRate) * 12
      val totalTimeout = frameCycles * (testBytes.length + 4)
      var elapsed = 0
      while (received.length < testBytes.length && elapsed < totalTimeout) {
        val st = apb.read(STATUS)
        if (((st >> STATUS_RX_DATA_AVAIL) & 1) == 1) {
          val b = apb.read(RXDATA).toInt & 0xff
          received += b
        } else {
          dut.clockDomain.waitSampling(100)
          elapsed += 100
        }
      }

      assert(
        received.length == testBytes.length,
        s"timeout: received ${received.length} of ${testBytes.length} bytes after $elapsed cycles"
      )
      for ((sent, got) <- testBytes.zip(received)) {
        assert(
          (sent & 0xff) == got,
          f"loopback mismatch: sent 0x$sent%02x, got 0x$got%02x"
        )
      }
      println(
        s"[ok] loopback round-tripped ${testBytes.length} bytes: ${received.map("0x%02x".format(_)).mkString(", ")}"
      )

      // ----- sticky bit / RC clear semantics -----
      // No errors should be set.
      val isr = apb.read(ISR)
      // RX-done should be set after the loopback above; reading clears it.
      assert(
        (isr & (1 << 4)) != 0,
        s"ISR.rx_done expected to be sticky after RX, got 0x${isr.toString(16)}"
      )
      val isr2 = apb.read(ISR)
      assert(
        (isr2 & (1 << 4)) == 0,
        s"ISR.rx_done should be cleared by read, got 0x${isr2.toString(16)}"
      )
      println("[ok] ISR.rx_done sticky-then-clear semantics verified")

      println("UartControllerSim: PASS")
    }
  }
}
