package blinky

import spinal.core._

/** Blinky variant that exposes an explicit asynchronous, active-low reset pin
  * in addition to the board clock.
  *
  * The reset is wired to the iCEbreaker user button via `icebreaker-reset.pcf`
  * (pin 10). The button pulls the line high through a pull-up and shorts it
  * to ground when pressed, so "reset asserted" means the pin is at 0 V — hence
  * `resetActiveLevel = LOW`. The button is not synchronous to anything, so
  * `resetKind = ASYNC` (Spinal still synchronises the *deassertion* via a
  * BufferCC on the reset path).
  *
  * The toplevel ports map cleanly to PCF entries: `io_clk`, `io_reset`,
  * `io_led`.
  *
  * @param counterWidth Width of the divider counter. See [[Blinky]] for the
  *                     synthesis vs. simulation trade-off.
  */
case class BlinkyWithReset(counterWidth: Int = 25) extends Component {
  val io = new Bundle {

    /** 12 MHz board clock (pcf maps to pin 35). */
    val clk = in Bool ()

    /** Active-LOW reset from the iCEbreaker user button (pcf maps to pin 10).
      * Hold the button to keep the counter clamped at 0; release to run.
      */
    val reset = in Bool ()

    /** Toggling LED output, driven by the MSB of the divider. */
    val led = out Bool ()
  }

  val mainClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = LOW
    )
  )

  val core = new ClockingArea(mainClockDomain) {
    val counter = Reg(UInt(counterWidth bits)) init (0)
    counter := counter + 1
  }

  io.led := core.counter.msb
}

/** Spinal entry point for `make verilog TOP=BlinkyWithReset`. */
object BlinkyWithResetVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      defaultClockDomainFrequency = FixedFrequency(12 MHz),
      targetDirectory = "gen"
    ).generateVerilog(BlinkyWithReset())
  }
}
