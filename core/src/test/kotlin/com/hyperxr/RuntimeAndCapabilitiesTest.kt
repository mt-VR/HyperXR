package com.hyperxr

import com.hyperxr.capabilities.CameraInfo
import com.hyperxr.capabilities.Capability
import com.hyperxr.capabilities.DeviceCapabilities
import com.hyperxr.capabilities.XrMode
import com.hyperxr.capabilities.XrModeResolver
import com.hyperxr.diagnostics.LatencyStats
import com.hyperxr.environments.EnvironmentKind
import com.hyperxr.environments.LicensePolicy
import com.hyperxr.environments.PolyHavenCatalog
import com.hyperxr.runtimebroker.RuntimeDescriptor
import com.hyperxr.runtimebroker.RuntimeProbe
import com.hyperxr.runtimebroker.RuntimeSelector
import com.hyperxr.runtimebroker.RuntimeSource
import com.hyperxr.runtimebroker.SessionArbiter
import com.hyperxr.runtimebroker.SessionState
import com.hyperxr.settings.XrModePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAndCapabilitiesTest {
    private val cam = CameraInfo("0", true, "FULL", 60, false, true)

    @Test fun mrOnlyWhenReallySupported() {
        val base = DeviceCapabilities(rotationVector = Capability.yes(), cameras = listOf(cam), cameraPermission = true)
        assertEquals(XrMode.MIXED_REALITY_ARCORE, XrModeResolver.resolve(base.copy(arCore = Capability.yes()), XrModePreference.AUTO).mode)
        val cameraOnly = XrModeResolver.resolve(base.copy(arCore = Capability.no("not installed")), XrModePreference.AUTO)
        assertEquals(XrMode.MIXED_REALITY_CAMERA, cameraOnly.mode)
        assertTrue(cameraOnly.reasons.any { it.contains("mono") })
        assertEquals(XrMode.VR_SBS, XrModeResolver.resolve(base.copy(cameraPermission = false), XrModePreference.AUTO).mode)
        assertEquals(XrMode.VR_SBS, XrModeResolver.resolve(base.copy(cameras = emptyList()), XrModePreference.MIXED_REALITY).mode)
        assertEquals(XrMode.VR_SBS, XrModeResolver.resolve(base, XrModePreference.VR_SBS).mode)
        val noRuntime = XrModeResolver.resolve(base, XrModePreference.OPENXR_RUNTIME)
        assertTrue(noRuntime.reasons.first().contains("not available"))
    }

    @Test fun runtimeSelection() {
        val vendor = RuntimeDescriptor("com.vendor.xr", "Vendor", RuntimeSource.BROKER_ACTIVE)
        val monado = RuntimeDescriptor("org.freedesktop.monado.openxr_runtime.out_of_process", "Monado", RuntimeSource.SERVICE_DISCOVERY)
        assertEquals(monado, RuntimeSelector.select(listOf(vendor, monado), null, preferMonado = true))
        assertEquals(vendor, RuntimeSelector.select(listOf(vendor, monado), null, preferMonado = false))
        assertEquals(vendor, RuntimeSelector.select(listOf(vendor, monado), "com.vendor.xr", preferMonado = true))
        assertNull(RuntimeSelector.select(emptyList(), null, true))
        assertEquals(listOf("XR_KHR_opengl_es_enable"),
            RuntimeSelector.missingRequired(RuntimeProbe(true, extensions = listOf("XR_KHR_android_create_instance"))))
    }

    @Test fun sessionArbiterPreventsConflicts() {
        val a = SessionArbiter()
        assertTrue(a.acquire("launcher"))
        assertFalse(a.acquire("game"))
        a.transition(SessionState.READY); a.transition(SessionState.RUNNING)
        assertThrows(IllegalStateException::class.java) { a.transition(SessionState.CREATING) }
        a.release("game") // not the owner: ignored
        assertEquals(SessionState.RUNNING, a.state)
        a.release("launcher")
        assertEquals(SessionState.IDLE, a.state)
        assertTrue(a.acquire("game"))
        a.fail("xrCreateSession failed: XR_ERROR_FORM_FACTOR_UNAVAILABLE")
        assertEquals(SessionState.ERROR, a.state)
        assertTrue(a.acquire("launcher")) // recovery after error
    }

    @Test fun licensePolicy() {
        assertTrue(LicensePolicy.isAllowed("CC0-1.0"))
        assertFalse(LicensePolicy.isAllowed("CC-BY-NC-4.0"))
        assertFalse(LicensePolicy.isAllowed("unknown"))
        assertTrue(LicensePolicy.validate(PolyHavenCatalog.STUDIO).isEmpty())
        assertTrue(LicensePolicy.validate(PolyHavenCatalog.STUDIO.copy(license = "CC-BY-4.0", attribution = null)).isNotEmpty())
    }

    @Test fun polyHavenParsing() {
        val asset = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"name":"Old Shed","authors":{"Jane":"All"},"polycount":1000}""").let { it as kotlinx.serialization.json.JsonObject }
        val files = """{"gltf":{"1k":{"gltf":{"url":"https://dl.polyhaven.org/x/shed_1k.gltf","size":10,"md5":"a",
            "include":{"shed.bin":{"url":"https://dl.polyhaven.org/x/shed.bin","size":100,"md5":"b"}}}}}}"""
        val e = PolyHavenCatalog.modelEntry("shed", asset, files)!!
        assertEquals(EnvironmentKind.SCENE_3D, e.kind)
        assertEquals("CC0-1.0", e.license)
        assertEquals(110L, e.downloadBytes)
        assertEquals("shed_1k.gltf", e.mainFile)
        assertEquals("Jane", e.author)
        val pano = PolyHavenCatalog.panoramaEntry("sky", asset, """{"tonemapped":{"url":"https://x/sky.jpg","size":5}}""")!!
        assertEquals(EnvironmentKind.PANORAMA_360, pano.kind)
    }

    @Test fun latencyStatsPercentiles() {
        val s = LatencyStats("t", 100)
        for (i in 1..100) s.record(i * 1_000_000L)
        val snap = s.snapshot()
        assertEquals(50.5, snap.meanMs, 1e-9)
        assertEquals(50.0, snap.p50Ms, 1.0)
        assertEquals(95.0, snap.p95Ms, 1.0)
        assertEquals(100.0, snap.maxMs, 0.0)
        for (i in 1..50) s.record(1_000_000L) // ring buffer overwrites oldest
        assertEquals(100, s.snapshot().count)
        assertEquals(150, s.snapshot().totalSamples)
    }
}
