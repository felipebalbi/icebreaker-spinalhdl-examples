# icebreaker-spinalhdl-examples — TODO

Repo-wide roadmap. Each project gets its own per-project `TODO.md` with
the bottom-up bring-up plan, design notes, and "what landed" entries —
this file is just the index.

Order of additions is roughly increasing complexity / building on
patterns introduced earlier. Order isn't a hard commitment; whichever
one looks most fun next wins.

A few items intentionally compose with each other. Notable chains:
**Spi** (SD card host) → **AtariSio** (1050 disk drive emulation),
giving a real Atari 130XE the ability to boot DOS off the FPGA;
**VexRiscvSoC** → **VexRiscvSoCExpanded** with SPI-flash XIP and
SPRAM, after which most peripherals (`Spi`, `I2c`, `Ps2`, `Vga`, …)
can optionally appear as CSR-mapped blocks; **Vga** + **Ps2** +
**VexRiscvSoC** together give us **Chip8Emu** as a capstone. Each
entry's "depends on" note calls out the chain it lives in.

---

## ✅ Done

- [x] **Blinky** — counter-MSB LED toggle. First `Component`, first
  `BOOT` reset, "does the toolchain work" smoke test.
- [x] **Button** — async input + `BufferCC` + naive edge detect.
  First time touching FPGA inputs.
- [x] **ButtonDebouncer** — abstract `Component` + `apply(cfg)`
  factory; `SpinalEnum`; integrator vs timer debouncer styles.
- [x] **Pwm** — counter-comparator PWM driven from PMOD inputs.
  First `SpinalSim` testbench (`forkStimulus`, exact-duty asserts).
- [x] **PwmFade** — pluggable duty modulator (linear / sine / gamma
  LUTs built at elaboration); two-timebase design (PWM carrier +
  modulation tick); `src/hw/` vs `src/sim/` split established.
- [x] **Uart** — full UART (TX + RX), parameterizable
  `UartConfig`, framing/parity/overrun error reporting,
  `UartEchoDemo` round-tripping characters in `picocom` on real
  iCEbreaker silicon. Establishes the bottom-up workflow:
  sub-block → sim → composition → bitstream → blinking cursor.
  See `Uart/TODO.md` for the per-step history.

---

## 🔲 Planned

### Serial buses
- [ ] **Spi** — controller + target. Easiest of the bus protocols
  (no open-drain, no ACKs, no clock stretching). Useful warm-up
  for tri-state and multi-master patterns coming in I²C/I³C.
  Bring-up target: PMOD flash or SD card breakout; sim the two
  halves against each other before touching hardware.
- [ ] **I2c** — controller + target. First open-drain (`TriState`),
  first clock-stretching, first real bus protocol (START/STOP,
  ACK/NACK, repeated-START). Cheap PMOD peripherals on iCEbreaker:
  SSD1306 OLED, MCP9808 temp sensor, 24LC EEPROMs. Sim the two
  halves against each other.
- [ ] **I3c** — controller + target. Builds on I²C: open-drain
  legacy mode + push-pull HDR, dynamic addressing, in-band
  interrupts. The most complex of the serial bus trio.
  Practical bring-up will be sim-only unless an I³C-capable
  PMOD shows up.
- [ ] **OneWire** — Dallas 1-Wire master. Single bidirectional
  open-drain line, time-slot encoded, weak-pullup parasitic
  power. Good follow-up to I²C: similar tri-state I/O, very
  different timing model. DS18B20 temp sensor is the canonical
  bring-up target.

### Legacy I/O
- [ ] **Ps2** — PS/2 keyboard receiver. Async serial cousin of
  UART (start bit + 8 data + odd parity + stop, but with a
  *device-driven clock*). Reuses RxSync / RxShiftReg patterns.
  Bring-up target: any cheap USB→PS/2 keyboard or a real PS/2
  one if you have it in a box somewhere.
