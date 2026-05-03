package button

import spinal.core._

case class DebounceIO() extends Bundle {
  val raw = in Bool() // raw button input
  val stable = out Bool() // debounced level
  val rising = out Bool() // rising ede pulse
  val falling = out Bool() // falling edge pulse
}
