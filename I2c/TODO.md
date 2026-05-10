# I2c — TODO

Bottom-up bring-up plan for an I²C controller + target in SpinalHDL.
Same workflow as the Uart project: each block built in isolation,
sim'd, then composed into a wrapper, then a demo top, then real
silicon. Order isn't load-bearing — adjust as the design teaches us
something.

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

### 🔲 Step 4 — `I2cBitController`

**Goal:** own SCL toggling and one-bit-at-a-time bus operations.
Given a command word, drive the right (SCL, SDA) edges with the
right `BusTiming` cycle counts, then assert `done`. This is the
only block that touches `I2cIo` directly; everything above it
speaks bytes.

**File:** `src/hw/I2cBitController.scala`

**Suggested IO:**
```scala
object BitCmd extends SpinalEnum {
  val Idle, Start, RepStart, Stop, WriteBit, ReadBit = newElement()
}

val io = new Bundle {
  val cmd     = slave Stream BitCmd()        // one command per transaction
  val txBit   = in  Bool()                   // value to drive on WriteBit
  val rxBit   = out Bool()                   // last sampled SDA on ReadBit
  val arbLost = out Bool()                   // we wrote 1 but bus stayed 0
  val bus     = master(I2cIo())              // open-drain SCL/SDA
}
```

**Design notes:**
- **State machine, not a counter soup.** Use `spinal.lib.fsm` with
  states `{ Idle, Setup, ScLLow, ScLRise, ScLHigh, ScLFall,
  Hold }` (or similar). Each transition is gated by a single
  shared `phaseCounter` that loads from `BusTiming` on entry.
- **Quarter-period scheduling.** The natural phase structure is
  four quarter-bit slots: drop SCL → set SDA → raise SCL →
  sample/drive bit. Reuse `cfg.quarterPeriodCycles` as the
  baseline; per-edge stretching (`tHdSta`, `tSuSto`, `tBuf`)
  loads a different value.
- **Clock stretching path** (only when `cfg.useClockStretching`):
  after we release SCL high, do not start counting `tHigh` until
  `bus.scl.sample === True`. Wrap this in a cfg-gated branch so
  the wait state synthesises away when stretching is disabled.
  Leave a `TODO` for a future stretch-timeout register; do **not**
  build it in this step.
- **Arbitration detection.** During `WriteBit` of a `1`, sample
  SDA in the high phase. If `bus.sda.sample === False`, someone
  else is pulling — set `arbLost`, abort the transaction, return
  to `Idle` with the bus released.
- **`Start` from `Idle`:** SDA falls while SCL is high. From a
  released bus, this is `release SDA → release SCL → drive SDA
  low → drive SCL low after tHdSta`. `RepStart` does the same
  starting from "SCL just went low after a previous bit".
- **`Stop`:** SCL low → SDA low → SCL high → SDA high after
  tSuSto. After Stop, enforce `tBuf` of bus-free time before we
  accept the next `cmd`.
- **Reset:** both lines released, FSM in `Idle`. `cmd.ready` low
  until reset is over so a fast producer doesn't slam in.

**Sim hints (`src/sim/I2cBitControllerSim.scala`):**
- Use the `I2cIoBus` Component from `src/sim/` (Step 2) to glue the
  controller's
  `I2cIo` to a Scala-side passive observer that records edges.
- For each command, assert the recorded edge sequence matches a
  golden trace (cycle counts equal to `BusTiming` values, ±0).
- Arbitration test: schedule a `WriteBit(1)` and have the sim
  helper drive SDA low during the high phase; assert `arbLost`
  rises and the FSM returns to `Idle` within one bit.
- Stretch test (only when `useClockStretching = true`): hold SCL
  low for N cycles after the controller releases it; assert the
  high phase is `N + tHigh` cycles long, not `tHigh`.
- Smoke test: a sequence `Start → WriteBit×8 → ReadBit → Stop`
  produces no protocol violations on the recorded trace.

**Makefile:** `sim-bitctrl` target; add to `sim` aggregate.

---

### 🔲 Step 5 — `I2cByteController`

**Goal:** lift the bit-level FSM to byte-level transactions —
address + R/W̅, payload bytes, and the all-important ACK slot —
so callers above this layer never have to think about
`BitCmd.WriteBit` again.

**File:** `src/hw/I2cByteController.scala`

