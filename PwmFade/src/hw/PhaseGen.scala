package pwm_fade

import spinal.core._
import spinal.lib._

/** Slow-moving symmetric triangle phase generator.
  *
  * Role The "modulation" timebase. Produces `io.phase` that ramps up from 0 to
  * (2^width - 1) and back, repeatedly. A downstream `Shaper` turns this raw
  * phase into a duty value.
  *
  * Tick contract `io.phase` only updates on cycles where `io.tick` is high.
  * This lets the top decouple the phase update rate from the clock — essential,
  * because the clock also drives the much faster PWM carrier in `PwmCore`. A
  * typical caller pulses `io.tick` once per N clock cycles via a prescaler.
  *
  * Why threshold (>=, <=) and not === With an arbitrary `step`, equality tests
  * against the boundary may never fire (e.g. step=10, width=12 → counter goes
  * 0,10,20,...,4090 then wraps; max=4095 is never hit). Threshold tests +
  * snap-to-boundary guarantee both endpoints are observed and direction
  * reliably flips for any `step` satisfying the `require` below.
  *
  * `require` guards Standard Scala asserts evaluated at elaboration. They abort
  * Verilog generation with a clear error rather than producing silently broken
  * hardware.
  */
case class PhaseGen(width: Int, step: Int = 10) extends Component {
  val io = new Bundle {
    val tick = in Bool ()
    val phase = out UInt (width bits)
  }

  require(step >= 1, "step must be >= 1")
  require(
    (BigInt(1) << width) > 2 * step,
    "step must be small relative to 2^width"
  )

  val maxVal = (BigInt(1) << width) - 1
  val maxU = U(maxVal, width bits)
  val upThr = U(maxVal - step + 1, width bits) // "one step away from max"
  val dnThr = U(step - 1, width bits) // "one step away from 0"

  val counter = Reg(UInt(width bits)) init (0)
  val up = Reg(Bool()) init (True)

  when(io.tick) {
    when(up) {
      // Going up: snap to max if the next step would overshoot, then flip.
      when(counter >= upThr) {
        counter := maxU
        up := False
      } otherwise {
        counter := counter + step
      }
    } otherwise {
      // Going down: symmetric to the up case.
      when(counter <= dnThr) {
        counter := 0
        up := True
      } otherwise {
        counter := counter - step
      }
    }
  }

  io.phase := counter
}
