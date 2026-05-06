package uart_tx

import spinal.core._

case class UartTxConfig(
  clockFreqHz: HertzNumber,
  baudRate   : Int,
  dataBits   : Int = 8,
  stopBits   : Int = 1,
  parity     : ParityType = ParityType.None
) {
  val ticksPerBit: Int = clockFreqHz / baudRate
  require (ticksPerBit >= 1, "clock must be at least baudRate")
}
