package button

import spinal.core._
import spinal.core.sim._

/** Standalone sim for [[ButtonTop]].
  *
  * What we verify
  *   1. The LED stays low until the first rising edge of the (synchronised)
  *      `pressed` signal.
  *   2. Every press of `io.btn` (line driven LOW) toggles the LED — but only
  *      after `BufferCC`'s 2-cycle synchroniser delay has elapsed.
  *   3. *Holding* the button does not toggle the LED again. Only the
  *      transition from "not pressed" to "pressed" counts.
  *   4. Releases (line returning HIGH) do not toggle the LED.
  *
  * Notes on timing
  *   - The toplevel exposes `io.clk` as an ordinary input rather than the
  *     implicit clock domain, and uses BOOT reset on the explicit `cd`.
  *     `cd.forkStimulus` doesn't reliably propagate the BOOT init values
  *     in Verilator for this configuration, so we drive `io.clk` by hand
  *     (the same pattern `BlinkySim` uses).
  *   - `BufferCC` defaults to a 2-flop synchroniser, so a transition on
  *     `io.btn` shows up on `pressedSync` two cycles later. The edge
  *     detector then needs one more cycle to register the previous-vs-
  *     current diff. We tick `>= 4` times per press to be safe.
  *   - We deliberately do *not* model bounce here — `ButtonTop` doesn't
  *     debounce, so a bouncy stimulus would (correctly) toggle the LED
  *     several times per "press". See `ButtonDebouncer/` for that case.
  *
  * Run: `sbt "runMain button.ButtonTopSim"` (or `make sim`).
  */
object ButtonTopSim {
  def main(args: Array[String]): Unit = {

    SimConfig.withWave
      .compile(ButtonTop())
      .doSim { dut =>
        // Pre-drive io.btn to its idle electrical level (HIGH = pulled up,
        // not pressed) and io.clk LOW before any sim time passes, so the
        // BufferCC chain never observes an `x` on its first sample.
        dut.io.btn #= true
        dut.io.clk #= false
        sleep(1)

        def tick(n: Int = 1): Unit = {
          for (_ <- 0 until n) {
            dut.io.clk #= true; sleep(5)
            dut.io.clk #= false; sleep(5)
          }
        }

        tick(8) // settle the BufferCC chain
        assert(!dut.io.led.toBoolean, "LED should be low after reset")

        // (1) Drive io.btn LOW: rising edge of `pressed` must toggle LED HIGH.
        dut.io.btn #= false
        tick(8)
        assert(
          dut.io.led.toBoolean,
          "LED should be HIGH after first press"
        )

        // (2) Continue holding (still LOW): no new rising edge, LED stays HIGH.
        tick(20)
        assert(
          dut.io.led.toBoolean,
          "LED should remain HIGH while button is held"
        )

        // (3) Release (drive HIGH): falling edge of `pressed` must NOT toggle.
        dut.io.btn #= true
        tick(8)
        assert(
          dut.io.led.toBoolean,
          "LED should remain HIGH after release (falling edge ignored)"
        )

        // (4) Second clean press toggles back off.
        press(dut, tick)
        assert(
          !dut.io.led.toBoolean,
          "LED should be LOW after second rising edge"
        )

        // Many more presses: confirm parity tracks press count.
        for (n <- 1 to 5) {
          press(dut, tick)
          val want = (n % 2) == 1
          assert(
            dut.io.led.toBoolean == want,
            s"after $n more presses, expected led=$want got ${dut.io.led.toBoolean}"
          )
        }

        println("OK: ButtonTop toggles LED on rising edges only")
      }
  }

  /** One full press-and-release cycle. Mirrors the board electrically: io.btn
    * is pulled HIGH at idle and shorts to GND when pressed. Drive LOW to
    * press, HIGH to release; tick long enough between transitions for the
    * BufferCC synchroniser + edge detector to register.
    */
  private def press(dut: ButtonTop, tick: Int => Unit): Unit = {
    dut.io.btn #= false
    tick(8)
    dut.io.btn #= true
    tick(8)
  }
}
