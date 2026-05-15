package i2c

import spinal.core._

/** I²C bus speed grade.
  *
  * Exposed as a `SpinalEnum` rather than a raw `Hz` integer so that callers can
  * only ever request a speed the spec actually defines — and so that downstream
  * code (timing tables, `BusTiming`, datasheet lookups) can pattern-match on a
  * closed set instead of guessing whether `350_000` was a typo for `400_000`.
  * Mirrors the `ParityType` pattern used in `UartConfig`.
  *
  *   - `Standard` : 100 kHz — the original I²C rate; ubiquitous on legacy
  *     EEPROMs, RTCs and small sensors. The longest periods (10 µs/bit), so
  *     also the cheapest to reach with a coarse system clock.
  *   - `Fast` : 400 kHz — the modern default for OLEDs, IMUs, etc. Same
  *     protocol as Standard, just tighter timing.
  *   - `FastPlus` : 1 MHz — supported by recent parts (some sensors, PMICs).
  *     Requires reasonably stiff pull-ups; we expose the choice here but the
  *     board-level integrator still has to make the electrical numbers work.
  */
object BusSpeed extends SpinalEnum {
  val Standard, Fast, FastPlus = newElement()
}

/** I²C addressing mode.
  *
  * Same rationale as [[BusSpeed]] — an enum keeps illegal widths
  * unrepresentable. Currently only `SevenBits` is exercised by the
  * controller/target FSMs; `TenBits` is reserved for the Phase 3 stretch goal
  * in `TODO.md` (the address-phase FSM is the only piece that needs to know —
  * bit timing and ACK handling are width-agnostic).
  *
  *   - `SevenBits` : the classic I²C address layout — 7 address bits packed
  *     into the high bits of the first byte, R/W̅ in bit 0.
  *   - `TenBits` : 10-bit addressing escape sequence (first byte `11110_AA_x`,
  *     second byte the low 8 bits). Plumbed through the enum so future work
  *     doesn't have to break the public API.
  */
object AddrMode extends SpinalEnum {
  val SevenBits, TenBits = newElement()
}

/** Compile-time configuration for the I²C controller and target.
  *
  * Held as a `case class` so it can be passed by value into every sub-block
  * (`BusTiming`, `I2cBitController`, byte-level FSM, target FSM, …) and used to
  * derive widths and counter constants at elaboration time. Nothing in this
  * class survives into hardware — it only shapes how the hardware is built.
  * Both halves of a controller/target loopback share one config so the two
  * sides cannot be accidentally built for mismatched speed grades or addressing
  * modes.
  *
  * @param clkFreqHz
  *   System clock frequency in Hz. Used to derive bit-period cycle counts; the
  *   iCEbreaker boots at 12 MHz, which is the default here.
  * @param busSpeed
  *   Target SCL rate — see [[BusSpeed]]. The spec values (100 k / 400 k / 1 M)
  *   are the only ones reachable, deliberately, so that downstream timing-table
  *   code can rely on a closed set.
  * @param addrMode
  *   Slave-address width — see [[AddrMode]]. Only `SevenBits` is wired up
  *   today; `TenBits` is plumbed to keep the public API stable when the Phase 3
  *   stretch goal lands.
  * @param useClockStretching
  *   If `true`, the controller releases SCL high and waits for the SDA input to
  *   actually read high before counting the high phase, so a target may stretch
  *   the low phase arbitrarily (subject to a future timeout). If `false`, the
  *   controller assumes no target ever stretches and clocks SCL purely from its
  *   own counter — saving the SCL sense path, the wait state in the FSM, and a
  *   comparator/timeout register. Make this a build-time toggle rather than a
  *   runtime input so the logic actually disappears from synthesised designs
  *   that don't need it.
  * @param txFifoDepth
  *   Depth of the TX-data FIFO inside `I2cController`, in bytes. Sized so that
  *   firmware can pre-load a burst payload and then issue back-to-back
  *   `WriteData` CMDs with `use_txdata=1`. The depth must fit in the 8-bit
  *   `depth` slot of `TX_FIFO_STATUS` (so `<= 255`), and a 1-deep FIFO would
  *   degenerate to a single shadow register — pick `>= 2`.
  * @param rxFifoDepth
  *   Depth of the RX-data FIFO inside `I2cController`, in bytes. Same width
  *   constraint as `txFifoDepth` (must fit in the 8-bit `depth` field of
  *   `RX_FIFO_STATUS`).
  */
