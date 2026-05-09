package i2c

import spinal.core._
import spinal.lib._
import spinal.lib.io.ReadableOpenDrain

// I²C bus port: SCL + SDA, both modelled as ReadableOpenDrain.
//
// Why ReadableOpenDrain (not TriState)? The original TODO sketch
// reached for TriState[Bool] wrapped in a helper that hard-wired
// write := False and used writeEnable as the real control. Spinal's
// ReadableOpenDrain primitive captures the same intent natively:
// open-drain semantics are baked in, there is no writeEnable to
// forget, and you cannot accidentally drive a hard '1'.
//
// Electrical model (matches an actual open-drain pad):
//   write := False  -> turn the pull-down NMOS on  -> pin tied to GND
//                                                     -> bus low
//   write := True   -> turn the pull-down NMOS off -> pin floats
//                                                     -> external pull-up
//                                                        wins -> bus high
// Hence the polarity of releaseAll() below: True == "release", because
// True drives the gate of the pull-down off, not because we drive '1'
// onto the wire.
//
// read is the live, post-pad value of the bus, including whatever any
// other device on the wired-AND segment is doing. The bit-controller
// uses it both for read-bit slots and for arbitration detection
// (writing True means releasing -- if read still reports False,
// someone else is pulling the line down).
//
// IMasterSlave lets a controller declare master(I2cIo()) and a target
// declare slave(I2cIo()); Spinal flips every nested signal direction
// for us, so controller.io.bus <> target.io.bus connects in one line
// without having to flip TriState fields by hand.
//
// Doc references:
//   IMasterSlave     : https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Data%20types/bundle.html#master-slave
//   TriState         : https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/IO/tristate.html#id1
//   ReadableOpenDrain: https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/IO/readableOpenDrain.html
case class I2cIo() extends Bundle with IMasterSlave {
  val scl = ReadableOpenDrain(Bool())
  val sda = ReadableOpenDrain(Bool())

  override def asMaster(): Unit = { master(scl); master(sda) }

  // "pull low" / "release" stay as readable shorthand at call sites.
  // releaseAll is mostly useful for reset paths where we want both
  // lines high-Z before the FSMs come up.
  def releaseAll(): Unit = { scl.write := True; sda.write := True }
}
