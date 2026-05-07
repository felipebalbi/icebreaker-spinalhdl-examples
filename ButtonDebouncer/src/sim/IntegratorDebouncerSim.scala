package button_debouncer

import spinal.core._
import spinal.core.sim._

/** Standalone sim for [[IntegratorDebouncer]].
  *
  * Strategy
  *   - Use a *small* `width` (4 bits => 16-cycle saturation) so the test
  *     finishes quickly.
  *   - First, feed steady-low for a few cycles to confirm `stable` stays
  *     low.
  *   - Then drive a bouncy signal: many fast 1/0 transitions but with a
  *     net-high duty cycle. Assert `stable` does NOT yet flip.
  *   - Then drive cleanly high for `>= 2^width` cycles. Assert `stable`
  *     flips, `rising` pulses for exactly one cycle.
  *   - Symmetrically drive cleanly low and check `falling`.
  *   - Verify `BufferCC` synchroniser latency is accounted for (a few
  *     cycles after each driven transition).
  *
  * Run: `sbt "runMain button_debouncer.IntegratorDebouncerSim"`
  */
object IntegratorDebouncerSim {
  def main(args: Array[String]): Unit = {

    val width = 4 // 16 cycles to fully saturate
    val satCycles = 1 << width

    SimConfig.withWave
      .compile(IntegratorDebouncer(width))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        dut.io.raw #= false
        dut.clockDomain.waitSampling(8)
        assert(!dut.io.stable.toBoolean, "stable should start low")

        // ----- (1) bouncy phase that must NOT saturate ------------------
        // Pattern: alternate H/L every cycle. Counter goes 0 -> 1 -> 0 -> 1
        // and clamps at 0 on every low (already at 0). Never reaches
        // maxValue, so `stable` must stay low throughout.
        var risingSeen = false
        for (_ <- 0 until satCycles * 4) {
          dut.io.raw #= true
          dut.clockDomain.waitSampling()
          if (dut.io.rising.toBoolean) risingSeen = true
          dut.io.raw #= false
          dut.clockDomain.waitSampling()
          if (dut.io.rising.toBoolean) risingSeen = true
        }
        assert(
          !risingSeen,
          "rising pulse fired during bouncy phase — counter should not have saturated"
        )
        assert(
          !dut.io.stable.toBoolean,
          "stable should still be low after bounces"
        )

        // ----- (2) clean high long enough to saturate --------------------
        dut.io.raw #= true
        var risingPulses = 0
        // BufferCC adds 2 cycles of latency, then need satCycles to count
        // up. Give a generous margin.
        for (_ <- 0 until satCycles + 8) {
          dut.clockDomain.waitSampling()
          if (dut.io.rising.toBoolean) risingPulses += 1
        }
        assert(
          dut.io.stable.toBoolean,
          "stable should be HIGH after sustained high input"
        )
        assert(
          risingPulses == 1,
          s"expected exactly 1 rising pulse, got $risingPulses"
        )

        // ----- (3) clean low long enough to saturate ---------------------
        dut.io.raw #= false
        var fallingPulses = 0
        for (_ <- 0 until satCycles + 8) {
          dut.clockDomain.waitSampling()
          if (dut.io.falling.toBoolean) fallingPulses += 1
        }
        assert(
          !dut.io.stable.toBoolean,
          "stable should be LOW after sustained low input"
        )
        assert(
          fallingPulses == 1,
          s"expected exactly 1 falling pulse, got $fallingPulses"
        )

        println("OK: IntegratorDebouncer behaves correctly")
      }
  }
}
