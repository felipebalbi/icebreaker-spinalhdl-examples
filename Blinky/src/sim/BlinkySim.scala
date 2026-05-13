package blinky

import spinal.core._
import spinal.core.sim._

/** Smoke test for [[Blinky]].
  *
  * Synthesises the design with a tiny counter (4 bits) so an MSB toggle occurs
  * every 2^3 = 8 cycles and the test wraps in milliseconds. Runs long enough to
  * observe at least two MSB transitions and asserts the counter actually
  * advances.
  *
  * The toplevel exposes `io.clk` as an ordinary input rather than the implicit
  * clock domain, so the testbench drives the clock by hand.
  */
object BlinkySim {
  def main(args: Array[String]): Unit = {
    val width = 4
    SimConfig.withWave
      .compile(Blinky(counterWidth = width))
      .doSim { dut =>
        dut.io.clk #= false
        sleep(1)

        def tick(): Unit = {
          dut.io.clk #= true; sleep(5)
          dut.io.clk #= false; sleep(5)
        }

        var transitions = 0
        var prev = dut.io.led.toBoolean
        for (_ <- 0 until (1 << width) * 4) {
          tick()
          val cur = dut.io.led.toBoolean
          if (cur != prev) transitions += 1
          prev = cur
        }
        assert(
          transitions >= 2,
          s"led should toggle multiple times, saw $transitions"
        )
        println(s"OK: Blinky toggled $transitions times in 4 periods")
      }
  }
}
