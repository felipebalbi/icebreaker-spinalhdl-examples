# I2c — TODO

Bottom-up bring-up plan for an I²C controller + target in SpinalHDL.
Same workflow as the Uart project: each block built in isolation,
sim'd, then composed into a wrapper, then a demo top, then real
silicon. Order isn't load-bearing — adjust as the design teaches us
something.

The Phase-1 top, `I2cController`, is the **APB3-fronted
register-mapped wrapper** around the Stream-shaped streaming cores
— a direct mirror of how `UartController` wraps `UartTx` / `UartRx`
in the sibling project. The register-file skeleton (REVISION at
0x00, then CTRL → STATUS → ISR/IER → CMD/TXDATA/RXDATA →
PRESCALE → *_FIFO_STATUS → CFG_INFO) is the convention every IP
in this repo follows.

Each completed step gets a "What landed" entry so the design rationale
survives independently of the source.

---

## ✅ Done
- [x] `I2cConfig` (clkFreqHz, busSpeed, addrMode, useClockStretching)
- [x] `BusSpeed` SpinalEnum (Standard / Fast / FastPlus)
- [x] `AddrMode` SpinalEnum (SevenBits / TenBits)
- [x] `I2cIo` bundle: `ReadableOpenDrain` SCL + SDA
- [x] `I2cIoSim` (sim-side wired-AND glue, reused by later sims)
- [x] `BusTiming` (spec-floor cycle table reconciled with `quarterPeriodCycles`)
- [x] `BusTimingSim` (pure-Scala spec-floor audit across BusSpeed × clkFreqHz)
- [x] `I2cBitController` (9-state FSM, registered SCL/SDA drives, optional clock stretching, spec-compliant arbitration loss)
- [x] `I2cBitControllerSim` (Start/Stop, write-byte, read-byte, RepStart, arbitration loss, clock stretching)
- [x] `BehaviouralI2cTarget` (sim-side slave model: address ACK, configurable per-byte `ackPattern`, ROM-style register file)
- [x] `I2cByteController` (byte-level FSM over `I2cBitController`; address + R/W̅, write/read bursts, RepStart/Stop, wedge regime on NAK / arb-loss, `InvalidSeq` for SW misuse)
- [x] `I2cByteControllerSim` (12 cases: smoke r/w, multi-byte r/w, RepStart, address NAK, data NAK, invalid-seq from-idle / wedged / direction-mismatch, arb-loss during-Start / mid-byte)
- [x] `BehaviouralI2cTarget` (sim-side slave model: address ACK, configurable per-byte ackPattern, ROM-style register file)
- [x] `I2cByteController` (byte-level FSM over `I2cBitController`; address + R/W̅, write/read bursts, RepStart/Stop, wedge regime on NAK / arb-loss, `InvalidSeq` for SW misuse)
- [x] `I2cByteControllerSim` (12 cases: smoke r/w, multi-byte r/w, RepStart, address NAK, data NAK, invalid-seq from-idle / wedged / direction-mismatch, arb-loss during-Start / mid-byte)

---

## ✅ Phase 0 — Foundations
### ✅ Step 1 — `I2cConfig`

**Goal:** a single, by-value compile-time record that every sub-block
keys off so widths and counter constants are derived once, at
elaboration, and the two halves of a controller/target loopback can't
be accidentally built for mismatched parameters.

**Files:** `src/hw/I2cConfig.scala`.

**What landed:**
- `I2cConfig(clkFreqHz, busSpeed, addrMode, useClockStretching)` —
  defaults to 12 MHz / Standard / 7-bit / no stretching, which matches
  the iCEbreaker + a typical hobby-grade target.
- Two SpinalEnums instead of the raw `busHz` / `addrBits` fields the
  TODO sketch suggested:
  - `BusSpeed` { `Standard` 100 kHz, `Fast` 400 kHz, `FastPlus` 1 MHz }
  - `AddrMode` { `SevenBits`, `TenBits` }

  This is a strict improvement: it constrains callers to spec speeds,
  matches the `ParityType` enum pattern from `UartConfig`, lets
  downstream code pattern-match on a closed set, and gets `FastPlus`
  support for free.
- `busFreqHz` derived from `busSpeed` — the single source of truth for
  the spec-table lookup, so adding a new speed grade is a one-line
  change.
- `quarterPeriodCycles = clkFreqHz / (busFreqHz * 4)` — the only
  timing constant exposed by the config itself. I²C bit-level events
  (SDA setup, SCL rise, sample, SCL fall) naturally fall on
  quarter-period boundaries, so this is the right unit for the
  forthcoming `BusTiming` helper to consume and fan out into the
  spec-named cycle counts (`tHIGH`, `tLOW`, `tHD;STA`, `tSU;STO`,
  `tBUF`, …).
- Two `require` guards:
  - `clkFreqHz > 0` — catches accidentally-zero or negative clocks.
  - `quarterPeriodCycles >= 1` with a `(clkFreqHz, busSpeed)`-aware
    message — catches an undersized system clock that would otherwise
    silently round to 0 and synthesise a design that never toggles
    SCL.

**Sim:** none. Pure compile-time `case class` — no RTL to drive. Same
convention as `UartConfig`; the first sim lands with the first real
Component (`BusTiming` or `I2cBitController`).

---


### ✅ Step 2 — `I2cIo`

**Goal:** model the wired-AND, open-drain SCL+SDA pair as a single
reusable `Bundle` so every block downstream — controller, target,
demos, sim glue — talks to the bus the same way and can't
accidentally drive a hard `1`.

**Files:** `src/hw/I2cIo.scala`, `src/sim/I2cIoSim.scala`.

**What landed:**
- `case class I2cIo() extends Bundle with IMasterSlave` carrying
  one `ReadableOpenDrain(Bool())` per line (`scl`, `sda`) plus a
  `releaseAll()` shorthand for reset paths.
- **Divergence from the original sketch.** The TODO sketch wrapped
  `TriState[Bool]` in an `OpenDrainIo` helper class with
  `driveLow` / `release` methods to police "never drive `1`". The
  implementation instead reaches for SpinalHDL's stock
  `ReadableOpenDrain[Bool]` primitive (in `spinal.lib.io`), which
  is a strict improvement:
  - **Open-drain semantics are baked in.** `ReadableOpenDrain`
    has only `(write, read)` — there is no `writeEnable` to
    forget, and you cannot accidentally drive a hard `1` because
    there is no second transistor to enable.
  - **Maps cleanly** to an actual open-drain pad at synthesis
    (iCE40 `SB_IO` open-drain mode).
  - **Two wires per line** instead of three.
  - **Helpers collapse:** `driveLow` / `release` / `sample`
    become plain `.write := False` / `.write := True` / `.read`,
    so most call sites need no helper at all.
  - **Polarity is electrical, not logical:** `write` drives the
    gate of the open-drain pull-down NMOS, so `False` turns the
    transistor on (pin → GND, bus low) and `True` turns it off
    (pin floats, external pull-up wins, bus high). Hence
    `releaseAll()` writes `True`.
