package pwm_fade

import spinal.core._

/** Passthrough shaper: `duty := phase`.
  *
  * Useful as a baseline (linear ramp = the original behavior) and as a
  * sanity-check fallback. Yosys will optimize the wire away.
  */
case class IdentityShaper(w: Int) extends Shaper(w) {
  io.duty := io.phase
}
