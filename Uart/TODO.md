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
- [x] `UartRx` Stream-producing wrapper + sim
- [x] `UartEchoDemo` (RX → FIFO → TX) + `icebreaker.pcf` `io_rx`
- [x] **Hardware bring-up: end-to-end echo loop running on the
      iCEbreaker — `picocom` round-trips characters cleanly.** 🎉
- [x] `UartController` (APB3-fronted, regif-generated register file)
- [x] `UartConfig.txFifoDepth` / `rxFifoDepth` parameters
- [x] Runtime-tunable BAUD via DDS `phaseInc` register
- [x] `UartEchoDemo` rebuilt on top of `UartController` + a tiny
      APB master FSM
- [x] `make docs` target — HTML / C / JSON / RALF / RDL datasheet
      generators
- [x] `STATUS` slim-down + dedicated `TX_FIFO_STATUS` /
      `RX_FIFO_STATUS` registers (full/empty/count/depth)
- [x] `ISR` fields flipped from RC to W1C; `CLKFREQ` register
      removed

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

## ✅ Phase 2 — UartRx and bidirectional demos

Same bottom-up rhythm as Phase 1: each block gets its own sim before
the next one composes it. RX is genuinely harder than TX — the wire
arrives on no clock you own, so you have to recover bit timing from
the start-bit edge and oversample to land samples at bit-centers.

**Cross-cutting decisions (made up front, all delivered):**
- **Oversample factor:** `16×` (industry standard, balances jitter
  tolerance vs. baud-clock divider granularity). `oversample` field
  on `UartConfig`.
- **No new `BaudGenerator`** — instantiate the existing DDS-based one
  with `cfg.copy(baudRate = cfg.baudRate * cfg.oversample)` for RX.
  The RxFsm counts oversample ticks itself.
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

### ✅ Step 9 — `UartRx` wrapper

**File:** `src/hw/UartRx.scala`

**What landed:**
- Composition: `RxSync + BaudGenerator + RxFsm`. Wiring is mostly
  mechanical mirror of `UartTx`; full Scaladoc explains the two
  non-obvious choices (below).
- **BaudGenerator at oversample rate via config-copy trick:**
  `BaudGenerator(cfg.copy(baudRate = cfg.baudRate * cfg.oversample))`.
  Avoids a second generator module — the existing DDS just gets a
  scaled `baudRate` parameter.
- **Free-running BaudGenerator:** `baud.io.enable := True`, in
  contrast to `UartTx`'s `enable := fsm.io.busy`. The half-bit
  verify after the start-bit edge needs ticks immediately, before
  `busy` could even be asserted; gating on `busy` would put the
  half-bit sample in the wrong place.
- **`RxSync` only crossed input:** the FSM is fed `sync.io.syncOut`,
  not `io.rx`. Skipping the sync would put a metastability hazard on
  the `io.rx.fall` edge detector — manifests as random framing
  errors on real silicon.
- **Optional `useRts`** via `cfg.useRts generate (out Bool())`,
  symmetric to `useCts` on TX. When enabled, `io.rts := io.payload.ready`
  (direct mirror — no lead-time before buffer fills, but fine at
  115200 with even a small upstream FIFO). When disabled, the port is
  omitted entirely.
- Three error flags (`framingError`, `parityError`, `overrun`)
  pass through unchanged from the FSM; they pulse for one cycle
  alongside `valid` and clear when the FSM next visits idle.
  Consumers must latch alongside `valid` if they want them.

**Sim (`src/sim/UartRxSim.scala`):**
- Black-box test of the wrapper: drive `io.rx` as a fake UART
  line, watch `io.payload` and the side-band flags. Real
  `BaudGenerator` and real `RxSync` in the loop (no fakes — both
  blocks are inside the DUT now).
- Sweep matrix `8N1, 8N2, 8E1, 8O1, 5N1` plus a `useRts = true`
  smoke variant.
- Tests: post-reset idle, byte sweep, framing error + recovery,
  parity error, overrun, RTS-mirrors-ready. All driver forks
  captured + `.join()`'d (the lesson from `RxFsmSim` —
  driver/main-thread races on `io.rx` are undefined).