- **`IMasterSlave`** so a controller declares `master(I2cIo())`
  and a target declares `slave(I2cIo())`; Spinal flips every
  nested `ReadableOpenDrain` direction so
  `controller.io.bus <> target.io.bus` connects in one line.
- **Sim helper: `I2cIoBus`.** A sim-only `Component` (lives in
  `src/sim/`, never synthesised) that wires two `I2cIo` slaves
  through `bus = a.write & b.write` for each line. This is the
  reusable wired-AND glue every later sim (`I2cBitControllerSim`,
  `I2cTargetFsmSim`, loopback) composes against — no external
  pull-up model required.
- **Bug fixed during review:** the first cut of `I2cIo.scala` was
  missing all imports (`spinal.core._`, `spinal.lib.IMasterSlave`,
  `spinal.lib.io.ReadableOpenDrain`) and would not compile. Also
  fixed a `Pul low` typo.

**Sim (`src/sim/I2cIoSim.scala`):**
- Compiles `I2cIoBus` and exercises every release/pull-low
  combination on both lines, asserting that both participants see
  the same bus state (the wired-AND fan-out has to reach
  everyone).
- Includes the "both pull both" case to pin down that contention
  is *not* an error — wired-AND just resolves to low.

**Makefile:** `sim-i2cio` target added; included in the `sim`
aggregate and `.PHONY`.

---

### ✅ Step 3 — `BusTiming`

**Goal (recap):** turn `I2cConfig` into a named table of cycle counts
the bit / byte controllers can read by symbol (`tHigh`, `tLow`, …)
instead of re-deriving timing from `clkFreqHz` at every site. Pure
elaboration-time math — no `Component`.

**Files:**
- `src/hw/BusTiming.scala` — final table.
- `src/sim/BusTimingSim.scala` — pure-Scala spec-floor audit.
- `Makefile` — `sim-bustiming` target wired into the `sim` aggregate
  and `.PHONY` line.

**What landed:**

- `case class BusTiming(cfg: I2cConfig)`, no Spinal imports needed
  — this file is pure Scala. Each public field is an `Int` cycle
  count usable directly as a counter constant in downstream FSMs.
- Per-speed-grade spec floors live in a private
  `case class SpeedMins(...)` and a single `match` on
  `cfg.busSpeed`. Adding `BusSpeed.HighSpeed` later is one new
  match arm, not a touch-every-call-site refactor.
- Round-up `ns → cycles` conversion in `toClockCycles`, with the
  intermediate product in `Long` (see "Divergence #1" below).
- Two-tier scheduling (see "Divergence #2"):
  - `tHighMin` / `tLowMin` carry the spec floor in cycles.
  - `tHigh` / `tLow` are what the bit-controller actually
    schedules: `max(2 × quarterPeriodCycles, tXxxMin)`.
- Framing (`tHdSta`, `tSuSta`, `tSuSto`, `tBuf`) and data-slot
  (`tSuDat`, `tHdDat`) values stay as straight round-ups from the
  spec minimum — they don't sit on the quarter-period grid.
- `tSuSta` (setup-for-repeated-START) added — see "Divergence #3".
- `I2cConfig.quarterPeriodCycles` is `clkFreqHz / (busFreqHz * 4)`
  (floor) — the natural quarter-period grid. A new
  `require(clkFreqHz >= busFreqHz * 4)` provides the
  "clock too slow for this bus" elaboration error.
- `BusTiming.tLow` includes a `shortfall` stretch
  (see "Divergence #4") that recovers an exact SCL frequency
  for non-clean clock/bus ratios by dumping
  `max(0, ceil(clkFreqHz / busFreqHz) - (tHigh + tLow0))`
  cycles into the low phase.
- Elaboration-time `assert`s pin the `tHigh ≥ tHighMin`,
  `tLow ≥ tLowMin`, and `tHigh + tLow ≥ 4 × quarterPeriodCycles`
  invariants for future readers.

**Divergences from the original hint:**

1. **Int → Long for the `ns × clkFreqHz` product.** The hint code
   used `Int * Int` arithmetic. That overflows silently for every
   realistic combo (e.g., 12 MHz × 4700 ns = 5.64 × 10¹⁰, vs
   `Int.MaxValue` ≈ 2.15 × 10⁹), publishing garbage cycle counts
   without any error. `toClockCycles` now widens both operands to
   `Long` before multiplying and `require`s the final value fits an
   `Int`. The new sim's `regressionOverflowFix` pins this.
2. **Two-tier `tHigh` / `tLow` instead of a single floor.** The
   original sketch published the spec floors directly as `tHigh` /
   `tLow`. Two problems with that:
   - It violated the `I2c/AGENTS.md` rule that `BusTiming` must
     consume `cfg.quarterPeriodCycles` rather than re-derive timing
     from `clkFreqHz` / `busFreqHz` — under the original sketch,
     `tHigh + tLow` and `4 × quarterPeriodCycles` were two
     independent rounding paths.
   - A symmetric 50/50 schedule at exactly `busFreqHz` is
     mathematically impossible in Fast mode at *any* clock
     (`tLow ≥ 1.3 µs` exceeds half of 1/400 kHz = 1.25 µs). So
     publishing `tHigh = tLow = 2 × qpc` and `require`-ing the
     spec floor would render Fast mode unreachable. Stretching the
     longer phase (here: `tLow`) when the spec demands it is the
     standard fix; the achieved SCL frequency drops slightly below
     `busFreqHz`, which the spec explicitly allows.
3. **Added `tSuSta` (repeated-START setup).** Distinct from
   `tHdSta` per the spec; the bit-controller (Step 4) needs it for
   repeated-START framing, and the spec table was already open in
   the file. Cheaper to add now than to revisit during Step 4.