case class I2cConfig(
    clkFreqHz: Int = 12000000,
    busSpeed: BusSpeed.E = BusSpeed.Standard,
    addrMode: AddrMode.E = AddrMode.SevenBits,
    useClockStretching: Boolean = false,
    txFifoDepth: Int = 16,
    rxFifoDepth: Int = 16
) {

  /** Target SCL frequency in Hz, looked up from [[busSpeed]].
    *
    * Single source of truth for the spec table — every downstream timing
    * derivation (here and in `BusTiming`) keys off this value rather than
    * re-reading the enum, so adding a new speed grade is a one-line change to
    * the `match`.
    */
  val busFreqHz: Int = busSpeed match {
    case BusSpeed.Standard => 100000
    case BusSpeed.Fast     => 400000
    case BusSpeed.FastPlus => 1000000
  }

  /** Number of system-clock cycles in one quarter of an SCL bit period.
    *
    * I²C bit-level events naturally fall on quarter-period boundaries — SDA
    * changes mid-low-phase, the SCL rising edge starts the high phase, data is
    * sampled mid-high-phase, the SCL falling edge starts the next low phase.
    * Counting in quarter periods gives the bit-controller a cheap way to
    * schedule all four edges with a single counter.
    *
    * Rounded *down* (floor): the natural quarter-period grid is the floor that
    * the bit-controller's quarter-period counter actually lands on. Stretching
    * the period to recover a clean SCL frequency for non-integer clock/bus
    * ratios (e.g., 25 MHz at Fast+: floor gives `qpc = 6 → 4×qpc = 24` cycles
    * vs. the `25` cycles needed for 1 MHz exactly) is the responsibility of
    * `BusTiming`, which dumps the shortfall into `tLow` so `tHigh` stays a
    * clean multiple of `quarterPeriodCycles`. See `BusTiming.scala` for the
    * full story.
    *
    * This is intentionally the only timing constant exposed by the config
    * itself; the `BusTiming` helper consumes it and fans it out into the
    * spec-named cycle counts (`tHIGH`, `tLOW`, `tHD;STA`, `tSU;STO`, `tBUF`, …)
    * so the FSMs can read those by name.
    */
  val quarterPeriodCycles = clkFreqHz / (busFreqHz * 4)

  // Catches accidentally-zero or negative clocks at elaboration. The math
  // below would silently produce 0 and a nonsensical design otherwise.
  require(clkFreqHz > 0)

  // The clock must allow at least one cycle per quarter-period slot,
  // otherwise the bit-controller has no room to schedule four
  // distinct edges per bit. With floor rounding this is exactly the
  // same condition as `quarterPeriodCycles >= 1`, but writing it out
  // in terms of frequencies gives a more useful error message when
  // it fires.
  require(
    clkFreqHz >= busFreqHz * 4,
    s"clkFreqHz=$clkFreqHz too low for $busSpeed (need >= ${busFreqHz * 4} Hz)"
  )

  // Defence-in-depth: redundant with the require above for floor
  // rounding, but documents the invariant the rest of the codebase
  // relies on.
  require(quarterPeriodCycles >= 1)

  // FIFO depths are exposed verbatim in the 8-bit `depth` slot of
  // {TX,RX}_FIFO_STATUS, so they have to fit. A 1-deep FIFO would
  // collapse to a single register (and `count` would be a 1-bit
  // signal that confuses the empty/full encoding) — require >= 2.
  require(
    txFifoDepth >= 2 && txFifoDepth <= 255,
    s"txFifoDepth=$txFifoDepth must be in [2, 255]"
  )
  require(
    rxFifoDepth >= 2 && rxFifoDepth <= 255,
    s"rxFifoDepth=$rxFifoDepth must be in [2, 255]"
  )
}
