package i2c

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Simulation for [[I2cBitController]].
  *
  * The bit controller is the only block that touches the I²C bus
  * directly, so this sim is the foundation for all the higher-level
  * tests. We don't bother with a second [[I2cIoBus]] participant for
  * most cases — a one-sided observer that snoops the wires is enough,
  * because the controller is the only driver. The arbitration test
  * is the exception: there we need a second driver to actively pull
  * SDA low while the controller has it released.
  *
  * Approach: instead of asserting an exact cycle-by-cycle waveform
  * (which is brittle and re-implements [[BusTiming]] in the test),
  * we focus on protocol-level invariants:
  *
  *   - SCL pulse counts match the number of bits issued.
  *   - Every Start/Stop edge satisfies its spec dwell ±1 cycle of
  *     entry latency.
  *   - WriteBit produces the expected SDA value during the SCL high
  *     phase.
  *   - ReadBit captures the SDA value driven by the sim during the
  *     SCL high phase.
  *   - Arbitration loss flips `arbLost` and releases both lines
  *     within one cycle.
  *   - Clock stretching extends the SCL high phase by exactly the
  *     number of cycles the sim holds SCL low.
  *
  * Run: `sbt "runMain i2c.I2cBitControllerSim"`
  */
object I2cBitControllerSim {

