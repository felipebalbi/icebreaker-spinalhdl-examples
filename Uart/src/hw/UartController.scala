package uart

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.regif._
import spinal.lib.bus.regif.AccessType._

/** Memory-mapped UART controller — APB3-fronted wrapper around [[UartTx]] /
  * [[UartRx]] with TX and RX FIFOs, runtime-tunable baud rate, and a
  * regif-generated register file that doubles as machine-readable
  * documentation.
  *
  * == Why this exists ==
  *
  * The streaming [[UartTx]] / [[UartRx]] cores are deliberately portable: they
  * present Stream handshakes, expose error pulses, and know nothing about a
  * processor bus. That makes them composable but inconvenient for embedded
  * firmware that just wants to "read a byte". `UartController` wraps the same
  * cores in a register interface that mirrors the industry norm (16550 / DW /
  * STM32-style: TXDATA, RXDATA, STATUS, ISR/IER, BAUD, CONFIG) and adds:
  *
  *   - **TX/RX FIFOs**, depth = `cfg.fifoDepth` (default 16). A brief APB
  *     stall can no longer overrun the line.
  *   - **Runtime baud**: BAUD is a writable register driving the DDS phase
  *     increment. Reset value matches `cfg.baudRate` at `cfg.clkFreqHz`.
  *   - **Sticky errors** with W1C/RC clear semantics, so firmware never has
  *     to race the hardware to catch a single-cycle error pulse.
  *   - **A maskable interrupt** OR-reduced over the framing/parity/overrun
  *     events, mirroring the 16550 IIR behaviour.
  *
  * == Address map ==
  * {{{
  *   0x00 CTRL     RW   [0]=enable [1]=tx_enable [2]=rx_enable
  *   0x04 STATUS   RO   [0]=tx_busy [1]=tx_fifo_full [2]=tx_fifo_empty
  *                      [3]=rx_data_avail [4]=rx_fifo_full
  *   0x08 ISR      RC   sticky error/event flags, read clears
  *                      [0]=framing [1]=parity [2]=overrun
  *                      [3]=tx_done [4]=rx_done
  *   0x0C IER      RW   per-bit enable; matches ISR layout
  *   0x10 TXDATA   WO   write pushes one byte into TX FIFO (silently
  *                      dropped if STATUS.tx_fifo_full is set — software
  *                      should poll first)
  *   0x14 RXDATA   RO   read returns the FIFO front and pops it (returns
  *                      0 if STATUS.rx_data_avail is clear)
  *   0x18 BAUD     RW   DDS phase increment driving BaudGenerator. Reset
  *                      = phaseIncFor(cfg.baudRate, cfg.clkFreqHz)
  *   0x1C CFG_INFO RO   [3:0]=dataBits-1 [5:4]=stopBits [7:6]=parity
  *                      [11:8]=log2(oversample) [23:16]=fifoDepth
  *   0x20 CLKFREQ  RO   cfg.clkFreqHz so software can compute a new
  *                      phase increment for any desired baud
  * }}}
  *
  * == Hardware datasheet ==
  *
  * This Component carries the regif metadata that drives `make docs` —
  * running `sbt "runMain uart.UartControllerDocs"` produces an HTML
  * datasheet (`gen/uart_controller.html`), a C header
  * (`gen/uart_controller.h`) for firmware, plus JSON / RALF / SystemRDL
  * for any verification tooling.
  *
  * @param cfg
  *   Compile-time UART configuration. `cfg.baudRate` and `cfg.clkFreqHz`
  *   become the *reset* values of BAUD; `cfg.dataBits`, `cfg.parity`,
  *   `cfg.stopBits` and `cfg.fifoDepth` shape the build (they are NOT
  *   runtime-tunable in this version — they show up read-only in
  *   CFG_INFO).
  */
