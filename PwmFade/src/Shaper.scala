package pwm_fade

import spinal.core._

case class ShaperIO(width: Int) extends Bundle {
  val phase = in  UInt(width bits)
  val duty  = out UInt(width bits)
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
