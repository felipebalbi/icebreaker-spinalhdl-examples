# Uart — TODO

History and status of the Uart project. Phase 1 (transmit-only) is
done and running on real hardware. Phase 2 (receive + bidirectional
demos) is the next bottom-up build, planned in Steps 6–10 below.

Each completed step has a "What landed" summary so you can rediscover
the design rationale without re-reading the source. Open items live
under "Phase 2 — UartRx" and "Stretch goals" at the bottom.

Order is bottom-up: each block is self-tested before the next one
needs it.

---

## ✅ Done
- [x] `UartConfig` (clkFreqHz, baudRate, dataBits, stopBits, parity, useCts)
- [x] `ParityType` SpinalEnum (None/Even/Odd)
- [x] `BaudGenerator` (DDS) + sim
- [x] `TxShiftReg` + sim
- [x] `TxFsm` + sim
- [x] `UartTx` Stream-fed wrapper + sim
- [x] `UartTxDemo` integration top (clock domain, FIFO, message ROM) + sim
- [x] `icebreaker.pcf` (clk, reset, tx)
- [x] `UartTxDemoVerilog` generation entrypoint
- [x] **Hardware bring-up: `Hello, World\r\n` running on the iCEbreaker
      and received cleanly on the desktop's USB-UART.** 🎉
- [x] `RxSync` (2-FF synchronizer) + sim
- [x] `RxShiftReg` + sim
- [x] `RxFsm` + sim

---

## ✅ Phase 1 - UartTx
### ✅ Step 2 — `TxShiftReg`

**Goal:** parallel-load a byte, then expose one bit per shift pulse, LSB
first.

**File:** `src/hw/TxShiftReg.scala`

**Suggested IO:**
```scala
val io = new Bundle {
  val load  = in  Bool()                       // 1-cycle pulse to load `data`
  val data  = in  Bits(cfg.dataBits bits)      // sampled when load=1
  val shift = in  Bool()                       // 1-cycle pulse to shift right
  val bit   = out Bool()                       // current LSB → goes on the wire
}
```

**Design notes:**
- **Shift right, expose LSB.** UART is LSB-first.
- **Load wins over shift** if both fire same cycle (`when(load) ... elsewhen(shift) ...`).
- Width is exactly `cfg.dataBits` — do **not** pre-load start/stop bits;
  the FSM sequences those.
- `bit` reflects the register's LSB at all times. Idle-line management
  lives in the FSM, not here. Keep this block dumb.

**Sim (`src/sim/TxShiftRegSim.scala`):**
- Load `0xA5`, pulse `shift` 8×, check bit sequence is `1,0,1,0,0,1,0,1`.
- Try a few other patterns (`0x00`, `0xFF`, `0x80`, `0x01`).
- Simultaneous `load=1, shift=1` → freshly loaded LSB is visible.
- After all 8 shifts the register state is "don't care" — the FSM
  won't sample it again until the next load.

**Add to Makefile:** `sim-shift` target, and add it to the `sim` aggregate.

---

### ✅ Step 3 — `TxFsm`

**Goal:** sequence one frame:
`Idle → Start → Data×N → (Parity) → Stop×M → Idle`.

**File:** `src/hw/TxFsm.scala` (uses `spinal.lib.fsm.StateMachine`)

**What landed:**
- States: Idle / Start / Data / Parity / Stop, with a registered
  `txBit` driven from `whenIsActive` in every state (so every bit
  boundary has the same 1-cycle pipeline delay → uniform bit
  periods).
- Parity fully wired: even/odd parity bit is XOR-accumulated across
  the data shifts and emitted in `parityState`. `ParityType.None`
  elides the parity-state transition at elaboration time so the
  state synthesises away.
- Configurable stop bits (`cfg.stopBits`) plumbed through via
  `stopCounter`.
- `loadReg` pulses exactly once per frame (in `idleState` on
  `start`); `shiftReg` pulses `dataBits − 1` times during `dataState`.
- `busy := !isActive(idleState)` — single source of truth.

**Sim (`src/sim/TxFsmSim.scala`):**
- Sweeps the full config matrix: 8N1, 8N2, 8E1, 8E2, 8O1, 8O2, plus
  5E1/5E2/5O1/5O2 for the `dataBits` axis.
- Per-config: bit-correctness sweep on a handful of patterns
  (0x00, 0xFF, alternating, walking-1/0, mixed) +
  back-to-back-frame test + `loadReg`/`shiftReg` pulse-count check.
