package pwm_fade

import spinal.core._
import spinal.core.sim._
import spinal.sim._

/** End-to-end smoke sim for `PwmFadeTop`.
  *
  * Uses a small `width` and a tiny `prescalerWidth` so a full breath
  * fits in a reasonable sim window. Open the resulting `.vcd`/wave
  * file in GTKWave (look under `simWorkspace/`) to see:
  *   - `io.pwm` toggling at the carrier rate, and
  *   - the `Modulator`'s internal phase/duty ramping up and down.
  *
  * Tweak `cfg.kind` to inspect Identity / Sine / Gamma waveforms.
  *
  * Run: `sbt "runMain pwm_fade.PwmFadeSim"` (or `make sim-top`).
  */
object PwmFadeSim {
  def main(args: Array[String]): Unit = {

    // Use a small width and tiny prescaler so a full breath fits in sim.
    val cfg = ModulatorConfig(
      kind  = ShaperKind.Gamma,
      width = 8,
      step  = 4,
      gamma = 2.2
    )
    val prescalerWidth = 2

    SimConfig
      .withWave
      .compile(PwmFadeTop(cfg = cfg, prescalerWidth = prescalerWidth))
      .doSim { dut =>

        // Clock (since Pwm has implicit clock domain)
        dut.clockDomain.forkStimulus(period = 10)

        sleep(100000)
      }
  }
}
