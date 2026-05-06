package pwm_fade

import spinal.core._

/** Abstract base + factory for waveform shapers.
  *
  * A `Shaper` is a *purely combinational* function `phase -> duty`, both
  * `width`-bit unsigned. Everything stateful (the phase ramp) lives in
  * `PhaseGen`; everything stateful-free (the curve) lives here. Splitting it
  * this way means adding a new waveform is just adding a new `Shaper` subclass
  * — no extra glue.
  *
  * Pattern (mirrors `Debouncer` in ButtonDebouncer)
  *   - `ShaperIO` : reusable `Bundle` declaring the port shape.
  *   - `abstract Shaper` : `Component` carrying that bundle so callers can
  *     write `shaper.io.duty` regardless of which concrete shaper they got
  *     back.
  *   - `Shaper.apply(cfg)`: factory dispatching on `cfg.kind`.
  *
  * The "abstract Component" trick is *elaboration-time polymorphism*: the
  * generated Verilog has no concept of inheritance, but the Scala code that
  * builds the hardware does.
  */
case class ShaperIO(width: Int) extends Bundle {
  val phase = in UInt (width bits)
  val duty = out UInt (width bits)
}

abstract class Shaper(val width: Int) extends Component {
  val io = ShaperIO(width)
}

object Shaper {
  def apply(cfg: ModulatorConfig): Shaper = cfg.kind match {
    case ShaperKind.Identity => IdentityShaper(cfg.width)
    case ShaperKind.Sine     => SineShaper(cfg.width)
    case ShaperKind.Gamma    => GammaShaper(cfg.width, cfg.gamma)
  }
}
