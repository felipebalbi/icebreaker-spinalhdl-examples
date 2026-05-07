package pwm

import spinal.core._
import spinal.core.sim._

/** Standalone sim for [[PwmTop]].
  *
  * Strategy
  *   - Use a small `width` (8 bits, period = 256 cycles) so a handful of full
  *     periods fits in a quick simulation.
  *   - For each duty value in a representative set, hold `io.duty` steady for
  *     several full periods, count how many of the `2^width` cycles in *one*
  *     full period had `io.pwm == 1`, and assert it equals `duty` exactly.
  *
  * What we verify
  *   1. With `duty = 0` the output is permanently low for a full period.
  *   2. With `duty = 2^width - 1` the output is high for `(2^width - 1)`
  *      cycles per period (one short of full duty — by design, see PwmTop's
  *      Scaladoc).
  *   3. For intermediate values the high-cycle count exactly matches `duty`
  *      (counter-comparator PWM is cycle-precise once the duty input has
  *      been stable for at least one full period).
  *   4. After sweeping, run a continuous ramp so the resulting waveform shows
  *     a recognisable PWM "fade" pattern.
  *
  * Run: `sbt "runMain pwm.PwmSim"` (or `make sim`).
  */
object PwmSim {
  def main(args: Array[String]): Unit = {

    val width = 8
    val period = 1 << width

    SimConfig.withWave
      .compile(PwmTop(width))
      .doSim { dut =>
        // PwmTop uses Spinal's implicit clock domain.
        dut.clockDomain.forkStimulus(period = 10)

        /** Hold `duty` steady for two full periods (let the counter wrap into
          * a known phase), then count the high cycles over the *next* period.
          */
        def measureHighCycles(duty: Int): Int = {
          dut.io.duty #= duty
          dut.clockDomain.waitSampling(2 * period)
          var high = 0
          for (_ <- 0 until period) {
            dut.clockDomain.waitSampling()
            if (dut.io.pwm.toBoolean) high += 1
          }
          high
        }

        // (1) + (2) + (3): exact duty match for representative values.
        val cases = Seq(0, 1, 2, 7, 64, 128, 200, period - 2, period - 1)
        for (d <- cases) {
          val got = measureHighCycles(d)
          assert(
            got == d,
            s"duty=$d: expected $d high cycles per period, got $got"
          )
        }

        // (4): visual ramp for the waveform viewer. Hold each duty for a
        // few periods so the eye can see the fade in GTKWave.
        for (d <- 0 until period by 8) {
          dut.io.duty #= d
          dut.clockDomain.waitSampling(period)
        }

        println(s"OK: PwmTop duty matches across ${cases.size} test points")
      }
  }
}
