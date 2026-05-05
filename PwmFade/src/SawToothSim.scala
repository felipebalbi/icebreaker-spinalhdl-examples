package pwm_fade

import spinal.core._
import spinal.core.sim._

object SawToothSim {
  def main(args: Array[String]): Unit = {

    val width = 8
    val step  = 10
    val maxV  = (1 << width) - 1

    SimConfig
      .withWave
      .compile(SawTooth(width = width, step = step))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.tick #= true

        // Wait for first rising edge so init values are visible.
        dut.clockDomain.waitSampling()

        var sawMax    = false
        var sawZero   = false
        var upFlips   = 0
        var downFlips = 0
        var prevUp    = true
        var prevDuty  = BigInt(-1)

        // One full triangle = ~ 2 * (max / step) ticks. Add slack.
        val cycles = 4 * (maxV / step + 1) + 32

        for (_ <- 0 until cycles) {
          dut.clockDomain.waitSampling()
          val duty = dut.io.duty.toBigInt
          if (duty == BigInt(maxV)) sawMax  = true
          if (duty == BigInt(0))    sawZero = true

          // Detect up flips (true -> false) and down flips (false -> true)
          // by sampling an internal signal-equivalent: derivative of duty.
          if (prevDuty >= 0) {
            if (prevUp && duty < prevDuty) { upFlips   += 1; prevUp = false }
            if (!prevUp && duty > prevDuty) { downFlips += 1; prevUp = true  }
          }
          prevDuty = duty
        }

        assert(sawMax,         s"duty never reached max=$maxV")
        assert(sawZero,        s"duty never reached 0")
        assert(upFlips   >= 1, "never flipped from up to down")
        assert(downFlips >= 1, "never flipped from down to up")
        println(s"OK: hit max=$sawMax, hit zero=$sawZero, upFlips=$upFlips, downFlips=$downFlips")
      }
  }
}
