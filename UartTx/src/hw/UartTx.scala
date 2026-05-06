package uart_tx

import spinal.core._
import spinal.lib._

case class UartTx(cfg: UartTxConfig) extends Component {
  val io = new Bundle {
    // Data input side
    val data = slave Stream(Bits(8 bits))  // byte to send + valid/ready handshake

    // Serial output
    val tx = out Bool()

    // Flow control
    val cts = in Bool()
  }
}

object UartTxTopVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(UartTxTop())
  }
}