- [ ] **AtariSio** — Atari 8-bit Serial I/O bus peripheral,
  emulating a 1050 disk drive so a real Atari 130XE can boot
  DOS off the FPGA. SIO is async serial (~19,200 baud, but the
  Atari can negotiate higher) on the bidirectional DATA IN/OUT
  pair, plus COMMAND / PROCEED / INTERRUPT side-band lines and
  the `MOTOR` / `READY` controls. Reuses everything from the
  `Uart` project for the wire layer; the new pieces are the
  SIO command frame parser (device ID + command + aux1 + aux2
  + checksum), the per-command response (ACK/NACK/COMPLETE/ERR
  bytes with timing windows), and a sector-cache state machine
  in front of the SD card.
  Composes with **Spi** as the SD card backend: parse `.ATR`
  disk images off SD, present sector reads/writes back to the
  Atari. Effectively an FPGA SIO2SD / SDrive-MAX. Needs a
  level shifter (Atari is 5 V TTL, iCE40 is 3.3 V) and a real
  Atari SIO cable wired to a PMOD breakout.
  This one is the most ambitious thing on the list and depends
  on **Spi** landing first.

### Video
- [ ] **Vga** — text-mode VGA driver. First time hitting hard
  pixel-clock timing; framebuffer in BRAM; character ROM built
  at elaboration. Big jump in scope from everything above.
  Bring-up target: 12-bit VGA PMOD.
- [ ] **Dvi** — TMDS-encoded DVI output. Builds on VGA — same
  timing structure, but you encode pixels through TMDS and
  serialise at 10× pixel clock (challenging on UP5K — may need
  a low-resolution mode like 640×480 or even custom). Bring-up
  target: DVI PMOD. **Note:** explicitly DVI, not HDMI — HDMI
  requires a license/HDCP for legal sale; DVI is the
  unencumbered, electrically-compatible subset.

### Compute / SoC
- [ ] **VexRiscvSoC** — minimal RV32I tiny SoC: VexRiscV core
  (no MMU, no FPU, no caches) + on-chip BRAM for code/data +
  the existing `Uart` as console + GPIO for LEDs/buttons. Boot
  a "hello world" + LED blink written in C, compiled with
  riscv-none-elf-gcc, BRAM-init at elaboration time. The new
  patterns: a tiny bus fabric (SpinalHDL's plugin bus or a
  hand-rolled Wishbone subset), a CSR-mapped peripheral
  contract, an interrupt controller, and a software flow
  (linker script, startup code, `.bin` → BRAM init array).
  Most ambitious "foundation" piece on the list — once it
  lands, every later peripheral has the option of being a
  CSR-mapped block instead of a standalone demo.
  iCE40 UP5K resource budget for context: 5,280 LUT4s, 128
  kbit BRAM, 128 KB SPRAM, 8 DSP MAC blocks, 1 PLL. A
  minimal RV32I VexRiscv comfortably fits; a Linux-class
  config emphatically does not.
- [ ] **VexRiscvSoCExpanded** — adds XIP boot from the
  iCEbreaker's on-board 16 MB SPI flash (so code lives in
  flash, not BRAM init), the UP5K's 128 KB SPRAM as main RAM,
  a CSR-mapped timer + interrupt controller. Composes with
  **Spi** for the XIP path and with whatever displays / inputs
  exist by then. After this, "add a peripheral to the SoC"
  becomes a small contract change rather than a new top.

### Audio
- [ ] **PdmAudio** — 1-bit PDM (sigma-delta) DAC. Drives a
  single pin into a passive RC low-pass filter into a
  headphone jack — no external DAC chip needed, the simplest
  possible way to make audible noise from an iCE40. Establishes
  the modulator pattern (first-order, then maybe second-order)
  that any later audio / DSP work builds on.
- [ ] **I2sAudio** — proper I²S serial audio output to a PMOD
  audio DAC (e.g., PMOD I2S2 with the CS4344). Builds on
  `PdmAudio` for source material; the new piece is the I²S
  frame format (BCLK, LRCK, SD, MSB-first, sample-aligned).
- [ ] **ChiptuneSynth** — multi-voice square / triangle / noise
  synth with envelope generators (SID-flavoured). Composes with
  `PdmAudio` or `I2sAudio` to actually make sound; uses the
  UP5K hard DSP MACs for envelope multiplications.

### DSP / math
- [ ] **Cordic** — generator-style CORDIC for sin / cos /
  atan2. Combinational stages or pipelined; either way a great
  excuse to use Scala-side parameterisation to unroll the
  iteration count at elaboration time. Useful subroutine for
  later graphics / synth work.