4. **`BusTiming.tLow` carries a shortfall stretch.** First-pass
   review settled on `tLow = max(2 × qpc, tLowMin)` and accepted
   that achieved SCL would sit slightly above `busFreqHz` for
   non-clean clock/bus ratios (e.g., 25 MHz Fast+ landed at
   892.86 kHz, ~10.7 % below 1 MHz; later iterations also showed
   12 MHz Fast 6.25 % below). Round 3 of the review noticed the
   pattern was always "the floor period is one or two cycles
   short of `ceil(clkFreqHz / busFreqHz)`", which is recoverable
   by dumping the missing cycles into `tLow`. The new
   `shortfall = max(0, ceil(clkFreqHz/busFreqHz) - (tHigh + tLow0))`
   term restores an *exact* SCL frequency in 7 of the 9 cells of
   the `{Standard, Fast, Fast+} × {12, 25, 48} MHz` test matrix.
   The remaining two cells (Fast at 25 MHz / 48 MHz) deviate by
   −0.79 % / −2.44 % — `tLowMin` already exceeds half the bit
   period there, so the period is stretched past the ideal and
   the shortfall has nothing to add. That residue is well inside
   any I²C target's tolerance; eliminating it would require
   shrinking `tHigh` below `2 × qpc` and breaking the
   bit-controller's mid-`tHigh` sampling-grid assumption.
   The shortfall lands on `tLow` rather than `tHigh` because (a)
   it preserves the `tHigh = k × qpc` grid the bit-controller
   wants, and (b) extra `tLow` is the I²C clock-stretching idiom
   the protocol explicitly allows.

**Sim:**

`BusTimingSim` is a pure-Scala `object` with a `main` — no
`SimConfig.compile`, no DUT to wrap. It iterates every
`(BusSpeed × {12, 25, 48} MHz)` combo and:

- Asserts every published cycle count meets its spec floor in real
  time (`cycles × 1e9 / clkFreqHz ≥ specMinNs`). The spec table is
  duplicated independently in the sim file so the test isn't
  fooled by a buggy `BusTiming` "agreeing with itself".
- Asserts the floor invariants `tHigh ≥ tHighMin`,
  `tLow ≥ tLowMin`, and `tHigh + tLow ≥ 4 × qpc`.
- Asserts the achieved SCL frequency never exceeds `busFreqHz`
  (within 0.5 % rounding slack).
- Pins the 32-bit overflow regression: `BusTiming(12 MHz, Standard)`
  produces `tBuf == 57`; an unfixed Int overflow would publish a
  negative or wrapped value.
- Pins the `I2cConfig` under-clock guard: a 100 kHz / Standard
  config must throw at construction.

Prints a formatted table per combo for human eyeballing.

**Makefile:** `sim-bustiming` target added; included in the `sim`
aggregate and `.PHONY`.

---

## 🔲 Phase 1 — Controller (host)

### ✅ Step 4 — `I2cBitController`

**Goal:** own SCL toggling and one-bit-at-a-time bus operations.
Given a command word, drive the right (SCL, SDA) edges with the
right `BusTiming` cycle counts, then assert `done`. This is the
only block that touches `I2cIo` directly; everything above it
speaks bytes.

**Files:** `src/hw/I2cBitController.scala`,
`src/sim/I2cBitControllerSim.scala`.

**What landed:**

- `BitCmd` SpinalEnum: `{ Idle, Start, RepStart, Stop, WriteBit,
  ReadBit }` exactly as the hint suggested.
- `case class I2cBitController(cfg: I2cConfig) extends Component`
  with the suggested IO bundle (`cmd: Stream[BitCmd]`, `txBit`,
  `rxBit`, `arbLost`, `bus: I2cIo`).
- Internal `val timing = BusTiming(cfg)` so the FSM reads spec
  cycle counts by name (`tHdSta`, `tSuSta`, `tSuSto`, `tBuf`,
  `tSuDat`, `tHigh`, `tLow`).

**State machine — 9 states, not 7:**

The hint suggested seven states (`Idle`, `Setup`, `ScLLow`,
`ScLRise`, `ScLHigh`, `ScLFall`, `Hold` or similar — a single
shared bit pipeline). What landed is a different decomposition
that emerged from a Socratic walk-through:

```
idleState                                            (1)
startState                                           (1)  Start tail; also reused by RepStart
repStartReleaseSdaState, repStartReleaseSclState     (2)  RepStart prep
stopSdaLowState, stopSclRiseState, stopSdaRiseState  (3)
bitLowState, bitHighState                            (2)  shared by Read/Write
```

Why this layout:

- **Each state owns exactly one edge and one dwell.** The body
  pattern is `onEntry { drive an edge; load phaseCounter }` /
  `whenIsActive { decrement; transition at zero }`. Reading the
  states top-to-bottom gives the bus waveform directly.
- **Read and Write share `bitLowState`/`bitHighState`.** They
  differ only in two lines: `bitLowState`'s SDA assignment
  (`Mux(isRead, True, txBitLatched)`) and the mid-`tHigh`
  branch (sample vs. arb-check). Splitting them into 4 + 4
  states would have been pure code duplication.
- **`startState` is shared by both Start and RepStart.**
  RepStart adds two prep states (`releaseSda`, `releaseScl`)
  that drag the bus from "mid-transaction (SCL low, SDA = last
  bit)" back to "(SCL high, SDA high)", then falls into the
  shared `startState` tail.
- **No `Hold` state.** The spec says `tHdDat = 0 ns` for every
  speed grade, so there's nothing to hold. `bitHighState` exits
  straight to `idleState` after pulling SCL low.

**Divergences from the original hint:**

1. **State naming convention.** All state names end in `State`
   per the convention adopted during this step. This survives
   into all future FSMs in the I2c project.
2. **Registered SCL/SDA drivers.** The hint implied direct
   per-state combinational drives. What landed: `Reg(Bool())`
   regs `sclDrive` / `sdaDrive` driven from the FSM, with
   `io.bus.scl.write := sclDrive` / `io.bus.sda.write :=
   sdaDrive` at component scope. This lets each state touch
   only the lines it changes; consecutive states that need to
   hold the previous drive value (very common — e.g., SDA stays
   low through the entire Start sequence) just don't write.
   This is a project-wide pattern now (see AGENTS.md).
3. **Clock stretching is a Scala-time `if`.** Wrapped around
   the counter-decrement in `bitHighState` and
   `stopSclRiseState`. When `cfg.useClockStretching = false`
   the SCL sense path and the gating logic disappear from the
   synthesised design entirely (the Scala `if` evaluates at
   elaboration). When `true`, the counter pauses while
   `io.bus.scl.read` is low.
4. **Spec-compliant arbitration loss.** On detecting that we
   tried to release SDA but the bus reads low, the FSM
   immediately releases both SCL and SDA, sets `arbLostReg`,
   and returns to `idleState`. The flag is sticky until the
   next accepted command. Multi-master safe out of the box.
5. **`BitCmd.Idle`** is a no-op that stays in `idleState`. An
   explicit "do nothing but consume one cmd slot" is a useful
   sync barrier for the byte-level FSM.

**Sim:** `I2cBitControllerSim` covers six cases:

- **smoke Start→Stop:** Verifies framing edges and that the bus
  rests released after Stop. Counts SCL/SDA edges with a
  passive observer fork.
- **write byte 0xA5:** Issues 8 `WriteBit` commands MSB-first,
  samples SDA mid-`tHigh` per bit, counts SCL pulses.
