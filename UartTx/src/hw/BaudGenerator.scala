package uart_tx

import spinal.core._

/** Baud-rate generator using Direct Digital Synthesis (DDS).
  *
  * Why DDS instead of a divide-by-N counter?
  *   A counter approach picks `N = round(clkFreqHz / baudRate)` and pulses
  *   every N cycles. The achievable rate is then quantised to clkFreqHz/N,
  *   which can carry several percent error for awkward (clk, baud) pairs.
  *   DDS keeps a fractional phase, so the *long-term* average rate matches
  *   the requested baud to ppm-level accuracy. Individual bit periods
  *   jitter by ±1 system clock, which is irrelevant for UART (the receiver
  *   samples in the middle of each bit).
  *
  * How it works:
  *   Each clock cycle, add a constant `phaseInc` to the phase accumulator.
  *   The accumulator is `accWidth + 1` bits wide; the bottom `accWidth`
  *   bits hold the running phase, and the extra MSB captures the carry-out
  *   of the addition. Whenever the addition overflows the `accWidth` bits,
  *   the MSB pulses high for one cycle — that is our baud tick.
  *
  *   By masking the MSB to 0 each cycle (via `acc(accWidth-1 downto 0)`
  *   then resizing back up), we ensure the MSB is purely "did we just
  *   overflow?" and not a sticky high bit.
  *
  * Phase increment math:
  *   tick_rate = clkFreqHz * phaseInc / 2^accWidth
  *   => phaseInc = baudRate * 2^accWidth / clkFreqHz
  *
  *   With accWidth=24 and 12 MHz / 115200 baud:
  *     phaseInc ≈ 161061, actual baud ≈ 115199.81 (~0.0002% error).
  *
  * @param cfg       UART config; supplies clkFreqHz and baudRate.
  * @param accWidth  Phase accumulator width in bits. Larger = more accurate
  *                  baud rate (and slightly more LUTs). 24 is plenty for
  *                  any realistic UART.
  */
case class BaudGenerator(cfg: UartTxConfig, accWidth: Int = 24) extends Component {
  val io = new Bundle {
    /** Hold low to keep the generator quiescent (acc = 0, no ticks).
      * Raise high to start producing ticks at the configured baud. The
      * TX FSM holds this low when idle and raises it the cycle a byte
      * is accepted, so the *first* tick lands a clean full bit period
      * later — giving a properly-sized start bit.
      */
    val enable = in Bool()

    /** One-cycle pulse, on average once every `clkFreqHz / baudRate`
      * cycles. Registered, so glitch-free.
      */
    val tick   = out Bool()
  }

  // Round-to-nearest gives slightly better long-term accuracy than the
  // default truncating integer division. Difference is microscopic at
  // accWidth=24 but free, so why not.
  val phaseInc: Long = (
    ((BigInt(cfg.baudRate) << accWidth) + (BigInt(cfg.clkFreqHz) >> 1)) / cfg.clkFreqHz
  ).toLong

  /** Phase accumulator. The extra MSB captures overflow from the add. */
  val acc = Reg(UInt(accWidth + 1 bits)) init(0)

  when(!io.enable) {
    acc := 0
  } otherwise {
    // Take only the bottom accWidth bits of the previous accumulator
    // (i.e. clear last cycle's carry), zero-extend, then add phaseInc.
    // Any carry into bit `accWidth` is this cycle's tick.
    acc := acc(accWidth - 1 downto 0).resize(accWidth + 1) + phaseInc
  }

  // Register the tick to keep the output glitch-free and timing clean.
  // Costs 1 cycle of latency, irrelevant at baud-rate timescales.
  io.tick := RegNext(acc.msb) init(False)
}
