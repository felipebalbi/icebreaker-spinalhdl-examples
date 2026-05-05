package pwm_fade

import spinal.core._
import spinal.lib._

/** Counter-comparator PWM generator.
  *
  * Role
  *   The PWM "carrier". Produces `io.pwm` whose duty ratio equals
  *   `io.duty / 2^width`. With `width=12` and a 12 MHz clock the
  *   period is 4096 cycles = ~341 us = ~2.93 kHz, well above the
  *   eye's flicker-fusion threshold so an LED looks like a smooth
  *   analog brightness.
  *
  * How it works
  *   A free-running unsigned counter increments every clock cycle and
  *   wraps naturally at 2^width (UInt arithmetic in SpinalHDL is
  *   modular at the declared width). Each cycle, `io.pwm` is high
  *   while `counter < io.duty` and low otherwise — a textbook PWM.
  *
  * Notes
  *   - `Reg(...) init(0)` declares a register with a known reset
  *     value. Combined with `BOOT` reset in the top, the iCE40 cold
  *     starts the counter at 0.
  *   - The counter is unconditional (no enable). It MUST run at the
  *     full clock; the PWM frequency is derived from it.
  */
case class PwmCore(width: Int) extends Component {
  val io = new Bundle {
    val duty = in UInt(width bits)
    val pwm  = out Bool()
  }

  val counter = Reg(UInt(width bits)) init(0)
  counter := counter + 1
  io.pwm := counter < io.duty
}

