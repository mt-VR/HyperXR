package com.hyperxr

import com.hyperxr.environments.PanoramaStitcher
import com.hyperxr.handtracking.PersonBreakthrough
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class RoomFeaturesTest {
    // ------------------------------------------------------------ person breakthrough

    private fun scene(n: Int, withPerson: Boolean): ByteArray {
        val l = ByteArray(n * n)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in y0 until y1) for (x in x0 until x1) l[y * n + x] = c.toByte() }
        // the wearer's own shirt at the bottom of the view, and their hand
        fill(0, n - 30, n, n, PersonBreakthrough.CLOTHES)
        fill(20, 150, 50, 190, PersonBreakthrough.SKIN)
        if (withPerson) {
            fill(120, 40, 150, 50, PersonBreakthrough.HAIR)
            fill(120, 50, 150, 80, PersonBreakthrough.FACE)
            fill(105, 80, 165, 200, PersonBreakthrough.CLOTHES)   // their body, not touching the shirt below
        }
        return l
    }

    @Test fun showsTheVisitorButNotTheWearersOwnBody() {
        val n = 256
        val p = PersonBreakthrough(n)
        var t = 0L
        assertNull(p.update(scene(n, true), t))           // first sighting: not yet
        t += 100_000_000L
        val m = p.update(scene(n, true), t)
        assertNotNull(m)
        fun at(x: Int, y: Int) = m!![y * n + x].toInt() and 0xff
        assertEquals(255, at(135, 60))                     // face
        assertEquals(255, at(135, 150))                    // their clothes, grown from the face
        assertEquals(0, at(128, n - 10))                   // own shirt
        assertEquals(0, at(30, 170))                       // own hand
        assertEquals(0, at(10, 10))                        // background
        // gone: held for a moment, then hidden
        t += 300_000_000L
        assertNotNull(p.update(scene(n, false), t))
        t += 600_000_000L
        assertNull(p.update(scene(n, false), t))
        assertTrue(!p.present)
    }

    @Test fun nobodyNoMask() {
        val p = PersonBreakthrough(256)
        repeat(5) { assertNull(p.update(scene(256, false), it * 100_000_000L)) }
    }

    // ------------------------------------------------------------ room panorama

    /** The "room": colour from the direction, so any pixel can be checked. */
    private fun world(dx: Float, dy: Float, dz: Float): Int {
        val r = ((dx * 0.5f + 0.5f) * 255).toInt(); val g = ((dy * 0.5f + 0.5f) * 255).toInt(); val b = ((dz * 0.5f + 0.5f) * 255).toInt()
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun quat(yaw: Float, pitch: Float): FloatArray {
        // yaw about +Y, then pitch about the camera's +X
        val qy = floatArrayOf(0f, sin(yaw / 2), 0f, cos(yaw / 2)); val qx = floatArrayOf(sin(pitch / 2), 0f, 0f, cos(pitch / 2))
        return floatArrayOf(
            qy[3] * qx[0] + qy[0] * qx[3] + qy[1] * qx[2] - qy[2] * qx[1],
            qy[3] * qx[1] - qy[0] * qx[2] + qy[1] * qx[3] + qy[2] * qx[0],
            qy[3] * qx[2] + qy[0] * qx[1] - qy[1] * qx[0] + qy[2] * qx[3],
            qy[3] * qx[3] - qy[0] * qx[0] - qy[1] * qx[1] - qy[2] * qx[2])
    }

    /** Renders what a 64-degree camera sees with orientation q. */
    private fun shoot(q: FloatArray, w: Int, h: Int, f: Float): IntArray {
        val x = q[0]; val y = q[1]; val z = q[2]; val ww = q[3]
        val m = floatArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - z * ww), 2 * (x * z + y * ww),
            2 * (x * y + z * ww), 1 - 2 * (x * x + z * z), 2 * (y * z - x * ww),
            2 * (x * z - y * ww), 2 * (y * z + x * ww), 1 - 2 * (x * x + y * y))
        return IntArray(w * h) { i ->
            val a = (i % w + 0.5f - w / 2f) / f; val b = -(i / w + 0.5f - h / 2f) / f
            var dx = m[0] * a + m[1] * b - m[2]; var dy = m[3] * a + m[4] * b - m[5]; var dz = m[6] * a + m[7] * b - m[8]
            val n = sqrt(dx * dx + dy * dy + dz * dz); dx /= n; dy /= n; dz /= n
            world(dx, dy, dz)
        }
    }

    @Test fun stitchesTurningAroundIntoTheRightPlaces() {
        val pano = PanoramaStitcher(512)
        val w = 160; val h = 120; val f = 128f
        for (pitch in listOf(-0.6f, 0f, 0.6f)) for (k in 0 until 12) pano.add(shoot(quat((k * PI / 6).toFloat(), pitch), w, h, f), w, h, f, f, w / 2f, h / 2f, quat((k * PI / 6).toFloat(), pitch))
        assertTrue("coverage ${pano.coverage()}", pano.coverage() > 0.8f)
        // every covered pixel shows the room in its own direction
        var checked = 0; var good = 0
        for (row in 0 until pano.height) for (col in 0 until pano.width) {
            val i = row * pano.width + col
            if (pano.weight[i] <= 0f) continue
            val lat = (0.5 - (row + 0.5) / pano.height) * PI; val lon = ((col + 0.5) / pano.width - 0.5) * 2 * PI
            val want = world((sin(lon) * cos(lat)).toFloat(), sin(lat).toFloat(), (-cos(lon) * cos(lat)).toFloat())
            val got = pano.color[i]
            val err = listOf(16, 8, 0).maxOf { s -> kotlin.math.abs(((want shr s) and 0xff) - ((got shr s) and 0xff)) }
            checked++; if (err <= 10) good++
        }
        assertTrue("$good of $checked", good > checked * 0.97)
        // the finished panorama has no holes
        assertTrue(pano.result().all { (it ushr 24) == 0xff })
    }

    @Test fun lookingStraightUpReachesThePole() {
        val pano = PanoramaStitcher(256)
        val w = 100; val h = 100; val f = 60f
        val q = quat(0f, (PI / 2).toFloat())
        pano.add(shoot(q, w, h, f), w, h, f, f, w / 2f, h / 2f, q)
        // the top row (the ceiling right above) got pixels all the way around
        val top = (0 until pano.width).count { pano.weight[it] > 0f }
        assertEquals(pano.width, top)
    }
}
