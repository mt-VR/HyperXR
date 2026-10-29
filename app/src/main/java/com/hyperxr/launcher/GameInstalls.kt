package com.hyperxr.launcher

import com.hyperxr.HyperXRApp

/**
 * Which HyperMagic games the player has downloaded from the Loja. Games are part of the app, but
 * they only show in the library, the dock and the voice commands after "Baixar" in the store,
 * like on a headset; removing one keeps its progress.
 */
object GameInstalls {
    private val prefs by lazy { HyperXRApp.instance.getSharedPreferences("game_installs", android.content.Context.MODE_PRIVATE) }

    fun isGame(e: AppEntry) = e.id.startsWith("game:")
    fun installed(id: String): Boolean = prefs.getBoolean(id, false)
    fun install(id: String) { prefs.edit().putBoolean(id, true).apply() }
    fun uninstall(id: String) { prefs.edit().remove(id).apply() }

    /** Shown in the library: every app, and the games that were downloaded. */
    fun visible(e: AppEntry) = !isGame(e) || installed(e.id)
}