**Suggested IO:**
```scala
object ByteCmdKind extends SpinalEnum {
  val AddrWrite, AddrRead, WriteData, ReadData, RepStart, Stop = newElement()
}

case class ByteCmd() extends Bundle {
  val kind   = ByteCmdKind()
  val data   = Bits(8 bits)   // address (with R/W̅) or write payload
  val ackOut = Bool()         // for ReadData: ack=0 to continue, 1 to NACK
}

case class ByteRsp() extends Bundle {
  val data    = Bits(8 bits) // bytes read back
  val ackIn   = Bool()        // ACK reported by the target (0 = ack)
  val arbLost = Bool()
}

val io = new Bundle {
  val cmd = slave  Stream ByteCmd()
  val rsp = master Stream ByteRsp()
  val bus = master(I2cIo())
}
```

**Design notes:**
- **One `cmd.fire` = one bit-level transaction.** The byte FSM
  drives the bit FSM eight times per data byte plus once for the
  ACK slot. The ACK slot is a `ReadBit` from the target's POV
  during writes (we listen) and a `WriteBit` of the master ACK
  bit during reads.
- **Address byte** = `{ addr[6:0], rw }`. For `AddrWrite`, R/W̅
  = 0; for `AddrRead`, R/W̅ = 1. The byte controller does not
  bake in the addressing mode — `cfg.addrMode` decides whether
  it's one address byte or the 10-bit escape sequence (latter
  is plumbed through but only `SevenBits` is exercised in
  Step 5).
- **Stream contracts.** Each `cmd.fire` produces exactly one
  `rsp.fire` for `AddrWrite` / `AddrRead` / `WriteData` /
  `ReadData`. `RepStart` and `Stop` fire `rsp` with `ackIn` /
  `data` set to "don't care" so the upper layer can rely on a
  1:1 handshake.
- **Error propagation.** If the bit-controller raises `arbLost`
  mid-byte, complete the current `rsp` with `arbLost = True`,
  release the bus, and stay quiescent until the producer drains
  / restarts.

**Sim hints (`src/sim/I2cByteControllerSim.scala`):**
- Build a Scala-side **behavioural target** (`BehaviouralTargetMock`)
  that lives in `src/sim/`: it watches the wired-AND bus, decodes
  START / STOP / address / R/W̅, ACKs at a configured slave
  address, and serves a small register file. Reuse it in Steps
  6, 7, 9, 10.
- Cases:
  - `AddrWrite(0x50) → WriteData(0xAB) → Stop` against a target
    that ACKs both bytes; assert mock register file received
    `0xAB`.
  - `AddrWrite(0x50) → WriteData(reg) → RepStart →
    AddrRead(0x50) → ReadData(ack=continue) →
    ReadData(ack=nack) → Stop` round-trip against a 2-byte
    register; assert the bytes match.
  - NACK on address: the mock answers a wrong address; assert
    `rsp.ackIn = 1` and the FSM stops cleanly.
  - Mid-byte arbitration loss: assert `rsp.arbLost = 1` once and
    only once.

**Makefile:** `sim-bytectrl` target.

---

### 🔲 Step 6 — `I2cController`

**Goal:** the public top-level Stream-fed controller. Mirrors the
shape of `UartTx` / `UartRx` so anyone who has wired up the UART
project can wire this one up by analogy.

**File:** `src/hw/I2cController.scala`

**Suggested IO:**
```scala
val io = new Bundle {
  val cmd = slave  Stream ByteCmd()  // identical to byte-controller cmd
  val rsp = master Stream ByteRsp()
  val bus = master(I2cIo())
}
```

**Design notes:**
- **Thin wrapper.** This block exists to be the place demos
  instantiate. It owns a `BusTiming(cfg)`, an `I2cByteController`
  and (when `useClockStretching`) a CDC-friendly synchroniser on
  `bus.scl.sample`. No new state machine of its own.
- **Pin out `I2cIo`.** Demo tops directly attach
  `io.bus.scl.ctrl.{write, writeEnable, read}` and
  `io.bus.sda.ctrl.{...}` to the iCE40 `inout` pins. This is the
  one and only place in the project where bus naming matters for
  external Verilog interop.
- No clock domain crossings inside this block beyond the SCL
  synchroniser; producer/consumer FIFOs (if any) live in the
  demo, like the Uart project does for `UartTxDemo`.

**Sim hints (`src/sim/I2cControllerSim.scala`):**
- Sweep matrix using the `BehaviouralTargetMock`:
  - 7-bit write of N bytes;
  - 7-bit read of N bytes;
  - write-then-read with `RepStart`;
  - ack-poll loop (repeated `AddrWrite` until ACK, modelling the
    EEPROM-write-cycle wait pattern);
  - NACK on address.
- Assert `rsp` order and flag bits match the producer's
  expectations on a per-transaction basis.

**Makefile:** `sim-controller` target.

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
- **Driver shape.** A small ROM of `ByteCmd` words feeds
  `I2cController.io.cmd`; `io.rsp.data` bytes go to a
  byte-to-hex-ASCII converter and out the UART. Reuse the
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
