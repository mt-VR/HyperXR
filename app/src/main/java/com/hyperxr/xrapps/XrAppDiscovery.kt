package com.hyperxr.xrapps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import com.hyperxr.launcher.AppCategory
import com.hyperxr.launcher.AppEntry
import com.hyperxr.launcher.AppKind

/** An installed XR application found through declared intent categories. */
data class InstalledXrApp(
    val entry: AppEntry,
    val packageName: String,
    val activity: String,
    val icon: Drawable?,
    val installedAtMs: Long,
)

/**
 * Finds Cardboard / Daydream / OpenXR apps using only the intent categories they declare
 * (allowed by the <queries> block; no QUERY_ALL_PACKAGES). HyperXR launches them as they are:
 * it does not convert Cardboard apps to OpenXR.
 */
class XrAppDiscovery(private val context: Context) {

    private data class Category(val category: String, val app: AppCategory, val runtime: String, val notes: String)

    private val categories = listOf(
        Category("com.google.intent.category.CARDBOARD", AppCategory.CARDBOARD, "Google Cardboard SDK",
            "Runs its own stereo renderer; HyperXR hands over the display and sensors."),
        Category("com.google.intent.category.DAYDREAM", AppCategory.CARDBOARD, "Google VR (Daydream)",
            "Daydream-only apps need Google VR Services; patch them in Files (Corrigir e instalar) to run them as Cardboard apps."),
        Category("org.khronos.openxr.intent.category.IMMERSIVE_HMD", AppCategory.OPENXR, "OpenXR runtime (via broker)",
            "Needs an installed OpenXR runtime (e.g. Monado) set as active in the runtime broker."),
        Category("com.oculus.intent.category.VR", AppCategory.OPENXR, "Quest app (OpenXR via HyperXR runtime)",
            "Built for Meta Quest: runs only if it uses the standard OpenXR loader and OpenGL ES (see the analysis in Files)."),
    )

    fun discover(): List<InstalledXrApp> {
        val pm = context.packageManager
        val seen = HashMap<String, InstalledXrApp>()
        for (cat in categories) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat.category)
            val list: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == context.packageName) continue
                if (seen.containsKey(ai.packageName)) continue
                val installed = try { pm.getPackageInfo(ai.packageName, 0).firstInstallTime } catch (e: Exception) { 0L }
                val recent = System.currentTimeMillis() - installed < 7L * 24 * 3600 * 1000
                val label = ri.loadLabel(pm).toString()
                val entry = AppEntry(
                    id = "android:${ai.packageName}",
                    title = label,
                    kind = AppKind.ANDROID_PACKAGE,
                    category = cat.app,
                    iconKey = null,
                    target = "${ai.packageName}/${ai.name}",
                    runtime = cat.runtime,
                    compatibility = if (recent) "Installed recently" else "Detected via ${cat.category.substringAfterLast('.')}",
                    limitations = cat.notes,
                )
                seen[ai.packageName] = InstalledXrApp(entry, ai.packageName, ai.name, ri.loadIcon(pm), installed)
            }
        }
        return seen.values.sortedByDescending { it.installedAtMs }
    }

    fun launch(app: InstalledXrApp): Result<Unit> = runCatching {
        // OpenXR / Quest games get the visibility grants that let them see the broker and runtime
        if (app.entry.category == AppCategory.OPENXR) {
            com.hyperxr.compat.CompatLayer.launchGame(context, app.packageName, app.activity)
            return@runCatching
        }
        // Cardboard / Daydream apps read the viewer from a shared file: give them the VR Box calibration
        if (app.entry.category == AppCategory.CARDBOARD) com.hyperxr.compat.CompatLayer.writeCardboardParams(context)
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(app.packageName, app.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