- Sim-side fakes for `BaudGenerator` (tick fork) and `TxShiftReg`
  (load/shift fork). The tick fork required two non-obvious
  fixes — see the comments in `TxFsmSim.scala`:
  - `waitSampling(20)` at fork start so the first `busy` poll
    doesn't fire on uninitialized state before reset settles.
  - `while (busy) waitSampling()` re-arm before each frame, because
    `signal.toBoolean` after `waitSampling()` returns the value of
    the cycle that just *completed* (not the post-edge value), so a
    naive level-poll catches a stale True the cycle after the FSM
    leaves the previous frame.

**Makefile:** `sim-fsm` target added; included in the `sim` aggregate.

---

### ✅ Step 4 — Wire it all together inside `UartTx`

**File:** `src/hw/UartTx.scala`

**What landed:**
- Composition: `BaudGenerator + TxShiftReg + TxFsm`. Wiring per the
  sketch below — `enable := busy`, `ready := canStart`, single-cycle
  Stream accept, payload latched into `TxShiftReg` the same cycle the
  FSM enters Start.
- **Optional CTS** via new `cfg.useCts: Boolean = true`. When set,
  the `cts` input pin exists and `canStart = !busy && cts`. When
  `false`, the port is omitted entirely (`cfg.useCts generate
  (in Bool())`) and the gate becomes a no-op (`ctsOk = True`) that
  synthesises away. Default `true` keeps existing call sites
  unchanged.
- CTS gates only the *start* of new frames; an in-flight frame
  cannot be aborted mid-transmission.
- Top-of-file Scaladoc and per-IO comments updated to reflect that
  parity is wired and CTS is used.

**Composition (current):**
```scala
val baud = BaudGenerator(cfg)
val sreg = TxShiftReg(cfg)
val fsm  = TxFsm(cfg)

baud.io.enable    := fsm.io.busy
fsm.io.tick       := baud.io.tick

sreg.io.load      := fsm.io.loadReg
sreg.io.data      := io.data.payload
sreg.io.shift     := fsm.io.shiftReg
fsm.io.shiftRegBit := sreg.io.bit

val ctsOk         = if (cfg.useCts) io.cts else True
val canStart      = !fsm.io.busy && ctsOk
fsm.io.start      := io.data.valid && canStart
io.data.ready     := canStart

io.tx             := fsm.io.txBit
```

**Sim (`src/sim/UartTxSim.scala`):**
- Black-box test of the wrapper: drive the Stream, watch `io.tx`,
  recover frames via mid-bit sampling (the same way a real RX does).
  Real BaudGenerator / TxShiftReg / TxFsm all in the loop.
- Tests: idle-high smoke, single-byte round trip across patterns
  (0x00/0xFF/0xAA/0x55/0xAD), back-to-back transmission with
  continuous `data.valid`, ready/valid handshake timing, **CTS
  blocks new frame** (with-CTS configs only), **CTS dropped
  mid-frame doesn't abort**, brief 8N1/8N2/8E1/8O1 config-matrix
  smoke, plus a `useCts=false` smoke confirming the optional port
  idiom works.

**Makefile:** `sim-top` target included in the `sim` aggregate.

---

### ✅ Step 5 — Hardware bring-up wrapper

**File:** `src/hw/UartTxDemo.scala` (new top-level wrapper)

**What landed:**
- Explicit `ClockDomain` (RISING / ASYNC / active-LOW reset) so the
  iCEbreaker user button (pulled high, grounded when pressed)
  actually resets the design. Spinal's implicit default would have
  silently left the reset pin disconnected.
- `StreamFifo[Bits(dataBits)]` (depth 16 by default) sitting between
  the message producer and `UartTx`. Provides natural back-pressure;
  no bytes lost when the line is busy.
- Tiny LUT-ROM holding the broadcast message (default
  `"Hello, World\r\n"`) plus a counter that walks it. Counter only
  advances on `push.fire`, so when the FIFO fills the producer
  parks until the UART drains.
- `RegNext(uart.io.tx) init(True)` on the output so the line
  starts cleanly idle-high regardless of FSM startup transient.
- Three constructor knobs: `cfg`, `message`, `fifoDepth`.

**Stretch goal "Internal `StreamFifo`" — resolution:**
Resolved by composition rather than baking it into `UartTx`. The
FIFO lives in `UartTxDemo` (the integration layer) so users with
their own buffering aren't forced to pay for a duplicate. See
`UartTxDemo.scala` Scaladoc for the full rationale.

---

### ✅ Step 5b — Hardware bring-up (on the board)

