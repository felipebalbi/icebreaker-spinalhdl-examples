package pwm

import spinal.core._
import spinal.lib._

case class PwmTop() extends Component {
  val io = new Bundle {
    val clk = in Bool()
    val led = out Bool()
  }

  val cd = ClockDomain(
    clock = io.clk,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  val area = new ClockingArea(cd) {
    val counter   = Reg(UInt(12 bits)) init(0)
    val duty      = Reg(UInt(12 bits)) init(0)
    val increment = Reg(Bool()) init(True)

    // increment the counter at every cycle
    counter := counter + 1

    // set output (LED) by comparing counter and duty
    io.led := counter < duty

    // whenever counter reaches the maximum value, we want to flip the
    // direction of the duty cycle such that the LED looks like it's
    // breathing.
    when(counter === counter.maxValue) {
      when(increment) {
        when(duty =/= counter.maxValue) {
          duty := duty + 1
        } otherwise {
          increment := False
        }
      } otherwise {
        when(duty =/= 0) {
          duty := duty -1
        } otherwise {
          increment := True
        }
      }
    }
  }
}

object PwmTopVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(PwmTop())
  }
}
