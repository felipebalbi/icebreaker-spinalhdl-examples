package button

import spinal.core._
import spinal.lib._

case class IntegratorDebouncer(width: Int = 16) extends Debouncer {
  val btnSync = BufferCC(io.raw)
  val counter = Reg(UInt(width bits)) init (0)

  when(btnSync) {
    when(counter =/= counter.maxValue) {
      counter := counter + 1
    }
  } otherwise {
    when(counter =/= 0) {
      counter := counter - 1
    }
  }

  val state = Reg(Bool()) init (False)

  when(counter === counter.maxValue) {
    state := True
  }

  when(counter === 0) {
    state := False
  }

  io.stable := state

  val prev = RegNext(state) init (False)

  io.rising := state && !prev
  io.falling := !state && prev
}
