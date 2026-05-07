package button_debouncer

import spinal.core._

/** Top-level: clock domain + debouncer + LED toggle on each clean press.
  *
  * Drop-in replacement for the `Button/` example, but the rising-edge
  * pulse comes from a [[Debouncer]] instead of a naive sample-and-XOR.
  * The result is a single LED toggle per real press regardless of how
  * bouncy the mechanical contact is.
  *
  * Configurability happens entirely at elaboration time via
  * [[DebouncerConfig]]: change `kind` between `Integrator` and `Timer`,
  * regenerate, reflash, no other code edits required. The two
  * implementations have visibly different "feel" if you flash them in
  * sequence — Integrator is snappier on a clean button; Timer is more
  * forgiving of really chattery contacts.
  *
  * Clock domain Same `BOOT`-reset 12 MHz domain as the rest of the
  * examples. iCE40 has no global async-reset pin; registers cold-start
  * to their `init(...)` values.
  */
case class ButtonTop(cfg: DebouncerConfig) extends Component {
  val io = new Bundle {

    /** Free-running 12 MHz clock from the iCEbreaker (pcf maps to pin 35). */
    val clk = in Bool ()

    /** Raw button input (pcf maps to pin 10). Asynchronous, bouncy.
      * Active-high in this design.
      */
    val btn = in Bool ()

    /** LED output (pcf maps to pin 11). Toggles on each *debounced* rising
      * edge. Bounces should be invisible to it.
      */
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

/** Verilog generation entry point.
  *
  * Edit `cfg` to pick between Integrator and Timer debouncers; both ship
  * identical IO so `ButtonTop` does not need to change.
  */
object ButtonTopVerilog {
  def main(args: Array[String]): Unit = {

    val cfg = DebouncerConfig(
      kind = DebounceKind.Timer, // change here to test (Integrator | Timer)
      clkFreqHz = 12_000_000,
      debounceMs = 10,
      width = 16
    )

    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(ButtonTop(cfg))
  }
}