- Used scaled `clk = 32 kHz / baud = 1 kHz / oversample = 16` so
  bit period = 32 sys clocks.

**Makefile:** `sim-rxtop` target added; included in `sim`
aggregate and `.PHONY`.

---

### ✅ Step 10 — Demos

Originally planned as three demos (`UartRxDemo` LEDs / `UartEchoDemo`
loopback / `UartDemo` dual-direction). In the end we built only the
echo demo: it's the smallest demo that exercises the entire stack
end-to-end and proves everything works. The LED-only and
dual-direction variants would have been pure exercise — same
composition pattern, no new design problems — so they're explicitly
**not built**. The echo demo is the canonical hardware bring-up.

**`UartEchoDemo` — RX → FIFO → TX** ✅
- **What landed:** explicit `ClockDomain` (RISING / ASYNC /
  active-LOW reset, same pattern as `UartTxDemo`); `UartRx +
  StreamFifo[Bits(dataBits)] + UartTx` wired with `<<`; `RegNext`
  init-True on the output line. Defaults: `cfg = UartConfig(useCts
  = false, useRts = false)`, `fifoDepth = 16`. `require(...)` guard
  rejects configs that ask for flow-control pins this demo doesn't
  expose.
- **Verilog entry point:** `UartEchoDemoVerilog`. Makefile `TOP`
  switched to `UartEchoDemo`.
- **`icebreaker.pcf`:** added `io_rx` (FT2232 channel B host→FPGA
  data line).
- **Hardware bring-up:** confirmed working — `make flash`, attach
  `picocom -b 115200 /dev/ttyUSB1`, type any character, see it
  echoed instantly. End-to-end UART working on real silicon. 🎉
- The FIFO matters even though RX and TX run at the same baud:
  dropping it would couple back-pressure and force RX-side
  overrun any time TX stalled mid-frame. With the FIFO, RX can
  finish a frame while TX is mid-transmit.

---

## ✅ Phase 3 — Memory-mapped controller (`UartController` + regif)

### ✅ Step 11 — `UartController` register file

**What landed:**

- **Files added/changed:**
  - `src/hw/UartController.scala` — APB3-fronted wrapper around
    `UartTx`/`UartRx`. Owns TX/RX `StreamFifo`s (`cfg.fifoDepth`,
    default 16), runtime-tunable BAUD register driving the DDS,
    and a regif register file (`spinal.lib.bus.regif`) covering
    CTRL / STATUS / ISR / IER / TXDATA / RXDATA / BAUD / CFG_INFO
    / CLKFREQ.
  - `src/hw/UartConfig.scala` — added `fifoDepth: Int = 16`.
  - `src/hw/BaudGenerator.scala` — refactored to take an
    `accWidth` only (no longer takes the full `UartConfig`); its
    `phaseInc` is a runtime input. Added the
    `BaudGenerator.phaseIncFor(...)` helper so callers can compute
    a compile-time-correct constant for static configurations.
  - `src/hw/UartTx.scala`, `src/hw/UartRx.scala` — both now expose
    `io.baudPhaseInc` as a mandatory input and wire it straight
    into their internal `BaudGenerator`. RX must pre-shift by
    `oversample`.
  - `src/hw/UartEchoDemo.scala` — fully rewritten on top of
    `UartController` + a hand-rolled APB3-master FSM. Same
    on-the-wire behaviour, same `icebreaker.pcf` pinout.
  - `src/hw/UartTxDemo.scala`, `src/sim/BaudGeneratorSim.scala`,
    `src/sim/UartTxSim.scala`, `src/sim/UartRxSim.scala` — updated
    to drive the new `phaseInc` input via
    `BaudGenerator.phaseIncFor(cfg, ...)`.
  - `src/sim/UartControllerSim.scala` — APB-driven loopback test
    using `Apb3Driver`. Exercises CTRL/STATUS/ISR/RXDATA/TXDATA
    end to end and proves RC sticky-clear semantics.
  - `Makefile` — added `sim-controller`, `gen-controller`, and
    `docs` targets; added `sim-controller` to the `sim` aggregate
    and `.PHONY`.

