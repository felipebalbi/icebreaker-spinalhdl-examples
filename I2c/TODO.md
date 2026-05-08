# I2c — TODO

Bottom-up bring-up plan for an I²C controller + target in SpinalHDL.
Same workflow as the Uart project: each block built in isolation,
sim'd, then composed into a wrapper, then a demo top, then real
silicon. Order isn't load-bearing — adjust as the design teaches us
something.

Each completed step gets a "What landed" entry so the design rationale
survives independently of the source.

---

## 🔲 Phase 0 — Foundations
- [ ] `I2cConfig` (clkFreqHz, busHz, addrBits=7, useClockStretching)
- [ ] `I2cIo` bundle: `TriState` SCL + `TriState` SDA
  (drive-low / release-high-Z; never drive high)
- [ ] `BusTiming` helper — derive `tHigh`, `tLow`, `tHd_sta`,
  `tSu_sto`, `tBuf` cycle counts from `clkFreqHz` / `busHz`.
  Should be a pure elaboration-time calculation (just like
  the UART's BaudGenerator increment).

## 🔲 Phase 1 — Controller (host)
- [ ] `I2cBitController` — drives one bit at a time given a
  command word (`{idle, start, stop, write_bit, read_bit,
  rep_start}`). Owns the bit-level timing and produces "bit
  done" / "arbitration lost" status. Sim it first, byte-level
  FSM later.
- [ ] `I2cByteController` — turns byte-level transactions
  (address+R/W, write byte, read byte+ACK) into bit-controller
  commands. Handles the ACK slot. Sim against a behavioural
  target model.
- [ ] `I2cController` — Stream-fed wrapper: producer pushes
  command words (start, write byte, read with ack/nack, stop),
  consumer reads back data bytes + status flags. Mirrors the
  shape of `UartTx`/`UartRx`.
- [ ] `I2cControllerSim` — sweep matrix: 7-bit write,
  7-bit read, write-then-read with repeated-START, ack-poll
  loop, NACK on address.
- [ ] Bring-up demo: read MCP9808 temperature register (or
  SSD1306 init sequence) on PMOD1A, dump bytes over the
  existing `UartTx` for visibility.

## 🔲 Phase 2 — Target (peripheral)
- [ ] `I2cTargetMonitor` — passive START/STOP/bit-edge detector.
  Pure observer first; lets us validate the rest of the target
  state without needing to drive anything. Sim against the
  bit-controller's bus traffic.
- [ ] `I2cTargetFsm` — drives SDA for ACK + read-data slots,
  optionally pulls SCL low for clock stretching. Sim against
  the controller from Phase 1.
- [ ] `I2cTarget` — Stream wrapper presenting "address matched,
  here's the byte the controller sent" / "controller wants a
  byte from us, supply it via Stream" handshakes.
- [ ] Loopback demo: `I2cController` ↔ `I2cTarget` on the same
  PMOD pair, with the target acting as a tiny memory the
  controller writes/reads. Verifies the whole stack on real
  silicon without depending on any external chip.

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
