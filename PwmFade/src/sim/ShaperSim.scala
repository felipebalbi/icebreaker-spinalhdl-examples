package pwm_fade

import spinal.core._
import spinal.core.sim._

/** LUT-content tests for the shapers.
  *
  * For each `Shaper` subclass, sweeps `phase` over the entire input
  * range and compares `io.duty` to the same Scala formula used to
  * build the LUT. Catches off-by-one and rounding bugs in the table
  * generation.
  *
  * Notes
  *   - `dut.io.phase #= i` is sim-DSL: drive a value onto a hardware
  *     signal in the testbench.
  *   - `sleep(1)` lets combinational logic settle before reading.
  *     Enough because the shapers use `readAsync`. With `readSync`
  *     we'd need `dut.clockDomain.waitSampling()` instead.
  *
  * Run: `sbt "runMain pwm_fade.ShaperSim"` (or `make sim-shaper`).
  */
object ShaperSim {
  def main(args: Array[String]): Unit = {
    checkIdentity(width = 6)
    checkSine(width = 6)
    checkGamma(width = 6, gamma = 2.2)
    checkGamma(width = 6, gamma = 1.0) // gamma=1 ≈ identity within rounding
    println("OK: all shaper LUTs match Scala reference within rounding.")
  }

  private def checkIdentity(width: Int): Unit = {
    val depth = 1 << width
    SimConfig.compile(IdentityShaper(width)).doSim { dut =>
      for (i <- 0 until depth) {
        dut.io.phase #= i
        sleep(1)
        val got = dut.io.duty.toBigInt
        assert(got == BigInt(i),
          s"Identity[$i] expected $i got $got")
      }
    }
  }

  private def checkSine(width: Int): Unit = {
    val depth = 1 << width
    val maxV  = depth - 1
    SimConfig.compile(SineShaper(width)).doSim { dut =>
      for (i <- 0 until depth) {
        dut.io.phase #= i
        sleep(1)
        val want = math.round(0.5 * (1.0 - math.cos(2.0 * math.Pi * i / depth)) * maxV)
        val got  = dut.io.duty.toBigInt.toLong
        assert(got == want, s"Sine[$i] expected $want got $got")
      }
    }
  }

  private def checkGamma(width: Int, gamma: Double): Unit = {
    val depth = 1 << width
    val maxV  = depth - 1
    SimConfig.compile(GammaShaper(width, gamma)).doSim { dut =>
      for (i <- 0 until depth) {
        dut.io.phase #= i
        sleep(1)
        val norm = i.toDouble / maxV.toDouble
        val want = math.round(math.pow(norm, gamma) * maxV.toDouble)
        val got  = dut.io.duty.toBigInt.toLong
        assert(got == want, s"Gamma($gamma)[$i] expected $want got $got")
      }
      // Endpoint sanity: f(0)=0 and f(max)=max for any gamma>0.
      dut.io.phase #= 0;     sleep(1); assert(dut.io.duty.toBigInt == BigInt(0))
      dut.io.phase #= maxV;  sleep(1); assert(dut.io.duty.toBigInt == BigInt(maxV))
    }
  }
}
