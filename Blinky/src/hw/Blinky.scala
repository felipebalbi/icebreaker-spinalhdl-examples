package blinky

import spinal.core._

/** Minimal "hello world" for the iCE40: divide the 12 MHz board clock with a
  * free-running counter and route its MSB to the on-board LED.
  *
  * The Lattice iCE40 used on the iCEBreaker has no global asynchronous reset
  * pin available to user logic. We therefore use Spinal's `BOOT` reset kind so
  * registers come up cleared from the bitstream's initial state and we don't
  * need to wire a reset net of our own. See [[BlinkyWithReset]] for the
  * variant that exposes an explicit asynchronous, active-low reset.
  *
  * The toplevel exposes `io.clk` as a plain `Bool` (rather than relying on
  * Spinal's implicit clock) so the generated Verilog port is named `io_clk`,
  * matching `icebreaker.pcf`.
  *
  * @param counterWidth Width of the divider counter. Synthesis builds use the
  *                     default of 25 bits which gives roughly a 1.4 s period
  *                     at 12 MHz; simulation can override this to keep tests
  *                     fast.
  */
case class Blinky(counterWidth: Int = 25) extends Component {
  val io = new Bundle {

    /** 12 MHz board clock (pcf maps to pin 35). */
    val clk = in Bool ()

    /** Toggling LED output, driven by the MSB of the divider. */
    val led = out Bool ()
  }

  // Build an explicit ClockDomain so the toplevel port is `io_clk` (matching
  // the PCF) instead of Spinal's default `clk`. BOOT reset means registers
  // come up at their `init` values from the bitstream — no reset net is
  // generated, which suits the iCE40 (no global async-reset pin).
  val mainClockDomain = ClockDomain(
    clock = io.clk,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = BOOT
    )
  )

  val core = new ClockingArea(mainClockDomain) {
    val counter = Reg(UInt(counterWidth bits)) init (0)
    counter := counter + 1
  }

  io.led := core.counter.msb
}

/** Spinal entry point for `make verilog` / `make`. */
object BlinkyVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      defaultClockDomainFrequency = FixedFrequency(12 MHz),
      targetDirectory = "gen"
    ).generateVerilog(Blinky())
  }
}
