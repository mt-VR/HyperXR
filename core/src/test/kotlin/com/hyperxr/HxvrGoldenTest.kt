package com.hyperxr

import com.hyperxr.steamvr.HxvrProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone and the PC driver speak the same bytes: the phone's messages are kept as golden files
 * in pc/tests/golden (read back by pc/tests/protocol_test.cpp, which parses them with the driver's
 * own header), and the files the C++ side writes (config, video) are parsed here.
 */
class HxvrGoldenTest {
    private val dir = sequenceOf(File("../pc/tests/golden"), File("pc/tests/golden")).first { it.parentFile.exists() }

    private fun hello() = HxvrProtocol.Hello(1664, 1840, 72, 1.1f, 0.9f, 1.2f, 1.25f, 0.064f, "Galaxy A54", codecs = 3, maxMbps = 40)

    private fun tracking() = HxvrProtocol.Tracking().apply {
        timeNs = 123456789012345L
        floatArrayOf(0.1f, 1.6f, -0.2f, 0f, 0.38268343f, 0f, 0.9238795f).copyInto(head)
        controllers[1].apply {
            flags = HxvrProtocol.CTRL_CONNECTED or HxvrProtocol.CTRL_POSE_VALID
            floatArrayOf(0.25f, 1.2f, -0.4f, 0f, 0f, 0f, 1f).copyInto(pose)
            buttons = HxvrProtocol.BTN_PRIMARY or HxvrProtocol.BTN_TRIGGER_TOUCH
            trigger = 0.9f; grip = 0.25f; joyX = -0.5f; joyY = 0.75f
            curls[1] = 0.8f
        }
    }

    private fun golden(name: String, bytes: ByteArray) {
        val f = File(dir, name)
        if (!f.exists()) f.writeBytes(bytes)
        assertArrayEquals("$name changed: the PC side reads these exact bytes", f.readBytes(), bytes)
    }

    @Test fun phoneMessagesMatchTheGoldenFiles() {
        golden("hello.bin", HxvrProtocol.hello(hello()))
        golden("tracking.bin", HxvrProtocol.tracking(tracking()))
        golden("idr.bin", HxvrProtocol.idrRequest())
    }

    private fun body(name: String): ByteBuffer? {
        val f = File(dir, name)
        if (!f.exists()) return null
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(b.capacity() - 4, b.int)
        return b
    }

    @Test fun pcMessagesParse() {
        body("config.bin")?.let { b ->
            assertEquals(HxvrProtocol.CONFIG, b.get())
            val c = HxvrProtocol.parseConfig(b.slice())!!
            assertEquals(HxvrProtocol.Config(1248, 1376, 60, 25000, 2, false), c)
        }
        body("video.bin")?.let { b ->
            assertEquals(HxvrProtocol.VIDEO, b.get())
            val p = b.slice().order(ByteOrder.LITTLE_ENDIAN)
            val v = HxvrProtocol.parseVideo(p)!!
            assertEquals(42L, v.frameId); assertEquals(987654321L, v.trackingNs); assertTrue(v.keyFrame)
            assertEquals(0.70710677f, v.qy, 1e-6f); assertEquals(0.70710677f, v.qw, 1e-6f)
            val data = ByteArray(p.remaining()).also { p.get(it) }
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0x65), data)
        }
        body("haptic.bin")?.let { b ->
            assertEquals(HxvrProtocol.HAPTIC, b.get())
            assertEquals(HxvrProtocol.Haptic(1, 0.05f, 160f, 0.8f), HxvrProtocol.parseHaptic(b.slice()))
        }
    }
}
