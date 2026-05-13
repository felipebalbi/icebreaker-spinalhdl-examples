package i2c

/** Compile-time table of I²C bus-timing constants, expressed in system-clock
  * cycles.
  *
  * `BusTiming` exists so the bit- and byte-level FSMs can read named symbols
  * (`tHigh`, `tLow`, `tHdSta`, …) instead of re-deriving cycle counts from
  * `clkFreqHz` and the spec table at every site. Every value is a `val`,
  * computed at elaboration from the supplied `cfg`, so the synthesised design
  * carries plain integer constants — there are no runtime knobs and no hardware
  * here.
  *
  * ==Units & rounding==
  * All values are non-negative integer system-clock cycle counts. Spec minimums
  * are translated `ns → cycles` with round-*up* so the driven bus never
  * undershoots a published spec floor.
  *
  * ==Relationship with `I2cConfig.quarterPeriodCycles`==
  * Per the project convention in `I2c/AGENTS.md`, `BusTiming` consumes
  * `cfg.quarterPeriodCycles` rather than re-deriving the bit period from
  * `clkFreqHz` / `busFreqHz`. To keep "what the FSM schedules" separate from
  * "what the spec demands":
  *
  *   - `tHighMin` / `tLowMin` are the spec floor in cycles, rounded up from the
  *     I²C-Bus Specification table.
  *   - `tHigh` is the *scheduled* SCL high-phase width, computed as `max(2 ×
  *     quarterPeriodCycles, tHighMin)`. It stays a clean multiple of
  *     `quarterPeriodCycles` whenever the spec floor allows, so the
  *     bit-controller can sample mid-`tHigh` from a simple quarter-period
  *     counter.
  *   - `tLow` is the scheduled SCL low-phase width. It starts at `max(2 ×
  *     quarterPeriodCycles, tLowMin)` and is then stretched by a `shortfall`
  *     term so that `tHigh + tLow ≥ ceil(clkFreqHz / busFreqHz)` cycles. This
  *     recovers an exact SCL frequency for clock/bus ratios that don't divide
  *     cleanly — e.g., 25 MHz at Fast+ floors to `qpc = 6 → 4×qpc = 24` cycles,
  *     but the ideal bit period is `25` cycles, so `tLow` is bumped by `1` to
  *     land on 1 MHz exactly. Putting the shortfall into `tLow` rather than
  *     `tHigh` keeps the high-phase sampling grid simple and matches what most
  *     I²C controllers do (longer `tLow` is the I²C clock-stretching idiom that
  *     the protocol explicitly allows).
  *
  * Two cases where this still leaves a residual deviation from the headline
  * grade rate:
  *
  *   - **Fast mode below 12 MHz integer multiples** (e.g., 25 MHz, 48 MHz).
  *     `tLowMin = 1.3 µs` is already wider than half the 400 kHz bit period, so
  *     `tLow` is determined by the spec floor and the shortfall has no room to
  *     pull the period up to the ideal. Achieved SCL sits a few percent below
  *     400 kHz; this is well inside any I²C target's tolerance and is the same
  *     behaviour every general-purpose I²C controller exhibits at these clocks.
  *   - **Strict 50/50 SCL is mathematically unreachable in Fast mode** at any
  *     clock for the same reason; the period is skewed towards a longer `tLow`.
  *     The protocol allows it.
  *
  * `tHigh + tLow ≥ 4 × quarterPeriodCycles` is therefore a one-way invariant —
  * equality holds only when both spec floors fit inside the natural
  * quarter-period grid *and* the clock divides cleanly into the bus rate.
  *
  * Framing parameters (`tHdSta`, `tSuSta`, `tSuSto`, `tBuf`) and the data-slot
  * parameters (`tSuDat`, `tHdDat`) do not live on the quarter-period grid; they
  * are counted out directly by the FSM and are published here as straight
  * round-ups from the spec minimum.
  *
  * ==Spec reference==
  * I²C-Bus Specification UM10204, table "Characteristics of the SDA and SCL bus
  * lines for Standard, Fast, and Fast-mode Plus I2C-bus devices". Minimums in
  * ns:
  *
  * {{{
  * symbol     Standard (100 kHz)   Fast (400 kHz)   Fast+ (1 MHz)
  * tHIGH      4000                 600              260
  * tLOW       4700                 1300             500
  * tHD;STA    4000                 600              260
  * tSU;STA    4700                 600              260
  * tSU;STO    4000                 600              260
  * tBUF       4700                 1300             500
  * tSU;DAT     250                 100               50
  * tHD;DAT       0                   0                0
  * }}}
  *
  * `tHD;DAT` is bounded from above (3.45 / 0.9 / 0.45 µs) — a min of zero is
  * legal and that's what we publish. The bit-controller still has to honour the
  * upper bound by not holding SDA past the next SCL rise; that's an FSM
  * concern, not a `BusTiming` concern.
  */
