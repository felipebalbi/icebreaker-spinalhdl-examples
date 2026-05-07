# UartTx — TODO

History and status of the UartTx build, plus optional follow-ups.
Each completed step has a "What landed" summary so you can rediscover
the design rationale without re-reading the source. Open items live
under "Stretch goals" at the bottom.

Order is bottom-up: each block was self-tested before the next one
needed it.

---

## ✅ Done
- [x] `UartTxConfig` (clkFreqHz, baudRate, dataBits, stopBits, parity, useCts)
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

---

## ✅ Step 2 — `TxShiftReg`

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

## ✅ Step 3 — `TxFsm`

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

## ✅ Step 4 — Wire it all together inside `UartTx`

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

## ✅ Step 5 — Hardware bring-up wrapper

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

## ✅ Step 5b — Hardware bring-up (on the board)

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

## 🔲 Stretch goals (optional, in roughly increasing complexity)

- [x] ~~**Internal `StreamFifo`** so bursty producers don't stall.~~
      *Resolved by composition* — `UartTxDemo` instantiates one
      externally instead. See Step 5 above.
- [ ] **Counter-based `BaudGenerator` variant** — implement as
      `BaudGeneratorCounter`, parameterise UartTx to pick one,
      compare LUT usage in `nextpnr-ice40` reports vs. DDS.
- [ ] **Loopback testbench:** instantiate UartTx → wire → minimal UartRx
      model in sim and verify byte-perfect transfer at the byte level.
- [ ] **UartRx** as its own bottom-up exercise (start-bit detection with
      16× oversampling, mid-bit sampling, framing-error detection).
- [ ] **Full `Uart` wrapper** combining TX+RX with proper RTS/CTS.

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
