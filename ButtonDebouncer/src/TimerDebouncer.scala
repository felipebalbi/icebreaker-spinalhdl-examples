package button

import spinal.core._
import spinal.lib._

case class TimerDebouncer(
    clkFreqHz: Int,
    debounceMs: Int
) extends Debouncer {
  // Synchronize button input
  val btnSync = BufferCC(io.raw)

  // Convert miliseconds to clock cycles
  val debounceCycles = (clkFreqHz / 1000) * debounceMs

  // Guard against too-small values (must be at least 1 cycle)
  val maxCount = if (debounceCycles <= 1) 1 else debounceCycles

  // Define a cycle counter
  val counter = Reg(UInt(log2Up(maxCount) bits)) init (0)

  val state = Reg(Bool()) init (False)
  val candidate = Reg(Bool()) init (False)

  when(btnSync =/= candidate) {
    candidate := btnSync
    counter := 0
  } otherwise {
    when(counter =/= (maxCount - 1)) {
      counter := counter + 1
    } otherwise {
      state := candidate
    }
  }

  io.stable := state

  val prev = RegNext(state) init (False)

  io.rising := state && !prev
  io.falling := !state && prev
}
