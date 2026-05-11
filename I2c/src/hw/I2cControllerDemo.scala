package i2c

import spinal.core._
import spinal.lib._

case class I2cControllerDemo(
    cfg: I2cConfig = I2cConfig(
      clkFreqHz = 12000000,
      busSpeed = BusSpeed.Standard,
      addrMode = AddrMode.SevenBits,
      useClockStretching = true
    )
) extends Component {
  // TODO
}

/** Verilog generation entry point for the echo demo. */
object I2cControllerDemoVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(targetDirectory = "gen").generateVerilog(I2cControllerDemo())
  }
}
