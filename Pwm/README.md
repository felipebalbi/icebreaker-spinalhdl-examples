# Pwm

A SpinalHDL example for the
[iCEbreaker](https://1bitsquared.com/products/icebreaker) that drives an LED
with a 12-bit PWM whose duty cycle is set externally — the duty bus is wired to
the iCEbreaker's PMOD pins, so brightness can be poked from another peripheral
(or, in a pinch, a few DIP switches).

The hardware itself is the textbook counter-comparator PWM in fewer than a
dozen lines of Scala. The point of this example is *not* the protocol — it's
the workflow:

  - one synthesisable component (`PwmTop`) under `src/hw/`
  - one `SpinalSim` testbench (`PwmSim`) under `src/sim/`
  - a Makefile that wires Spinal -> Verilog -> yosys -> nextpnr -> bitstream
    *and* runs the sim, all from `make`/`make sim`/`make flash`

Once you've internalised this loop the rest of the repo is variations on the
same theme.

## Block diagram

```
                12 MHz from pin 35
                       |
                       v
              +-----------------+
io.duty[12] ->|  counter < duty |--> io.pwm  -> LED on pin 11
              |  (free running) |
              +-----------------+
```

`counter` is an unconditional `Reg(UInt(width bits))` that wraps naturally at
`2^width`. `io.pwm` is high while `counter < io.duty` and low otherwise, so the
average duty cycle equals `duty / 2^width`.

At 12 MHz with `width = 12` the carrier is `12_000_000 / 4096 ≈ 2.93 kHz`,
well above the eye's flicker-fusion threshold so a connected LED looks like a
smooth analog brightness.

## Layout

```
Pwm/
  README.md          this file
  Makefile           build / sim / flash
  build.sbt
  icebreaker.pcf     iCE40 pin constraints
  src/
    hw/              synthesizable (becomes Verilog/bitstream)
      PwmTop.scala         counter-comparator PWM
    sim/             SpinalSim testbenches (never see silicon)
      PwmSim.scala         exact-duty assertions + ramp for waveform viewer
```

The Scala package is `pwm` for all files regardless of folder — Scala does
not require package = directory.

## Quick start

Tools needed: `sbt`, `yosys`, `nextpnr-ice40`, `icepack`, `iceprog` (plus a JDK
for sbt) and optionally `gtkwave` for `make waves`.

```sh
make help     # list targets
make sim      # run PwmSim (asserts duty count matches across multiple values)
make waves    # open the most recent VCD in gtkwave
make verilog  # regenerate gen/PwmTop.v
make          # full build -> gen/PwmTop.bin
make flash    # program the iCEbreaker
```

## Knobs

| Parameter | Where                | Effect                                                      |
|-----------|----------------------|-------------------------------------------------------------|
| `width`   | `PwmTop(width)` call | PWM resolution (steps) and carrier frequency = clk / 2^w.   |

`PwmTopVerilog.main` instantiates `PwmTop(12)` for the bitstream; the sim uses
`PwmTop(8)` so a full period fits in a quick simulation window.

## Hardware

- **Board**: iCEbreaker (Lattice iCE40 UP5K, sg48 package).
- **Pins** (from `icebreaker.pcf`):
  - `clk`        -> pin 35 (12 MHz)
  - `io_pwm`     -> pin 11 (LED)
  - `io_duty[*]` -> PMOD pins (see pcf for exact mapping)
  - `reset`      -> pin 10 (declared but unused; default `BOOT` reset)

The implicit clock-domain generates a top-level `clk` port that the pcf binds
to the 12 MHz oscillator on pin 35. There is no reset wire: with `BOOT` reset
the iCE40 cold-starts the counter to `init(0)`.

## Why this design is split this way

`PwmTop` does one job: turn a duty value into a PWM. There is no waveform
generator, no prescaler, no clock-domain glue — those concerns live in
`PwmFade/`, which composes this same `counter < duty` idiom with a phase
generator and a configurable shaper to produce a "breathing" LED.

Keep this leaf component as small and dumb as possible; everything else can be
built on top of it.
