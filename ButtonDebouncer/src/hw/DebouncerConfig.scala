package button_debouncer

import spinal.core._

/** Closed set of debouncer implementations.
  *
  * Members are added to this `SpinalEnum` (rather than a vanilla Scala
  * `enum`) so the same value can later be promoted to a hardware signal
  * if we ever want a runtime-selectable debouncer mux. For now it is
  * only consumed at elaboration time by [[Debouncer.apply]].
  */
object DebounceKind extends SpinalEnum {
  val Integrator, Timer = newElement()
}

/** Compile-time configuration for [[Debouncer]].
  *
  * Pattern (mirrors `ModulatorConfig` in PwmFade):
  *   - `kind` selects which concrete subclass to instantiate.
  *   - The remaining parameters are read by *some* but not all subclasses
  *     (`width` is integrator-only; `clkFreqHz`/`debounceMs` are timer-only).
  *     Carrying them all in one `case class` keeps the call site simple at
  *     the cost of a few unused defaults — acceptable for a teaching example.
  *
  * @param kind
  *   which `Debouncer` subclass to instantiate.
  * @param width
  *   integrator counter width in bits. Larger = more bounces tolerated before
  *   the output flips. At 12 MHz the integrator's "fully decided" time is
  *   roughly `2^width` cycles when the input is steadily high or low.
  * @param clkFreqHz
  *   system clock frequency in Hz, used by [[TimerDebouncer]] to size its
  *   countdown.
  * @param debounceMs
  *   debounce window in milliseconds, used by [[TimerDebouncer]]. ~10 ms is
  *   the canonical "tactile button" choice.
  */
case class DebouncerConfig(
    kind: DebounceKind.E,
    width: Int = 16,
    clkFreqHz: Int = 12_000_000,
    debounceMs: Int = 10
)
