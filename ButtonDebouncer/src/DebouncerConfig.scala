package button

import spinal.core._

object DebounceKind extends SpinalEnum {
  val Integrator, Timer = newElement()
}

case class DebouncerConfig(
    kind: DebounceKind.E,
    width: Int = 16,
    clkFreqHz: Int = 12_000_000,
    debounceMs: Int = 10
)
