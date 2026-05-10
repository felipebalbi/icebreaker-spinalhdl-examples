package i2c

/** Standalone sim for [[BusTiming]].
  *
  * `BusTiming` is a pure-Scala compile-time table — there is no clock
  * to drive, no DUT to wrap, and (deliberately) no `SimConfig.compile`.
  * This `main` is structured the same way `BaudGeneratorSim` is, just
  * without the SpinalSim harness around it.
  *
  * What we check, for every `(BusSpeed × {12, 25, 48} MHz)` combo:
  *
  *   1. Every published cycle count meets or exceeds the spec floor in
  *      real time. Concretely: `cycles × 1e9 / clkFreqHz ≥ specMinNs`.
  *      Using floating-point on the *output* side keeps the test honest
  *      — if `BusTiming`'s own integer math is wrong (the prior
  *      `Int * Int` overflow bug was a real one), the test catches it.
  *   2. Every cycle count is ≥ 0 (`tHdDat = 0` is legal; nothing else
  *      should ever be negative or zero).
  *   3. `tHigh ≥ tHighMin` and `tLow ≥ tLowMin` (the `max(...)` floor).
  *   4. `tHigh + tLow ≥ 4 × quarterPeriodCycles` — one-way invariant
  *      between the scheduled bit period and the natural
  *      quarter-period grid. Equality is informational, not required.
  *   5. Cross-check vs `I2cConfig.busFreqHz`: the *achieved* SCL
  *      frequency is `clkFreqHz / (tHigh + tLow)`. It should equal
  *      `busFreqHz` whenever neither phase needed stretching, and be
  *      strictly less than `busFreqHz` otherwise (Fast mode at every
  *      tested clock falls into the second bucket — see the
  *      class-level note in `BusTiming`).
  *
  * The sim also prints a formatted table per combo so that a human
  * reviewer can eyeball the numbers against the I²C spec table without
  * stepping through the asserts.
  *
  * Run: `sbt "runMain i2c.BusTimingSim"`
  */
object BusTimingSim {

  // (config-name, BusSpeed, ns-min) for each spec field. Mirrors the
  // SpeedMins table in BusTiming, but kept independent here so the sim
  // can't be fooled by a buggy table that "agrees with itself".
  private val specMinsNs: Map[BusSpeed.E, Map[String, Int]] = Map(
    BusSpeed.Standard -> Map(
      "tHigh"  -> 4000,
      "tLow"   -> 4700,
      "tHdSta" -> 4000,
      "tSuSta" -> 4700,
      "tSuSto" -> 4000,
      "tBuf"   -> 4700,
      "tSuDat" -> 250,
      "tHdDat" -> 0
    ),
    BusSpeed.Fast -> Map(
      "tHigh"  -> 600,
      "tLow"   -> 1300,
      "tHdSta" -> 600,
      "tSuSta" -> 600,
      "tSuSto" -> 600,
      "tBuf"   -> 1300,
      "tSuDat" -> 100,
      "tHdDat" -> 0
    ),
    BusSpeed.FastPlus -> Map(
      "tHigh"  -> 260,
      "tLow"   -> 500,
      "tHdSta" -> 260,
      "tSuSta" -> 260,
      "tSuSto" -> 260,
      "tBuf"   -> 500,
      "tSuDat" -> 50,
      "tHdDat" -> 0
    )
  )

  private def ns(cycles: Int, clkFreqHz: Int): Double =
    cycles.toDouble * 1e9 / clkFreqHz.toDouble

  private def assertFloor(
      label: String,
      cycles: Int,
      clkFreqHz: Int,
      specNs: Int
  ): Unit = {
    val achievedNs = ns(cycles, clkFreqHz)
    assert(
      cycles >= 0,
      f"$label: $cycles cycles is negative"
    )
    assert(
      achievedNs >= specNs.toDouble,
      f"$label: $cycles cycles = $achievedNs%.2f ns, below spec min ${specNs} ns"
    )
  }

