package i2c

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

object ByteCmdKind extends SpinalEnum {
  val AddrWrite, AddrRead, WriteData, ReadData, RepStart, Stop = newElement()
}

case class ByteCmd() extends Bundle {
  val kind = ByteCmdKind()
  val data = Bits(8 bits) // addres (with R/w) or the actual write payload
  val ackOut = Bool() // for ReadData: ack=0 to continue, 1 to NAK
}

case class ByteRsp() extends Bundle{
  val data = Bits(8 bits) // bytes read back
  val ackIn = Bool() // Target-reported ACK (0 = ACK, 1 = NAK)
  val arbLost = Bool()
}

case class I2cByteController(cfg: I2cConfig) extends Component {
  val io = new Bundle {
    val cmd = slave Stream ByteCmd()
    val rsp = master Stream ByteRsp()
    val bus = master(I2cIo())
  }

  val bitCtrl = I2cBitController(cfg)
  bitCtrl.io.bus := io.bus

  val arbLostReg = Reg(Bool()) init(False)
  val txBitReg = Reg(Bool()) init(True)
  val rxBitReg = Reg(Bool()) init(True)
  val bitCmd = master Stream BitCmd()

  // Add FSM here
}
