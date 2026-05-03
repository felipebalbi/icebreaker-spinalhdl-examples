package button

import spinal.core._
import spinal.lib._

case class ButtonTop() extends Component {
  val io = new Bundle {
    val clk = in Bool()
    val btn = in Bool()
    val led = out Bool()
  }

  // Single clock domain (12 MHz)
  val cd = ClockDomain(
    clock = io.clk,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  val area = new ClockingArea(cd) {
    val btnSync = BufferCC(io.btn)
    val btnPrev = RegNext(btnSync)

    // Rising edge detection (naive)
    val rising = io.btn && !btnPrev

    // LED state
    val ledReg = Reg(Bool()) init(False)

    when(rising) {
      ledReg := !ledReg
    }

    io.led := ledReg
  }
}

object ButtonTopVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(ButtonTop())
  }
}
