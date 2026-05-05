package pwm_fade

import spinal.core._
import spinal.lib._

/** Top-level: clock domain + prescaler + modulator + PWM carrier.
  *
  * Two timebases derived from one physical clock
  *
  *   - PWM carrier (fast): `PwmCore` counts every cycle and wraps
  *     at 2^width. At 12 MHz, width=12 => ~2.93 kHz PWM (invisible
  *     to the eye, perfect for analog brightness).
  *
  *   - Duty modulation (slow): a free-running `prescaler` of
  *     `prescalerWidth` bits generates a 1-cycle `tick` pulse every
  *     2^prescalerWidth cycles. The `Modulator` only updates its
  *     phase on those ticks, so the duty value stays stable for
  *     many PWM periods and the carrier can faithfully represent it.
  *
  *   Visual breath rate (full triangle):
  *     `(phase_steps_per_ramp * 2) * (prescaler_period / clock_freq)`
  *     With width=12, step=10, prescalerWidth=15, 12 MHz:
  *       phase_steps_per_ramp = 4096 / 10 ~= 410
  *       prescaler_period     = 32768 cycles ~= 2.73 ms
  *       full breath          ~= 410 * 2 * 2.73 ms ~= 2.24 s.
  *
  * `BOOT` reset
  *   iCE40 has no global async reset pin; `BOOT` tells SpinalHDL to
  *   rely on FPGA cold-start values (the `init(...)` we wrote on
  *   each Reg). No reset wire is generated.
  *
  * Swapping waveforms
  *   Change `kind` (and optionally `gamma`) in `PwmFadeTopVerilog.main`,
  *   `make`, `make flash`. Done. Also see PwmFade/README.md.
  */
case class PwmFadeTop(cfg: ModulatorConfig, prescalerWidth: Int = 15) extends Component {
  val io = new Bundle {
    val clk = in Bool()
    val pwm = out Bool()
  }

  val cd = ClockDomain(
    clock = io.clk,
    config = ClockDomainConfig(
      resetKind = BOOT,
    )
  )

  val area = new ClockingArea(cd) {
    val mod = Modulator(cfg)
    val pwm = PwmCore(cfg.width)

    // Prescaler: pulses io.tick once every 2^prescalerWidth cycles so the
    // duty changes much more slowly than one PWM period (2^width cycles).
    val prescaler = Reg(UInt(prescalerWidth bits)) init(0)
    prescaler := prescaler + 1
    mod.io.tick := prescaler === 0

    pwm.io.duty := mod.io.duty
    io.pwm := pwm.io.pwm
  }
}

/** Verilog generation entry point. Edit `cfg` to pick a waveform. */
object PwmFadeTopVerilog {
  def main(args: Array[String]): Unit = {

    val cfg = ModulatorConfig(
      kind  = ShaperKind.Gamma,   // change here to test (Identity/Sine/Gamma)
      width = 12,
      step  = 10,
      gamma = 2.2
    )

    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(PwmFadeTop(cfg))
  }
}
