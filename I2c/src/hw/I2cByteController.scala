package i2c

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

// ============================================================================
//  I2cByteController — byte-level I²C master FSM
// ============================================================================
//
// `I2cByteController` is the second layer of the I²C master stack:
//
//     +------------------------------------------------+
//     |  SW driver (future MMIO wrapper, Step 7)       |
//     |   speaks ByteCmd / ByteRsp                     |
//     +------------------------------------------------+
//                          │  Stream[ByteCmd]
//                          │  Stream[ByteRsp]
//                          ▼
//     +------------------------------------------------+
//     |  I2cByteController  (this file)                |
//     |   sequences address + data + ACK as bits       |
//     +------------------------------------------------+
//                          │  Stream[BitCmd] + txBit
//                          │  rxBit, arbLost
//                          ▼
//     +------------------------------------------------+
//     |  I2cBitController                              |
//     |   owns SCL/SDA timing, START/STOP framing,     |
//     |   arbitration detection                        |
//     +------------------------------------------------+
//                          │  I2cIo (open-drain)
//                          ▼
//                       The bus
//
// Below us, `I2cBitController` translates one [[BitCmd]] into one bus
// event (Start, RepStart, Stop, WriteBit, ReadBit). Above us, a SW
// driver speaks the byte-level protocol: "address this peripheral",
// "write this byte", "read another byte", "stop". This file is the
// glue.
//
// ==Why a separate byte layer?==
// Splitting bytes from bits keeps the bit controller laser-focused on
// timing and arbitration (where bugs are subtle and silicon-specific)
// and lets the byte FSM concentrate on protocol structure (start →
// addr → data… → stop). It also makes both layers individually
// simulatable.
//
// ==Stream protocol (upstream contract)==
// One `ByteCmd` consumed produces exactly one `ByteRsp`. That
// invariant is the controller's load-bearing promise to the SW
// driver: every command — including misuse — gets a response, so the
// driver never has to wait on a phantom completion. The mapping is:
//
//   AddrWrite, AddrRead   → one rsp (target ACK/NAK in `ackIn`)
//   WriteData             → one rsp (target ACK/NAK in `ackIn`)
//   ReadData              → one rsp (8 received bits in `data`;
//                                    we drove ACK/NAK from `ackOut`)
//   RepStart              → one rsp (ACK/NAK on the new addr byte)
//   Stop                  → one rsp (status only; no `ackIn` semantic)
//   <out-of-sequence cmd> → one rsp with `status = InvalidSeq`
//
// ==Legal command sequences==
// At a glance:
//
//   IDLE      :  AddrWrite | AddrRead
//   ↓
//   STARTED   :  WriteData (when isRead=False)
//                ReadData  (when isRead=True)
//                RepStart  (re-aim direction)
//                Stop
//   ↓ (NAK / arb-loss / SW NAK on read)
//   WEDGED    :  RepStart | Stop                   ← only terminators
//   ↓ (Stop)
//   IDLE
//
// Anything else at any stage is reported as `InvalidSeq` and the bus
// is left untouched. The controller never decides policy on the
// driver's behalf — no auto-Stop, no command swallow.
//
// ==Hub-and-spoke FSM topology==
//   * Two "hub" states own the upstream handshake:
//       `idleState`         — no transaction in flight
//       `waitNextCmdState`  — transaction in flight, awaiting next byte op
//   * One "report" hub serves SW-misuse paths:
//       `errorRspState`     — emits one InvalidSeq rsp without bus traffic
//   * Every bus operation is a three-state spoke:
//       <op>IssueState     → present a BitCmd, wait for `cmd.fire`
//       <op>WaitDoneState  → wait for `cmd.ready` (== bit ctrl returned to idle)
//       <op>RspState       → emit one ByteRsp, then go back to a hub
//
// State identity (idle vs wait hub) encodes "transaction in flight",
// so we don't gate on a separate `hasStarted` register.
//
// ==Three-phase pattern, in detail==
// The bit controller's contract is fire-and-forget with delayed
// completion: `cmd.fire` enqueues a bit-level operation, and `cmd
// .ready` only re-asserts when the op is finished. We therefore can't
// collapse Issue and Wait into a single state — we need one cycle to
// observe `fire` and a separate window to observe `ready`. Lifting
// the response emission into its own state (RspState) gives us a
// clean point to:
//   - latch `mustTerminate` on NAK / arb-loss
//   - update direction / counters
//   - block on the upper layer consuming the rsp before moving on
// without entangling those concerns with bit-level handshaking.
//
// ==Latch-on-fire discipline==
// `io.cmd.data` and `io.cmd.ackOut` are stream payloads — valid only
// the cycle the cmd fires (the upper layer is free to drop them
// next cycle). Anything we'll need later (the address/payload byte,
// the read-end NAK choice) is copied into a Reg in the cycle the
// cmd fires. The same rule applies to bit-level inputs: `txBit` is
// driven by the active state and the bit ctrl latches it on `cmd
// .fire`, so we only need to drive it during the Issue state.
//
// ==Arbitration loss handling==
// `bitCtrl.io.arbLost` goes high when the bit controller detects
// another master winning a bit it tried to drive. It is *sticky*
// inside the bit controller until the next accepted command. Every
// `*WaitDoneState` and `*WaitState` snapshots it into `arbLostReg`
// the cycle it appears, and short-circuits to the corresponding
// RspState — no point clocking out bits we don't own. The rsp
// surfaces `status = ArbLost` and sets `mustTerminate := True`, so
// the SW driver's next legal move is `Stop` (or `RepStart`, which
// re-arbitrates).
//
// ==Open issues / not implemented here==
//   - Multi-master clock-stretching coordination: the bit controller
//     handles SCL stretching by the target; multi-master collision
//     beyond simple arb-loss is out of scope.
//   - SMBus / PMBus extras (PEC, alert, address resolution): not
//     present; would slot in as additional ByteCmdKind entries.
//   - 10-bit addressing: not present; SW driver would have to issue
//     it as a manual sequence of WriteData ops.
// ============================================================================

