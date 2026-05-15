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
  val apb3Config = Apb3Config(
    addressWidth = 8,
    dataWidth = 32,
    selWidth = 1,
    useSlaveError = false
  )

  val io = new Bundle {
    val apb = slave(Apb3(apb3Config))
    val bus = master(I2cIo()) // open-drain SCL/SDA
    val irq = out Bool () // OR(ISR & IER) when CTRL.enable = 1
  }

  val byteCtrl = I2cByteController(cfg)
  byteCtrl.io.bus <> io.bus

  // ----- regif --------------------------------------------------
  val busif = Apb3BusInterface(io.apb, (0x00, 256 Byte))

  // 0x00 REVISION ------------------------------------------------
  // Layout: [31:24]=major [23:16]=minor, [15:0]=patch
  // regif allocates bit-0-up so declare patch -> minor -> major.
  val REVISION =
    busif.newReg(doc = "IP revision (major.minor.patch), read-only")
  val revPatch = REVISION.field(UInt(16 bits), RO, doc = "Patch [15:0]")
  val revMinor = REVISION.field(UInt(8 bits), RO, doc = "Minor [23:16]")
  val revMajor = REVISION.field(UInt(8 bits), RO, doc = "Major [31:24]")
  revPatch := U(Revision.patch, 16 bits)
  revMinor := U(Revision.minor, 8 bits)
  revMajor := U(Revision.major, 8 bits)

  // 0x04 CTRL --------------------------------------------------
  val CTRL = busif.newReg(doc = "Control register")
  val ctrlEnable = CTRL.field(
    Bool(),
    RW,
    0,
    doc = "Master enable. 0 = block IRQ + freeze byte-controller. 1 = run."
  )
  val ctrlCmdEnable =
    CTRL.field(Bool(), RW, 0, doc = "Drain CMD shadow into byte-controller.")
  val ctrlRxEnable = CTRL.field(
    Bool(),
    RW,
    0,
    doc = "Push read bytes into RX FIFO. 0 = drop them."
  )
  val ctrlStretchEnable: Bool =
    if (cfg.useClockStretching)
      CTRL.field(Bool(), RW, 1, doc = "Honour clock-stretching path.")
    else {
      // Reserve the bit so the address map matches across cfg variants,
      // but tie it to 0 - there is no stretch path to gate.
      CTRL.field(
        Bool(),
        RO,
        0,
        doc = "Reserved (cfg.useClockStretching=false)."
      ) := False
      False
    }

  // 0x08 STATUS --------------------------------------------------
  val STATUS = busif.newReg(doc = "Status register")
  val statusBusBusy = STATUS.field(Bool(), RO, 0, doc = "Controller is busy.")
  val statusCmdBusy = STATUS.field(
    Bool(),
    RO,
    0,
    doc =
      "A Command is queue or in flight; Software must wait for this to fall before writing CMD again."
  )
  val statusArbLost =
    STATUS.field(Bool(), RO, 0, doc = "Arbitration loss (W1C copy in ISR).")

  // TODO: drive these from byteCtrl / cmd-issue FSM in the wiring pass.
  statusBusBusy := False
  statusCmdBusy := False
  statusArbLost := False

  // 0x0c ISR --------------------------------------------------
  val ISR = busif.newReg(doc = "Interrupt Status Register")
  val isrAddrNack =
    ISR.field(Bool(), W1C, 0, doc = "Target NACKed address phase.")
  val isrDataNack = ISR.field(Bool(), W1C, 0, doc = "Target NACKed data phase.")
  val isrArbLost =
    ISR.field(Bool(), W1C, 0, doc = "If set, then we lost arbitration.")
  val isrStretchTimeout = ISR.field(
    Bool(),
    RO,
    0,
    doc = "Reserved for future use. Currently tied to 0."
  )
  val isrCmdDone =
    ISR.field(Bool(), W1C, 0, doc = "Command retired (byte-ctrl finished).")
  val isrCmdOverrun = ISR.field(
    Bool(),
    W1C,
    0,
    doc = "Command was written while CMD_BUSY=1; the new write was dropped."
  )
  val isrRxDone =
    ISR.field(Bool(), W1C, 0, doc = "A byte was pushed into the RX FIFO.")
  val isrTxUnderrun = ISR.field(
    Bool(),
    W1C,
    0,
    doc = "CMD needed a TXDATA byte but TXDATA was empty; the CMD was dropped."
  )

  // Sticky-event triggers from byteCtrl. Each W1C bit latches on the
  // one-cycle pulse below and stays set until firmware writes 1 to
  // clear it. Same idiom as UartController:
  //   when(rxCore.io.framingError) { isrFraming.set() }
  //
  // arb_lost: byteCtrl publishes ArbLost as a status field on the
  // response stream — assert on the cycle the response is consumed
  // (rsp.fire is a one-cycle pulse, perfect for a sticky latch).
  val inFlightKind = Reg(ByteCmdKind()) init (ByteCmdKind.Stop)
  when(byteCtrl.io.cmd.fire) { inFlightKind := byteCtrl.io.cmd.payload.kind }

  def inAddrPhase = inFlightKind === ByteCmdKind.AddrWrite ||
    inFlightKind === ByteCmdKind.AddrRead ||
    inFlightKind === ByteCmdKind.RepStart

  def inDataPhase = inFlighKind === ByteCmdKind.WriteData

  when(
    byteCtrl.io.rsp.fire && byteCtrl.io.rsp.payload.status === ByteRspStatus.Ok
  ) {
    when(inAddrPhase && byteCtrl.io.rsp.payload.ackIn) { isrAddrNack.set() }
    when(inDataPhase && byteCtrl.io.rsp.payload.ackIn) { isrDataNack.set() }
  }
  when(
    byteCtrl.io.rsp.fire &&
      byteCtrl.io.rsp.payload.status === ByteRspStatus.ArbLost
  ) {
    isrArbLost.set()
  }
  when(byteCtrl.io.rsp.fire) {
    isrCmdDone.set()
  }
  
  // TODO (wiring pass): cmd_overrun (need cmd-issue FSM), rx_done
  // (needs RX FIFO), tx_underrun (needs cmd-issue FSM + TX FIFO).

  // 0x10 IER --------------------------------------------------
  // Mirrors the ISR layout bit-for-bit so firmware can mask events
  // by writing the same bit positions it just read from ISR. Bit 3
  // (stretch_timeout) is reserved RO 0 to match ISR's reservation
  // — the mask slot is preserved for the future stretch-timeout
  // event without renumbering the rest.
  val IER = busif.newReg(doc = "Interrupt enable (mask, RW)")
  val ierAddrNack =
    IER.field(Bool(), RW, 0, doc = "Enable address-NACK interrupt.")
  val ierDataNack =
    IER.field(Bool(), RW, 0, doc = "Enable data-NACK interrupt.")
  val ierArbLost =
    IER.field(Bool(), RW, 0, doc = "Enable arbitration-lost interrupt.")
  val ierStretchTimeout = {
    // Reserved to keep the bit position aligned with ISR.stretch_timeout.
    val f = IER.field(
      Bool(),
      RO,
      0,
      doc = "Reserved (matches ISR.stretch_timeout slot)."
    )
    f := False
    f
  }
  val ierCmdDone =
    IER.field(Bool(), RW, 0, doc = "Enable CMD-done interrupt.")
  val ierCmdOverrun =
    IER.field(Bool(), RW, 0, doc = "Enable CMD-overrun interrupt.")
  val ierRxDone =
    IER.field(Bool(), RW, 0, doc = "Enable RX-done interrupt.")
  val ierTxUnderrun =
    IER.field(Bool(), RW, 0, doc = "Enable TX-underrun interrupt.")

  // 0x14 CMD --------------------------------------------------
  // 1-deep WO shadow register (NOT a FIFO). Firmware polls
  // STATUS.cmd_busy (or waits for ISR.cmd_done) between writes.
  // Writes while cmd_busy = 1 are silently dropped and
  // ISR.cmd_overrun is set. The opcode/ack_out land here; every
  // on-wire payload byte is sourced from TXDATA.
  val CMD = busif.newReg(doc = "Byte-command shadow register (write-only)")
  val cmdKind = CMD.field(
    UInt(3 bits),
    WO,
    doc =
      "Command kind: 0=AddrWrite 1=AddrRead 2=WriteData 3=ReadData 4=RepStart 5=Stop."
  )
  val cmdAckOut = CMD.field(
    Bool(),
    WO,
    doc =
      "Master ACK polarity for ReadData: 0 = ACK and continue, 1 = NACK before STOP."
  )

  // 0x18 TXDATA --------------------------------------------------
  // WO byte-push port for the TX-data FIFO. Sized by cfg.txFifoDepth.
  // Every on-wire payload byte (address byte for AddrWrite/AddrRead,
  // address+R/W byte for RepStart, each WriteData byte) is sourced
  // from this FIFO. Doc-only here; the actual push is detected
  // against the register address in the wiring pass.
  val TXDATA = busif.newReg(doc = "Write to push a byte into the TX FIFO.")
  val txDataWord = TXDATA.field(
    Bits(8 bits),
    WO,
    doc = "Byte to enqueue. Pushed into TX FIFO on write hit."
  )

  // 0x1C RXDATA --------------------------------------------------
  // RO byte-pop port for the RX-data FIFO. Sized by cfg.rxFifoDepth.
  // Read returns the front of the RX FIFO and pops it (returns 0 if
  // RX_FIFO_STATUS.empty = 1). Doc-only here; the pop is detected
  // against the register address in the wiring pass.
  val RXDATA = busif.newReg(doc = "Read to pop a byte from the RX FIFO.")
  val rxDataWord = RXDATA.field(
    Bits(8 bits),
    RO,
    doc =
      "Front byte of the RX FIFO. Reading this register pops the FIFO; reads while RX_FIFO_STATUS.empty = 1 return zero."
  )
  // TODO: drive from rxFifo.io.pop.payload in the wiring pass.
  rxDataWord := B(0, 8 bits)

  // 0x20 PRESCALE --------------------------------------------------
  // Runtime override of BusTiming. Reset value = cfg.quarterPeriodCycles
  // so a bare reset reproduces the build-time-configured SCL frequency.
  // Firmware can re-tune SCL after the fact by writing this register
  // (the same role BAUD plays for the UART).
  val PRESCALE = busif.newReg(doc = "Quarter-period cycle count for BusTiming")
  val prescale = PRESCALE.field(
    UInt(16 bits),
    RW,
    BigInt(cfg.quarterPeriodCycles),
    doc =
      "Quarter-period in system-clock cycles. Reset = cfg.quarterPeriodCycles. Downstream timing scales proportionally."
  )

  // 0x24 TX_FIFO_STATUS --------------------------------------------------
  // Layout (identical to RX_FIFO_STATUS):
  //   [0]      full   - push.ready is low
  //   [1]      empty  - pop.valid is low (no byte queued)
  //   [7:2]    reserved
  //   [15:8]   count  - live occupancy (0..depth)
  //   [23:16]  depth  - synth-time capacity (cfg.txFifoDepth)
  val TX_FIFO_STATUS = busif.newReg(doc = "TX FIFO status (read-only)")
  val txFifoFull = TX_FIFO_STATUS.field(
    Bool(),
    RO,
    doc = "TX FIFO is full; further TXDATA writes are dropped silently."
  )
  val txFifoEmpty = TX_FIFO_STATUS.field(
    Bool(),
    RO,
    doc = "TX FIFO is empty (no payload byte staged)."
  )
  TX_FIFO_STATUS.reserved(6 bits)
  val txFifoCount = TX_FIFO_STATUS.field(
    UInt(8 bits),
    RO,
    doc = "Bytes currently queued in the TX FIFO (0..txFifoDepth)."
  )
  val txFifoDepth = TX_FIFO_STATUS.field(
    UInt(8 bits),
    RO,
    doc = "Synth-time TX FIFO capacity in bytes (= cfg.txFifoDepth)."
  )
  // TODO: drive full/empty/count from txFifo in the wiring pass.
  txFifoFull := False
  txFifoEmpty := True
  txFifoCount := U(0, 8 bits)
  txFifoDepth := U(cfg.txFifoDepth, 8 bits)

  // 0x28 RX_FIFO_STATUS --------------------------------------------------
  val RX_FIFO_STATUS = busif.newReg(doc = "RX FIFO status (read-only)")
  val rxFifoFull = RX_FIFO_STATUS.field(
    Bool(),
    RO,
    doc = "RX FIFO is full; the next received byte will trigger an overrun."
  )
  val rxFifoEmpty = RX_FIFO_STATUS.field(
    Bool(),
    RO,
    doc =
      "RX FIFO is empty; firmware should poll for !empty before reading RXDATA."
  )
  RX_FIFO_STATUS.reserved(6 bits)
  val rxFifoCount = RX_FIFO_STATUS.field(
    UInt(8 bits),
    RO,
    doc = "Bytes currently queued in the RX FIFO (0..rxFifoDepth)."
  )
  val rxFifoDepth = RX_FIFO_STATUS.field(
    UInt(8 bits),
    RO,
    doc = "Synth-time RX FIFO capacity in bytes (= cfg.rxFifoDepth)."
  )
  // TODO: drive full/empty/count from rxFifo in the wiring pass.
  rxFifoFull := False
  rxFifoEmpty := True
  rxFifoCount := U(0, 8 bits)
  rxFifoDepth := U(cfg.rxFifoDepth, 8 bits)

  // 0x2C CFG_INFO --------------------------------------------------
  // Read-only window onto the synth-time configuration so firmware
  // can fingerprint the build it's talking to. Layout per the
  // address map in TODO.md:
  //   [1:0]    bus_speed           0=Std 1=Fast 2=Fast+
  //   [2]      addr_mode           0=7-bit 1=10-bit
  //   [3]      use_clock_stretching
  //   [15:4]   reserved
  //   [23:16]  clk_freq_mhz        clkFreqHz / 1_000_000 (truncated)
  val busSpeedCode = cfg.busSpeed match {
    case BusSpeed.Standard => 0
    case BusSpeed.Fast     => 1
    case BusSpeed.FastPlus => 2
  }
  val addrModeCode = cfg.addrMode match {
    case AddrMode.SevenBits => 0
    case AddrMode.TenBits   => 1
  }

  val CFG_INFO = busif.newReg(doc = "Build-time configuration (read-only)")
  val cfgInfoBusSpeed = CFG_INFO.field(
    UInt(2 bits),
    RO,
    doc = "Bus speed: 0 = Standard, 1 = Fast, 2 = Fast+."
  )
  val cfgInfoAddrMode =
    CFG_INFO.field(
      UInt(1 bits),
      RO,
      doc = "Address mode: 0 = 7-bit, 1 = 10-bit."
    )
  val cfgInfoUseStretch = CFG_INFO.field(
    Bool(),
    RO,
    doc = "1 = clock-stretching path is wired in (cfg.useClockStretching)."
  )
  CFG_INFO.reserved(12 bits)
  val cfgInfoClkFreqMhz = CFG_INFO.field(
    UInt(8 bits),
    RO,
    doc = "Synth clock in MHz (clkFreqHz / 1_000_000, truncated)."
  )

  cfgInfoBusSpeed := U(busSpeedCode, 2 bits)
  cfgInfoAddrMode := U(addrModeCode, 1 bits)
  cfgInfoUseStretch := Bool(cfg.useClockStretching)
  cfgInfoClkFreqMhz := U(cfg.clkFreqHz / 1000000, 8 bits)

  // ----- byteCtrl / IRQ tie-offs (boilerplate; wiring lands later) -------
  //
  // The byte-controller is instantiated above and shares the bus port
  // with us, but the cmd/rsp streams have no producer/consumer yet.
  // Tie them off so elaboration succeeds; the cmd-issue FSM and
  // response-handling glue land in the wiring pass.
  byteCtrl.io.cmd.valid := False
  byteCtrl.io.cmd.payload.kind := ByteCmdKind.Stop
  byteCtrl.io.cmd.payload.data := B(0, 8 bits)
  byteCtrl.io.cmd.payload.ackOut := True
  byteCtrl.io.rsp.ready := True

  // IRQ aggregation matches the Uart pattern: OR(ISR & IER) gated by
  // the master enable. Stays low until the ISR .set() triggers wire
  // up — the expression is in place so the address map / IRQ contract
  // are observable from sim today.
  val irqRaw =
    (isrAddrNack & ierAddrNack) |
      (isrDataNack & ierDataNack) |
      (isrArbLost & ierArbLost) |
      (isrCmdDone & ierCmdDone) |
      (isrCmdOverrun & ierCmdOverrun) |
      (isrRxDone & ierRxDone) |
      (isrTxUnderrun & ierTxUnderrun)
  io.irq := irqRaw && ctrlEnable
}
