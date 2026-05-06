package uart_tx

import spinal.core._
import spinal.core.sim._
import spinal.sim._

object TxShiftRegSim {
  def main(args: Array[String]): Unit = {

    val cfg = UartTxConfig(dataBits = 8)

    SimConfig.withWave
      .compile(TxShiftReg(cfg))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        // Init
        dut.io.load #= false
        dut.io.shift #= false
        dut.io.data #= 0

        dut.clockDomain.waitSampling(2)

        // Load
        val testByte = 0xad
        dut.io.data #= testByte
        dut.io.load #= true
        dut.clockDomain.waitSampling() // 1 cycle pulse
        dut.io.load #= false

        println(f"Loaded byte = 0x$testByte%02X")

        // IMPORTANT: sample on a known phase (after sampling edge)
        dut.clockDomain.waitSampling()

        // Shift it out, LSB first
        for (i <- 0 until 8) {

          // Sample BEFORE issuing the next shift
          val bit = dut.io.bit.toBoolean
          println(s"Shift $i -> bit = $bit")

          // 1-cycle shift pulse
          dut.io.shift #= true
          dut.clockDomain.waitSampling()
          dut.io.shift #= false
          dut.clockDomain.waitSampling()
        }

        dut.clockDomain.waitSampling(5)
      }

  }
}
