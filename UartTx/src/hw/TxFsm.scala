package uart_tx

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

case class TxFsm(cfg: UartTxConfig) extends Component {
  val io = new Bundle {
    val start = in Bool()
    val tick = in Bool()
    val shiftRegBit = in Bool()

    val busy = out Bool()
    val loadReg = out Bool()
    val shiftReg = out Bool()
    val txBit = out Bool()
  }

  val txReg = Reg(Bool()) init(True)
  io.txBit := txReg

  val bitCounter = Reg(UInt(log2Up(cfg.dataBits + 1) bits)) init(0)

  io.loadReg := False
  io.shiftReg := False
  io.busy := True

  // FSM proper
  val fsm = new StateMachine {
    // --------------------
    val idleState: State = new State with EntryPoint {
      onEntry {
        txReg := True
        bitCounter := 0
      }

      whenIsActive {
        io.busy := False

        val startEdge = io.start.rise(False)

        when(startEdge) {
          io.loadReg := True
          goto(startState)
        }
      }
    }

    // --------------------
    val startState: State = new State {
      onEntry {
        txReg := False
      }

      whenIsActive {
        when(io.tick) {
          goto(dataState)
        }
      }
    }

    // --------------------
    val dataState: State = new State {
      onEntry {
        bitCounter := 0
      }

      whenIsActive {
        // expose current bit
        txReg := io.shiftRegBit

        when(io.tick) {
          io.shiftReg := True
          bitCounter := bitCounter + 1

          when(bitCounter === cfg.dataBits - 1) {
            goto(stopState)
          }
        }
      }
    }

    // --------------------
    val stopState: State = new State {
      val stopCounter = Reg(UInt(log2Up(cfg.stopBits + 1) bits)) init(0)

      onEntry {
        txReg := True
        stopCounter := 0
      }

      whenIsActive {
        when(io.tick) {
          stopCounter := stopCounter + 1

          when (stopCounter === cfg.stopBits - 1) {
            goto(idleState)
          }
        }
      }
    }
  }
}
