package pwm_fade

import spinal.core._
import spinal.lib._

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