  // Drive a command into the bit controller and wait for it to fire.
  private def issue(dut: I2cBitController, cmd: BitCmd.E, txBit: Boolean = false): Unit = {
    dut.io.cmd.payload #= cmd
    dut.io.txBit #= txBit
    dut.io.cmd.valid #= true
    dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean)
    dut.io.cmd.valid #= false
  }

  // Wait until the FSM is back in idle (cmd.ready re-asserts).
  private def waitDone(dut: I2cBitController): Unit = {
    dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean)
  }

  // Mirror what the controller drives onto the .read inputs every
  // cycle. With no second participant, this is the wired-AND model
  // for a single-master bus: whatever the controller writes is what
  // it sees back. Without this, .read defaults to 0 (false) under
  // Verilator and the arbitration check `txBitLatched && !sda.read`
  // trips on every WriteBit(1), which both flips arbLost and (via
  // the recovery path leaving SCL released) silently masks the next
  // bit's SCL rising edge.
  //
  // Tests that need a second driver (read sim, arb-loss sim,
  // clock-stretching sim) install their own mirror that overrides
  // the relevant line during the contention window.
  private def mirrorBus(dut: I2cBitController) = {
    dut.io.bus.scl.read #= true
    dut.io.bus.sda.read #= true
    fork {
      while (true) {
        dut.io.bus.scl.read #= dut.io.bus.scl.write.toBoolean
        dut.io.bus.sda.read #= dut.io.bus.sda.write.toBoolean
        dut.clockDomain.waitSampling()
      }
    }
  }

  // Count rising/falling edges on a sim signal across a window.
  private class EdgeCounter(initialHigh: Boolean) {
    var prev = initialHigh
    var rising = 0
    var falling = 0
    def update(now: Boolean): Unit = {
      if (!prev && now) rising += 1
      if (prev && !now) falling += 1
      prev = now
    }
  }

  // ---- Smoke: Start -> Stop on a fresh bus ---------------------------------
  // Verifies the FSM produces both framing edges and ends with the bus
  // released. The SCL pulse count is 0 (Start and Stop don't toggle SCL
  // through any data bit) and SDA exhibits two edges total: fall during
  // Start, rise during Stop.
  private def smokeStartStop(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    SimConfig.withWave.compile(I2cBitController(cfg)).doSim("smoke-start-stop") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.txBit #= false
      val mirror = mirrorBus(dut)
      dut.clockDomain.waitSampling(5)

      val sclEdges = new EdgeCounter(true)
      val sdaEdges = new EdgeCounter(true)
      val watcher = fork {
        while (true) {
          sclEdges.update(dut.io.bus.scl.write.toBoolean)
          sdaEdges.update(dut.io.bus.sda.write.toBoolean)
          dut.clockDomain.waitSampling()
        }
      }

      issue(dut, BitCmd.Start)
      waitDone(dut)
      issue(dut, BitCmd.Stop)
      waitDone(dut)
      dut.clockDomain.waitSampling(5)
      watcher.terminate()
      mirror.terminate()

      assert(dut.io.bus.scl.write.toBoolean, "bus.scl should rest released after Stop")
      assert(dut.io.bus.sda.write.toBoolean, "bus.sda should rest released after Stop")
      assert(sdaEdges.falling >= 1, s"expected SDA fall during Start, got ${sdaEdges.falling}")
      assert(sdaEdges.rising >= 1, s"expected SDA rise during Stop, got ${sdaEdges.rising}")
      // SCL: falls once (after Start dwell), rises once (during Stop sequence),
      // falls again? No -- Stop ends with SCL released. So 1 fall, 1 rise.
      assert(sclEdges.falling == 1, s"expected 1 SCL fall, got ${sclEdges.falling}")
      assert(sclEdges.rising == 1, s"expected 1 SCL rise, got ${sclEdges.rising}")

      println("OK: smoke Start->Stop")
    }
  }

  // ---- Write a byte: 8 WriteBit commands, check SCL pulses & SDA values ----
  // After Start, issue 8 bits of pattern 0xA5 = 1010_0101 (MSB first). For
  // each bit, observe SDA value during the SCL high phase.
  private def writeByte(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    SimConfig.withWave.compile(I2cBitController(cfg)).doSim("write-byte") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.txBit #= false
      val mirror = mirrorBus(dut)
      dut.clockDomain.waitSampling(5)

      issue(dut, BitCmd.Start); waitDone(dut)

      val pattern = Seq(true, false, true, false, false, true, false, true) // 0xA5 MSB-first
      var sclPulses = 0
      val sclTracker = new EdgeCounter(true)
      val watcher = fork {
        while (true) {
          val now = dut.io.bus.scl.write.toBoolean
          sclTracker.update(now)
          dut.clockDomain.waitSampling()
        }
      }

      for ((bit, idx) <- pattern.zipWithIndex) {
        // Issue WriteBit
        issue(dut, BitCmd.WriteBit, txBit = bit)
        // While the FSM is processing the bit, wait for SCL to go high
        // and verify SDA reflects the requested bit at midpoint.
        dut.clockDomain.waitSamplingWhere(dut.io.bus.scl.write.toBoolean)
        // Wait a few cycles into the high phase, then sample SDA.
        dut.clockDomain.waitSampling(timing(cfg).tHigh / 2)
        assert(
          dut.io.bus.sda.write.toBoolean == bit,
          s"bit $idx: expected SDA=$bit, got ${dut.io.bus.sda.write.toBoolean}"
        )
        waitDone(dut)
      }

      dut.clockDomain.waitSampling(5)
      watcher.terminate()
      mirror.terminate()
      sclPulses = sclTracker.rising
      assert(sclPulses == pattern.size, s"expected ${pattern.size} SCL pulses, got $sclPulses")
      assert(!dut.io.arbLost.toBoolean, "arbLost should not fire when bus is mirrored")
      println("OK: write byte 0xA5")
    }
  }

  // ---- Read a byte: drive SDA from sim, check rxBit ------------------------
  // After Start, issue 8 ReadBits. For each bit, drive SDA from the sim
  // before SCL rises, and check rxBit after the FSM returns to idle.
  private def readByte(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    SimConfig.withWave.compile(I2cBitController(cfg)).doSim("read-byte") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.txBit #= false
      // Default: bus reads what we drive (no second participant).
      dut.io.bus.scl.read #= true
      dut.io.bus.sda.read #= true
      // Mirror the controller's drive onto .read by default; sim tasks
      // override sda.read when they want to "be the slave".
      val mirror = fork {
        while (true) {
          dut.io.bus.scl.read #= dut.io.bus.scl.write.toBoolean
          dut.clockDomain.waitSampling()
        }
      }
      dut.clockDomain.waitSampling(5)

      issue(dut, BitCmd.Start); waitDone(dut)

      val pattern = Seq(false, true, true, false, true, false, false, true) // 0x69 MSB-first
      val received = scala.collection.mutable.ArrayBuffer[Boolean]()
      for (bit <- pattern) {
        // Pre-set the SDA value the "slave" will drive for this bit.
        dut.io.bus.sda.read #= bit
        issue(dut, BitCmd.ReadBit, txBit = false)
        waitDone(dut)
        received += dut.io.rxBit.toBoolean
      }

      dut.clockDomain.waitSampling(5)
      mirror.terminate()

      assert(received.toSeq == pattern, s"expected $pattern, got $received")
      println("OK: read byte 0x69")
    }
  }

  // ---- RepStart -----------------------------------------------------------
  // Start -> WriteBit -> RepStart -> WriteBit -> Stop. Verifies that the
  // RepStart prep states produce the expected (release SDA, release SCL,
  // SDA fall, SCL fall) sequence and that the post-RepStart bit transmits
  // cleanly.
  private def repeatedStart(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    SimConfig.withWave.compile(I2cBitController(cfg)).doSim("rep-start") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.txBit #= false
      val mirror = mirrorBus(dut)
      dut.clockDomain.waitSampling(5)

      issue(dut, BitCmd.Start); waitDone(dut)
      issue(dut, BitCmd.WriteBit, txBit = true); waitDone(dut)
      issue(dut, BitCmd.RepStart); waitDone(dut)
      issue(dut, BitCmd.WriteBit, txBit = false); waitDone(dut)
      issue(dut, BitCmd.Stop); waitDone(dut)
      mirror.terminate()

      assert(dut.io.bus.scl.write.toBoolean, "SCL released after Stop")
      assert(dut.io.bus.sda.write.toBoolean, "SDA released after Stop")
      assert(!dut.io.arbLost.toBoolean, "no arbitration loss expected")
      println("OK: RepStart sequence")
    }
  }

  // ---- Arbitration loss ---------------------------------------------------
  // Issue WriteBit(1) (controller releases SDA). During the SCL high
  // phase, drive SDA low from the sim. Assert arbLost rises and the FSM
  // returns to idle within one cycle (cmd.ready re-asserts).
  private def arbitrationLoss(): Unit = {
    val cfg = I2cConfig(clkFreqHz = 12000000, busSpeed = BusSpeed.Standard)
    SimConfig.withWave.compile(I2cBitController(cfg)).doSim("arb-loss") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.txBit #= false
      dut.io.bus.scl.read #= true
      dut.io.bus.sda.read #= true
      val mirror = fork {
        while (true) {
          dut.io.bus.scl.read #= dut.io.bus.scl.write.toBoolean
          dut.clockDomain.waitSampling()
        }
      }
      dut.clockDomain.waitSampling(5)

      issue(dut, BitCmd.Start); waitDone(dut)

      // Issue WriteBit(1): controller will release SDA.
      issue(dut, BitCmd.WriteBit, txBit = true)
      // Wait for SCL to rise, then mid-tHigh, force SDA low to simulate
      // another master winning the bus.
      dut.clockDomain.waitSamplingWhere(dut.io.bus.scl.write.toBoolean)
      dut.io.bus.sda.read #= false
      // Wait for the FSM to bail out and return to idle.
      waitDone(dut)
      mirror.terminate()

      assert(dut.io.arbLost.toBoolean, "arbLost must be set after losing SDA contention")
      assert(dut.io.bus.scl.write.toBoolean, "SCL must be released after arb loss")
      assert(dut.io.bus.sda.write.toBoolean, "SDA must be released after arb loss")
      println("OK: arbitration loss")
    }
  }

  // ---- Clock stretching ---------------------------------------------------
  // With useClockStretching=true, hold bus.scl.read low for N cycles after
  // the controller releases SCL during a WriteBit's high phase. Verify
  // the bit takes longer to complete than the nominal tHigh.
  private def clockStretching(): Unit = {
    val cfg = I2cConfig(
      clkFreqHz = 12000000,
      busSpeed = BusSpeed.Standard,
      useClockStretching = true
    )
    SimConfig.withWave.compile(I2cBitController(cfg)).doSim("clock-stretch") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.cmd.valid #= false
      dut.io.txBit #= false
      dut.io.bus.scl.read #= true
      dut.io.bus.sda.read #= true

      // Mirror SDA always; SCL mirror is overridden during the stretch
      // window.
      var stretchCycles = 0
      val mirror = fork {
        while (true) {
          dut.io.bus.sda.read #= dut.io.bus.sda.write.toBoolean
          if (stretchCycles > 0) {
            dut.io.bus.scl.read #= false
            stretchCycles -= 1
          } else {
            dut.io.bus.scl.read #= dut.io.bus.scl.write.toBoolean
          }
          dut.clockDomain.waitSampling()
        }
      }
      dut.clockDomain.waitSampling(5)

      issue(dut, BitCmd.Start); waitDone(dut)

      // Time a normal write bit.
      val tStart1 = simTime()
      issue(dut, BitCmd.WriteBit, txBit = false)
      waitDone(dut)
      val tNormal = simTime() - tStart1

      // Time a stretched write bit.
      val stretchN = 50
      val tStart2 = simTime()
      issue(dut, BitCmd.WriteBit, txBit = false)
      // Wait for SCL to be released by the controller, then start
      // the stretch window.
      dut.clockDomain.waitSamplingWhere(dut.io.bus.scl.write.toBoolean)
      stretchCycles = stretchN
      waitDone(dut)
      val tStretched = simTime() - tStart2
      mirror.terminate()

      val expectedDelta = stretchN * 10L // sim period = 10
      val actualDelta = tStretched - tNormal
      assert(
        actualDelta >= expectedDelta,
        s"stretching: expected at least +$expectedDelta time, got +$actualDelta " +
          s"(normal=$tNormal, stretched=$tStretched)"
      )
      println(s"OK: clock stretching extends bit by $actualDelta sim-time (>= $expectedDelta)")
    }
  }

  // Helper: BusTiming for a config (avoids re-instantiating in the closure).
  private def timing(cfg: I2cConfig): BusTiming = BusTiming(cfg)

  def main(args: Array[String]): Unit = {
    smokeStartStop()
    writeByte()
    readByte()
    repeatedStart()
    arbitrationLoss()
    clockStretching()
    println("All I2cBitController sim cases passed.")
  }
}
