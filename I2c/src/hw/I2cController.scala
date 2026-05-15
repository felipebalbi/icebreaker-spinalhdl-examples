package i2c

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.regif._
import spinal.lib.bus.regif.AccessType._

case class I2cController(
    cfg: I2cConfig = I2cConfig(
      clkFreqHz = 12000000,
      busSpeed = BusSpeed.Standard,
      addrMode = AddrMode.SevenBits,
      useClockStretching = true
    )
) extends Component {
  val apb3Config = Apb3Config(addressWidth = 8, dataWidth = 32,
    selWidth = 1, useSlaveError = false)

  val io = new Bundle {
    val apb = slave(Apb3(apb3Config))
    val bus = master(I2cIo())              // open-drain SCL/SDA
    val irq = out Bool()                   // OR(ISR & IER) when CTRL.enable = 1
  }

  val byteCtrl = I2cByteController(cfg)
  byteCtrl.io.bus <> io.bus

  // ----- regif --------------------------------------------------
  val busif = Apb3BusInterface(io.apb, (0x00, 256 Byte))

  // 0x00 REVISION ------------------------------------------------
  // Layout: [31:24]=major [23:16]=minor, [15:0]=patch
  // regif allocates bit-0-up so declare patch -> minor -> major.
  val REVISION = busif.newReg(doc = "IP revision (major.minor.patch), read-only")
  val revPatch = REVISION.field(UInt(16 bits), RO, doc = "Patch [15:0]")
  val revMinor = REVISION.field(UInt(8 bits), RO, doc = "Minor [23:16]")
  val revMajor = REVISION.field(UInt(8 bits), RO, doc = "Major [31:24]")
  revPatch := U(Revision.patch, 16 bits)
  revMinor := U(Revision.minor, 8 bits)
  revMajor := U(Revision.major, 8 bits)

  // 0x04 CTRL --------------------------------------------------
  val CTRL = busif.newReg(doc = "Control register")
  val ctrlEneable = CTRL.field(Bool(), RW, 0,
    doc = "Master enable. 0 = block IRQ + freeze byte-controller. 1c run")
  val ctrlCmdEnable = CTRL.field(Bool(), RW, 0,
    doc = "Drain CMD shadow into byte-controller.")
  val ctrlRxEnable = CTRL.field(Bool(), RW, 0,
    doc = "Push read bytes into RX FIFO. 0 = drop them.")
  val ctrlStretchEnable =
    if (cfg.useClockStretching)
      CTRL.field(Bool(), RW, 1, doc = "Honour clock-stretching path.")
    else {
      // Reserve the bit so the address map matches across cfg variants,
      // but tie it to 0 - there is no stretch path to gate.
      CTRL.field(Bool(), RO, 0, doc = "Reserved (cfg.useClockStretching=false).") := False
      false
    }

  // 0x08 STATUS --------------------------------------------------
  val STATUS = busif.newReg(doc = "Status register")
  val statusBusBusy = STATUS.field(Bool(), RO, 0,
    doc = "Controller is busy.")
  val satusCmdBusy = STATUS.field(Bool(), RO, 0,
    doc = "A Command is queue or in flight; Software must wait for this to fall before writing CMD again.")
  val statusArbLost = STATUS.field(Bool(), RO, 0,
    doc = "Arbitration loss (W1C copy in ISR).")

  // 0x0c ISR --------------------------------------------------
  val ISR = busif.newReg(doc = "Interrupt Status Register")
  val isrAddrNack = ISR.field(Bool(), W1C, 0,
    doc = "Target NACKed address phase.")
  val isrDataNack = ISR.field(Bool(), W1C, 0,
    doc = "Target NACKed data phase.")
  val isrArbLost = ISR.field(Bool(), W1C, 0,
    doc = "If set, then we lost arbitration.")
  val isrStretchTimeout = ISR.field(Bool(), W1C, 0,
    doc = "Reserved for future use. Currently tied to 0.")
  val isrCmdDone = ISR.field(Bool(), W1C, 0,
    doc = "Command retired (byte-ctrl finished).")
  val isrCmdOverrun = ISR.field(Bool(), W1C, 0,
    doc = "Command was written while CMD_BUSY=1; the new write was dropped.")
  val isrRxDone = ISR.field(Bool(), W1C, 0,
    doc = "A byte was pushed into the RX FIFO.")

  // 0x10 IER --------------------------------------------------
  // 0x14 CMD --------------------------------------------------
  // 0x18 TXDATA --------------------------------------------------
  // 0x1c RXDATA --------------------------------------------------
  // 0x20 PRESCALE --------------------------------------------------
  // 0x24 TX_FIFO_STATUS --------------------------------------------------
  // 0x28 RX_FIFO_STATUS --------------------------------------------------
  // 0x2c CFG_INFO --------------------------------------------------
}
