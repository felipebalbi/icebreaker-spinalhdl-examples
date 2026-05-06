package uart_tx

import spinal.core._
import spinal.core.formal._

/** Inductive (unbounded) formal proof for [[BaudGenerator]].
  *
  * BMC ([[BaudGeneratorFormal]]) only proves properties hold for the
  * first N cycles starting from reset. Induction proves they hold
  * **forever**, from any state. The price is that the solver starts
  * the inductive step from an *arbitrary* register state — including
  * states the design can never actually reach — which produces
  * spurious counterexamples unless you supply invariants tight enough
  * to rule them out.
  *
  * The two-step contract `sby` runs in `prove` mode:
  *
  *   1. BASE CASE: starting at reset, properties hold for k cycles.
  *      (Same thing as BMC of depth k.)
  *
  *   2. INDUCTIVE STEP: starting at an *arbitrary* state, if all
  *      properties (including the strengthening invariants) held for
  *      the last k cycles, they hold for the next one.
  *
  * If both pass, the design satisfies the properties for all reachable
  * states for all time.
  *
  * What we prove (unbounded):
  *
  *   1. SAFETY — accumulator envelope: `acc` is always in
  *      `[0, 2^accWidth + phaseInc)`. This is the strengthening
  *      invariant that makes the no-back-to-back-tick property
  *      inductive. Without it, the solver can pick `acc = 2^(accWidth+1) - 1`
  *      as an initial state and immediately violate everything.
  *
  *   2. SAFETY — no back-to-back ticks: `tick(t) ∧ tick(t+1)` is
  *      impossible. Together with property 1 this proves the DDS is
  *      genuinely producing isolated pulses, not a continuous high.
  *
  *   3. SAFETY — quiescence: holding `enable` low for two cycles
  *      forces `tick` low. Already inductive without help (depends
  *      only on registered values of `enable` and the unconditional
  *      `acc := 0` assignment).
  *
  * Why no covers?
  *   `cover` is a BMC-only construct. Covers prove a state is
  *   reachable, which is meaningless in the inductive step (where
  *   every state is "reachable" by fiat). The BMC harness already
  *   carries the covers; this file's job is the unbounded proof.
  *
  * Run with `make formal-baud-prove`. Requires `yosys` and `sby`.
  */
object BaudGeneratorProve extends App {

  // Same toy config as the BMC harness — small state space keeps the
  // inductive step fast.
  val cfg      = UartTxConfig(clkFreqHz = 16, baudRate = 4)
  val accWidth = 4

  // Match the design's phaseInc computation exactly. This number
  // appears in the strengthening invariant.
  val phaseInc: Long = (
    ((BigInt(cfg.baudRate) << accWidth) + (BigInt(cfg.clkFreqHz) >> 1))
      / cfg.clkFreqHz
  ).toLong

  FormalConfig
    .withProve(8)              // k-induction depth; 8 is plenty for a 5-bit accumulator
    .doVerify(new Component {
      setDefinitionName("BaudGeneratorProve")

      val dut = FormalDut(BaudGenerator(cfg, accWidth))

      anyseq(dut.io.enable)

      // Reset only the BASE case starts in reset; the inductive step
      // is allowed to start anywhere. That's exactly the point of
      // induction and exactly why we need invariants.
      assumeInitial(ClockDomain.current.isResetActive)

      val enableD1 = RegNext(dut.io.enable) init (False)
      val enableD2 = RegNext(enableD1)      init (False)
      val tickD1   = RegNext(dut.io.tick)   init (False)

      // ── PROPERTY 1 (the strengthening invariant) ─────────────────
      // After every clock edge, `acc` lies in the reachable envelope:
      //   - lower bound: 0 (always non-negative; `acc` is a UInt so
      //     this is automatic from the type)
      //   - upper bound: 2^accWidth + phaseInc, exclusive. The +1
      //     accounts for the cycle the carry MSB is set; the next
      //     cycle's masking brings it back into [0, 2^accWidth).
      // Without this invariant, the inductive step is free to pick
      // `acc = 2^(accWidth+1) - 1` and immediately falsify everything.
      val accBound = U(BigInt(1) << accWidth, accWidth + 1 bits) + phaseInc
      assert(
        dut.acc < accBound,
        "acc must stay within the reachable envelope"
      )

      // ── PROPERTY 2 — no back-to-back ticks ───────────────────────
      // With the envelope above, `acc.msb` cannot be 1 on two
      // consecutive cycles, hence `tick` cannot either.
      when(tickD1) {
        assert(!dut.io.tick, "tick must be at most one cycle wide")
      }

      // ── PROPERTY 3 — quiescence under sustained disable ──────────
      // Two cycles of registered history are needed because:
      //   - one cycle to force `acc := 0`
      //   - one cycle for `tick = RegNext(acc.msb)` to settle to 0
      when(!enableD1 && !enableD2) {
        assert(!dut.io.tick, "tick must be low when enable held low")
      }
    })
}
