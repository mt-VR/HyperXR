package com.hyperxr.progress

import com.hyperxr.HyperXRApp
import com.hyperxr.diagnostics.Diagnostics
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The player's progress on this phone (progress.json): records activities, saves, tells the
 * headset what to celebrate, and mirrors level and XP to the Pessoas profile.
 */
object ProgressStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File get() = File(HyperXRApp.instance.filesDir, "progress.json")
    private val listeners = CopyOnWriteArrayList<(Outcome) -> Unit>()

    @Volatile var state: ProgressState = load(); private set

    private fun load(): ProgressState = runCatching { json.decodeFromString(ProgressState.serializer(), file.readText()) }.getOrDefault(ProgressState())
        .let { Progression.rollDay(it, LocalDate.now()) }

    fun addListener(l: (Outcome) -> Unit) { listeners += l }
    fun removeListener(l: (Outcome) -> Unit) { listeners -= l }

    val level: Int get() = Progression.level(state.xp)

    /** Today's missions (new ones after midnight). */
    fun today(): ProgressState {
        val s = Progression.rollDay(state, LocalDate.now())
        if (s !== state) { state = s; save() }
        return s
    }

    @Synchronized fun record(a: Activity) {
        val (s, out) = Progression.apply(state, a, LocalDate.now())
        state = s
        save()
        if (out.xpGained > 0 || out.missionsDone.isNotEmpty() || out.achievements.isNotEmpty()) listeners.forEach { runCatching { it(out) } }
        if (out.levelUp != null || out.xpGained >= 20) runCatching { com.hyperxr.social.Social.syncProgress(s.xp, Progression.level(s.xp)) }
    }

    private fun save() {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(ProgressState.serializer(), state))
            tmp.renameTo(file)
        } catch (t: Throwable) { Diagnostics.log("Progress save: $t") }
    }
}
