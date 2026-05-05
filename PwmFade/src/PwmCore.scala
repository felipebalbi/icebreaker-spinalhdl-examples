package pwm_fade

import spinal.core._
import spinal.lib._

case class PwmCore(width: Int) extends Component {
  val io = new Bundle {
    val duty = in UInt(width bits)
    val pwm  = out Bool()
  }

  val counter = Reg(UInt(width bits)) init(0)
  counter := counter + 1
  io.pwm := counter < io.duty
}

