package button

import spinal.core._

case class ButtonTop(cfg: DebouncerConfig) extends Component {
  val io = new Bundle {
    val clk = in Bool ()
    val btn = in Bool ()
    val led = out Bool ()
  }

  val cd = ClockDomain(
    clock = io.clk,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  val area = new ClockingArea(cd) {
    val debouncer = Debouncer(cfg)
    debouncer.io.raw := io.btn

    val ledReg = Reg(Bool()) init (False)

    when(debouncer.io.rising) {
      ledReg := !ledReg
    }

    io.led := ledReg
  }
}

object ButtonTopVerilog {
  def main(args: Array[String]): Unit = {

    val cfg = DebouncerConfig(
      kind = DebounceKind.Timer, // change here to test
      clkFreqHz = 12_000_000,
      debounceMs = 10
      // kind = DebounceKind.Integrator,
      // width = 16,
    )

    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(ButtonTop(cfg))
  }
}
