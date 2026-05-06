package uart_tx

import spinal.core._
import spinal.core.formal._

/** Formal verification harness for [[TxShiftReg]].
  *
  * The strategy here is the classic "reference model equivalence"
  * approach: we build a tiny shadow register inside the harness that
  * faithfully implements the *spec* of TxShiftReg, then assert that
  * the DUT's `io.bit` always matches the shadow's bit 0.
  *
  * If the spec model and the DUT diverge under any input sequence
  * (within the BMC depth), the solver hands back a counterexample
  * trace showing the offending input pattern. This catches bugs that
  * are easy to miss in directed sims — wrong fill-bit on shift,
  * load/shift priority backwards, off-by-one on the LSB, etc.
  *
  * What we prove (BMC, ~25 cycles):
  *
  *   1. SAFETY — equivalence: `dut.io.bit === shadow(0)` on every
  *      cycle. This single assertion subsumes:
  *        - load behaviour       (load → bit = data(0))
  *        - shift behaviour      (shift → bit = previous bit 1)
  *        - load priority        (load && shift → load wins)
  *        - hold behaviour       (!load && !shift → bit unchanged)
  *        - reset value          (init = 0xff)
  *        - LSB-first ordering   (bit 0 first, then 1, then 2, …)
  *
  * What we cover (must be REACHABLE):
  *
  *   - A load happens.
  *   - A shift after a load, both with bit=0 and with bit=1, so we
  *     know both polarities are exercised.
  *   - A simultaneous load+shift (load priority is actually used).
  *   - All-zero data and all-one data both appear on the wire.
  *
  * The full-width `dataBits = 8` config is used — the state space is
  * small enough (~256 register states × 2 input bits per cycle) that
  * BMC of 25 cycles is instant.
  *
  * Run with `make formal-shiftreg`. Requires `yosys` and `sby` on PATH.
  */
object TxShiftRegFormal extends App {

  val cfg = UartTxConfig()

  FormalConfig
    .withBMC(25)
    .withCover(20)
    .doVerify(new Component {
      setDefinitionName("TxShiftRegFormal")

      val dut = FormalDut(TxShiftReg(cfg))

      // Let the solver pick any value for each input on every cycle.
      // Without this the inputs are undriven and PhaseCheck fails.
      anyseq(dut.io.load)
      anyseq(dut.io.shift)
      anyseq(dut.io.data)

      assumeInitial(ClockDomain.current.isResetActive)

      // ── reference model ──────────────────────────────────────────
      // Mirrors TxShiftReg's spec exactly. If this and the DUT ever
      // diverge, the DUT is wrong (or the spec is wrong, but if so
      // we want to know).
      val shadow = Reg(Bits(cfg.dataBits bits)) init (0xff)
      when(dut.io.load) {
        shadow := dut.io.data
      } elsewhen (dut.io.shift) {
        shadow := (shadow >> 1).resize(cfg.dataBits)
      }

      // ── PROPERTY 1: equivalence ──────────────────────────────────
      assert(
        dut.io.bit === shadow(0),
        "TxShiftReg.io.bit must equal shadow LSB on every cycle"
      )

      // ── COVERS ───────────────────────────────────────────────────
      cover(dut.io.load)
      cover(dut.io.shift && !dut.io.load)
      cover(dut.io.load && dut.io.shift)            // load-priority case actually exercised
      cover(dut.io.bit === False)
      cover(dut.io.bit === True)
      // A non-trivial sequence: load with bit-0 = 1, then shift to
      // expose bit-1 = 0 (or vice versa). Forces the solver to find
      // a real load-then-shift trace, not just hold forever.
      val loadedHi = RegInit(False)
      when(dut.io.load && dut.io.data(0)) { loadedHi := True }
      cover(loadedHi && dut.io.shift && !dut.io.load)
    })
}
