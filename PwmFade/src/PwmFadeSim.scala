package pwm_fade

import spinal.core._
import spinal.core.sim._
import spinal.sim._

object PwmFadeSim {
  def main(args: Array[String]): Unit = {

    val width = 8

    SimConfig
      .withWave
      .compile(PwmFadeTop(width))
      .doSim { dut =>

        // Clock (since Pwm has implicit clock domain)
        dut.clockDomain.forkStimulus(period = 10)

        sleep(100000)
      }
  }
}
