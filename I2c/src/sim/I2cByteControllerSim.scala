package i2c

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Simulation for [[I2cByteController]].
  *
  * The byte controller sits one rung above [[I2cBitController]]:
  * each `ByteCmd` it accepts maps to 8 bit-level transactions
  * plus an ACK slot. So this sim doesn't re-prove what the bit
  * sim already proved (SCL pulse counts, Start/Stop dwell, arb
  * detection at the bit level). Instead it focuses on the
  * byte-level invariants the controller is responsible for:
  *
  *   - One `cmd.fire` produces exactly one `rsp.fire`.
  *   - Address-byte direction-bit masking matches `kind`
  *     (AddrWrite forces lsb = 0, AddrRead forces lsb = 1,
  *     RepStart honours the user's lsb).
  *   - Multi-byte transactions stay "addressed" between bytes
  *     without re-issuing Start.
  *   - NAK from the target latches `mustTerminate`, and any
  *     non-terminator command issued while wedged returns
  *     `InvalidSeq`.
  *   - Arbitration loss short-circuits each `*WaitState` straight
  *     to the matching `*RspState` with `status = ArbLost`.
  *   - Stop is idempotent w.r.t. arb-loss (it always completes,
  *     because we owe the bus a release).
  *   - InvalidSeq is reported (and the FSM returns to idle) for
  *     every illegal command — from idle, while wedged, and on
  *     direction mismatch within a transaction.
  *
  * Rig
  * ---
  * The DUT's [[I2cIo]] hangs off one slave port of an
  * [[I2cIoBus]]. The other slave port is wired to a
  * [[BehaviouralI2cTarget]] master. That puts a real wired-AND
  * between the two participants without sim hand-waving, and lets
  * arbitration tests fork an extra driver onto the same bus.
  *
  *   ┌─────────────────────┐         ┌─────────────────────┐
  *   │ I2cByteController   │◀── a ──▶│                     │
  *   │ (DUT)               │         │     I2cIoBus        │
  *   └─────────────────────┘         │  (wired-AND slave)  │
  *                                   │                     │
  *   ┌─────────────────────┐         │                     │
  *   │ BehaviouralI2cTarget│◀── b ──▶│                     │
  *   └─────────────────────┘         └─────────────────────┘
  *
  * Cases
  * -----
  * Twelve cases, each a stub at scaffold time. See per-method
  * Scaladoc below for what each one will eventually verify.
  *
  * Run: `sbt "runMain i2c.I2cByteControllerSim"`
  */
object I2cByteControllerSim {

  // ----- Snapshot type --------------------------------------------------
  //
  // Plain-Scala value type so assertions don't have to chase
  // SpinalSim handles after the fact.
  private case class ByteRspSnapshot(
      data: BigInt,
      ackIn: Boolean,
      status: ByteRspStatus.E
  )

  // ----- Helpers --------------------------------------------------------
  //
  // Signatures only; bodies wait for the first real test case to
  // anchor the patterns.

  /** Drive one [[ByteCmd]] into the DUT and wait for the producer
    * handshake to fire.
    */
  private def issueCmd(
      dut: I2cByteController,
      kind: ByteCmdKind.E,
      data: Int = 0,
      ackOut: Boolean = false
  ): Unit = {
    // TODO: drive io.cmd.{kind, data, ackOut} and waitSamplingWhere(io.cmd.fire).
    dut.clockDomain.waitSampling()
  }

  /** Wait for one [[ByteRsp]] and snapshot its fields. */
  private def expectRsp(dut: I2cByteController): ByteRspSnapshot = {
    // TODO: io.rsp.ready := True; waitSamplingWhere(io.rsp.fire); snapshot.
    dut.clockDomain.waitSampling()
    ByteRspSnapshot(0, ackIn = true, ByteRspStatus.Ok)
  }

  // ----- Test cases -----------------------------------------------------
  //
  // Each case is a stub. Scaffold prints "PENDING: <name>" so the
  // runner exits cleanly and `make sim-bytectrl` exercises the full
  // build/run path without spinning a single DUT cycle.

  /** AddrWrite + WriteData + Stop, target ACKs both bytes.
    * Smoke test of the happy path.
    */
  private def caseSmokeWriteByte(): Unit = {
    println("PENDING: caseSmokeWriteByte")
  }

  /** AddrRead + ReadData(NAK) + Stop, target sends 0xA5.
    * Smoke test of the read direction.
    */
  private def caseSmokeReadByte(): Unit = {
    println("PENDING: caseSmokeReadByte")
  }

  /** AddrWrite + WriteData × 3 + Stop. Verifies the FSM stays
    * addressed across data bytes (no spurious Start between bytes).
    */
  private def caseMultiByteWrite(): Unit = {
    println("PENDING: caseMultiByteWrite")
  }

  /** AddrRead + ReadData(ACK) × 2 + ReadData(NAK) + Stop. Verifies
    * `ackOut` propagates correctly to the master ACK slot, and that
    * the final NAK doesn't trip mustTerminate (it's the legal end
    * of a read).
    */
  private def caseMultiByteRead(): Unit = {
    println("PENDING: caseMultiByteRead")
  }

  /** AddrWrite + WriteData(reg) + RepStart + AddrRead +
    * ReadData(NAK) + Stop. Verifies a full register-read round trip,
    * including the direction flip via RepStart.
    */
  private def caseRepeatedStartReadAfterWrite(): Unit = {
    println("PENDING: caseRepeatedStartReadAfterWrite")
  }

  /** Target NAKs the address byte. Assert `ackIn = 1`,
    * `mustTerminate` is set, and a subsequent Stop is accepted
    * (returning the FSM to idle).
    */
  private def caseAddressNak(): Unit = {
    println("PENDING: caseAddressNak")
  }

  /** Target NAKs the second data byte mid-burst. Assert the rsp
    * carries `ackIn = 1`, `mustTerminate` latches, and only Stop /
    * RepStart are accepted next.
    */
  private def caseDataNak(): Unit = {
    println("PENDING: caseDataNak")
  }

  /** A second master pulls SDA low during the Start condition.
    * Assert the rsp carries `status = ArbLost` and the FSM
    * returns to idle without driving the bus further.
    */
  private def caseArbLossDuringStart(): Unit = {
    println("PENDING: caseArbLossDuringStart")
  }

  /** Contention on SDA mid-byte (controller drives 1, second
    * master pulls 0). Assert the controller's `*WaitState`
    * short-circuits to the matching `*RspState` with
    * `status = ArbLost`, and that `arbLost` is reported exactly
    * once.
    */
  private def caseArbLossMidByte(): Unit = {
    println("PENDING: caseArbLossMidByte")
  }

  /** From idle, every non-Addr* command (WriteData, ReadData,
    * RepStart, Stop) must emit `status = InvalidSeq` and leave
    * the FSM in idle.
    */
  private def caseInvalidSeqFromIdle(): Unit = {
    println("PENDING: caseInvalidSeqFromIdle")
  }

  /** While `mustTerminate` is latched, every non-terminator
    * command (AddrWrite, AddrRead, WriteData, ReadData) must emit
    * `status = InvalidSeq`. Only Stop / RepStart should clear the
    * wedged state.
    */
  private def caseInvalidSeqWedged(): Unit = {
    println("PENDING: caseInvalidSeqWedged")
  }

  /** Direction mismatch within a transaction:
    *   - WriteData on a read transaction (kind = AddrRead) →
    *     InvalidSeq.
    *   - ReadData on a write transaction (kind = AddrWrite) →
    *     InvalidSeq.
    */
  private def caseInvalidSeqDirectionMismatch(): Unit = {
    println("PENDING: caseInvalidSeqDirectionMismatch")
  }

  // ----- Runner ---------------------------------------------------------

  def main(args: Array[String]): Unit = {
    caseSmokeWriteByte()
    caseSmokeReadByte()
    caseMultiByteWrite()
    caseMultiByteRead()
    caseRepeatedStartReadAfterWrite()
    caseAddressNak()
    caseDataNak()
    caseArbLossDuringStart()
    caseArbLossMidByte()
    caseInvalidSeqFromIdle()
    caseInvalidSeqWedged()
    caseInvalidSeqDirectionMismatch()
    println("Done: 0 / 12 implemented")
  }
}
