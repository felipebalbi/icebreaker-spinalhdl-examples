package pwm_fade

import spinal.core._

case class SineShaper(w: Int) extends Shaper(w) {
  private val depth = 1 << w
  private val maxV  = depth - 1

  // Build the LUT in Scala at elaboration time. Maps phase 0..N-1 to
  // a smooth full-period rise-and-fall in 0..max.
  private val table: Seq[UInt] = (0 until depth).map { i =>
    val v = 0.5 * (1.0 - math.cos(2.0 * math.Pi * i / depth)) * maxV
    U(math.round(v).toLong, w bits)
  }

  val rom = Mem(UInt(w bits), initialContent = table)

  io.duty := rom.readAsync(io.phase)
}
