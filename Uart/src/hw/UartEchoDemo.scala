package uart

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.fsm._

/** Minimal hardware echo demo, register-mapped edition.
  *
  * Same on-the-wire behaviour as the legacy UartEchoDemo (RX byte in → TX byte
  * out), but the byte plumbing now sits behind a memory-mapped
  * [[UartController]] instead of touching the streaming cores directly. This
  * makes the demo a tiny ad-hoc CPU that drives an APB3 bus — exactly the
  * topology a soft-core (VexRiscv etc.) would present, just with a dozen-state
  * FSM standing in for the processor.
  *
  * Why structure it this way instead of the original direct-Stream version?
  *
  *   - Exercises the full [[UartController]] register file end-to-end (STATUS
  *     poll, RXDATA pop, TXDATA push). If the controller's APB read/write
  *     address-decode is wrong, this demo silently stops echoing — a much
  *     sharper bring-up signal than a sim pass alone.
  *   - Same iCEbreaker pin map as before, so the existing pcf and `make` /
  *     `make flash` flow keeps working.
  *
  * On-chip topology:
  *   - [[UartController]] owns the FIFOs, BaudGenerator, and registers
  *   - this Component is a hand-rolled APB master that polls STATUS, reads
  *     RXDATA, and writes TXDATA in a loop.
  *
  * Same `ClockDomain` shape as the rest of the project: explicit
  * RISING/ASYNC/active-LOW reset so the iCEbreaker user button works.
  *
  * @param cfg
  *   Compile-time UART configuration. Defaults to 8N1 with no flow control. The
  *   UartController's BAUD register is loaded with the compile-time-correct
  *   phase increment so the demo hits the configured baud out of reset.
  */
case class UartEchoDemo(
    cfg: UartConfig = UartConfig(useCts = false, useRts = false)
) extends Component {
  require(
    !cfg.useCts && !cfg.useRts,
    "UartEchoDemo wires no flow-control pins; use cfg.useCts = false and cfg.useRts = false"
  )

  val io = new Bundle {

    /** The board's free-running 12 MHz clock (pcf maps to pin 32). */
    val clk = in Bool ()

    /** Active-LOW reset from the iCEbreaker user button (pcf maps to pin 10).
      * Pulled high through a pull-up; shorted to ground when pressed.
      */
    val reset = in Bool ()

    /** Serial input from the host's USB-UART TX (pcf maps to pin 6). */
    val rx = in Bool ()

    /** Serial output to the host's USB-UART RX (pcf maps to pin 9). */
    val tx = out Bool ()
  }

  val mainClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = LOW
    )
  )

  val core = new ClockingArea(mainClockDomain) {

    val uart = UartController(cfg)

    // ----- APB3 master FSM ------------------------------------------------
    //
    // APB3 is two-phase per transaction:
    //   - SETUP : PSEL=1, PENABLE=0, address+write+wdata valid
    //   - ACCESS: PSEL=1, PENABLE=1; on this cycle PRDATA is sampled
    //     and PREADY is checked.
    // We don't bother with PREADY/PSLVERR (UartController doesn't drive
    // PREADY low) so each transaction is a clean 2-cycle pulse.
    //
    // The loop is: poll RX_FIFO_STATUS → if !empty then read RXDATA into
    // a holding register and write it to TXDATA. Addresses bumped up by
    // 4 bytes vs. the original layout because REVISION now sits at 0x00.

    val rxFifoStatusAddr = U(0x24, 8 bits)
    val rxDataAddr = U(0x18, 8 bits)
    val txDataAddr = U(0x14, 8 bits)

    // Holding register for the byte we just read from RXDATA.
    val byteReg = Reg(Bits(cfg.dataBits bits)) init (0)

    // Default APB drives.
    uart.io.apb.PSEL := B(0)
    uart.io.apb.PENABLE := False
    uart.io.apb.PWRITE := False
    uart.io.apb.PADDR := U(0, 8 bits)
    uart.io.apb.PWDATA := B(0, 32 bits)

    val fsm = new StateMachine {

      val pollSetup = new State with EntryPoint
      val pollAccess = new State
      val readRxSetup = new State
      val readRxAccess = new State
      val writeTxSetup = new State
      val writeTxAccess = new State

      pollSetup.whenIsActive {
        uart.io.apb.PSEL := B(1)
        uart.io.apb.PADDR := rxFifoStatusAddr
        uart.io.apb.PWRITE := False
        goto(pollAccess)
      }

      pollAccess.whenIsActive {
        uart.io.apb.PSEL := B(1)
        uart.io.apb.PENABLE := True
        uart.io.apb.PADDR := rxFifoStatusAddr
        uart.io.apb.PWRITE := False
        // RX_FIFO_STATUS bit 1 = empty; data is available when !empty.
        when(!uart.io.apb.PRDATA(1)) {
          goto(readRxSetup)
        } otherwise {
          goto(pollSetup)
        }
      }

      readRxSetup.whenIsActive {
        uart.io.apb.PSEL := B(1)
        uart.io.apb.PADDR := rxDataAddr
        uart.io.apb.PWRITE := False
        goto(readRxAccess)
      }

      readRxAccess.whenIsActive {
        uart.io.apb.PSEL := B(1)
        uart.io.apb.PENABLE := True
        uart.io.apb.PADDR := rxDataAddr
        uart.io.apb.PWRITE := False
        byteReg := uart.io.apb.PRDATA(cfg.dataBits - 1 downto 0)
        goto(writeTxSetup)
      }

      writeTxSetup.whenIsActive {
        uart.io.apb.PSEL := B(1)
        uart.io.apb.PADDR := txDataAddr
        uart.io.apb.PWRITE := True
        uart.io.apb.PWDATA := byteReg.resize(32)
        goto(writeTxAccess)
      }

      writeTxAccess.whenIsActive {
        uart.io.apb.PSEL := B(1)
        uart.io.apb.PENABLE := True
        uart.io.apb.PADDR := txDataAddr
        uart.io.apb.PWRITE := True
        uart.io.apb.PWDATA := byteReg.resize(32)
        goto(pollSetup)
      }
    }

    // ----- pin wiring -----------------------------------------------------

    uart.io.rx := io.rx

    // Same idle-high reset trick as the legacy demo.
    val txOut = RegNext(uart.io.tx) init (True)
  }

  io.tx := core.txOut
}

/** Verilog generation entry point for the echo demo. */
object UartEchoDemoVerilog {
  def main(args: Array[String]): Unit = {
    SpinalConfig(targetDirectory = "gen").generateVerilog(UartEchoDemo())
  }
}
