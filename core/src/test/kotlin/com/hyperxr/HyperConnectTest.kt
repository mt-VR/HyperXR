package com.hyperxr

import com.hyperxr.connect.HyperConnectProtocol as P
import com.hyperxr.connect.PhoneControllerModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class HyperConnectTest {
    private fun state(q: FloatArray, buttons: Int = 0, touching: Boolean = false, ty: Float = 0.5f, seq: Int = 1) =
        P.State(seq, 123456789L, q, floatArrayOf(0.1f, 0.2f, 0.3f), floatArrayOf(0f, 0f, 9.81f), buttons, touching, 0.25f, ty, 77)

    @Test fun framesSurviveGarbageAndSplitReads() {
        val bytes = byteArrayOf(1, 2, 'H'.code.toByte(), 9) + P.encode(P.Hello("Galaxy A52", leftHanded = true)) +
            P.encode(state(floatArrayOf(0f, 0f, 0f, 1f), P.TRIGGER or P.HOME, true, 0.75f, seq = 42)) + P.encode(P.Vibrate(15, 200))
        val d = P.Decoder()
        val got = ArrayList<Any>()
        var i = 0
        while (i < bytes.size) { val n = minOf(7, bytes.size - i); got.addAll(d.feed(bytes.copyOfRange(i, i + n))); i += n }
        assertEquals(3, got.size)
        val h = got[0] as P.Hello
        assertEquals("Galaxy A52", h.name); assertTrue(h.leftHanded)
        val s = got[1] as P.State
        assertEquals(42, s.seq); assertEquals(123456789L, s.timestampNs)
        assertEquals(P.TRIGGER or P.HOME, s.buttons); assertTrue(s.touching)
        assertEquals(0.75f, s.touchY, 0f); assertEquals(0.2f, s.gyro[1], 0f); assertEquals(9.81f, s.accel[2], 0f); assertEquals(77, s.battery)
        val v = got[2] as P.Vibrate
        assertEquals(15, v.ms); assertEquals(200, v.amplitude)
    }

    /** Android rotation: phone flat, screen up, its top turned [yaw] from north (left positive), tilted up by [pitch]. */
    private fun phone(yaw: Float, pitch: Float = 0f): FloatArray {
        val qz = floatArrayOf(0f, 0f, sin(yaw / 2), cos(yaw / 2))
        val qx = floatArrayOf(sin(pitch / 2), 0f, 0f, cos(pitch / 2))
        return floatArrayOf(
            qz[3] * qx[0] + qz[0] * qx[3] + qz[1] * qx[2] - qz[2] * qx[1],
            qz[3] * qx[1] - qz[0] * qx[2] + qz[1] * qx[3] + qz[2] * qx[0],
            qz[3] * qx[2] + qz[0] * qx[1] - qz[1] * qx[0] + qz[2] * qx[3],
            qz[3] * qx[3] - qz[0] * qx[0] - qz[1] * qx[1] - qz[2] * qx[2])
    }

    private val head = floatArrayOf(0f, 1.6f, 0f)
    private val ahead = floatArrayOf(0f, 0f, 0f, 1f)

    @Test fun pointsWhereThePhoneTopPointsAfterAligningToTheHead() {
        val m = PhoneControllerModel()
        // connected while the phone's top points east: that becomes "ahead"
        m.update(state(phone((-PI / 2).toFloat())), head, ahead, 1L)
        assertTrue(m.recentered)
        assertEquals(-1f, m.rayDirection[2], 1e-4f)
        // turn the phone 90 degrees to the left: the ray points left (-X)
        m.update(state(phone(0f)), head, ahead, 2L)
        assertEquals(-1f, m.rayDirection[0], 1e-4f)
        // tilt it up: the ray rises
        m.update(state(phone((-PI / 2).toFloat(), 0.5f)), head, ahead, 3L)
        assertEquals(sin(0.5f), m.rayDirection[1], 1e-4f)
        // the ray starts at the right hand, below the head
        assertTrue(m.rayOrigin[0] > 0.1f && m.rayOrigin[1] < 1.3f)
    }

    @Test fun homeHeldRecentersHomeTappedRecentersTheView() {
        val m = PhoneControllerModel()
        var t = 0L
        m.update(state(phone(0f)), head, ahead, t)
        // drifted: the phone now points 30 degrees right but the user means "ahead"
        val drifted = phone(-0.5f)
        t += 10_000_000L; m.update(state(drifted), head, ahead, t)
        assertTrue(m.rayDirection[0] > 0.3f)
        repeat(80) { t += 10_000_000L; m.update(state(drifted, P.HOME), head, ahead, t) }
        assertEquals(-1f, m.rayDirection[2], 1e-4f)
        t += 10_000_000L; m.update(state(drifted), head, ahead, t)
        assertFalse(m.homeTapped)
        t += 10_000_000L; m.update(state(drifted, P.HOME), head, ahead, t)
        t += 10_000_000L; m.update(state(drifted), head, ahead, t)
        assertTrue(m.homeTapped)
    }

    @Test fun buttonsAndScrolling() {
        val m = PhoneControllerModel()
        var t = 1L
        m.update(state(phone(0f), P.VOL_DOWN), head, ahead, t)
        assertTrue(m.select)
        t += 20_000_000L; m.update(state(phone(0f), P.BACK, touching = true, ty = 0.6f), head, ahead, t)
        assertTrue(m.back); assertFalse(m.select)
        // finger moving up the pad: natural scrolling, negative
        t += 20_000_000L; m.update(state(phone(0f), 0, touching = true, ty = 0.58f), head, ahead, t)
        assertTrue(m.scroll < 0f)
        t += 20_000_000L; m.update(state(phone(0f)), head, ahead, t)
        assertEquals(0f, m.scroll, 0f)
    }
}
