package uart

import spinal.core._
import spinal.lib._

/** System-integration top wrapping the pure [[UartTx]] core for hardware
  * bring-up on the iCEbreaker.
  *
  * `UartTx` itself is a self-contained Stream-fed transmitter with no opinion
  * on where the bytes come from. This wrapper is the "application" layer:
  *
  *   - it owns the **clock domain** (12 MHz on `clk`, async reset on `reset`,
  *     active-LOW because the on-board button pulls high and shorts to ground
  *     when pressed),
  *   - it instantiates a **`StreamFifo`** in front of the UART so a bursty
  *     producer can run ahead without losing bytes when the line is busy,
  *   - it provides the **stimulus** — a small ROM that cycles through a fixed
  *     message (`"Hello, World\r\n"` by default) and pushes each byte into the
  *     FIFO. Naturally throttled by the FIFO's `push.ready`: when the FIFO
  *     fills, the index stops advancing so no byte is ever dropped.
  *
  * Why FIFO at this layer and not inside `UartTx`?
  *   - depth/width are application concerns (a CPU bridge wants something
  *     different from a "blink hello world" demo);
  *   - many producers already have buffering upstream and don't want to pay for
  *     a duplicate;
  *   - keeps `UartTx`'s public surface a single Stream, which is the standard
  *     SpinalHDL "byte producer" interface.
  *
  * The whole component is constructed inside an explicit [[ClockDomain]] so the
  * iCE40's `reset` pin is honoured: anything that doesn't consume a clock
  * domain (e.g. relying on Spinal's implicit default) would silently make the
  * reset pin a no-op — a class of bug that's invisible until the board
  * misbehaves.
  *
  * @param cfg
  *   Compile-time UART configuration. Defaults to 8N1 with no CTS, matching
  *   what a USB-UART cable on a desk speaks.
  * @param message
  *   ASCII string to broadcast in a loop. Each character is one byte. Use
  *   `\r\n` if your terminal needs explicit carriage returns. Default is
  *   `"Hello, World\r\n"`.
  * @param fifoDepth
  *   Number of bytes the FIFO can hold. Doesn't really matter for the "spam a
  *   fixed string" stimulus (the producer can always re-push later) but useful
  *   when wiring up a real producer that produces in bursts.
  */
case class UartTxDemo(
    cfg: UartTxConfig = UartTxConfig(useCts = false),
    message: String = "Hello, World\r\n",
    fifoDepth: Int = 16
) extends Component {
  require(message.nonEmpty, "message must not be empty")
  require(cfg.dataBits >= 7, "ASCII needs at least 7 data bits")
  require(
    !cfg.useCts,
    "UartTxDemo does not expose a CTS pin; use cfg.useCts = false"
  )

  val io = new Bundle {

    /** The board's free-running 12 MHz clock (pcf maps to pin 35). */
    val clk = in Bool ()

    /** Active-LOW reset from the iCEbreaker user button (pcf maps to pin 10).
      * The button pulls the line high through a pull-up and shorts it to ground
      * when pressed, so "reset asserted" means the pin is at 0 V.
      */
    val reset = in Bool ()

    /** Serial output to the host's USB-UART RX. Idles high; UART frames per
      * `cfg`.
      */
    val tx = out Bool ()
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

    // ----- pure UART core --------------------------------------------------

    val uart = UartTx(cfg)

    // ----- byte FIFO -------------------------------------------------------
    //
    // Stands between the message ROM (producer) and the UART's data
    // Stream (consumer). Provides natural back-pressure: when the
    // UART is busy, push.ready falls and the producer parks until
    // the line drains. Without this, a faster producer (e.g. a CPU
    // writing every cycle) would overrun the UART.
    val fifo = StreamFifo(Bits(cfg.dataBits bits), fifoDepth)

    // ----- message ROM + index counter ------------------------------------
    //
    // Build a Vec of constants from the Scala-level `message`
    // string; the synthesizer turns this into a tiny LUT-ROM. For
    // long messages a `Mem(...).initialContent(...)` would be
    // friendlier to BRAM, but at this size LUTs are fine.
    val payloadMask = (1 << cfg.dataBits) - 1
    val rom = Vec(
      message.map(c => B(c.toInt & payloadMask, cfg.dataBits bits))
    )

    // Index of the *next* byte to push. Width sized to fit the
    // message length exactly. `log2Up` returns 0 for length 1, so
    // floor at 1 to avoid a zero-width register.
    val idx = Reg(UInt(log2Up(message.length).max(1) bits)) init (0)

    // Continuously offer the current ROM byte to the FIFO. The byte
    // only "lands" when push.fire (valid && ready) — that's the
    // cycle we advance the index. When the FIFO is full,
    // push.ready=0, fire doesn't trigger, idx holds steady, no byte
    // is lost.
    fifo.io.push.valid := True
    fifo.io.push.payload := rom(idx)
    when(fifo.io.push.fire) {
      idx := Mux(
        idx === message.length - 1,
        U(0, idx.getWidth bits),
        idx + 1
      )
    }

    // ----- UART consumes the FIFO ------------------------------------------

    uart.io.data << fifo.io.pop

    // ----- registered output ----------------------------------------------
    //
    // Register the line one extra time on the way out. iCE40 IO
    // cells already have a flop, but the explicit `init(True)` here
    // pins down the post-reset value (idle high) and avoids any
    // routing-induced glitch around the first post-reset cycle. UART
    // receivers tolerate a bit period or two of garbage at startup,
    // but it costs us nothing to be tidy.
    val txOut = RegNext(uart.io.tx) init (True)
  }

  io.tx := core.txOut
}

/** Verilog generation entry point for the bring-up wrapper.
  *
  * Run via `make` — the Makefile's TOP variable is set to `UartTxDemo`, so this
  * is what `gen/UartTxDemo.v` is built from.
  */
object UartTxDemoVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      targetDirectory = "gen"
    ).generateVerilog(UartTxDemo())
  }
}