case class BusTiming(cfg: I2cConfig) {

  /** Spec floors for one bus-speed grade, all in ns. Kept as a `case class` so
    * adding a new speed grade is a single new entry in the `match` below, not a
    * touch-every-call-site refactor.
    */
  private case class SpeedMins(
      tHighNs: Int,
      tLowNs: Int,
      tHdStaNs: Int,
      tSuStaNs: Int,
      tSuStoNs: Int,
      tBufNs: Int,
      tSuDatNs: Int,
      tHdDatNs: Int
  )

  private val mins: SpeedMins = cfg.busSpeed match {
    case BusSpeed.Standard =>
      SpeedMins(4000, 4700, 4000, 4700, 4000, 4700, 250, 0)
    case BusSpeed.Fast =>
      SpeedMins(600, 1300, 600, 600, 600, 1300, 100, 0)
    case BusSpeed.FastPlus =>
      SpeedMins(260, 500, 260, 260, 260, 500, 50, 0)
  }

  // -------- SCL phase widths (scheduled by the bit-controller) -----

  /** Spec floor for SCL-high time, rounded up to system-clock cycles. */
  val tHighMin: Int = toClockCycles(mins.tHighNs)

  /** Spec floor for SCL-low time, rounded up to system-clock cycles. */
  val tLowMin: Int = toClockCycles(mins.tLowNs)

  /** Scheduled SCL high-phase width, in system-clock cycles.
    *
    * `max(2 × quarterPeriodCycles, tHighMin)`. Uses the natural quarter-period
    * schedule when it already meets spec; otherwise stretches to the floor. See
    * the class-level docs for why a strict 50/50 schedule would be unreachable
    * in Fast mode.
    */
  val tHigh: Int = math.max(2 * cfg.quarterPeriodCycles, tHighMin)

  /** Provisional SCL low-phase width before the shortfall stretch. */
  private val tLow0: Int = math.max(2 * cfg.quarterPeriodCycles, tLowMin)

  /** Cycles by which `tHigh + tLow0` falls short of one ideal bit period
    * (`ceil(clkFreqHz / busFreqHz)`).
    *
    * For integer-clean clock/bus ratios this is `0`. For ratios that don't
    * divide cleanly — and where the spec floors haven't already stretched the
    * period past the ideal — this picks up the missing cycles so the achieved
    * SCL frequency lands on `busFreqHz` exactly. Saturated at zero: when the
    * spec floors *do* stretch the period (Fast mode is the canonical case), we
    * honour the floor rather than try to claw the period back below it.
    */
  private val shortfall: Int = math.max(
    0,
    divRoundUp(
      cfg.clkFreqHz.toLong,
      cfg.busFreqHz.toLong
    ).toInt - (tHigh + tLow0)
  )

  /** Scheduled SCL low-phase width, in system-clock cycles.
    *
    * Starts at `max(2 × quarterPeriodCycles, tLowMin)` and absorbs the
    * [[shortfall]] so the achieved SCL frequency lands on `busFreqHz` exactly
    * when the spec floors leave room.
    */
  val tLow: Int = tLow0 + shortfall

  // -------- START / STOP framing (FSM counts these out directly) ---

  /** Hold time after a START condition (SCL falls before SDA changes again),
    * rounded up from the spec minimum.
    */
  val tHdSta: Int = toClockCycles(mins.tHdStaNs)

  /** Setup time before a *repeated* START condition. Distinct from [[tHdSta]];
    * the spec gives the two different floors.
    */
  val tSuSta: Int = toClockCycles(mins.tSuStaNs)

  /** Setup time before a STOP condition (SDA stays low while SCL rises, then
    * SDA released).
    */
  val tSuSto: Int = toClockCycles(mins.tSuStoNs)

  /** Bus-free time between a STOP and the next START. */
  val tBuf: Int = toClockCycles(mins.tBufNs)

  // -------- Data slot ----------------------------------------------

  /** SDA setup time before the SCL rising edge. Round-up of the spec minimum;
    * the bit-controller adds this on top of its mid-low-phase SDA-change point
    * if the natural quarter-period alignment doesn't already cover it.
    */
  val tSuDat: Int = toClockCycles(mins.tSuDatNs)

  /** SDA hold time after the SCL falling edge. Spec minimum is zero for every
    * speed grade — the bit-controller treats this as "no extra hold cycles"
    * without any off-by-one.
    */
  val tHdDat: Int = toClockCycles(mins.tHdDatNs)

  // -------- Elaboration-time audits --------------------------------

  // After the max() above, tHigh / tLow can't undershoot the spec
  // floor — but if a caller picks a clock so slow that the natural
  // quarter-period grid would be 0 cycles, I2cConfig has already
  // failed elaboration before we get here. These asserts document
  // the invariants for readers and would catch any future regression
  // that decoupled tHigh / tLow from the max(...) above.
  assert(tHigh >= tHighMin)
  assert(tLow >= tLowMin)
  assert(tHigh + tLow >= 4 * cfg.quarterPeriodCycles)

  // -------- Helpers ------------------------------------------------

  private def divRoundUp(num: Long, den: Long): Long =
    (num + den - 1) / den

  /** Round `timeNs` up to whole system-clock cycles.
    *
    * The intermediate product is `Long` because `clkFreqHz × timeNs` routinely
    * exceeds `Int.MaxValue` for realistic inputs — at 12 MHz and 4700 ns, the
    * product is 5.64 × 10¹⁰, vs `Int.MaxValue` ≈ 2.15 × 10⁹. A naive `Int *
    * Int` here wraps silently and produces garbage cycle counts.
    */
  private def toClockCycles(timeNs: Int): Int = {
    val product = cfg.clkFreqHz.toLong * timeNs.toLong
    val cycles = divRoundUp(product, 1000000000L)
    require(
      cycles <= Int.MaxValue,
      s"BusTiming: cycle count $cycles for ${timeNs}ns @ ${cfg.clkFreqHz} Hz overflows Int"
    )
    cycles.toInt
  }
}
