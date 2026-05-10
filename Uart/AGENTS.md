# Uart — AGENTS.md

Uart-project-specific conventions. The repo-wide rules in the
top-level `AGENTS.md` still apply; this file adds Uart-only ones.

## Status pointer

The Uart project is **on real hardware**. Both `UartTxDemo`
(`Hello, World`) and `UartEchoDemo` (round-trip echo via picocom)
work on the iCEbreaker. Check `TODO.md`'s `## ✅ Done` block before
adding new work; most open items are Phase 3 stretch goals (no
commitment).

## DDS over divide-by-N for baud

`BaudGenerator` uses Direct Digital Synthesis (24-bit phase
accumulator), **not** a divide-by-N counter. Don't replace it.

Rationale (full version at the top of `BaudGenerator.scala`):
- Divide-by-N quantises the actual baud to `clkFreqHz / N`, which
  carries several percent error for awkward (clk, baud) pairs.
- DDS keeps fractional phase, so the long-term average rate
  matches the requested baud to ppm-level accuracy. Per-bit
  jitter is ±1 system clock — irrelevant for UART (the receiver
  samples mid-bit).

## Default frame: 8N1

`UartConfig` defaults to 8 data bits, no parity, 1 stop bit.
- `dataBits` 5..9 are *supported* but only 8 is exercised on
  hardware.
- `parity` is `ParityType.None` by default; even/odd are
  implemented but unused on hardware.
- `stopBits` is 1 or 2.

If you change defaults, update `UartConfig`'s require guards too.

## Build-time flow-control toggles

`useCts` (TX-side) and `useRts` (RX-side) are **build-time**
booleans — when `false`, the corresponding pin is omitted from the
top-level `Bundle` entirely so synthesis saves a pad. **Don't**
convert them to runtime enables; the asymmetry between "gates our
TX" (CTS) and "announces our RX readiness" (RTS) is documented in
`UartConfig.scala` and matters for downstream pin budgets.

## Synchronizer policy

`RxSync` (2-FF) is the **only** metastability filter on the RX
path. Don't add a third FF or a glitch filter without a documented
reason; the receiver oversamples 16× and the FSM majority-votes
mid-bit, which already absorbs single-cycle line glitches.

## TX reuse in the I2c project

When `I2c/Step 7` (controller bring-up demo) needs UART output for
byte dumps, **copy** the minimum TX path
(`BaudGenerator`, `TxShiftReg`, `TxFsm`, `UartTx`) into the `i2c`
package as `LocalUartTx*` (or similar). **Do not** introduce a
cross-project sbt dependency from `i2c` to `uart`.

Mark the copies with a header comment:
```scala
// Copied from the Uart project for self-contained I2c builds.
// If Uart's BaudGenerator changes meaningfully, sync forward.
```

## What's reasonable to extend

Everything in `Phase 3` of `Uart/TODO.md` is fair game (auto-baud,
9-bit / address-mark, BREAK, IRDA, UART-DMA hooks). Each one
should follow the bottom-up workflow in the root `AGENTS.md`.
