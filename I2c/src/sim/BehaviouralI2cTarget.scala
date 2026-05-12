package i2c

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** Sim-only behavioural I²C target.
  *
  * Lives alongside [[I2cIoBus]] in `src/sim/`. Both are sim-only
  * glue that lets us stand a controller up in a closed loop without
  * reaching for an external chip model. The controller drives one
  * end of the wired-AND bus, this Component drives the other.
  *
  * Role
  * ----
  * Passive bus participant. It snoops SCL / SDA, decodes the I²C
  * link-layer events the controller produces, and drives SDA low
  * at the right moments to ACK / NAK and to shift read data back.
  * It never initiates a transaction of its own — there's no master
  * arbitration logic in here.
  *
  * Events it must decode (filled in by future implementation)
  * ----------------------------------------------------------
  *   - Start condition (SDA falling while SCL is high).
  *   - Stop condition  (SDA rising while SCL is high).
  *   - Repeated start  (a Start while a transaction is already in
  *     progress; from the wire's POV identical to a plain Start,
  *     but the FSM stays "addressed").
  *   - Address byte    (first 8 bits after a Start; high 7 are the
  *     7-bit address, low bit is R/W̅: 0 = master writes, 1 =
  *     master reads).
  *   - Write-data ACK / NAK policy (per `cfg.ackPattern`): which
  *     byte indices to NAK on writes, useful for exercising the
  *     controller's mustTerminate / mid-byte error handling.
  *   - Read-data shifts (drive successive bits of the configured
  *     byte source onto SDA during the master's ReadBit slots).
  *
  * Configuration knobs
  * -------------------
  * Provided up-front so the test scaffold can call out the API
  * surface even before the body is implemented:
  *
  *   - `targetAddress` — the 7-bit address this target answers to.
  *     Address mismatches are silently ignored (target releases
  *     the bus, controller sees NAK because nothing pulls SDA).
  *   - `ackPattern` — per-byte ACK policy on writes. Index `i`
  *     `true` = ACK byte `i`, `false` = NAK. Empty pattern means
  *     "ACK every byte". Wraps mod length on long writes.
  *   - `regFileInit` — sparse initial register-file contents,
  *     `address -> value`. Reads of unwritten addresses return
  *     0xFF (open-drain idle).
  *   - `stretchCycles` — number of clock cycles to hold SCL low
  *     after the address byte's ACK, to exercise the controller's
  *     clock-stretch tolerance. 0 disables.
  *
  * Out of scope
  * ------------
  *   - 10-bit addressing.
  *   - SMBus PEC / packet error checking.
  *   - General call address (0x00).
  *   - Multi-master arbitration scenarios beyond passive
  *     observation. (Use a dedicated second-driver fork in the sim
  *     itself for arbitration tests.)
  */
case class BehaviouralI2cTargetConfig(
    targetAddress: Int = 0x50,
    ackPattern: Seq[Boolean] = Seq.empty,
    regFileInit: Map[Int, Int] = Map.empty,
    stretchCycles: Int = 0
)

/** Sim-only [[Component]] that exposes a master-side [[I2cIo]] for
  * connection through an [[I2cIoBus]] to the controller under test.
  *
  * The body is currently a release-the-bus placeholder so the
  * scaffold compiles. The actual decode FSM lands with the test
  * bodies in [[I2cByteControllerSim]].
  */
class BehaviouralI2cTarget(cfg: I2cConfig, tCfg: BehaviouralI2cTargetConfig) extends Component {
  val io = new Bundle {
    val bus = master(I2cIo())
  }

  val sclR = RegNext(io.bus.scl.read) init (True)
  val sdaR = RegNext(io.bus.sda.read) init (True)

  val sclRise = !sclR && io.bus.scl.read
  val sclFall = sclR && !io.bus.scl.read
  val sdaRise = !sdaR && io.bus.sda.read
  val sdaFall = sdaR && !io.bus.sda.read

  val startCond = sdaFall && io.bus.scl.read
  val stopCond = sdaRise && io.bus.scl.read

  val inAckSlot = Reg(Bool()) init (False)
  val direction = Reg(Bool()) init (False)
  val isRead = Reg(Bool()) init (False)

  val readByte = Reg(Bits(8 bits)) init (0)
  val shiftReg = Reg(Bits(8 bits)) init (0)
  val byteIdx = Reg(UInt(8 bits)) init (0)

  val regAddr = Reg(UInt(8 bits)) init (0)
  val regFile = Vec(Reg(Bits(8 bits)), 256)

  for ((el, i) <- regFile.zipWithIndex) {
    el := tCfg.regFileInit.getOrElse(i, 0x55)
  }

  val bitCount = Reg(UInt(4 bits)) init (0)
  val inReadAck = Reg(Bool()) init (False)

  val ackVec =
    if (tCfg.ackPattern.isEmpty)
      Vec(True, 1)
    else
      Vec(tCfg.ackPattern.map(Bool(_)))

  val ackThisByte = ackVec(byteIdx.resize(log2Up(ackVec.length)) % ackVec.length)

  val sdaDrive = Reg(Bool()) init (True)
  io.bus.scl.write := True
  io.bus.sda.write := sdaDrive

  val fsm = new StateMachine {
    val idleState: State = new State with EntryPoint {
      whenIsActive {
        when(startCond) {
          goto(addrState)
        }
      }
    }

    val addrState: State = new State {
      whenIsActive {
        when(sclRise) {
          shiftReg := shiftReg(6 downto 0) ## io.bus.sda.read
          bitCount := bitCount + 1
        }

        when(bitCount === 8 && sclFall) {
          val matched = shiftReg(7 downto 1) === tCfg.targetAddress
          val isRead = shiftReg.lsb

          when(matched) {
            sdaDrive := False // pull SDA low for the ACK slot
            direction := isRead
            inAckSlot := True
          } otherwise {
            sdaDrive := True // NAK
            goto(idleState)
          }
        } elsewhen (inAckSlot && sclFall) {
          inAckSlot := False
          bitCount := 0
          when(direction) {
            // Read: this 9th SCL fall is also the slave's setup edge
            // for data bit 0. Drive MSB of readByte now, pre-shift,
            // and start readTxState already accounting for the bit
            // we just emitted (bitCount := 1).
            sdaDrive := regFile(regAddr)(7)
            readByte := regFile(regAddr)(6 downto 0) ## False
            bitCount := 1
            goto(readTxState)
          } otherwise {
            // Write: just release; writeRxState will receive bits.
            sdaDrive := True
            goto(writeRxState)
          }
        }
      }
    }

    val writeRxState = new State {
      whenIsActive {
        when(stopCond) {
          bitCount := 0
          byteIdx := 0
          inAckSlot := False
          goto(idleState)
        } elsewhen (startCond) {
          bitCount := 0
          byteIdx := 0
          inAckSlot := False
          goto(addrState)
        } otherwise {
          when(sclRise && bitCount =/= 8) {
            shiftReg := shiftReg(6 downto 0) ## io.bus.sda.read
            bitCount := bitCount + 1
          }

          when(bitCount === 8 && sclFall && !inAckSlot) {
            sdaDrive := !ackThisByte
            inAckSlot := True
          }

          when(inAckSlot && sclFall) {
            sdaDrive := True
            inAckSlot := False
            bitCount := 0
            byteIdx := byteIdx + 1
          }
        }
      }
    }

    val readTxState = new State {
      whenIsActive {
        // Start/Stop traps -- mirror writeRxState. Without these the
        // slave deadlocks on the controller's Stop because nothing
        // returns it to idle.
        when(stopCond) {
          bitCount := 0
          byteIdx := 0
          inReadAck := False
          sdaDrive := True
          goto(idleState)
        } elsewhen (startCond) {
          bitCount := 0
          byteIdx := 0
          inReadAck := False
          sdaDrive := True
          goto(addrState)
        } otherwise {
          // Drive bits 6..0 on falls 10..16. addrState pre-drove bit
          // 7 on the 9th fall and handed us bitCount := 1, so we drive
          // 7 more bits to land at bitCount === 8 after the 16th fall.
          when(sclFall && bitCount < 8) {
            sdaDrive := readByte(7) // MSB-first within the remaining bits
            readByte := readByte(6 downto 0) ## False
            bitCount := bitCount + 1
          }

          // Once all 8 data bits are out, hold SDA released so the
          // master owns the ACK slot. Level-sensitive on purpose:
          // re-asserts every cycle, so we don't have to worry about
          // exactly which edge releases the line.
          when(bitCount === 8) {
            sdaDrive := True
          }

          // The ACK slot spans two SCL rises:
          //   - 17th rise: master samples our LSB. Slave must NOT
          //     react -- the byte's data phase hasn't ended yet from
          //     the master's POV. We just remember "I've seen the LSB
          //     sample" by setting inReadAck.
          //   - 18th rise: master samples its own ACK/NAK level. This
          //     is the one we care about (handled below).
          // Without this two-step disambiguation, both rises would
          // trip the same `bitCount === 8 && sclRise` condition and
          // we'd react on the wrong edge.
          when(bitCount === 8 && sclRise && !inReadAck) {
            inReadAck := True
          }

          // 18th rise: master ACK/NAK sample.
          //   - SDA low  -> master ACKed -> "give me another byte".
          //     Reload readByte from the next regFile entry and reset
          //     bitCount to 0. We do NOT touch sdaDrive here -- the
          //     18th rise is the master's slot, the slave releases.
          //     The next sclFall (the bit-7 setup edge for the new
          //     byte) is when the body's `bitCount < 8` branch will
          //     fire and drive bit 7 from the freshly-loaded readByte.
          //   - SDA high -> master NAKed -> end of read. Do nothing;
          //     the Stop or RepStart that follows will trip the trap
          //     above and pull us out of readTxState.
          // Either way, clear inReadAck so the next byte (if any)
          // starts the two-step disambiguation fresh.
          when(bitCount === 8 && sclRise && inReadAck) {
            inReadAck := False
            when(!io.bus.sda.read) {
              readByte := regFile(regAddr + 1)
              bitCount := 0
              regAddr := regAddr + 1
            }
          }
        }
      }
    }
  }
}
