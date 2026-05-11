package i2c

import spinal.core._
import spinal.lib._

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

  // Placeholder: release both lines so the wired-AND lets the
  // controller drive freely. Replace with a real decode/drive FSM
  // when the test bodies are filled in.
  io.bus.scl.write := True
  io.bus.sda.write := True

  // TODO: implement target FSM
  //   - Start/Stop detection.
  //   - Address-byte capture and address match.
  //   - Per-byte ACK/NAK driven from `tCfg.ackPattern`.
  //   - Register file read/write per `tCfg.regFileInit`.
  //   - Optional clock stretch driven by `tCfg.stretchCycles`.
}
