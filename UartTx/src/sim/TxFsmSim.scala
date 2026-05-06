package uart_tx

import spinal.core._
import spinal.core.sim._

/** Standalone sim for [[TxFsm]].
  *
  * The DUT here is *just* the FSM, not the wider UartTx. To stand it up
  * we need a model of its two combinational neighbours:
  *
  *   - The [[BaudGenerator]]: a sim-side thread that pulses `io.tick`
  *     for one cycle every `ticksPerBit` cycles, but **only while the
  *     FSM's `busy` is high** — exactly the contract the real
  *     wrapper enforces via `baud.io.enable := fsm.io.busy`. The
  *     phase resets between frames.
  *
  *   - The [[TxShiftReg]]: a sim-side mutable byte that:
  *       * captures `nextByte` on every cycle the FSM pulses
  *         `io.loadReg` high,
  *       * shifts right on every cycle the FSM pulses `io.shiftReg`
  *         high (load wins over shift, matching the real block),
  *       * exposes its LSB on `io.shiftRegBit` continuously.
  *
  * What we verify
  *   1. **Bit-pattern correctness.** For each of several test bytes
  *      (0x00, 0xFF, 0xAA, 0x55, 0x80, 0x01, 0xAD), drive a frame
  *      and sample `io.txBit` at the *middle* of each bit period.
  *      The reconstructed sequence must match `[start=0, d0..d7,
  *      stop=1]`. Mid-bit sampling is exactly how a real UART RX
  *      recovers data, so this is the canonical correctness test.
  *      The mix of patterns catches: init-bleed bugs (0x00, 0xFF),
  *      LSB/MSB swaps (0x80, 0x01), off-by-one shifts (0xAA, 0x55),
  *      and a generic mix (0xAD).
  *
  *   2. **`loadReg` pulse count.** Exactly one `loadReg` pulse per
  *      frame, fired the cycle the FSM accepts.
  *
  *   3. **`shiftReg` pulse count.** Exactly `dataBits - 1` `shiftReg`
  *      pulses per frame — the FSM suppresses the dangling shift on
  *      the final data tick (see TxFsm.scala for why).
  *
  *   4. **Back-to-back frames.** Holding "start" high across multiple
  *      frames must work: the FSM should consume `start` once per
  *      Idle visit and immediately launch the next frame. This is
  *      the test that would catch a regression to the old
  *      `io.start.rise()` behaviour.
  *
  *   5. **Stop-bit width.** With `cfg.stopBits = 1`, the line must be
  *      high for `ticksPerBit` cycles after the last data bit
  *      (modulo the 1-cycle txReg pipeline at each boundary, which
  *      mid-bit sampling tolerates by construction).
  *
  *   6. **Two stop bits.** A second elaboration with
  *      `cfg.stopBits = 2` runs one frame and confirms two stop-bit
  *      periods of high line.
  *
  * Run: `sbt "runMain uart_tx.TxFsmSim"`
  */
object TxFsmSim {

