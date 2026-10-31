package com.hyperxr

import com.hyperxr.socialvr.AvatarStyle
import com.hyperxr.socialvr.MapLayout
import com.hyperxr.socialvr.PieceBounds
import com.hyperxr.socialvr.SocialCatalog
import com.hyperxr.socialvr.VrmPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class SocialVrTest {
    @Test fun catalogHasTenAvatarsPerStyleAndTwentyMaps() {
        for (s in AvatarStyle.entries) assertEquals(10, SocialCatalog.AVATARS.count { it.style == s })
        assertEquals(30, SocialCatalog.AVATARS.map { it.id }.distinct().size)
        assertEquals(20, SocialCatalog.MAPS.size)
        assertEquals(20, SocialCatalog.MAPS.map { it.id }.distinct().size)
        assertTrue(SocialCatalog.MAPS.all { it.pieces.isNotEmpty() && it.pieces.size <= 26 })
        assertTrue(SocialCatalog.AVATARS.all { it.url.startsWith("https://arweave.net/") })
    }

    @Test fun layoutKeepsThePlazaFreeAndPiecesApart() {
        val pieces = listOf(PieceBounds("floor", true, -10f, -0.2f, -10f, 10f, 0.1f, 10f)) +
            (0 until 12).map { PieceBounds("p$it", false, -1f, 0.5f, -1f, 1f, 2.5f, 1f) }
        val out = MapLayout.arrange(pieces)
        val floor = out.first { it.name == "floor" }
        assertEquals(-0.1f, floor.y, 1e-6f)                     // floor top at y = 0
        val props = out.filter { it.name != "floor" }
        assertEquals(12, props.size)
        for (p in props) {
            assertEquals(-0.5f, p.y, 1e-6f)                     // standing on the floor
            assertTrue(hypot(p.x, p.z) >= MapLayout.PLAZA)       // outside the plaza
        }
        for (a in props) for (b in props) if (a !== b) assertTrue(hypot(a.x - b.x, a.z - b.z) > 2f)  // no overlaps
        assertEquals(out, MapLayout.arrange(pieces))              // the same map on every phone
    }

    @Test fun relaxArmsLeavesOtherFilesAlone() {
        val junk = byteArrayOf(1, 2, 3)
        assertTrue(VrmPose.relaxArms(junk) === junk)
        val path = System.getenv("HX_VRM_SAMPLE") ?: return
        val bytes = java.io.File(path).readBytes()
        val posed = VrmPose.relaxArms(bytes)
        val before = com.hyperxr.gltf.GltfLoader.load(bytes); val after = com.hyperxr.gltf.GltfLoader.load(posed)
        println("width ${before.boundsMax.x - before.boundsMin.x} -> ${after.boundsMax.x - after.boundsMin.x}, height ${after.boundsMax.y - after.boundsMin.y}")
        assertTrue(after.boundsMax.x - after.boundsMin.x < 0.7f * (before.boundsMax.x - before.boundsMin.x))
    }
}
