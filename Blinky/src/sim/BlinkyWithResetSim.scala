package blinky

import spinal.core._
import spinal.core.sim._

/** Smoke test for [[BlinkyWithReset]].
  *
  * Verifies that:
  *   1. While reset is asserted (low), the counter stays at 0 and the LED is low.
  *   2. After deassertion the counter advances and the LED eventually toggles.
  *
  * The toplevel exposes `io.clk` as an ordinary input rather than the
  * implicit clock domain, so the testbench has to drive the clock by hand.
  */
object BlinkyWithResetSim {
  def main(args: Array[String]): Unit = {
    val width = 4
    SimConfig.withWave
      .compile(BlinkyWithReset(counterWidth = width))
      .doSim { dut =>
        dut.io.reset #= false // assert reset (active low)
        dut.io.clk #= false
        sleep(1)

        def tick(): Unit = {
          dut.io.clk #= true; sleep(5)
          dut.io.clk #= false; sleep(5)
        }

        // 1) Hold in reset for a while; LED must remain low.
        for (_ <- 0 until 16) {
          tick()
          assert(!dut.io.led.toBoolean, "led should stay low while reset is asserted")
        }

        // 2) Deassert reset and look for at least two MSB transitions.
        dut.io.reset #= true
        var transitions = 0
        var prev = dut.io.led.toBoolean
        for (_ <- 0 until (1 << width) * 4) {
          tick()
          val cur = dut.io.led.toBoolean
          if (cur != prev) transitions += 1
          prev = cur
        }
        assert(transitions >= 2, s"led should toggle after reset release, saw $transitions")
        println(s"OK: BlinkyWithReset stayed quiet during reset and toggled $transitions times after release")
      }
  }
}
