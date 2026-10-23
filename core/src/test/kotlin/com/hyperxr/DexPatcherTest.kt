package com.hyperxr

import com.hyperxr.xrapps.DexPatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * daydream_check.dex (built with d8 from a small "game" that refuses to start without a Daydream
 * phone, and a stand-in for the GVR SDK that asks the same question internally).
 */
class DexPatcherTest {
    private val dex = javaClass.getResourceAsStream("/dex/daydream_check.dex")!!.readBytes()

    @Test fun appCallSitesAnswerYesSdkKeepsTheTruth() {
        val r = DexPatcher.patch(dex)
        // Game.start and Game.many call it; DaydreamApi.sdkInternalCheck (inside the SDK) is left alone
        assertEquals(2, r.sites)
        assertTrue(r.methods.toString(), r.methods.all { it.startsWith("com.example.game.Game.") })
        assertEquals(dex.size, r.dex.size)
        java.io.File("build/patched_daydream_check.dex").writeBytes(r.dex)  // for dexdump by hand
        // header checksum and signature match the new content
        val b = ByteBuffer.wrap(r.dex).order(ByteOrder.LITTLE_ENDIAN)
        val adler = Adler32().apply { update(r.dex, 12, r.dex.size - 12) }
        assertEquals(adler.value.toInt(), b.getInt(8))
        val sha = MessageDigest.getInstance("SHA-1").digest(r.dex.copyOfRange(32, r.dex.size))
        assertTrue(sha.contentEquals(r.dex.copyOfRange(12, 32)))
    }

    @Test fun nothingToPatchLeavesTheDexAsIs() {
        val r = DexPatcher.patch(dex, listOf("Lcom/nothing/Here;" to "x"))
        assertEquals(0, r.sites)
        assertTrue(r.dex.contentEquals(dex))
    }
}
