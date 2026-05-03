import spinal.core._

case class Blinky() extends Component {
  val io = new Bundle {
    val clk = in Bool()
    val led = out Bool()
  }

  val cd = ClockDomain(
    clock = io.clk,
    config = ClockDomainConfig(
      resetKind = BOOT
    )
  )

  val area = new ClockingArea(cd) {
    val counter = Reg(UInt(25 bits)) init(0)
    counter := counter + 1
    io.led := counter.msb
  }
}

object BlinkyVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "rtl"
    ).generateVerilog(Blinky())
  }
}
