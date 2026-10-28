package com.hyperxr

import com.hyperxr.xrapps.CardboardParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CardboardParamsTest {
    /** Minimal protobuf reader: field number -> raw values (varint as Long, fixed32 as Float, bytes). */
    private fun decode(b: ByteArray): Map<Int, List<Any>> {
        val out = HashMap<Int, MutableList<Any>>()
        var i = 0
        fun varint(): Long { var r = 0L; var sh = 0; while (true) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl sh); if (x < 0x80) return r; sh += 7 } }
        while (i < b.size) {
            val key = varint().toInt(); val field = key ushr 3
            val v: Any = when (key and 7) {
                0 -> varint()
                5 -> ByteBuffer.wrap(b, i, 4).order(ByteOrder.LITTLE_ENDIAN).float.also { i += 4 }
                2 -> { val n = varint().toInt(); b.copyOfRange(i, i + n).also { i += n } }
                else -> error("wire type")
            }
            out.getOrPut(field) { ArrayList() } += v
        }
        return out
    }

    @Test fun deviceParamsLayout() {
        val v = CardboardParams.fromSettings(lensSeparationMm = 62f, fovDegrees = 100f, k1 = 0.3f, k2 = 0.1f)
        val file = CardboardParams.fileBytes(v)
        val bb = ByteBuffer.wrap(file)
        assertEquals(0x35587A2B, bb.int)
        val len = bb.int
        assertEquals(file.size - 8, len)
        val f = decode(file.copyOfRange(8, file.size))
        assertEquals("HyperMagic", String(f[1]!![0] as ByteArray))
        assertEquals(0.062f, f[4]!![0] as Float, 1e-6f)
        val fov = ByteBuffer.wrap(f[5]!![0] as ByteArray).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4) { assertEquals(50f, fov.float, 1e-6f) }
        val k = ByteBuffer.wrap(f[7]!![0] as ByteArray).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0.3f, k.float, 1e-6f); assertEquals(0.1f, k.float, 1e-6f)
        assertEquals(3L, f[12]!![0])
        assertTrue(CardboardParams.qrUrl(v).startsWith("https://google.com/cardboard/cfg?p="))
    }
}
