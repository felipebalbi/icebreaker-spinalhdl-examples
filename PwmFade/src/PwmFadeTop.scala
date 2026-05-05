package pwm_fade

import spinal.core._
import spinal.lib._

case class PwmFadeTop(width: Int, prescalerWidth: Int = 15) extends Component {
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
    val saw = SawTooth(width)
    val pwm = PwmCore(width)

    // Prescaler: pulses io.tick once every 2^prescalerWidth cycles so the
    // duty changes much more slowly than one PWM period (2^width cycles).
    val prescaler = Reg(UInt(prescalerWidth bits)) init(0)
    prescaler := prescaler + 1
    saw.io.tick := prescaler === 0

    pwm.io.duty := saw.io.duty
    io.pwm := pwm.io.pwm
  }
}

object PwmFadeTopVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(PwmFadeTop(width = 12, prescalerWidth = 15))
  }
}
