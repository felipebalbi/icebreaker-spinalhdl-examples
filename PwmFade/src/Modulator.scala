package pwm_fade

import spinal.core._

case class ModulatorIO(width: Int) extends Bundle {
  val tick = in  Bool()
  val duty = out UInt(width bits)
}

abstract class Modulator(val width: Int) extends Component {
  val io = ModulatorIO(width)
}

object Modulator {
  def apply(cfg: ModulatorConfig): Modulator = new ModulatorImpl(cfg)
}

class ModulatorImpl(cfg: ModulatorConfig) extends Modulator(cfg.width) {
  setDefinitionName(s"Modulator_${cfg.kind.toString}")

  val phaseGen = PhaseGen(cfg.width, cfg.step)
  val shaper   = Shaper(cfg)

  phaseGen.io.tick := io.tick
  shaper.io.phase  := phaseGen.io.phase
  io.duty          := shaper.io.duty
}
