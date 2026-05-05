package pwm_fade

import spinal.core._

object ShaperKind extends SpinalEnum {
  val Identity, Sine, Gamma = newElement()
}

case class ModulatorConfig(
  kind:  ShaperKind.E,
  width: Int    = 12,
  step:  Int    = 10,
  gamma: Double = 2.2
)
