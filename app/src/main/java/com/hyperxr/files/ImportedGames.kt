package com.hyperxr.files

import android.content.Context
import android.content.Intent
import com.hyperxr.compat.CompatLayer
import com.hyperxr.xrapps.ApkReport
import com.hyperxr.xrapps.XrApi
import org.json.JSONObject

/** An APK brought in through the import tab: what it is and how to start it. */
data class ImportedGame(
    val packageName: String,
    val label: String,
    val activity: String?,
    val api: XrApi,
    val patched: Boolean,
    val importedAtMs: Long,
)

/**
 * The games and apps imported in HyperMagic, remembered so the import tab can list them with a
 * "Jogar" button. Only the ones still installed are shown.
 */
object ImportedGames {
    const val RUNTIME_MISSING = "os jogos OpenXR ainda não encontram o runtime HyperMagic: instale o broker no topo desta aba"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("imported_games", Context.MODE_PRIVATE)

    fun remember(ctx: Context, r: ApkReport, label: String, patched: Boolean) {
        val o = JSONObject()
            .put("label", label)
            .put("activity", r.launchActivity ?: "")
            .put("api", r.xrApi.name)
            .put("patched", patched)
            .put("at", System.currentTimeMillis())
        prefs(ctx).edit().putString(r.packageName, o.toString()).apply()
    }

    fun forget(ctx: Context, pkg: String) { prefs(ctx).edit().remove(pkg).apply() }

    /** Every remembered game, newest first, with whether it is installed now. */
    fun all(ctx: Context): List<Pair<ImportedGame, Boolean>> = prefs(ctx).all.mapNotNull { (pkg, v) ->
        val o = runCatching { JSONObject(v as String) }.getOrNull() ?: return@mapNotNull null
        val g = ImportedGame(
            packageName = pkg,
            label = o.optString("label", pkg),
            activity = o.optString("activity").ifEmpty { null },
            api = runCatching { XrApi.valueOf(o.optString("api")) }.getOrDefault(XrApi.NONE),
            patched = o.optBoolean("patched"),
            importedAtMs = o.optLong("at"),
        )
        g to CompatLayer.installed(ctx, pkg)
    }.sortedByDescending { it.first.importedAtMs }

    /** The label Android shows for an installed package, or null. */
    fun installedLabel(ctx: Context, pkg: String): String? = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /** The last game started from here and when (to tell a crash on start from a normal exit). */
    @Volatile var lastLaunch: Pair<String, Long>? = null

    /**
     * Starts a game: XR games through the compatibility layer (runtime visibility, hands), Cardboard
     * and Daydream apps with the viewer's calibration, other apps as Android starts them.
     */
    fun launch(ctx: Context, g: ImportedGame) {
        lastLaunch = g.packageName to System.currentTimeMillis()
        val activity = g.activity ?: ctx.packageManager.getLaunchIntentForPackage(g.packageName)?.component?.className
            ?: throw IllegalStateException("o app não tem tela inicial")
        when (g.api) {
            XrApi.CARDBOARD, XrApi.DAYDREAM -> { CompatLayer.writeCardboardParams(ctx); CompatLayer.launchGame(ctx, g.packageName, activity) }
            XrApi.OPENXR, XrApi.VRAPI -> {
                // without the broker the game's loader finds no runtime and the game says "OpenXR failed to start"
                if (!com.hyperxr.HyperXRApp.instance.runtimeBroker.hyperMagicActive()) throw IllegalStateException(RUNTIME_MISSING)
                CompatLayer.launchGame(ctx, g.packageName, activity)
            }
            XrApi.NONE -> ctx.startActivity(Intent(Intent.ACTION_MAIN).setClassName(g.packageName, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
