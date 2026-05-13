package button_debouncer

import spinal.core._
import spinal.core.sim._

/** Standalone sim for [[TimerDebouncer]].
  *
  * Strategy
  *   - Use a tiny clock frequency / debounce time so the countdown is just a
  *     handful of cycles. With clkFreqHz = 1_000_000 and debounceMs = 1 the
  *     window is `1_000_000 / 1000 * 1 = 1000` cycles — still too many. The
  *     numerator-vs-divider math means the smallest non-degenerate window we
  *     can express is 1 ms / 1 MHz = 1000 cycles; for the sim we use 1 MHz / 1
  *     ms = 1000 cycles and just live with the count.
  *   - To make the sim quick we instead bypass the helper and pass small
  *     numbers that result in `maxCount = 8` cycles by exploiting the
  *     `if`-guard at the bottom of `TimerDebouncer`. We construct it with
  *     `clkFreqHz = 8000, debounceMs = 1` => `(8000/1000)*1 = 8` cycles.
  *
  * What we verify
  *   1. With steady-low input, `stable` stays low. 2. A bouncy input that never
  *      holds steady for a full window does NOT commit (the timer keeps getting
  *      reset). 3. A clean rising edge held for >= window flips `stable`
  *      exactly once and pulses `rising` for exactly one cycle. 4. A clean
  *      falling edge does the symmetric thing for `falling`.
  *
  * Run: `sbt "runMain button_debouncer.TimerDebouncerSim"`
  */
object TimerDebouncerSim {
  def main(args: Array[String]): Unit = {

    // (8000 / 1000) * 1 = 8 cycle debounce window. Quick to sim.
    val window = 8

    SimConfig.withWave
      .compile(TimerDebouncer(clkFreqHz = 8000, debounceMs = 1))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        dut.io.raw #= false
        dut.clockDomain.waitSampling(8)
        assert(!dut.io.stable.toBoolean, "stable should start low")

        // (2) Bouncy input: toggle every (window/2 - 1) cycles so the
        //     timer keeps resetting and never expires.
        var risingSeen = false
        for (_ <- 0 until 6) {
          dut.io.raw #= true
          dut.clockDomain.waitSampling(window - 2)
          if (dut.io.rising.toBoolean) risingSeen = true
          dut.io.raw #= false
          dut.clockDomain.waitSampling(window - 2)
          if (dut.io.rising.toBoolean) risingSeen = true
        }
        assert(!risingSeen, "rising fired during bouncy phase")
        assert(
          !dut.io.stable.toBoolean,
          "stable should still be low after bounces"
        )

        // (3) Clean high: drive high and hold for well past the window
        //     plus BufferCC latency.
        dut.io.raw #= true
        var rises = 0
        for (_ <- 0 until window + 8) {
          dut.clockDomain.waitSampling()
          if (dut.io.rising.toBoolean) rises += 1
        }
        assert(
          dut.io.stable.toBoolean,
          "stable should go HIGH after sustained high input"
        )
        assert(rises == 1, s"expected exactly 1 rising pulse, got $rises")

        // (4) Clean low: same for falling.
        dut.io.raw #= false
        var falls = 0
        for (_ <- 0 until window + 8) {
          dut.clockDomain.waitSampling()
          if (dut.io.falling.toBoolean) falls += 1
        }
        assert(
          !dut.io.stable.toBoolean,
          "stable should go LOW after sustained low input"
        )
        assert(falls == 1, s"expected exactly 1 falling pulse, got $falls")

        println("OK: TimerDebouncer behaves correctly")
      }
  }
}