- **read byte 0x69:** A sim-side "slave" pre-sets SDA before
  each `ReadBit`; checks that `rxBit` matches what the slave
  drove.
- **RepStart:** Start → WriteBit → RepStart → WriteBit → Stop;
  verifies the prep-then-fall sequence and that `arbLost` stays
  clear.
- **arbitration loss:** Issues `WriteBit(1)` then forces
  `bus.sda.read := False` mid-`tHigh`; asserts `arbLost` rises
  and both lines are released.
- **clock stretching:** With `useClockStretching = true`, holds
  `bus.scl.read` low for 50 cycles after the controller
  releases SCL during a `WriteBit`. Asserts the stretched bit
  takes at least 50 cycles longer than a non-stretched bit.

**Open follow-ups:**

- **SDA-assignment asymmetry between `idleState` and `bitLowState`
  is tech debt.** `WriteBit`/`ReadBit` set `sdaDrive` in
  `idleState`'s switch (live `io.txBit`) rather than in
  `bitLowState.onEntry` (which would see the lagged
  `txBitLatched` Reg). It works, but it's easy for a future
  contributor to "consolidate" the SDA assignment back into
  `bitLowState.onEntry` and silently reintroduce the bug. Both
  sites carry warning comments. Revisit once Step 5
  (`I2cByteController`) exercises back-to-back bits at speed —
  candidate fixes include a dedicated `bitSetupState` or a
  combinational cmd-payload shim that states can read without
  the Reg-update lag.
- A stretch-timeout register (mentioned in the original hint).
  Belongs on `I2cController` as a writable register, not in
  `I2cBitController`. Deferred to Step 6.
- The arbitration-loss recovery currently bails to `idleState`
  but doesn't propagate the flag back through the `cmd`
  handshake. The byte controller (Step 5) needs to observe
  `arbLost` directly and decide whether to abort the byte.

**Makefile:** `sim-bitctrl` target added; included in the `sim`
aggregate and `.PHONY`.

---

### ✅ Step 5 — `I2cByteController`

**Goal:** lift the bit-level FSM to byte-level transactions —
address + R/W̅, payload bytes, and the all-important ACK slot —
so callers above this layer never have to think about
`BitCmd.WriteBit` again.

**Files:** `src/hw/I2cByteController.scala`,
`src/sim/BehaviouralI2cTarget.scala`,
`src/sim/I2cByteControllerSim.scala`,
`src/sim/I2cIoSim.scala` (extended to 3 ports for arb-loss tests).

**What landed:**

- `ByteCmdKind` SpinalEnum: `{ AddrWrite, AddrRead, WriteData,
  ReadData, RepStart, Stop }` exactly as the hint suggested.
- `ByteCmd` / `ByteRsp` bundles. `cmd.ackOut` is the master's
  ACK bit during reads (0 = continue, 1 = NAK-final). `rsp.ackIn`
  is the target's ACK bit during writes (0 = ACK, 1 = NAK).
- `ByteRspStatus` SpinalEnum: `{ Ok, ArbLost, InvalidSeq }` —
  added beyond the hint to give the FSM a first-class way to
  report SW misuse without conflating it with bus-level NAKs.
  See "Divergences" below.
- One `cmd.fire` produces exactly one `rsp.fire` for every kind,
  including `RepStart` / `Stop` (the upper layer can rely on a
  1:1 handshake; `data` / `ackIn` are "don't care" on those).
- Bit FSM is driven through three-state spokes:
  `<op>IssueState → <op>WaitState → <op>RspState`. The wait
  state listens for `arbLost` and short-circuits straight to
  `*RspState` with `status = ArbLost` so we don't clock out
  bits we no longer own.
- **Wedge regime.** A failed write (address NAK, data NAK, or
  any `ArbLost`) sets `mustTerminate := True`. While wedged,
  only `Stop` and `RepStart` are accepted; everything else
  returns `InvalidSeq` without touching the bus. `mustTerminate`
  and `arbLostReg` clear on `Stop.onExit` (or on the next
  accepted `AddrWrite` / `AddrRead`).
- **Sticky `arbLostReg`.** Every `*RspState` reports
  `status := arbLostReg ? ArbLost | Ok`, including
  `stopRspState` — so the recovering Stop's rsp still carries
  `ArbLost` before the flag clears on `onExit`. The next
  `AddrWrite` sees `Ok`.

**Divergences from the original hint:**

1. **Three statuses, not a single `arbLost` Bool.** The hint's
   `rsp.arbLost` Bool can't distinguish "bus arb lost" from "SW
   tried to do something illegal". Splitting into a tri-valued
   `ByteRspStatus.{Ok, ArbLost, InvalidSeq}` lets sims (and the
   eventual SW driver) tell the two failure modes apart and
   funnels the impossible cases through a single
   `errorRspState` hub instead of polluting every spoke.
2. **Wedge regime is explicit.** The hint suggested "complete
   the current rsp with `arbLost = True`, release the bus, and
   stay quiescent." What landed is more conservative: don't
   release the bus on the FSM's own initiative, just refuse
   non-terminator commands. Forcing the SW driver to issue a
   matching `Stop` makes the post-failure waveform predictable
   and SMBus-compatible.
3. **`BehaviouralI2cTarget` is a real `Component`, not a Scala
   passive snoop.** It's a synthesizable-shape `Component` (sim
   only — never built into a bitstream) with its own FSM that
   decodes Start/Stop, sniffs the address byte, ACKs at
   `tCfg.targetAddress`, and serves a ROM-style register file
   (`Vec.tabulate(256) { ... }` driven from `tCfg.regFileInit`).
   `tCfg.ackPattern` lets a test NAK any subset of data bytes
   mid-burst. Reused by Phase 2's target-side bring-up
   (Steps 8–10).
4. **`I2cIoBus` extended to 3 ports.** Per AGENTS.md guidance
   ("extend rather than fork a new helper" when a third
   participant is needed): port `c` is the competitor master
   for the arb-loss tests. The existing `I2cIoSim` smoke test
   was updated to drive `c` released; `I2cBitControllerSim`
   doesn't use `I2cIoBus` so wasn't affected.

**Sim:** `I2cByteControllerSim` covers 12 cases, more than the
4 the hint listed. The wedge regime + the `InvalidSeq` status
surface their own failure modes that the original 4-case hint
predates:

- **smoke write byte / smoke read byte:** address + one data
  byte against `BehaviouralI2cTarget`.
- **multi-byte write / multi-byte read:** four-byte bursts;
  read uses `cmd.ackOut=true` only on the last byte to
  terminate.
- **repeated start, read after write:** `AddrWrite → WriteData
  → RepStart(addr|R) → ReadData → Stop`; load-bearing register
  preload via `tCfg.regFileInit`.
- **address NAK:** sweeps every 7-bit address that isn't the
  target's, asserts NAK + clean Stop recovery per iteration.