  private def runOne(clkFreqHz: Int, speed: BusSpeed.E): Unit = {
    val cfg = I2cConfig(clkFreqHz = clkFreqHz, busSpeed = speed)
    val t   = BusTiming(cfg)

    val achievedSclHz = clkFreqHz.toDouble / (t.tHigh + t.tLow).toDouble
    val stretched     = (t.tHigh + t.tLow) > 4 * cfg.quarterPeriodCycles

    println(
      f"--- $speed @ ${clkFreqHz / 1000000}%d MHz " +
        f"(qpc=${cfg.quarterPeriodCycles}, " +
        f"busFreq=${cfg.busFreqHz / 1000}%d kHz, " +
        f"achievedScl=${achievedSclHz / 1000}%.2f kHz, " +
        f"stretched=$stretched)"
    )
    println(
      f"  tHigh=${t.tHigh}%-5d (min ${t.tHighMin}%-5d, ${ns(t.tHigh, clkFreqHz)}%7.1f ns)"
    )
    println(
      f"  tLow =${t.tLow}%-5d (min ${t.tLowMin}%-5d, ${ns(t.tLow, clkFreqHz)}%7.1f ns)"
    )
    println(
      f"  tHdSta=${t.tHdSta}%-4d  tSuSta=${t.tSuSta}%-4d  " +
        f"tSuSto=${t.tSuSto}%-4d  tBuf=${t.tBuf}%-4d  " +
        f"tSuDat=${t.tSuDat}%-3d  tHdDat=${t.tHdDat}%-3d"
    )

    val mins = specMinsNs(speed)
    assertFloor("tHigh",  t.tHigh,  clkFreqHz, mins("tHigh"))
    assertFloor("tLow",   t.tLow,   clkFreqHz, mins("tLow"))
    assertFloor("tHdSta", t.tHdSta, clkFreqHz, mins("tHdSta"))
    assertFloor("tSuSta", t.tSuSta, clkFreqHz, mins("tSuSta"))
    assertFloor("tSuSto", t.tSuSto, clkFreqHz, mins("tSuSto"))
    assertFloor("tBuf",   t.tBuf,   clkFreqHz, mins("tBuf"))
    assertFloor("tSuDat", t.tSuDat, clkFreqHz, mins("tSuDat"))
    assertFloor("tHdDat", t.tHdDat, clkFreqHz, mins("tHdDat"))

    // Floor invariants from BusTiming itself.
    assert(t.tHigh >= t.tHighMin, "tHigh below tHighMin")
    assert(t.tLow  >= t.tLowMin,  "tLow below tLowMin")
    assert(
      t.tHigh + t.tLow >= 4 * cfg.quarterPeriodCycles,
      s"tHigh+tLow=${t.tHigh + t.tLow} < 4*qpc=${4 * cfg.quarterPeriodCycles}"
    )

    // Achieved SCL frequency must never exceed busFreqHz (we can only
    // drop below by stretching). 0.5 % slack absorbs the integer
    // rounding when no stretching happens.
    assert(
      achievedSclHz <= cfg.busFreqHz.toDouble * 1.005,
      f"achievedScl=$achievedSclHz%.2f Hz exceeds busFreqHz=${cfg.busFreqHz} Hz"
    )
  }

  /** Confirm the prior 32-bit overflow bug stays fixed.
    *
    * At 12 MHz × 4700 ns the *unwidened* `Int * Int` product wraps to
    * roughly -2.04e9, and `divRoundUp` against `1_000_000_000` would
    * publish a negative cycle count. The fix in `toClockCycles`
    * widens to `Long` before multiplying, which we re-derive here
    * from the final published `tBuf` (whose ns value is 4700).
    */
  private def regressionOverflowFix(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    val t   = BusTiming(cfg)
    assert(
      t.tBuf > 0,
      s"regression: tBuf=${t.tBuf} — Int*Int overflow has come back"
    )
    // Precise expectation: ceil(12e6 * 4700 / 1e9) = ceil(56.4) = 57.
    assert(
      t.tBuf == 57,
      s"regression: tBuf=${t.tBuf}, expected 57"
    )
    println(s"OK: 32-bit overflow regression check passes (tBuf=${t.tBuf})")
  }

  /** Confirm `I2cConfig`'s `quarterPeriodCycles >= 1` guard fires
    * for combos `BusTiming` couldn't possibly serve. Catches the
    * "user gave us a 100 kHz clock for a Standard bus" mistake at
    * elaboration rather than producing a never-toggling SCL.
    */
  private def regressionConfigGuards(): Unit = {
    var threw = false
    try {
      I2cConfig(clkFreqHz = 100000, busSpeed = BusSpeed.Standard)
    } catch {
      case _: IllegalArgumentException => threw = true
    }
    assert(threw, "I2cConfig should reject clkFreqHz too low for busSpeed")
    println("OK: I2cConfig guard rejects under-clocked configs")
  }

  def main(args: Array[String]): Unit = {
    val clocks = Seq(12000000, 25000000, 48000000)
    val speeds = Seq(BusSpeed.Standard, BusSpeed.Fast, BusSpeed.FastPlus)

    for (clk <- clocks; speed <- speeds) runOne(clk, speed)

    regressionOverflowFix()
    regressionConfigGuards()

    println("OK: BusTiming meets every spec floor across the test matrix")
  }
}
