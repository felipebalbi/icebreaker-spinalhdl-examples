package i2c

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

object ByteCmdKind extends SpinalEnum {
  val AddrWrite, AddrRead, WriteData, ReadData, RepStart, Stop = newElement()
}

case class ByteCmd() extends Bundle {
  val kind = ByteCmdKind()
  val data = Bits(8 bits) // address (with R/w) or the actual write payload
  val ackOut = Bool() // for ReadData: ack=0 to continue, 1 to NAK
}

case class ByteRsp() extends Bundle{
  val data = Bits(8 bits) // bytes read back
  val ackIn = Bool() // Target-reported ACK (0 = ACK, 1 = NAK)
  val arbLost = Bool()
}

case class I2cByteController(cfg: I2cConfig) extends Component {
  val io = new Bundle {
    val cmd = slave Stream ByteCmd()
    val rsp = master Stream ByteRsp()
    val bus = master(I2cIo())
  }

  val bitCtrl = I2cBitController(cfg)
  bitCtrl.io.bus <> io.bus

  val isRead = Reg(Bool()) init(False)
  val mustTerminate = Reg(Bool()) init(False)
  val hasStarted = Reg(Bool()) init(False)
  val arbLostReg = Reg(Bool()) init(False)

  // ---- Address-shift bookkeeping -------------------------------------------
  // addrByte holds the latched (7-bit address ## R/W) byte that we
  // shift onto the bus MSB-first during S/Sr. We MUST latch it the
  // cycle io.cmd fires, because by the time the shift loop starts the
  // upper layer has long since dropped the stream payload.
  //
  // bitCounter counts shifted bits, 0..7 inclusive (so 4 bits wide for
  // headroom). ackInReg is the sampled SDA value during the 9th clock
  // -- 0 == ACK from target, 1 == NAK.
  val dataByte   = Reg(Bits(8 bits)) init(0)
  val bitCounter = Reg(UInt(4 bits)) init(0)
  val ackInReg     = Reg(Bool())       init(False)
  val ackOutReg     = Reg(Bool())       init(False)

  // ---- Component-level defaults --------------------------------------------
  // Every state overrides only what it needs; everything else falls
  // back to these. Without defaults, Spinal will flag combinational
  // signals as undriven for any state path that doesn't touch them.

  // Bit controller command channel: not asserting valid means "nothing
  // to do right now"; Idle is a safe payload that the bit controller
  // also ignores while valid is False.
  bitCtrl.io.cmd.valid   := False
  bitCtrl.io.cmd.payload := BitCmd.Idle
  bitCtrl.io.txBit       := True // open-drain idle = released

  // Upstream command/response handshake. Only idleState and
  // waitNextCmdState raise cmd.ready. Only the *RspState members raise
  // rsp.valid.
  io.cmd.ready           := False
  io.rsp.valid           := False
  io.rsp.payload.data    := 0
  io.rsp.payload.ackIn   := True  // NAK-ish: only meaningful on byte rsps
  io.rsp.payload.arbLost := False

  val fsm = new StateMachine {

    // ---------------- Idle --------------------------------------------------
    val idleState: State = new State with EntryPoint {
      whenIsActive {
        io.cmd.ready := True

        when(io.cmd.fire) {

          switch(io.cmd.kind) {
            is(ByteCmdKind.AddrWrite) {
              // Latch addr byte and direction NOW. io.cmd.data is
              // only valid this cycle.
              dataByte   := io.cmd.data
              isRead     := False
              bitCounter := 0
              arbLostReg := False
              goto(startTransactionIssueState)
            }
            is(ByteCmdKind.AddrRead) {
              dataByte   := io.cmd.data
              isRead     := True
              bitCounter := 0
              arbLostReg := False
              goto(startTransactionIssueState)
            }
            default { /* should handle errors here */ }
          }
        }
      }
    }

    // ---------------- Wait next cmd --------------------------------------------------
    val waitNextCmdState: State = new State {
      whenIsActive {
        io.cmd.ready := True
        when(io.cmd.fire) {
          switch(io.cmd.kind) {
            is(ByteCmdKind.WriteData) {
              when(!isRead) {
                // latch new byte here
                dataByte      := io.cmd.data
                bitCounter    := 0
                goto(writeShiftIssueState)
              }
            }

            is(ByteCmdKind.ReadData) {
              when(isRead) {
                // latch new byte here
                dataByte      := io.cmd.data
                bitCounter    := 0
                ackOutReg := io.cmd.ackOut
                goto(readShiftIssueState)
              }
            }

            is(ByteCmdKind.RepStart) {
              // RepStart re-aims direction: latch new addr byte the
              // same way idleState does, and clear mustTerminate
              // (RepStart is one of the legal terminators).
              dataByte      := io.cmd.data
              isRead        := io.cmd.data.lsb
              bitCounter    := 0
              mustTerminate := False
              arbLostReg    := False
              goto(repeatedStartIssueState)
            }
            is(ByteCmdKind.Stop) { goto(stopIssueState) }
            default { /* should handle errors here */ }
          }
        }
      }
    }

    // ---------------- Start Transaction --------------------------------------
    //
    // The full address-issue chain is:
    //   1. startTransactionIssueState    -- present BitCmd.Start
    //   2. startTransactionWaitDoneState -- wait for the Start condition
    //                                       to actually be on the bus
    //   3. addrShiftIssueState           -- present one WriteBit with
    //                                       dataByte's MSB on txBit
    //   4. addrShiftWaitState            -- wait for the bit to clock
    //                                       out, shift dataByte left,
    //                                       loop until 8 bits done
    //   5. addrAckIssueState             -- present one ReadBit
    //   6. addrAckWaitState              -- wait, snapshot rxBit into
    //                                       ackInReg
    //   7. addrRspState                  -- emit one ByteRsp carrying
    //                                       ackIn=ackInReg; on NAK set
    //                                       mustTerminate; mark
    //                                       hasStarted; goto wait hub
    //
    // Steps 3-7 are shared with RepeatedStart (they pick up at
    // addrShiftIssueState after their own Sr completes).
    val startTransactionIssueState: State = new State {
      whenIsActive {
        bitCtrl.io.cmd.valid   := True
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
          goto(writeShiftIssueState)
        }
      }
    }

    // ---------------- Read --------------------------------------------------
    val readShiftIssueState: State = new State {
      whenIsActive {
        // Receive LSB from rxBit
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
          // Now rxBit is the bit we just clocked in. Shift it into LSB.
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

    val readAckIssueState: State = new State {
      whenIsActive {
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
          goto(readRspState)
        }
      }
    }

    val readRspState: State = new State {
      whenIsActive {
        io.rsp.valid           := True
        io.rsp.payload.data    := dataByte
        io.rsp.payload.ackIn   := False        // n/a on read; we drove ACK ourselves
        io.rsp.payload.arbLost := arbLostReg
        
        when(io.rsp.fire) {
          hasStarted := True
          // We sent NAK → upper layer must terminate next.
          when(ackOutReg) { mustTerminate := True }
          goto(waitNextCmdState)
        }
      }
    }

    // ---------------- Write --------------------------------------------------
    val writeShiftIssueState: State = new State {
      whenIsActive {
        // Drive MSB onto txBit; bit ctrl latches it on cmd.fire.
        bitCtrl.io.cmd.valid   := True
        bitCtrl.io.cmd.payload := BitCmd.WriteBit
        bitCtrl.io.txBit       := dataByte.msb

        when(bitCtrl.io.cmd.fire) {
          goto(writeShiftWaitState)
        }
      }
    }

    val writeShiftWaitState: State = new State {
      whenIsActive {
        when(bitCtrl.io.arbLost) { arbLostReg := True }
        when(bitCtrl.io.cmd.ready) {
          // Bit clocked out. Shift dataByte left by 1 (MSB was just
          // sent; next MSB will be dataByte(6) on the next pass).
          dataByte   := dataByte(6 downto 0) ## False
          bitCounter := bitCounter + 1
          when(bitCounter === 7) {
            // 8 bits done; move to ACK phase.
            bitCounter := 0
            goto(writeAckIssueState)
          } otherwise {
            goto(writeShiftIssueState)
          }
        }
      }
    }

    val writeAckIssueState: State = new State {
      whenIsActive {
        bitCtrl.io.cmd.valid   := True
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
          ackInReg := bitCtrl.io.rxBit
          goto(writeRspState)
        }
      }
    }

    val writeRspState: State = new State {
      whenIsActive {
        io.rsp.valid           := True
        io.rsp.payload.data    := 0           // address phase carries no read data
        io.rsp.payload.ackIn   := ackInReg
        io.rsp.payload.arbLost := arbLostReg

        when(io.rsp.fire) {
          // Latch transaction-in-flight only on the success path
          // (i.e., when the upper layer actually consumes the rsp).
          hasStarted := True
          // NAK from the target means the upper layer must terminate
          // (Stop or RepStart) before doing anything else.
          when(ackInReg) {
            mustTerminate := True
          }
          goto(waitNextCmdState)
        }
      }
    }

    // ---------------- Stop ---------------------------------------------------
    //
    // Stop is fully owned by the FSM at this point -- the Stop ByteCmd
    // was already consumed by waitNextCmdState (or, in the
    // address-NAK / error path, will be the only legal command there).
    // No further io.cmd is read from here on; we just drive the bit
    // controller and emit one response when the bus is idle again.
    //
    // Three phases:
    //   1. stopIssueState    -- present BitCmd.Stop to the bit
    //                           controller and wait for it to accept
    //                           (cmd.fire).
    //   2. stopWaitDoneState -- wait for the bit controller to finish
    //                           the Stop sequence. It re-asserts
    //                           cmd.ready when it lands back in its
    //                           own idleState, which is our "done"
    //                           signal.
    //   3. stopRspState      -- emit a single ByteRsp to acknowledge
    //                           to the upper layer that the
    //                           transaction is over. Only after the
    //                           response is accepted do we return to
    //                           idleState and clear the per-
    //                           transaction registers. This prevents
    //                           the upper layer from re-issuing an
    //                           AddrWrite while SDA/SCL are still
    //                           settling.
    val stopIssueState: State = new State {
      whenIsActive {
        bitCtrl.io.cmd.valid   := True
        bitCtrl.io.cmd.payload := BitCmd.Stop

        when(bitCtrl.io.cmd.fire) {
          goto(stopWaitDoneState)
        }
      }
    }

    val stopWaitDoneState: State = new State {
      whenIsActive {
        // Bit controller is executing the Stop. It re-asserts
        // cmd.ready when it returns to its own idleState. Snapshot
        // arbLost while we still trust it; the next bit command
        // will clear arbLostReg inside the bit controller.
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
        io.rsp.valid           := True
        io.rsp.payload.data    := 0      // no data on a Stop response
        io.rsp.payload.ackIn   := False  // n/a for Stop
        io.rsp.payload.arbLost := arbLostReg

        when(io.rsp.fire) {
          goto(idleState)
        }
      }

      onExit {
        // Transaction is fully over: clear all per-transaction
        // bookkeeping so the next AddrWrite/AddrRead starts clean.
        hasStarted    := False
        mustTerminate := False
        arbLostReg    := False
      }
    }

    // ---------------- RepeatedStart -----------------------------------------
    //
    // Same shape as Start: issue Sr, wait done, then fall into the
    // shared address-shift / ACK / rsp pipeline above. dataByte and
    // isRead were already latched by waitNextCmdState's RepStart arm.
    val repeatedStartIssueState: State = new State {
      whenIsActive {
        bitCtrl.io.cmd.valid   := True
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
          goto(writeShiftIssueState)
        }
      }
    }
  }
}
