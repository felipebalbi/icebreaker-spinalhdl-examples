package button_debouncer

import spinal.core._
import spinal.core.sim._

/** End-to-end smoke sim for [[ButtonTop]].
  *
  * Wires up the full top with a small Timer debouncer (8-cycle window) and
  * verifies the headline behaviour: bouncy presses produce *one* LED toggle
  * each, not many.
  *
  * Strategy
  *   - Construct `ButtonTop` with `DebouncerConfig(Timer, ...)` sized for a
  *     quick sim (window = 8 cycles).
  *   - Drive a bouncy "press": several fast toggles followed by a long
  *     steady-high. Assert the LED toggles exactly once.
  *   - Drive a bouncy "release": same shape but ending steady-low. Assert the
  *     LED does NOT toggle (rising-edge only).
  *   - Repeat to confirm the toggle parity tracks the press count.
  *
  * Run: `sbt "runMain button_debouncer.ButtonTopSim"`
  */
object ButtonTopSim {
  def main(args: Array[String]): Unit = {

    // 8-cycle debounce window; matches TimerDebouncerSim numbers.
    val cfg = DebouncerConfig(
      kind = DebounceKind.Timer,
      clkFreqHz = 8000,
      debounceMs = 1,
      width = 4 // unused for Timer kind
    )
    val window = 8

    SimConfig.withWave
      .compile(ButtonTop(cfg))
      .doSim { dut =>
        val cd = dut.cd
        cd.forkStimulus(period = 10)

        dut.io.btn #= false
        cd.waitSampling(window + 8)
        assert(!dut.io.led.toBoolean, "LED should start low")

        def bouncyPress(): Unit = {
          // Bouncy phase: alternate fast.
          for (_ <- 0 until 5) {
            dut.io.btn #= true
            cd.waitSampling(window - 2)
            dut.io.btn #= false
            cd.waitSampling(window - 2)
          }
          // Then hold high long enough to commit.
          dut.io.btn #= true
          cd.waitSampling(window + 8)
        }
        def bouncyRelease(): Unit = {
          for (_ <- 0 until 5) {
            dut.io.btn #= false
            cd.waitSampling(window - 2)
            dut.io.btn #= true
            cd.waitSampling(window - 2)
          }
          dut.io.btn #= false
          cd.waitSampling(window + 8)
        }

        for (n <- 1 to 4) {
          bouncyPress()
          val want = (n % 2) == 1
          assert(
            dut.io.led.toBoolean == want,
            s"after $n bouncy presses, expected led=$want got ${dut.io.led.toBoolean}"
          )
          // Release: must not toggle the LED.
          val before = dut.io.led.toBoolean
          bouncyRelease()
          assert(
            dut.io.led.toBoolean == before,
            "release toggled the LED — should be press-only"
          )
        }

        println("OK: ButtonTop debounces and toggles on press only")
      }
  }
}
