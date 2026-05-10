# I2c — AGENTS.md

I2c-project-specific conventions. The repo-wide rules in the
top-level `AGENTS.md` still apply; this file adds I2c-only ones.

## Status pointer

The current bring-up status lives in `TODO.md`:
- Check the `## ✅ Done` checklist near the top.
- Skim the highest-numbered `### 🔲 Step N` block before starting
  new work — the hints there are the design contract for the next
  step (Goal, Suggested IO, Design notes, Sim hints, Makefile).

Step numbering is **sequential across phases** starting at Step 1
(`I2cConfig`), not reset per phase.

## Open-drain primitive: `ReadableOpenDrain`, not `TriState`

Every I²C wire (`scl`, `sda`) uses
`spinal.lib.io.ReadableOpenDrain[Bool]`. **Do not** replace with
`TriState`.

Rationale:
- `ReadableOpenDrain` has only `(write, read)` — no `writeEnable`
  to forget, and you cannot accidentally drive a hard `1` because
  there is no second transistor to enable.
- Maps cleanly to an actual open-drain pad at synthesis (iCE40
  `SB_IO` open-drain mode).
- Two wires per line instead of three.

Polarity is **electrical, not logical**:
- `write := False` turns the open-drain pull-down NMOS on → pin
  tied to GND → **bus low**.
- `write := True` turns the NMOS off → pin floats → external
  pull-up wins → **bus high**.

This is why `I2cIo.releaseAll()` writes `True`, not `False`.

## `I2cIo` is the bus port

Every block that touches the bus exposes one of:
```scala
val bus = master(I2cIo())   // controller side
val bus = slave(I2cIo())    // target / observer side
```

The `IMasterSlave` mixin makes `controller.io.bus <> target.io.bus`
connect in one line — Spinal flips every nested `ReadableOpenDrain`
direction. Don't manually flip fields.

## Sim glue: `I2cIoBus`

`I2cIoBus` (in `src/sim/I2cIoSim.scala`) is the reusable wired-AND
helper for any sim that needs two `I2cIo` participants on the same
bus. Wiring is `bus = a.write & b.write` for each line — anyone
pulling low wins, otherwise the (modelled) pull-up holds high.

When you need a third participant later (multi-master tests),
extend `I2cIoBus` rather than spinning up a new helper.

## `I2cConfig` is the by-value compile-time record

`I2cConfig(clkFreqHz, busSpeed, addrMode, useClockStretching)` is
passed by value into every sub-block. It carries:
- `BusSpeed` enum: `Standard` (100 kHz), `Fast` (400 kHz),
  `FastPlus` (1 MHz). `busFreqHz` is derived from the enum —
  single source of truth.
- `AddrMode` enum: `SevenBits`, `TenBits` (the latter plumbed
  through but reserved for a Phase 3 stretch goal).
- `quarterPeriodCycles` derived val: I²C bit-level events fall
  on quarter-period boundaries, so this is the right unit for the
  `BusTiming` helper to consume.

The forthcoming `BusTiming` helper (Step 3) **must** consume
`quarterPeriodCycles` rather than re-derive from `clkFreqHz` /
`busFreqHz`, otherwise the rounding rule has two homes.

## Step closeout convention

When closing a step:
1. Tick its checkbox in `## ✅ Done` (or add the line if absent).
2. Convert its `### 🔲 Step N` hint block into `### ✅ Step N`
   with a "What landed" body. Include:
   - **Files** changed / created.
   - **Divergence from the hint**, with rationale (the I2c project
     has already taken two such divergences — `BusSpeed` /
     `AddrMode` enums vs. raw fields, and `ReadableOpenDrain` vs.
     `TriState`).
   - **Sim** notes — companion file path and what it covers.
   - **Makefile** — the new `sim-<name>` target name.
3. Bump `README.md`'s status line if visible state changed.

## Pin assignments

See `icebreaker.pcf`:
- PMOD1A pin 1 (FPGA pin 4) → SCL
- PMOD1A pin 2 (FPGA pin 2) → SDA
- External 4.7 kΩ pull-ups to 3.3 V are required (not on-die).

## Hardware bring-up gating

Bring-up demos (`I2cControllerDemo` once Step 7 lands,
`I2cLoopbackDemo` for Step 11) are the gate for declaring a phase
done. Don't tick a "phase complete" box on simulation alone — the
Uart project's precedent is "🎉 hardware bring-up" entries in
`TODO.md`, and the I2c project follows the same rule.
