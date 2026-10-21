package com.hyperxr.xr

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.hyperxr.HyperXRApp
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.environments.EnvironmentEntry
import com.hyperxr.environments.PolyHavenCatalog
import com.hyperxr.gameruntime.GameActivity
import com.hyperxr.handtracking.DepthSource
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.input.DirectTouch
import com.hyperxr.input.InteractionSurface
import com.hyperxr.input.PointerEvent
import com.hyperxr.input.PointerPhase
import com.hyperxr.input.PointerSource
import com.hyperxr.input.RayPointer
import com.hyperxr.joycon.JoyConSide
import com.hyperxr.launcher.AppCategory
import com.hyperxr.launcher.AppEntry
import com.hyperxr.launcher.AppKind
import com.hyperxr.launcher.BuiltinApps
import com.hyperxr.launcher.LauncherModel
import com.hyperxr.launcher.SpatialSlot
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.Background
import com.hyperxr.rendering.Gl
import com.hyperxr.rendering.IconFactory
import com.hyperxr.rendering.TextRenderer
import com.hyperxr.settings.ControllerAction
import com.hyperxr.settings.LauncherMode
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.ui2d.MainActivity
import com.hyperxr.webapps.SpatialWebPanel
import com.hyperxr.webapps.WebAppConfig
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin

/** A launchable icon placed in space. Long-press starts moving it (New mode). */
class AppTile(val app: AppEntry, var size: Float = 0.17f) : InteractionSurface {
    override val surfaceId = "tile:${app.id}"
    override val pose = Pose()
    override val widthMeters get() = size
    override val heightMeters get() = size
    var texture = 0
    var labelTexture = 0
    var labelAspect = 4f
    var hover = 0f
    var hovered = false
    var pressedAt = 0L
    var pressed = false
    var moving = false
    var clicked = false
    var longPressed = false
    var phase = Math.random().toFloat() * 6.28f
    val target = Pose()

    override fun onPointerEvent(event: PointerEvent) {
        when (event.phase) {
            PointerPhase.HOVER -> hovered = true
            PointerPhase.EXIT -> { hovered = false; pressed = false }
            PointerPhase.DOWN -> { pressed = true; pressedAt = event.timestampNs; longPressed = false }
            PointerPhase.MOVE -> if (pressed && !longPressed && event.timestampNs - pressedAt > 550_000_000L) longPressed = true
            PointerPhase.UP -> { if (pressed && !longPressed && !moving) clicked = true; pressed = false }
            PointerPhase.CANCEL -> pressed = false
        }
    }
}

/**
 * The XR launcher. Dock mode: an arc of pinned apps under a home panel. New mode: apps float
 * around the user in 3D and can be grabbed and re-arranged (placements persist). Both modes share
 * the window manager (system panels, spatial web apps) and the pointer stack.
 */
class SpatialLauncher(private val activity: android.app.Activity, private val root: android.widget.FrameLayout) : XrContent, LauncherActions {
    private val app = HyperXRApp.instance
    private val model = LauncherModel { allApps() }
    private lateinit var ctx: XrContext
    private val tiles = LinkedHashMap<String, AppTile>()
    private val windows = ArrayList<XrPanel>()
    private val webPanels = ArrayList<SpatialWebPanel>()
    private val webControls = HashMap<SpatialWebPanel, XrPanel>()
    private lateinit var home: HomePanel
    private lateinit var dockControls: DockControls
    private val gaze = RayPointer(0, PointerSource.GAZE)
    private val ctrlPointers = arrayOf(RayPointer(1, PointerSource.JOYCON), RayPointer(2, PointerSource.JOYCON))
    private val touches = arrayOf(DirectTouch(3), DirectTouch(4))
    private val ray = Ray()
    private var anchorYaw = 0f
    private var anchored = false
    private var reticleTex = 0
    private var installedApps: List<AppEntry> = emptyList()
    private var movingTile: AppTile? = null
    private var movingDistance = 1.6f
    private var currentEnvId = ""