- **Address map (machine-readable in the generated regif docs):**
  ```
  0x00 CTRL     RW   [0]=enable [1]=tx_enable [2]=rx_enable
  0x04 STATUS   RO   tx_busy / fifo_full / fifo_empty / rx_data_avail / rx_fifo_full
  0x08 ISR      RC   sticky framing/parity/overrun/tx_done/rx_done
  0x0C IER      RW   per-bit interrupt mask, mirrors ISR layout
  0x10 TXDATA   WO   write pushes a byte into the TX FIFO
  0x14 RXDATA   RO   read pops the RX FIFO front
  0x18 BAUD     RW   DDS phase increment, reset = phaseIncFor(cfg)
  0x1C CFG_INFO RO   dataBits / stopBits / parity / oversample / fifoDepth
  0x20 CLKFREQ  RO   cfg.clkFreqHz
  ```

- **Divergences from the original ad-hoc plan and rationale:**
  1. **Errors are sticky-with-read-clear (RC), not W1C.** RC matches
     the 16550's "read LSR clears it" pattern firmware writers
     are familiar with, and avoids the W1C race window where a
     fresh error pulse arriving on the same cycle as the clear
     would be lost. ISR/IER mirror layouts so a single OR-reduce
     drives the IRQ line cleanly.
  2. **TXDATA/RXDATA are hand-rolled on top of regif's `doWrite` /
     `doRead` + `writeAddress()` / `readAddress()` rather than the
     stock `RW` field model.** A FIFO push isn't a register; we
     want a one-cycle pulse, not stored state. The plain `WO` /
     `RO` fields are kept anyway so they appear in the generated
     datasheet at the right offset — they document the address,
     while the FIFO wiring sits in user logic.
  3. **`busif.writeData` is sourced for the TX FIFO push instead
     of the field's stored value.** The field updates one cycle
     after `doWrite`; using `writeData` keeps the FIFO push
     payload aligned with the ACCESS phase of the APB transaction.
  4. **`UartConfig.useCts` and `useRts` are forbidden in v1.** The
     register layout would otherwise need MODEM-control fields,
     and the streaming cores' CTS plumbing isn't mapped through
     yet. Forced to `false` via `require`.
  5. **`oversample` must be a power of two.** The RX baud generator
     runs at `phaseInc << log2(oversample)`; a non-power-of-two
     oversample would leave rounding error in the shift. Default
     16 satisfies this.

- **Sim:** `src/sim/UartControllerSim.scala`, run with
  `make sim-controller`. Loopback at the UART line: APB writes to
  TXDATA, the testbench feeds TX → RX, APB reads from RXDATA,
  bytes round-trip unchanged. Also exercises CFG_INFO/CLKFREQ
  introspection and ISR.rx_done sticky-then-clear-on-read.

- **Documentation generation:** `make docs` runs
  `UartControllerDocs`, which writes `gen/uart_controller.html`
  (datasheet), `.h` (C header), `.json`, `.ralf`, and `.rdl`
  (SystemRDL) — all derived from the regif `doc = "..."`
  parameters on each register/field.

- **Makefile:** `sim-controller`, `gen-controller`, `docs`.

### ✅ Step 12 — `UartController` register-file refinements

Closeout of four coupled register-file changes that came out of
hands-on use of the controller:

1. **FIFO_STATUS split.** Peeled the four FIFO-related bits out of
   `STATUS` into dedicated `TX_FIFO_STATUS` (0x1C) and
   `RX_FIFO_STATUS` (0x20) registers. Each carries `[0]=full`,
   `[1]=empty`, `[15:8]=count` (live occupancy from
   `StreamFifo.io.occupancy`), and `[23:16]=depth` (synth-time
   capacity, RO). Firmware can compute free-space `= depth - count`
   in a single 32-bit read per side. STATUS now holds only
   `[0]=tx_busy`.

