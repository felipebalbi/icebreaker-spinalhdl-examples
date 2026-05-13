package pwm

import spinal.core._
import spinal.lib._

/** Counter-comparator pulse-width modulator.
  *
  * The textbook PWM. A free-running unsigned counter wraps every `2^width`
  * cycles; the output is high while `counter < duty` and low otherwise. Average
  * duty cycle is therefore `duty / 2^width` exactly.
  *
  * On the iCEbreaker (12 MHz, `width = 12`) the carrier is `12_000_000 / 4096 ≈
  * 2.93 kHz` — well above the eye's flicker-fusion threshold, so a connected
  * LED looks like a smooth analog brightness whose intensity tracks `io.duty /
  * 4095`.
  *
  * Why no `ClockDomain` is created here:
  *   - Pwm is a leaf component. It uses SpinalHDL's implicit default clock
  *     domain so the synthesised module exposes a single top-level `clk` port.
  *     The icebreaker pcf binds that port to the 12 MHz oscillator on pin 35.
  *   - There is no reset wire: with the default `BOOT` reset kind the iCE40
  *     cold-starts the counter to its `init(0)` value.
  *
  * Why `io.duty` is an *external* input, not internal state:
  *   - In this demo the duty value comes from the PMOD header pins (see
  *     `icebreaker.pcf`) so the brightness can be set with DIP switches or
  *     another peripheral.
  *   - For a self-contained breathing-LED demo see `PwmFade/`, which drives
  *     this same idea from an internal phase generator and waveform shaper.
  *
  * @param width
  *   Width of the duty/counter in bits. PWM resolution is `2^width` steps; the
  *   carrier frequency is `clkFreqHz / 2^width`.
  */
case class PwmTop(width: Int) extends Component {
  require(width >= 1, "width must be >= 1")

  val io = new Bundle {

    /** Desired duty value, in [0, 2^width).
      *
      * `io.pwm` will be high for `duty` cycles out of every `2^width`. `0` is
      * permanently low; `2^width - 1` is high `(2^width - 1) / 2^width` of the
      * time (the strict-less-than comparison means we can never reach 100 %
      * duty without widening the comparison — see PwmFade for that variant).
      */
    val duty = in UInt (width bits)

    /** Modulated output. High while `counter < duty`. Idles low after reset
      * until the first cycle the counter advances.
      */
    val pwm = out Bool ()
  }

  // Free-running counter: wraps naturally at 2^width because UInt
  // arithmetic in SpinalHDL is modular at the declared width. No
  // enable, no reset wire — just count, every cycle, forever.
  val counter = Reg(UInt(width bits)) init (0)
  counter := counter + 1

  io.pwm := counter < io.duty
}

/** Verilog generation entry point.
  *
  * Run via `make` (the Makefile's TOP variable is set to `PwmTop`, so this is
  * what `gen/PwmTop.v` is built from).
  */
object PwmTopVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(PwmTop(12))
  }
}
