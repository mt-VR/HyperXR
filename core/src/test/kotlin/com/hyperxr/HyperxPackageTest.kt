package com.hyperxr

import com.hyperxr.permissions.HyperPermission
import com.hyperxr.permissions.PermissionPolicy
import com.hyperxr.sdk.HyperxManifest
import com.hyperxr.sdk.HyperxPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HyperxPackageTest {
    private fun sourceDir(manifest: String, files: Map<String, String> = mapOf("main.lua" to "print('hi')")): File {
        val dir = Files.createTempDirectory("hxsrc").toFile()
        File(dir, "manifest.json").writeText(manifest)
        files.forEach { (k, v) -> File(dir, k).apply { parentFile.mkdirs() }.writeText(v) }
        return dir
    }

    private val good = """{"id":"com.example.demo","name":"Demo","version":"1.0.0","mode":"scene","permissions":["ui","physics"]}"""

    @Test fun packAndInspectRoundTrip() {
        val out = File(Files.createTempDirectory("hx").toFile(), "demo.hyperx")
        HyperxPackage.pack(sourceDir(good, mapOf("main.lua" to "x=1", "lib/util.lua" to "y=2")), out)
        val r = HyperxPackage.inspect(out)
        assertTrue(r.errors.toString(), r.valid)
        assertEquals(2, r.manifest!!.integrity.files.size)
        assertEquals(setOf(HyperPermission.UI, HyperPermission.PHYSICS), r.manifest.declaredPermissions)
    }

    @Test fun tamperedFileFailsIntegrity() {
        val dir = Files.createTempDirectory("hx").toFile()
        val pkg = File(dir, "a.hyperx")
        val m = HyperxPackage.pack(sourceDir(good), pkg)
        val tampered = File(dir, "b.hyperx")
        ZipOutputStream(tampered.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(HyperxManifest.encode(m).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("main.lua")); z.write("os.execute('rm -rf /')".toByteArray()); z.closeEntry()
        }
        val r = HyperxPackage.inspect(tampered)
        assertFalse(r.valid)
        assertTrue(r.errors.any { it.contains("Hash mismatch") })
    }

    @Test fun unlistedExtraFileRejected() {
        val dir = Files.createTempDirectory("hx").toFile()
        val m = HyperxPackage.pack(sourceDir(good), File(dir, "a.hyperx"))
        val bad = File(dir, "c.hyperx")
        ZipOutputStream(bad.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(HyperxManifest.encode(m).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("main.lua")); z.write("print('hi')".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("sneaky.lua")); z.write("x".toByteArray()); z.closeEntry()
        }
        assertTrue(HyperxPackage.inspect(bad).errors.any { it.contains("not covered") })
    }

    @Test fun pathTraversalRejected() {
        val f = File(Files.createTempDirectory("hx").toFile(), "evil.hyperx")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(good.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("../../evil.so")); z.write(1); z.closeEntry()
        }
        val r = HyperxPackage.inspect(f)
        assertFalse(r.valid)
        assertTrue(r.errors.any { it.contains("Unsafe entry path") })
        listOf("/abs", "a/../b", "c:\\x", "a\\b", "a//b", "./a").forEach { assertFalse(it, HyperxPackage.isSafeEntryName(it)) }
        assertTrue(HyperxPackage.isSafeEntryName("assets/models/ship.glb"))
    }

    @Test fun manifestValidation() {
        fun errorsOf(json: String): List<String> {
            val m = HyperxManifest.parse(json)
            val errors = mutableListOf<String>(); val warnings = mutableListOf<String>()
            HyperxPackage.validateManifest(m, setOf("main.lua"), errors, warnings)
            return errors
        }
        assertTrue(errorsOf(good).isEmpty())
        assertTrue(errorsOf(good.replace("com.example.demo", "Bad Id")).any { it.contains("Invalid id") })
        assertTrue(errorsOf(good.replace("1.0.0", "1.0")).any { it.contains("semantic") })
        assertTrue(errorsOf(good.replace("scene", "fullscreen")).any { it.contains("mode") })
        assertTrue(errorsOf(good.replace("\"physics\"", "\"root\"")).any { it.contains("Unknown permission") })
        assertTrue(errorsOf(good.replace("\"physics\"", "\"network\"")).any { it.contains("allowedHosts") })
        assertTrue(errorsOf(good.replace("\"mode\"", "\"entry\":\"missing.lua\",\"mode\"")).any { it.contains("not found") })
        // Unknown manifest keys are an error, to catch typos in permission-related fields
        assertThrows(Exception::class.java) { HyperxManifest.parse(good.replace("}", ",\"permisions\":[]}")) }
    }

    @Test fun notAZip() {
        val f = File.createTempFile("notzip", ".hyperx").apply { writeText("hello") }
        assertFalse(HyperxPackage.inspect(f).valid)
    }

    @Test fun extractStaysInsideDestination() {
        val dir = Files.createTempDirectory("hx").toFile()
        val pkg = File(dir, "a.hyperx")
        HyperxPackage.pack(sourceDir(good, mapOf("main.lua" to "x", "assets/a.txt" to "y")), pkg)
        val dest = File(dir, "out")
        HyperxPackage.extract(pkg, HyperxPackage.inspect(pkg), dest)
        assertEquals("y", File(dest, "assets/a.txt").readText())
        assertTrue(File(dest, "manifest.json").isFile)
    }

    @Test fun permissionPolicy() {
        val declared = setOf(HyperPermission.UI, HyperPermission.NETWORK, HyperPermission.UNSAFE)
        var g = PermissionPolicy.initialGrants("com.x.y", declared)
        assertEquals(setOf(HyperPermission.UI), g.granted)
        g = PermissionPolicy.grant(g, declared, HyperPermission.NETWORK, userApproved = true)
        assertTrue(g.isGranted(HyperPermission.NETWORK))
        // unsafe is never granted without explicit confirmation
        assertThrows(IllegalArgumentException::class.java) {
            PermissionPolicy.grant(g, declared, HyperPermission.UNSAFE, userApproved = true, explicitConfirmation = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PermissionPolicy.grant(g, declared, HyperPermission.UNSAFE, userApproved = true, explicitConfirmation = true, integrityVerified = false)
        }
        g = PermissionPolicy.grant(g, declared, HyperPermission.UNSAFE, userApproved = true, explicitConfirmation = true)
        assertTrue(g.isGranted(HyperPermission.UNSAFE))
        // undeclared permission can never be granted
        assertThrows(IllegalArgumentException::class.java) {
            PermissionPolicy.grant(g, declared, HyperPermission.CAMERA, userApproved = true)
        }
        g = PermissionPolicy.grant(g, declared, HyperPermission.NETWORK, userApproved = false)
        assertFalse(g.isGranted(HyperPermission.NETWORK))
        assertTrue(HyperPermission.NETWORK in g.denied)
    }
}
