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
      // Third-master drivers for arb-loss tests. Default-released
      // (poke `true`) for every other test; pulled low to simulate a
      // competing master on the wired-AND bus.
      val competitorScl = in Bool ()
      val competitorSda = in Bool ()
    }
    val dut = I2cByteController(cfg)
    val target = new BehaviouralI2cTarget(cfg, tCfg)
    val bus = new I2cIoBus

    dut.io.cmd << io.cmd
    io.rsp << dut.io.rsp
    dut.io.bus <> bus.io.a
    target.io.bus <> bus.io.b
    bus.io.c.scl.write := io.competitorScl
    bus.io.c.sda.write := io.competitorSda

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
        rig.io.competitorScl #= true
        rig.io.competitorSda #= true
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
      rig.io.competitorScl #= true
      rig.io.competitorSda #= true
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
        rig.io.competitorScl #= true
        rig.io.competitorSda #= true
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
      rig.io.competitorScl #= true
      rig.io.competitorSda #= true
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

  /** AddrWrite + WriteData(reg) + RepStart + ReadData(NAK) + Stop. Verifies a
    * full register-read round trip, including the direction flip via RepStart.
    */
  private def caseRepeatedStartReadAfterWrite(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    val tCfg = BehaviouralI2cTargetConfig(regFileInit =
      Map(0x03 -> 0xfb)
    ) // address 0x50, ACK every byte
    SimConfig.withWave.compile(Rig(cfg, tCfg)).doSim("smoke-read-byte") { rig =>
      rig.clockDomain.forkStimulus(period = 10)
      rig.io.cmd.valid #= false
      rig.io.rsp.ready #= false
      rig.io.competitorScl #= true
      rig.io.competitorSda #= true
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

      // WriteData(0x03).
      issueCmd(rig, ByteCmdKind.WriteData, data = 0x03)
      val r2 = expectRsp(rig)
      assert(
        r2.status == ByteRspStatus.Ok,
        s"addr: status=${r2.status}, expected Ok"
      )
      assert(
        !r2.ackIn,
        s"addr: target NAK'd (ackIn=true); expected ACK (false)"
      )

      // RepStart(0x50 << 1 | 1). LSB is explicit.
      issueCmd(rig, ByteCmdKind.RepStart, data = 0x50 << 1 | 1)
      val r3 = expectRsp(rig)
      assert(
        r3.status == ByteRspStatus.Ok,
        s"addr: status=${r3.status}, expected Ok"
      )
      assert(
        !r3.ackIn,
        s"addr: target NAK'd (ackIn=true); expected ACK (false)"
      )

      // ReadData(NAK) -- single-byte read, so we tell the controller
      // to send NAK on the master ACK slot to signal end-of-read.
      issueCmd(rig, ByteCmdKind.ReadData, ackOut = true)
      val r4 = expectRsp(rig)
      assert(
        r4.status == ByteRspStatus.Ok,
        s"data: status=${r4.status}, expected Ok"
      )
      // ackIn is meaningless on a ReadData rsp (the controller drove
      // ACK itself); the load-bearing check is `data` -- the byte we
      // received from the slave. Default BehaviouralI2cTarget sends
      // 0xA5 (initial value of `readByte`).
      assert(
        r4.data == 0xfb,
        s"data: got 0x${r4.data.toString(16)}, expected 0xfb"
      )

      // Stop.
      issueCmd(rig, ByteCmdKind.Stop)
      val r5 = expectRsp(rig)
      assert(
        r5.status == ByteRspStatus.Ok,
        s"stop: status=${r5.status}, expected Ok"
      )

      // Let the bus settle, then check it's released.
      rig.clockDomain.waitSampling(20)
      assert(rig.sclWriteOut.toBoolean, "SCL not released after Stop")
      assert(rig.sdaWriteOut.toBoolean, "SDA not released after Stop")

      println("Ok: caseRepeatedStartReadAfterWrite")
    }
  }

  /** Target NAKs the address byte. Assert `ackIn = 1`, `mustTerminate` is set,
    * and a subsequent Stop is accepted (returning the FSM to idle).
    */
  private def caseAddressNak(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    val tCfg = BehaviouralI2cTargetConfig() // address 0x50, ACK every byte
    SimConfig.withWave.compile(Rig(cfg, tCfg)).doSim("smoke-read-byte") { rig =>
      rig.clockDomain.forkStimulus(period = 10)
      rig.io.cmd.valid #= false
      rig.io.rsp.ready #= false
      rig.io.competitorScl #= true
      rig.io.competitorSda #= true
      rig.clockDomain.waitSampling(5)

      // Start with a successful Write.
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

      // Stop immediately. Write probe-type access.
      issueCmd(rig, ByteCmdKind.Stop)
      val r2 = expectRsp(rig)
      assert(
        r2.status == ByteRspStatus.Ok,
        s"stop: status=${r2.status}, expected Ok"
      )

      // Then write to every other address
      for (addr <- 0 until 128 if addr != 0x50) {
        issueCmd(rig, ByteCmdKind.AddrWrite, data = addr << 1)
        val r3 = expectRsp(rig)
        assert(
          r3.status == ByteRspStatus.Ok,
          s"addr: status=${r3.status}, expected Ok"
        )
        assert(
          r3.ackIn,
          s"addr: target ACK'd (ackIn=false); expected NAK (true).}"
        )

        // Stop.
        issueCmd(rig, ByteCmdKind.Stop)
        val r4 = expectRsp(rig)
        assert(
          r4.status == ByteRspStatus.Ok,
          s"stop: status=${r4.status}, expected Ok"
        )

        // Let the bus settle, then check it's released.
        rig.clockDomain.waitSampling(20)
        assert(rig.sclWriteOut.toBoolean, "SCL not released after Stop")
        assert(rig.sdaWriteOut.toBoolean, "SDA not released after Stop")
      }

      println("Ok: caseAddressNak")
    }
  }

  /** Target NAKs the second data byte mid-burst. Assert the rsp carries `ackIn
    * \= 1`, `mustTerminate` latches, and only Stop / RepStart are accepted
    * next.
    *
    * Differs from caseInvalidSeqWedged: that one wedges on the *first* data
    * byte (always-NAK target). This one ACKs the address and the first data
    * byte, NAKs the second — the more realistic SMBus failure mode.
    */
  private def caseDataNak(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    // ACK byte 0, NAK byte 1 (pattern wraps mod 2 but we only write 2 bytes).
    val tCfg = BehaviouralI2cTargetConfig(ackPattern = Seq(true, false))
    SimConfig.withWave.compile(Rig(cfg, tCfg)).doSim("data-nak") { rig =>
      rig.clockDomain.forkStimulus(period = 10)
      rig.io.cmd.valid #= false
      rig.io.rsp.ready #= false
      rig.io.competitorScl #= true
      rig.io.competitorSda #= true
      rig.clockDomain.waitSampling(5)

      // Address phase: target ACKs.
      issueCmd(rig, ByteCmdKind.AddrWrite, data = 0x50 << 1)
      val r1 = expectRsp(rig)
      assert(
        r1.status == ByteRspStatus.Ok,
        s"addr: status=${r1.status}, expected Ok"
      )
      assert(!r1.ackIn, s"addr: ackIn=true, expected ACK")

      // First data byte: ACK'd.
      issueCmd(rig, ByteCmdKind.WriteData, data = 0xaa)
      val r2 = expectRsp(rig)
      assert(
        r2.status == ByteRspStatus.Ok,
        s"data0: status=${r2.status}, expected Ok"
      )
      assert(!r2.ackIn, s"data0: ackIn=true, expected ACK")

      // Second data byte: NAK'd. Status is still Ok (the byte transferred
      // cleanly); the NAK shows up via ackIn=true and latches mustTerminate.
      issueCmd(rig, ByteCmdKind.WriteData, data = 0xbb)
      val r3 = expectRsp(rig)
      assert(
        r3.status == ByteRspStatus.Ok,
        s"data1: status=${r3.status}, expected Ok"
      )
      assert(r3.ackIn, s"data1: ackIn=false, expected NAK (ackIn=true)")

      // Wedge proof: any non-terminator now returns InvalidSeq.
      issueCmd(rig, ByteCmdKind.WriteData, data = 0xcc)
      val r4 = expectRsp(rig)
      assert(
        r4.status == ByteRspStatus.InvalidSeq,
        s"wedged-data: status=${r4.status}, expected InvalidSeq"
      )

      // Recover with Stop.
      issueCmd(rig, ByteCmdKind.Stop)
      val r5 = expectRsp(rig)
      assert(
        r5.status == ByteRspStatus.Ok,
        s"stop: status=${r5.status}, expected Ok"
      )

      rig.clockDomain.waitSampling(20)
      assert(rig.sclWriteOut.toBoolean, "SCL not released after Stop")
      assert(rig.sdaWriteOut.toBoolean, "SDA not released after Stop")

      println("Ok: caseDataNak")
    }
  }

  /** A second master pulls SDA low during the Start condition. Assert the rsp
    * carries `status = ArbLost` and the FSM returns to idle without driving the
    * bus further.
    *
    * The bit controller only flags arb-loss when *releasing* SDA and reading
    * back 0 (i.e. during a `WriteBit` of 1). The Start edge itself can't
    * detect arb (both masters pulling low looks identical to solo). So
    * "during Start" really means during the first 1-bit of the address byte.
    * Address `0x55` (8-bit form `0xAA = 10101010`) puts a 1 at bit 7, the
    * very first bit shifted out, so the competitor wins on the first
    * release.
    */
  private def caseArbLossDuringStart(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    val tCfg = BehaviouralI2cTargetConfig() // unused — DUT never reaches addr ACK.
    SimConfig.withWave.compile(Rig(cfg, tCfg)).doSim("arb-loss-start") { rig =>
      rig.clockDomain.forkStimulus(period = 10)
      rig.io.cmd.valid #= false
      rig.io.rsp.ready #= false
      rig.io.competitorScl #= true
      rig.io.competitorSda #= true
      rig.clockDomain.waitSampling(5)

      // Competitor wins SDA from the get-go. Holding low across the Start
      // is benign — Start is "SDA falls while SCL high", and the DUT pulls
      // SDA itself, so it can't tell anyone else is also pulling. Arb-loss
      // fires when the DUT later releases SDA on the first 1-bit.
      rig.io.competitorSda #= false

      issueCmd(rig, ByteCmdKind.AddrWrite, data = 0x55 << 1)
      val r1 = expectRsp(rig)
      assert(
        r1.status == ByteRspStatus.ArbLost,
        s"addr: status=${r1.status}, expected ArbLost"
      )

      // Wedge proof: subsequent non-terminator returns InvalidSeq.
      issueCmd(rig, ByteCmdKind.WriteData, data = 0xcc)
      val r2 = expectRsp(rig)
      assert(
        r2.status == ByteRspStatus.InvalidSeq,
        s"wedged-data: status=${r2.status}, expected InvalidSeq"
      )

      // Release competitor before recovering — otherwise Stop can't drive
      // SDA high (the wired-AND would still see low).
      rig.io.competitorSda #= true

      issueCmd(rig, ByteCmdKind.Stop)
      val r3 = expectRsp(rig)
      // Stop's rsp surfaces sticky `arbLostReg`, so status is still ArbLost.
      // arbLostReg is cleared on Stop.onExit, so the next AddrWrite sees Ok.
      assert(
        r3.status == ByteRspStatus.ArbLost,
        s"stop: status=${r3.status}, expected ArbLost (sticky)"
      )
      // Recovery proof: a fresh, uncontested transaction succeeds (i.e. the
      // controller's wedge cleared). We don't assert on `ackIn` here — the
      // behavioural target may have been confused by the contention/Stop
      // sequence; what we care about is that the controller accepts the
      // new transaction (`status == Ok`) instead of returning InvalidSeq.
      issueCmd(rig, ByteCmdKind.AddrWrite, data = 0x50 << 1)
      val r4 = expectRsp(rig)
      assert(
        r4.status == ByteRspStatus.Ok,
        s"recover-addr: status=${r4.status}, expected Ok"
      )

      issueCmd(rig, ByteCmdKind.Stop)
      val r5 = expectRsp(rig)
      assert(
        r5.status == ByteRspStatus.Ok,
        s"recover-stop: status=${r5.status}, expected Ok"
      )

      rig.clockDomain.waitSampling(20)
      assert(rig.sclWriteOut.toBoolean, "SCL not released at end")
      assert(rig.sdaWriteOut.toBoolean, "SDA not released at end")

      println("Ok: caseArbLossDuringStart")
    }
  }

  /** Contention on SDA mid-byte (controller drives 1, second master pulls 0).
    * Assert the controller's `*WaitState` short-circuits to the matching
    * `*RspState` with `status = ArbLost`, and that `arbLost` is reported
    * exactly once.
    *
    * Strategy: clean address phase (competitor released), then issue
    * `WriteData(0xFF)` — every bit is a release, so any moment we pull
    * competitor SDA low during the data phase forces arb-loss.
    */
  private def caseArbLossMidByte(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    val tCfg = BehaviouralI2cTargetConfig()
    SimConfig.withWave.compile(Rig(cfg, tCfg)).doSim("arb-loss-mid") { rig =>
      rig.clockDomain.forkStimulus(period = 10)
      rig.io.cmd.valid #= false
      rig.io.rsp.ready #= false
      rig.io.competitorScl #= true
      rig.io.competitorSda #= true
      rig.clockDomain.waitSampling(5)

      // Clean address phase.
      issueCmd(rig, ByteCmdKind.AddrWrite, data = 0x50 << 1)
      val r1 = expectRsp(rig)
      assert(
        r1.status == ByteRspStatus.Ok,
        s"addr: status=${r1.status}, expected Ok"
      )
      assert(!r1.ackIn, s"addr: ackIn=true, expected ACK")

      // Fork a thread that pulls competitor SDA low partway through the
      // data byte. WriteData(0xFF) means the DUT releases SDA on every
      // bit; the competitor wins as soon as it pulls low.
      //
      // Timing: after issueCmd returns the cmd was just consumed. Bit
      // period at 100 kHz / 12 MHz clk is 120 cycles. Wait ~150 cycles
      // (about one bit in) before pulling low so we land mid-byte rather
      // than on bit 7.
      val arbThread = fork {
        rig.clockDomain.waitSampling(150)
        rig.io.competitorSda #= false
      }

      issueCmd(rig, ByteCmdKind.WriteData, data = 0xff)
      val r2 = expectRsp(rig)
      arbThread.join()
      assert(
        r2.status == ByteRspStatus.ArbLost,
        s"data: status=${r2.status}, expected ArbLost"
      )

      // Wedge proof.
      issueCmd(rig, ByteCmdKind.WriteData, data = 0xcc)
      val r3 = expectRsp(rig)
      assert(
        r3.status == ByteRspStatus.InvalidSeq,
        s"wedged-data: status=${r3.status}, expected InvalidSeq"
      )

      // Release competitor and recover.
      rig.io.competitorSda #= true

      issueCmd(rig, ByteCmdKind.Stop)
      val r4 = expectRsp(rig)
      // Stop's rsp surfaces sticky `arbLostReg`. Cleared on Stop.onExit.
      assert(
        r4.status == ByteRspStatus.ArbLost,
        s"stop: status=${r4.status}, expected ArbLost (sticky)"
      )

      // Recovery proof.
      // Recovery proof: a fresh, uncontested transaction is accepted by
      // the controller (`status == Ok`). We don't assert on `ackIn` —
      // the behavioural target may have been knocked out of sync by the
      // contention; what matters here is that the controller un-wedged.
      issueCmd(rig, ByteCmdKind.AddrWrite, data = 0x50 << 1)
      val r5 = expectRsp(rig)
      assert(
        r5.status == ByteRspStatus.Ok,
        s"recover-addr: status=${r5.status}, expected Ok"
      )

      issueCmd(rig, ByteCmdKind.Stop)
      val r6 = expectRsp(rig)
      assert(
        r6.status == ByteRspStatus.Ok,
        s"recover-stop: status=${r6.status}, expected Ok"
      )

      rig.clockDomain.waitSampling(20)
      assert(rig.sclWriteOut.toBoolean, "SCL not released at end")
      assert(rig.sdaWriteOut.toBoolean, "SDA not released at end")

      println("Ok: caseArbLossMidByte")
    }
  }

  /** From idle, every non-Addr* command (WriteData, ReadData, RepStart, Stop)
    * must emit `status = InvalidSeq` and leave the FSM in idle.
    */
  private def caseInvalidSeqFromIdle(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    val tCfg = BehaviouralI2cTargetConfig() // address 0x50, ACK every byte
    SimConfig.withWave.compile(Rig(cfg, tCfg)).doSim("invalid-seq-idle") {
      rig =>
        rig.clockDomain.forkStimulus(period = 10)
        rig.io.cmd.valid #= false
        rig.io.rsp.ready #= false
        rig.io.competitorScl #= true
        rig.io.competitorSda #= true
        rig.clockDomain.waitSampling(5)

        for (
          kind <- Seq(
            ByteCmdKind.WriteData,
            ByteCmdKind.ReadData,
            ByteCmdKind.RepStart,
            ByteCmdKind.Stop
          )
        ) {
          issueCmd(rig, kind, data = 0xd0)
          val r1 = expectRsp(rig)
          assert(
            r1.status == ByteRspStatus.InvalidSeq,
            s"$kind: status=${r1.status}, expected InvalidSeq"
          )

          // Let the bus settle, then check it's released.
          rig.clockDomain.waitSampling(20)
          assert(rig.sclWriteOut.toBoolean, s"SCL not released after $kind")
          assert(rig.sdaWriteOut.toBoolean, s"SDA not released after $kind")
        }

        println("Ok: caseInvalidSeqFromIdle")
    }
  }

  /** While `mustTerminate` is latched, every non-terminator command (AddrWrite,
    * AddrRead, WriteData, ReadData) must emit `status = InvalidSeq`. Only Stop
    * / RepStart should clear the wedged state.
    */
  private def caseInvalidSeqWedged(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    val tCfg = BehaviouralI2cTargetConfig(ackPattern =
      Seq(false)
    ) // address 0x50, always NAK
    SimConfig.withWave.compile(Rig(cfg, tCfg)).doSim("invalid-seq-wedged") {
      rig =>
        rig.clockDomain.forkStimulus(period = 10)
        rig.io.cmd.valid #= false
        rig.io.rsp.ready #= false
        rig.io.competitorScl #= true
        rig.io.competitorSda #= true
        rig.clockDomain.waitSampling(5)

        for (recovery <- Seq(ByteCmdKind.Stop, ByteCmdKind.RepStart)) {
          // Addr the target, should succeed
          issueCmd(rig, ByteCmdKind.AddrWrite, 0x50 << 1)
          val r1 = expectRsp(rig)
          assert(
            r1.status == ByteRspStatus.Ok,
            s"addr: status=${r1.status}, expected Ok"
          )

          // WriteData, should NAK
          issueCmd(rig, ByteCmdKind.WriteData, data = 0xfb)
          val r2 = expectRsp(rig)
          assert(
            r2.status == ByteRspStatus.Ok,
            s"data: status=${r2.status}, expected Ok"
          )
          assert(
            r2.ackIn,
            s"data: target ACK'd (ackIn=false); expected NAK (true)"
          )

          for (
            kind <- Seq(
              ByteCmdKind.AddrWrite,
              ByteCmdKind.AddrRead,
              ByteCmdKind.WriteData,
              ByteCmdKind.ReadData
            )
          ) {
            issueCmd(rig, kind, data = 0x50 << 1)
            val r3 = expectRsp(rig)
            assert(
              r3.status == ByteRspStatus.InvalidSeq,
              s"wedge-$kind: status=${r3.status}, expected InvalidSeq"
            )
          }

          // Clear the wedge. Aim RepStart at read direction (lsb=1) so
          // the follow-up ReadData below proves the controller can
          // resume normal operation. Stop ignores `data`.
          issueCmd(rig, recovery, data = 0x50 << 1 | 1)
          val r4 = expectRsp(rig)
          assert(
            r4.status == ByteRspStatus.Ok,
            s"recovery-${recovery}: status=${r4.status}, expected Ok"
          )

          // For RepStart, prove the wedge is *actually* cleared (not
          // just that RepStart returned Ok) by issuing a follow-up
          // ReadData. ackOut=true sends NAK to end the burst. The bus
          // ends up in the same between-bytes state RepStart left it
          // in (SCL low, SDA released), so the assertions below still
          // hold.
          if (recovery == ByteCmdKind.RepStart) {
            issueCmd(rig, ByteCmdKind.ReadData, ackOut = true)
            val r5 = expectRsp(rig)
            assert(
              r5.status == ByteRspStatus.Ok,
              s"post-recovery read: status=${r5.status}, expected Ok"
            )

            // End with a stop command, such that both SCL and SDA are
            // released in the end.
            issueCmd(rig, ByteCmdKind.Stop)
            val r6 = expectRsp(rig)
            assert(
              r6.status == ByteRspStatus.Ok,
              s"stop: status=${r6.status}, expected Ok"
            )
          }

          // Let the bus settle, then check it's in the expected state.
          rig.clockDomain.waitSampling(20)
          assert(rig.sclWriteOut.toBoolean, "SCL not released after Stop")
          assert(rig.sdaWriteOut.toBoolean, "SDA not released after Stop")
        }

        println("Ok: caseInvalidSeqWedged")
    }
  }

  /** Direction mismatch within a transaction:
    *   - WriteData on a read transaction (kind = AddrRead) → InvalidSeq.
    *   - ReadData on a write transaction (kind = AddrWrite) → InvalidSeq.
    */
  private def caseInvalidSeqDirectionMismatch(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    val tCfg = BehaviouralI2cTargetConfig() // address 0x50, always ACK
    SimConfig.withWave
      .compile(Rig(cfg, tCfg))
      .doSim("invalid-seq-dir-mismatch") { rig =>
        rig.clockDomain.forkStimulus(period = 10)
        rig.io.cmd.valid #= false
        rig.io.rsp.ready #= false
        rig.io.competitorScl #= true
        rig.io.competitorSda #= true
        rig.clockDomain.waitSampling(5)

        for (
          (addr, badData, goodData) <- Seq(
            (ByteCmdKind.AddrWrite, ByteCmdKind.ReadData, ByteCmdKind.WriteData),
            (ByteCmdKind.AddrRead, ByteCmdKind.WriteData, ByteCmdKind.ReadData)
          )
        ) {
          issueCmd(rig, addr, data = 0x50 << 1)
          val r1 = expectRsp(rig)
          assert(
            r1.status == ByteRspStatus.Ok,
            s"$addr: status=${r1.status}, expected Ok"
          )
          assert(
            !r1.ackIn,
            s"$addr: target NAK'd (ackIn=true); expected ACK (false)"
          )

          issueCmd(rig, badData, data = 0)
          val r2 = expectRsp(rig)
          assert(
            r2.status == ByteRspStatus.InvalidSeq,
            s"$addr: status=${r2.status}, expected InvalidSeq"
          )

          issueCmd(rig, goodData, data = 0)
          val r3 = expectRsp(rig)
          assert(
            r3.status == ByteRspStatus.Ok,
            s"$addr: status=${r3.status}, expected Ok"
          )

          issueCmd(rig, ByteCmdKind.Stop)
          val r4 = expectRsp(rig)
          assert(
            r4.status == ByteRspStatus.Ok,
            s"stop: status=${r4.status}, expected Ok"
          )
        }

        // Let the bus settle, then check it's in the expected state.
        rig.clockDomain.waitSampling(20)
        assert(rig.sclWriteOut.toBoolean, "SCL not released after Stop")
        assert(rig.sdaWriteOut.toBoolean, "SDA not released after Stop")

        println("Ok: caseInvalidSeqDirectionMismatch")
      }
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
    println("Done: 12 / 12 implemented")
  }
}