Confirmed working end-to-end on real hardware:

1. `make` produces `gen/UartTxDemo.bin`.
2. `make flash` loads it onto the iCEbreaker via `iceprog`.
3. The board broadcasts `"Hello, World\r\n"` continuously at 115 200
   baud out the FPGA's tx pin (pcf pin 9).
4. Wired tx → host's USB-UART RX (the iCEbreaker's onboard FT2232
   already exposes one), `picocom -b 115200 /dev/ttyUSB1` shows the
   message scrolling cleanly with no corruption.

The `useCts = false` path is what's deployed (no flow control on the
USB-UART).

---

## 🔲 Phase 2 — UartRx and bidirectional demos

Same bottom-up rhythm as Phase 1: each block gets its own sim before
the next one composes it. RX is genuinely harder than TX — the wire
arrives on no clock you own, so you have to recover bit timing from
the start-bit edge and oversample to land samples at bit-centers.

**Cross-cutting decisions:**
- **Config rename:** `UartConfig` → `UartConfig`. The file rename
  (`UartConfig.scala` → `UartConfig.scala`) is already done as
  part of the directory/package rename commit; the **case-class
  name + field additions** (`oversample: Int = 16`, `useRts: Boolean = true`)
  are the Step 0 work and need a sweep through every importer.
- **Oversample factor:** `16×` (industry standard, balances jitter
  tolerance vs. baud-clock divider granularity). New `oversample`
  field on `UartConfig`.
- **No new `BaudGenerator`** — instantiate the existing DDS-based one
  with `freqDiv = clkFreqHz / (baudRate * oversample)` for RX. The
  RxFsm counts oversample ticks itself.
- **Error reporting:** RX exposes `framingError`, `parityError`, and
  `overrun` as side-band flags pulsed for one cycle alongside `valid`.

---

### ✅ Step 6 — `RxSync` (2-FF synchronizer)

**File:** `src/hw/RxSync.scala`

**Why:** the off-chip `rx` pin is async to our system clock. A direct
sample is a metastability bug waiting to happen — shows up as random
framing errors months later. Two back-to-back FFs collapse that risk
to negligible MTBF.

**Suggested IO:**
```scala
val io = new Bundle {
  val asyncIn = in  Bool()  // direct from FPGA pin, no clock relation
  val syncOut = out Bool()  // safe to use in our clock domain
}
```

**Implementation:** literally `RegNext(RegNext(io.asyncIn, init = True))`.
Init high so the line looks idle on reset (idle-high is a UART invariant).

**Sim:** drive `asyncIn` with toggling pattern, verify `syncOut` is
delayed by exactly 2 cycles and never drops/duplicates a transition.

---

### ✅ Step 7 — `RxShiftReg`

**File:** `src/hw/RxShiftReg.scala`

**What landed:**
- LSB-first shift-in: on `shift`, `sreg := sample ## sreg(N-1 downto 1)`.
  Worked example for 0xAD in the component Scaladoc.
