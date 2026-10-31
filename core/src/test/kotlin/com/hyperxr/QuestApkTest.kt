package com.hyperxr

import com.hyperxr.xrapps.BinaryXml
import com.hyperxr.xrapps.LoaderKind
import com.hyperxr.xrapps.QuestApk
import com.hyperxr.xrapps.StringScan
import com.hyperxr.xrapps.Verdict
import com.hyperxr.xrapps.XrApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class QuestApkTest {
    // questgame_manifest.xml compiled with `aapt2 link --manifest` (binary AXML as found in an APK)
    private val manifestBytes = javaClass.getResourceAsStream("/questgame_manifest.axml")!!.readBytes()

    @Test fun parsesBinaryManifest() {
        val m = BinaryXml.parse(manifestBytes)
        assertEquals("manifest", m.name)
        assertEquals("com.example.questgame", m.attr("package"))
        assertEquals("42", m.attr("versionCode"))
        assertEquals("1.2.3", m.attr("versionName"))
        assertEquals("29", m.all("uses-sdk")[0].attr("minSdkVersion"))
        val meta = m.all("application")[0].all("meta-data")[0]
        assertEquals("quest2|questpro|quest3", meta.attr("value"))
        val cats = m.descendants("category").map { it.attr("name") }
        assertTrue("com.oculus.intent.category.VR" in cats)
    }

    // openxr_manifest.xml: a plain Android OpenXR game (no headset markers), compiled the same way
    private val openxrManifest = javaClass.getResourceAsStream("/openxr_manifest.axml")!!.readBytes()

    private fun apk(libs: Map<String, ByteArray>, manifest: ByteArray = manifestBytes): File {
        val f = Files.createTempFile("game", ".apk").toFile()
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifest); z.closeEntry()
            for ((n, b) in libs) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return f
    }

    private fun bin(vararg strings: String): ByteArray = strings.joinToString("\u0000") { it }.toByteArray() + byteArrayOf(0)

    @Test fun questGameIsRefused() {
        val r = QuestApk.inspect(apk(mapOf(
            "lib/arm64-v8a/libopenxr_loader.so" to bin("org.khronos.openxr.runtime_broker", "XR_KHR_opengl_es_enable"),
            "lib/arm64-v8a/libunity.so" to bin("XR_FB_passthrough", "XR_EXT_hand_tracking"),
            "lib/arm64-v8a/libil2cpp.so" to ByteArray(10),
        )))
        assertEquals("com.example.questgame", r.packageName)
        assertEquals(42L, r.versionCode)
        assertEquals("Unity", r.engine)
        assertEquals(XrApi.OPENXR, r.xrApi)
        assertTrue(r.questApp)
        assertEquals("com.example.questgame.MainActivity", r.launchActivity)
        assertEquals(Verdict.WONT_RUN, r.verdict)
        assertTrue(r.reasons.any { "Feito para Quest" in it })
    }

    @Test fun khronosLoaderGlesGameRuns() {
        val r = QuestApk.inspect(apk(mapOf(
            "lib/arm64-v8a/libopenxr_loader.so" to bin("org.khronos.openxr.runtime_broker", "XR_KHR_opengl_es_enable"),
            "lib/arm64-v8a/libgodot_android.so" to bin("XR_EXT_hand_tracking", "XR_KHR_composition_layer_cylinder"),
        ), openxrManifest))
        assertEquals("com.example.openxrgame", r.packageName)
        assertEquals("Godot", r.engine)
        assertEquals(LoaderKind.KHRONOS_BROKER, r.loader)
        assertEquals("com.example.openxrgame.GameActivity", r.launchActivity)
        assertEquals(Verdict.LIKELY_RUNS, r.verdict)
        assertTrue(r.reasons.any { "hand tracking" in it })
    }

    @Test fun vendorExtensionsAndLoadersHoldAGameBack() {
        val ext = QuestApk.inspect(apk(mapOf(
            "lib/arm64-v8a/libopenxr_loader.so" to bin("org.khronos.openxr.runtime_broker", "XR_KHR_opengl_es_enable"),
            "lib/arm64-v8a/libmain.so" to bin("XR_FB_display_refresh_rate", "XR_FB_passthrough"),
        ), openxrManifest))
        assertEquals(Verdict.MAY_RUN, ext.verdict)
        assertTrue(ext.reasons.any { "2 extensões de fabricante" in it })
        val metaLoader = QuestApk.inspect(apk(mapOf("lib/arm64-v8a/libopenxr_loader.so" to bin("libopenxr_forwardloader", "XR_KHR_opengl_es_enable")), openxrManifest))
        assertEquals(Verdict.WONT_RUN, metaLoader.verdict)
    }

    @Test fun picoAppIsRefused() {
        val r = QuestApk.inspect(apk(mapOf(
            "lib/arm64-v8a/libopenxr_loader.so" to bin("org.khronos.openxr.runtime_broker", "XR_KHR_opengl_es_enable", "XR_BD_controller_interaction"),
            "lib/arm64-v8a/libpxr_api.so" to bin("pxr"),
        ), openxrManifest))
        assertTrue(r.picoApp)
        assertEquals(Verdict.WONT_RUN, r.verdict)
        assertTrue(r.reasons.any { "Feito para Pico" in it })
    }

    @Test fun vrapiAndVulkanOnlyWontRun() {
        val vrapi = QuestApk.inspect(apk(mapOf("lib/arm64-v8a/libvrapi.so" to bin("vrapi_Initialize"))))
        assertEquals(XrApi.VRAPI, vrapi.xrApi); assertEquals(Verdict.WONT_RUN, vrapi.verdict)
        val vk = QuestApk.inspect(apk(mapOf("lib/arm64-v8a/libopenxr_loader.so" to bin("org.khronos.openxr.runtime_broker", "XR_KHR_vulkan_enable2"))))
        assertEquals(Verdict.WONT_RUN, vk.verdict)
        val armOnly = QuestApk.inspect(apk(mapOf("lib/x86_64/libopenxr_loader.so" to bin("x"))))
        assertEquals(Verdict.WONT_RUN, armOnly.verdict)
    }

    @Test fun daydreamOnlyDetected() {
        val dd = QuestApkTest::class.java.getResourceAsStream("/daydream_manifest.axml")!!.readBytes()
        val f = Files.createTempFile("dd", ".apk").toFile()
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(dd); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/arm64-v8a/libgvr.so")); z.write(ByteArray(8)); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(bin("x", "Lcom/google/vr/sdk/controller/ControllerManager;")); z.closeEntry()
        }
        val r = QuestApk.inspect(f)
        assertEquals(XrApi.DAYDREAM, r.xrApi)
        assertEquals(Verdict.WONT_RUN, r.verdict)
        assertTrue(r.daydreamController)
        assertEquals("com.example.daydreamgame.VrActivity", r.launchActivity)
    }

    @Test fun scanFindsStringsAcrossChunkBoundaries() {
        val s = StringScan()
        val big = ByteArray(70_000) + "XR_META_body_tracking_fidelity".toByteArray() + ByteArray(3)
        s.feed(big)
        assertTrue("XR_META_body_tracking_fidelity" in s.extensions())
        assertTrue(QuestApk.isValidObbName("main.42.com.example.questgame.obb"))
    }

    @Test fun fridaGadgetIsReported() {
        val r = QuestApk.inspect(apk(mapOf(
            "lib/arm64-v8a/libopenxr_loader.so" to bin("org.khronos.openxr.runtime_broker", "XR_KHR_opengl_es_enable"),
            "lib/arm64-v8a/libunity.so" to ByteArray(10),
            "lib/arm64-v8a/libfrda.so" to ByteArray(10),
            "lib/arm64-v8a/libfrda.config.so" to ByteArray(10),
        )))
        assertTrue(r.reasons.any { it.contains("Frida") && it.contains("libfrda.config.so") })
    }
}
