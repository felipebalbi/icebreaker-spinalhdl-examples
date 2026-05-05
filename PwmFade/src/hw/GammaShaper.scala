package pwm_fade

import spinal.core._

/** Gamma-corrected LUT for perceptually-linear brightness.
  *
  * Why
  *   The eye's brightness response is roughly logarithmic
  *   (Stevens' power law; sRGB encodes ~2.2). PWM duty cycle is
  *   linear in *light energy*. Result: a linear duty ramp looks
  *   fast at the bottom and barely changes at the top.
  *
  *   Pre-distorting with `duty = ((phase/max)^gamma) * max` for
  *   `gamma > 1` compresses small values and stretches large ones,
  *   so a linearly-ramping phase *looks* like a linear brightness
  *   ramp to the eye.
  *
  * Implementation
  *   Same `Mem` + `readAsync` pattern as `SineShaper`. `math.pow`
  *   runs in Scala at elaboration; only integer constants reach
  *   the FPGA — no runtime floating point.
  *
  * Tuning
  *   gamma = 1.0     : identity (within rounding)
  *   gamma = 2.2     : sRGB-ish, default, looks like a real breath
  *   gamma = 2.8-3.0 : even more dramatic compression of low values
  */
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
