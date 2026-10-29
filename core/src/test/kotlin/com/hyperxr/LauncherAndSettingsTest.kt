package com.hyperxr

import com.hyperxr.launcher.BuiltinApps
import com.hyperxr.launcher.LauncherModel
import com.hyperxr.launcher.SpatialSlot
import com.hyperxr.settings.FileSettingsStorage
import com.hyperxr.settings.LauncherMode
import com.hyperxr.settings.SettingsRepository
import com.hyperxr.settings.SettingsStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LauncherAndSettingsTest {
    private val model = LauncherModel { BuiltinApps.all }

    @Test fun modeSurvivesRestart() {
        val dir = Files.createTempDirectory("hx").toFile()
        val file = File(dir, "settings.json")
        SettingsRepository(FileSettingsStorage(file)).setLauncherMode(LauncherMode.META)
        val reloaded = SettingsRepository(FileSettingsStorage(file))
        assertEquals(LauncherMode.META, reloaded.current.launcher.mode)
    }

    @Test fun corruptedSettingsFallBackToDefaultsAndReport() {
        val storage = object : SettingsStorage {
            override fun read() = "{ not json"
            override fun write(content: String) {}
        }
        val repo = SettingsRepository(storage)
        assertEquals(LauncherMode.HYPER, repo.current.launcher.mode)
        assertNotNull(repo.lastLoadError)
    }

    @Test fun unknownFieldsAreIgnored() {
        val storage = object : SettingsStorage {
            override fun read() = """{"launcher":{"mode":"NEW"},"futureField":42}"""
            override fun write(content: String) {}
        }
        assertEquals(LauncherMode.META, SettingsRepository(storage).current.launcher.mode)
    }

    @Test fun listenersSeeChanges() {
        var mem: String? = null
        val repo = SettingsRepository(object : SettingsStorage {
            override fun read() = mem
            override fun write(content: String) { mem = content }
        })
        var seen: LauncherMode? = null
        repo.addListener { seen = it.launcher.mode }
        repo.update { it.copy(launcher = model.toggleMode(it.launcher)) }
        assertEquals(LauncherMode.META, seen)
        assertTrue(mem!!.contains("\"NEW\""))
    }

    @Test fun dockPinUnpinReorder() {
        var s = com.hyperxr.settings.LauncherSettings(pinned = listOf("hx:browser", "hx:files"))
        s = model.pin(s, "hx:clock")
        s = model.pin(s, "hx:clock")
        assertEquals(listOf("hx:browser", "hx:files", "hx:clock"), s.pinned)
        s = model.move(s, "hx:clock", 0)
        assertEquals(listOf("hx:clock", "hx:browser", "hx:files"), s.pinned)
        s = model.move(s, "hx:clock", 99)
        assertEquals("hx:clock", s.pinned.last())
        s = model.unpin(s, "hx:browser")
        assertEquals(listOf("hx:files", "hx:clock"), s.pinned)
        assertEquals(2, model.dockApps(s).size)
    }

    @Test fun recentsAreLruAndBounded() {
        var s = com.hyperxr.settings.LauncherSettings()
        for (i in 0 until 12) s = model.recordLaunch(s, BuiltinApps.all[i].id)
        s = model.recordLaunch(s, BuiltinApps.all[5].id)
        assertEquals(LauncherModel.MAX_RECENTS, s.recents.size)
        assertEquals(BuiltinApps.all[5].id, s.recents.first())
        assertEquals(s.recents.size, s.recents.toSet().size)
    }

    @Test fun spatialLayoutDefaultsAndOverrides() {
        var s = com.hyperxr.settings.LauncherSettings()
        val apps = BuiltinApps.all.take(8)
        val slots = model.spatialPlacement(s, apps)
        assertEquals(8, slots.size)
        assertEquals(8, slots.values.map { it.yawDeg to it.pitchDeg }.toSet().size)
        s = model.place(s, apps[0].id, 30f, 10f, 2f)
        val moved = model.spatialPlacement(s, apps).getValue(apps[0].id)
        assertTrue(moved.custom)
        assertEquals(30f, moved.yawDeg, 1e-4f)
        // round trip position <-> spherical
        val back = SpatialSlot.fromPosition(moved.toPosition())
        assertEquals(moved.yawDeg, back.yawDeg, 1e-3f)
        assertEquals(moved.pitchDeg, back.pitchDeg, 1e-3f)
        assertEquals(moved.distance, back.distance, 1e-4f)
    }
}