/** Opcode for a [[ByteCmd]].
  *
  * The kind is the controller's primary discriminator. It both selects
  * the FSM path and (combined with current `isRead`/`mustTerminate`
  * regime) decides whether the command is legal at all.
  *
  *   - `AddrWrite` : Start condition + 7-bit address with `R/W=0`.
  *                   `data` = address byte (lsb is forced to 0; see
  *                   [[ByteCmd]]). `ackOut` is ignored. Legal only
  *                   from idle.
  *   - `AddrRead`  : Start condition + 7-bit address with `R/W=1`.
  *                   Same shape as AddrWrite but lsb is forced to 1.
  *                   Legal only from idle.
  *   - `WriteData` : Shift `data` MSB-first onto SDA, sample target
  *                   ACK on the 9th clock. Legal only after a write
  *                   address has landed (isRead=False) and the
  *                   transaction is not wedged.
  *   - `ReadData`  : Receive 8 bits into `data`, then drive ACK
  *                   (continue) or NAK (end of read) per `ackOut`
  *                   on the 9th clock. Legal only after a read
  *                   address has landed (isRead=True) and the
  *                   transaction is not wedged.
  *   - `RepStart`  : Repeated start. `data`'s lsb sets the new
  *                   direction. Legal in either regime — it is the
  *                   only way (besides `Stop`) to recover from a
  *                   wedged transaction without releasing the bus.
  *   - `Stop`      : Terminate the transaction and release the bus.
  *                   Legal in either regime. Always returns the
  *                   controller to the idle hub.
  */
object ByteCmdKind extends SpinalEnum {
  val AddrWrite, AddrRead, WriteData, ReadData, RepStart, Stop = newElement()
}

/** Single command from the SW driver into the byte controller.
  *
  * The bundle is consumed as a stream payload, so all fields are
  * valid only the cycle `io.cmd.fire`. Anything the FSM needs later
  * (the address/payload byte, the read-end NAK choice) is latched
  * into a Reg that same cycle.
  *
  * @param kind   See [[ByteCmdKind]].
  * @param data   Multi-purpose payload:
  *               - On `AddrWrite` / `AddrRead` : 7-bit address in
  *                 bits[7:1]; bit[0] is overwritten by the controller
  *                 to match `kind` (so a SW driver passing junk in
  *                 the lsb cannot desync the wire from `isRead`).
  *               - On `RepStart` : 7-bit address in bits[7:1] and
  *                 the *real* R/W bit in bit[0] — RepStart is the
  *                 one place the lsb is honoured directly, because
  *                 there's no separate AddrWrite/AddrRead kind to
  *                 disambiguate direction.
  *               - On `WriteData` : the byte to clock onto SDA.
  *               - On `ReadData` / `Stop` : ignored (don't-care).
  * @param ackOut Read-end signal, only meaningful on `ReadData`:
  *               `False` = ACK (target should send another byte),
  *               `True`  = NAK (we're done; target releases SDA).
  *               Driving NAK on a read transitions the controller
  *               into the wedged regime, forcing the SW driver to
  *               follow with `Stop` or `RepStart`.
  */
case class ByteCmd() extends Bundle {
  val kind = ByteCmdKind()
  val data = Bits(8 bits)
  val ackOut = Bool()
}

/** Outcome of a single [[ByteCmd]].
  *
  *   - `Ok`         : Bus operation completed without controller-side
  *                    incident. The SW driver still needs to inspect
  *                    `ackIn` on writes to know whether the target
  *                    accepted the byte.
  *   - `ArbLost`    : The bit controller detected another master
  *                    winning a bit we tried to drive. The byte was
  *                    abandoned. The transaction is forced into the
  *                    wedged regime — only `Stop` or `RepStart` will
  *                    be accepted next.
  *   - `InvalidSeq` : The SW driver issued a command that is not legal
  *                    in the current regime (e.g. `WriteData` from
  *                    idle, `AddrWrite` while a transaction is
  *                    already in flight, or anything other than
  *                    `Stop`/`RepStart` while wedged). The bus was
  *                    NOT touched. The controller's regime did not
  *                    change. The driver is expected to inspect
  *                    `status`, identify the bug, and recover (which
  *                    typically means issuing `Stop` if a transaction
  *                    is still in flight).
  */
