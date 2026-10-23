package com.hyperxr

import com.hyperxr.xrapps.ApkBundle
import com.hyperxr.xrapps.QuestApk
import com.hyperxr.xrapps.XrApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Daydream apps that do not declare the DAYDREAM launcher category (unity_daydream_manifest.axml,
 * compiled with aapt2: enableVrMode, the Daydream Home icon, the VR features), and split bundles.
 */
class DaydreamDetectionTest {
    private fun tmp(name: String) = File(Files.createTempDirectory("dd").toFile(), name)
    private val unityManifest = javaClass.getResourceAsStream("/unity_daydream_manifest.axml")!!.readBytes()

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bo.toByteArray()
    }

    @Test fun daydreamWithoutTheCategoryIsDetected() {
        val apk = tmp("u.apk").apply { writeBytes(zip(mapOf("AndroidManifest.xml" to unityManifest, "lib/armeabi-v7a/libgvr.so" to ByteArray(8),
            "lib/armeabi-v7a/libunity.so" to ByteArray(8)))) }
        val r = QuestApk.inspect(apk, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(XrApi.DAYDREAM, r.xrApi)
        assertTrue(r.daydream)
        assertTrue(r.reasons.toString(), r.reasons.any { it.contains("enableVrMode") && it.contains("ícone") })
        assertEquals("Unity", r.engine)
        assertEquals("com.unity3d.player.UnityPlayerActivity", r.launchActivity)
    }

    @Test fun splitBundleBecomesOneApk() {
        val base = zip(mapOf("AndroidManifest.xml" to unityManifest, "classes.dex" to ByteArray(4), "META-INF/CERT.RSA" to ByteArray(3)))
        val arm64 = zip(mapOf("AndroidManifest.xml" to ByteArray(0), "lib/arm64-v8a/libgvr.so" to ByteArray(16), "lib/arm64-v8a/libunity.so" to ByteArray(16)))
        val v7a = zip(mapOf("lib/armeabi-v7a/libgvr.so" to ByteArray(16)))
        val xapk = tmp("game.xapk").apply {
            writeBytes(zip(mapOf("manifest.json" to "{}".toByteArray(), "com.example.unitydaydream.apk" to base,
                "config.arm64_v8a.apk" to arm64, "config.armeabi_v7a.apk" to v7a, "config.xxhdpi.apk" to zip(mapOf("res/x.png" to ByteArray(2))))))
        }
        assertTrue(ApkBundle.isBundle(xapk))
        val m = ApkBundle.merge(xapk, tmp("merged.apk"), listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("com.example.unitydaydream.apk", m.base)
        assertEquals("config.arm64_v8a.apk", m.abiSplit)
        ZipFile(m.apk).use { z ->
            assertNotNull(z.getEntry("AndroidManifest.xml"))
            assertNotNull(z.getEntry("lib/arm64-v8a/libgvr.so"))
            assertEquals(null, z.getEntry("lib/armeabi-v7a/libgvr.so"))
            assertEquals(null, z.getEntry("META-INF/CERT.RSA"))
            assertEquals(ZipEntry.STORED, z.getEntry("lib/arm64-v8a/libgvr.so").method)
        }
        // and the merged APK is analysed like any other
        assertEquals(XrApi.DAYDREAM, QuestApk.inspect(m.apk, listOf("arm64-v8a")).xrApi)
        // a 32-bit only phone takes the 32-bit split
        assertEquals("config.armeabi_v7a.apk", ApkBundle.merge(xapk, tmp("m32.apk"), listOf("armeabi-v7a")).abiSplit)
    }
}
