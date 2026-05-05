package pwm_fade

import spinal.core._

/** Compile-time configuration for `Modulator`.
  *
  * Mirrors the `DebouncerConfig` pattern from `ButtonDebouncer`:
  *   - A `SpinalEnum` selects which concrete subclass to build.
  *   - A plain Scala `case class` carries the parameters.
  *
  * `SpinalEnum` is used instead of a vanilla Scala enum because:
  *   - It is the standard Spinal idiom for closed sets of choices.
  *   - It is also usable as a hardware signal if we later add a
  *     runtime mux variant; with vanilla `enum` we would need to
  *     translate at the boundary.
  *
  * Parameters
  *   `kind`  — which `Shaper` subclass to instantiate.
  *   `width` — bit-width shared by `PhaseGen`, `Shaper`, `PwmCore`.
  *             Determines PWM resolution (and period in cycles).
  *   `step`  — how many phase units per `tick` in `PhaseGen`. Larger
  *             step = faster (coarser) phase ramp.
  *   `gamma` — exponent for `GammaShaper` only. Ignored by other
  *             shapers. ~2.2 matches sRGB / human perceptual
  *             brightness reasonably well.
  */
object ShaperKind extends SpinalEnum {
  val Identity, Sine, Gamma = newElement()
}

case class ModulatorConfig(
  kind:  ShaperKind.E,
  width: Int    = 12,
  step:  Int    = 10,
  gamma: Double = 2.2
)