case class UartController(cfg: UartConfig = UartConfig(useCts = false, useRts = false))
    extends Component {
  require(
    !cfg.useCts && !cfg.useRts,
    "UartController v1 does not wire CTS/RTS; use cfg.useCts = false and cfg.useRts = false"
  )

  /** APB3 configuration: 8-bit byte-addressed, 32-bit data, no PSLVERR.
    *
    * 8 address bits is more than enough for the eight 4-byte registers we
    * own — gives 256 bytes of address space — and keeps the externally
    * visible address signal small. Increase only if more registers land.
    */
  val apb3Config = Apb3Config(
    addressWidth = 8,
    dataWidth = 32,
    selWidth = 1,
    useSlaveError = false
  )

  val io = new Bundle {

    /** APB3 slave port. Wire to whichever bus master fronts it (a soft CPU,
      * a bus-master FSM, an external test driver via `Apb3Driver`, …).
      */
    val apb = slave(Apb3(apb3Config))

    /** UART receive line, idles high. Synchronised internally. */
    val rx = in Bool ()

    /** UART transmit line, idles high. */
    val tx = out Bool ()

    /** Aggregate active-high interrupt: OR(ISR & IER) when CTRL.enable = 1.
      * Wire to the host's interrupt controller, or leave dangling for a
      * polled bring-up.
      */
    val irq = out Bool ()
  }

  // ----- streaming cores ---------------------------------------------------

  val rxCore = UartRx(cfg)
  val txCore = UartTx(cfg)

  rxCore.io.rx := io.rx
  io.tx := txCore.io.tx

  // ----- FIFOs -------------------------------------------------------------
  //
  // Both halves of the cores are Stream-shaped, so the FIFOs slot in
  // straight on top with no handshake gymnastics. `cfg.fifoDepth` is
  // typically 16 — the same default as a 16550 — and on UP5K BRAM
  // budgets that's negligible.

  val txFifo = StreamFifo(Bits(cfg.dataBits bits), cfg.fifoDepth)
  val rxFifo = StreamFifo(Bits(cfg.dataBits bits), cfg.fifoDepth)

  rxFifo.io.push.payload := rxCore.io.payload.payload
  // rxFifo.io.push.valid and rxCore.io.payload.ready are wired further
  // below — the CTRL.rx_enable gate sits between them.

  // ----- regif -------------------------------------------------------------

  val busif = Apb3BusInterface(io.apb, (0x000, 256 Byte))

  // CTRL ------------------------------------------------------------------

  val CTRL = busif.newReg(doc = "Control register")
  val ctrlEnable = CTRL.field(
    Bool(),
    RW,
    1,
    doc = "Master enable. 0 = block IRQ output and freeze TX engine; 1 = run."
  )
  val ctrlTxEnable = CTRL.field(
    Bool(),
    RW,
    1,
    doc = "Enable TX FIFO drain into UartTx. 0 = bytes still accepted into FIFO but no transmission happens."
  )
  val ctrlRxEnable = CTRL.field(
    Bool(),
    RW,
    1,
    doc = "Enable RX FIFO push from UartRx. 0 = received bytes are dropped on the floor."
  )

  // STATUS ----------------------------------------------------------------

  val STATUS = busif.newReg(doc = "Status register (read-only)")
  val statusTxBusy = STATUS.field(
    Bool(),
    RO,
    doc = "Asserted while a frame is in flight on the TX line."
  )
  val statusTxFifoFull = STATUS.field(
    Bool(),
    RO,
    doc = "TX FIFO is full; further TXDATA writes are dropped silently."
  )
  val statusTxFifoEmpty = STATUS.field(
    Bool(),
    RO,
    doc = "TX FIFO is empty (and likely the line will go idle soon)."
  )
  val statusRxDataAvail = STATUS.field(
    Bool(),
    RO,
    doc = "At least one byte sits in the RX FIFO; reading RXDATA pops it."
  )
  val statusRxFifoFull = STATUS.field(
    Bool(),
    RO,
    doc = "RX FIFO full; the next received byte will trigger an overrun."
  )

  statusTxBusy := !txCore.io.data.ready
  statusTxFifoFull := !txFifo.io.push.ready
  statusTxFifoEmpty := !txFifo.io.pop.valid
  statusRxDataAvail := rxFifo.io.pop.valid
  statusRxFifoFull := !rxFifo.io.push.ready

  // ISR / IER -------------------------------------------------------------
  //
  // Sticky-with-read-clear (RC) for ISR matches the 16550's "read LSR
  // to clear" semantics. The streaming RxCore emits one-cycle pulses,
  // and `.set()` on an RC field latches them into the register without
  // racing the bus. IER (the mask) is plain RW; the OR with ISR drives
  // the IRQ output.

  val ISR = busif.newReg(doc = "Interrupt status (read-clears)")
  val isrFraming = ISR.field(
    Bool(),
    RC,
    doc = "Stop bit was sampled low on at least one received frame."
  )
  val isrParity = ISR.field(
    Bool(),
    RC,
    doc = "Parity bit didn't match data parity. Only meaningful when CFG_INFO.parity != None."
  )
  val isrOverrun = ISR.field(
    Bool(),
    RC,
    doc = "RX FIFO overflowed; a byte was lost."
  )
  val isrTxDone = ISR.field(
    Bool(),
    RC,
    doc = "TX FIFO transitioned from non-empty to empty (the last queued byte completed)."
  )
  val isrRxDone = ISR.field(
    Bool(),
    RC,
    doc = "A byte was pushed into the RX FIFO."
  )

  val IER = busif.newReg(doc = "Interrupt enable (mask, RW)")
  val ierFraming = IER.field(Bool(), RW, 0, doc = "Enable framing-error interrupt.")
  val ierParity = IER.field(Bool(), RW, 0, doc = "Enable parity-error interrupt.")
  val ierOverrun = IER.field(Bool(), RW, 0, doc = "Enable overrun interrupt.")
  val ierTxDone = IER.field(Bool(), RW, 0, doc = "Enable TX-done interrupt.")
  val ierRxDone = IER.field(Bool(), RW, 0, doc = "Enable RX-done interrupt.")

  when(rxCore.io.framingError) { isrFraming.set() }
  when(rxCore.io.parityError) { isrParity.set() }
  when(rxCore.io.overrun) { isrOverrun.set() }
  // RX-done = a successful FIFO push (rxCore byte queued).
  when(rxFifo.io.push.fire) { isrRxDone.set() }
  // TX-done = rising edge of (TX FIFO empty AND TX line idle), i.e. the
  // moment the very last queued byte finishes leaving the wire.
  val txIdle = !txFifo.io.pop.valid && !statusTxBusy
  val txIdlePrev = RegNext(txIdle) init (False)
  when(txIdle && !txIdlePrev) { isrTxDone.set() }

  // TXDATA / RXDATA -------------------------------------------------------
  //
  // The data registers don't fit the regif "field" model neatly: TXDATA
  // is a write-triggered FIFO push, RXDATA is a read-triggered FIFO pop
  // with hardware-driven read data. We use plain `field()` for the
  // documentation/address machinery and then drop down to BusIfBase's
  // `doWrite`/`doRead` + `writeAddress()`/`readAddress()` to detect the
  // hits.

  val TXDATA = busif.newReg(doc = "Write to push a byte into the TX FIFO")
  val txDataWord = TXDATA.field(
    Bits(cfg.dataBits bits),
    WO,
    doc = "Byte to transmit. LSB-first on the wire."
  )

  val RXDATA = busif.newReg(doc = "Read to pop a byte from the RX FIFO")
  val rxDataWord = RXDATA.field(
    Bits(cfg.dataBits bits),
    RO,
    doc = "Front byte of the RX FIFO. Reading this register pops the FIFO; reads while STATUS.rx_data_avail = 0 return zero."
  )

  rxDataWord := rxFifo.io.pop.payload

  // BAUD ------------------------------------------------------------------

  val accWidth = BaudGenerator.defaultAccWidth
  val baudResetValue = BigInt(BaudGenerator.phaseIncFor(cfg, accWidth))
  val BAUD = busif.newReg(doc = "DDS phase increment driving BaudGenerator")
  val baudPhaseInc = BAUD.field(
    UInt(accWidth bits),
    RW,
    baudResetValue,
    doc =
      "phaseInc = round(baudRate * 2^24 / clkFreqHz). The RX side uses phaseInc * oversample internally."
  )

  // Drive both cores' baud generators from the BAUD register. RX runs
  // at oversample × baudRate so its phaseInc is shifted up by
  // log2(oversample); the require below enforces that oversample is
  // a power of two so the shift is lossless.
  require(
    (cfg.oversample & (cfg.oversample - 1)) == 0,
    "cfg.oversample must be a power of two for the RX phaseInc shift to be lossless"
  )
  val osShift = log2Up(cfg.oversample)
  txCore.io.baudPhaseInc := baudPhaseInc
  rxCore.io.baudPhaseInc := (baudPhaseInc << osShift).resize(accWidth bits)

  // CFG_INFO --------------------------------------------------------------
  //
  // Read-only parameter window so firmware (or a Rust embedded-hal
  // driver) can introspect what the synthesiser actually wired in.
  // None of these are tunable post-synthesis.

  val parityCode = cfg.parity match {
    case ParityType.None => 0
    case ParityType.Even => 1
    case ParityType.Odd  => 2
  }

  val CFG_INFO = busif.newReg(doc = "Build-time configuration (read-only)")
  val cfgInfoDataBits = CFG_INFO.field(UInt(4 bits), RO, doc = "dataBits - 1 (so 8N1 reads as 7).")
  val cfgInfoStopBits = CFG_INFO.field(UInt(2 bits), RO, doc = "Number of stop bits (1 or 2).")
  val cfgInfoParity = CFG_INFO.field(UInt(2 bits), RO, doc = "Parity: 0=None 1=Even 2=Odd.")
  val cfgInfoOsShift = CFG_INFO.field(UInt(4 bits), RO, doc = "log2(oversample); RX baudgen runs phaseInc << this.")
  CFG_INFO.reserved(4 bits)
  val cfgInfoFifoDepth = CFG_INFO.field(UInt(8 bits), RO, doc = "TX/RX FIFO depth in bytes.")

  cfgInfoDataBits := U(cfg.dataBits - 1, 4 bits)
  cfgInfoStopBits := U(cfg.stopBits, 2 bits)
  cfgInfoParity := U(parityCode, 2 bits)
  cfgInfoOsShift := U(osShift, 4 bits)
  cfgInfoFifoDepth := U(cfg.fifoDepth, 8 bits)

  val CLKFREQ = busif.newReg(doc = "System clock frequency in Hz (read-only)")
  val clkFreqVal = CLKFREQ.field(UInt(32 bits), RO, doc = "clkFreqHz; firmware uses this to compute a new BAUD value.")
  clkFreqVal := U(cfg.clkFreqHz, 32 bits)

  // ----- TX FIFO push glue ------------------------------------------------
  //
  // A plain WO field lives in the register file (so it shows up in the
  // datasheet) but the *effect* is hand-rolled: any write that hits
  // TXDATA's address pulses fifo.io.push.valid for one cycle.

  val txDataWriteHit =
    busif.doWrite && (busif.writeAddress() === U(TXDATA.getAddr(), busif.writeAddress().getWidth bits))
  txFifo.io.push.valid := txDataWriteHit
  // Pull the byte from the live bus write data rather than the stored
  // field — the field's stored value updates one cycle late, but
  // busif.writeData reflects PWDATA on the doWrite cycle directly.
  txFifo.io.push.payload := busif.writeData(cfg.dataBits - 1 downto 0)

  // TX FIFO drain → UartTx.io.data, gated by CTRL.tx_enable.
  txCore.io.data.valid := txFifo.io.pop.valid && ctrlTxEnable
  txCore.io.data.payload := txFifo.io.pop.payload
  txFifo.io.pop.ready := txCore.io.data.ready && ctrlTxEnable

  // ----- RX FIFO push glue (gated by CTRL.rx_enable) ----------------------
  //
  // When rx_enable = 0, the rxCore's payloads are still produced (the
  // line is always sampled) but they're consumed without being queued —
  // i.e. dropped on the floor.

  rxFifo.io.push.valid := rxCore.io.payload.valid && ctrlRxEnable
  rxCore.io.payload.ready := Mux(ctrlRxEnable, rxFifo.io.push.ready, True)

  // ----- RX FIFO pop glue -------------------------------------------------

  val rxDataReadHit = busif.doRead && (busif.readAddress() === U(RXDATA.getAddr(), busif.writeAddress().getWidth bits))
  rxFifo.io.pop.ready := rxDataReadHit && rxFifo.io.pop.valid

  // ----- IRQ aggregation --------------------------------------------------

  val irqRaw =
    (isrFraming & ierFraming) |
      (isrParity & ierParity) |
      (isrOverrun & ierOverrun) |
      (isrTxDone & ierTxDone) |
      (isrRxDone & ierRxDone)
  io.irq := irqRaw && ctrlEnable
}

/** Verilog generation entry point for the controller in isolation. */
object UartControllerVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(targetDirectory = "gen").generateVerilog(UartController())
  }
}

/** Documentation generator entry point.
  *
  * Runs the elaboration and then asks regif to dump the register file
  * documentation alongside the Verilog. Outputs in `gen/`:
  *
  *   - `uart_controller.html` — datasheet table
  *   - `uart_controller.h`    — C header (offsets / shifts / masks)
  *   - `uart_controller.json` — machine-readable register map
  *   - `uart_controller.ralf` — UVM RALF for verification
  *   - `uart_controller.rdl`  — SystemRDL
  *
  * Run with `make docs`.
  */
object UartControllerDocs {
  def main(args: Array[String]): Unit = {
    val report = SpinalConfig(targetDirectory = "gen")
      .generateVerilog(UartController())
    report.toplevel.busif.accept(DocHtml("uart_controller"))
    report.toplevel.busif.accept(DocCHeader("uart_controller", "UART"))
    report.toplevel.busif.accept(DocJson("uart_controller"))
    report.toplevel.busif.accept(DocRalf("uart_controller"))
    report.toplevel.busif.accept(DocSystemRdl("uart_controller"))
  }
}
