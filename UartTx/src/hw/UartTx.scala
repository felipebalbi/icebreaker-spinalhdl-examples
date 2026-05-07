package uart_tx

import spinal.core._
import spinal.lib._

/** UART transmitter (TX-only half).
  *
  * Top-level shape: a Stream of bytes in, a serial line out, plus a CTS input
  * for (optional, currently unused) hardware flow control.
  *
  * Frame format on the wire (8N1 by default): idle : line stays high start :
  * single low bit, one bit period long data : `cfg.dataBits` bits, **LSB
  * first** (UART convention) parity : optional, one bit (when
  * `cfg.parity != None`) — not yet wired stop : `cfg.stopBits` high bits idle :
  * line returns high
  *
  * Internally this composes:
  *   - a [[BaudGenerator]] producing one tick per bit period
  *   - a shift register holding the byte being transmitted
  *   - an FSM stepping idle → start → data (→ parity) → stop → idle
  *
  * The Stream handshake on `io.data` provides natural back-pressure:
  * `data.ready` is held low while a frame is in flight, so the producer (or an
  * upstream FIFO) waits without losing bytes.
  *
  * @param cfg
  *   compile-time configuration (clock freq, baud, frame format)
  */
case class UartTx(cfg: UartTxConfig) extends Component {
  val io = new Bundle {

    /** Byte input, ready/valid handshake.
      *
      * Producer drives `data.valid` with a byte on `data.payload`. UartTx
      * asserts `data.ready` only when it is idle (and `cts` permits). A
      * transfer occurs on any cycle where both are high; from that cycle the
      * byte is "owned" by UartTx and shifted out over the next ~10 bit periods,
      * during which `data.ready` stays low.
      */
    val data = slave Stream (Bits(cfg.dataBits bits))

    /** Serial output line. Idles high. Drops low for the start bit, then
      * carries `cfg.dataBits` data bits LSB-first, then `cfg.stopBits` high
      * stop bits, then returns to idle.
      */
    val tx = out Bool ()

    /** Clear-To-Send input from the far end (active high = "I can receive").
      *
      * This is the *local* TX's CTS input, fed by the *remote* RX's RTS output
      * via the cable. When low, UartTx must not start a new frame — it gates
      * `data.ready` so the producer is held off until the far end has buffer
      * space again. Currently wired but unused; it will be AND-ed with the
      * FSM's idle signal once the FSM exists.
      */
    val cts = in Bool ()
  }

  val baud = BaudGenerator(cfg)
  val sreg = TxShiftReg(cfg)
  val fsm = TxFsm(cfg)

  baud.io.enable := fsm.io.busy // tick only while transmitting
  fsm.io.tick := baud.io.tick

  sreg.io.load := fsm.io.loadReg
  sreg.io.data := io.data.payload
  sreg.io.shift := fsm.io.shiftReg
  fsm.io.shiftRegBit := sreg.io.bit

  // Stream handshake + CTS gating
  val canStart = !fsm.io.busy && io.cts
  fsm.io.start := io.data.valid && canStart
  io.data.ready := canStart // accept the cycle FSM starts

  io.tx := fsm.io.txBit
}

/** Verilog generation entry point.
  *
  * For now this generates the bare `UartTx` core. Once a `UartTxTop` wrapper
  * exists (clock domain + pin mapping for the iCE40), swap the
  * `generateVerilog(...)` argument to that.
  *
  * Run with: `sbt "runMain uart_tx.UartTxTopVerilog"` (or `make`).
  */
object UartTxVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(UartTx(UartTxConfig()))
  }
}