- **data NAK:** target ACKs the address + first data byte,
  NAKs the second; asserts `ackIn=true`, then proves the wedge
  by failing a follow-up `WriteData` with `InvalidSeq`.
- **invalid sequence from idle:** non-Addr* commands from idle
  return `InvalidSeq` without touching the bus.
- **invalid sequence wedged:** wedges via always-NAK target,
  probes that all four non-terminators return `InvalidSeq`,
  recovers via `Stop` and via `RepStart`-then-`Stop`.
- **invalid sequence direction mismatch:** `WriteData` on a
  read txn (and vice versa) is a soft reject; the transaction
  continues and a `goodData` follow-up succeeds.
- **arb-loss during Start:** competitor pulls SDA low before
  the address byte; DUT detects on the first 1-bit release and
  surfaces `ArbLost`. Wedge proof + Stop recovery + uncontested
  follow-up txn.
- **arb-loss mid-byte:** clean address phase, then a forked
  thread pulls competitor SDA low partway through
  `WriteData(0xFF)`. Same wedge + recovery shape.

**Open follow-ups:**

- The arb-loss tests' recovery proofs only assert the recovery
  `AddrWrite` returns `status = Ok` (controller un-wedged); they
  don't assert the target ACKs. The behavioural target's FSM
  can be knocked out of sync by the contention/Stop sequence.
  Tightening this would mean either (a) hardening the target's
  Start/Stop detector against held-low contention, or (b)
  clocking the target through enough idle cycles after recovery
  to let it self-resync. Deferred.
- `tCfg.ackPattern` indexes by the master's `byteIdx`, not by
  any per-transaction reset. Multi-burst tests that need a
  fresh ACK pattern per RepStart would have to compute their
  pattern with the wrap-around in mind.
- A stretch-timeout register (mentioned in the original Step 4
  follow-ups). Belongs on `I2cController`'s regif. Deferred to
  Step 6.
- 10-bit addressing is plumbed through `cfg.addrMode` but only
  `SevenBits` is exercised. Phase-3 stretch goal.

**Makefile:** `sim-bytectrl` target added; included in the `sim`
aggregate and `.PHONY`.

---

### 🔲 Step 6 — `I2cController` (APB3-fronted register-mapped wrapper)

**Goal:** the Phase-1 top. An APB3 slave that wraps
`I2cByteController` in TX / RX FIFOs, sticky errors, an aggregated
IRQ, runtime-tunable bit timing, and a regif-generated register
file that doubles as machine-readable documentation. **Direct
mirror of `UartController`** in the sibling Uart project — same
skeleton (REVISION → CTRL → STATUS → ISR/IER → CMD / TXDATA /
RXDATA → PRESCALE → *_FIFO_STATUS → CFG_INFO), same regif idioms,
same `make docs` flow.

**Files:**
- `src/hw/I2cController.scala` — the wrapper Component.
- `src/hw/Revision.scala` — **copy from `Uart/src/hw/Revision.scala`**,
  package changed to `i2c`. Per the AGENTS.md no-cross-project-deps
  rule, this is an intentional copy. Same `(major, minor, patch)`
  read out of `sys.props` with 0/1/0 defaults; same lockstep
  requirement with the Makefile.
- `src/hw/I2cControllerVerilog.scala` — `runMain` entrypoint for
  `make gen-controller`.
- `src/hw/I2cControllerDocs.scala` — `runMain` entrypoint for
  `make docs` (HTML / C header / JSON / RALF / SystemRDL).
- `src/sim/I2cControllerSim.scala` — APB-driven smoke test.

**`I2cConfig` additions (land with this step):**
- `txFifoDepth: Int = 16` — TXDATA FIFO depth.
- `rxFifoDepth: Int = 16` — RXDATA FIFO depth.
- (No `cmdFifoDepth`. CMD is a 1-deep shadow register, see below.)

**Address map (locked here; this is the contract Step 6 has to
deliver):**
```
0x00 REVISION         RO   [31:24]=major [23:16]=minor [15:0]=patch
0x04 CTRL             RW   [0]=enable
                           [1]=cmd_enable      drain CMD into byte-ctrl
                           [2]=rx_enable       push read bytes into RX FIFO
                           [3]=stretch_enable  honour clock-stretching path
                                               (only when cfg.useClockStretching
                                                = true; otherwise tied to 0)
0x08 STATUS           RO   [0]=bus_busy        live (controller mid-transaction)
                           [1]=cmd_busy        a CMD is queued / in flight;
                                               firmware must wait for this to
                                               fall before writing CMD again
                           [2]=arb_lost_live   live arbitration-loss line
                                               (sticky copy in ISR)
0x0C ISR              W1C  sticky event/error flags; write 1 to clear
                           [0]=addr_nack       target NACKed address phase
                           [1]=data_nack       target NACKed a data byte
                           [2]=arb_lost        we lost arbitration
                           [3]=stretch_timeout future-proof; tied to 0 in v0.1
                           [4]=cmd_done        CMD retired (byte-ctrl finished)
                           [5]=cmd_overrun     CMD was written while cmd_busy=1;
                                               the new write was dropped
                           [6]=rx_done         a byte was pushed into RX FIFO
0x10 IER              RW   per-bit enable / interrupt mask; matches ISR
0x14 CMD              WO   write posts ONE byte-command to the controller. CMD
                           is a 1-deep shadow register, NOT a FIFO — firmware
                           polls STATUS.cmd_busy (or waits for ISR.cmd_done)
                           between writes. Writes while cmd_busy=1 are
                           silently dropped and ISR.cmd_overrun is set.
                           [7:0]=byte          inline data byte (ignored if
                                               use_txdata=1 or kind ∈
                                               {ReadData, RepStart, Stop})
                           [10:8]=kind         0=AddrWrite 1=AddrRead 2=WriteData
                                               3=ReadData 4=RepStart 5=Stop
                           [11]=ack_out        master ACK polarity for ReadData
                                               (0=ACK and continue,
                                                1=NACK before STOP)
                           [12]=use_txdata     1 = pop a byte from the TXDATA
                                               FIFO on AddrWrite/WriteData;
                                               0 = use the inline byte field.
0x18 TXDATA           WO   write pushes one byte into the TX-data FIFO. Sized
                           by cfg.txFifoDepth so firmware can pre-load a burst
                           payload, then issue WriteData CMDs with
                           use_txdata=1.
0x1C RXDATA           RO   read returns the front of the RX FIFO and pops it
                           (returns 0 if RX_FIFO_STATUS.empty=1). Sized by
                           cfg.rxFifoDepth.
0x20 PRESCALE         RW   runtime override of BusTiming. Reset value =
                           cfg.quarterPeriodCycles. Writing remaps every
                           cycle count downstream proportionally so firmware
                           can re-tune SCL after the fact (the same role
                           BAUD plays for the UART). Width = 16 bits.
0x24 TX_FIFO_STATUS   RO   [0]=full [1]=empty [15:8]=count [23:16]=depth
0x28 RX_FIFO_STATUS   RO   same layout, for RXDATA bytes
0x2C CFG_INFO         RO   [1:0]=bus_speed (0=Std 1=Fast 2=Fast+)
                           [2]=addr_mode (0=7-bit 1=10-bit)
                           [3]=use_clock_stretching
                           [11:4]=reserved
                           [23:16]=clkFreqHz / 1_000_000 (synth clock in MHz)
```

