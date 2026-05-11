package i2c

import spinal.core._
import spinal.lib._
import spinal.core.sim._

/** Sim-only Component that wires two `I2cIo` slaves through a perfect
  * wired-AND.
  *
  * Models the electrical reality of an I²C segment: any device pulling
  * the line low wins, otherwise the external pull-up holds it high.
  * With `ReadableOpenDrain.write = False` meaning "pull low", the
  * combinational rule is simply `bus = a.write & b.write`.
  *
  * Reused by every later sim that needs to glue a controller and a
  * target together (`I2cBitControllerSim`, `I2cTargetFsmSim`, …)
  * without reaching for an external chip model.
  */
class I2cIoBus extends Component {
  val io = new Bundle {
    val a = slave(I2cIo())
    val b = slave(I2cIo())
  }
  val sclBus = io.a.scl.write & io.b.scl.write
  val sdaBus = io.a.sda.write & io.b.sda.write
  io.a.scl.read := sclBus
  io.b.scl.read := sclBus
  io.a.sda.read := sdaBus
  io.b.sda.read := sdaBus
}

/** Smoke-test [[I2cIo]] + [[I2cIoBus]].
  *
  * Most of `I2cIo` is wires, so this sim has two jobs:
  *   1. Confirm the wired-AND model in `I2cIoBus` matches the truth
  *      table for an open-drain segment with two participants.
  *   2. Exercise the `I2cIoBus` helper itself, since every later sim
  *      depends on it being correct.
  *
  * What we check
  *   - Both ends release      → both lines high.
  *   - One end pulls SCL      → SCL low, SDA high.
  *   - Other end pulls SDA    → SDA low, SCL high.
  *   - Both ends pull both    → both low (no contention; that's the
  *     wired-AND rule, not a bug).
  *   - Mixed pulls            → both low.
  *   - `releaseAll` shorthand → both lines high.
  *
  * Run: `sbt "runMain i2c.I2cIoSim"`
  */
object I2cIoSim {
  def main(args: Array[String]): Unit = {
    SimConfig.withWave.compile(new I2cIoBus).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Drive both ends, advance one cycle, then assert that both
      // sides see the same bus state -- the wired-AND fan-out has to
      // reach every participant.
      def step(
        aScl: Boolean,
        aSda: Boolean,
        bScl: Boolean,
        bSda: Boolean,
        expScl: Boolean,
        expSda: Boolean,
        label: String
      ): Unit = {
        dut.io.a.scl.write #= aScl
        dut.io.a.sda.write #= aSda
        dut.io.b.scl.write #= bScl
        dut.io.b.sda.write #= bSda
        dut.clockDomain.waitSampling()
        assert(
          dut.io.a.scl.read.toBoolean == expScl,
          s"$label: a.scl expected $expScl, got ${dut.io.a.scl.read.toBoolean}"
        )
        assert(
          dut.io.a.sda.read.toBoolean == expSda,
          s"$label: a.sda expected $expSda, got ${dut.io.a.sda.read.toBoolean}"
        )
        assert(
          dut.io.b.scl.read.toBoolean == expScl,
          s"$label: b.scl expected $expScl, got ${dut.io.b.scl.read.toBoolean}"
        )
        assert(
          dut.io.b.sda.read.toBoolean == expSda,
          s"$label: b.sda expected $expSda, got ${dut.io.b.sda.read.toBoolean}"
        )
      }

      // Both released: bus floats high through the (modelled) pull-up.
      step(true, true, true, true, true, true, "both released")

      // A pulls SCL only.
      step(false, true, true, true, false, true, "A pulls SCL")

      // B pulls SDA only.
      step(true, true, true, false, true, false, "B pulls SDA")

      // Both pull both: the wired-AND rule says low wins, no error.
      step(false, false, false, false, false, false, "both pull both")

      // Mixed: A pulls SCL, B pulls SDA. Both lines end up low because
      // each line independently sees at least one pull-down.
      step(false, true, true, false, false, false, "mixed pulls")

      // Re-release everything; back to idle bus.
      step(true, true, true, true, true, true, "back to idle")

      println("OK: I2cIo wired-AND behaves correctly")
    }
  }
}
