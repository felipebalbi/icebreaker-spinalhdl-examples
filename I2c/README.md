# I2c

A from-scratch I²C **controller** and **target** in SpinalHDL, targeting
the iCEbreaker. Both halves are built in the same project so they can
be simulated against each other before either ever touches a real bus.

Status: **Phase 0 complete** — `I2cConfig`, `I2cIo`, and `BusTiming`
have all landed with sims. Next up is `I2cBitController`. See
`TODO.md` for the full bring-up plan.

## What's in scope

- Standard-mode (100 kHz), Fast-mode (400 kHz) and Fast-mode-Plus
  (1 MHz) bit timing, parameterised via an `I2cConfig` record and
  selected with the `BusSpeed` enum.
- 7-bit addressing, selected via the `AddrMode` enum. (10-bit is a
  stretch goal — the addressing state in the FSM is the only piece
  that needs to know, and the enum is already plumbed through.)
- Open-drain SCL/SDA modelled with Spinal's `ReadableOpenDrain`
  primitive — `write := False` pulls the line low, `write := True`
  releases it. No `writeEnable`, no way to accidentally drive a
  hard `1`.
- Clock stretching — both as a controller (sample SCL, wait if
  the target holds it low) and as a target (pull SCL low to give
  the firmware time to react).
- START / repeated-START / STOP detection on the target side.
- ACK/NACK on every byte, with ack-poll-friendly behaviour.

## What's out of scope (for now)

- Multi-master arbitration. Single-controller is hard enough first.
- High-speed mode (3.4 MHz). Same reason.
- SMBus / PMBus quirks (host notify, packet error checking, etc.).
  The FSM should be flexible enough to add these later.

## Layout

```
I2c/
  README.md          this file
  TODO.md            phased bring-up plan + design notes per block
  Makefile
  build.sbt
  icebreaker.pcf     scl/sda on PMOD1A; pull-ups external
  src/
    hw/              synthesizable code
    sim/             SpinalSim testbenches
```

## Quickstart

Once there's something to build:

```sh
cd I2c
make           # bitstream
make sim       # run all sims
make flash     # program the iCEbreaker
```

`make help` lists every target.

## Hardware notes

- I²C is open-drain. We model each line with Spinal's
  `ReadableOpenDrain[Bool]`: `write := False` pulls the line low,
  `write := True` releases it (high-Z; the external pull-up wins).
  External 4.7 kΩ pull-ups to 3.3 V on the PMOD do the rest.
- PMOD1A pinout used here (see `icebreaker.pcf`):
  - PMOD1A.1 (FPGA pin 4) → SCL
  - PMOD1A.2 (FPGA pin 2) → SDA
- For first bring-up, an SSD1306 OLED or MCP9808 temperature sensor
  on the PMOD is a good controller-side target. A logic analyser
  on SCL/SDA is invaluable.
