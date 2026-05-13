package button_debouncer

import spinal.core._
import spinal.lib._

/** One-shot-timer debouncer.
  *
  * Strategy
  *   - Cross the async input into the clock domain with `BufferCC`.
  *   - Track a `candidate` value: the most recent value the input took.
  *     Whenever `btnSync` differs from `candidate`, latch the new value and
  *     reset a countdown counter.
  *   - When the input has held steady at `candidate` for the full
  *     `debounceCycles` window, commit `state := candidate`.
  *
  * Why this works Any bounce within the window restarts the countdown, so the
  * `state` register only ever updates after a real `debounceMs` of stillness.
  * Conceptually identical to the Arduino-style "millis() since last change"
  * idiom, just expressed in clocked logic.
  *
  * Sizing
  *   - `debounceCycles = clkFreqHz / 1000 * debounceMs` gives the count; an
  *     `if`-guard pins the lower bound at 1 cycle to keep the counter width
  *     sensible if a degenerate `debounceMs = 0` ever sneaks in.
  *   - `log2Up(maxCount)` widths the counter exactly; no wasted bits.
  */
case class TimerDebouncer(
    clkFreqHz: Int,
    debounceMs: Int
) extends Debouncer {
  setDefinitionName(s"TimerDebouncer_${clkFreqHz}_${debounceMs}ms")

  require(clkFreqHz > 0, "clkFreqHz must be > 0")
  require(debounceMs >= 0, "debounceMs must be >= 0")

  // Sync the async input.
  val btnSync = BufferCC(io.raw)

  // Convert ms -> clock cycles. Floor at 1 so log2Up below never
  // sees 0.
  val debounceCycles = (clkFreqHz / 1000) * debounceMs
  val maxCount = if (debounceCycles <= 1) 1 else debounceCycles

  // Width sized exactly to fit `maxCount - 1`.
  val counter = Reg(UInt(log2Up(maxCount) bits)) init (0)

  // `candidate` tracks the input's most recent value; `state` is the
  // committed debounced output that consumers see.
  val state = Reg(Bool()) init (False)
  val candidate = Reg(Bool()) init (False)

  when(btnSync =/= candidate) {
    // New value seen — restart the countdown.
    candidate := btnSync
    counter := 0
  } otherwise {
    when(counter =/= (maxCount - 1)) {
      counter := counter + 1
    } otherwise {
      // Held steady for the full window; commit.
      state := candidate
    }
  }

  io.stable := state

  // One-cycle edge pulses, same shape as IntegratorDebouncer's outputs.
  val prev = RegNext(state) init (False)
  io.rising := state && !prev
  io.falling := !state && prev
}
