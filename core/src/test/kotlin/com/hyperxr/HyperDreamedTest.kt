package com.hyperxr

import com.hyperxr.daydream.DaydreamBle
import com.hyperxr.daydream.DexRules
import com.hyperxr.daydream.VrServicesPatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32

class HyperDreamedTest {
    /** vrservices_checks.dex: d8 output of vrservices_checks_src (Android classes left out, as in a real APK). */
    private val dex = javaClass.getResourceAsStream("/dex/vrservices_checks.dex")!!.readBytes()

    @Test fun vrChecksAnswerYesAndVrModeCallIsRemoved() {
        val r = DexRules.patch(dex, VrServicesPatcher.RULES)
        val h = r.hits
        // three VR feature checks (two in daydreamReady, one with a version), not the camera one
        assertEquals(3, h.entries.first { it.key.startsWith("\"O celular tem modo VR") }.value)
        assertEquals(1, h.entries.first { it.key.startsWith("Troca para o modo VR") }.value)
        assertEquals(1, h.entries.first { it.key.startsWith("Verificação de assinatura") }.value)
        assertEquals(dex.size, r.dex.size)
        val b = ByteBuffer.wrap(r.dex).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Adler32().apply { update(r.dex, 12, r.dex.size - 12) }.value.toInt(), b.getInt(8))
        assertTrue(MessageDigest.getInstance("SHA-1").digest(r.dex.copyOfRange(32, r.dex.size)).contentEquals(r.dex.copyOfRange(12, 32)))
        java.io.File("build/patched_vrservices_checks.dex").writeBytes(r.dex)  // dexdump by hand
        // patching again finds nothing left to do
        assertEquals(0, DexRules.patch(r.dex, VrServicesPatcher.RULES).total)
    }

    @Test fun wholeApkIsPatchedAndUnsigned() {
        val dir = java.nio.file.Files.createTempDirectory("hd").toFile()
        val apk = java.io.File(dir, "in.apk")
        java.util.zip.ZipOutputStream(apk.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() }
            put("AndroidManifest.xml", javaClass.getResourceAsStream("/openxr_manifest.axml")!!.readBytes())
            put("classes.dex", dex)
            put("META-INF/CERT.RSA", byteArrayOf(1, 2, 3))
            put("META-INF/MANIFEST.MF", byteArrayOf(4))
            put("res/raw/x.bin", byteArrayOf(9, 9))
        }
        val out = java.io.File(dir, "out.apk")
        val rep = VrServicesPatcher.patch(apk, out)
        assertTrue(rep.changes.toString(), rep.changes.any { it.startsWith("\"O celular tem modo VR") })
        java.util.zip.ZipFile(out).use { z ->
            assertEquals(null, z.getEntry("META-INF/CERT.RSA")); assertEquals(null, z.getEntry("META-INF/MANIFEST.MF"))
            val patched = z.getInputStream(z.getEntry("classes.dex")).readBytes()
            assertFalse(patched.contentEquals(dex))
            assertEquals(0, DexRules.patch(patched, VrServicesPatcher.RULES).total)
            assertTrue(z.getInputStream(z.getEntry("res/raw/x.bin")).readBytes().contentEquals(byteArrayOf(9, 9)))
        }
        dir.deleteRecursively()
    }

    @Test fun manifestVrFeaturesBecomeOptional() {
        val m = javaClass.getResourceAsStream("/daydream_manifest.axml")!!.readBytes()
        val (out, changes) = VrServicesPatcher.patchManifest(m)
        assertEquals(changes.toString(), 2, changes.count { it.endsWith("deixa de ser obrigatório") })
        // targetSdk 27 stays (only targets below 24 are raised)
        assertFalse(changes.any { it.startsWith("targetSdk") })
        val parsed = com.hyperxr.xrapps.BinaryXml.parse(out)
        assertTrue(parsed.descendants("uses-feature").all { it.attr("required") == "false" })
        assertEquals(VrServicesPatcher.Role.GAME, VrServicesPatcher.roleOf("com.example.daydreamgame", true))
    }

    @Test fun featureRuleNeedsTheVrString() {
        val r = DexRules.patch(dex, listOf(DexRules.Answer("Landroid/content/pm/PackageManager;", "hasSystemFeature", "x", 1, setOf("android.hardware.nfc"))))
        assertEquals(0, r.total)
        assertTrue(r.dex.contentEquals(dex))
    }

    @Test fun bleReportRoundTrips() {
        val s = DaydreamBle.State().apply {
            time = 300; seq = 17
            orientation[0] = 0.5f; orientation[1] = -1.2f; orientation[2] = 3.0f
            accel[0] = 0.1f; accel[1] = -9.8f; accel[2] = 2.5f
            gyro[0] = -0.7f; gyro[1] = 4.2f; gyro[2] = 0f
            touching = true; touchX = 0.25f; touchY = 0.9f
            buttons = DaydreamBle.CLICK or DaydreamBle.VOL_UP
        }
        val bytes = DaydreamBle.encode(s)
        assertEquals(20, bytes.size)
        val d = DaydreamBle.decode(bytes)
        assertEquals(300, d.time); assertEquals(17, d.seq)
        for (k in 0..2) {
            assertEquals(s.orientation[k], d.orientation[k], DaydreamBle.ORI_SCALE)
            assertEquals(s.accel[k], d.accel[k], DaydreamBle.ACC_SCALE)
            assertEquals(s.gyro[k], d.gyro[k], DaydreamBle.GYRO_SCALE)
        }
        assertEquals(0.25f, d.touchX, 1f / 255); assertEquals(0.9f, d.touchY, 1f / 255)
        assertEquals(DaydreamBle.CLICK or DaydreamBle.VOL_UP, d.buttons)
        // no finger: (0, 0), and the buttons do not leak into the touch bits
        s.touching = false; s.buttons = 0x1f
        val n = DaydreamBle.decode(DaydreamBle.encode(s))
        assertFalse(n.touching); assertEquals(0x1f, n.buttons)
    }

    @Test fun rotationVectorAndAxes() {
        // 90 degrees about z
        val h = (Math.sqrt(0.5)).toFloat()
        val v = DaydreamBle.rotationVector(0f, 0f, h, h)
        assertEquals((Math.PI / 2).toFloat(), v[2], 1e-4f)
        // the phone's top (y) is the controller's forward (-z), its screen normal (z) is up (y)
        val c = DaydreamBle.phoneToController(floatArrayOf(0f, 1f, 0f))
        assertEquals(-1f, c[2], 0f)
        assertEquals(1f, DaydreamBle.phoneToController(floatArrayOf(0f, 0f, 1f))[1], 0f)
    }
}
