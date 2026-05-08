package uart

import spinal.core._
import spinal.lib._

/** Minimal hardware echo demo: receive a byte, send it right back.
  *
  * This is the smallest possible end-to-end test of the entire UART
  * stack. If you can attach `picocom` to the board, type a key, and see
  * it appear back in the terminal, then *every* part of the design is
  * working: TX path, RX path, clock domain, reset wiring, baud match,
  * pcf pins, idle-line behaviour. It is the milestone hardware test.
  *
  * Composition:
  *   - [[UartRx]] takes the line in and produces a Stream of bytes
  *   - a [[StreamFifo]] buffers up to `fifoDepth` bytes between RX and TX
  *   - [[UartTx]] drains the FIFO back out
  *
  * The FIFO matters even though RX and TX run at the same baud:
  * dropping it would couple the two halves' back-pressure, so any TX
  * stall (start/stop bit timing, mid-frame) would force the RX to
  * report overrun. With even a 1-deep FIFO, RX can finish a frame
  * while TX is mid-transmit. Default depth 16 is a free safety margin.
  *
  * Flow control is OFF on both sides (`useCts = false`, `useRts =
  * false`) so this demo works against a vanilla USB-UART and a default
  * `picocom` configuration.
  *
  * Same `ClockDomain` pattern as [[UartTxDemo]]: the iCE40's `reset`
  * pin is wired explicitly (RISING / ASYNC / active-LOW) so the user
  * button actually resets the design.
  *
  * @param cfg
  *   Compile-time UART configuration. Defaults to 8N1 with no flow
  *   control.
  * @param fifoDepth
  *   Bytes the buffer between RX and TX can hold.
  */
case class UartEchoDemo(
  cfg: UartConfig = UartConfig(useCts = false, useRts = false),
  fifoDepth: Int = 16
) extends Component {
  require(
    !cfg.useCts && !cfg.useRts,
    "UartEchoDemo wires no flow-control pins; use cfg.useCts = false and cfg.useRts = false"
  )

  val io = new Bundle {
    /** The board's free-running 12 MHz clock (pcfg maps to pin 32). */
    val clk = in Bool()

    /** Active-LOW reset from the iCEbreaker user button (pcf maps to
      * pin 10). The button pulls the line high through a pull-up and
      * shorts it to ground when pressed., so "reset asserted" means
      *  the pin is at 0 V.
      */
    val reset = in Bool()

    /** Serial input from the host's USB-UART TX (pcf maps to pin 6).
      * Idles high; UART frames per `cfg`.
      */
    val rx = in Bool()

    /** Serial output to the host's USB-UART RX (pcf maps to pin 9).
      * Idles high; UART frames per `cfg`.
      */
    val tx = out Bool()
  }

  // Build an explicit ClockDomain rather than relying on Spinal's
  // implicit default. This is the only way to wire the `reset`
  // pin into the design; without it the pin is unconnected and the
  // user button does nothing.
  //
  //   - clockEdge        = RISING : standard for this part.
  //   - resetActiveLevel = LOW    : matches the iCEbreaker user
  //                                 button (pulled high, grounded
  //                                 when pressed).
  //   - resetKind        = ASYNC  : the button isn't synchronous to
  //                                 anything — assertion is async.
  //                                 Spinal will still register it
  //                                 on the way in via the default
  //                                 reset BufferCC.
  val mainClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = LOW
    )
  )

  val core = new ClockingArea(mainClockDomain) {

    // ----- pure UART halves --------------------------------------------------
    
    val rx = UartRx(cfg)
    val tx = UartTx(cfg)
    
    // ----- echo FIFO ---------------------------------------------------------
    //
    // Sits between RX and TX so a brief TX stall doesn't cause RX to
    // overrun. Naturally back-pressures: when the FIFO fills,
    // `push.ready` falls and RX raises `overrun` — but in practice
    // 16 bytes is wildly more than we'll ever queue at matched baud.
    val fifo = StreamFifo(Bits(cfg.dataBits bits), fifoDepth)
    
    // ----- wiring ------------------------------------------------------------
    
    rx.io.rx       := io.rx
    fifo.io.push   << rx.io.payload
    tx.io.data     << fifo.io.pop
    
    // ----- registered output ------------------------------------------------
    //
    // Same idle-high reset trick as UartTxDemo. Costs one flop, gives
    // a clean high level on the line during the first cycle after
    // reset before the FSM has its bearings.
    val txOut = RegNext(tx.io.tx) init(True)
  }

  io.tx := core.txOut
}

/** Verilog generation entry point for the echo demo.
  *
  * Run via `make` — set the Makefile's TOP variable to `UartEchoDemo`
  * and this is what `gen/UartEchoDemo.v` is built from.
  */
object UartEchoDemoVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(UartEchoDemo())
  }
}
