package button_debouncer

import spinal.core._

/** Abstract base + factory for button debouncers.
  *
  * A `Debouncer` consumes a noisy async input on `io.raw` and produces a clean
  * level on `io.stable` plus one-cycle `rising` / `falling` pulses on each
  * stable transition.
  *
  * Pattern (mirrors `Shaper` in PwmFade)
  *   - `DebounceIO` : reusable `Bundle` declaring the port shape.
  *   - `abstract Debouncer` : `Component` carrying that bundle so callers can
  *     write `debouncer.io.rising` regardless of which concrete subclass they
  *     got back.
  *   - `Debouncer.apply(cfg)` : factory dispatching on `cfg.kind`.
  *
  * The "abstract `Component`" trick is *elaboration-time polymorphism*: the
  * generated Verilog has no concept of inheritance, but the Scala code that
  * builds the hardware does.
  *
  * Why two implementations?
  *   - [[IntegratorDebouncer]] tracks how many recent samples were high vs low
  *     (saturating up-down counter). Snappy and bounce-noise-resistant because
  *     every "wrong" sample drags the counter back towards the other rail.
  *   - [[TimerDebouncer]] arms a one-shot timer when the input changes and only
  *     commits the new level once the input has held steady for a fixed
  *     real-time window. Conceptually simpler; closer to "what a human means by
  *     debouncing".
  */
abstract class Debouncer extends Component {
  val io = DebounceIO()
}

object Debouncer {
  def apply(cfg: DebouncerConfig): Debouncer = cfg.kind match {
    case DebounceKind.Integrator => IntegratorDebouncer(cfg.width)
    case DebounceKind.Timer      =>
      TimerDebouncer(
        clkFreqHz = cfg.clkFreqHz,
        debounceMs = cfg.debounceMs
      )
  }
}
