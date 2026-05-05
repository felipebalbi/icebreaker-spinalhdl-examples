package pwm_fade

import spinal.core._

case class GammaShaper(w: Int, gamma: Double = 2.2) extends Shaper(w) {
  require(gamma > 0.0, "gamma must be > 0")

  private val depth = 1 << w
  private val maxV  = depth - 1

  // duty = ((phase / max)^gamma) * max
  // Pre-distorts a linear phase so it *looks* linear to the eye.
  private val table: Seq[UInt] = (0 until depth).map { i =>
    val norm = i.toDouble / maxV.toDouble
    val v    = math.pow(norm, gamma) * maxV.toDouble
    U(math.round(v).toLong, w bits)
  }

  val rom = Mem(UInt(w bits), initialContent = table)

  io.duty := rom.readAsync(io.phase)
}
