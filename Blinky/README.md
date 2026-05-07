# Blinky

The "hello, world" of FPGA examples. Two variants ship in this folder:

  - **`Blinky`**          — bare counter, MSB drives the LED, **no reset
    pin** (uses iCE40 `BOOT` reset). This is what `make` builds by default.
  - **`BlinkyWithReset`** — same counter with an asynchronous, active-LOW
    reset wired to the user button.

If the LED on your iCEbreaker breathes after `make flash`, the entire
toolchain (sbt -> SpinalHDL -> yosys -> nextpnr -> icepack -> iceprog ->
board) is healthy. Use this as the very first sanity check on a new setup.

## What it teaches

  - Minimal `Component` + `Bundle` (no parameters).
  - `Reg(...)` with `init(...)` for cold-start state.
  - The two flavours of reset on iCE40:
    - `BOOT`  — cold-start `init(...)` only; no reset wire generated.
    - `ASYNC` — asynchronous assertion (matches the user button), with
      Spinal's BufferCC synchronising deassertion onto the clock.
  - The standard `ClockDomain` + `ClockingArea` idiom that every other
    example in this repo reuses.

## Layout

```
Blinky/
  README.md          this file
  Makefile           build / sim / flash
  build.sbt
  icebreaker.pcf       iCE40 pin constraints (default Blinky: clk + led)
  icebreaker-reset.pcf iCE40 pin constraints (BlinkyWithReset: + button)
  src/
    hw/              synthesizable (becomes Verilog/bitstream)
      Blinky.scala            counter MSB drives LED, BOOT reset
      BlinkyWithReset.scala   same, with explicit ASYNC active-LOW reset
    sim/             SpinalSim testbenches (never see silicon)
      BlinkySim.scala            run for >= 1 full period, count toggles
      BlinkyWithResetSim.scala   exercise the reset pin, confirm hold/release
```

The Scala package is `blinky` for all files regardless of folder.

## Quick start

```sh
make help        # list targets
make sim         # run BlinkySim and BlinkyWithResetSim
make sim-blinky  # just the BOOT-reset variant
make sim-reset   # just the explicit-reset variant
make waves       # open the most recent VCD in gtkwave
make verilog     # regenerate gen/Blinky.v
make             # full build -> gen/Blinky.bin (the BOOT-reset variant)
make flash       # program the iCEbreaker
```

To flash the reset variant instead, override `TOP` (the Makefile picks the
matching PCF automatically):

```sh
make TOP=BlinkyWithReset
make TOP=BlinkyWithReset flash
```

## Hardware

- **Board**: iCEbreaker (Lattice iCE40 UP5K, sg48 package).
- **Pins**:
  - `icebreaker.pcf` (default Blinky):
    - `io_clk` -> pin 35 (12 MHz)
    - `io_led` -> pin 11 (LED)
  - `icebreaker-reset.pcf` (BlinkyWithReset adds):
    - `io_reset` -> pin 10 (user button, active-low)

## Why two variants?

Most iCE40 designs simply don't need a reset wire — the FPGA cold-starts
registers from the bitstream's `init` values, and that is exactly what
`BOOT` reset means. The reset variant exists purely to demonstrate how
SpinalHDL's `ClockDomain` consumes an explicit reset pin; in real designs
prefer `BOOT` unless you have a concrete need to re-zero state at runtime.
