package com.hyperxr

import com.hyperxr.xrapps.ElfNeeded
import com.hyperxr.xrapps.GameLog
import com.hyperxr.xrapps.GodotProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameDiagnosticsTest {
    @Test fun elfNeededReadsTheDynamicSection() {
        val ls = File("/bin/ls")
        if (!ls.exists()) return
        val needed = ElfNeeded.read { ls.inputStream() }
        assertTrue(needed.toString(), needed.any { it.startsWith("libc.so") })
        assertTrue(ElfNeeded.read { "not an elf".byteInputStream() }.isEmpty())
    }

    @Test fun missingLibrariesAreThoseNeitherBundledNorSystem() {
        val m = ElfNeeded.missing(mapOf(
            "lib/arm64-v8a/libOVRPlugin.so" to listOf("libvrapi.so", "liblog.so", "libc++_shared.so"),
            "lib/arm64-v8a/libc++_shared.so" to listOf("libc.so"),
        ))
        assertEquals(listOf("libvrapi.so"), m)
    }

    @Test fun gameLogKeepsTheGameProcessAndFindsTheCause() {
        val log = listOf(
            "10-31 10:00:00.000  1000  1100 I ActivityManager: Start proc 4242:com.Holonautic.HandPhysicsLab/u0a300 for top-activity",
            "10-31 10:00:00.100  4242  4242 I Unity   : Loading player",
            "10-31 10:00:00.200  5555  5555 I Other   : unrelated",
            "10-31 10:00:00.300  4242  4260 E AndroidRuntime: FATAL EXCEPTION: main",
            "10-31 10:00:00.301  4242  4260 E AndroidRuntime: java.lang.UnsatisfiedLinkError: dlopen failed: library \"libvrapi.so\" not found",
            "10-31 10:00:00.400  1000  1100 I ActivityManager: Process com.Holonautic.HandPhysicsLab (pid 4242) has died",
        )
        val kept = GameLog.filter(log, "com.Holonautic.HandPhysicsLab")
        assertEquals(5, kept.size)
        val s = GameLog.summary(kept)
        assertTrue(s.any { it.contains("libvrapi.so") } && s.any { it.contains("has died") })
    }

    @Test fun godotRendererSwitchesToCompatibility() {
        val settings = listOf(
            "application/config/name" to GodotProject.encodeString("Jogo"),
            "xr/openxr/enabled" to byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0),
        )
        val bytes = GodotProject.serialize(settings)
        val parsed = GodotProject.parse(bytes)!!
        assertEquals("Jogo", GodotProject.string(parsed[0].second))
        assertEquals("mobile", GodotProject.renderer(parsed))
        val fixed = GodotProject.parse(GodotProject.serialize(GodotProject.withCompatibility(parsed)))!!
        assertEquals(GodotProject.COMPATIBILITY, GodotProject.renderer(fixed))
        assertEquals(4, fixed.size)
        assertTrue(fixed.any { it.first == "xr/openxr/enabled" })
        assertEquals(null, GodotProject.parse("nope".toByteArray()))
    }
}
