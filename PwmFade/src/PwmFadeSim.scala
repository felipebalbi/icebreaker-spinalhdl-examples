package pwm_fade

import spinal.core._
import spinal.core.sim._
import spinal.sim._

object PwmFadeSim {
  def main(args: Array[String]): Unit = {

    // Use a small width and tiny prescaler so a full breath fits in sim.
    val width          = 8
    val prescalerWidth = 2

    SimConfig
      .withWave
      .compile(PwmFadeTop(width = width, prescalerWidth = prescalerWidth))
      .doSim { dut =>

        // Clock (since Pwm has implicit clock domain)
        dut.clockDomain.forkStimulus(period = 10)

        sleep(100000)
      }
  }
}
