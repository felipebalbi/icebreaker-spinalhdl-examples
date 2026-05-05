package pwm_fade

import spinal.core._

case class IdentityShaper(w: Int) extends Shaper(w) {
  io.duty := io.phase
}
