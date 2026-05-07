# Uart

A from-scratch UART transmitter for the
[iCEbreaker](https://1bitsquared.com/products/icebreaker), written in
SpinalHDL. After `make flash` the iCEbreaker will cheerfully spam
`"Hello, World\r\n"` at your terminal over the on-board USB-UART bridge,
forever, at 115200 8N1.

The interesting part isn't the protocol — it's a shift register with
opinions. The interesting part is the **workflow** this example demonstrates:

  1. Spec the smallest sub-block (`BaudGenerator`).
  2. Build it, test it (`BaudGeneratorSim`), commit.
  3. Spec the next sub-block (`TxShiftReg`).
  4. Build, test (`TxShiftRegSim`), commit.
  5. Repeat for `TxFsm`.
  6. Compose them into `UartTx`.
  7. Wrap that with stimulus and a FIFO into `UartTxDemo`.
  8. Generate Verilog → bitstream → blinking cursor on real hardware.

Once you've internalised that loop the rest of the repo is variations on
the theme.

> Status: TX is done and running on real hardware. RX + bidirectional
> demos are in progress; see `TODO.md` for the open work.

## Block diagram

```
                                     12 MHz from pin 35
                                            |
                                            v
                              +--------------------------+
                              |        UartTxDemo        |
                              |                          |
                              |  +-----+   +----------+  |
   message ROM  ----  byte ---->| FIFO|-->|  UartTx   |--|--> io.tx (pin 9)
   ("Hello, World\r\n")         |     |   |  core     |  |
                              |  +-----+   +----------+  |
                              |               | uses     |
                              |               v          |
                              |   BaudGenerator (DDS)    |
                              |   TxShiftReg  (PISO)     |
                              |   TxFsm       (control)  |
                              |                          |
                              +--------------------------+
                                            ^
                                            |
                                       io.reset (pin 10)
                                       active-LOW user button
```

## Layout

```
Uart/
  README.md          this file
  Makefile           build / sim / flash
  TODO.md            phase-by-phase status (TX done, RX in progress)
  build.sbt
  icebreaker.pcf     iCE40 pin constraints
  src/
    hw/              synthesizable (becomes Verilog/bitstream)
      UartConfig.scala       compile-time params + ParityType enum
      BaudGenerator.scala    DDS-based bit-rate tick generator
      TxShiftReg.scala       parallel-in/serial-out shift register (LSB first)
      TxFsm.scala            idle->start->data->[parity]->stop->idle FSM
      UartTx.scala           the pure transmitter core (Stream in, line out)
      UartTxDemo.scala       clock domain + ROM + FIFO + UartTx, top-level
    sim/             SpinalSim testbenches (never see silicon)
      BaudGeneratorSim.scala   tick cadence + DDS accuracy
      TxShiftRegSim.scala      load/shift/hold/load-priority semantics
      TxFsmSim.scala           full frame sequence with parity variants
      UartTxSim.scala          end-to-end byte-on-the-wire decoder
```

The Scala package is `uart` for all files regardless of folder.

## Quick start

Tools needed: `sbt`, `yosys`, `nextpnr-ice40`, `icepack`, `iceprog` (plus a
JDK for sbt) and optionally `gtkwave` for `make waves`.

```sh
make help        # list every target with a description
make sim-baud    # validate the BaudGenerator
make sim-shiftreg
make sim-fsm
make sim-top     # end-to-end UartTx sim (decodes "Hello, World" bytes)
make sim         # all of the above
make waves       # open the most recent VCD in gtkwave
make verilog     # regenerate gen/UartTxDemo.v
make             # full build -> gen/UartTxDemo.bin
make flash       # program the iCEbreaker
```

After `make flash`, plug the iCEbreaker's micro-USB into your host and
attach a terminal to the resulting `/dev/ttyUSB1` (or COM port on
Windows) at **115200 8N1, no flow control**. With `screen`:

```sh
screen /dev/ttyUSB1 115200
```

Press the user button to reset; release it and you should see
`Hello, World\r\nHello, World\r\n...` stream past.

## Knobs

Edit `cfg` (and optionally `message`/`fifoDepth`) in `UartTxDemo.scala`:

| Parameter   | Where           | Effect                                                                      |
|-------------|-----------------|-----------------------------------------------------------------------------|
| `clkFreqHz` | `UartTxConfig`  | System clock frequency in Hz (12 MHz for iCEbreaker).                       |
| `baudRate`  | `UartTxConfig`  | Line rate in bits/s. DDS gets ppm-level accuracy regardless of clock match. |
| `dataBits`  | `UartTxConfig`  | 5..9 (8 is the universal default).                                          |
| `stopBits`  | `UartTxConfig`  | 1 or 2.                                                                     |
| `parity`    | `UartTxConfig`  | None / Even / Odd. Adds a parity slot to the frame when not None.           |
| `useCts`    | `UartTxConfig`  | Whether to expose a CTS pin and gate frame starts on it.                    |
| `message`   | `UartTxDemo`    | ASCII string streamed in a loop. Use `\r\n` for terminal line endings.      |
| `fifoDepth` | `UartTxDemo`    | Bytes the FIFO buffers between the ROM producer and `UartTx`.               |

## Hardware

- **Board**: iCEbreaker (Lattice iCE40 UP5K, sg48 package).
- **Pins** (from `icebreaker.pcf`):
  - `io_clk`   -> pin 35 (12 MHz)
  - `io_reset` -> pin 10 (user button, active-LOW)
  - `io_tx`    -> pin 9  (USB-UART RX, i.e. **into** the host)

## Why the design is split this way

- **`BaudGenerator` is pure timebase.** Knows nothing about frames or
  bytes. Produces one tick per bit period and that is all.
- **`TxShiftReg` is dumb storage.** Parallel-in/serial-out, LSB first.
  Knows nothing about start/stop/parity, idle line, or how many bits
  have been shifted. Load wins over shift, deliberately, so a stray
  shift pulse never corrupts an incoming byte.
- **`TxFsm` is pure control.** Sequences the line through idle → start
  → data → [parity] → stop → idle, one bit per tick. Produces the
  start/stop/parity bits inline; pulls data bits from `TxShiftReg`'s
  combinational `bit` output.
- **`UartTx` is the wiring.** Composes the three above with a Stream
  byte input and an optional CTS gate. No new logic of its own beyond
  the handshake.
- **`UartTxDemo` is the application.** Owns the explicit `ClockDomain`
  with active-LOW reset (so the user button actually resets things), a
  `StreamFifo` for bursty producers, and a tiny ROM that loops a fixed
  message. Substitute your own producer here and `UartTx` won't care.

This split is the same composition pattern the rest of the repo uses
(`Modulator` + `Shaper` in PwmFade, `Debouncer` + `IntegratorDebouncer`/
`TimerDebouncer` in ButtonDebouncer): a thin abstract base, concrete
strategies behind it, and an application-level wrapper that owns the
clock domain and pins.

## Status

The TX path is done and running on real hardware. UART RX is the next
build, planned bottom-up the same way; see `TODO.md` for the in-flight
phase plan.
