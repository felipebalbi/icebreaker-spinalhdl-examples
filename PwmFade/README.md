# PwmFade

A SpinalHDL example for the
[iCEBreaker](https://1bitsquared.com/products/icebreaker) that drives an LED
with a smoothly-changing PWM duty cycle ("breathing" effect). The duty source is
**pluggable**: pick a linear ramp, a sine, or a perceptually-corrected gamma
curve at compile time.

The project doubles as a study case for several SpinalHDL idioms: `Reg`/`init`,
`Bundle`, `Component`, abstract `Component` + factory, `SpinalEnum`, `Mem` with
`readAsync` and an elaboration-time-built LUT, `ClockDomain` with `BOOT` reset,
and splitting hardware code from simulation testbenches.

## Block diagram

```
                       12 MHz from pin 35
                              |
                              v
            +-----------------+-----------------+
            |                                   |
            v                                   v
     +-------------+                     +--------------+
     |  prescaler  |  tick (1/32768)    |   PwmCore    |
     |  (15 bits)  |-------+--------->  |  counter+cmp |
     +-------------+       |            +--------------+
                           |                   ^
                           v                   |
                     +-----+-----+             |
                     |  Modulator|---- duty ---+
                     |           |
                     | PhaseGen  |  triangle phase
                     |    +      |
                     | Shaper    |  curve: id / sin / gamma
                     +-----------+

                                              v
                                       io.pwm -> LED (pin 11)
```

Two timebases derived from one physical clock:

| Timebase           | Source             | Rate (12 MHz, defaults)            |
|--------------------|--------------------|------------------------------------|
| PWM carrier (fast) | `PwmCore` counter  | ~2.93 kHz (period 4096 cycles)     |
| Duty modulation    | prescaler `tick`   | ~366 Hz pulse (every 32768 cycles) |
| Full breath        | derived from above | ~2.24 s (`step=10`, `width=12`)    |

The slow modulation rate matters: if duty changed every clock cycle, the PWM
comparator would never see a stable value for a full period and the output would
average out. See `hw/PwmFadeTop.scala` for the math.

## Layout

```
PwmFade/
  README.md          this file
  Makefile           build / sim / flash
  build.sbt
  icebreaker.pcf     iCE40 pin constraints
  src/
    hw/              synthesizable (becomes Verilog/bitstream)
      PwmCore.scala            counter-comparator PWM
      PhaseGen.scala           tick-gated triangle counter
      Shaper.scala             abstract Shaper + factory
      IdentityShaper.scala     duty := phase
      SineShaper.scala         sine LUT (Mem)
      GammaShaper.scala        perceptual brightness LUT (Mem)
      Modulator.scala          PhaseGen + Shaper composite
      ModulatorConfig.scala    ShaperKind enum + params
      PwmFadeTop.scala         clock domain + prescaler + main()
    sim/             SpinalSim testbenches (never see silicon)
      PhaseGenSim.scala        triangle reaches both endpoints
      ShaperSim.scala          LUT contents match Scala reference
      PwmFadeSim.scala         end-to-end smoke + waveforms
```

The Scala package is `pwm_fade` for *all* files regardless of folder — Scala
does not require package = directory.

## Quick start

Tools needed: `sbt`, `yosys`, `nextpnr-ice40`, `icepack`, `iceprog` (plus a JDK
for sbt).

```sh
make help           # list targets
make sim-shaper     # validate the LUTs
make sim            # run all sims (phase + shaper + top)
make verilog        # regenerate gen/PwmFadeTop.v
make                # full build -> gen/PwmFadeTop.bin
make flash          # program the iCEBreaker
```

Open `simWorkspace/.../*.vcd` in GTKWave to inspect the breathing
waveform produced by `sim-top`.

## Swapping waveforms

Edit `cfg` in `src/hw/PwmFadeTop.scala` (object `PwmFadeTopVerilog`):

```scala
val cfg = ModulatorConfig(
  kind  = ShaperKind.Gamma,   // Identity | Sine | Gamma
  width = 12,
  step  = 10,
  gamma = 2.2
)
```

Then `make flash`. Try the three in order — the difference between `Identity`
(linear) and `Gamma` (perceptual) is striking by eye.

## Knobs

| Parameter        | Where                 | Effect                                                                              |
|------------------|-----------------------|-------------------------------------------------------------------------------------|
| `width`          | `ModulatorConfig`     | PWM resolution (bits) and LUT depth. Bigger = smoother + more BRAM.                 |
| `step`           | `ModulatorConfig`     | Phase units per tick. Bigger = faster (coarser) breath.                             |
| `gamma`          | `ModulatorConfig`     | Curvature for `GammaShaper`. 1.0 = linear, 2.2 = sRGB-ish, 2.8-3.0 = more dramatic. |
| `prescalerWidth` | `PwmFadeTop` ctor arg | Doubles the breath period for each +1.                                              |
| `kind`           | `ModulatorConfig`     | Which `Shaper` subclass to instantiate.                                             |

## Adding a new waveform

1. Create `src/hw/MyShaper.scala`:
   ```scala
   case class MyShaper(w: Int) extends Shaper(w) {
     io.duty := /* your function of io.phase */
   }
   ```
2. Add `MyShape` to `ShaperKind` in `ModulatorConfig.scala`.
3. Add the case to `Shaper.apply` in `Shaper.scala`.
4. Add a test in `ShaperSim.scala` comparing to a Scala reference.

That's it — `Modulator` and `PwmFadeTop` need no changes.

## Hardware

- **Board**: iCEBreaker (Lattice iCE40 UP5K, sg48 package).
- **Pins** (from `icebreaker.pcf`):
  - `io_clk` -> pin 35 (12 MHz)
  - `io_pwm` -> pin 11 (LED)
  - `reset`  -> pin 10 (declared but unused; we use `BOOT` reset)

## Why the design is split this way

- **`PwmCore` is unconditional and dumb.** It always counts; it has
  one job: turn a duty value into a PWM. Easy to reason about, easy
  to reuse.
- **`PhaseGen` is stateful but waveform-agnostic.** It only knows
  about a triangle ramp; it does not know what the resulting numbers
  *mean*.
- **`Shaper` is stateless and pure.** Each subclass is a tiny lookup
  function. Adding a new one cannot break anything else.
- **`Modulator` is a thin composite.** It hides the `PhaseGen`+`Shaper`
  split from the top, so `PwmFadeTop` doesn't grow when waveforms are
  added.
- **`PwmFadeTop` only orchestrates time.** Clock domain, prescaler,
  carrier — nothing about *what* the duty looks like.

This is the same composition pattern as `ButtonDebouncer` in the sibling project
(`Debouncer` + `DebouncerConfig` + factory).
