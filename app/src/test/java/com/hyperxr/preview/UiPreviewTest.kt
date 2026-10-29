package com.hyperxr.preview

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.hyperxr.HyperXRApp
import com.hyperxr.files.MediaHost
import com.hyperxr.launcher.BuiltinApps
import com.hyperxr.launcher.AppEntry
import com.hyperxr.rendering.IconFactory
import com.hyperxr.settings.LauncherMode
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.xr.LauncherActions
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Proxy

/**
 * Pictures of the VR panels, drawn with the real Android Canvas (Robolectric's native graphics),
 * to look at the design without a headset. Runs only when asked:
 * ./gradlew :app:testDebugUnitTest --tests '*UiPreviewTest' -Phyperxr.previews=/some/dir
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiPreviewTest {
    private val out: File? = System.getProperty("hyperxr.previews")?.let { File(it).apply { mkdirs() } }

    private fun app(): HyperXRApp {
        val base = RuntimeEnvironment.getApplication()
        val a = HyperXRApp()
        val attach = android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
        attach.isAccessible = true
        attach.invoke(a, base)
        HyperXRApp::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, a)
        return a
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> fake(type: Class<T>, answers: Map<String, Any?>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, _ ->
        if (answers.containsKey(m.name)) return@newProxyInstance answers[m.name]
        when (m.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Float.TYPE -> 0f
            java.lang.Long.TYPE -> 0L
            String::class.java -> ""
            List::class.java -> emptyList<Any>()
            else -> null
        }
    } as T

    private fun actions(ctx: android.content.Context, activity: Activity): LauncherActions {
        val icons = IconFactory(ctx)
        val all = BuiltinApps.all
        val pinned = all.filter { it.id in setOf("hx:browser", "hx:youtube", "game:rhythm") }
        return fake(LauncherActions::class.java, mapOf(
            "icons" to icons, "allApps" to all, "pinnedApps" to pinned, "mode" to LauncherMode.NEW,
            "getModeDescription" to "Modo New • rastreamento 6DoF", "getTrackingDescription" to "6DoF",
            "getHandStatus" to "Mãos: rastreando (NPU)", "getHyperConnectStatus" to "", "vrOn" to true, "vrSwitchable" to true,
            "voiceStatus" to "", "currentEnvironmentId" to "studio", "libraryOpen" to false,
        )).let { base ->
            // appIcon needs the factory: wrap once more so it answers with real icons
            Proxy.newProxyInstance(LauncherActions::class.java.classLoader, arrayOf(LauncherActions::class.java)) { _, m, args ->
                if (m.name == "appIcon") icons.icon((args!![0] as AppEntry).iconKey, args[1] as Int) else m.invoke(base, *(args ?: emptyArray()))
            } as LauncherActions
        }
    }

    /** The panel over a dusk sky, like it floats in VR. */
    private fun shot(name: String, p: XrPanel, hover: Pair<Float, Float>? = null) {
        val w = p.pxW; val h = p.pxH
        val pad = 60
        val bmp = Bitmap.createBitmap(w + pad * 2, h + pad * 2 + 30, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sky = Paint().apply { shader = LinearGradient(0f, 0f, 0f, bmp.height.toFloat(), Color.rgb(250, 170, 90), Color.rgb(60, 50, 90), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), sky)
        val panel = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        p.update(0f)
        p.renderTo(Canvas(panel))
        if (hover != null) { p.hoverAt(hover.first, hover.second); panel.eraseColor(0); p.renderTo(Canvas(panel)) }
        c.drawBitmap(panel, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        if (p.windowBar) {
            val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255) }
            val hw = w * 0.14f
            c.drawRoundRect(pad + w / 2f - hw / 2, pad + h + 20f, pad + w / 2f + hw / 2, pad + h + 30f, 5f, 5f, hp)
        }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun panels() {
        assumeTrue(out != null)
        val ctx = app()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val a = actions(ctx, activity)
        shot("quick", com.hyperxr.xr.QuickSettingsPanel(a, activity) {}, hover = 300f to 395f)
        val dock = com.hyperxr.xr.TaskbarPanel(a)
        dock.update(0f)
        shot("dock", dock, hover = 600f to 90f)
        val host = fake(MediaHost::class.java, mapOf("getActivity" to activity))
        shot("store", com.hyperxr.xr.StorePanel(a, host, { BuiltinApps.all }) {})
        val store2 = com.hyperxr.xr.StorePanel(a, host, { BuiltinApps.all }) {}
        store2.renderTo(Canvas(Bitmap.createBitmap(store2.pxW, store2.pxH, Bitmap.Config.ARGB_8888)))
        clickRegion(store2, "shelf:all")
        shot("store_all", store2)
        shot("library", com.hyperxr.xr.LibraryHomePanel(a, false))
        for ((name, make) in listOf<Pair<String, () -> XrPanel>>(
            "settings" to { com.hyperxr.xr.SettingsHubPanel(a) {} },
            "people" to { com.hyperxr.xr.PeoplePanel(a, host) },
            "missions" to { com.hyperxr.xr.MissionsPanel() },
        )) runCatching { shot(name, make()) }.onFailure { println("preview $name failed: $it") }
        // the same panels in the other languages
        for (lang in listOf("en", "ru", "es")) {
            com.hyperxr.i18n.Translations.apply(ctx, lang)
            shot("quick_$lang", com.hyperxr.xr.QuickSettingsPanel(a, activity) {})
            shot("store_$lang", com.hyperxr.xr.StorePanel(a, host, { BuiltinApps.all }) {})
            shot("dock_$lang", com.hyperxr.xr.TaskbarPanel(a).also { it.update(0f) }, hover = 520f to 90f)
            shot("settings_$lang", com.hyperxr.xr.SettingsHubPanel(a) {})
        }
        com.hyperxr.i18n.Translations.apply(ctx, "pt")
    }

    /** Runs the action of the region [id] (as a click would). */
    private fun clickRegion(p: XrPanel, id: String) {
        val f = XrPanel::class.java.getDeclaredField("regions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val regions = f.get(p) as List<XrPanel.Region>
        regions.first { it.id == id }.action?.invoke()
    }
}
