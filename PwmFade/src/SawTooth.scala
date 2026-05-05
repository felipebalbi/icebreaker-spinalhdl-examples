package pwm_fade

import spinal.core._
import spinal.lib._

case class SawTooth(width: Int, step: Int = 10) extends Component {
  val io = new Bundle {
    val tick = in  Bool()
    val duty = out UInt(width bits)
  }

  require(step >= 1, "step must be >= 1")
  require((BigInt(1) << width) > 2 * step, "step must be small relative to 2^width")

  val maxVal = (BigInt(1) << width) - 1
  val maxU   = U(maxVal,            width bits)
  val upThr  = U(maxVal - step + 1, width bits)
  val dnThr  = U(step - 1,          width bits)

  val counter = Reg(UInt(width bits)) init(0)
  val up      = Reg(Bool())           init(True)

  when(io.tick) {
    when(up) {
      when(counter >= upThr) {
        counter := maxU
        up      := False
      } otherwise {
        counter := counter + step
      }
    } otherwise {
      when(counter <= dnThr) {
        counter := 0
        up      := True
      } otherwise {
        counter := counter - step
      }
    }
  }

  io.duty := counter
}
