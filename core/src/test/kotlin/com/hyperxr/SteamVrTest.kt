package com.hyperxr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.input.ControllerButton
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.steamvr.HxvrProtocol
import com.hyperxr.steamvr.PlaySpace
import com.hyperxr.steamvr.SteamVrInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class SteamVrTest {
    private fun payload(msg: ByteArray, type: Byte): ByteBuffer {
        val b = ByteBuffer.wrap(msg).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(msg.size - 4, b.int)
        assertEquals(type, b.get())
        return b.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    @Test fun helloRoundTrip() {
        val h = HxvrProtocol.Hello(1200, 1080, 90, 1.1f, 0.9f, 1.0f, 1.2f, 0.063f, "Pixel 8")
        assertEquals(h, HxvrProtocol.parseHello(payload(HxvrProtocol.hello(h), HxvrProtocol.HELLO)))
    }

    @Test fun trackingRoundTripAndSize() {
        val t = HxvrProtocol.Tracking().apply {
            timeNs = 123456789L; head[1] = 1.65f
            controllers[1].flags = 7; controllers[1].buttons = HxvrProtocol.BTN_PRIMARY; controllers[1].trigger = 0.5f; controllers[1].curls[3] = 0.9f
        }
        val msg = HxvrProtocol.tracking(t)
        assertEquals(5 + HxvrProtocol.TRACKING_SIZE, msg.size)
        assertEquals(174, HxvrProtocol.TRACKING_SIZE)
        val r = HxvrProtocol.parseTracking(payload(msg, HxvrProtocol.TRACKING))!!
        assertEquals(123456789L, r.timeNs)
        assertArrayEquals(t.head, r.head, 0f)
        assertEquals(7, r.controllers[1].flags); assertEquals(0.5f, r.controllers[1].trigger); assertEquals(0.9f, r.controllers[1].curls[3])
    }

    @Test fun videoAndConfig() {
        val data = byteArrayOf(0, 0, 0, 1, 0x67)
        val p = payload(HxvrProtocol.video(HxvrProtocol.VideoHeader(5, 99, 0f, 0f, 0f, 1f, true), data), HxvrProtocol.VIDEO)
        val v = HxvrProtocol.parseVideo(p)!!
        assertEquals(5L, v.frameId); assertTrue(v.keyFrame)
        assertEquals(data.size, p.remaining())
        val c = HxvrProtocol.Config(1200, 1080, 90, 30000, 0, true)
        assertEquals(c, HxvrProtocol.parseConfig(payload(HxvrProtocol.config(c), HxvrProtocol.CONFIG)))
    }

    /** Right hand, palm down, fingers pointing -Z: the grip frame is the identity. */
    private fun flatRightHand(curl: Boolean = false): Array<Vec3> {
        val j = Array(HandJoint.COUNT) { Vec3() }
        j[HandJoint.WRIST].set(-0.01f, 0f, 0f)
        val xs = floatArrayOf(-0.03f, -0.01f, 0.01f, 0.03f)  // index (thumb side, -X) to pinky
        for ((f, mcp) in listOf(HandJoint.INDEX_MCP, HandJoint.MIDDLE_MCP, HandJoint.RING_MCP, HandJoint.PINKY_MCP).withIndex()) {
            j[mcp].set(xs[f], 0f, -0.09f)
            for (k in 1..3) j[mcp + k].set(xs[f], if (curl) -0.02f * k else 0f, if (curl) -0.09f - 0.01f * (if (k == 1) 1 else 0) else -0.09f - 0.03f * k)
        }
        j[HandJoint.THUMB_CMC].set(-0.03f, 0f, -0.02f); j[HandJoint.THUMB_MCP].set(-0.05f, 0f, -0.04f)
        j[HandJoint.THUMB_IP].set(-0.06f, 0f, -0.06f); j[HandJoint.THUMB_TIP].set(-0.07f, 0f, -0.08f)
        return j
    }

    @Test fun handPoseOfFlatRightHandIsIdentity() {
        val pos = Vec3(); val rot = Quat()
        SteamVrInput.handPose(flatRightHand(), right = true, outPos = pos, outRot = rot)
        assertTrue(rot.toString(), abs(abs(rot.w) - 1f) < 1e-3f)
        assertEquals(-0.045f, pos.z, 1e-4f)
    }

    @Test fun curlsOpenAndClosed() {
        val open = SteamVrInput.curls(flatRightHand())
        assertTrue(open.drop(1).all { it < 0.1f })
        val fist = SteamVrInput.curls(flatRightHand(curl = true))
        assertTrue(fist.joinToString(), fist.drop(1).all { it > 0.5f })
    }

    @Test fun joyConMapsToTouchButtons() {
        val c = HxvrProtocol.Controller()
        val held = setOf(ControllerButton.ZR, ControllerButton.B, ControllerButton.R)
        SteamVrInput.fromJoyCon({ it in held }, 0f, 0.5f, c)
        assertEquals(1f, c.trigger); assertEquals(1f, c.grip)
        assertTrue(c.buttons and HxvrProtocol.BTN_PRIMARY != 0)
        assertTrue(c.buttons and HxvrProtocol.BTN_STICK_TOUCH != 0)
        assertEquals(0.5f, c.joyY)
    }

    @Test fun playSpaceRecenter() {
        val s = PlaySpace(1.6f)
        val headRot = PlaySpace.yaw(1.0f)
        s.recenter(Vec3(2f, 0.3f, -1f), headRot)
        val p = Vec3(); val r = Quat()
        s.apply(Vec3(2f, 0.3f, -1f), headRot, p, r)
        assertEquals(0f, p.x, 1e-5f); assertEquals(1.6f, p.y, 1e-5f); assertEquals(0f, p.z, 1e-5f)
        assertEquals(0f, PlaySpace.yawOf(r), 1e-4f)
        // a step forward along the old facing becomes a step along -Z
        val f = headRot.rotate(Vec3(0f, 0f, -1f))
        s.apply(Vec3(2f + f.x, 0.3f, -1f + f.z), headRot, p, r)
        assertEquals(-1f, p.z, 1e-4f); assertEquals(0f, p.x, 1e-4f)
    }
}
