package button

import spinal.core._
import spinal.lib._

/** Button-toggles-LED top.
  *
  * One of the smallest "real input -> real output" designs you can build on the
  * iCE40. Pressing the iCEbreaker user button toggles the on-board LED.
  *
  * What this teaches:
  *   - **Async input synchronisation.** The button is wired straight to a
  *     physical pin and is asynchronous to our 12 MHz clock. Sampling it
  *     directly into clocked logic risks metastability — a flip-flop captured
  *     mid-transition can settle to either value and, worse, propagate a
  *     glitch to fan-out that disagrees about which value it saw. `BufferCC`
  *     inserts a chain of registers to make the metastable window vanishingly
  *     improbable before any consumer sees the signal.
  *   - **Boundary inversion for active-low inputs.** The iCEbreaker user
  *     button is electrically active-LOW (pulled high by a PCB pull-up,
  *     shorts to ground when pressed). We invert it once at the boundary
  *     (`val pressed = !io.btn`) so the rest of the logic — synchroniser,
  *     edge detector, toggle — works in natural "1 = pressed" terms and
  *     `init = False` reads as "not pressed at boot".
  *   - **Edge detection.** Using a level-sensitive button would toggle the LED
  *     once per *cycle* the button was held, i.e. millions of times per press.
  *     We register the synchronised value and assert "rising edge" only when
  *     the *current* sample is high and the *previous* one was low.
  *   - **No debouncing.** A real mechanical button bounces for ~ms after each
  *     press, producing dozens of spurious edges. This example happily counts
  *     them all — the LED flickers and lands in an unpredictable state. See
  *     `ButtonDebouncer/` for the proper fix.
  *
  * Clock domain
  *   - The iCE40 has no global async-reset pin, so we configure the clock
  *     domain with `resetKind = BOOT`: registers are cold-started to their
  *     `init(...)` values and no reset wire is generated.
  */
case class ButtonTop() extends Component {
  val io = new Bundle {

    /** Free-running 12 MHz clock from the iCEbreaker (pcf maps to pin 35). */
    val clk = in Bool ()

    /** Raw button input (pcf maps to pin 10). Asynchronous, bouncy, and
      * **electrically active-LOW**: the iCEbreaker user button is pulled
      * high through a PCB pull-up and shorts to ground when pressed. We
      * invert it once at the boundary (`val pressed = !io.btn`) so the
      * rest of the design works in *logical* "pressed = 1" terms.
      */
    val btn = in Bool ()

    /** LED output (pcf maps to pin 11). Toggles on each detected rising
      * edge of the synchronised button signal.
      */
    val led = out Bool ()
  }

  // Single 12 MHz clock domain. BOOT reset means registers cold-start to
  // their `init(...)` values and no reset wire is generated.
  val cd = ClockDomain(
    clock = io.clk,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  val area = new ClockingArea(cd) {
    // Boundary inversion: io.btn is electrically active-LOW (pulled high,
    // shorts to ground when pressed). Convert to a logical "pressed"
    // signal here so all downstream logic — synchroniser, edge detector,
    // toggle — speaks the natural "1 = pressed" language and `init = False`
    // means "not pressed at boot".
    val pressed = !io.btn

    // Cross the async (now active-high) signal into our clock domain.
    // `BufferCC` defaults to a 2-flop synchroniser, which is plenty for a
    // slow human-pressed button. `init = False` pins both stages to "not
    // pressed" out of BOOT reset so the very first cycle cannot fabricate
    // a phantom rising edge from `x` propagation in simulation.
    val pressedSync = BufferCC(pressed, init = False)

    // Previous-cycle value of the *synchronised* press signal. Used for
    // the edge detector below.
    val pressedPrev = RegNext(pressedSync) init (False)

    // Rising-edge detection on the synchronised signal: pressed this
    // cycle, not pressed last cycle. Must use `pressedSync` (not
    // `pressed` directly!) here — pulling straight from the async-derived
    // signal would compare a metastable sample against a clean registered
    // one and is the classic CDC bug.
    val rising = pressedSync && !pressedPrev

    // The actual LED state register, toggled on each detected rising edge.
    val ledReg = Reg(Bool()) init (False)
    when(rising) {
      ledReg := !ledReg
    }

    io.led := ledReg
  }
}

/** Verilog generation entry point.
  *
  * Run via `make` (the Makefile's TOP variable is `ButtonTop`, so this is what
  * `gen/ButtonTop.v` is built from).
  */
object ButtonTopVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(ButtonTop())
  }
}
