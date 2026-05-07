# Button

A SpinalHDL example for the
[iCEbreaker](https://1bitsquared.com/products/icebreaker) that toggles the
on-board LED on each press of the user button. Useful as the smallest possible
"real-world input + clocked-logic output" demo on the iCE40.

This example deliberately does **no** debouncing — the LED will flicker for a
few milliseconds on each press as the mechanical contacts settle, and may end
up in an unintended state. That is the point: it sets up the problem solved by
[ButtonDebouncer](../ButtonDebouncer/).

## What it teaches

  - **Async input synchronisation** with `BufferCC`. The user button is wired
    straight to an FPGA pin and is asynchronous to our 12 MHz clock; sampling
    it directly into clocked logic risks metastability. A two-flop BufferCC
    chain shrinks the metastable window to "won't happen in the lifetime of
    the universe".
  - **Boundary inversion for active-low inputs.** The iCEbreaker user button
    is electrically idle-high (PCB pull-up) and shorts to ground when
    pressed. We invert it once at the boundary (`val pressed = !io.btn`)
    so the rest of the design speaks in natural "1 = pressed" terms.
  - **Naive edge detection.** Compare the current sample of the synchronised
    `pressed` signal to its previous registered value: rising = high && !prev.
    Counts every transition, including bounces.
  - **`BOOT` reset on iCE40.** No global async-reset pin exists on the iCE40,
    so we use SpinalHDL's `BOOT` reset kind: registers cold-start to their
    `init(...)` values and no reset wire is generated.

## Block diagram

```
    io.btn (idle-HIGH, async, bouncy)
        |
        v
    !io.btn   (boundary inversion: 1 = pressed)
        |
        v
    [ BufferCC ] ---> pressedSync ---+
                                     |
        +-------- RegNext -----------+--> pressedPrev
        |                            |
        v                            v
    (rising = pressedSync && !pressedPrev)
        |
        v
    +-------------+
    |   ledReg    |---> io.led
    | toggle on   |
    | every rising|
    +-------------+
```

## Layout

```
Button/
  README.md          this file
  Makefile           build / sim / flash
  build.sbt
  icebreaker.pcf     iCE40 pin constraints
  src/
    hw/              synthesizable (becomes Verilog/bitstream)
      ButtonTop.scala       BufferCC + edge detect + toggle
    sim/             SpinalSim testbenches (never see silicon)
      ButtonTopSim.scala    drives btn, asserts edge-only behaviour
```

The Scala package is `button` for all files regardless of folder — Scala does
not require package = directory.

## Quick start

```sh
make help     # list targets
make sim      # run ButtonTopSim
make waves    # open the most recent VCD in gtkwave
make verilog  # regenerate gen/ButtonTop.v
make          # full build -> gen/ButtonTop.bin
make flash    # program the iCEbreaker
```

## Hardware

- **Board**: iCEbreaker (Lattice iCE40 UP5K, sg48 package).
- **Pins** (from `icebreaker.pcf`):
  - `io_clk` -> pin 35 (12 MHz)
  - `io_btn` -> pin 10 (user button)
  - `io_led` -> pin 11 (LED)

## Why no debouncing here

Adding a debouncer would obscure the two ideas this example exists to show:
clock-domain crossing on an async input, and rising-edge detection. The
debouncer story has its own home in `ButtonDebouncer/`, where two different
strategies (integrator and timer) are compared side-by-side using the same
top-level wrapper.
