import spinal.core._

case class BlinkyWithReset() extends Component {
  val io = new Bundle {
    val clk = in Bool ()
    val reset = in Bool ()
    val led = out Bool ()
  }

  val cd = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      resetKind = SYNC,
      resetActiveLevel = LOW
    )
  )

  val area = new ClockingArea(cd) {
    val counter = Reg(UInt(24 bits)) init (0)
    counter := counter + 1
    io.led := counter.msb
  }
}

object BlinkyWithResetVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "rtl"
    ).generateVerilog(BlinkyWithReset())
  }
}
