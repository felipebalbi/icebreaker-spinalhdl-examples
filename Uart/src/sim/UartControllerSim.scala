package uart

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba3.apb.sim.Apb3Driver

/** APB-driven smoke test for [[UartController]].
  *
  * Drives the controller through its memory-mapped interface with a
  * software-style sequence:
  *
  *   1. Read CFG_INFO and the FIFO_STATUS depth fields — sanity-check
  *      the introspection registers reflect the build parameters.
  *   2. Enable the controller and TX/RX paths via CTRL.
  *   3. Write a handful of bytes to TXDATA, with the TX line looped
  *      back to RX in the testbench.
  *   4. Poll RX_FIFO_STATUS.empty and pop bytes from RXDATA, checking
  *      they round-trip unchanged.
  *   5. Verify ISR.rx_done sticks until firmware writes 1 to clear it
  *      (W1C semantics).
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
  private val TX_FIFO_STATUS = 0x1c
  private val RX_FIFO_STATUS = 0x20
  private val CFG_INFO = 0x24

  // ISR / IER bit positions (mirror layout).
  private val IRQ_FRAMING = 0
  private val IRQ_PARITY = 1
  private val IRQ_OVERRUN = 2
  private val IRQ_TX_DONE = 3
  private val IRQ_RX_DONE = 4

  // FIFO_STATUS bit positions (same layout for TX and RX).
  private val FIFO_FULL = 0
  private val FIFO_EMPTY = 1
  private val FIFO_COUNT_LSB = 8
  private val FIFO_DEPTH_LSB = 16

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
      val cfgInfo = apb.read(CFG_INFO)
      println(f"[ok] CFG_INFO = 0x$cfgInfo%08x")
      // Bits [3:0] should be dataBits - 1.
      assert(
        (cfgInfo & 0xf) == BigInt(cfg.dataBits - 1),
        s"CFG_INFO.dataBits got ${cfgInfo & 0xf}, expected ${cfg.dataBits - 1}"
      )

      // FIFO depths should reflect the per-side cfg fields.
      val txStatusReset = apb.read(TX_FIFO_STATUS)
      val rxStatusReset = apb.read(RX_FIFO_STATUS)
      val txDepthRb = ((txStatusReset >> FIFO_DEPTH_LSB) & 0xff).toInt
      val rxDepthRb = ((rxStatusReset >> FIFO_DEPTH_LSB) & 0xff).toInt
      assert(
        txDepthRb == cfg.txFifoDepth,
        s"TX_FIFO_STATUS.depth = $txDepthRb, expected ${cfg.txFifoDepth}"
      )
      assert(
        rxDepthRb == cfg.rxFifoDepth,
        s"RX_FIFO_STATUS.depth = $rxDepthRb, expected ${cfg.rxFifoDepth}"
      )
      // After reset both FIFOs must read empty (count = 0, empty = 1).
      assert(
        ((txStatusReset >> FIFO_EMPTY) & 1) == 1 && ((txStatusReset >> FIFO_COUNT_LSB) & 0xff) == 0,
        s"TX FIFO not empty at reset: 0x${txStatusReset.toString(16)}"
      )
      assert(
        ((rxStatusReset >> FIFO_EMPTY) & 1) == 1 && ((rxStatusReset >> FIFO_COUNT_LSB) & 0xff) == 0,
        s"RX FIFO not empty at reset: 0x${rxStatusReset.toString(16)}"
      )
      println(s"[ok] FIFO_STATUS depth fields = TX:$txDepthRb RX:$rxDepthRb, both empty at reset")

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

      // Push all bytes into TX FIFO up front; depth is 16 by default,
      // so 6 bytes always fit.
      for (b <- testBytes) {
        // Wait if FIFO full (shouldn't happen with default depth=16).
        var txStatus = apb.read(TX_FIFO_STATUS)
        while (((txStatus >> FIFO_FULL) & 1) == 1) {
          dut.clockDomain.waitSampling(10)
          txStatus = apb.read(TX_FIFO_STATUS)
        }
        apb.write(TXDATA, b)
      }

      // Drain RX side: for each byte sent, poll until !empty and
      // read RXDATA.
      val received = scala.collection.mutable.ArrayBuffer[Int]()
      // Generous timeout: each frame is ~ clkFreqHz/baudRate * 10 cycles.
      val frameCycles = (cfg.clkFreqHz / cfg.baudRate) * 12
      val totalTimeout = frameCycles * (testBytes.length + 4)
      var elapsed = 0
      while (received.length < testBytes.length && elapsed < totalTimeout) {
        val rxStatus = apb.read(RX_FIFO_STATUS)
        if (((rxStatus >> FIFO_EMPTY) & 1) == 0) {
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

      // After draining everything the RX FIFO must be empty again with count = 0.
      val rxStatusDrained = apb.read(RX_FIFO_STATUS)
      assert(
        ((rxStatusDrained >> FIFO_EMPTY) & 1) == 1 && ((rxStatusDrained >> FIFO_COUNT_LSB) & 0xff) == 0,
        s"RX FIFO not empty after drain: 0x${rxStatusDrained.toString(16)}"
      )
      println("[ok] RX FIFO empty after drain")

      // ----- ISR sticky / W1C clear semantics -----
      // RX-done should still be set (sticky); writing 1 clears it,
      // but a plain read must NOT clear it.
      val isr = apb.read(ISR)
      assert(
        (isr & (1 << IRQ_RX_DONE)) != 0,
        s"ISR.rx_done expected to be sticky after RX, got 0x${isr.toString(16)}"
      )
      val isrReread = apb.read(ISR)
      assert(
        (isrReread & (1 << IRQ_RX_DONE)) != 0,
        s"ISR.rx_done was cleared by a plain read (expected W1C, not RC), got 0x${isrReread.toString(16)}"
      )
      apb.write(ISR, BigInt(1) << IRQ_RX_DONE)
      val isrCleared = apb.read(ISR)
      assert(
        (isrCleared & (1 << IRQ_RX_DONE)) == 0,
        s"ISR.rx_done should be cleared by W1C write, got 0x${isrCleared.toString(16)}"
      )
      println("[ok] ISR.rx_done sticky / read-preserves / W1C-clears semantics verified")

      println("UartControllerSim: PASS")
    }
  }
}
