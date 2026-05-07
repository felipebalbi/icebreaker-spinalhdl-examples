# ButtonDebouncer

A SpinalHDL example for the
[iCEbreaker](https://1bitsquared.com/products/icebreaker) that does what the
sibling `Button/` example deliberately doesn't: filter mechanical bounce out
of the user-button input so each press produces exactly one LED toggle.

Two debouncer styles are bundled and selected at compile time via a
`DebouncerConfig`:

  - **Integrator** (`IntegratorDebouncer`) — saturating up/down counter.
    Snappy, very robust against chattery contacts.
  - **Timer**     (`TimerDebouncer`)      — one-shot countdown after each
    transition. Conceptually simpler; fixed real-time window.

The point of carrying both is *composition*: once you have an abstract
`Debouncer` + a factory + a config enum, swapping implementations is a
one-line change in `ButtonTopVerilog.main`.

## Block diagram

```
                                 +----------------+
io.btn ---> [BufferCC] -.        |  Debouncer     |
                         '------>|  (Integrator   |---> stable
                                 |     or Timer)  |---> rising  -.
                                 +----------------+              |
                                                                 v
                                                          +-------------+
                                                          |   ledReg    |---> io.led
                                                          | toggle on   |
                                                          | rising      |
                                                          +-------------+
```

## Layout

```
ButtonDebouncer/
  README.md          this file
  Makefile           build / sim / flash
  build.sbt
  icebreaker.pcf     iCE40 pin constraints
  src/
    hw/              synthesizable (becomes Verilog/bitstream)
      DebounceIO.scala            shared port shape
      DebouncerConfig.scala       SpinalEnum kind + parameters
      Debouncer.scala             abstract base + factory
      IntegratorDebouncer.scala   saturating up/down counter
      TimerDebouncer.scala        one-shot countdown
      ButtonTop.scala             clock domain + debouncer + LED toggle
    sim/             SpinalSim testbenches (never see silicon)
      IntegratorDebouncerSim.scala  bouncy + clean stimulus, edge counts
      TimerDebouncerSim.scala       same contract, timer flavour
      ButtonTopSim.scala            end-to-end: 1 toggle per bouncy press
```

The Scala package is `button_debouncer` for all files regardless of folder.

## Quick start

```sh
make help            # list targets
make sim-integrator  # validate the integrator implementation
make sim-timer       # validate the timer implementation
make sim             # all sims (integrator + timer + top)
make verilog         # regenerate gen/ButtonTop.v
make                 # full build -> gen/ButtonTop.bin
make flash           # program the iCEbreaker
```

## Switching debouncers

Edit `cfg` in `src/hw/ButtonTop.scala` (object `ButtonTopVerilog`):

```scala
val cfg = DebouncerConfig(
  kind       = DebounceKind.Timer,   // Integrator | Timer
  clkFreqHz  = 12_000_000,
  debounceMs = 10,
  width      = 16
)
```

Then `make flash`. The integrator version "feels" snappier on a clean board;
the timer version is more forgiving of really chattery contacts.

## Knobs

| Parameter    | Where               | Used by      | Effect                                                      |
|--------------|---------------------|--------------|-------------------------------------------------------------|
| `kind`       | `DebouncerConfig`   | both         | Which `Debouncer` subclass to instantiate.                  |
| `width`      | `DebouncerConfig`   | Integrator   | Counter width. Larger = more bounce tolerance, slower commit. |
| `clkFreqHz`  | `DebouncerConfig`   | Timer        | System clock frequency in Hz; sizes the countdown.          |
| `debounceMs` | `DebouncerConfig`   | Timer        | Required steady-input window in milliseconds.               |

## Hardware

- **Board**: iCEbreaker (Lattice iCE40 UP5K, sg48 package).
- **Pins** (from `icebreaker.pcf`):
  - `io_clk` -> pin 35 (12 MHz)
  - `io_btn` -> pin 10 (user button)
  - `io_led` -> pin 11 (LED)

## Why the design is split this way

- **`DebounceIO` is shared.** Every concrete debouncer presents the same
  port shape (`raw` in, `stable`/`rising`/`falling` out) so consumers like
  `ButtonTop` are oblivious to which one they got.
- **`Debouncer` is the abstract base + factory.** The factory pattern (an
  `apply(cfg)` on the companion object) means new strategies can be added
  without touching call sites.
- **`IntegratorDebouncer` and `TimerDebouncer` are siblings, not layered.**
  Each is a complete debouncer in its own right; the example exists to
  contrast them.
- **`ButtonTop` only orchestrates clock domain + LED toggle.** It carries
  no debouncing logic of its own.

This is the same composition pattern as `Modulator` + `Shaper` in the sibling
`PwmFade/` project.