2. **`ISR` flipped from `RC` to `W1C`.** Read-clear competed with
   firmware's own clearing policy: an interrupt handler that
   wanted to "read ISR, mask via IER, wake bottom-half task, let
   the task clear" couldn't, because the very read in step 1
   destroyed the sticky state. W1C puts firmware in charge.
   Hardware-side `.set()` calls and the IER mask logic are
   unchanged; the only behavioural delta is that a plain read no
   longer clears anything.

3. **Removed `CLKFREQ` register.** It was misleading — `cfg.clkFreqHz`
   is a synth-time constant baked into BAUD's reset value, but
   exposing it as RO implied firmware could trust it for divider
   math when nothing actually validated the synth value. Mirrors
   the STM32 BRR pattern: firmware tracks the clock tree itself
   and computes BAUD's phase increment.

4. **`UartConfig.fifoDepth` split into `txFifoDepth` /
   `rxFifoDepth`.** Asymmetric workloads (burst-write logger,
   RX-heavy console) can size the two halves independently.
   Removed `cfgInfoFifoDepth` from CFG_INFO since the depth lives
   on each per-side FIFO_STATUS register now. Added `<= 255`
   guards on each field so the 8-bit `depth` slot can't overflow.

**Files changed:**
- `src/hw/UartConfig.scala` — `fifoDepth` → `txFifoDepth` +
  `rxFifoDepth`; new require guards.
- `src/hw/UartController.scala` — STATUS slim-down; two new
  FIFO_STATUS registers; RC→W1C on the five ISR fields; CLKFREQ
  removed; `cfgInfoFifoDepth` removed; per-side StreamFifo
  depths; address-map scaladoc + ISR comment block updated.
- `src/hw/UartEchoDemo.scala` — APB master polls
  `RX_FIFO_STATUS[1]` (inverted = data-available) instead of
  `STATUS[3]`.
- `src/sim/UartControllerSim.scala` — new register offset
  constants; CLKFREQ readback dropped; FIFO_STATUS depth/empty
  assertions added; sticky-clear test rewritten to W1C semantics
  ("read; assert sticky; read again; assert still sticky;
  W1C-write; read; assert cleared").
- `README.md` — knobs table updated for the FIFO depth split.

**Updated address map:**
```
0x00 CTRL            RW
0x04 STATUS          RO   [0]=tx_busy
0x08 ISR             W1C  framing/parity/overrun/tx_done/rx_done
0x0C IER             RW   per-bit interrupt mask (mirrors ISR)
0x10 TXDATA          WO
0x14 RXDATA          RO
0x18 BAUD            RW   DDS phase increment
0x1C TX_FIFO_STATUS  RO   [0]=full [1]=empty [15:8]=count [23:16]=depth
0x20 RX_FIFO_STATUS  RO   same layout as TX
0x24 CFG_INFO        RO   dataBits / stopBits / parity / oversample
                          (no fifoDepth — lives on each FIFO_STATUS)
                          (no CLKFREQ register at all)
```

**Sim:** `make sim-controller`. Same loopback as Step 11; new
assertions cover the FIFO depth/empty fields and the W1C clear
behaviour.

---

## 🔲 Stretch goals (optional)

- [x] ~~**Internal `StreamFifo`** so bursty producers don't stall.~~
      *Resolved by composition* — `UartTxDemo` instantiates one
      externally instead. See Step 5 above.
- [x] ~~**Loopback testbench**~~ — superseded by `UartEchoDemo`,
      which is the same idea promoted from a pure-sim toy to a real
      hardware demo.
- [x] ~~**UartRx**~~ — delivered as Phase 2 above (Steps 6–9).
- [x] ~~**`UartRxDemo` (LEDs) and `UartDemo` (dual-direction)**~~ —
      explicitly won't-do. Both are pure exercises in the same
      composition pattern as the echo demo, with no new design
      problems. Not worth the maintenance burden.
- [x] ~~**Full `Uart` wrapper**~~ — would have been part of the
      dual-direction demo, also won't-do.
- [ ] **Counter-based `BaudGenerator` variant** — implement as
      `BaudGeneratorCounter`, parameterise to pick one, compare LUT
      usage in `nextpnr-ice40` reports vs. DDS.

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