**Suggested IO:**
```scala
val apb3Config = Apb3Config(addressWidth = 8, dataWidth = 32,
                            selWidth = 1, useSlaveError = false)

val io = new Bundle {
  val apb = slave(Apb3(apb3Config))
  val bus = master(I2cIo())              // open-drain SCL/SDA
  val irq = out Bool()                   // OR(ISR & IER) when CTRL.enable = 1
}
```

**Design notes:**

- **Why CMD is a single shadow register, not a FIFO.** A queue of
  pending byte-commands would be easy enough to add but it isn't
  load-bearing — the I²C wire is the rate limiter (~25 µs per
  byte at 100 kHz, ~2.5 µs at 1 MHz). The few hundred nanoseconds
  of firmware overhead between CMD writes is invisible at any
  spec speed. A 1-deep shadow keeps the address map small,
  removes a third FIFO_STATUS register, and makes back-pressure
  semantics obvious: `STATUS.cmd_busy` is the gate; writes while
  busy fail loudly via `ISR.cmd_overrun` rather than being
  silently re-queued.
- **Why split CMD and TXDATA.** One CMD = one byte-command, but a
  burst write naturally has N data bytes. Firmware preloads
  TXDATA (default 16-deep), then issues WriteData CMDs with
  `use_txdata=1`; each CMD pops the next TXDATA byte. The
  alternative (a single packed CMD) would force one bus-write
  per byte even when firmware already has the payload in hand.
  This is the OpenCores i2c_master_top split.
- **Inline byte in CMD** is the single-byte fast path. Address
  bytes (1 byte per transaction) and most one-shot register pokes
  fit. Set `use_txdata=0` and the byte field carries the value;
  no need to involve the TXDATA FIFO at all.
- **PRESCALE** is the user-tunable runtime knob the BusTiming
  review identified. `cfg.quarterPeriodCycles` becomes the *reset*
  value of PRESCALE; downstream timing is computed from PRESCALE,
  not from `cfg.quarterPeriodCycles` directly. This is what gives
  firmware the lever to recover the residual SCL frequency error
  on awkward clock/bus ratios (e.g., 25 MHz × Fast+ → 10.7 % off
  with the cfg-derived value alone).
- **Sticky errors / W1C** match the Uart pattern exactly. The
  byte-controller emits one-cycle `addrNack` / `dataNack` /
  `arbLost` / `stretchTimeout` pulses; `.set()` latches each into
  the matching ISR field; firmware clears by writing 1.
- **IRQ** = `OR(ISR & IER) & CTRL.enable`. Same shape as Uart.
- **Sub-block instantiation:** one `BusTiming(cfg)`, one
  `I2cByteController(cfg)`, two `StreamFifo`s (TX-data, RX-data),
  the regif `Apb3BusInterface`, the CMD shadow register and the
  `cmd_busy` FSM that drives `byteCtrl.io.cmd.valid`.
- **Demo entrypoints:** `I2cControllerVerilog` and
  `I2cControllerDocs` `object`s with `main(args)`. `Docs` runs
  `accept(DocHtml(...))` / `DocCHeader` / `DocJson` / `DocRalf` /
  `DocSystemRdl` — same lineup as `UartControllerDocs`.

**Sim hints (`src/sim/I2cControllerSim.scala`):**
- Mirror `UartControllerSim` closely:
  - First check after reset: read REVISION, decode `(major,
    minor, patch)`, assert vs. `Revision.{major, minor, patch}`.
    Locks the Makefile→sys.props→regif plumbing.
  - Read CFG_INFO; assert it reports the build params.
  - Read TX/RX_FIFO_STATUS depth fields; assert they match
    `cfg.{tx,rx}FifoDepth`.
- Compose the controller against a Scala-side
  `BehaviouralTargetMock` (built in Step 5) on a shared `I2cIoBus`.
- Cases:
  - **Single-byte register write** via inline CMD bytes: AddrWrite
    (use_txdata=0, byte=slave addr) → WriteData (use_txdata=0,
    byte=reg) → WriteData → Stop. Assert mock register file.
  - **Burst write via TXDATA**: preload TXDATA with N bytes;
    issue AddrWrite + N × WriteData(use_txdata=1) + Stop; assert
    mock received the bytes in order.
  - **Read with RepStart**: AddrWrite(reg) → RepStart →
    AddrRead(slave) → ReadData(ack=continue) → ReadData(ack=nack) →
    Stop. Pop RXDATA twice; assert bytes.
  - **NACK on address**: target answers a wrong address. Assert
    `ISR.addr_nack` sets and stays sticky until W1C clears it.
  - **CMD overrun**: deliberately write CMD twice back-to-back
    while `cmd_busy=1`. Assert the second write is dropped, the
    bus traffic only contains the first command, and
    `ISR.cmd_overrun` sets.
  - **PRESCALE retune**: write a different value, assert the
    on-bus SCL period changes accordingly.

**Makefile:**
- `sim-controller` target.
- `gen-controller` target (`runMain i2c.I2cControllerVerilog`).
- `docs` target (`runMain i2c.I2cControllerDocs`).
- All hooked into the `## help` line and `.PHONY`.
- `REVISION_DEFS` (already present from the planning pass) is
  forwarded automatically because `SBT := sbt $(REVISION_DEFS)`.

---

### 🔲 Step 7 — Controller bring-up demo

**Goal:** prove the full controller stack works on real silicon
by reading a known-quantity sensor and dumping its register data
over UART so we can eyeball it on a desktop terminal — same
"`Hello, World`" gating gesture the Uart project used.

**File:** `src/hw/I2cControllerDemo.scala` plus
`src/hw/I2cControllerDemoVerilog.scala` for the elaboration
entrypoint.

**Suggested IO (top-level pins):**
```
clk_12      -> input
rst_n       -> input (active-low button)
i2c_scl     -> inout  (PMOD1A pin 1)
i2c_sda     -> inout  (PMOD1A pin 2)
uart_tx     -> output (USB-UART TXD on the iCEbreaker)
```

