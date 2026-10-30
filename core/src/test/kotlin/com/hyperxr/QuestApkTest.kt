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

    private fun apk(libs: Map<String, ByteArray>): File {
        val f = Files.createTempFile("game", ".apk").toFile()
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifestBytes); z.closeEntry()
            for ((n, b) in libs) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return f
    }

    private fun bin(vararg strings: String): ByteArray = strings.joinToString("\u0000") { it }.toByteArray() + byteArrayOf(0)

    @Test fun khronosLoaderGlesGameMayRun() {
        val r = QuestApk.inspect(apk(mapOf(
            "lib/arm64-v8a/libopenxr_loader.so" to bin("org.khronos.openxr.runtime_broker", "XR_KHR_opengl_es_enable"),
            "lib/arm64-v8a/libunity.so" to bin("XR_FB_passthrough", "XR_FB_display_refresh_rate", "XR_EXT_hand_tracking", "XR_FB_keyboard_tracking"),
            "lib/arm64-v8a/libil2cpp.so" to ByteArray(10),
        )))
        assertEquals("com.example.questgame", r.packageName)
        assertEquals(42L, r.versionCode)
        assertEquals("Unity", r.engine)
        assertEquals(XrApi.OPENXR, r.xrApi)
        assertEquals(LoaderKind.KHRONOS_BROKER, r.loader)
        assertTrue(r.questApp)
        assertEquals("com.example.questgame.MainActivity", r.launchActivity)
        assertTrue("XR_FB_passthrough" in r.extensions && "XR_EXT_hand_tracking" in r.extensions)
        assertEquals(Verdict.MAY_RUN, r.verdict) // a Meta extension the runtime lacks (tracked keyboard)
    }

    @Test fun metaExtensionsTheRuntimeHasNoLongerHoldAGameBack() {
        val r = QuestApk.inspect(apk(mapOf(
            "lib/arm64-v8a/libopenxr_loader.so" to bin("org.khronos.openxr.runtime_broker", "XR_KHR_opengl_es_enable"),
            "lib/arm64-v8a/libunity.so" to bin("XR_FB_display_refresh_rate", "XR_FB_color_space", "XR_FB_foveation"),
        )))
        assertEquals(Verdict.LIKELY_RUNS, r.verdict)
        assertTrue(r.reasons.any { "3 extensões Meta" in it })
    }

    @Test fun handTrackingGameIsServed() {
        val r = QuestApk.inspect(apk(mapOf(
            "lib/arm64-v8a/libopenxr_loader.so" to bin("org.khronos.openxr.runtime_broker", "XR_KHR_opengl_es_enable"),
            "lib/arm64-v8a/libunity.so" to bin("XR_EXT_hand_tracking", "XR_FB_hand_tracking_aim", "XR_FB_passthrough"),
        )))
        assertEquals(Verdict.LIKELY_RUNS, r.verdict)
        assertTrue(r.reasons.any { "hand tracking" in it })
        assertTrue(r.reasons.any { "passthrough" in it })
    }

    @Test fun picoAppIsRecognised() {
        val r = QuestApk.inspect(apk(mapOf(
            "lib/arm64-v8a/libopenxr_loader.so" to bin("XR_KHR_opengl_es_enable", "XR_BD_controller_interaction", "XR_PICO_configuration"),
            "lib/arm64-v8a/libpxr_api.so" to bin("pxr"),
        )))
        assertTrue(r.picoApp)
        assertTrue(r.reasons.any { "App da Pico" in it })
        assertTrue(r.reasons.any { "XR_PICO_configuration" in it })
        assertTrue(r.reasons.any { "libpxr_api.so" in it })
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
}