object ByteRspStatus extends SpinalEnum {
  val Ok, ArbLost, InvalidSeq = newElement()
}

/** Single response from the byte controller back to the SW driver.
  *
  * Field meaningfulness is gated on `status` and on the originating
  * command kind:
  *
  *   status        kind originating it          data         ackIn
  *   Ok            AddrWrite/AddrRead/RepStart  0            target ACK/NAK
  *   Ok            WriteData                    0            target ACK/NAK
  *   Ok            ReadData                     received     False (we drove ACK)
  *   Ok            Stop                         0            False
  *   ArbLost       any bus op                   0 or partial don't-care
  *   InvalidSeq    any (refused)                0            True (NAK-ish)
  *
  * @param data   Bytes clocked in from the bus on a `ReadData` rsp;
  *               `0` for everything else.
  * @param ackIn  Target's ACK on the 9th SCL clock: `False` = ACK
  *               (target accepted), `True` = NAK (target refused).
  *               Only meaningful when `status == Ok` and the
  *               originating kind was a write-direction op (Addr*,
  *               WriteData, RepStart). NAK forces the wedged regime
  *               for the next command.
  * @param status See [[ByteRspStatus]]. The canonical signal —
  *               always trustworthy.
  */
case class ByteRsp() extends Bundle {
  val data = Bits(8 bits)
  val ackIn = Bool()
  val status = ByteRspStatus()
}

/** Byte-level I²C master FSM. See top-of-file comment for design
  * rationale, command stream semantics, and the hub-and-spoke
  * topology overview.
  *
  * @param cfg
  *   Compile-time configuration shared with [[I2cBitController]]
  *   (clock frequency, bus speed, clock-stretching flag, etc.).
  *   Only forwarded — no fields are read directly by the byte FSM.
  */
