package uart_tx

import spinal.core._

/** BaudGenerator: Direct Digital Synthesis (DDS) implementation.
  * 
  */
case class BaudGenerator(cfg: UartConfig, accWidth: Int = 24) extends Component {
  val io = new Bundle {
    val enable = in Bool()   // gate the counter
    val tick   = out Bool()  // 1-cycle pulse, once per bit period
  }

  val phaseInc = ((BigInt(cfg.baudRate) << accWidth) / cfg.clkFreqHz).toLong
  val acc = Reg(UInt(accWidth + 1 bits)) init(0) // one extra to capture carry

  when (!io.enable) {
    acc := 0
  } otherwise {
    acc := (acc(accWidth - 1 downto 0).resize(accWidth + 1)) + phaseInc
  }

  io.tick := RegNext(acc.msb) init(False) // tick on accumulator overflow
}