- **Clear wins over shift** (mirrors `TxShiftReg`'s "load wins over
  shift" priority). RxFsm contract is to keep the two mutually
  exclusive (clear at frame entry, shifts at each bit-centre tick),
  so the priority is normally invisible. The defensive default of
  "go to known state on collision" matches industry coding-guide
  convention and keeps TX/RX symmetric.
- Reset value `0` rather than all-ones: the consumer always issues
  `clear` at frame start so the reset value is a don't-care, and `0`
  makes the reset path observationally identical to the clear path
  (no "did this byte come from reset or a real frame?" ambiguity).

**Sim (`src/sim/RxShiftRegSim.scala`):**
- Sweeps every supported `dataBits` width (5..9) via a `runOne`
  helper, proving the `cfg.dataBits - 1 downto 1` slice
  parameterises cleanly.
- Uses the **record-and-post-validate** recipe from `RxSyncSim`:
  `onSamplings` records `(clear, shift, sample, data)` on every
  edge into parallel `ArrayBuffer`s; a single contract loop walks
  the recording with a tiny in-Scala reference register and asserts
  at every edge that the recorded `data` matches what the contract
  predicts. Inline `dut.io.X.toLong` asserts deliberately omitted —
  they race the post-`waitSampling` register-commit window.
- **Subtle timing alignment** (documented inline so future sims
  don't re-hit it): `onSamplings` reads register state BEFORE the
  edge's update commits, so `dataSeq(k)` is the value just *before*
  edge k. The contract is therefore
  `dataSeq(k) = apply(inputs(k-1), dataSeq(k-1))` — index inputs at
  `k-1`, not `k`. Same effect as the `syncOut(k) == asyncIn(k-2)`
  alignment in `RxSyncSim` (one register stage = one edge of delay).
- Coverage: post-reset zero, full byte sweep at every width,
  clear-wins-over-shift collision plus follow-up shift, hold,
  800-cycle randomised burst with biased probabilities and fixed
  PRNG seed (`0xBADC0FFEEL`) for reproducible failures.

**Makefile:** `sim-rxshiftreg` target added; included in the `sim`
aggregate and `.PHONY`.

---

### ✅ Step 8 — `RxFsm` (the heart of the receiver)

**File:** `src/hw/RxFsm.scala`

**What landed:**
- States: `idle / startVerify / data / parity / stop`. `idle` watches
  for `io.rx.fall` (1-cycle history); `startVerify` waits
  `oversample/2` ticks then resamples — glitches that go high again
  return to idle without ever entering data. `data` shifts a bit per
  `oversample` ticks, `parity` resamples once and compares against
  the XOR-accumulated expected parity, `stop` consumes `cfg.stopBits`
  bit periods and reports framing error if any is low.
- **Composes `RxShiftReg`** rather than re-implementing the shifter.
  `clear` is pulsed on entry to `dataState` (not on idle→startVerify)
  so a rejected glitch never wastes a clear; `shift` is pulsed at
  every data-bit centre tick. `clear-wins-over-shift` priority makes
  the wiring safe even though the FSM never raises both same cycle.
- **Tick contract is different from TX.** RX BaudGenerator must NOT
  be gated by `busy` — `startVerify` needs ticks immediately after
  the falling edge. Documented in the top-of-file Scaladoc.
- **Error/valid asymmetry (intentional, not a bug):**
  `payloadValidReg` is sticky-until-`fire`; `framingError` /
  `parityError` / `overrun` are 1-cycle pulses cleared on the next
  visit to `idle`. Consumers that take >1 cycle to acknowledge a
  byte must latch the error flags themselves.
- **Overrun:** if a new frame completes while `valid` is still high,
  the new byte clobbers the old in `RxShiftReg` during data shifts;
  consumer reads the new byte tagged with the old `valid` plus an
  `overrun` pulse. (Conventional UART overrun usually drops the new
  byte instead. Flagged for follow-up; not changed here.)
- Parity accumulator seeded `False` for Even, `True` for Odd at
  `dataState.onEntry`; XORed every data bit. By `parityState` entry
  it holds the expected received parity bit value. `ParityType.None`
  elides the parity-state transition at elaboration time so the
  state synthesises away.

**Sim (`src/sim/RxFsmSim.scala`):**
- Sweeps the matrix `8N1, 8N2, 8E1, 8O1, 5N1`.
- Free-running tick fork at oversample rate (NOT gated by `busy`) —
  this is the key difference from `TxFsmSim`'s tick fork.
- Sim-side UART line driver (`driveBits`/`frameBits`) walks `io.rx`
  through start/data/[parity]/stop, holding each level for one full
  bit period (`oversample × ticksPerOversample` clocks).
- Tests: post-reset idle, byte sweep, start-bit glitch rejection
  (1-cycle low pulse on idle line — must NOT trigger a frame),
  framing error (stop bit low), recovery after framing error,
  parity error (deliberately corrupted parity bit), back-to-back
  frames with consumer firing handshake, and overrun (consumer
  holds `ready` low across two frames — expects `overrun` pulse and
  `valid` to remain sticky).
- Uses `clkFreqHz=32000, baudRate=1000, oversample=16,
  ticksPerOversample=2 → bitClocks = 32` so sims run fast.

**Open follow-ups (flagged but not fixed in this step):**
- **Error-flag stickiness mismatch.** Errors pulse for 1 cycle while
  `valid` is sticky-until-`fire`. Consumer must latch errors on the
  same cycle as `valid` if it wants them.
- **Overrun byte clobber.** New byte overwrites old in the shifter
  before `valid` is consumed. A more conventional implementation
  would drop the new byte instead.
- **`oversample = 1` edge case.** `log2Up(0)` for the half-bit
  counter width and `(oversample/2) - 1 = -1` both go bad.
  `UartConfig` currently only enforces `oversample >= 1`. Real
  default is 16, so not a runtime concern, but worth tightening.

**Makefile:** `sim-rxfsm` target added; included in the `sim`
aggregate and `.PHONY`.

---

### 🔲 Step 9 — `UartRx` wrapper

**File:** `src/hw/UartRx.scala`

**Why:** symmetrical wrapper to `UartTx`. Composes `RxSync` +
`BaudGenerator` (at oversample rate) + `RxShiftReg` + `RxFsm`.

**Suggested IO:**
```scala
val io = new Bundle {
  val rx           = in  Bool()                       // FPGA pin
  val payload      = master Stream(Bits(cfg.dataBits bits))
  val rts          = cfg.useRts generate (out Bool())  // mirror of useCts
  val framingError = out Bool()
  val parityError  = out Bool()
  val overrun      = out Bool()
}
```

**Optional `useRts`:** symmetric to `useCts` on TX. RTS goes low when
downstream isn't ready, telling the other side to stop sending.
Use the same `Bool generate (out Bool())` idiom from `UartTx`.

**Sim:** black-box driver synthesizes UART frames on `io.rx`, walks
the same test matrix as RxFsm but at the wrapper level (proves all
the sub-blocks are wired correctly).

---

### 🔲 Step 10 — Demos

Three small synthesizable tops, each picking up where Phase 1's
`UartTxDemo` left off. All use the explicit ClockDomain pattern from
`UartTxDemo` (RISING / ASYNC / active-LOW reset).

**10a. `UartRxDemo` — receive only, drive LEDs**
- Wires the iCEbreaker's onboard `rx` pin to `UartRx`.
- Sinks the payload Stream into the 3 onboard LEDs (R/G/B = received
  byte's lower 3 bits, latched). Bonus: blink one of them on
  `framingError` so corruption is visible at a glance.
- Hardware test: type characters in `picocom`, watch LEDs change.

**10b. `UartEchoDemo` — RX → FIFO → TX**
- Wires `UartRx.payload` straight into a `StreamFifo` and out to
  `UartTx.io.data`.
- The simplest end-to-end proof: `picocom` types "hello", sees "hello"
  back. Catches almost every wiring bug.

**10c. `UartDemo` — both directions, independent**
- Composes one `UartTx` (with `UartTxDemo`'s message ROM still
  broadcasting) **and** one `UartRx` (echoing input via a small
  command interpreter, or just forwarding to LEDs).
- Demonstrates that TX and RX truly run independently, with no
  shared state, on a single FPGA.
- Pick which behaviour to ship at implementation time — the
  scaffolding (clock domain, both wrappers, FIFO between them if
  desired) is the same.

**`pcf` updates:** add `io_rx` (iCEbreaker FT2232 host→FPGA pin) for
all three demos. Add the LED pins for 10a/10c.

---

## 🔲 Stretch goals (optional)

- [x] ~~**Internal `StreamFifo`** so bursty producers don't stall.~~
      *Resolved by composition* — `UartTxDemo` instantiates one
      externally instead. See Step 5 above.
- [x] ~~**Loopback testbench**~~ — superseded by Step 10b
      (`UartEchoDemo`), which is the same idea promoted from a
      pure-sim toy to a real hardware demo.
- [x] ~~**UartRx**~~ — promoted to Phase 2 above (Steps 6–9).
- [x] ~~**Full `Uart` wrapper**~~ — addressed by Step 10c
      (`UartDemo`) which composes both directions at the integration
      layer rather than hiding them behind one bundled module.
- [ ] **Counter-based `BaudGenerator` variant** — implement as
      `BaudGeneratorCounter`, parameterise to pick one, compare LUT
      usage in `nextpnr-ice40` reports vs. DDS.
- [ ] **Rename `UartConfig` → `UartConfig`** (file already renamed
      to `UartConfig.scala` as part of the directory/package rename
      commit — still need to update the case-class name and add
      `oversample`/`useRts` fields). Touches every importer; do it
      as a single sweep at the start of Step 6 (formally Step 0).

---

## When to ask for help

- SpinalHDL syntax / type error you don't recognise.
- Sim passes but hardware doesn't (or vice versa) — highest-value
  moment to consult.
- Want a design review **before** committing to an approach (cheaper
  than reviewing after the fact).
- Considering deviating from the suggested IO/architecture above and
  want a sanity check.

---

## Conventions reminder

- All modified files should be run through `dos2unix`.
- Sims live in `src/sim/`, hardware in `src/hw/`.
- Each sim is its own `object …Sim { def main … }` and gets its own
  `sim-<name>` Makefile target plus inclusion in the aggregate `sim`.
- Detailed doc comments on every Component explaining *why*, not just
  *what*.