case class I2cByteController(cfg: I2cConfig) extends Component {

  // -------------------------------------------------------------------------
  // I/O
  // -------------------------------------------------------------------------
  // `cmd` and `rsp` are independent streams; the SW driver may stage
  // a follow-on command before consuming the previous response, but
  // back-to-back issuance is bounded by the rsp arrival of the
  // previous op (we hold `cmd.ready := False` outside the hubs).
  val io = new Bundle {
    val cmd = slave Stream ByteCmd()
    val rsp = master Stream ByteRsp()
    val bus = master(I2cIo())
  }

  // -------------------------------------------------------------------------
  // Bit-controller instance
  // -------------------------------------------------------------------------
  // Bit ctrl contract recap (see I2cBitController.scala for full
  // detail):
  //
  //   - `cmd` is a Stream[BitCmd]; one bus event per fired command.
  //   - `cmd.ready` is True only when the bit ctrl is in *its* idle
  //     state. It drops while the bit ctrl is mid-event and rises
  //     again on completion. We use `cmd.fire` ("we just enqueued
  //     a new op") and `cmd.ready` ("the previous op is done") as
  //     the two halves of every Issue/Wait pair.
  //   - `txBit` must be driven during the Issue state; the bit ctrl
  //     latches it on `cmd.fire` and we don't have to hold it.
  //   - `rxBit` is the registered output of the last sampled SDA;
  //     it is valid from the cycle `cmd.ready` re-asserts after a
  //     ReadBit until the next bit op completes.
  //   - `arbLost` is sticky inside the bit ctrl until the next
  //     accepted command. We snapshot it into our own `arbLostReg`
  //     so it survives across our state transitions and surfaces
  //     in the rsp.
  val bitCtrl = I2cBitController(cfg)
  bitCtrl.io.bus <> io.bus

  // -------------------------------------------------------------------------
  // Per-transaction state
  // -------------------------------------------------------------------------

  /** Direction of the current transaction, latched at AddrWrite/
    * AddrRead time and re-latched on RepStart. Drives which data
    * commands `waitNextCmdState` accepts (WriteData when False,
    * ReadData when True). Cleared only implicitly by the next
    * AddrWrite/AddrRead (we never reset it on Stop because state
    * identity already prevents stale reads from idle).
    */
  val isRead = Reg(Bool()) init (False)

  /** Wedged-regime flag. Set by:
    *   - any rsp that observed a target NAK (write direction),
    *   - any rsp that observed arb-loss,
    *   - the read rsp when the SW driver drove a NAK end-of-read
    *     (`ackOutReg` was True).
    * Cleared by:
    *   - `stopRspState.onExit` (transaction over),
    *   - the RepStart arm of `waitNextCmdState` (RepStart re-aims
    *     and is itself a recovery action).
    * Read by `waitNextCmdState` to gate the legal command set down
    * to `Stop` / `RepStart` only.
    */
  val mustTerminate = Reg(Bool()) init (False)

  /** Sticky arb-loss snapshot for the current bus operation. Set in
    * any `*Wait*` state that observes `bitCtrl.io.arbLost`, and
    * (per the short-circuit pattern below) immediately diverts the
    * FSM to the corresponding RspState. Surfaced in
    * `rsp.status == ArbLost`. Cleared at the start of each new
    * transaction (idle's AddrWrite/AddrRead arms, RepStart, and
    * Stop's onExit).
    */
  val arbLostReg = Reg(Bool()) init (False)

  /** Multi-purpose 8-bit shift register. Three roles, never
    * concurrent:
    *   1. Outbound address byte during S/Sr → write pipeline.
    *   2. Outbound data byte during WriteData → write pipeline.
    *   3. Inbound shift register during ReadData (filled LSB-last).
    *
    * Latched on `io.cmd.fire` for outbound use (idle, RepStart arm,
    * WriteData arm). Cleared to 0 on idle's data ops, but the more
    * important reset is "loaded fresh at every cmd.fire that needs
    * it" — we never carry stale outbound bytes across transactions.
    */
  val dataByte = Reg(Bits(8 bits)) init (0)

  /** Bit position counter for the 8-bit shift loops. 0..7 inclusive
    * (4 bits wide for headroom against off-by-one). Re-zeroed at the
    * start of each shift loop and again before entering the ACK
    * phase, so neither the shift nor the ACK depends on prior
    * residual values.
    */
  val bitCounter = Reg(UInt(4 bits)) init (0)

  /** Latched target ACK from the 9th SCL pulse on a write op
    * (WriteBit ACK phase samples SDA via `BitCmd.ReadBit`). 0 = ACK,
    * 1 = NAK. Surfaced in the next rsp's `ackIn`. Written by
    * `writeAckWaitState`. Read by `writeRspState`. Carries no
    * meaning on read transactions.
    */
  val ackInReg = Reg(Bool()) init (False)

  /** Latched read-end NAK choice from the SW driver, captured at the
    * cycle `io.cmd.fire` for a `ReadData` op. Drives `txBit` during
    * the read ACK phase (`readAckIssueState`). Read again by
    * `readRspState` to decide whether to set `mustTerminate` (NAK
    * means "this was the last byte").
    */
  val ackOutReg = Reg(Bool()) init (False)

  // -------------------------------------------------------------------------
  // Error reporting bookkeeping
  // -------------------------------------------------------------------------
  // When the SW driver issues an out-of-sequence command we refuse
  // to touch the bus and emit a single ByteRsp through the shared
  // `errorRspState`. Two regs decide what to put in the rsp and
  // where to land afterwards:
  //
  //   - `errorStatus` latches *which* error to report. Today this is
  //     always InvalidSeq (the only non-bus error path), but the
  //     reg exists to keep the door open for future status codes
  //     reachable from non-rsp states without adding more states.
  //   - `errorReturnToIdle` picks where to land:
  //       True  → no transaction in flight (e.g. AddrRead before any
  //               AddrWrite was issued from idle); go back to
  //               idleState clean.
  //       False → a transaction is in flight; go back to
  //               waitNextCmdState with mustTerminate untouched, so
  //               the driver's next command must still be a
  //               terminator.
  val errorStatus = Reg(ByteRspStatus()) init (ByteRspStatus.Ok)
  val errorReturnToIdle = Reg(Bool()) init (False)

  // -------------------------------------------------------------------------
  // Component-level defaults
  // -------------------------------------------------------------------------
  // Every state overrides only what it actively needs; everything
  // else falls back to these. Without defaults, Spinal would flag
  // combinational signals as undriven on any state path that doesn't
  // touch them.
  //
  // Bit controller command channel: not asserting valid means
  // "nothing to do right now"; `Idle` is a safe payload that the
  // bit controller also ignores while valid is False. `txBit := True`
  // is "release SDA" (open-drain idle).
  bitCtrl.io.cmd.valid := False
  bitCtrl.io.cmd.payload := BitCmd.Idle
  bitCtrl.io.txBit := True

  // Upstream command/response handshake. Only `idleState` and
  // `waitNextCmdState` raise `cmd.ready`; only the `*RspState`
  // members (and `errorRspState`) raise `rsp.valid`. The default
  // `ackIn := True` is NAK-ish, which is the safe interpretation if
  // a downstream consumer reads the field on a rsp where it isn't
  // meaningful (Stop, ReadData, InvalidSeq).
  io.cmd.ready := False
  io.rsp.valid := False
  io.rsp.payload.data := 0
  io.rsp.payload.ackIn := True
  io.rsp.payload.status := ByteRspStatus.Ok

  // -------------------------------------------------------------------------
  // The FSM
  // -------------------------------------------------------------------------
  val fsm = new StateMachine {

    // ======================================================================
    //  Hub: idleState
    // ======================================================================
    //
    // No transaction in flight. The bus is released. The only legal
    // commands are `AddrWrite` and `AddrRead` — anything else is a
    // SW protocol violation and routes through `errorRspState` with
    // `errorReturnToIdle = True` (we come right back here).
    //
    // On a legal address command we latch the address byte (with the
    // R/W bit forced to match `kind`, see below), zero the bit
    // counter, clear the arb-loss snapshot from any prior aborted
    // transaction, and dive into the start-condition spoke.
    //
    // ----- direction-bit forcing -----
    // We mask `data.lsb` to match `kind` when latching the outbound
    // byte so that a SW driver passing junk in bit 0 cannot end up
    // shifting a R/W bit that disagrees with our internal `isRead`
    // view of the world. RepStart's arm in `waitNextCmdState` does
    // *not* do this — RepStart is the one place the lsb itself is
    // the directional signal (there's no AddrWrite-vs-AddrRead kind
    // to disambiguate it).
    val idleState: State = new State with EntryPoint {
      whenIsActive {
        io.cmd.ready := True

        when(io.cmd.fire) {
          switch(io.cmd.kind) {
            is(ByteCmdKind.AddrWrite) {
              dataByte := io.cmd.data(7 downto 1) ## False // force R/W = 0
              isRead := False
              bitCounter := 0
              arbLostReg := False
              goto(startTransactionIssueState)
            }
            is(ByteCmdKind.AddrRead) {
              dataByte := io.cmd.data(7 downto 1) ## True // force R/W = 1
              isRead := True
              bitCounter := 0
              arbLostReg := False
              goto(startTransactionIssueState)
            }
            default {
              // WriteData / ReadData / RepStart / Stop without a
              // prior AddrWrite/AddrRead. Bus is idle, no
              // termination needed; route to errorRspState and
              // bounce back to idle once the rsp is consumed.
              errorStatus := ByteRspStatus.InvalidSeq
              errorReturnToIdle := True
              goto(errorRspState)
            }
          }
        }
      }
    }

    // ======================================================================
    //  Hub: waitNextCmdState
    // ======================================================================
    //
    // A transaction is in flight (we got past the address-ACK rsp).
    // The legal command set depends on the regime:
    //
    //   ----- Normal regime (mustTerminate = False) -----
    //   Full menu, with direction enforced by `isRead`:
    //     WriteData   → only when isRead = False (write-direction txn)
    //     ReadData    → only when isRead = True  (read-direction txn)
    //     RepStart    → always legal, re-aims direction from data.lsb
    //     Stop        → always legal, releases the bus
    //   Direction mismatch (e.g. WriteData on a read txn) is a SW
    //   bug → InvalidSeq, stays wedged-but-not-terminating in the
    //   same regime. AddrWrite/AddrRead while already started is also
    //   a SW bug → InvalidSeq.
    //
    //   ----- Wedged regime (mustTerminate = True) -----
    //   Set by any rsp that observed a target NAK, an arb-loss, or
    //   a SW-driven NAK end-of-read. The transaction is unrecoverable
    //   in its current direction; only terminators are accepted:
    //     RepStart → recover by re-arming a fresh transaction without
    //                releasing the bus; clears mustTerminate.
    //     Stop     → release the bus; clears mustTerminate via
    //                `stopRspState.onExit`.
    //   Anything else is InvalidSeq, stays wedged.
    //
    // Note: the InvalidSeq paths use `errorReturnToIdle := False` so
    // we land back here, *without* clearing mustTerminate — the
    // driver still owes us a terminator.
    val waitNextCmdState: State = new State {
      whenIsActive {
        io.cmd.ready := True
        when(io.cmd.fire) {
          when(mustTerminate) {
            // Wedged: only terminators allowed.
            switch(io.cmd.kind) {
              is(ByteCmdKind.Stop) {
                goto(stopIssueState)
              }
              is(ByteCmdKind.RepStart) {
                // RepStart's lsb is the direction selector — no
                // masking here, see the design note in `idleState`.
                dataByte := io.cmd.data
                isRead := io.cmd.data.lsb
                bitCounter := 0
                mustTerminate := False
                arbLostReg := False
                goto(repeatedStartIssueState)
              }
              default {
                errorStatus := ByteRspStatus.InvalidSeq
                errorReturnToIdle := False
                goto(errorRspState)
              }
            }
          } otherwise {
            // Normal: full menu, with direction enforced.
            switch(io.cmd.kind) {
              is(ByteCmdKind.WriteData) {
                when(!isRead) {
                  dataByte := io.cmd.data
                  bitCounter := 0
                  goto(writeShiftIssueState)
                } otherwise {
                  // WriteData on a read-direction txn.
                  errorStatus := ByteRspStatus.InvalidSeq
                  errorReturnToIdle := False
                  goto(errorRspState)
                }
              }
              is(ByteCmdKind.ReadData) {
                when(isRead) {
                  bitCounter := 0
                  ackOutReg := io.cmd.ackOut // SW's read-end NAK choice
                  goto(readShiftIssueState)
                } otherwise {
                  // ReadData on a write-direction txn.
                  errorStatus := ByteRspStatus.InvalidSeq
                  errorReturnToIdle := False
                  goto(errorRspState)
                }
              }
              is(ByteCmdKind.RepStart) {
                // Same direction-from-lsb rule as the wedged arm.
                dataByte := io.cmd.data
                isRead := io.cmd.data.lsb
                bitCounter := 0
                arbLostReg := False
                goto(repeatedStartIssueState)
              }
              is(ByteCmdKind.Stop) {
                goto(stopIssueState)
              }
              default {
                // AddrWrite / AddrRead while already started.
                errorStatus := ByteRspStatus.InvalidSeq
                errorReturnToIdle := False
                goto(errorRspState)
              }
            }
          }
        }
      }
    }

    // ======================================================================
    //  Hub: errorRspState
    // ======================================================================
    //
    // Reached when the SW driver issued an out-of-sequence command.
    // The bus is *not* touched here — we just emit one rsp carrying
    // the latched status code and bounce to either `idleState` or
    // `waitNextCmdState` (chosen via `errorReturnToIdle`). The
    // controller's regime (mustTerminate, isRead, dataByte) is left
    // untouched, because nothing happened on the bus.
    //
    // The driver is expected to inspect `status`, identify the bug,
    // and recover. Typical recovery is:
    //   - `errorReturnToIdle = True` : nothing to clean up.
    //   - `errorReturnToIdle = False`: issue `Stop` (always legal).
    val errorRspState: State = new State {
      whenIsActive {
        io.rsp.valid := True
        io.rsp.payload.data := 0
        io.rsp.payload.ackIn := True // NAK-ish; meaningless for InvalidSeq
        io.rsp.payload.status := errorStatus

        when(io.rsp.fire) {
          when(errorReturnToIdle) {
            goto(idleState)
          } otherwise {
            goto(waitNextCmdState)
          }
        }
      }
    }

    // ======================================================================
    //  Spoke: Start condition (S)
    // ======================================================================
    //
    // Issues `BitCmd.Start` to the bit controller, waits for it to
    // settle, then dives into the shared write pipeline to clock
    // the latched address byte out.
    //
    // Arb-loss can happen during Start if another master is already
    // pulling SDA low. In that case we short-circuit straight to
    // `writeRspState`, which emits a `status = ArbLost` rsp with
    // `mustTerminate := True` — the SW driver knows the start
    // never landed and will recover via Stop or RepStart. We pre-
    // clear `ackInReg` so the rsp's `ackIn` field doesn't carry a
    // stale value from a previous transaction (the field is
    // meaningless on an arb-loss rsp, but cleanliness is cheap).
    val startTransactionIssueState: State = new State {
      whenIsActive {
        bitCtrl.io.cmd.valid := True
        bitCtrl.io.cmd.payload := BitCmd.Start

        when(bitCtrl.io.cmd.fire) {
          goto(startTransactionWaitDoneState)
        }
      }
    }

    val startTransactionWaitDoneState: State = new State {
      whenIsActive {
        when(bitCtrl.io.arbLost) { arbLostReg := True }
        when(bitCtrl.io.cmd.ready) {
          when(arbLostReg || bitCtrl.io.arbLost) {
            // Arb-loss during Start: never landed. Surface it.
            ackInReg := False
            goto(writeRspState)
          } otherwise {
            // Bus is ours; clock out the address byte.
            goto(writeShiftIssueState)
          }
        }
      }
    }

    // ======================================================================
    //  Spoke: Read pipeline
    // ======================================================================
    //
    // 8× ReadBit (LSB last) → 1× WriteBit driving ackOutReg → rsp.
    //
    //   readShiftIssueState → readShiftWaitState
    //                          ├─ on arb-loss     → readRspState
    //                          ├─ bitCounter < 7  → readShiftIssueState
    //                          └─ bitCounter == 7 → readAckIssueState
    //   readAckIssueState   → readAckWaitState
    //                          ├─ on arb-loss     → readRspState
    //                          └─ otherwise       → readRspState
    //   readRspState         → waitNextCmdState
    //
    // The shift happens in the *Wait* state, not the Issue state,
    // because `bitCtrl.io.rxBit` is only valid after the bit
    // controller has finished sampling — i.e. on the cycle
    // `cmd.ready` re-asserts. Doing the shift in Issue would latch
    // the *previous* bit's value, which isn't what we want.
    val readShiftIssueState: State = new State {
      whenIsActive {
        bitCtrl.io.cmd.valid := True
        bitCtrl.io.cmd.payload := BitCmd.ReadBit

        when(bitCtrl.io.cmd.fire) {
          goto(readShiftWaitState)
        }
      }
    }

    val readShiftWaitState: State = new State {
      whenIsActive {
        when(bitCtrl.io.arbLost) { arbLostReg := True }
        when(bitCtrl.io.cmd.ready) {
          when(arbLostReg || bitCtrl.io.arbLost) {
            // Arb-loss mid-byte: abandon, surface in rsp.
            goto(readRspState)
          } otherwise {
            // Shift the freshly-clocked bit into the LSB of dataByte;
            // I²C transmits MSB-first, so the first sample becomes
            // the eventual MSB after seven more shifts.
            dataByte := dataByte(6 downto 0) ## bitCtrl.io.rxBit
            bitCounter := bitCounter + 1
            when(bitCounter === 7) {
              bitCounter := 0
              goto(readAckIssueState)
            } otherwise {
              goto(readShiftIssueState)
            }
          }
        }
      }
    }

    val readAckIssueState: State = new State {
      whenIsActive {
        // We drive the ACK ourselves on the 9th clock. ackOutReg
        // came from `io.cmd.ackOut` at the time the ReadData cmd
        // fired; 0 = ACK (target sends another byte), 1 = NAK
        // (target releases SDA, end-of-read).
        bitCtrl.io.cmd.valid := True
        bitCtrl.io.cmd.payload := BitCmd.WriteBit
        bitCtrl.io.txBit := ackOutReg

        when(bitCtrl.io.cmd.fire) {
          goto(readAckWaitState)
        }
      }
    }

    val readAckWaitState: State = new State {
      whenIsActive {
        when(bitCtrl.io.arbLost) { arbLostReg := True }
        when(bitCtrl.io.cmd.ready) {
          // Whether or not arb was lost on the ACK bit, we go to
          // the rsp state — both paths report through it. The arb
          // status (set above) survives.
          goto(readRspState)
        }
      }
    }

    val readRspState: State = new State {
      whenIsActive {
        io.rsp.valid := True
        io.rsp.payload.data := dataByte
        io.rsp.payload.ackIn := False // n/a on read; we drove ACK ourselves
        io.rsp.payload.status := arbLostReg ? ByteRspStatus.ArbLost | ByteRspStatus.Ok

        when(io.rsp.fire) {
          // Wedge the transaction if either:
          //   * the SW driver drove a NAK (`ackOutReg`), meaning
          //     "this was the last byte"; the protocol now requires
          //     a Stop or RepStart, or
          //   * arb was lost; the bus state is unknown and we must
          //     terminate.
          when(ackOutReg || arbLostReg) {
            mustTerminate := True
          }
          goto(waitNextCmdState)
        }
      }
    }

    // ======================================================================
    //  Spoke: Write pipeline (shared by AddrWrite, AddrRead address
    //                         shift, RepStart address shift, WriteData)
    // ======================================================================
    //
    // 8× WriteBit (MSB first) → 1× ReadBit sampling ACK → rsp.
    //
    //   writeShiftIssueState → writeShiftWaitState
    //                           ├─ on arb-loss     → writeRspState
    //                           ├─ bitCounter < 7  → writeShiftIssueState
    //                           └─ bitCounter == 7 → writeAckIssueState
    //   writeAckIssueState    → writeAckWaitState
    //                           ├─ on arb-loss     → writeRspState
    //                           └─ otherwise       → writeRspState
    //   writeRspState          → waitNextCmdState
    //
    // The shift is in the Wait state for symmetry with the read
    // pipeline, even though writes don't have the rxBit timing
    // concern: doing it in Wait keeps the bit-counter increment and
    // state transition adjacent in code, which makes the loop
    // easier to read.
    val writeShiftIssueState: State = new State {
      whenIsActive {
        // I²C transmits MSB first; the bit ctrl latches `txBit` on
        // `cmd.fire`, so we only need to drive it during Issue.
        bitCtrl.io.cmd.valid := True
        bitCtrl.io.cmd.payload := BitCmd.WriteBit
        bitCtrl.io.txBit := dataByte.msb

        when(bitCtrl.io.cmd.fire) {
          goto(writeShiftWaitState)
        }
      }
    }

    val writeShiftWaitState: State = new State {
      whenIsActive {
        when(bitCtrl.io.arbLost) { arbLostReg := True }
        when(bitCtrl.io.cmd.ready) {
          when(arbLostReg || bitCtrl.io.arbLost) {
            // Arb-loss mid-byte: stop clocking out bits we don't
            // own. Surface it in the rsp; ackInReg is stale but the
            // rsp's ackIn is meaningless under ArbLost status.
            goto(writeRspState)
          } otherwise {
            // Bit clocked out. Shift dataByte left by 1 (MSB was
            // just sent; next MSB will be old dataByte(6)). The
            // `## False` is cosmetic — that bit will be replaced
            // before it ever reaches the bus.
            dataByte := dataByte(6 downto 0) ## False
            bitCounter := bitCounter + 1
            when(bitCounter === 7) {
              bitCounter := 0
              goto(writeAckIssueState)
            } otherwise {
              goto(writeShiftIssueState)
            }
          }
        }
      }
    }

    val writeAckIssueState: State = new State {
      whenIsActive {
        // Sample target ACK on the 9th clock. The bit ctrl performs
        // a ReadBit (releases SDA, samples mid-tHigh). We don't
        // drive `txBit` here; it stays at the default "release".
        bitCtrl.io.cmd.valid := True
        bitCtrl.io.cmd.payload := BitCmd.ReadBit

        when(bitCtrl.io.cmd.fire) {
          goto(writeAckWaitState)
        }
      }
    }

    val writeAckWaitState: State = new State {
      whenIsActive {
        when(bitCtrl.io.arbLost) { arbLostReg := True }
        when(bitCtrl.io.cmd.ready) {
          // Snapshot the ACK bit. Even if arb was lost on the ACK
          // (improbable — we're not driving SDA — but possible if
          // SCL was hijacked), `ackInReg` is harmless because the
          // rsp will surface ArbLost status.
          ackInReg := bitCtrl.io.rxBit
          goto(writeRspState)
        }
      }
    }

    val writeRspState: State = new State {
      whenIsActive {
        io.rsp.valid := True
        io.rsp.payload.data := 0 // address & write rsps carry no read data
        io.rsp.payload.ackIn := ackInReg
        io.rsp.payload.status := arbLostReg ? ByteRspStatus.ArbLost | ByteRspStatus.Ok

        when(io.rsp.fire) {
          // NAK from target, or arb-loss, wedges the transaction:
          // the upper layer must follow with Stop or RepStart.
          when(ackInReg || arbLostReg) {
            mustTerminate := True
          }
          goto(waitNextCmdState)
        }
      }
    }

    // ======================================================================
    //  Spoke: Stop (P)
    // ======================================================================
    //
    // Stop is fully owned by the FSM at this point — the `Stop`
    // ByteCmd was already consumed by `waitNextCmdState` (in either
    // regime). No further `io.cmd` is read from here on; we just
    // drive the bit controller and emit one response when the bus
    // is idle again.
    //
    // We deliberately do NOT short-circuit on arb-loss in
    // `stopWaitDoneState` (unlike the data-shift wait states): a
    // Stop's job is to release the bus regardless, and aborting
    // mid-Stop would leave SDA/SCL in an unknown state. We snapshot
    // arb-loss for reporting and let the bit controller finish.
    //
    // Three phases:
    //   1. stopIssueState     — present BitCmd.Stop and wait for fire.
    //   2. stopWaitDoneState  — wait for the bit ctrl to finish the
    //                           Stop sequence (it re-asserts
    //                           cmd.ready when it lands back in its
    //                           own idleState).
    //   3. stopRspState       — emit one ByteRsp acknowledging the
    //                           transaction is over. Only after the
    //                           rsp is consumed do we return to
    //                           `idleState` and clear the per-
    //                           transaction registers via `onExit`.
    //                           This prevents the SW driver from
    //                           re-issuing AddrWrite while SDA/SCL
    //                           are still settling.
    val stopIssueState: State = new State {
      whenIsActive {
        bitCtrl.io.cmd.valid := True
        bitCtrl.io.cmd.payload := BitCmd.Stop

        when(bitCtrl.io.cmd.fire) {
          goto(stopWaitDoneState)
        }
      }
    }

    val stopWaitDoneState: State = new State {
      whenIsActive {
        // Snapshot arb-loss for reporting, but let the bit
        // controller finish the Stop sequence regardless.
        when(bitCtrl.io.arbLost) {
          arbLostReg := True
        }
        when(bitCtrl.io.cmd.ready) {
          goto(stopRspState)
        }
      }
    }

    val stopRspState: State = new State {
      whenIsActive {
        io.rsp.valid := True
        io.rsp.payload.data := 0 // no data on a Stop response
        io.rsp.payload.ackIn := False // n/a for Stop
        io.rsp.payload.status := arbLostReg ? ByteRspStatus.ArbLost | ByteRspStatus.Ok

        when(io.rsp.fire) {
          goto(idleState)
        }
      }

      onExit {
        // Transaction is fully over: clear all per-transaction
        // bookkeeping so the next AddrWrite/AddrRead starts clean.
        // (`isRead` and `dataByte` are not cleared here because
        //  they are unconditionally re-latched at the next
        //  AddrWrite/AddrRead cmd.fire.)
        mustTerminate := False
        arbLostReg := False
      }
    }

    // ======================================================================
    //  Spoke: Repeated Start (Sr)
    // ======================================================================
    //
    // Same shape as Start: issue Sr, wait done, then fall into the
    // shared write pipeline to clock out the new address byte.
    // `dataByte` and `isRead` were already latched by
    // `waitNextCmdState`'s RepStart arm (in either regime). The
    // arm also cleared `arbLostReg` and (in the wedged regime)
    // `mustTerminate`.
    //
    // Arb-loss on Sr is handled the same way as on S: short-circuit
    // to `writeRspState` and report it.
    val repeatedStartIssueState: State = new State {
      whenIsActive {
        bitCtrl.io.cmd.valid := True
        bitCtrl.io.cmd.payload := BitCmd.RepStart

        when(bitCtrl.io.cmd.fire) {
          goto(repeatedStartWaitDoneState)
        }
      }
    }

    val repeatedStartWaitDoneState: State = new State {
      whenIsActive {
        when(bitCtrl.io.arbLost) { arbLostReg := True }
        when(bitCtrl.io.cmd.ready) {
          when(arbLostReg || bitCtrl.io.arbLost) {
            ackInReg := False
            goto(writeRspState)
          } otherwise {
            goto(writeShiftIssueState)
          }
        }
      }
    }
  }
}
