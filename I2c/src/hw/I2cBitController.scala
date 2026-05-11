package i2c

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** One-bit-at-a-time I²C bus driver.
  *
  * `I2cBitController` is the only block in this project that touches
  * `I2cIo` directly. It owns SCL toggling, SDA setup/sample timing,
  * START/STOP framing edges, and arbitration detection. Everything
  * built on top of it (byte controller, address phase, ACK policy,
  * register file) speaks bytes, not bits.
  *
  * ==Command interface==
  * The bit controller is a [[BitCmd]] consumer. Each command produces
  * exactly one bus event:
  *
  *   - `Start`     : SDA falls while SCL is high (bus must be idle).
  *   - `RepStart`  : repeated START — bus is already inside a
  *                   transaction (SCL low), prep edges are produced
  *                   first, then the same fall as `Start`.
  *   - `Stop`      : SDA rises while SCL is high; honours `tBuf`
  *                   before the next `Start` is accepted.
  *   - `WriteBit`  : drive `txBit` for one full SCL pulse.
  *   - `ReadBit`   : release SDA, sample mid-`tHigh` into `rxBit`.
  *   - `Idle`      : explicit no-op synchronisation barrier.
  *
  * The byte controller (Step 5) is responsible for sequencing these
  * into address + data + ACK; the bit controller has no notion of
  * "which bit number am I" or "is this an ACK slot".
  *
  * ==FSM shape==
  * Every state follows the same idiom:
  *
  *   - `onEntry` produces an edge (drives one of `sclDrive` /
  *     `sdaDrive`) and loads `phaseCounter` with the dwell required
  *     before the *next* edge. The counter is therefore loaded with
  *     "time until exit", not "time since entry".
  *   - `whenIsActive` decrements the counter and transitions when it
  *     reaches zero. No bus changes happen mid-state (except the
  *     mid-`tHigh` sample/arb-check in `bitHighState`).
  *
  * Result: 9 states, every spec-required dwell (`tHdSta`, `tSuSta`,
  * `tSuSto`, `tBuf`, `tSuDat`, plus `tHigh`/`tLow` for the bit pulse)
  * is honoured exactly once, and any future maintainer can read the
  * states top-to-bottom as a sequence of (edge, dwell) pairs.
  *
  * ==Registered drivers==
  * SCL and SDA are driven from `Reg(Bool())` regs (`sclDrive`,
  * `sdaDrive`) rather than per-state combinational assignments. This
  * matters because consecutive states often need to *hold* the
  * previous state's drive value (e.g., after a Start, SDA stays low
  * while SCL transitions). Combinational drives would force every
  * state to re-assert every line every cycle; the registered pattern
  * lets each state touch only the lines it changes.
  *
  * ==Clock stretching==
  * When `cfg.useClockStretching` is `true`, `bitHighState` and
  * `stopSclRiseState` gate their countdown on `io.bus.scl.read`. A
  * target may pull SCL low at any time after we release it — the
  * counter pauses until the target lets go. The gating is a
  * Scala-time `if`, so the SCL sense path and gating logic disappear
  * entirely from the synthesised design when stretching is disabled.
  *
  * ==Arbitration==
  * During `WriteBit` of a `1` (released SDA), if `bus.sda.read` reads
  * back as `0`, another master is winning the bus. We immediately
  * release both lines, set `arbLost`, and return to idle. `arbLost`
  * is sticky until the next accepted command. This is the
  * spec-compliant behaviour and makes the controller safe to use on
  * a multi-master segment.
  *
  * @param cfg
  *   Compile-time configuration (clock frequency, bus speed,
  *   stretching flag). Drives the [[BusTiming]] table.
  */
object BitCmd extends SpinalEnum {
  val Idle, Start, RepStart, Stop, WriteBit, ReadBit = newElement()
}