  /** Test plan executed against a freshly-elaborated [[TxFsm]] DUT.
    * Pulled out of `main` so we can run it for two different configs
    * (1 stop bit and 2 stop bits) without duplicating code.
    *
    * @param patterns        bytes to transmit and verify in test (1)
    * @param backToBackBytes bytes to transmit in the back-to-back test (4)
    */
  def runFsmTest(
      cfg: UartTxConfig,
      patterns: Seq[Int],
      backToBackBytes: Seq[Int]
  ): Unit = {
    val ticksPerBit = cfg.ticksPerBit

    SimConfig.withWave
      .compile(TxFsm(cfg))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        // ------------------------------------------------------------------
        // Sim-side fake TxShiftReg. Updated on every clock edge based on
        // the FSM's combinational pulse outputs sampled in the cycle
        // that just ended. `nextByte` is what the next `loadReg` will
        // capture; the test sets it before pulsing `start`.
        // ------------------------------------------------------------------
        val mask = (1 << cfg.dataBits) - 1
        var sreg: Int     = 0xff
        var nextByte: Int = 0
        def expose(): Unit = dut.io.shiftRegBit #= ((sreg & 1) == 1)
        expose()

        fork {
          while (true) {
            dut.clockDomain.waitSampling()
            // Sampled just after the rising edge — values reflect the
            // cycle that just completed. Match real-RTL register
            // semantics: load wins over shift.
            val didLoad  = dut.io.loadReg.toBoolean
            val didShift = dut.io.shiftReg.toBoolean
            if (didLoad) {
              sreg = nextByte & mask
            } else if (didShift) {
              sreg = (sreg >> 1) & mask
            }
            expose()
          }
        }

        // ------------------------------------------------------------------
        // Sim-side fake BaudGenerator. Pulses `io.tick` for one cycle
        // every `ticksPerBit` cycles, but only while `io.busy` is high
        // — same contract as `baud.io.enable := busy` in the real
        // wrapper. Phase resets each time `busy` rises (matching a DDS
        // baud generator that's gated by enable from acc=0).
        // ------------------------------------------------------------------
        fork {
          dut.io.tick #= false
          while (true) {
            // Wait for a frame to start.
            while (!dut.io.busy.toBoolean) dut.clockDomain.waitSampling()

            // First tick lands ticksPerBit cycles after busy rose.
            for (_ <- 0 until ticksPerBit - 1) dut.clockDomain.waitSampling()
            dut.io.tick #= true
            dut.clockDomain.waitSampling()
            dut.io.tick #= false
          }
        }

        // ------------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------------

        /** Wait for the FSM to accept a `start` request (busy goes
          * high). Drops `start` once accepted so we don't accidentally
          * launch a second frame.
          */
        def launchFrame(byte: Int): Unit = {
          nextByte = byte
          dut.io.start #= true
          while (!dut.io.busy.toBoolean) dut.clockDomain.waitSampling()
          dut.io.start #= false
        }

        /** Sample `io.txBit` at the middle of each of the
          * `1 + dataBits + stopBits` bit periods of a frame. Caller
          * must have just launched a frame; this routine returns once
          * sampling is done but does NOT wait for `busy` to drop.
          */
        def sampleFrameMidBit(): Seq[Boolean] = {
          // We're somewhere in the first cycle or two of startState.
          // Walk to the middle of the start bit. The startState span
          // is ticksPerBit cycles starting from the cycle after `busy`
          // rose; we entered this routine some 1-2 cycles into that
          // span (whatever it took for the polling loop to notice
          // busy). Approximate: wait `ticksPerBit / 2` cycles. The
          // mid-bit window is wide (~ticksPerBit/2 cycles either
          // side), so a couple of cycles of slack don't matter.
          dut.clockDomain.waitSampling(ticksPerBit / 2)
          val nBits = 1 + cfg.dataBits + cfg.stopBits
          val out   = scala.collection.mutable.ArrayBuffer[Boolean]()
          for (i <- 0 until nBits) {
            out += dut.io.txBit.toBoolean
            if (i < nBits - 1) dut.clockDomain.waitSampling(ticksPerBit)
          }
          out.toSeq
        }

        /** Build the expected mid-bit sequence for `byte`:
          * `[0, d0, d1, ..., d{N-1}, 1, 1, ...]`.
          */
        def expectedFrame(byte: Int): Seq[Boolean] = {
          Seq(false) ++
            (0 until cfg.dataBits).map(i => ((byte >> i) & 1) == 1) ++
            Seq.fill(cfg.stopBits)(true)
        }

        def fmt(bs: Seq[Boolean]): String =
          bs.map(b => if (b) '1' else '0').mkString

        /** End-to-end: launch + sample + verify + drain. */
        def expectFrame(byte: Int, label: String = ""): Unit = {
          launchFrame(byte)
          val got = sampleFrameMidBit()
          val exp = expectedFrame(byte)
          assert(
            got == exp,
            f"[$label] frame for 0x$byte%02X (cfg.stopBits=${cfg.stopBits}): " +
              f"got ${fmt(got)} expected ${fmt(exp)}"
          )
          // Drain: wait for busy to drop so the next frame starts clean.
          while (dut.io.busy.toBoolean) dut.clockDomain.waitSampling()
        }

        // ------------------------------------------------------------------
        // Init
        // ------------------------------------------------------------------
        dut.io.start #= false
        dut.io.tick  #= false
        dut.clockDomain.waitSampling(20)

        assert(dut.io.txBit.toBoolean, "TX should idle high after reset")
        assert(!dut.io.busy.toBoolean, "busy should be low at startup")

        // ------------------------------------------------------------------
        // (1) Per-pattern bit-correctness sweep.
        // ------------------------------------------------------------------
        for (p <- patterns) {
          expectFrame(p, label = "pattern")
          // Small inter-frame gap — let the tick thread re-arm.
          dut.clockDomain.waitSampling(5)
        }

        // ------------------------------------------------------------------
        // (2) loadReg pulse count: exactly one per frame.
        // (3) shiftReg pulse count: exactly dataBits - 1 per frame.
        //
        // We count pulses by polling each cycle for one frame's worth
        // of activity. Use a sentinel byte so observed pulses can't
        // be confused with anything else.
        // ------------------------------------------------------------------
        var loadPulses  = 0
        var shiftPulses = 0
        val counter = fork {
          while (true) {
            dut.clockDomain.waitSampling()
            if (dut.io.loadReg.toBoolean)  loadPulses  += 1
            if (dut.io.shiftReg.toBoolean) shiftPulses += 1
          }
        }
        loadPulses  = 0
        shiftPulses = 0
        expectFrame(0x5a, label = "pulse-count")
        // Allow one extra cycle for the counting thread to observe the
        // tail of the frame.
        dut.clockDomain.waitSampling(2)
        counter.terminate()
        assert(
          loadPulses == 1,
          s"loadReg pulses: expected 1, got $loadPulses"
        )
        val expectedShifts = cfg.dataBits - 1
        assert(
          shiftPulses == expectedShifts,
          s"shiftReg pulses: expected $expectedShifts, got $shiftPulses"
        )

        // ------------------------------------------------------------------
        // (4) Back-to-back frames with `start` held high. This is the
        // test that catches the rise-edge regression.
        // ------------------------------------------------------------------
        for (b <- backToBackBytes) {
          // Set up the next byte BEFORE pulsing start so the load
          // captures it.
          nextByte = b
          dut.io.start #= true
          while (!dut.io.busy.toBoolean) dut.clockDomain.waitSampling()
          // Don't drop start — leave it high so the loop above tests
          // the held-high case across the next frame.
          val got = sampleFrameMidBit()
          val exp = expectedFrame(b)
          assert(
            got == exp,
            f"[back-to-back] frame for 0x$b%02X: got ${fmt(got)} " +
              f"expected ${fmt(exp)}"
          )
          while (dut.io.busy.toBoolean) dut.clockDomain.waitSampling()
        }
        dut.io.start #= false

        // ------------------------------------------------------------------
        // (5)/(6) Stop-bit width sanity. Send 0x00 (so all data bits
        // are low and the high "block" at the end is unambiguously the
        // stop period), then assert the line is high for the bulk of
        // `ticksPerBit * cfg.stopBits` cycles. We trim 2 cycles either
        // side of the window to tolerate the 1-cycle txReg pipeline at
        // both edges (a real RX would never sample those boundary
        // cycles either).
        // ------------------------------------------------------------------
        nextByte = 0x00
        dut.io.start #= true
        while (!dut.io.busy.toBoolean) dut.clockDomain.waitSampling()
        dut.io.start #= false
        // Skip start bit + dataBits data bits to land in stop.
        dut.clockDomain.waitSampling(ticksPerBit * (1 + cfg.dataBits) + 2)
        val stopWindow = ticksPerBit * cfg.stopBits - 4
        for (_ <- 0 until stopWindow) {
          assert(
            dut.io.txBit.toBoolean,
            s"stop-bit window (cfg.stopBits=${cfg.stopBits}): expected high"
          )
          dut.clockDomain.waitSampling()
        }
        while (dut.io.busy.toBoolean) dut.clockDomain.waitSampling()

        println(
          s"OK: TxFsm behaves correctly (dataBits=${cfg.dataBits}, " +
            s"stopBits=${cfg.stopBits})"
        )
      }
  }

  def main(args: Array[String]): Unit = {
    val patterns        = Seq(0x00, 0xff, 0xaa, 0x55, 0x80, 0x01, 0xad)
    val backToBackBytes = Seq(0x12, 0x34, 0x56, 0x78)

    // -- Default config: 8N1 at a small clk/baud ratio for fast sim --
    runFsmTest(
      UartTxConfig(
        clkFreqHz = 1000000,
        baudRate  = 100000, // ticksPerBit = 10
        dataBits  = 8,
        stopBits  = 1
      ),
      patterns,
      backToBackBytes
    )

    // -- 8N2 variant: re-elaborate, run a smaller smoke test --
    runFsmTest(
      UartTxConfig(
        clkFreqHz = 1000000,
        baudRate  = 100000,
        dataBits  = 8,
        stopBits  = 2
      ),
      patterns        = Seq(0x00, 0xff, 0xa5),
      backToBackBytes = Seq(0x42, 0x99)
    )
  }
}
