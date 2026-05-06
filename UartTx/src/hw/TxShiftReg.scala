package uart_tx

import spinal.core._
import spinal.lib._

/** TxShiftReg: Shift register for transmission
  */
case class TxShiftReg(cfg: UartTxConfig) extends Component {
  val io = new Bundle {
    // 1-cycle pulse to load `data`
    val load = in Bool ()

    // data to be shifted
    val data = in Bits (cfg.dataBits bits)

    // 1-cycle pulse to shift right
    val shift = in Bool ()

    // current LST -> goes on the wire
    val bit = out Bool ()
  }

  val byte = Reg(Bits(cfg.dataBits bits)) init (0)

  io.bit := byte(0)

  when(io.load) {
    byte := io.data
  } elsewhen (io.shift) {
    byte := (byte >> 1).resized
  }
}
