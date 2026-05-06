package pwm_fade

import spinal.core._

/** Sine-shaped LUT.
  *
  * Maps phase in [0, N-1] to duty = round(0.5*(1 - cos(2*pi*i/N))*max).
  * That's a smooth full-period rise-and-fall in [0, max]. Combined
  * with `PhaseGen`'s triangle phase you get a "sine of a triangle":
  * still symmetric, but with softer ends than the linear ramp.
  *
  * Implementation
  *   `Mem[T](dataType, initialContent)` is Spinal's hardware-memory
  *   primitive. We compute the table values in Scala using `math.cos`
  *   at *elaboration* time; they get baked into the generated Verilog
  *   as constants (and inferred into BRAM by Yosys/nextpnr).
  *
  *   `readAsync(addr)` is a combinational read (no output register),
  *   matching the "Shaper is purely combinational" model. If timing
  *   ever became tight we could swap to `readSync` for a 1-cycle
  *   latency penalty — at 12 MHz this won't be needed.
  *
  * Memory cost
  *   `2^width * width` bits. At width=12: 4096 * 12 = ~6 KB,
  *   fits comfortably in iCE40 BRAM (UP5K has 120 Kbit BRAM).
  */
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
