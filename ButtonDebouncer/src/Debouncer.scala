package button

import spinal.core._

abstract class Debouncer extends Component {
  val io = DebounceIO()
}

object Debouncer {
  def apply(cfg: DebouncerConfig): Debouncer = {
    cfg.kind match {
      case DebounceKind.Integrator => IntegratorDebouncer(cfg.width)
      case DebounceKind.Timer => TimerDebouncer(
        clkFreqHz = cfg.clkFreqHz,
        debounceMs = cfg.debounceMs
      )
    }
  }
}
