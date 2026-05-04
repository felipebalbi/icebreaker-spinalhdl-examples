package pwm

import spinal.core._
import spinal.core.sim._
import spinal.sim._

object PwmSim {
  def main(args: Array[String]): Unit = {

    val width = 8

    SimConfig
      .withWave
      .compile(PwmTop(width))
      .doSim { dut =>

        // Clock (since Pwm has implicit clock domain)
        dut.clockDomain.forkStimulus(period = 10)

        // Sweep duty cycle quickly
        for (i <- 0 until (1 << width)) {
          dut.io.duty #= i
          sleep(50)   // fast sweep
        }

        sleep(500)
      }
  }
}
