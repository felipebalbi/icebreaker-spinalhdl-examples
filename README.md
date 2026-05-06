# icebreaker-spinalhdl-examples

A small, growing collection of
[SpinalHDL](https://spinalhdl.github.io/SpinalDoc-RTD/) projects targeting the
[iCEBreaker FPGA board](https://1bitsquared.com/products/icebreaker) (Lattice
iCE40 UP5K, sg48). Primarily a **learning notebook** as I work through
SpinalHDL; secondarily a handful of board-level demos that might be useful
starting points if you have an iCEBreaker on your desk.

Each subdirectory is a self-contained sbt project with its own `Makefile` so you
can build, simulate, or flash it independently.

## Examples

In rough order of complexity (later ones build on patterns introduced earlier):

| Project             | What it does                                                             | What it teaches                                                                                                                                                                                                                                                       |
|---------------------|--------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Blinky**          | Toggles an LED at ~0.18 Hz from a counter MSB.                           | Minimal `Component` + `Bundle`; `Reg` with `init`; `BOOT` reset on iCE40.                                                                                                                                                                                             |
| **Button**          | Toggles an LED on each (raw) button press.                               | Input pins; `BufferCC` for crossing the async button into clocked logic; `RegNext`; naive edge detection.                                                                                                                                                             |
| **ButtonDebouncer** | Same, but with proper debouncing.                                        | Abstract `Component` + companion `apply(cfg)` factory; `SpinalEnum`; reusable IO `Bundle`; two debouncer styles (integrator vs timer).                                                                                                                                |
| **Pwm**             | Counter-comparator PWM driving an LED.                                   | The PWM idiom; `SpinalSim` testbench (`forkStimulus`, `sleep`, waveform dump).                                                                                                                                                                                        |
| **PwmFade**         | "Breathing" LED with a pluggable duty modulator (linear / sine / gamma). | Composing previous patterns; `Mem` with `readAsync` + LUTs built at elaboration; two-timebase design (PWM carrier + slow modulation tick); perceptual brightness with gamma; project layout split into `src/hw/` and `src/sim/`. See `PwmFade/README.md` for details. |

## The board

- **iCEBreaker** — Lattice iCE40 UP5K, sg48 package.
- **Clock**: 12 MHz on pin 35 (used by every example).
- **LEDs / button**: see each project's `icebreaker.pcf` for the
  pin map it uses.

The iCE40 has no global asynchronous reset pin, so every example uses
SpinalHDL's `BOOT` reset kind. The FPGA cold-starts registers to the values
declared via `init(...)`; no reset wire is generated.

## Toolchain

You'll need (any recent version):

- **JDK** (8 or newer) and **sbt** — to run the SpinalHDL Scala
  generator and the simulators.
- **yosys**, **nextpnr-ice40**, **icestorm** (`icepack`, `iceprog`) —
  the open-source iCE40 synthesis/PnR/programming flow.
- (For waveform viewing) **GTKWave**.

On Debian/Ubuntu, all of these are packaged. On macOS, Homebrew has them. On
Windows, [oss-cad-suite](https://github.com/YosysHQ/oss-cad-suite-build) is the
easiest way to get a coherent toolchain.

## Building any example

Every project follows the same pattern:

```sh
cd <Project>
make           # SpinalHDL -> Verilog -> yosys -> nextpnr -> bitstream
make sim       # run the SpinalSim testbench(es), if the project has any
make flash     # program the iCEBreaker (board must be plugged in)
make clean
```

`PwmFade` also has `make help` listing per-sim targets — worth a peek for the
conventions I'm settling on going forward.

## Conventions

- **Package** matches the project name (`blinky`, `button`,
  `pwm`, `pwm_fade`, ...). Scala doesn't require this, but it keeps
  generated Verilog module prefixes predictable.
- **Generated Verilog** lives in `gen/` (or `rtl/` for Blinky); it is
  `.gitignore`d.
- **Simulation workspaces** (waves, compiled C++) live in
  `simWorkspace/`; also ignored.
- **Reset**: `BOOT` for everything (iCE40 has no global async reset).
- **Clock**: 12 MHz from pin 35 unless noted otherwise.

## Why SpinalHDL?

It's a hardware DSL embedded in Scala — the source code is plain Scala, so I get
the full language (case classes, traits, generics, pattern matching, ordinary
`math.*`) to parameterize designs and generate things like LUTs at elaboration
time. The output is plain synthesizable Verilog, so it slots straight into the
iCE40 open-source flow above. Compared with writing Verilog directly: strong
typing, explicit clock domains, no `wire`/`reg` distinction, and a much nicer
simulation story (`SpinalSim` over Verilator).

## Resources

- SpinalHDL docs: <https://spinalhdl.github.io/SpinalDoc-RTD/>
- iCEBreaker: <https://1bitsquared.com/products/icebreaker>
- Project Icestorm: <https://github.com/YosysHQ/icestorm>
- Yosys / nextpnr: <https://github.com/YosysHQ>
- oss-cad-suite (prebuilt toolchains): <https://github.com/YosysHQ/oss-cad-suite-build>

## Status

This is a personal learning repo — examples are added as I work through new
SpinalHDL features. Don't expect a stable API, CI, or
versioning. PRs/suggestions/corrections are welcome though, especially if you
spot something un-idiomatic.