**Design notes:**
- **APB master FSM in the demo.** The controller now exposes
  Apb3, not a Stream — so the demo owns a small APB master FSM
  that walks the register file (write CTRL.enable, write CMD,
  poll STATUS.cmd_busy, ...) the way `UartEchoDemo` does. Reuse
  that FSM shape; it's already proven.
- **Pick one target part.** Recommend MCP9808 (temperature
  sensor) over SSD1306: MCP9808 has a tiny driver footprint
  (one register read returns the temperature), SSD1306 needs an
  init blob and DC-DC startup delays. Document the choice in
  this block's header comment.
- **No external `Uart` project dependency.** Copy the minimal TX
  path (`BaudGenerator`, `TxShiftReg`, `TxFsm`, `UartTx`) into
  the `i2c` package as `LocalUartTx*` — or, cleaner, factor the
  TX out into a shared `common` package later. For Step 7,
  inline-copy with an "imported from Uart project, sync forward
  if Uart changes" comment header. Keeps the I2c project
  buildable with `sbt` in isolation.
- **Driver shape.** A small ROM of `(addr, data, kind)` triples
  feeds the APB master FSM; bytes read from `RXDATA` go to a
  byte-to-hex-ASCII converter and out the local UART. Reuse the
  Uart project's "drain a ROM through a Stream" pattern.
- **Reset:** debounced active-low button; after reset, kick off
  one MCP9808 read every ~500 ms (a counter reset, not a fancy
  RTC).

**Sim hints:**
- No new SpinalSim — this is hardware bring-up. Optional
  end-to-end sim that wires `BehaviouralTargetMock(0x18,
  registers = Map(0x05 -> 0x0123))` to the controller and
  scrapes the UART output for "T=0x0123" is nice-to-have but
  not required to ship.

**Makefile:** add `TOP := I2cControllerDemo` once this step
lands (the placeholder is already there); the bitstream then
flows through `make` / `make flash` like the Uart demo did.
No new sim target.

**Hardware bring-up checklist** (mirror the Uart Step 5b style):
- [ ] Bitstream builds clean.
- [ ] On scope/LA: SCL toggles at the configured `busFreqHz`.
- [ ] Address phase ACKs at `0x18`.
- [ ] Temperature register read returns plausible data.
- [ ] UART output decodes on `picocom`.

---

## 🔲 Phase 2 — Target (peripheral)

### 🔲 Step 8 — `I2cTargetMonitor`

**Goal:** a *passive* bus observer that detects START / repeated
START / STOP and per-bit edges from the wired-AND lines without
ever driving anything. Building block for the target FSM and
also a sim assertion engine for Phase 1.

**File:** `src/hw/I2cTargetMonitor.scala`

**Suggested IO:**
```scala
val io = new Bundle {
  val bus      = slave(I2cIo())          // read-only attachment
  val start    = out Bool()              // 1-cycle pulse
  val repStart = out Bool()              // 1-cycle pulse
  val stop     = out Bool()              // 1-cycle pulse
  val sclRise  = out Bool()              // 1-cycle pulse
  val sclFall  = out Bool()              // 1-cycle pulse
  val sdaSamp  = out Bool()              // SDA value on last sclRise
}
```

**Design notes:**
- **All inputs synchronised first.** Even though the controller
  drives both lines, the target side may live in a different
  electrical clock domain after debounce/glitch filtering. Run
  both `bus.scl.sample` and `bus.sda.sample` through a 2-FF
  synchroniser (steal `RxSync` shape from the Uart project)
  before edge detection.
- **Edge definitions:**
  - `start`    : SDA falls while SCL is high, from idle (after
    `tBuf` of bus-free observation, optional).
  - `repStart` : SDA falls while SCL is high, *not* from idle.
  - `stop`     : SDA rises while SCL is high.
  - `sclRise` / `sclFall` : registered edge of the synchronised
    SCL.
- **Pure observer.** No `writeEnable` ever asserted; this block
  must be safe to instantiate alongside any number of other bus
  drivers.
- **No spec-timing dependence.** `tBuf` etc. are nice for
  glitch filtering but optional; first cut should detect
  edges by combinational logic on registered samples and
  leave timing-aware filtering as a future hardening pass.

**Sim hints (`src/sim/I2cTargetMonitorSim.scala`):**
- Replay traces produced by Step 4's `I2cBitControllerSim`
  (i.e. drive the bus from a Scala harness) and assert
  `start` / `repStart` / `stop` / `sclRise` / `sclFall` fire
  at the expected cycles.
