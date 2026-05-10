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
  *   - **TX/RX FIFOs** sized independently by `cfg.txFifoDepth` and
  *     `cfg.rxFifoDepth` (defaults 16 each). A brief APB stall can no
  *     longer overrun the line.
  *   - **Runtime baud**: BAUD is a writable register driving the DDS phase
  *     increment. Reset value matches `cfg.baudRate` at `cfg.clkFreqHz`.
  *   - **Sticky errors** with W1C clear semantics, so firmware never has
  *     to race the hardware to catch a single-cycle error pulse.
  *   - **A maskable interrupt** OR-reduced over the framing/parity/overrun
  *     events, mirroring the 16550 IIR behaviour.
  *
  * == Address map ==
  * {{{
  *   0x00 CTRL            RW   [0]=enable [1]=tx_enable [2]=rx_enable
  *   0x04 STATUS          RO   [0]=tx_busy
  *   0x08 ISR             W1C  sticky error/event flags; write 1 to clear
  *                             [0]=framing [1]=parity [2]=overrun
  *                             [3]=tx_done [4]=rx_done
  *   0x0C IER             RW   per-bit enable / interrupt mask; matches
  *                             ISR layout
  *   0x10 TXDATA          WO   write pushes one byte into TX FIFO (silently
  *                             dropped if TX_FIFO_STATUS.full is set —
  *                             software should poll first)
  *   0x14 RXDATA          RO   read returns the FIFO front and pops it
  *                             (returns 0 if RX_FIFO_STATUS.empty is set)
  *   0x18 BAUD            RW   DDS phase increment driving BaudGenerator.
  *                             Reset = phaseIncFor(cfg.baudRate,
  *                             cfg.clkFreqHz). Firmware computes the value
  *                             from the system clock it knows it wired in;
  *                             there is no CLKFREQ register because a wrong
  *                             synth value would silently corrupt baud
  *                             rates.
  *   0x1C TX_FIFO_STATUS  RO   [0]=full [1]=empty [15:8]=count
  *                             [23:16]=depth (synth-time capacity)
  *   0x20 RX_FIFO_STATUS  RO   same layout as TX. `empty=1` means no byte
  *                             is queued; firmware checks `!empty` before
  *                             reading RXDATA.
  *   0x24 CFG_INFO        RO   [3:0]=dataBits-1 [5:4]=stopBits [7:6]=parity
  *                             [11:8]=log2(oversample). Per-FIFO depths
  *                             live on the FIFO_STATUS registers, not here.
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
  *   `cfg.stopBits`, `cfg.txFifoDepth` and `cfg.rxFifoDepth` shape the
  *   build (they are NOT runtime-tunable in this version — the format
  *   bits show up read-only in CFG_INFO; the FIFO depths show up in the
  *   per-side FIFO_STATUS registers).
  */
case class UartController(cfg: UartConfig = UartConfig(useCts = false, useRts = false)) extends Component {
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
  // straight on top with no handshake gymnastics. `cfg.txFifoDepth` /
  // `cfg.rxFifoDepth` are typically 16 — the same default as a 16550 —
  // and on UP5K BRAM budgets that's negligible. Splitting them lets
  // asymmetric workloads (e.g. burst-write logger, RX-heavy console)
  // size each side independently.

  val txFifo = StreamFifo(Bits(cfg.dataBits bits), cfg.txFifoDepth)
  val rxFifo = StreamFifo(Bits(cfg.dataBits bits), cfg.rxFifoDepth)

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
  //
  // `STATUS` is intentionally minimal: just the live "TX line is in the
  // middle of shipping a frame" bit. FIFO occupancy moved to the dedicated
  // TX_FIFO_STATUS / RX_FIFO_STATUS registers further down — they each
  // expose full / empty / count / depth in one 32-bit word so firmware
  // can compute free-space `= depth - count` in a single read.

  val STATUS = busif.newReg(doc = "Status register (read-only)")
  val statusTxBusy = STATUS.field(
    Bool(),
    RO,
    doc = "Asserted while a frame is in flight on the TX line."
  )

  statusTxBusy := !txCore.io.data.ready

  // ISR / IER -------------------------------------------------------------
  //
  // ISR fields are W1C: the streaming RxCore emits one-cycle event pulses,
  // `.set()` latches them sticky into the register, and firmware clears a
  // bit by writing 1 to it (read-only access leaves the bit alone). This
  // supports the standard interrupt handler flow: read ISR → mask via IER
  // → wake the bottom-half task → task processes the events and clears
  // their ISR bits → unmask. IER is plain RW; the OR of (ISR & IER)
  // drives the IRQ output.

  val ISR = busif.newReg(doc = "Interrupt status (write 1 to clear)")
  val isrFraming = ISR.field(
    Bool(),
    W1C,
    doc = "Stop bit was sampled low on at least one received frame."
  )
  val isrParity = ISR.field(
    Bool(),
    W1C,
    doc = "Parity bit didn't match data parity. Only meaningful when CFG_INFO.parity != None."
  )
  val isrOverrun = ISR.field(
    Bool(),
    W1C,
    doc = "RX FIFO overflowed; a byte was lost."
  )
  val isrTxDone = ISR.field(
    Bool(),
    W1C,
    doc = "TX FIFO transitioned from non-empty to empty (the last queued byte completed)."
  )
  val isrRxDone = ISR.field(
    Bool(),
    W1C,
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
    doc =
      "Front byte of the RX FIFO. Reading this register pops the FIFO; reads while STATUS.rx_data_avail = 0 return zero."
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
    doc = "phaseInc = round(baudRate * 2^24 / clkFreqHz). The RX side uses phaseInc * oversample internally."
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

  // TX_FIFO_STATUS / RX_FIFO_STATUS ---------------------------------------
  //
  // Per-side FIFO observability, packed so firmware can pull
  // (full, empty, count, depth) in a single 32-bit read and compute
  // free-space `= depth - count` without a second bus turn.
  //
  // Layout (identical for TX and RX):
  //   [0]      full   — push.ready is low
  //   [1]      empty  — pop.valid is low (no byte queued)
  //   [7:2]    reserved
  //   [15:8]   count  — live occupancy (0..depth)
  //   [23:16]  depth  — synth-time capacity (cfg.txFifoDepth /
  //                     cfg.rxFifoDepth); duplicated per side because the
  //                     two sides may be sized independently.

  val TX_FIFO_STATUS = busif.newReg(doc = "TX FIFO status (read-only)")
  val txFifoFull = TX_FIFO_STATUS.field(Bool(), RO, doc = "TX FIFO is full; further TXDATA writes are dropped silently.")
  val txFifoEmpty = TX_FIFO_STATUS.field(Bool(), RO, doc = "TX FIFO is empty (the line will go idle once any in-flight frame finishes).")
  TX_FIFO_STATUS.reserved(6 bits)
  val txFifoCount = TX_FIFO_STATUS.field(UInt(8 bits), RO, doc = "Bytes currently queued in the TX FIFO (0..txFifoDepth).")
  val txFifoDepth = TX_FIFO_STATUS.field(UInt(8 bits), RO, doc = "Synth-time TX FIFO capacity in bytes (= cfg.txFifoDepth).")

  txFifoFull := !txFifo.io.push.ready
  txFifoEmpty := !txFifo.io.pop.valid
  txFifoCount := txFifo.io.occupancy.resize(8 bits)
  txFifoDepth := U(cfg.txFifoDepth, 8 bits)

  val RX_FIFO_STATUS = busif.newReg(doc = "RX FIFO status (read-only)")
  val rxFifoFull = RX_FIFO_STATUS.field(Bool(), RO, doc = "RX FIFO is full; the next received byte will trigger an overrun.")
  val rxFifoEmpty = RX_FIFO_STATUS.field(Bool(), RO, doc = "RX FIFO is empty; firmware should poll for !empty before reading RXDATA.")
  RX_FIFO_STATUS.reserved(6 bits)
  val rxFifoCount = RX_FIFO_STATUS.field(UInt(8 bits), RO, doc = "Bytes currently queued in the RX FIFO (0..rxFifoDepth).")
  val rxFifoDepth = RX_FIFO_STATUS.field(UInt(8 bits), RO, doc = "Synth-time RX FIFO capacity in bytes (= cfg.rxFifoDepth).")

  rxFifoFull := !rxFifo.io.push.ready
  rxFifoEmpty := !rxFifo.io.pop.valid
  rxFifoCount := rxFifo.io.occupancy.resize(8 bits)
  rxFifoDepth := U(cfg.rxFifoDepth, 8 bits)

  // CFG_INFO --------------------------------------------------------------
  //
  // Read-only parameter window so firmware (or a Rust embedded-hal
  // driver) can introspect what the synthesiser actually wired in.
  // None of these are tunable post-synthesis. FIFO depths are NOT
  // mirrored here — they live on the per-side FIFO_STATUS registers
  // because TX and RX may be sized independently.
  //
  // No CLKFREQ register: the system clock that drives BAUD is a
  // physical/synth-time fact, not a tunable; exposing it as RO would
  // invite firmware to trust a value that nothing actually validates.
  // Firmware computes BAUD's phase increment from the clock tree it
  // knows it wired in, the same way an STM32 driver computes BRR.

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

  cfgInfoDataBits := U(cfg.dataBits - 1, 4 bits)
  cfgInfoStopBits := U(cfg.stopBits, 2 bits)
  cfgInfoParity := U(parityCode, 2 bits)
  cfgInfoOsShift := U(osShift, 4 bits)

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
