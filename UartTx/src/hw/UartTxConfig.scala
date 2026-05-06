package uart_tx

import spinal.core._

/** Parity scheme for a UART frame.
  *
  *   - `None` : no parity bit transmitted (default; the "N" in 8N1).
  *   - `Even` : parity bit set so that the *total* count of 1s in
  *              (data + parity) is even.
  *   - `Odd`  : parity bit set so that the *total* count of 1s in
  *              (data + parity) is odd.
  *
  * Currently only declared — the FSM does not yet emit a parity bit.
  */
object ParityType extends SpinalEnum {
  val None, Even, Odd = newElement()
}

/** Compile-time configuration for the UART transmitter.
  *
  * Held as a `case class` so it can be passed by value into every sub-block
  * (BaudGenerator, FSM, etc.) and used to derive widths / constants at
  * elaboration time. Nothing in this class survives into hardware — it only
  * shapes how the hardware is built.
  *
  * @param clkFreqHz  System clock frequency in Hz. Used to size the DDS
  *                   phase increment so that ticks land at the requested baud.
  * @param baudRate   Target line rate (bits per second).
  * @param dataBits   Number of data bits per frame (5..9 in the wild;
  *                   8 is by far the most common — the "8" in 8N1).
  * @param stopBits   Number of stop bits (1 or 2). Stop bits are just extra
  *                   high-level idle time at the end of a frame.
  * @param parity     Parity scheme. Currently unused until the FSM adds
  *                   the parity-bit slot.
  */
case class UartTxConfig(
  clkFreqHz : Int               = 12_000_000,
  baudRate  : Int               = 115_200,
  dataBits  : Int               = 8,
  stopBits  : Int               = 1,
  parity    : ParityType.E      = ParityType.None
) {
  /** Average number of system-clock cycles per UART bit period.
    *
    * Used as a sanity check / reference value for sims and assertions.
    * The DDS BaudGenerator does not actually count to this number — it
    * accumulates a phase increment — but on average it ticks every
    * `ticksPerBit` cycles.
    */
  val ticksPerBit: Int = clkFreqHz / baudRate

  require(ticksPerBit >= 1,             "clkFreqHz must be >= baudRate")
  require(dataBits >= 5 && dataBits <= 9, "dataBits must be 5..9")
  require(stopBits == 1 || stopBits == 2, "stopBits must be 1 or 2")
}