- [ ] **FirFilter** — parameterisable FIR with coefficients
  baked in as elaboration-time constants. Showcases the UP5K's
  hard DSP MAC blocks. Pairs naturally with `PdmAudio` /
  `I2sAudio` (filter the output before it hits the DAC).

### Standalone peripherals & demos
- [ ] **Ws2812** — bit-banged WS2812B / NeoPixel driver.
  Strict high-/low-time pulse encoding (you have ~150 ns of
  slack per bit at the spec-stated rate; very iCE40-friendly
  at 12 MHz with a small clock multiplier). Tiny project, big
  visual payoff.
- [ ] **SpiFlashReader** — read the iCEbreaker's on-board 16
  MB SPI flash (the same chip that holds the bitstream) from
  user logic. Useful prelude to SoC XIP and to anything that
  wants read-only assets (sprite ROMs, audio samples, fonts).
  Reuses **Spi** host code.
- [ ] **Ssd1306Oled** — driver for the ubiquitous 128×64 mono
  OLED PMOD over I²C. Composes with **I2c** (host) — an
  obvious first "real peripheral" target after I²C lands.
- [ ] **LogicAnalyser** — N-channel sampling buffer in BRAM
  with a simple trigger and UART upload to a host-side script
  that emits a `.vcd` or sigrok stream. The kind of thing
  you'd actually use to debug other projects on this list.
- [ ] **IrReceiver** — NEC-protocol IR remote decoder (carrier
  demod + pulse-width decode). Cheap TSOP1838 module on a PMOD.
  Tiny, cute, useful as an input device for SoC demos.

### Integration / fun
- [ ] **Chip8Emu** — CHIP-8 interpreter, either implemented as
  hardware or running as software on `VexRiscvSoC` (whichever
  is more interesting at the time). 64×32 framebuffer driven
  out via **Vga**, hex keypad via **Ps2** or buttons. CHIP-8
  is small, well-documented, and has a huge public ROM library
  → instant gratification. Excellent capstone for combining
  VGA + RAM + a CPU (real or emulated).
- [ ] **GameOfLife** — Conway's Game of Life on a **Vga**
  display. Pure-hardware double-buffered grid update, no CPU.
  All the interesting stuff is in the parallel cell-update
  logic and the framebuffer ping-pong.

---

## 💭 Cross-cutting / nice-to-have (no commitment)

- [ ] Per-project `make help` audit — every public target gets a
  `## description` (most already have this; spot-check on next pass).
- [ ] Repo-wide `scalafmt --check` from a top-level `Makefile` or
  GitHub Actions CI workflow. Currently each project just has its
  own `.scalafmt.conf`; no enforcement.
- [ ] Pull common shapes (`UartConfig`-style parameter records,
  `BaudGenerator`, `BufferCC` wrappers) into a shared library
  *if* duplication starts hurting. Resist the urge to do this
  prematurely — examples are supposed to be self-contained.
- [ ] Publish prebuilt bitstreams as GitHub Releases so people can
  flash without toolchain setup. Only worth doing once there are
  more board-level demos worth flashing.

---

## 📚 Reading-stack / patterns established so far

Project-by-project, what each one teaches that the next ones build on:

| Project          | New patterns introduced                                                |
|------------------|------------------------------------------------------------------------|
| Blinky           | `Component`, `Bundle`, `Reg.init`, BOOT reset                          |
| Button           | input pins, `BufferCC`, `RegNext` edge detect                          |
| ButtonDebouncer  | abstract `Component`, `apply(cfg)` factory, `SpinalEnum`               |
| Pwm              | first `SpinalSim` testbench, PMOD inputs                               |
| PwmFade          | LUTs at elaboration, `Mem` + `readAsync`, `src/hw` vs `src/sim` split  |
| Uart             | DDS baud gen, FSM + shift reg composition, sync FIFO, Stream wiring,   |
|                  | parameterizable config record, error side-band ports                   |

Future protocols should reuse these where they fit — especially the
`*Config` record, the `src/hw` vs `src/sim` split, the per-block sim,
and the `Stream`-based wrapper / FIFO / demo-top compositional pattern.
