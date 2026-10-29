package com.hyperxr.launcher

import com.hyperxr.math.Vec3
import com.hyperxr.settings.LauncherMode
import com.hyperxr.settings.LauncherSettings
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure launcher state (dock pins, recents, New-mode spatial layout). All mutations return
 * new [LauncherSettings] so the result can be persisted through SettingsRepository.
 */
class LauncherModel(private val catalog: () -> List<AppEntry>) {

    fun pin(s: LauncherSettings, id: String): LauncherSettings =
        if (id in s.pinned || s.pinned.size >= MAX_DOCK) s else s.copy(pinned = s.pinned + id)

    fun unpin(s: LauncherSettings, id: String): LauncherSettings = s.copy(pinned = s.pinned - id)

    /** Moves a pinned app to [toIndex], clamped to the dock size. */
    fun move(s: LauncherSettings, id: String, toIndex: Int): LauncherSettings {
        val from = s.pinned.indexOf(id)
        if (from < 0) return s
        val list = s.pinned.toMutableList()
        list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), id)
        return s.copy(pinned = list)
    }

    fun recordLaunch(s: LauncherSettings, id: String): LauncherSettings =
        s.copy(recents = (listOf(id) + (s.recents - id)).take(MAX_RECENTS))

    fun toggleMode(s: LauncherSettings): LauncherSettings =
        s.copy(mode = if (s.mode == LauncherMode.HYPER) LauncherMode.META else LauncherMode.HYPER)

    fun dockApps(s: LauncherSettings): List<AppEntry> {
        val all = catalog().associateBy { it.id }
        return s.pinned.mapNotNull { all[it] }
    }

    fun recentApps(s: LauncherSettings): List<AppEntry> {
        val all = catalog().associateBy { it.id }
        return s.recents.mapNotNull { all[it] }
    }

    /**
     * New-mode placement as spherical coordinates around the user (yaw/pitch in degrees,
     * distance in meters). Unplaced apps are laid out on a gentle arc in rows; explicit
     * placements from the user override the default.
     */
    fun spatialPlacement(s: LauncherSettings, apps: List<AppEntry>): Map<String, SpatialSlot> {
        val out = LinkedHashMap<String, SpatialSlot>()
        var auto = 0
        for (app in apps) {
            val custom = s.spatialLayout[app.id]
            out[app.id] = if (custom != null && custom.size == 3) {
                SpatialSlot(custom[0], custom[1], custom[2], custom = true)
            } else {
                defaultSlot(auto++)
            }
        }
        return out
    }

    fun place(s: LauncherSettings, id: String, yawDeg: Float, pitchDeg: Float, distance: Float): LauncherSettings =
        s.copy(spatialLayout = s.spatialLayout + (id to listOf(
            yawDeg.coerceIn(-170f, 170f), pitchDeg.coerceIn(-60f, 70f), distance.coerceIn(0.6f, 4f),
        )))

    fun resetLayout(s: LauncherSettings): LauncherSettings = s.copy(spatialLayout = emptyMap())

    private fun defaultSlot(index: Int): SpatialSlot {
        val row = index / PER_ROW
        val col = index % PER_ROW
        val yaw = (col - (PER_ROW - 1) / 2f) * 15f + (if (row % 2 == 1) 7.5f else 0f)
        val pitch = 14f - row * 13f
        return SpatialSlot(yaw, pitch, 1.6f + 0.05f * row, custom = false)
    }

    companion object {
        const val MAX_DOCK = 9
        const val MAX_RECENTS = 8
        const val PER_ROW = 6
    }
}

data class SpatialSlot(val yawDeg: Float, val pitchDeg: Float, val distance: Float, val custom: Boolean) {
    /** Position relative to the user's head origin (-Z forward). */
    fun toPosition(out: Vec3 = Vec3()): Vec3 {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        return out.set(
            (-sin(yaw) * cos(pitch) * distance).toFloat(),
            (sin(pitch) * distance).toFloat(),
            (-cos(yaw) * cos(pitch) * distance).toFloat(),
        )
    }

    companion object {
        /** Inverse of [toPosition]. */
        fun fromPosition(p: Vec3): SpatialSlot {
            val d = p.length().coerceAtLeast(1e-4f)
            val pitch = Math.toDegrees(kotlin.math.asin((p.y / d).toDouble())).toFloat()
            val yaw = Math.toDegrees(kotlin.math.atan2(-p.x.toDouble(), -p.z.toDouble())).toFloat()
            return SpatialSlot(yaw, pitch, d, custom = true)
        }
    }
}
