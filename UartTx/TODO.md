# UartTx — TODO

A self-contained checklist of everything left to build. Designed so you can
work through it without needing to ask for the next step. Each item lists
**what** to do, **why** it matters, the **interface** to aim for, and what
to **verify** in sim.

Order is bottom-up: each block is self-testable before the next one needs it.

---

## ✅ Done
- [x] `UartTxConfig` (clkFreqHz, baudRate, dataBits, stopBits, parity)
- [x] `ParityType` SpinalEnum (None/Even/Odd)
- [x] `BaudGenerator` (DDS) + sim
- [x] `UartTx` IO bundle stub (Stream in, tx out, cts in)
- [x] `icebreaker.pcf` pinout (clk, tx, cts, data Stream)
- [x] `UartTxVerilog` generation entrypoint (UartTx is its own top)

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

## 🔲 Step 4 — Wire it all together inside `UartTx`

**File:** edit `src/hw/UartTx.scala` (replace the stub body).

**Composition sketch:**
```scala
val baud = BaudGenerator(cfg)
val sreg = TxShiftReg(cfg)
val fsm  = TxFsm(cfg)

baud.io.enable    := fsm.io.busy        // tick only while transmitting
fsm.io.tick       := baud.io.tick

sreg.io.load      := fsm.io.loadReg
sreg.io.data      := io.data.payload
sreg.io.shift     := fsm.io.shiftReg
fsm.io.shiftRegBit := sreg.io.bit

// Stream handshake + CTS gating
val canStart      = !fsm.io.busy && io.cts
fsm.io.start      := io.data.valid && canStart
io.data.ready     := canStart           // accept the cycle FSM starts

io.tx             := fsm.io.txBit
```

**Why `enable := busy`:**
Holding the BaudGenerator quiescent during Idle ensures the *first* tick
after `start` lands a clean full bit period later → start bit is exactly
one bit period wide. This is exactly the bug the `enable` input was added
to prevent. (Verify in sim: a frame's start bit should be ≥ ticksPerBit
clocks wide, never truncated.)

**Why `ready := canStart` (combinational):**
The Stream transfer happens the same cycle the FSM enters Start. The byte
on `data.payload` is latched into `TxShiftReg` via `loadReg` that same
cycle. Single-cycle accept, no buffering needed.

**End-to-end sim (`src/sim/UartTxSim.scala`):**
- Drive the Stream with a sequence of bytes (e.g. `"Hello"` then random).
- Sample `io.tx` at the *middle* of each bit period
  (`ticksPerBit/2` cycles after each start-edge falling) and reconstruct
  the byte. Compare against what you sent.
- Tie `cts` high.
- **CTS test:** drop `cts` between frames — the next frame should wait.
  Drop `cts` mid-frame — should NOT abort the in-flight frame (CTS only
  gates the *start* of new ones).
- **Back-to-back test:** keep `data.valid` high across multiple bytes,
  verify each one arrives intact with no missing/extra bit periods.

---

## 🔲 Step 5 — Hardware bring-up

The pcf and Verilog generation are already wired up. Just:

1. `make` → produces `gen/UartTx.bin`.
2. `make flash` → loads it onto the icebreaker.
3. **Driving it from outside:** since `data_valid`, `data_ready`, and
   `data_payload[7:0]` are exposed as physical pins (per current pcf),
   you'll need *something* to wiggle them. Options:
   - **Easier:** add a tiny pattern generator inside UartTx (or in a
     thin wrapper) that emits a fixed byte (`'U' = 0x55` is a great
     test pattern — alternating bits make scope traces obvious) once
     per second, ignoring the external `data_*` pins. Gate behind a
     constructor flag so sim still uses the Stream.
   - **Harder but more honest:** drive the `data_*` pins from another
     MCU / FTDI GPIOs and respect the ready/valid handshake.
4. **Verify on host:** wire icebreaker `tx` → FTDI cable RX, GND → GND.
   `picocom -b 115200 /dev/ttyUSB1` (Linux) or PuTTY (Windows). You
   should see your test pattern.
5. **Scope check (optional but instructive):** probe `tx` and verify
   bit width = `1 / 115200 ≈ 8.68 µs`, frame structure matches 8N1.

**Pcf to revisit:** the `cts` and `data_*` pin assignments are marked
`REVISIT` — check them against the icebreaker pinout and your actual
wiring before flashing.

---

## 🔲 Stretch goals (optional, in roughly increasing complexity)

- [ ] **Internal `StreamFifo`** so bursty producers don't stall.
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
