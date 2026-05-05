package pwm_fade

import spinal.core._
import spinal.lib._

case class PwmFadeTop(width: Int) extends Component {
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

    pwm.io.duty := saw.io.duty
    io.pwm := pwm.io.pwm
  }
}

object PwmFadeTopVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(PwmFadeTop(12))
  }
}
