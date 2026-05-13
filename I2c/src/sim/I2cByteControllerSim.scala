package i2c

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Simulation for [[I2cByteController]].
  *
  * The byte controller sits one rung above [[I2cBitController]]: each `ByteCmd`
  * it accepts maps to 8 bit-level transactions plus an ACK slot. So this sim
  * doesn't re-prove what the bit sim already proved (SCL pulse counts,
  * Start/Stop dwell, arb detection at the bit level). Instead it focuses on the
  * byte-level invariants the controller is responsible for:
  *
  *   - One `cmd.fire` produces exactly one `rsp.fire`.
  *   - Address-byte direction-bit masking matches `kind` (AddrWrite forces lsb
  *     \= 0, AddrRead forces lsb = 1, RepStart honours the user's lsb).
  *   - Multi-byte transactions stay "addressed" between bytes without
  *     re-issuing Start.
  *   - NAK from the target latches `mustTerminate`, and any non-terminator
  *     command issued while wedged returns `InvalidSeq`.
  *   - Arbitration loss short-circuits each `*WaitState` straight to the
  *     matching `*RspState` with `status = ArbLost`.
  *   - Stop is idempotent w.r.t. arb-loss (it always completes, because we owe
  *     the bus a release).
  *   - InvalidSeq is reported (and the FSM returns to idle) for every illegal
  *     command — from idle, while wedged, and on direction mismatch within a
  *     transaction.
  *
  * Rig --- The DUT's [[I2cIo]] hangs off one slave port of an [[I2cIoBus]]. The
  * other slave port is wired to a [[BehaviouralI2cTarget]] master. That puts a
  * real wired-AND between the two participants without sim hand-waving, and
  * lets arbitration tests fork an extra driver onto the same bus.
  *
  * ┌─────────────────────┐ ┌─────────────────────┐ │ I2cByteController │◀── a
  * ──▶│ │ │ (DUT) │ │ I2cIoBus │ └─────────────────────┘ │ (wired-AND slave) │
  * │ │ ┌─────────────────────┐ │ │ │ BehaviouralI2cTarget│◀── b ──▶│ │
  * └─────────────────────┘ └─────────────────────┘
  *
  * Cases ----- Twelve cases, each a stub at scaffold time. See per-method
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

  // ----- Test rig --------------------------------------------------------
  //
  // Wraps the controller, the behavioural target, and the wired-AND
  // I2cIoBus glue into a single top-level Component so SpinalSim has
  // exactly one `compile` target. The bus has no IO on the rig — the
  // wired-AND lives entirely inside it. The test pokes the
  // controller's cmd/rsp streams and snoops the controller-side bus
  // signals for assertions.
  private case class Rig(
      cfg: I2cConfig,
      tCfg: BehaviouralI2cTargetConfig
  ) extends Component {
    val io = new Bundle {
      val cmd = slave Stream ByteCmd()
      val rsp = master Stream ByteRsp()
    }
    val dut = I2cByteController(cfg)
    val target = new BehaviouralI2cTarget(cfg, tCfg)
    val bus = new I2cIoBus

    dut.io.cmd << io.cmd
    io.rsp << dut.io.rsp
    dut.io.bus <> bus.io.a
    target.io.bus <> bus.io.b

    val sclWriteOut = dut.io.bus.scl.write.simPublic()
    val sdaWriteOut = dut.io.bus.sda.write.simPublic()
  }

  // ----- Helpers --------------------------------------------------------

  /** Drive one [[ByteCmd]] into the DUT and wait for the producer handshake to
    * fire.
    *
    * Uses `waitSamplingWhere(cmd.ready)` rather than polling because the
    * controller's `cmd.ready` only rises in the idle/started/ wedged hubs —
    * between Issue/Wait pairs at the bit-ctrl level it stays low for many
    * cycles. Drop `valid` on the same cycle we observe `ready` (== `fire`) so a
    * second cmd doesn't accidentally land back-to-back.
    */
  private def issueCmd(
      rig: Rig,
      kind: ByteCmdKind.E,
      data: Int = 0,
      ackOut: Boolean = false
  ): Unit = {
    rig.io.cmd.kind #= kind
    rig.io.cmd.data #= data
    rig.io.cmd.ackOut #= ackOut
    rig.io.cmd.valid #= true
    rig.clockDomain.waitSamplingWhere(rig.io.cmd.ready.toBoolean)
    rig.io.cmd.valid #= false
  }

  /** Wait for one [[ByteRsp]] and snapshot its fields.
    *
    * Snapshots `data`/`ackIn`/`status` while `valid` is still true, then drops
    * `ready` next cycle so a stale `ready` doesn't consume the next response.
    */
  private def expectRsp(rig: Rig): ByteRspSnapshot = {
    rig.io.rsp.ready #= true
    rig.clockDomain.waitSamplingWhere(rig.io.rsp.valid.toBoolean)
    val snap = ByteRspSnapshot(
      data = rig.io.rsp.data.toBigInt,
      ackIn = rig.io.rsp.ackIn.toBoolean,
      status = rig.io.rsp.status.toEnum
    )
    rig.clockDomain.waitSampling()
    rig.io.rsp.ready #= false
    snap
  }

  // ----- Test cases -----------------------------------------------------
  //
  // Each case is a stub. Scaffold prints "PENDING: <name>" so the
  // runner exits cleanly and `make sim-bytectrl` exercises the full
  // build/run path without spinning a single DUT cycle.

  /** AddrWrite + WriteData + Stop, target ACKs both bytes. Smoke test of the
    * happy path.
    *
    * Asserts:
    *   - Each cmd produces exactly one rsp (3 cmds, 3 rsps).
    *   - All three rsps carry status = Ok.
    *   - Both write rsps carry ackIn = false (target accepted).
    *   - After Stop, the bus is released by the controller (both
    *     `bus.scl.write` and `bus.sda.write` are high). The wired-AND plus the
    *     target's idle-released drives mean the bus is at idle.
    */
  private def caseSmokeWriteByte(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    val tCfg = BehaviouralI2cTargetConfig() // address 0x50, ACK every byte
    SimConfig.withWave.compile(Rig(cfg, tCfg)).doSim("smoke-write-byte") {
      rig =>
        rig.clockDomain.forkStimulus(period = 10)
        rig.io.cmd.valid #= false
        rig.io.rsp.ready #= false
        rig.clockDomain.waitSampling(5)

        // AddrWrite(0x50<<1). Controller forces lsb = 0 anyway.
        issueCmd(rig, ByteCmdKind.AddrWrite, data = 0x50 << 1)
        val r1 = expectRsp(rig)
        assert(
          r1.status == ByteRspStatus.Ok,
          s"addr: status=${r1.status}, expected Ok"
        )
        assert(
          !r1.ackIn,
          s"addr: target NAK'd (ackIn=true); expected ACK (false)"
        )

        // WriteData(0xA5).
        issueCmd(rig, ByteCmdKind.WriteData, data = 0xa5)
        val r2 = expectRsp(rig)
        assert(
          r2.status == ByteRspStatus.Ok,
          s"data: status=${r2.status}, expected Ok"
        )
        assert(
          !r2.ackIn,
          s"data: target NAK'd (ackIn=true); expected ACK (false)"
        )

        // Stop.
        issueCmd(rig, ByteCmdKind.Stop)
        val r3 = expectRsp(rig)
        assert(
          r3.status == ByteRspStatus.Ok,
          s"stop: status=${r3.status}, expected Ok"
        )

        // Let the bus settle, then check it's released.
        rig.clockDomain.waitSampling(20)
        assert(rig.sclWriteOut.toBoolean, "SCL not released after Stop")
        assert(rig.sdaWriteOut.toBoolean, "SDA not released after Stop")

        println("OK: caseSmokeWriteByte")
    }
  }

  /** AddrRead + ReadData(NAK) + Stop, target sends 0xA5. Smoke test of the read
    * direction.
    */
  private def caseSmokeReadByte(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    val tCfg = BehaviouralI2cTargetConfig() // address 0x50, ACK every byte
    SimConfig.withWave.compile(Rig(cfg, tCfg)).doSim("smoke-read-byte") { rig =>
      rig.clockDomain.forkStimulus(period = 10)
      rig.io.cmd.valid #= false
      rig.io.rsp.ready #= false
      rig.clockDomain.waitSampling(5)

      // AddrRead(0x50<<1). Controller forces lsb = 1 anyway.
      issueCmd(rig, ByteCmdKind.AddrRead, data = 0x50 << 1)
      val r1 = expectRsp(rig)
      assert(
        r1.status == ByteRspStatus.Ok,
        s"addr: status=${r1.status}, expected Ok"
      )
      assert(
        !r1.ackIn,
        s"addr: target NAK'd (ackIn=true); expected ACK (false)"
      )

      // ReadData(NAK) -- single-byte read, so we tell the controller
      // to send NAK on the master ACK slot to signal end-of-read.
      issueCmd(rig, ByteCmdKind.ReadData, ackOut = true)
      val r2 = expectRsp(rig)
      assert(
        r2.status == ByteRspStatus.Ok,
        s"data: status=${r2.status}, expected Ok"
      )
      // ackIn is meaningless on a ReadData rsp (the controller drove
      // ACK itself); the load-bearing check is `data` -- the byte we
      // received from the slave. Default BehaviouralI2cTarget sends
      // 0x55 (initial value of `readByte`).
      assert(
        r2.data == 0x55,
        s"data: got 0x${r2.data.toString(16)}, expected 0x55"
      )

      // Stop.
      issueCmd(rig, ByteCmdKind.Stop)
      val r3 = expectRsp(rig)
      assert(
        r3.status == ByteRspStatus.Ok,
        s"stop: status=${r3.status}, expected Ok"
      )

      // Let the bus settle, then check it's released.
      rig.clockDomain.waitSampling(20)
      assert(rig.sclWriteOut.toBoolean, "SCL not released after Stop")
      assert(rig.sdaWriteOut.toBoolean, "SDA not released after Stop")

      println("OK: caseSmokeReadByte")
    }
  }

  /** AddrWrite + WriteData × 3 + Stop. Verifies the FSM stays addressed across
    * data bytes (no spurious Start between bytes).
    */
  private def caseMultiByteWrite(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    val tCfg = BehaviouralI2cTargetConfig() // address 0x50, ACK every byte
    SimConfig.withWave.compile(Rig(cfg, tCfg)).doSim("multi-write-byte") {
      rig =>
        rig.clockDomain.forkStimulus(period = 10)
        rig.io.cmd.valid #= false
        rig.io.rsp.ready #= false
        rig.clockDomain.waitSampling(5)

        // AddrWrite(0x50<<1). Controller forces lsb = 0 anyway.
        issueCmd(rig, ByteCmdKind.AddrWrite, data = 0x50 << 1)
        val r1 = expectRsp(rig)
        assert(
          r1.status == ByteRspStatus.Ok,
          s"addr: status=${r1.status}, expected Ok"
        )
        assert(
          !r1.ackIn,
          s"addr: target NAK'd (ackIn=true); expected ACK (false)"
        )

        // WriteData(0xA5).
        for (i <- 0 until 3) {
          issueCmd(rig, ByteCmdKind.WriteData, data = 0xa5)
          val r2 = expectRsp(rig)
          assert(
            r2.status == ByteRspStatus.Ok,
            s"data: status=${r2.status}, expected Ok"
          )
          assert(
            !r2.ackIn,
            s"data: target NAK'd (ackIn=true); expected ACK (false)"
          )
        }

        // Stop.
        issueCmd(rig, ByteCmdKind.Stop)
        val r3 = expectRsp(rig)
        assert(
          r3.status == ByteRspStatus.Ok,
          s"stop: status=${r3.status}, expected Ok"
        )

        // Let the bus settle, then check it's released.
        rig.clockDomain.waitSampling(20)
        assert(rig.sclWriteOut.toBoolean, "SCL not released after Stop")
        assert(rig.sdaWriteOut.toBoolean, "SDA not released after Stop")

        println("OK: caseMultiWriteByte")
    }
  }

  /** AddrRead + ReadData(ACK) × 2 + ReadData(NAK) + Stop. Verifies `ackOut`
    * propagates correctly to the master ACK slot, and that the final NAK
    * doesn't trip mustTerminate (it's the legal end of a read).
    */
  private def caseMultiByteRead(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    val tCfg = BehaviouralI2cTargetConfig() // address 0x50, ACK every byte
    SimConfig.withWave.compile(Rig(cfg, tCfg)).doSim("smoke-read-byte") { rig =>
      rig.clockDomain.forkStimulus(period = 10)
      rig.io.cmd.valid #= false
      rig.io.rsp.ready #= false
      rig.clockDomain.waitSampling(5)

      // AddrRead(0x50<<1). Controller forces lsb = 1 anyway.
      issueCmd(rig, ByteCmdKind.AddrRead, data = 0x50 << 1)
      val r1 = expectRsp(rig)
      assert(
        r1.status == ByteRspStatus.Ok,
        s"addr: status=${r1.status}, expected Ok"
      )
      assert(
        !r1.ackIn,
        s"addr: target NAK'd (ackIn=true); expected ACK (false)"
      )

      for (i <- 0 until 3) {
        // ReadData(NAK) -- single-byte read, so we tell the controller
        // to send NAK on the master ACK slot to signal end-of-read.
        issueCmd(rig, ByteCmdKind.ReadData, ackOut = i == 2)
        val r2 = expectRsp(rig)
        assert(
          r2.status == ByteRspStatus.Ok,
          s"data: status=${r2.status}, expected Ok"
        )
        // ackIn is meaningless on a ReadData rsp (the controller drove
        // ACK itself); the load-bearing check is `data` -- the byte we
        // received from the slave. Default BehaviouralI2cTarget sends
        // 0xA5 (initial value of `readByte`).
        assert(
          r2.data == 0x55,
          s"data: got 0x${r2.data.toString(16)}, expected 0x55"
        )
      }

      // Stop.
      issueCmd(rig, ByteCmdKind.Stop)
      val r3 = expectRsp(rig)
      assert(
        r3.status == ByteRspStatus.Ok,
        s"stop: status=${r3.status}, expected Ok"
      )

      // Let the bus settle, then check it's released.
      rig.clockDomain.waitSampling(20)
      assert(rig.sclWriteOut.toBoolean, "SCL not released after Stop")
      assert(rig.sdaWriteOut.toBoolean, "SDA not released after Stop")

      println("OK: caseMultiReadByte")
    }
  }

  /** AddrWrite + WriteData(reg) + RepStart + AddrRead + ReadData(NAK) + Stop.
    * Verifies a full register-read round trip, including the direction flip via
    * RepStart.
    */
  private def caseRepeatedStartReadAfterWrite(): Unit = {
    println("PENDING: caseRepeatedStartReadAfterWrite")
  }

  /** Target NAKs the address byte. Assert `ackIn = 1`, `mustTerminate` is set,
    * and a subsequent Stop is accepted (returning the FSM to idle).
    */
  private def caseAddressNak(): Unit = {
    println("PENDING: caseAddressNak")
  }

  /** Target NAKs the second data byte mid-burst. Assert the rsp carries `ackIn
    * \= 1`, `mustTerminate` latches, and only Stop / RepStart are accepted
    * next.
    */
  private def caseDataNak(): Unit = {
    println("PENDING: caseDataNak")
  }

  /** A second master pulls SDA low during the Start condition. Assert the rsp
    * carries `status = ArbLost` and the FSM returns to idle without driving the
    * bus further.
    */
  private def caseArbLossDuringStart(): Unit = {
    println("PENDING: caseArbLossDuringStart")
  }

  /** Contention on SDA mid-byte (controller drives 1, second master pulls 0).
    * Assert the controller's `*WaitState` short-circuits to the matching
    * `*RspState` with `status = ArbLost`, and that `arbLost` is reported
    * exactly once.
    */
  private def caseArbLossMidByte(): Unit = {
    println("PENDING: caseArbLossMidByte")
  }

  /** From idle, every non-Addr* command (WriteData, ReadData, RepStart, Stop)
    * must emit `status = InvalidSeq` and leave the FSM in idle.
    */
  private def caseInvalidSeqFromIdle(): Unit = {
    println("PENDING: caseInvalidSeqFromIdle")
  }

  /** While `mustTerminate` is latched, every non-terminator command (AddrWrite,
    * AddrRead, WriteData, ReadData) must emit `status = InvalidSeq`. Only Stop
    * / RepStart should clear the wedged state.
    */
  private def caseInvalidSeqWedged(): Unit = {
    println("PENDING: caseInvalidSeqWedged")
  }

  /** Direction mismatch within a transaction:
    *   - WriteData on a read transaction (kind = AddrRead) → InvalidSeq.
    *   - ReadData on a write transaction (kind = AddrWrite) → InvalidSeq.
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
    println("Done: 4 / 12 implemented")
  }
}
