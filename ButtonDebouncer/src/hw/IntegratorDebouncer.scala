package button_debouncer

import spinal.core._
import spinal.lib._

/** Saturating up/down-counter debouncer.
  *
  * Strategy
  *   - Cross the async input into the clock domain with `BufferCC`.
  *   - Each cycle, increment the counter when the synchronised input is high;
  *     decrement when it is low. Saturate at `0` and `2^width - 1`.
  *   - Latch `state := True` once the counter saturates high and `state :=
  *     False` once it saturates low.
  *
  * Why this works Bounces look like brief excursions to the wrong rail in the
  * middle of a long run of correct samples. Each wrong sample only undoes one
  * cycle of progress, so a few bounces in 2^width cycles cannot flip the
  * output.
  *
  * Choosing `width` Roughly: minimum debounce time at 12 MHz is `2^width /
  * clkFreqHz`. For width=16 that is ~5.5 ms; width=18 gives ~22 ms. Tactile
  * buttons settle in well under 10 ms, so width=16 is a reasonable default.
  *
  * Notes
  *   - The output `state` only ever changes on a *full* saturation, so the
  *     `rising`/`falling` pulses are guaranteed to fire at most once per real
  *     press / release.
  *   - `state` is registered, so `rising = state && !prev` cannot glitch. The
  *     dedicated `prev` register avoids the level-vs-edge CDC trap that biting
  *     `io.raw` directly would suffer from.
  */
case class IntegratorDebouncer(width: Int = 16) extends Debouncer {
  // Friendly module name in the generated Verilog. Without this both
  // debouncer subclasses would just be called `Debouncer`.
  setDefinitionName(s"IntegratorDebouncer_$width")

  // Async -> sync. Two-flop chain by default; sufficient for slow human
  // inputs.
  val btnSync = BufferCC(io.raw)

  // Saturating up/down counter.
  val counter = Reg(UInt(width bits)) init (0)
  when(btnSync) {
    when(counter =/= counter.maxValue) {
      counter := counter + 1
    }
  } otherwise {
    when(counter =/= 0) {
      counter := counter - 1
    }
  }

  // Debounced level. Only updates when the counter saturates.
  val state = Reg(Bool()) init (False)
  when(counter === counter.maxValue) {
    state := True
  }
  when(counter === 0) {
    state := False
  }

  io.stable := state

  // Edge detection on the *registered* state. `prev` lags `state` by one
  // cycle, so `rising` is a one-cycle pulse on every transition.
  val prev = RegNext(state) init (False)
  io.rising := state && !prev
  io.falling := !state && prev
}
