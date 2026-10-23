package com.hyperxr.settings

import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Byte storage abstraction so persistence can be unit tested without Android. */
interface SettingsStorage {
    fun read(): String?
    fun write(content: String)
}

/** Atomic file storage: writes a temp file and renames it so a crash never leaves half a JSON. */
class FileSettingsStorage(private val file: File) : SettingsStorage {
    override fun read(): String? = if (file.exists()) file.readText() else null
    override fun write(content: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not persist settings to $file" }
        }
    }
}

class SettingsRepository(private val storage: SettingsStorage) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val listeners = CopyOnWriteArrayList<(HyperSettings) -> Unit>()

    @Volatile
    var current: HyperSettings = load()
        private set

    /** Last load problem (corrupted file etc.), surfaced in diagnostics instead of being swallowed. */
    @Volatile
    var lastLoadError: String? = null
        private set

    private fun load(): HyperSettings {
        val raw = storage.read() ?: return HyperSettings()
        return try {
            HyperSettings.migrate(json.decodeFromString(HyperSettings.serializer(), raw))
        } catch (e: Exception) {
            lastLoadError = "Settings file unreadable, defaults restored: ${e.message}"
            HyperSettings()
        }
    }

    @Synchronized
    fun update(transform: (HyperSettings) -> HyperSettings): HyperSettings {
        val next = transform(current)
        if (next != current) {
            storage.write(json.encodeToString(HyperSettings.serializer(), next))
            current = next
            listeners.forEach { it(next) }
        }
        return next
    }

    fun setLauncherMode(mode: LauncherMode) = update { it.copy(launcher = it.launcher.copy(mode = mode)) }

    fun addListener(l: (HyperSettings) -> Unit) { listeners += l }
    fun removeListener(l: (HyperSettings) -> Unit) { listeners -= l }
}
