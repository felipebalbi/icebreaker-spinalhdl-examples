package i2c

/** Compile-time table of I²C bus-timing constants, expressed in
  * system-clock cycles.
  *
  * `BusTiming` exists so the bit- and byte-level FSMs can read named
  * symbols (`tHigh`, `tLow`, `tHdSta`, …) instead of re-deriving cycle
  * counts from `clkFreqHz` and the spec table at every site. Every value
  * is a `val`, computed at elaboration from the supplied `cfg`, so the
  * synthesised design carries plain integer constants — there are no
  * runtime knobs and no hardware here.
  *
  * ==Units & rounding==
  * All values are non-negative integer system-clock cycle counts. Spec
  * minimums are translated `ns → cycles` with round-*up* so the driven
  * bus never undershoots a published spec floor.
  *
  * ==Relationship with `I2cConfig.quarterPeriodCycles`==
  * Per the project convention in `I2c/AGENTS.md`, `BusTiming` consumes
  * `cfg.quarterPeriodCycles` rather than re-deriving the bit period
  * from `clkFreqHz` / `busFreqHz`. To keep "what the FSM schedules"
  * separate from "what the spec demands":
  *
  *   - `tHighMin` / `tLowMin` are the spec floor in cycles, rounded up
  *     from the I²C-Bus Specification table.
  *   - `tHigh` / `tLow` are the *scheduled* SCL phase widths, computed
  *     as `max(2 × quarterPeriodCycles, tXxxMin)`. The natural
  *     quarter-period schedule is used whenever it already clears the
  *     spec; otherwise the violating phase is stretched to the floor.
  *     This matters in practice because Fast-mode demands
  *     `tLow ≥ 1.3 µs` while half the bit period at 400 kHz is only
  *     1.25 µs — a strict 50/50 schedule would make the spec
  *     unreachable at *any* system clock. Stretching `tLow` (and
  *     therefore the bit period) is the standard fix; the resulting
  *     SCL frequency will be slightly below `busFreqHz` when this
  *     happens, which the spec also explicitly allows.
  *
  * `tHigh + tLow ≥ 4 × quarterPeriodCycles` is therefore a one-way
  * invariant — equality holds only when both spec floors fit inside
  * the natural quarter-period grid.
  *
  * Framing parameters (`tHdSta`, `tSuSta`, `tSuSto`, `tBuf`) and the
  * data-slot parameters (`tSuDat`, `tHdDat`) do not live on the
  * quarter-period grid; they are counted out directly by the FSM and
  * are published here as straight round-ups from the spec minimum.
  *
  * ==Spec reference==
  * I²C-Bus Specification UM10204, table "Characteristics of the SDA
  * and SCL bus lines for Standard, Fast, and Fast-mode Plus I2C-bus
  * devices". Minimums in ns:
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
  * `tHD;DAT` is bounded from above (3.45 / 0.9 / 0.45 µs) — a min of
  * zero is legal and that's what we publish. The bit-controller still
  * has to honour the upper bound by not holding SDA past the next SCL
  * rise; that's an FSM concern, not a `BusTiming` concern.
  */
case class BusTiming(cfg: I2cConfig) {

  /** Spec floors for one bus-speed grade, all in ns. Kept as a
    * `case class` so adding a new speed grade is a single new entry
    * in the `match` below, not a touch-every-call-site refactor.
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
    * `max(2 × quarterPeriodCycles, tHighMin)`. Uses the natural
    * quarter-period schedule when it already meets spec; otherwise
    * stretches to the floor. See the class-level docs for why a
    * strict 50/50 schedule would be unreachable in Fast mode.
    */
  val tHigh: Int = math.max(2 * cfg.quarterPeriodCycles, tHighMin)

  /** Scheduled SCL low-phase width, in system-clock cycles. See [[tHigh]]. */
  val tLow: Int = math.max(2 * cfg.quarterPeriodCycles, tLowMin)

  // -------- START / STOP framing (FSM counts these out directly) ---

  /** Hold time after a START condition (SCL falls before SDA changes
    * again), rounded up from the spec minimum.
    */
  val tHdSta: Int = toClockCycles(mins.tHdStaNs)

  /** Setup time before a *repeated* START condition. Distinct from
    * [[tHdSta]]; the spec gives the two different floors.
    */
  val tSuSta: Int = toClockCycles(mins.tSuStaNs)

  /** Setup time before a STOP condition (SDA stays low while SCL
    * rises, then SDA released).
    */
  val tSuSto: Int = toClockCycles(mins.tSuStoNs)

  /** Bus-free time between a STOP and the next START. */
  val tBuf: Int = toClockCycles(mins.tBufNs)

  // -------- Data slot ----------------------------------------------

  /** SDA setup time before the SCL rising edge. Round-up of the spec
    * minimum; the bit-controller adds this on top of its mid-low-phase
    * SDA-change point if the natural quarter-period alignment doesn't
    * already cover it.
    */
  val tSuDat: Int = toClockCycles(mins.tSuDatNs)

  /** SDA hold time after the SCL falling edge. Spec minimum is zero
    * for every speed grade — the bit-controller treats this as "no
    * extra hold cycles" without any off-by-one.
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
    * The intermediate product is `Long` because `clkFreqHz × timeNs`
    * routinely exceeds `Int.MaxValue` for realistic inputs — at 12 MHz
    * and 4700 ns, the product is 5.64 × 10¹⁰, vs `Int.MaxValue` ≈
    * 2.15 × 10⁹. A naive `Int * Int` here wraps silently and produces
    * garbage cycle counts.
    */
  private def toClockCycles(timeNs: Int): Int = {
    val product = cfg.clkFreqHz.toLong * timeNs.toLong
    val cycles  = divRoundUp(product, 1000000000L)
    require(
      cycles <= Int.MaxValue,
      s"BusTiming: cycle count $cycles for ${timeNs}ns @ ${cfg.clkFreqHz} Hz overflows Int"
    )
    cycles.toInt
  }
}
