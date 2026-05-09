package i2c

import spinal.core._

/** I2C Bus Speed
  *
  *   - `Standard` : 100kHz
  *   - `Fast`     : 400kHz
  *   - `FastPlus` : 1MHz
  */
object BusSpeed extends SpinalEnum {
  val Standard, Fast, FastPlus = newElement()
}

/** Addressing mode
  *
  *   - `SevenBits` : 7-bit addressing
  *   - `TenBits`   : 10-bit addressing
  */
object AddrMode extends SpinalEnum {
  val SevenBits, TenBits = newElement()
}

case class I2cConfig(
    clkFreqHz: Int = 12000000,
    busSpeed: BusSpeed.E = BusSpeed.Standard,
    addrMode: AddrMode.E = AddrMode.SevenBits,
    useClockStretching: Boolean = false
) {

  val busFreqHz: Int = busSpeed match {
    case BusSpeed.Standard => 100000
    case BusSpeed.Fast     => 400000
    case BusSpeed.FastPlus => 1000000
  }

  val quarterPeriodCycles = clkFreqHz / (busFreqHz * 4)

  require(clkFreqHz > 0)

  require(
    quarterPeriodCycles >= 1,
    s"clkFreqHz=$clkFreqHz too low for $busSpeed"
  )
}
