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

## 🔲 Step 3 — `TxFsm`

**Goal:** sequence one frame:
`Idle → Start → Data×N → (Parity) → Stop×M → Idle`.

**File:** `src/hw/TxFsm.scala` (use `spinal.lib.fsm.StateMachine`)

**Suggested IO:**
```scala
val io = new Bundle {
  val start       = in  Bool()   // pulse to begin a frame (FSM is in Idle)
  val tick        = in  Bool()   // baud tick (advance one bit boundary)
  val shiftRegBit = in  Bool()   // current bit from TxShiftReg
  val busy        = out Bool()   // high while not Idle
  val loadReg     = out Bool()   // pulse on accepted byte (latch shift reg)
  val shiftReg    = out Bool()   // pulse on each Data-state tick
  val txBit       = out Bool()   // what to drive on the line this bit period
}
```

**States:**
- `Idle`: `txBit=1`, `busy=0`. On `start`: pulse `loadReg`, go to `Start`.
- `Start`: `txBit=0`. After one `tick`, go to `Data`.
- `Data`: `txBit = shiftRegBit`. Count ticks; after `dataBits` ticks total,
  go to `Parity` (if enabled) else `Stop`. Pulse `shiftReg` once per tick
  while in Data — verify the count carefully so all bits get exposed.
- `Parity` (if `cfg.parity != None`): `txBit = parity bit`. After 1 tick,
  go to `Stop`.
- `Stop`: `txBit=1`. After `stopBits` ticks, go to `Idle`.

**Design notes:**
- **`txBit` should be registered** so `io.tx` is glitch-free.
- The FSM does **not** own the shift register's bit value; it just emits
  `shiftReg` pulses and consumes `shiftRegBit` combinationally.
- Bit counter: `UInt(log2Up(dataBits + 1) bits)`, increments on `tick`,
  resets on state entry.
- **Off-by-one is the classic bug here** — verify in sim that exactly
  `dataBits` bit periods of data appear, not `dataBits-1` or `dataBits+1`.

**Sim (`src/sim/TxFsmSim.scala`):**
- Drive a fake tick (e.g. every 4 cycles, well below baud).
- Stub the shift register: a small mutable byte in the testbench that
  shifts when `shiftReg` pulses, exposing its LSB on `shiftRegBit`.
- Pulse `start` and watch the `txBit` sequence: should be
  `0, d0, d1, ..., d7, 1` (and `1`s for stop).
- Verify `busy` is high from `start` until back in Idle.
- Verify `loadReg` pulses exactly once at frame start.
- Verify `shiftReg` pulses the right number of times.
- Try back-to-back frames (start a second frame the cycle busy drops).

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

- [ ] **Parity bit support.** Compute `^data` (XOR-reduce) for even,
      invert for odd, send in the Parity state.
- [ ] **Configurable stop bits actually plumbed through the FSM.**
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
