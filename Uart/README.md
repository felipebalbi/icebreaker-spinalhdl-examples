# Uart

A from-scratch UART (transmit + receive) for the
[iCEbreaker](https://1bitsquared.com/products/icebreaker), written in
SpinalHDL. After `make flash` the iCEbreaker echoes every character
you type at it back to your terminal — full-duplex, end-to-end, on
real silicon.

The interesting part isn't the protocol — it's a shift register with
opinions. The interesting part is the **workflow** this example demonstrates:

  1. Spec the smallest sub-block (`BaudGenerator`).
  2. Build it, test it (`BaudGeneratorSim`), commit.
  3. Spec the next sub-block (`TxShiftReg`).
  4. Build, test (`TxShiftRegSim`), commit.
  5. Repeat for `TxFsm`.
  6. Compose them into `UartTx`.
  7. Wrap that with stimulus and a FIFO into `UartTxDemo` — first hardware bring-up.
  8. Repeat the same loop for the RX path: `RxSync`, `RxShiftReg`, `RxFsm`, `UartRx`.
  9. Compose RX + TX into `UartEchoDemo` — second hardware bring-up.

Once you've internalised that loop the rest of the repo is variations on
the theme.

> Status: TX, RX, and the bidirectional echo demo are all running on
> real hardware. The echo demo now sits on top of `UartController`,
> an APB3-fronted register-mapped wrapper with TX/RX FIFOs,
> runtime-tunable baud, sticky errors, and an auto-generated IP
> datasheet (`make docs`). Optional follow-ons (RX-only LED demo,
> dual-direction demo, counter-based BaudGenerator variant) live in
> `TODO.md`.

## Block diagram

```
                                     12 MHz from pin 35
                                            |
                                            v
                              +--------------------------+
                              |       UartEchoDemo       |
                              |                          |
                              |  +--------+              |
   io.rx  (pin 6)  -----------|->| UartRx |---+          |
   from host's USB-UART TX    |  +--------+   |          |
                              |               v          |
                              |          +---------+     |
                              |          | StreamFifo    |
                              |          +---------+     |
                              |               |          |
                              |               v          |
                              |          +--------+      |
   io.tx  (pin 9)  <----------|----------| UartTx |      |
   to host's USB-UART RX      |          +--------+      |
                              |                          |
                              |  Each half uses:         |
                              |    BaudGenerator (DDS)   |
                              |    TxShiftReg / RxShiftReg (PISO/SIPO)
                              |    TxFsm / RxFsm (control)
                              |    RxSync (RX only — 2-FF synchronizer)
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
  TODO.md            phase-by-phase status (TX, RX, echo demo all done)
  build.sbt
  icebreaker.pcf     iCE40 pin constraints
  src/
    hw/              synthesizable (becomes Verilog/bitstream)
      UartConfig.scala       compile-time params + ParityType enum
      BaudGenerator.scala    DDS-based bit-rate tick generator
      TxShiftReg.scala       parallel-in/serial-out shift register (LSB first)
      TxFsm.scala            idle->start->data->[parity]->stop->idle FSM
      UartTx.scala           the pure transmitter core (Stream in, line out)
      UartTxDemo.scala       clock domain + ROM + FIFO + UartTx, hello-world top
      RxSync.scala           2-FF metastability synchronizer for the rx pin
      RxShiftReg.scala       serial-in/parallel-out shift register (LSB first)
      RxFsm.scala            idle->verify->data->[parity]->stop->idle FSM
      UartRx.scala           the pure receiver core (line in, Stream out)
      UartEchoDemo.scala     clock domain + UartRx + FIFO + UartTx, echo top
    sim/             SpinalSim testbenches (never see silicon)
      BaudGeneratorSim.scala   tick cadence + DDS accuracy
      TxShiftRegSim.scala      load/shift/hold/load-priority semantics
      TxFsmSim.scala           full frame sequence with parity variants
      UartTxSim.scala          end-to-end byte-on-the-wire decoder
      RxSyncSim.scala          2-cycle delay + transition fidelity
      RxShiftRegSim.scala      LSB-first shift-in + clear-priority semantics
      RxFsmSim.scala           full RX frame: byte sweep + glitch / framing /
                               parity / overrun coverage
      UartRxSim.scala          black-box wrapper: line in, Stream out
```

The Scala package is `uart` for all files regardless of folder.

## Quick start

Tools needed: `sbt`, `yosys`, `nextpnr-ice40`, `icepack`, `iceprog` (plus a
JDK for sbt) and optionally `gtkwave` for `make waves`.

```sh
make help        # list every target with a description
make sim-baud    # validate the BaudGenerator
make sim-fsm     # full TX FSM frame sequence
make sim-rxfsm   # full RX FSM frame sequence
make sim-rxtop   # end-to-end UartRx wrapper sim
make sim-top     # end-to-end UartTx wrapper sim
make sim         # all of the above (plus the smaller block sims)
make waves       # open the most recent VCD in gtkwave
make verilog     # regenerate gen/UartEchoDemo.v
make             # full build -> gen/UartEchoDemo.bin
make flash       # program the iCEbreaker
```

After `make flash`, plug the iCEbreaker's micro-USB into your host and
attach a terminal to the resulting `/dev/ttyUSB1` (or COM port on
Windows) at **115200 8N1, no flow control**. With `picocom`:

```sh
picocom -b 115200 /dev/ttyUSB1
```

Press the user button to reset, then type any character — it'll come
straight back. That's the entire UART round-tripping bytes through the
FPGA.

> Want the original "broadcast `Hello, World\r\n` forever" demo
> instead? Edit the `Makefile` and change `TOP := UartEchoDemo` back
> to `TOP := UartTxDemo`.

## Knobs

Edit `cfg` (and optionally `fifoDepth` for `UartTxDemo`, or
`txFifoDepth` / `rxFifoDepth` in `UartConfig` for the
controller-based echo demo):

| Parameter     | Where        | Effect                                                                      |
|---------------|--------------|-----------------------------------------------------------------------------|
| `clkFreqHz`   | `UartConfig` | System clock frequency in Hz (12 MHz for iCEbreaker).                       |
| `baudRate`    | `UartConfig` | Line rate in bits/s. DDS gets ppm-level accuracy regardless of clock match. |
| `dataBits`    | `UartConfig` | 5..9 (8 is the universal default).                                          |
| `stopBits`    | `UartConfig` | 1 or 2.                                                                     |
| `parity`      | `UartConfig` | None / Even / Odd. Adds a parity slot to the frame when not None.           |
| `oversample`  | `UartConfig` | RX-only sample rate per bit (16 is industry standard).                      |
| `useCts`      | `UartConfig` | TX: expose a CTS pin and gate frame starts on it. Off in the echo demo.     |
| `useRts`      | `UartConfig` | RX: expose an RTS pin mirroring `payload.ready`. Off in the echo demo.      |
| `txFifoDepth` | `UartConfig` | Depth of the TX FIFO inside `UartController`. Independent from RX side.     |
| `rxFifoDepth` | `UartConfig` | Depth of the RX FIFO inside `UartController`. Independent from TX side.     |
| `message`     | `UartTxDemo` | ASCII string streamed in a loop. Use `\r\n` for terminal line endings.      |
| `fifoDepth`   | `UartTxDemo` | Bytes buffered between the message ROM and `UartTx`.                        |

## Hardware

- **Board**: iCEbreaker (Lattice iCE40 UP5K, sg48 package).
- **Pins** (from `icebreaker.pcf`):
  - `io_clk`   -> pin 35 (12 MHz)
  - `io_reset` -> pin 10 (user button, active-LOW)
  - `io_tx`    -> pin 9  (USB-UART RX, i.e. **into** the host)
  - `io_rx`    -> pin 6  (USB-UART TX, i.e. **from** the host; iCEbreaker FT2232 channel B)

## Why the design is split this way

- **`BaudGenerator` is pure timebase.** Knows nothing about frames or
  bytes. Produces one tick per period and that is all. The TX side
  parameterises it at `baudRate`; the RX side parameterises it at
  `baudRate × oversample` (via `cfg.copy(baudRate = ...)`).
- **`TxShiftReg` / `RxShiftReg` are dumb storage.** PISO and SIPO,
  both LSB first. Neither knows anything about start/stop/parity, idle
  line, or how many bits have been shifted. The collision priorities
  (load-wins-over-shift on TX, clear-wins-over-shift on RX) match
  industry coding guides and keep the FSM contracts simple.
- **`RxSync` is the only block that touches an async signal.**
  Two flops, init high. Centralising metastability handling in one
  place makes it easy to audit.
- **`TxFsm` / `RxFsm` are pure control.** Each sequences its half
  through `idle → start → data → [parity] → stop → idle`, one bit
  per tick (TX) or per `oversample` ticks (RX). RX adds a half-bit
  verify state for noise rejection and produces three side-band
  error flags.
- **`UartTx` / `UartRx` are the wiring.** Each composes its three
  sub-blocks behind a Stream interface (and an optional flow-control
  pin). No new logic beyond the handshake.
- **`UartTxDemo` / `UartEchoDemo` are applications.** Each owns the
  explicit `ClockDomain` with active-LOW reset (so the user button
  actually resets things), a `StreamFifo` for back-pressure, and the
  application-specific producer/consumer. `UartTxDemo` has a tiny
  message ROM; `UartEchoDemo` plumbs RX straight to TX.

This split is the same composition pattern the rest of the repo uses
(`Modulator` + `Shaper` in PwmFade, `Debouncer` + `IntegratorDebouncer`/
`TimerDebouncer` in ButtonDebouncer): a thin abstract base, concrete
strategies behind it, and an application-level wrapper that owns the
clock domain and pins.

## Status

TX, RX, and the bidirectional echo demo are all done and running on
real hardware. Open follow-ons (RX-only LED demo, dual-direction demo,
counter-based BaudGenerator variant, optional `UartConfig` rename) live
in `TODO.md`.
