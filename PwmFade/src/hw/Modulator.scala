package pwm_fade

import spinal.core._

/** Composite duty modulator: `PhaseGen` + selected `Shaper`.
  *
  * The only thing `PwmFadeTop` needs to know about. Hides the
  * phase-vs-shape split behind a single (tick in, duty out) port.
  *
  * Pattern
  *   - `Modulator` is an abstract `Component` carrying `ModulatorIO`.
  *   - `ModulatorImpl` is the concrete composite that instantiates
  *     children and wires them.
  *   - `Modulator.apply(cfg)` is the factory; callers use it instead
  *     of `new ModulatorImpl(cfg)` so future variants (e.g. CORDIC,
  *     LFSR-driven dithering, ...) can be added without touching
  *     callers.
  *
  * `setDefinitionName(...)` renames the generated Verilog module to
  * include the shaper kind (e.g. `Modulator_Sine`). Helpful when
  * reading synthesis reports — otherwise everything would be called
  * `ModulatorImpl`.
  */
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
