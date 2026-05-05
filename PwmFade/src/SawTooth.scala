package pwm_fade

import spinal.core._
import spinal.lib._

case class SawTooth(width: Int, step: Int = 10) extends Component {
  val io = new Bundle {
    val duty = out UInt(width bits)
  }

  val counter = Reg(UInt(width bits)) init(0)
  val up = Reg(Bool()) init(True)

  val max = U((BigInt(1) << width) - 1)

  when(up) {
    counter := counter + step
    when(counter === max) {
      up := False
    }
  } otherwise {
    counter := counter - step
    when(counter === 0) {
      up := True
    }
  }

  io.duty := counter
}
