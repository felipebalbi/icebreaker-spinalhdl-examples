package pwm_fade

import spinal.core._
import spinal.core.sim._

/** Standalone sim for `PhaseGen`.
  *
  * Drives `io.tick` permanently high (so phase advances every cycle) and walks
  * long enough to traverse a full triangle. Asserts that:
  *   - phase reaches both 0 and max, and
  *   - direction (`up`) flips at least once each way.
  *
  * Run: `sbt "runMain pwm_fade.PhaseGenSim"` (or `make sim-phase`).
  */
object PhaseGenSim {
  def main(args: Array[String]): Unit = {

    val width = 8
    val step = 10
    val maxV = (1 << width) - 1

    SimConfig.withWave
      .compile(PhaseGen(width = width, step = step))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.tick #= true

        dut.clockDomain.waitSampling()

        var sawMax = false
        var sawZero = false
        var upFlips = 0
        var downFlips = 0
        var prevUp = true
        var prevPh = BigInt(-1)

        val cycles = 4 * (maxV / step + 1) + 32

        for (_ <- 0 until cycles) {
          dut.clockDomain.waitSampling()
          val ph = dut.io.phase.toBigInt
          if (ph == BigInt(maxV)) sawMax = true
          if (ph == BigInt(0)) sawZero = true

          if (prevPh >= 0) {
            if (prevUp && ph < prevPh) { upFlips += 1; prevUp = false }
            if (!prevUp && ph > prevPh) { downFlips += 1; prevUp = true }
          }
          prevPh = ph
        }

        assert(sawMax, s"phase never reached max=$maxV")
        assert(sawZero, s"phase never reached 0")
        assert(upFlips >= 1, "never flipped from up to down")
        assert(downFlips >= 1, "never flipped from down to up")
        println(
          s"OK: hit max=$sawMax, hit zero=$sawZero, upFlips=$upFlips, downFlips=$downFlips"
        )
      }
  }
}
