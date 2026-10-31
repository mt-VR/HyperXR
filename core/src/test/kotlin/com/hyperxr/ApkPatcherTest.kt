package com.hyperxr

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.hyperxr.xrapps.ApkPatcher
import com.hyperxr.xrapps.BinaryXml
import com.hyperxr.xrapps.CompatLibraries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkPatcherTest {
    private val manifestBytes = javaClass.getResourceAsStream("/questgame_manifest.axml")!!.readBytes()
    private fun tmp(name: String) = File(Files.createTempDirectory("patch").toFile(), name)

    private fun apk(entries: Map<String, ByteArray>): File {
        val f = tmp("game.apk")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifestBytes); z.closeEntry()
            z.putNextEntry(ZipEntry("META-INF/CERT.RSA")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
            for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return f
    }

    private fun lib(text: String): File = tmp("$text.so").apply { writeText(text) }

    @Test fun manifestEditsInPlace() {
        val (patched, changes) = ApkPatcher.patchManifest(manifestBytes)
        assertEquals(manifestBytes.size, patched.size)
        val m = BinaryXml.parse(patched)
        assertEquals("29", m.all("uses-sdk")[0].attr("targetSdkVersion"))
        assertEquals("false", m.all("uses-feature")[0].attr("required"))
        assertEquals("com.example.questgame", m.attr("package"))
        assertEquals(2, changes.size)
    }

    @Test fun swapsMetaLoaderAndSigns() {
        val libs = CompatLibraries(mapOf("arm64-v8a" to lib("KHRONOS-LOADER")))
        val input = apk(mapOf(
            "lib/arm64-v8a/libopenxr_loader.so" to "libopenxr_forwardloader meta".toByteArray(),
            "lib/arm64-v8a/libunity.so" to ByteArray(5000) { (it % 7).toByte() },
            "assets/data.bin" to ByteArray(20000),
        ))
        val unsigned = tmp("unsigned.apk")
        val report = ApkPatcher.patch(input, unsigned, libs)
        assertTrue(report.blockers.toString(), report.blockers.isEmpty())
        assertTrue(report.questGame && !report.daydream)
        assertEquals("com.example.questgame", report.packageName)
        ZipFile(unsigned).use { z ->
            assertEquals("KHRONOS-LOADER", z.getInputStream(z.getEntry("lib/arm64-v8a/libopenxr_loader.so")).readBytes().decodeToString())
            assertEquals(ZipEntry.STORED, z.getEntry("lib/arm64-v8a/libunity.so").method)
            assertTrue(z.getEntry("META-INF/CERT.RSA") == null)
            assertEquals(20000, z.getInputStream(z.getEntry("assets/data.bin")).readBytes().size)
        }
        // sign as the app does and check Android would accept it
        val ks = KeyStore.getInstance("PKCS12").apply { ApkPatcherTest::class.java.getResourceAsStream("/test-signing.jks").use { load(it, "android".toCharArray()) } }
        val key = ks.getKey("androiddebugkey", "android".toCharArray()) as PrivateKey
        val cert = ks.getCertificate("androiddebugkey") as X509Certificate
        val signed = tmp("signed.apk")
        ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("hyperxr", key, listOf(cert)).build()))
            .setInputApk(unsigned).setOutputApk(signed).setLibraryPageAlignmentBytes(16384).build().sign()
        val v = ApkVerifier.Builder(signed).build().verify()
        assertTrue(v.errors.toString(), v.isVerified)
    }

    @Test fun nothingToDoForKhronosLoaderAndBlocksNonArm() {
        val libs = CompatLibraries(mapOf("arm64-v8a" to lib("NEW")))
        val ok = ApkPatcher.patch(apk(mapOf("lib/arm64-v8a/libopenxr_loader.so" to "org.khronos.openxr.runtime_broker".toByteArray())), tmp("o.apk"), libs, dryRun = true)
        assertFalse(ok.changes.any { it.contains("Loader") })
        val x86 = ApkPatcher.patch(apk(mapOf("lib/x86_64/libmain.so" to ByteArray(3))), tmp("x.apk"), libs)
        assertTrue(x86.blockers.isNotEmpty())
        // OpenXR only: VrApi games are refused
        val vrapi = ApkPatcher.patch(apk(mapOf("lib/arm64-v8a/libvrapi.so" to ByteArray(3))), tmp("v.apk"), libs)
        assertTrue(vrapi.blockers.any { it.contains("VrApi") })
        // both backends (OVRPlugin): the OpenXR one is used
        val both = ApkPatcher.patch(apk(mapOf("lib/arm64-v8a/libvrapi.so" to ByteArray(3), "lib/arm64-v8a/libopenxr_loader.so" to ByteArray(3))), tmp("vb.apk"), libs, dryRun = true)
        assertTrue(both.blockers.none { it.contains("VrApi") })
    }

    @Test fun daydreamOnlyBecomesCardboard() {
        val dd = ApkPatcherTest::class.java.getResourceAsStream("/daydream_manifest.axml")!!.readBytes()
        val input = tmp("dd.apk")
        ZipOutputStream(input.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(dd); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/armeabi-v7a/libgvr.so")); z.write(ByteArray(10)); z.closeEntry()
        }
        val out = tmp("dd-out.apk")
        val r = ApkPatcher.patch(input, out, CompatLibraries(emptyMap()))
        assertTrue(r.blockers.toString(), r.blockers.isEmpty())
        assertTrue(r.daydream)
        val m = ZipFile(out).use { z -> BinaryXml.parse(z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes()) }
        val cats = m.descendants("category").map { it.attr("name") }
        assertTrue(cats.toString(), "com.google.intent.category.CARDBOARD" in cats && "com.google.intent.category.DAYDREAM" !in cats)
        assertTrue("android.intent.category.LAUNCHER" in cats)
        assertTrue(m.all("uses-feature").all { it.attr("required") == "false" })
        assertEquals("com.example.daydreamgame", m.attr("package"))
        assertEquals(".VrActivity", m.descendants("activity")[0].attr("name"))
    }

    private fun daydreamApk(withDex: Boolean): File {
        val dd = ApkPatcherTest::class.java.getResourceAsStream("/daydream_manifest.axml")!!.readBytes()
        val input = tmp("dd2.apk")
        ZipOutputStream(input.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(dd); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/armeabi-v7a/libgvr.so")); z.write(ByteArray(10)); z.closeEntry()
            if (withDex) {
                z.putNextEntry(ZipEntry("classes.dex"))
                z.write(ApkPatcherTest::class.java.getResourceAsStream("/dex/daydream_check.dex")!!.readBytes()); z.closeEntry()
            }
        }
        return input
    }

    @Test fun daydreamChecksInTheAppCodeAreRemoved() {
        val out = tmp("dd2-out.apk")
        val r = ApkPatcher.patch(daydreamApk(true), out, CompatLibraries(emptyMap()), device32Bit = true)
        assertTrue(r.blockers.toString(), r.blockers.isEmpty())
        assertTrue(r.changes.toString(), r.changes.any { it.contains("Daydream?") && it.contains("2 ponto") })
        assertTrue(r.changes.toString(), r.changes.any { it.contains("32 bits") })
        val patched = ZipFile(out).use { z -> z.getInputStream(z.getEntry("classes.dex")).readBytes() }
        val original = ApkPatcherTest::class.java.getResourceAsStream("/dex/daydream_check.dex")!!.readBytes()
        assertFalse(patched.contentEquals(original))
    }

    @Test fun thirtyTwoBitOnlyAppOnSixtyFourBitOnlyPhoneIsExplained() {
        val r = ApkPatcher.patch(daydreamApk(false), tmp("x.apk"), CompatLibraries(emptyMap()), dryRun = true, device32Bit = false)
        assertTrue(r.blockers.toString(), r.blockers.any { it.contains("64 bits") })
    }
}