case class I2cBitController(cfg: I2cConfig) extends Component {
  val timing = BusTiming(cfg)

  val io = new Bundle {
    val cmd = slave Stream BitCmd() // one bus event per fired command
    val txBit = in Bool () // value to drive on WriteBit
    val rxBit = out Bool () // last sampled SDA (ReadBit)
    val arbLost = out Bool () // sticky: we wrote 1, bus stayed 0
    val bus = master(I2cIo()) // open-drain SCL/SDA
  }

  // Phase counter: wide enough to hold the largest dwell we'll ever
  // load. The largest is tLow (or tHigh), bounded by the full bit
  // period = 4 * quarterPeriodCycles. The +1 keeps log2Up honest at
  // power-of-two boundaries.
  private val phaseCounterWidth = log2Up(cfg.quarterPeriodCycles * 4 + 1) bits
  val phaseCounter = Reg(UInt(phaseCounterWidth)) init (0)

  // Latched-on-fire copies of cmd-time inputs. The producer is free
  // to change io.txBit / io.cmd.payload after fire, so we snapshot.
  val txBitLatched = Reg(Bool()) init (True)
  val isRead = Reg(Bool()) init (False)

  // Registered SCL/SDA drives. True == "release" (open-drain pad
  // floats; external pull-up wins). Reset state is "bus idle".
  val sclDrive = Reg(Bool()) init (True)
  val sdaDrive = Reg(Bool()) init (True)

  io.bus.scl.write := sclDrive
  io.bus.sda.write := sdaDrive

  // Sampled bit and arbitration-lost flag. arbLostReg is sticky
  // until cleared on the next accepted command (see idleState).
  val rxBitReg = Reg(Bool()) init (False)
  val arbLostReg = Reg(Bool()) init (False)

  io.rxBit := rxBitReg
  io.arbLost := arbLostReg

  // Default ready -- only the idle state asserts True.
  io.cmd.ready := False

  val fsm = new StateMachine {

    // ---------------- Idle --------------------------------------------------
    // Bus rests in whatever state the previous command left it
    // (released after Stop / reset, SCL-low after a bit transfer or
    // a Start). The registered sclDrive/sdaDrive carry that state
    // unchanged through this state.
    val idleState: State = new State with EntryPoint {
      whenIsActive {
        io.cmd.ready := True

        when(io.cmd.fire) {
          // Snapshot inputs and clear the per-command arb flag.
          txBitLatched := io.txBit
          isRead := io.cmd.payload === BitCmd.ReadBit
          arbLostReg := False

          switch(io.cmd.payload) {
            is(BitCmd.Start) { goto(startState) }
            is(BitCmd.RepStart) { goto(repStartReleaseSdaState) }
            is(BitCmd.Stop) { goto(stopSdaLowState) }
            is(BitCmd.WriteBit) {
              // NOTE: SDA is assigned HERE (in idleState's switch),
              // not in bitLowState.onEntry, on purpose.
              //
              // bitLowState.onEntry runs on the same clock edge that
              // cmd.fire updates txBitLatched. Reg writes on that
              // edge see the OLD txBitLatched, so a Mux there would
              // drive last-command's value onto SDA. Setting sdaDrive
              // from the live io.txBit while we're still in idleState
              // dodges the lag.
              //
              // This asymmetry is error-prone -- a future change that
              // adds a new bit-shaped command, or "tidies" this back
              // into bitLowState.onEntry, will reintroduce the bug.
              // TODO(step-5+): revisit. Options include a dedicated
              // setup state, or a combinational cmd-payload shim that
              // states can read without the Reg-update lag.
              sdaDrive := io.txBit
              goto(bitLowState)
            }
            is(BitCmd.ReadBit) {
              // Same rationale as WriteBit above: release SDA here,
              // not in bitLowState.onEntry.
              sdaDrive := True
              goto(bitLowState)
            }
            is(BitCmd.Idle) { /* no-op: stay in idleState */ }
          }
        }
      }
    }

    // ---------------- Start --------------------------------------------------
    // The Start condition is "SDA falls while SCL is high". On entry
    // SDA falls; we then dwell tHdSta before pulling SCL low (which
    // marks "we are now inside a transaction" for any other master
    // listening). After this state the bus rests at SCL=low,
    // SDA=low -- the canonical mid-transaction resting position.
    val startState = new State {
      onEntry {
        sdaDrive := False
        phaseCounter := timing.tHdSta
      }
      whenIsActive {
        when(phaseCounter === 0) {
          sclDrive := False // we are now inside a transaction
          goto(idleState)
        } otherwise {
          phaseCounter := phaseCounter - 1
        }
      }
    }

    // ---------------- Repeated Start -----------------------------------------
    // RepStart is entered with the bus mid-transaction (SCL low,
    // SDA = whatever the last bit was). Two prep edges are needed
    // before we can produce the actual Start fall: release SDA
    // (so the SDA fall is well-defined), then release SCL (so the
    // SDA fall happens while SCL is high). The shared startState
    // tail then produces the Start condition and drops SCL.

    val repStartReleaseSdaState = new State {
      onEntry {
        sdaDrive := True // release SDA -- might already be high
        phaseCounter := timing.tSuDat // SDA must be stable before SCL rises
      }
      whenIsActive {
        when(phaseCounter === 0) {
          goto(repStartReleaseSclState)
        } otherwise {
          phaseCounter := phaseCounter - 1
        }
      }
    }

    val repStartReleaseSclState = new State {
      onEntry {
        sclDrive := True // release SCL -- bus heading toward (high, high)
        phaseCounter := timing.tSuSta // SCL stable high before SDA falls
      }
      whenIsActive {
        // If clock stretching is enabled, a target can hold SCL low
        // after we release it. Pause the countdown until SCL has
        // actually risen. When disabled the gate is a constant True
        // and synthesises away.
        val mayCount =
          if (cfg.useClockStretching) io.bus.scl.read else True

        when(phaseCounter === 0) {
          goto(startState) // shared Start tail produces the SDA fall
        } elsewhen (mayCount) {
          phaseCounter := phaseCounter - 1
        }
      }
    }

    // ---------------- Stop ---------------------------------------------------
    // The Stop condition is "SDA rises while SCL is high". From a
    // mid-transaction resting state (SCL low, SDA = anything), we
    // must first force SDA low (so the next state's SCL release
    // doesn't accidentally produce a Stop edge), then release SCL,
    // then release SDA -- that last edge is the Stop. After Stop,
    // tBuf of bus-free time before the next Start is allowed.

    val stopSdaLowState = new State {
      onEntry {
        // Force SDA low so the upcoming SCL release produces no
        // premature Stop edge. No-op if SDA was already low.
        sdaDrive := False
        phaseCounter := timing.tSuDat // SDA stable before SCL rises
      }
      whenIsActive {
        when(phaseCounter === 0) {
          goto(stopSclRiseState)
        } otherwise {
          phaseCounter := phaseCounter - 1
        }
      }
    }

    val stopSclRiseState = new State {
      onEntry {
        sclDrive := True
        phaseCounter := timing.tSuSto // SCL stable high before Stop edge
      }
      whenIsActive {
        // Same clock-stretching rationale as repStartReleaseSclState.
        val mayCount =
          if (cfg.useClockStretching) io.bus.scl.read else True

        when(phaseCounter === 0) {
          goto(stopSdaRiseState)
        } elsewhen (mayCount) {
          phaseCounter := phaseCounter - 1
        }
      }
    }

    val stopSdaRiseState = new State {
      onEntry {
        sdaDrive := True // <-- the Stop edge
        phaseCounter := timing.tBuf
      }
      whenIsActive {
        when(phaseCounter === 0) {
          goto(idleState)
        } otherwise {
          phaseCounter := phaseCounter - 1
        }
      }
    }

    // ---------------- Bit pulse (shared by Read and Write) ------------------
    // Read and Write differ only in what SDA does:
    //   - Write: drive txBitLatched onto SDA for the whole pulse.
    //   - Read:  release SDA so the target may drive it; sample
    //            mid-tHigh into rxBitReg.
    // The SCL waveform is identical for both. Mid-tHigh, a Write
    // also performs an arbitration check.

    val bitLowState = new State {
      onEntry {
        // Intentionally does NOT touch sdaDrive -- that's the
        // responsibility of idleState's WriteBit/ReadBit arms (see
        // the long comment there). Folding the SDA assignment back
        // into this onEntry would reintroduce the txBitLatched lag
        // bug. TODO(step-5+): consider restructuring so onEntry can
        // own the SDA assignment uniformly across all states.
        //
        // tLow >> tSuDat in every realistic (clock, speed) combo, so
        // a single tLow dwell trivially satisfies the data-setup
        // constraint at the SCL rise that follows.
        phaseCounter := timing.tLow
      }
      whenIsActive {
        when(phaseCounter === 0) {
          goto(bitHighState)
        } otherwise {
          phaseCounter := phaseCounter - 1
        }
      }
    }

    val bitHighState = new State {
      onEntry {
        sclDrive := True // release SCL -> pull-up brings it high
        phaseCounter := timing.tHigh
      }
      whenIsActive {
        // Sample / arb-check at the midpoint of the high phase.
        when(phaseCounter === (timing.tHigh / 2)) {
          when(isRead) {
            rxBitReg := io.bus.sda.read
          } otherwise {
            // Arbitration: we tried to release SDA (write 1) but the
            // bus reads 0 -- another master is pulling. Spec-compliant
            // recovery: release both lines and abort to idle. The
            // arbLostReg latch survives until the next cmd.fire.
            when(txBitLatched && !io.bus.sda.read) {
              arbLostReg := True
              sclDrive := True // release SCL immediately
              sdaDrive := True // (we were already trying to release SDA)
              goto(idleState)
            }
          }
        }

        // Stretching: a target may hold SCL low even after we release
        // it; pause the high-phase countdown until SCL has risen.
        // Synth-time constant when stretching is disabled.
        val mayCount =
          if (cfg.useClockStretching) io.bus.scl.read else True

        when(phaseCounter === 0) {
          sclDrive := False // pull SCL low -> next bit's bitLowState rests here
          goto(idleState)
        } elsewhen (mayCount && phaseCounter =/= 0) {
          phaseCounter := phaseCounter - 1
        }
      }
    }
  }
}
