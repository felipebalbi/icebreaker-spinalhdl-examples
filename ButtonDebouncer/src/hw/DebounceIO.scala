package button_debouncer

import spinal.core._

/** Public IO bundle every [[Debouncer]] subclass exposes.
  *
  * Pulled out into its own type so the abstract [[Debouncer]] can declare
  * `val io = DebounceIO()` once, and downstream consumers (e.g.
  * [[ButtonTop]]) can write `debouncer.io.rising` regardless of which
  * concrete debouncer was instantiated.
  */
case class DebounceIO() extends Bundle {

  /** Raw button input — asynchronous, bouncy. Each implementation crosses
    * this into the local clock domain with `BufferCC` before consuming it.
    */
  val raw = in Bool ()

  /** Debounced level: a clean high/low that only changes after the
    * implementation is convinced the input is stable.
    */
  val stable = out Bool ()

  /** One-cycle pulse on every low-to-high transition of `stable`.
    * Convenient for "trigger on press" consumers like [[ButtonTop]].
    */
  val rising = out Bool ()

  /** One-cycle pulse on every high-to-low transition of `stable`.
    * Convenient for "trigger on release" consumers.
    */
  val falling = out Bool ()
}