    override val trackingDescription: String get() = if (::ctx.isInitialized) ctx.trackingDescription else ""
    override val modeDescription: String get() = if (!::ctx.isInitialized) "" else when (ctx.decision.mode) {
        com.hyperxr.capabilities.XrMode.MIXED_REALITY_ARCORE, com.hyperxr.capabilities.XrMode.MIXED_REALITY_CAMERA -> "Realidade mista"
        com.hyperxr.capabilities.XrMode.OPENXR_RUNTIME -> "OpenXR"
        else -> "VR SBS"
    }

    // ---------------------------------------------------------------- catalog
    override fun allApps(): List<AppEntry> = BuiltinApps.all + installedApps
    override fun icons(): IconFactory = ctx.icons

    private fun refreshInstalled() {
        val pkgs = app.packages.list().filter { p -> BuiltinApps.all.none { it.target == p.manifest.id } }.map { p ->
            AppEntry("pkg:${p.manifest.id}", p.manifest.name, AppKind.HYPERX_PACKAGE, AppCategory.HYPERXR_GAME, null, p.manifest.id,
                runtime = "HyperXR SDK ${p.manifest.sdk}", compatibility = p.manifest.mode)
        }
        val xr = try { app.xrApps.discover().map { it.entry } } catch (t: Throwable) { emptyList() }
        installedApps = pkgs + xr
    }

    // ---------------------------------------------------------------- lifecycle
    override fun onGlInit(ctx: XrContext) {
        this.ctx = ctx
        app.packages.installBundled()
        refreshInstalled()
        home = HomePanel(this)
        dockControls = DockControls(this) { openSystem(it) }
        reticleTex = Gl.uploadBitmap(reticleBitmap())
        val env = app.environments.installed().firstOrNull { it.id == app.settings.current.launcher.environmentId } ?: PolyHavenCatalog.STUDIO
        selectEnvironment(env)
        File(app.filesDir, "crash").listFiles()?.forEach { Diagnostics.log("Package crash report: ${it.nameWithoutExtension}: ${it.readText().take(160)}") }
    }

    override fun onResume() { if (::ctx.isInitialized) activity.runOnUiThread { refreshInstalled() } }

    override fun onGlRelease() {
        tiles.values.forEach { Gl.deleteTexture(it.texture); Gl.deleteTexture(it.labelTexture) }
        tiles.clear()
        (windows + home + dockControls).forEach { it.release() }
        webPanels.forEach { it.releaseGl(); it.close() }
        Gl.deleteTexture(reticleTex)
    }