- Adversarial trace: SDA glitches during SCL low must NOT
  produce START (the synchroniser filter handles single-cycle
  glitches; multi-cycle glitches are a known limitation —
  document, don't fix).

**Makefile:** `sim-tgtmon` target.

---

### 🔲 Step 9 — `I2cTargetFsm`

**Goal:** drive SDA during the ACK slot and during read-data
slots; optionally pull SCL low to stretch when the upper layer
isn't ready with a byte to serve. First block on the target
side that actually drives the bus.

**File:** `src/hw/I2cTargetFsm.scala`

**Suggested IO:**
```scala
val io = new Bundle {
  val bus       = slave(I2cIo())
  // Upstream (the future I2cTarget wrapper) handshake:
  val addrMatch = in  Bool()           // upper layer answers per START
  val rxByte    = master Flow Bits(8 bits)  // bytes received from controller
  val txByte    = slave  Stream Bits(8 bits) // bytes we hand back on read
  // Optional: tell the FSM whether the controller is reading or writing
  val isRead    = in  Bool()
}
```

**Design notes:**
- **Composes `I2cTargetMonitor`.** This block does not duplicate
  edge detection; it instantiates the monitor and reacts to its
  pulses.
- **ACK slot rule.** During the 9th bit of every byte sent *to*
  us, drive SDA low (ACK) if `addrMatch` (or a per-byte
  flow-control bit) is set; release otherwise (NACK).
- **Read slots.** Shift `txByte.payload` out MSB-first onto SDA,
  one bit per `sclFall → sclRise` window. Pop `txByte` when the
  byte is fully clocked out and the controller responded with
  ACK; on NACK, return to address-wait.
- **Clock stretching (`cfg.useClockStretching`).** When the FSM
  needs more time — e.g. `txByte.valid = false` at the start of
  a new read byte — drive SCL low. Release once `txByte` is
  presented. Same `cfg`-gated synthesis-elision as Step 4.
- **Reset:** bus released, FSM idle, no in-flight `txByte`.

**Sim hints (`src/sim/I2cTargetFsmSim.scala`):**
- **Composition test:** instantiate Phase 1's `I2cController` and
  this `I2cTargetFsm` on a shared `I2cIoBus`. Drive
  controller commands from one fork and `txByte` from another;
  assert payload integrity and ACK polarity.
- Stretch test: hold `txByte.valid = false` for N cycles after
  `addrMatch`; assert SCL stays low for N cycles, then ticks
  resume cleanly.

**Makefile:** `sim-tgtfsm` target.

---

### 🔲 Step 10 — `I2cTarget`

**Goal:** the public Stream-shaped target wrapper. Presents
"address matched, here's the byte the controller sent" / "we
need a byte to send back" handshakes and hides the FSM
plumbing.

**File:** `src/hw/I2cTarget.scala`

**Suggested IO:**
```scala
val io = new Bundle {
  val bus     = slave(I2cIo())
  val rx      = master Stream Bits(8 bits)   // bytes the controller wrote to us
  val tx      = slave  Stream Bits(8 bits)   // bytes we'll return on read
  val matched = out   Bool()                 // pulses on every address match
}
```

**Design notes:**
- **Match against `cfg.slaveAddress`.** That field doesn't exist
  in `I2cConfig` yet — adding it is part of this step. Default
  to `0x50` (a typical EEPROM address) and document that.
- **Wraps `I2cTargetFsm`.** Wires `rx` / `tx` Streams to the FSM's
  `rxByte` / `txByte`; converts `addrMatch` into the
  `matched` pulse for upstream observability.
- **Backpressure:** if `rx` consumer is slow, the FSM's
  next-byte ACK is gated on `rx.ready`. With clock stretching
  off, this means an overrun NACK; with stretching on, the FSM
  pulls SCL low until `rx.ready`.

**Sim hints (`src/sim/I2cTargetSim.scala`):**
- Behavioural test using a tiny in-memory register file behind
  `rx` / `tx`. Drive Phase-1 controller through a sequence of
  writes and reads at multiple addresses (matched and
  unmatched); assert register file contents and read-back data.
- Address-mismatch case: NACK on address, no pulses on
  `matched`.

**Makefile:** `sim-target` target.

---

### 🔲 Step 11 — Loopback demo

**Goal:** end-to-end hardware bring-up of the target half. Run
the controller and target on the same iCEbreaker, talking to
each other on the same PMOD pair. Verifies the full stack
without depending on any external chip.

**File:** `src/hw/I2cLoopbackDemo.scala` plus
`src/hw/I2cLoopbackDemoVerilog.scala`.

**Suggested IO (top-level pins):**
```
clk_12      -> input
rst_n       -> input
i2c_scl     -> inout (single PMOD pin shared by both halves)
i2c_sda     -> inout (single PMOD pin shared by both halves)
uart_tx     -> output (status messages)
led[0..2]   -> output (target matched, last-write-byte LSB, error)
```

**Design notes:**
- **Single shared bus.** Both `I2cController.io.bus` and
  `I2cTarget.io.bus` attach to the same iCE40 `inout` pin.
  Because both speak `I2cIo`, the wired-AND happens correctly
  in silicon via the external pull-ups; no `I2cIoBus` Scala
  helper involved.
- **Controller side is APB-driven now.** Same APB master FSM
  shape as Step 7 — small ROM of (offset, data, kind) triples
  walks the controller's register file. The target side stays
  Stream-shaped per Step 10 (an APB-fronted target is a future
  exercise; keeping it Stream-fed is fine for the loopback gate).
- **Tiny memory behind the target.** 16-byte register file
  driven by `I2cTarget.rx` / `tx`. The controller writes a
  pattern, then reads it back; LEDs / UART report PASS / FAIL.
- **No new sim.** All sim coverage already lives in earlier
  steps; this is hardware verification only. Optional smoke sim
  is fine but should not block.

**Hardware bring-up checklist:**
- [ ] Bitstream builds.
- [ ] Pattern `0..15` round-trips through the register file.
- [ ] Logic-analyser capture shows correct ACK timing on every
      byte.
- [ ] `picocom` shows the PASS message after each round-trip.

**Makefile:** add a second `TOP_LOOPBACK := I2cLoopbackDemo`
+ `flash-loopback` target so the two demos can coexist; or
swap `TOP` between builds. Pick whichever style stays
closest to the Uart project's `UartTxDemo` / `UartEchoDemo`
pair.

## 🔲 Phase 3 — Stretch goals (no commitment)
- [ ] 10-bit addressing.
- [ ] Multi-master arbitration (SDA conflict detection during
  address phase).
- [ ] SMBus host-notify / PEC.
- [ ] High-speed (3.4 MHz) mode — needs a faster pixel-clock-style
  parameterisation pass.

---

## Design notes (sketch)

### Open-drain modelling

I²C lines are wired-AND: many devices, pull-up resistor, anyone can
pull low, no one drives high. The iCE40 has no true open-drain
output; we model it with `TriState`:

```scala
val sda = TriState(Bool())
sda.write := False        // when we want to drive low
sda.writeEnable := pullDown
val sdaIn = sda.read      // whatever the bus is doing right now
```

`pullDown=False` means high-Z (line floats up to 3.3 V via the
external resistor). `pullDown=True` means we drive 0. We never
drive 1 — that would short against another device pulling low and
either burn pins or just lose the wired-AND property.

Top-level pin connection uses `inout` in the generated Verilog, which
on iCE40 maps to an `SB_IO` configured as bidirectional with no
internal pull-up.

### Clock stretching

A target may pull SCL low to extend the low phase while it processes
a byte. The controller must:

1. Release SCL high (drive 0 → 1 transition) at the end of the low
   phase.
2. Wait for the SDA-input pin to read high again — only then count
   the high phase. If a target is stretching, this can be arbitrarily
   long (within reason; we should have a configurable timeout).

This is exactly the kind of thing that's painful to get right in
isolation but trivial to sim once both halves exist.

### Bit timing (Standard-mode, 100 kHz)

From the spec:

| Symbol  | Description           | Min @ 100 kHz |
|---------|-----------------------|---------------|
| tHIGH   | SCL high time         | 4.0 μs        |
| tLOW    | SCL low time          | 4.7 μs        |
| tHD;STA | Hold time, START      | 4.0 μs        |
| tSU;STO | Setup time, STOP      | 4.0 μs        |
| tBUF    | Bus free between txns | 4.7 μs        |
| tSU;DAT | Setup time, data      | 0.25 μs       |
| tHD;DAT | Hold time, data       | 0 μs          |

At a 12 MHz system clock, multiply by 12 to get cycle counts and
round up. `BusTiming` should do this at elaboration time so the
synthesised design has plain integer constants.

### Project conventions to keep

- `src/hw/` for synthesizable, `src/sim/` for testbenches. Verilog
  regeneration depends only on `src/hw/`.
- Each block gets a `*Sim` companion before the next layer is built.
- Per-block `sim-<name>` Makefile target plus aggregate `sim`.
- Public Makefile targets carry `## description` for `make help`.
- Detailed Scaladoc explains *why* not *what*.
- All committed files end with LF (run `dos2unix` on Windows hosts).
