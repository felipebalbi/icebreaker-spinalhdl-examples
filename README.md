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

**Blinky**
: Toggles an LED at ~0.18 Hz from a counter MSB. Teaches a minimal
  `Component` + `Bundle`; `Reg` with `init`; `BOOT` reset on iCE40.

**Button**
: Toggles an LED on each (raw) button press. Teaches input pins;
  `BufferCC` for crossing the async button into clocked logic;
  `RegNext`; naive edge detection.

**ButtonDebouncer**
: Same as Button, but with proper debouncing. Teaches abstract
  `Component` + companion `apply(cfg)` factory; `SpinalEnum`; reusable
  IO `Bundle`; two debouncer styles (integrator vs timer).

**Pwm**
: Counter-comparator PWM driving an LED, duty cycle set from external
  PMOD pins. Teaches the PWM idiom; `SpinalSim` testbench
  (`forkStimulus`, `sleep`, exact-duty assertions, waveform dump).

**PwmFade**
: "Breathing" LED with a pluggable duty modulator (linear / sine /
  gamma). Teaches composing previous patterns; `Mem` with `readAsync` +
  LUTs built at elaboration; two-timebase design (PWM carrier + slow
  modulation tick); perceptual brightness with gamma; project layout
  split into `src/hw/` and `src/sim/`. See `PwmFade/README.md` for
  details.

**Uart**
: A from-scratch full UART (TX + RX, working end-to-end on real
  hardware) that talks to your terminal over the iCEBreaker's
  USB-serial bridge. Walks through designing each sub-block in
  isolation — `BaudGenerator`, `TxShiftReg` / `RxShiftReg`,
  `TxFsm` / `RxFsm`, the `RxSync` 2-FF synchronizer, a
  parameterizable `UartConfig` — then composes them into `UartTx`
  and `UartRx` wrappers, then assembles those into `UartEchoDemo`:
  type a character in `picocom`, watch it come straight back. The
  interesting part isn't the protocol (it's a shift register with
  opinions); it's the workflow: spec → sub-block → simulation →
  composition → bitstream → blinking cursor on real hardware,
  repeated for both directions.

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

Every project has `make help` listing per-sim and per-stage targets, plus a
`make waves` shortcut that opens the most recent VCD in GTKWave.

## Conventions

Every project follows the same shape:

```
<Project>/
  README.md          per-project overview (block diagram, knobs, pins, ...)
  Makefile           Spinal -> Verilog -> bitstream + sim targets + help
  build.sbt
  icebreaker.pcf     iCE40 pin constraints
  src/
    hw/              synthesizable code (becomes Verilog/bitstream)
    sim/             SpinalSim testbenches (never see silicon)
```

- **Package** matches the project directory in lower-snake-case
  (`blinky`, `button`, `button_debouncer`, `pwm`, `pwm_fade`, `uart`).
  Scala doesn't require package = directory; this is a convention to
  keep generated Verilog module prefixes predictable.
- **Generated Verilog** lives in `gen/`; it is `.gitignore`d.
- **Simulation workspaces** (waves, compiled C++) live in
  `simWorkspace/`; also ignored.
- **Makefiles** have a self-documenting `help` target — every public
  target carries a `## description` comment that `make help` parses.
  Common targets across all projects: `all`, `verilog`, `sim`,
  `sim-<block>`, `waves`, `flash`, `clean`, `distclean`, `help`.
- **Sim deps:** Verilog regeneration depends only on `src/hw/`, so
  editing a testbench under `src/sim/` does NOT trigger a full
  resynthesis.
- **Reset**: `BOOT` by default (iCE40 has no global async-reset pin).
  A few examples (`Blinky`'s reset variant, `Uart`) wire an explicit
  reset for teaching purposes.
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
