package i2c

import spinal.core._
import spinal.lib._

// All values are integer system-clock cycle counts, rounded up so the
// hardware never undershoots the spec. Symbol names match the I²C
// spec table verbatim so cross-referencing the datasheet is trivial.
case class BusTiming(cfg: I2cConfig) {
  // Bit-period halves
  val tHigh: Int = toClockCycles(nsFor(cfg.busSpeed)(4000, 600, 260)) // SCL high time
  val tLow: Int = toClockCycles(nsFor(cfg.busSpeed)(4700, 1300, 500)) // SCL low time

  // Start / stop framing
  val tHdSta: Int = toClockCycles(nsFor(cfg.busSpeed)(4000, 600, 260)) // hold time after START
  val tSuSto: Int = toClockCycles(nsFor(cfg.busSpeed)(4000, 600, 260)) // setup time before STOP
  val tBuf: Int = toClockCycles(nsFor(cfg.busSpeed)(4700, 1300, 500)) // bus-free between txns

  // Data slot
  val tSuDat: Int = toClockCycles(nsFor(cfg.busSpeed)(250, 100, 50)) // SDA setup before SCL↑
  val tHdDat: Int = toClockCycles(nsFor(cfg.busSpeed)(0, 0, 0)) // SDA hold after SCL↓

  private def divRoundUp(num: Int, den: Int): Int = {
    (num + den - 1) / den
  }

  private def toClockCycles(timeNs: Int): Int = {
    divRoundUp(cfg.clkFreqHz * timeNs, 1000000000)
  }

  private def nsFor(speed: BusSpeed.E)(
    standard: Int,
    fast: Int,
    fastPlus: Int
  ): Int =
    speed match {
      case BusSpeed.Standard => standard
      case BusSpeed.Fast     => fast
      case BusSpeed.FastPlus => fastPlus
    }
}
