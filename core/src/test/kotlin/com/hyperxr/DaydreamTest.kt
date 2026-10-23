package com.hyperxr

import com.hyperxr.daydream.ControllerFrame
import com.hyperxr.daydream.DaydreamController
import com.hyperxr.daydream.DaydreamPackets
import com.hyperxr.daydream.DaydreamProtocol
import com.hyperxr.daydream.Evdev
import com.hyperxr.daydream.EvdevPad
import com.hyperxr.daydream.ParcelSink
import com.hyperxr.daydream.Quat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs

class DaydreamTest {
    /** Parcel stand-in: little-endian, 4-byte ints/floats and 8-byte longs, like android.os.Parcel. */
    private class Sink : ParcelSink {
        val out = ByteArrayOutputStream()
        private fun put(b: ByteBuffer) = out.write(b.array())
        override fun writeInt(v: Int) = put(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v))
        override fun writeLong(v: Long) = put(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v))
        override fun writeFloat(v: Float) = put(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v))
    }

    private fun frame() = ControllerFrame(5_000_000_000L).apply {
        orientation = floatArrayOf(0f, 0f, 0f, 1f)
        gyro = floatArrayOf(0.1f, 0.2f, 0.3f)
        accel = floatArrayOf(0f, 9.8f, 0f)
        buttons += ControllerFrame.Button(DaydreamProtocol.BUTTON_CLICK, true)
        buttons += ControllerFrame.Button(DaydreamProtocol.BUTTON_APP, false)
        touch = ControllerFrame.Touch(DaydreamProtocol.TOUCH_DOWN, 0.25f, 0.75f)
    }

    @Test fun packet2LengthIsExactAndReadsBackLikeTheClient() {
        val s = Sink()
        DaydreamPackets.writePacket2(s, frame())
        val bytes = s.out.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(bytes.size, bb.getInt(0))
        // the empty packet is 48 bytes, as the SDK's calculateParcelByteLength gives
        assertEquals(48, DaydreamPackets.packet2Bytes(ControllerFrame(0)))

        // read it the way ControllerEventPacket2.readFromParcel does
        var p = 4
        fun int() = bb.getInt(p).also { p += 4 }
        fun long() = bb.getLong(p).also { p += 8 }
        fun float() = bb.getFloat(p).also { p += 4 }
        assertEquals(1, int())                               // version
        assertEquals(1, int()); long(); assertEquals(0, int()); repeat(3) { float() }          // accel
        assertEquals(2, int())
        long(); int(); assertEquals(DaydreamProtocol.BUTTON_CLICK, int()); assertEquals(1, int())
        long(); int(); assertEquals(DaydreamProtocol.BUTTON_APP, int()); assertEquals(0, int())
        assertEquals(1, int()); long(); int(); assertEquals(0.1f, float(), 0f); float(); float() // gyro
        assertEquals(1, int()); assertEquals(5_000_000_000L, long()); int(); repeat(3) { float() }; assertEquals(1f, float(), 0f) // orientation
        assertEquals(1, int()); long(); int(); int(); assertEquals(DaydreamProtocol.TOUCH_DOWN, int()); assertEquals(0.25f, float(), 0f); assertEquals(0.75f, float(), 0f)
        assertEquals(0, int())                               // positions
        assertEquals(0, int())                               // battery
        assertEquals(5000L, long())                          // ms timestamp
        assertEquals(0, int())                               // tracking status
        assertEquals(bytes.size, p)
    }

    private val getevent = """
add device 1: /dev/input/event3
  bus:      0005
  name:     "Joy-Con (R)"
  events:
    KEY (0001): 0130  0131  0133  0134  0137  0139  013b  013c
                013e
    ABS (0003): 0003  : value 0, min -32767, max 32767, fuzz 250, flat 500, resolution 0
                0004  : value 0, min -32767, max 32767, fuzz 250, flat 500, resolution 0
  input props:
    <none>
add device 2: /dev/input/event4
  name:     "Joy-Con (R) IMU"
  events:
    ABS (0003): 0000  : value 0, min -32767, max 32767, fuzz 10, flat 0, resolution 4096
                0001  : value 0, min -32767, max 32767, fuzz 10, flat 0, resolution 4096
                0002  : value 0, min -32767, max 32767, fuzz 10, flat 0, resolution 4096
                0003  : value 0, min -32767000, max 32767000, fuzz 10, flat 0, resolution 14247
                0004  : value 0, min -32767000, max 32767000, fuzz 10, flat 0, resolution 14247
                0005  : value 0, min -32767000, max 32767000, fuzz 10, flat 0, resolution 14247
  input props:
    INPUT_PROP_ACCELEROMETER
add device 3: /dev/input/event0
  name:     "gpio-keys"
  events:
    KEY (0001): 0072  0073  0074
  input props:
    <none>
""".trimIndent()

    @Test fun findsTheJoyConAndItsImu() {
        val devs = Evdev.parseGeteventP(getevent)
        assertEquals(3, devs.size)
        val (pad, motion) = Evdev.pick(devs)
        assertEquals("/dev/input/event3", pad!!.path)
        assertTrue(Evdev.BTN_THUMBR in pad.keys)
        assertEquals("/dev/input/event4", motion!!.path)
        assertEquals(14247, motion.abs[Evdev.ABS_RX]!!.resolution)
    }

    @Test fun decodesInputEventsAndUnits() {
        val (pad, motion) = Evdev.pick(Evdev.parseGeteventP(getevent))
        val st = EvdevPad(pad!!, motion)
        fun ev(type: Int, code: Int, value: Int): ByteArray {
            val b = ByteArrayOutputStream(); val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            bb.putLong(1); bb.putLong(2); bb.putShort(type.toShort()); bb.putShort(code.toShort()); bb.putInt(value)
            b.write(bb.array()); return b.toByteArray()
        }
        val raw = ev(Evdev.EV_KEY, Evdev.BTN_EAST, 1) + ev(Evdev.EV_ABS, Evdev.ABS_RX, 32767) + ev(Evdev.EV_SYN, 0, 0)
        val events = Evdev.parse(raw, raw.size, is64Bit = true)
        assertEquals(3, events.size)
        assertEquals(false, st.onPadEvent(events[0])); st.onPadEvent(events[1])
        assertTrue(st.onPadEvent(events[2]))
        assertTrue(st.daydreamButtons() and (1 shl DaydreamProtocol.BUTTON_CLICK) != 0)
        assertEquals(1f, st.stick()[0], 1e-3f)

        // 90 deg/s on the gyro, 1 g on the accelerometer
        st.onMotionEvent(Evdev.Event(Evdev.EV_ABS, Evdev.ABS_RY, 14247 * 90))
        st.onMotionEvent(Evdev.Event(Evdev.EV_ABS, Evdev.ABS_Z, 4096))
        assertEquals((PI / 2).toFloat(), st.gyro()!![1], 1e-4f)
        assertEquals(9.80665f, st.accel()!![2], 1e-4f)
        // 32-bit readers use the 16-byte struct
        assertEquals(16, Evdev.eventSize(false))
    }

    @Test fun gyroTurnsTheControllerAndHomeHoldRecenters() {
        val c = DaydreamController()
        var t = 1_000_000_000L
        fun step(buttons: Int = 0, gyroY: Float = 0f) = c.update(DaydreamController.Input(t, buttons, 0f, 0f, floatArrayOf(0f, gyroY, 0f), floatArrayOf(0f, 9.80665f, 0f))).also { t += 10_000_000L }
        step()
        repeat(100) { step(gyroY = (PI / 2).toFloat()) }               // 1 s at 90 deg/s to the left
        val fwd = Quat.rotate(c.orientation(), floatArrayOf(0f, 0f, -1f))
        assertEquals(-1f, fwd[0], 0.03f)                                // now points left (-X)
        // hold HOME 1 s: recenter, and no home button event
        val home = 1 shl DaydreamProtocol.BUTTON_HOME
        var sawRecenter = false
        repeat(100) { val f = step(home); assertTrue(f.buttons.isEmpty()); if (c.recentered) sawRecenter = true }
        val released = step()
        assertTrue(sawRecenter)
        assertTrue(released.buttons.isEmpty())
        val again = Quat.rotate(c.orientation(), floatArrayOf(0f, 0f, -1f))
        assertEquals(-1f, again[2], 1e-3f)
        // a short press is a home click
        step(home); val up = step()
        assertEquals(2, up.buttons.size)
        assertEquals(DaydreamProtocol.BUTTON_HOME, up.buttons[0].button)
    }

    @Test fun tiltCorrectionPullsTowardGravity() {
        val c = DaydreamController()
        var t = 1L
        // start the estimate tilted by rolling 0.5 rad, then hold still with gravity on +Y
        c.update(DaydreamController.Input(t, 0, 0f, 0f, floatArrayOf(0f, 0f, 0f), null)); t += 10_000_000L
        c.update(DaydreamController.Input(t, 0, 0f, 0f, floatArrayOf(0f, 0f, 50f), null)); t += 10_000_000L
        val tilted = Quat.rotate(c.orientation(), floatArrayOf(0f, 1f, 0f))[1]
        repeat(400) { c.update(DaydreamController.Input(t, 0, 0f, 0f, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 9.80665f, 0f))); t += 10_000_000L }
        val after = Quat.rotate(c.orientation(), floatArrayOf(0f, 1f, 0f))[1]
        assertTrue(tilted < 0.9f)
        assertTrue(after > 0.995f)
    }

    @Test fun stickIsTheTouchpadAndButtonsAreEdges() {
        val c = DaydreamController()
        val head = floatArrayOf(0f, 0f, 0f, 1f)
        var t = 1L
        fun step(b: Int, x: Float, y: Float) = c.update(DaydreamController.Input(t, b, x, y, head = head)).also { t += 10_000_000L }
        val click = 1 shl DaydreamProtocol.BUTTON_CLICK
        val a = step(click, 1f, 1f)
        assertEquals(1, a.buttons.size)
        assertEquals(DaydreamProtocol.TOUCH_DOWN, a.touch!!.action)
        assertEquals(1f, a.touch!!.x, 0f); assertEquals(0f, a.touch!!.y, 0f)   // top-right of the pad
        val b = step(click, 0.9f, 0.9f)
        assertTrue(b.buttons.isEmpty())
        assertEquals(DaydreamProtocol.TOUCH_MOVE, b.touch!!.action)
        val c2 = step(0, 0f, 0f)
        assertEquals(false, c2.buttons.single().down)
        assertEquals(DaydreamProtocol.TOUCH_UP, c2.touch!!.action)
        assertNull(step(0, 0f, 0f).touch)
        // without a gyro the orientation is the head's
        assertNotNull(c2.orientation)
        assertEquals(1f, abs(c2.orientation!![3]), 1e-6f)
    }

    @Test fun axisMapFlipsAndSwaps() {
        val c = DaydreamController(intArrayOf(2, -3, 1))
        val v = c.map(floatArrayOf(1f, 2f, 3f))
        assertEquals(2f, v[0]); assertEquals(-3f, v[1]); assertEquals(1f, v[2])
    }
}