    override fun onAction(ctx: XrContext, action: ControllerAction, pressed: Boolean, side: JoyConSide?) {
        if (!pressed) return
        when (action) {
            ControllerAction.BACK -> windows.lastOrNull { it.visible && it.closable }?.let { it.visible = false }
                ?: webPanels.lastOrNull()?.let { closeWeb(it) }
            ControllerAction.MENU -> openSystem("settings")
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- per frame
    override fun onFrame(ctx: XrContext, dt: Float) {
        processPendingCloses()
        val s = app.settings.current
        val head = ctx.input.head
        if (!anchored && ctx.frameIndex > 5) { anchorTo(head); anchored = true }
        ctx.scene.background = if (ctx.passthroughAvailable) Background.PASSTHROUGH else Background.ENVIRONMENT

        layoutTiles(s.launcher.mode, dt)
        val surfaces = ArrayList<InteractionSurface>()
        val visibleTiles = tiles.values.filter { it.app.id in visibleIds(s.launcher.mode) }
        surfaces += visibleTiles
        if (s.launcher.mode == LauncherMode.DOCK) { surfaces += home; surfaces += dockControls } else surfaces += dockControls
        surfaces += windows.filter { it.visible }
        surfaces += webPanels.filter { it.visible }
        surfaces += webControls.values.filter { it.visible }

        updatePointers(ctx, surfaces, s.handTracking.directTouch)
        handleTileEvents(visibleTiles)

        // submit
        for (t in visibleTiles) submitTile(t)
        if (s.launcher.mode == LauncherMode.DOCK) { home.update(dt); home.submit(ctx.scene) }
        dockControls.submit(ctx.scene)
        windows.removeAll { w -> if (!w.visible) { w.release(); true } else false }
        for (w in windows) { w.update(dt); w.submit(ctx.scene) }
        for (wp in webPanels) wp.submit(ctx.scene)
        for ((wp, c) in webControls) { placeBelow(c, wp); c.submit(ctx.scene) }
        submitPointerVisuals()
    }

    private fun visibleIds(mode: LauncherMode): Set<String> =
        if (mode == LauncherMode.DOCK) app.settings.current.launcher.pinned.toSet() else allApps().filter { it.kind != AppKind.ANDROID_PACKAGE }.map { it.id }.toSet()

    private fun anchorTo(head: Pose) {
        val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
        anchorYaw = kotlin.math.atan2(-f.x, -f.z)
        home.pose.position.set(head.position.x - sin(anchorYaw) * 1.5f, head.position.y + 0.22f, head.position.z - cos(anchorYaw) * 1.5f)
        Quat.fromAxisAngle(0f, 1f, 0f, anchorYaw, home.pose.orientation)
        dockControls.pose.position.set(head.position.x - sin(anchorYaw) * 1.25f, head.position.y - 0.62f, head.position.z - cos(anchorYaw) * 1.25f)
        Quat.fromAxisAngle(0f, 1f, 0f, anchorYaw, dockControls.pose.orientation)
        // tilt the controls up towards the eyes
        dockControls.pose.orientation.mul(Quat.fromAxisAngle(1f, 0f, 0f, 0.35f), dockControls.pose.orientation)
    }

    private fun tileFor(a: AppEntry): AppTile = tiles.getOrPut(a.id) {
        AppTile(a).also { t ->
            t.texture = Gl.uploadBitmap(ctx.icons.icon(a.iconKey, 256), mipmap = true)
            val label = TextRenderer.render(a.title, 44f, Color.WHITE, isBold = true, maxWidth = 420)
            t.labelAspect = label.width.toFloat() / label.height
            t.labelTexture = Gl.uploadBitmap(label)
            label.recycle()
        }
    }

    private fun layoutTiles(mode: LauncherMode, dt: Float) {
        val head = ctx.input.head
        val s = app.settings.current.launcher
        if (mode == LauncherMode.DOCK) {
            val apps = model.dockApps(s)
            val n = apps.size
            apps.forEachIndexed { i, a ->
                val t = tileFor(a)
                t.size = 0.15f
                val yaw = anchorYaw + Math.toRadians(((i - (n - 1) / 2f) * 9.5f).toDouble()).toFloat()
                val r = 1.3f
                t.target.position.set(head.position.x - sin(yaw) * r, head.position.y - 0.34f, head.position.z - cos(yaw) * r)
                Quat.fromAxisAngle(0f, 1f, 0f, yaw, t.target.orientation)
                t.target.orientation.mul(Quat.fromAxisAngle(1f, 0f, 0f, 0.25f), t.target.orientation)
            }
        } else {
            val apps = allApps().filter { it.kind != AppKind.ANDROID_PACKAGE }
            val slots = model.spatialPlacement(s, apps)
            val origin = Vec3(head.position.x, head.position.y, head.position.z)
            for (a in apps) {
                val t = tileFor(a)
                if (t === movingTile) continue
                t.size = 0.2f
                val slot = slots.getValue(a.id)
                val rel = SpatialSlot(slot.yawDeg + Math.toDegrees(anchorYaw.toDouble()).toFloat(), slot.pitchDeg, slot.distance, slot.custom).toPosition()
                t.phase += dt
                val bob = if (app.settings.current.accessibility.reduceMotion) 0f else sin(t.phase * 0.9f) * 0.012f
                t.target.position.set(origin.x + rel.x, origin.y + rel.y + bob, origin.z + rel.z)
                faceTowards(t.target, origin)
            }
        }
        for (t in tiles.values) {
            if (t === movingTile) continue
            val k = (dt * 10f).coerceIn(0f, 1f)
            t.pose.position.lerp(t.target.position, k)
            t.pose.orientation.slerp(t.target.orientation, k, t.pose.orientation)
            t.hover += ((if (t.hovered) 1f else 0f) - t.hover) * (dt * 12f).coerceIn(0f, 1f)
        }
    }

    private fun faceTowards(p: Pose, eye: Vec3) {
        val dx = p.position.x - eye.x; val dy = p.position.y - eye.y; val dz = p.position.z - eye.z
        val yaw = kotlin.math.atan2(-dx, -dz)
        val pitch = kotlin.math.atan2(dy, kotlin.math.sqrt(dx * dx + dz * dz))
        Quat.fromAxisAngle(0f, 1f, 0f, yaw, p.orientation)
        p.orientation.mul(Quat.fromAxisAngle(1f, 0f, 0f, -pitch), p.orientation)
    }

    private fun submitTile(t: AppTile) {
        val sc = ctx.scene
        val grow = 1f + 0.12f * t.hover + if (t.pressed) -0.05f else 0f
        val p = sc.addPanel()
        p.texture = t.texture
        p.pose.set(t.pose)
        // pop the tile slightly towards the viewer when hovered
        val fwd = t.pose.orientation.rotate(Vec3(0f, 0f, 1f))
        p.pose.position.addScaled(fwd, 0.02f * t.hover)
        p.width = t.size * grow; p.height = t.size * grow
        p.radius = t.size * 0.22f * grow
        p.glow = 0.35f * t.hover
        if (t.app.kind == AppKind.UNAVAILABLE) p.alpha = 0.5f
        // label under the icon
        val l = sc.addPanel()
        l.texture = t.labelTexture
        l.pose.set(t.pose)
        val down = t.pose.orientation.rotate(Vec3(0f, -1f, 0f))
        l.pose.position.addScaled(down, t.size * 0.5f + 0.028f)
        l.height = 0.032f; l.width = l.height * t.labelAspect
        l.radius = 0f
        l.alpha = 0.6f + 0.4f * t.hover
    }

    // ---------------------------------------------------------------- pointers
    private val hitPoints = ArrayList<Vec3>()

    private fun updatePointers(ctx: XrContext, surfaces: List<InteractionSurface>, directTouch: Boolean) {
        val now = System.nanoTime()
        hitPoints.clear()
        val input = ctx.input
        var anyController = false
        for (i in 0..1) {
            val c = input.controllers[i]
            if (c.connected) {
                anyController = true
                c.ray(ray)
                val primary = c.buttons and (1 or 4 or 128) != 0 // R, ZR, A
                ctrlPointers[i].update(ray, primary, surfaces, now)
                trackHit(ctrlPointers[i], ray)
                dragWindows(ctrlPointers[i], ray, primary)
            } else ctrlPointers[i].update(null, false, surfaces, now)
        }
        // fingertips: direct touch (disabled while Joy-Cons drive the pointer)
        for (i in 0..1) {
            val h = input.hands[i]
            val tip = if (directTouch && !anyController && h.isUsable) h.joints[HandJoint.INDEX_TIP] else null
            touches[i].update(tip, h.depthSource == DepthSource.HAND_SIZE_PRIOR, surfaces, now)
        }
        // gaze + screen tap is always available
        input.gazeRay(ray)
        gaze.update(ray, input.tapDown, surfaces, now)
        trackHit(gaze, ray)
        dragWindows(gaze, ray, input.tapDown)
    }

    private fun trackHit(p: RayPointer, r: Ray) {
        if (!p.hitDistance.isNaN()) hitPoints += r.pointAt(p.hitDistance - 0.004f)
    }

    private fun dragWindows(p: RayPointer, r: Ray, down: Boolean) {
        val target = p.captured as? XrPanel ?: return
        if (down && target.isDragging) target.drag(r.origin, r.direction, ctx.input.head)
        if (!down) target.endDrag()
        // tiles being moved in New mode follow the ray at their distance
        val mt = movingTile
        if (mt != null) {
            if (down) {
                mt.pose.position.set(r.pointAt(movingDistance))
                faceTowards(mt.pose, ctx.input.head.position)
            }
        }
    }

    private fun handleTileEvents(visible: List<AppTile>) {
        val mode = app.settings.current.launcher.mode
        for (t in visible) {
            if (t.longPressed && mode == LauncherMode.NEW && movingTile == null) {
                movingTile = t; t.moving = true
                movingDistance = ctx.input.head.position.distanceTo(t.pose.position)
            }
            if (t.longPressed && mode == LauncherMode.DOCK) {
                t.longPressed = false
                openWindow(InfoPanel(t.app.title, "Segure no modo New para mover. Na Biblioteca use Fixar/Soltar para editar a dock.").apply { placeInFront(ctx.input.head) })
            }
            if (t.clicked) { t.clicked = false; launch(t.app) }
        }
        val mt = movingTile
        if (mt != null && !mt.pressed) {
            // release: persist the new spherical placement relative to the launcher anchor
            val rel = Vec3.sub(mt.pose.position, ctx.input.head.position)
            val slot = SpatialSlot.fromPosition(rel)
            val yaw = slot.yawDeg - Math.toDegrees(anchorYaw.toDouble()).toFloat()
            app.settings.update { it.copy(launcher = model.place(it.launcher, mt.app.id, yaw, slot.pitchDeg, slot.distance)) }
            mt.moving = false; mt.longPressed = false
            movingTile = null
        }
    }

    private fun submitPointerVisuals() {
        for (hp in hitPoints) {
            val p = ctx.scene.addPanel()
            p.texture = reticleTex
            p.pose.position.set(hp)
            faceTowards(p.pose, ctx.input.head.position)
            p.width = 0.018f; p.height = 0.018f; p.radius = 0f
            p.overlay = true
        }
        // controller beams
        for (i in 0..1) {
            val c = ctx.input.controllers[i]
            if (!c.connected) continue
            val len = ctrlPointers[i].hitDistance.takeIf { !it.isNaN() } ?: 2.5f
            val d = ctx.scene.addDraw()
            d.mesh = ctx.renderer.cylinder
            d.unlit = true; d.transparent = true
            d.color[0] = 0.7f; d.color[1] = 0.65f; d.color[2] = 1f; d.color[3] = 0.45f
            c.ray(ray)
            val mid = ray.pointAt(len / 2)
            val q = Quat.fromTo(Vec3(0f, 1f, 0f), ray.direction)
            com.hyperxr.math.Mat4.compose(mid, q, 0.004f, len, 0.004f, d.matrix)
        }
    }

    private fun reticleBitmap(): Bitmap {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(200, 0, 0, 0); c.drawCircle(32f, 32f, 30f, p)
        p.color = Color.WHITE; c.drawCircle(32f, 32f, 20f, p)
        return b
    }

    // ---------------------------------------------------------------- windows
    private fun openWindow(w: XrPanel) {
        windows.removeAll { it.javaClass == w.javaClass && it !is InfoPanel && it !is PermissionPromptPanel }
        windows += w
    }

    private fun openSystem(target: String) {
        val head = ctx.input.head
        val w: XrPanel = when (target) {
            "settings" -> QuickSettingsPanel(this) { openSystem(it) }
            "library" -> LibraryPanel(this)
            "environments" -> EnvironmentsPanel(this)
            "files" -> FilesPanel()
            "gallery" -> GalleryPanel()
            "messages" -> MessagesPanel()
            "help" -> HelpPanel()
            "tv" -> TvPanel(this)
            "xrapps" -> XrAppsPanel(this)
            "capture" -> { capture(); return }
            else -> InfoPanel("HyperXR", "Painel '$target' indisponível")
        }
        w.placeInFront(head, 1.35f, -0.05f, yawOffsetDeg = (windows.size % 3 - 1) * 8f)
        openWindow(w)
    }

    private fun placeBelow(c: XrPanel, wp: SpatialWebPanel) {
        c.pose.orientation.set(wp.pose.orientation)
        val down = wp.pose.orientation.rotate(Vec3(0f, -1f, 0f))
        c.pose.position.set(wp.pose.position).addScaled(down, wp.heightMeters / 2 + c.heightMeters / 2 + 0.02f)
    }

    private fun openWeb(a: AppEntry) {
        val desktop = app.settings.current.network.webAppsDesktopMode || a.id == "web:whatsapp"
        val wp = SpatialWebPanel(activity, root, a.target, a.title, desktop = desktop)
        wp.pose.set(Pose().also { p ->
            val head = ctx.input.head
            val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
            val yaw = kotlin.math.atan2(-f.x, -f.z)
            p.position.set(head.position.x - sin(yaw) * 1.6f, head.position.y + 0.05f, head.position.z - cos(yaw) * 1.6f)
            Quat.fromAxisAngle(0f, 1f, 0f, yaw, p.orientation)
        })
        webPanels += wp
        webControls[wp] = WebControls(wp)
    }

    private fun closeWeb(wp: SpatialWebPanel) {
        webPanels.remove(wp)
        webControls.remove(wp)?.release()
        wp.releaseGl(); wp.close()
    }

    private inner class WebControls(private val wp: SpatialWebPanel) : XrPanel(1.2f, 0.12f, title = "") {
        init { closable = false }
        override fun draw(c: com.hyperxr.spatialui.PanelCanvas) {
            val bw = (c.w - 2 * c.pad - 3 * 14f) / 4
            c.button("back", "Voltar", c.pad, 12f, bw, 80f) { wp.back() }
            c.button("reload", "Recarregar", c.pad + bw + 14f, 12f, bw, 80f) { wp.reload() }
            c.button("ext", "Navegador externo", c.pad + 2 * (bw + 14f), 12f, bw, 80f) { WebAppConfig.openExternal(activity, wp.url) }
            c.button("close", "Fechar", c.pad + 3 * (bw + 14f), 12f, bw, 80f, accent = true) { pendingClose += wp }
        }
    }
    private val pendingClose = ArrayList<SpatialWebPanel>()

    // ---------------------------------------------------------------- LauncherActions
    override fun launch(app: AppEntry) {
        this.app.settings.update { it.copy(launcher = model.recordLaunch(it.launcher, app.id)) }
        when (app.kind) {
            AppKind.HYPERX_PACKAGE -> {
                val pending = GameActivity.pendingPrompts(app.target)
                if (pending.isNotEmpty()) {
                    openWindow(PermissionPromptPanel(app, app.target) { ok -> if (ok) ctx.ui { GameActivity.launch(activity, app.target) } }.apply { placeInFront(ctx.input.head) })
                } else ctx.ui { GameActivity.launch(activity, app.target) }
            }
            AppKind.WEB -> openWeb(app)
            AppKind.ANDROID_PACKAGE -> ctx.ui {
                val x = this.app.xrApps.discover().firstOrNull { it.entry.id == app.id }
                if (x != null) this.app.xrApps.launch(x)
            }
            AppKind.SYSTEM_PANEL -> openSystem(app.target)
            AppKind.UNAVAILABLE -> openWindow(InfoPanel(app.title, app.limitations).apply { placeInFront(ctx.input.head) })
        }
    }

    override fun togglePin(app: AppEntry) {
        this.app.settings.update { s -> s.copy(launcher = if (isPinned(app)) model.unpin(s.launcher, app.id) else model.pin(s.launcher, app.id)) }
    }
    override fun isPinned(app: AppEntry) = app.id in this.app.settings.current.launcher.pinned
    override fun setMode(mode: LauncherMode) { app.settings.setLauncherMode(mode); dockControls.dirty = true }
    override fun mode(): LauncherMode = app.settings.current.launcher.mode
    override fun recenter() { ctx.requestRecenter(); anchored = false }
    override fun exitToSettings() = ctx.ui {
        activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        activity.finish()
    }
    override fun resetLayout() { app.settings.update { it.copy(launcher = model.resetLayout(it.launcher)) } }
    override fun currentEnvironmentId(): String = currentEnvId

    override fun selectEnvironment(entry: EnvironmentEntry) {
        val old = ctx.renderer.environment
        ctx.renderer.environment = app.environments.loadVisual(entry)
        when (old) {
            is com.hyperxr.rendering.SceneRenderer.EnvironmentVisual.Panorama -> Gl.deleteTexture(old.texture)
            is com.hyperxr.rendering.SceneRenderer.EnvironmentVisual.Model -> old.model.release()
            else -> Unit
        }
        currentEnvId = entry.id
        app.settings.update { it.copy(launcher = it.launcher.copy(environmentId = entry.id)) }
    }

    override fun capture() {
        ctx.captureRequest = { bmp ->
            val dir = File(app.filesDir, "captures").apply { mkdirs() }
            val f = File(dir, "hyperxr_${System.currentTimeMillis()}.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            Diagnostics.log("Capture saved: ${f.name}")
        }
    }

    /** Close requests from web controls are applied at the start of the next frame (GL thread). */
    private fun processPendingCloses() {
        if (pendingClose.isEmpty()) return
        pendingClose.toList().forEach { closeWeb(it) }
        pendingClose.clear()
    }
}
