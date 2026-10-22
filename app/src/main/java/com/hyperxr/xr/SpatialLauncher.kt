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
import com.hyperxr.windows.AndroidAppWindow
import com.hyperxr.windows.SpatialWindow
import com.hyperxr.windows.WebWindow
import com.hyperxr.webapps.WebAppConfig
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * The XR launcher, laid out like the Quest home. New mode: the app library floats in front of the
 * user with the taskbar under it. Dock mode: windows side by side over the taskbar; the library
 * opens from the taskbar. Both modes share the window manager (system panels, spatial web apps,
 * Android apps) and the pointer stack.
 */
class SpatialLauncher(override val activity: android.app.Activity, private val root: android.widget.FrameLayout) : XrContent, LauncherActions, BrowserUiHost, com.hyperxr.files.MediaHost {
    private val app = HyperXRApp.instance
    private val model = LauncherModel { allApps() }
    private lateinit var ctx: XrContext
    private val windows = ArrayList<XrPanel>()
    private val appWindows = ArrayList<SpatialWindow>()
    private val windowControls = LinkedHashMap<SpatialWindow, XrPanel>()
    /** Work posted from the UI thread (Gecko callbacks) to run on the GL thread at the next frame. */
    private val glTasks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private val browserStore by lazy { com.hyperxr.browser.BrowserStore(File(app.filesDir, "browser.json")) }
    /** Windows hidden by the dock's Home button (they keep running). */
    private var windowsMinimized = false
    /** Hand Physics Lab bench, shown in the same space as the launcher while open. */
    private var lab: com.hyperxr.lab.HandPhysicsLab? = null
    private val cursorRay = Ray()
    private var cursorRayValid = false
    private var cursorLabDown = false
    private var restored = false
    private var lastSessionJson = ""
    private var keyboard: KeyboardPanel? = null
    private lateinit var library: LibraryHomePanel
    private lateinit var taskbar: TaskbarPanel
    /** Library opened from the taskbar (Dock mode, or over windows in New mode). */
    private var libraryShown = false
    /** "camera", "dim" or an environment id. */
    private var background = ""
    private val gaze = RayPointer(0, PointerSource.GAZE)
    private val ctrlPointers = arrayOf(RayPointer(1, PointerSource.JOYCON), RayPointer(2, PointerSource.JOYCON))
    private val touches = arrayOf(DirectTouch(3), DirectTouch(4))
    private val ray = Ray()
    private var anchorYaw = 0f
    private var anchored = false
    private var reticleTex = 0
    private var ringTex = 0
    private var installedApps: List<AppEntry> = emptyList()
    private var currentEnvId = ""

    override val trackingDescription: String get() = if (::ctx.isInitialized) ctx.trackingDescription else ""
    override val handStatus: String get() = if (::ctx.isInitialized) ctx.handStatus() else ""
    override val modeDescription: String get() = if (!::ctx.isInitialized) "" else when (ctx.decision.mode) {
        com.hyperxr.capabilities.XrMode.MIXED_REALITY_ARCORE, com.hyperxr.capabilities.XrMode.MIXED_REALITY_CAMERA -> "Realidade mista"
        com.hyperxr.capabilities.XrMode.OPENXR_RUNTIME -> "OpenXR"
        else -> "VR SBS"
    }

    // ---------------------------------------------------------------- catalog
    private var appsCache: List<AppEntry> = BuiltinApps.all
    private val surfaces = ArrayList<InteractionSurface>()
    override fun allApps(): List<AppEntry> = appsCache
    override fun icons(): IconFactory = ctx.icons

    private fun refreshInstalled() {
        val pkgs = app.packages.list().filter { p -> BuiltinApps.all.none { it.target == p.manifest.id } }.map { p ->
            AppEntry("pkg:${p.manifest.id}", p.manifest.name, AppKind.HYPERX_PACKAGE, AppCategory.HYPERXR_GAME, null, p.manifest.id,
                runtime = "HyperXR SDK ${p.manifest.sdk}", compatibility = p.manifest.mode)
        }
        val xr = try { app.xrApps.discover().map { it.entry } } catch (t: Throwable) { emptyList() }
        installedApps = pkgs + xr
        appsCache = BuiltinApps.all + installedApps
    }

    // ---------------------------------------------------------------- lifecycle
    override fun onGlInit(ctx: XrContext) {
        this.ctx = ctx
        app.packages.installBundled()
        refreshInstalled()
        library = LibraryHomePanel(this, asWindow = false)
        taskbar = TaskbarPanel(this)
        reticleTex = Gl.uploadBitmap(reticleBitmap())
        ringTex = Gl.uploadBitmap(ringBitmap())
        val env = app.environments.installed().firstOrNull { it.id == app.settings.current.launcher.environmentId } ?: PolyHavenCatalog.STUDIO
        loadEnvironment(env)
        File(app.filesDir, "crash").listFiles()?.forEach { Diagnostics.log("Package crash report: ${it.nameWithoutExtension}: ${it.readText().take(160)}") }
    }

    override fun onResume() { if (::ctx.isInitialized) activity.runOnUiThread { refreshInstalled() } }

    override fun onGlRelease() {
        (windows + library + taskbar).forEach { it.release() }
        appWindows.forEach { it.close(); it.screen.release() }
        windowControls.values.forEach { it.release() }
        keyboard?.release()
        lab?.release(); lab = null
        Gl.deleteTexture(reticleTex); Gl.deleteTexture(ringTex); Gl.deleteTexture(dragChipTex)
        radialTex.forEach { Gl.deleteTexture(it) }
    }

    override fun onAction(ctx: XrContext, action: ControllerAction, pressed: Boolean, side: JoyConSide?) {
        if (!pressed) return
        when (action) {
            ControllerAction.BACK -> backOrClose()
            ControllerAction.MENU -> openSystem("settings")
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- per frame
    override fun onFrame(ctx: XrContext, dt: Float) {
        while (true) { val t = glTasks.poll() ?: break; t() }
        processPendingCloses()
        val s = app.settings.current
        val head = ctx.input.head
        if (!anchored && ctx.frameIndex > 5) {
            anchorTo(head); anchored = true
            if (!restored) { restored = true; restoreSession() }
        }
        if (ctx.frameIndex % 90 == 0L) saveSession()
        val windowsShown = !windowsMinimized && appWindows.any { it.visible }
        applyBackground()

        surfaces.clear()
        // inside a 360 photo/video only the taskbar and the open panels stay
        val immersiveMedia = ctx.renderer.immersive != null
        // New mode: the library is home while no window is open; otherwise it opens from the taskbar
        val showLibrary = !immersiveMedia && (libraryShown || (s.launcher.mode == LauncherMode.NEW && !windowsShown))
        if (!showLibrary && libraryShown) libraryShown = false
        if (showLibrary) surfaces += library
        surfaces += taskbar
        surfaces += windows.filter { it.visible }
        if (!windowsMinimized) for (w in appWindows) if (w.visible) {
            surfaces += w
            if (!isTheater(w)) windowControls[w]?.let { surfaces += it }
        }
        keyboard?.let { if (it.visible) surfaces += it }
        lab?.let { surfaces += it.panel }

        updatePointers(ctx, surfaces, s.handTracking.directTouch)
        lab?.update(dt, ctx.input.hands, head, if (cursorRayValid) cursorRay else null, cursorLabDown)

        // submit
        if (showLibrary) { library.update(dt); library.submit(ctx.scene) }
        lab?.submit()
        taskbar.update(dt); taskbar.submit(ctx.scene)
        windows.removeAll { w -> if (!w.visible) { w.release(); true } else false }
        for (w in windows) { w.update(dt); w.submit(ctx.scene) }
        if (!windowsMinimized) for ((w, c) in windowControls) {
            if (isTheater(w)) { w.submit(ctx.scene); continue }
            placeAbove(w, c); w.submit(ctx.scene); c.update(dt); c.submit(ctx.scene)
        }
        keyboard?.let { k -> if (k.visible) k.submit(ctx.scene) else { k.release(); keyboard = null } }
        submitPointerVisuals()
        updateLinkDrag()
        submitRadialMenus()
    }

    private fun anchorTo(head: Pose) {
        val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
        anchorYaw = kotlin.math.atan2(-f.x, -f.z)
        placeLibraryHome(head)
        taskbar.pose.position.set(head.position.x - sin(anchorYaw) * 1.2f, head.position.y - 0.5f, head.position.z - cos(anchorYaw) * 1.2f)
        Quat.fromAxisAngle(0f, 1f, 0f, anchorYaw, taskbar.pose.orientation)
        // tilt the taskbar up towards the eyes
        taskbar.pose.orientation.mul(Quat.fromAxisAngle(1f, 0f, 0f, -0.35f), taskbar.pose.orientation)
    }

    private fun faceTowards(p: Pose, eye: Vec3) {
        val dx = p.position.x - eye.x; val dy = p.position.y - eye.y; val dz = p.position.z - eye.z
        val yaw = kotlin.math.atan2(-dx, -dz)
        val pitch = kotlin.math.atan2(dy, kotlin.math.sqrt(dx * dx + dz * dz))
        Quat.fromAxisAngle(0f, 1f, 0f, yaw, p.orientation)
        // rotating about +X by the (negative) pitch tilts the face up towards eyes above it
        p.orientation.mul(Quat.fromAxisAngle(1f, 0f, 0f, pitch), p.orientation)
    }

    /** The library as New mode's home: straight ahead of the launcher anchor, a little above the taskbar. */
    private fun placeLibraryHome(head: Pose) {
        library.pose.position.set(head.position.x - sin(anchorYaw) * 1.55f, head.position.y + 0.06f, head.position.z - cos(anchorYaw) * 1.55f)
        Quat.fromAxisAngle(0f, 1f, 0f, anchorYaw, library.pose.orientation)
    }

    // ---------------------------------------------------------------- pointers
    private val hitPoints = ArrayList<Vec3>()

    private val handRays = arrayOf(RayPointer(5, PointerSource.HAND_RAY), RayPointer(6, PointerSource.HAND_RAY))
    private val handStabilizers = arrayOf(com.hyperxr.input.RayStabilizer(), com.hyperxr.input.RayStabilizer())
    private val handRayOut = arrayOf(Ray(), Ray())
    private val handRayVisible = BooleanArray(2)
    private val pinchStrength = FloatArray(2)
    private val dwell = com.hyperxr.input.DwellClick()
    private val linkDrag = com.hyperxr.input.DragDrop<String>()
    private val pendingDrags = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Int>>()
    private var dragChipTex = 0
    private var dragChipAspect = 4f
    private val pointerRays = HashMap<Int, Ray>()
    private val pointerDown = HashMap<Int, Boolean>()
    private val radialItems = listOf("Voltar", "Início", "Teclado", "Captura")
    private val radials = arrayOf(com.hyperxr.input.RadialMenu(radialItems), com.hyperxr.input.RadialMenu(radialItems))
    private val radialTex = IntArray(4)
    private val radialAspect = FloatArray(4)
    private val pinchPoint = Vec3()
    private val pinchBall = arrayOf(Vec3(), Vec3())
    private val ballPointers = arrayOf(com.hyperxr.input.BallPointer(), com.hyperxr.input.BallPointer())
    private val pinchBallVisible = BooleanArray(2)
    private val identityQ = Quat()
    private val MICRO_SCROLL_SCREENS = 1.5f
    private val microGestures = arrayOf(com.hyperxr.handtracking.MicroGestures(), com.hyperxr.handtracking.MicroGestures())
    private var gazeHit: Vec3? = null

    private fun updatePointers(ctx: XrContext, surfaces: List<InteractionSurface>, directTouch: Boolean) {
        val now = System.nanoTime()
        com.hyperxr.input.HandRay.pitchOffsetDeg = app.settings.current.handTracking.rayPitchDeg
        hitPoints.clear()
        val input = ctx.input
        val controls = app.settings.current.controllers
        val dt = if (lastPointerNs == 0L) 1f / 60 else ((now - lastPointerNs) / 1e9f).coerceIn(0.001f, 0.1f)
        lastPointerNs = now
        val anyController = input.controllers.any { it.connected }
        ctrlPointers[1].update(null, false, surfaces, now)
        if (anyController) {
            // Joy-Con: the stick moves a cursor, A clicks (hold to drag), B goes back / closes
            val p = input.controllers[1].takeIf { it.connected } ?: input.controllers[0]
            val other = input.controllers[0].takeIf { it.connected && it !== p }
            if (input.recenterCursor) { stickCursor.recenter(); input.recenterCursor = false }
            val shoulder = p.buttons and (1 or 2 or 4 or 8) != 0 // R, L, ZR, ZL held: the stick scrolls
            var scrollY = if (shoulder) p.stickY else 0f
            val second = other?.stickY ?: p.stick2Y
            if (kotlin.math.abs(second) > 0.15f) scrollY = second
            stickCursor.speedDegPerSec = controls.stickCursorSpeed
            if (!shoulder) stickCursor.update(p.stickX, p.stickY, dt)
            stickCursor.ray(input.head, ray)
            val buttons = p.buttons or (other?.buttons ?: 0)
            val aDown = buttons and 128 != 0
            // A on a lab object carries it; A on the UI clicks
            val l = lab
            val toLab = l != null && ctrlPointers[0].captured == null && (cursorLabDown || (ctrlPointers[0].hovered == null && l.cursorTargetsObject(ray)))
            cursorRay.origin.set(ray.origin); cursorRay.direction.set(ray.direction); cursorRayValid = true
            cursorLabDown = toLab && aDown
            ctrlPointers[0].update(ray, aDown && !toLab, surfaces, now)
            recordPointer(1, ray, aDown)
            if (ctrlPointers[0].hitDistance.isNaN()) hitPoints += ray.pointAt(1.5f) else trackHit(ctrlPointers[0], ray)
            dragWindows(ctrlPointers[0], ray, aDown)
            if (kotlin.math.abs(scrollY) > 0.15f) {
                val target = (ctrlPointers[0].hovered as? SpatialWindow) ?: appWindows.lastOrNull { it.visible }
                target?.scroll(-scrollY * STICK_SCROLL_PX_PER_SEC * dt)
            }
            // B: short press = back / close the top panel; held 0.8 s on a window = close it
            val bDown = buttons and 256 != 0
            if (bDown && !bWasDown) bDownNs = now
            if (bDown && bDownNs != 0L && now - bDownNs > 800_000_000L) {
                bDownNs = 0L
                ((ctrlPointers[0].hovered as? SpatialWindow) ?: appWindows.lastOrNull { it.visible })?.let { pendingClose += it }
            }
            if (!bDown && bWasDown && bDownNs != 0L) { bDownNs = 0L; backOrClose() }
            bWasDown = bDown
        } else { ctrlPointers[0].update(null, false, surfaces, now); cursorRayValid = false; cursorLabDown = false }
        // hands: far rays clicked with a pinch, plus direct touch with the fingertip
        for (i in 0..1) {
            val h = input.hands[i]
            // pinch-and-hold away from any target opens the radial menu around this hand
            val pinching = h.gesture == com.hyperxr.handtracking.Gesture.PINCH
            val overTarget = handRays[i].hovered != null || handRays[i].captured != null || touches[i].hovered != null || linkDrag.active ||
                lab?.handBusy?.get(i) == true
            if (h.isUsable) {
                pinchPoint.set(h.joints[HandJoint.THUMB_TIP]).add(h.joints[HandJoint.INDEX_TIP]).scale(0.5f)
                radials[i].update(pinching, pinchPoint, input.head, overTarget, now)?.let { radialAction(it) }
            } else radials[i].update(false, pinchPoint, input.head, true, now)
            val menuOpen = radials[i].phase != com.hyperxr.input.RadialMenu.Phase.IDLE && !overTarget
            // a hand working on the lab bench (holding or reaching an object) does not point at the UI
            val labBusy = lab?.handBusy?.get(i) == true
            // The pinch ball is the pointer: what is under the ball, as seen from the eyes, is what a
            // pinch clicks (no beam). The shoulder ray remains available in Settings.
            if (h.isUsable) pinchBall[i].set(h.joints[HandJoint.THUMB_TIP]).add(h.joints[HandJoint.INDEX_TIP]).scale(0.5f)
            val raw = when {
                menuOpen || labBusy || !h.isUsable -> { ballPointers[i].reset(); null }
                app.settings.current.handTracking.ballPointer -> ballPointers[i].update(pinchBall[i], pinching, input.head.position, now, ray)
                else -> com.hyperxr.input.HandRay.compute(h, input.head, rightHand = i == 1, ray)
            }
            pinchBallVisible[i] = h.isUsable && !labBusy
            val handRay = handStabilizers[i].update(raw, now, handRayOut[i])
            // pinch is the click; it is read from the (held) state so a lost frame does not release it
            val pinch = h.gesture == com.hyperxr.handtracking.Gesture.PINCH || (raw == null && handRays[i].captured != null && handRay != null)
            handRays[i].update(handRay, pinch, surfaces, now)
            if (handRay != null) recordPointer(5 + i, handRay, pinch) else pointerRays.remove(5 + i)
            handRayVisible[i] = handRay != null
            pinchStrength[i] = if (h.isUsable) h.pinchStrength else 0f
            if (handRay != null) { trackHit(handRays[i], handRay); dragWindows(handRays[i], handRay, pinch) }
            // microgestures: thumb sliding along the index scrolls, a thumb double tap goes back
            if (h.isUsable && !menuOpen && handRays[i].captured == null) {
                when (val e = microGestures[i].update(h, now)) {
                    is com.hyperxr.handtracking.MicroGestures.Event.Scroll -> scrollTarget(i)?.let { w -> w.scroll(e.delta * MICRO_SCROLL_SCREENS * w.screen.heightPx) }
                    com.hyperxr.handtracking.MicroGestures.Event.DoubleTap -> radialAction(0)
                    null -> {}
                }
            } else microGestures[i].reset()
            val tip = if (directTouch && h.isUsable) h.joints[HandJoint.INDEX_TIP] else null
            touches[i].update(tip, h.depthSource == DepthSource.HAND_SIZE_PRIOR, surfaces, now)
        }
        // gaze: screen tap / viewer button, or dwell (look at a button to click it)
        input.gazeRay(ray)
        dwell.dwellNs = if (controls.dwellClick && !anyController) controls.dwellMs * 1_000_000L else 0L
        val dwellDown = dwell.update(gaze.hovered ?: gaze.captured, gaze.hitU, gaze.hitV, now)
        gaze.update(ray, input.tapDown || dwellDown, surfaces, now)
        recordPointer(0, ray, input.tapDown || dwellDown)
        gazeHit = if (gaze.hitDistance.isNaN() || anyController) null else ray.pointAt(gaze.hitDistance - 0.004f)
        dragWindows(gaze, ray, input.tapDown)
    }

    /** Window the microgesture applies to: the one looked at, else the one this hand points at, else the newest. */
    private fun scrollTarget(hand: Int): com.hyperxr.windows.SpatialWindow? =
        (gaze.hovered as? com.hyperxr.windows.SpatialWindow)
            ?: (handRays[hand].hovered as? com.hyperxr.windows.SpatialWindow)
            ?: appWindows.lastOrNull { it.visible }

    private val stickCursor = com.hyperxr.input.StickCursor()
    private var lastPointerNs = 0L
    private var bWasDown = false
    private var bDownNs = 0L
    private val STICK_SCROLL_PX_PER_SEC = 1400f

    /** Back: keyboard, then the top panel, then the window's own back. */
    private fun backOrClose() {
        if (keyboard != null) { closeKeyboard(); return }
        val top = windows.lastOrNull { it.visible && it.closable }
        if (top != null) { top.visible = false; top.onClose?.invoke(); return }
        ((ctrlPointers[0].hovered as? SpatialWindow) ?: appWindows.lastOrNull { it.visible })?.back()
    }

    private fun recordPointer(id: Int, r: Ray, down: Boolean) {
        val copy = pointerRays.getOrPut(id) { Ray() }
        copy.origin.set(r.origin); copy.direction.set(r.direction)
        pointerDown[id] = down
    }

    /** Link drag-and-drop: follows the pointer that picked the link and drops where it is released. */
    private fun updateLinkDrag() {
        pendingDrags.poll()?.let { (url, pid) ->
            linkDrag.begin(url, pid)
            Gl.deleteTexture(dragChipTex)
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
            val bmp = TextRenderer.render("Link: $host", 40f, Color.WHITE, isBold = true, maxWidth = 900)
            dragChipAspect = bmp.width.toFloat() / bmp.height
            dragChipTex = Gl.uploadBitmap(bmp); bmp.recycle()
        }
        if (!linkDrag.active) return
        val pid = linkDrag.pointerId
        val r = pointerRays[pid]
        val down = pointerDown[pid] ?: false
        // the window under the dragging pointer (nearest hit)
        var target: com.hyperxr.windows.SpatialWindow? = null
        var best = Float.MAX_VALUE
        val uv = FloatArray(2)
        if (r != null) for (w in appWindows) {
            if (!w.visible) continue
            val d = com.hyperxr.input.SurfaceMath.raycast(w, r, uv)
            if (!d.isNaN() && d < best) { best = d; target = w }
        }
        if (r == null && pid !in pointerDown) { linkDrag.cancel(); return }
        val drop = linkDrag.update(pid, down, target)
        if (drop != null) {
            val t = drop.target as? com.hyperxr.windows.SpatialWindow
            if (t != null) t.openUrl(drop.payload)
            else openBrowser(drop.payload, app.settings.current.network.webAppsDesktopMode)
            return
        }
        if (r != null) {
            val chip = ctx.scene.addPanel()
            chip.texture = dragChipTex
            chip.pose.position.set(r.pointAt(if (best < Float.MAX_VALUE) best - 0.03f else 1.0f))
            faceTowards(chip.pose, ctx.input.head.position)
            chip.height = 0.035f; chip.width = chip.height * dragChipAspect
            chip.radius = 0.0175f
            chip.tint[0] = 0.92f; chip.tint[1] = 0.94f; chip.tint[2] = 1f
            chip.overlay = true
            chip.glow = if (target != null) 0.4f else 0f
        }
    }

    private fun radialAction(i: Int) {
        when (i) {
            0 -> windows.lastOrNull { it.visible && it.closable }?.let { it.visible = false } ?: appWindows.lastOrNull()?.back()
            1 -> { anchored = false } // re-anchor the launcher in front of the user
            2 -> appWindows.lastOrNull()?.let { w -> openKeyboard(w) }
                ?: openWindow(InfoPanel("Teclado", "Abra um navegador ou app para digitar.").apply { placeInFront(ctx.input.head) })
            3 -> capture()
        }
    }

    private fun submitRadialMenus() {
        for (m in radials) {
            if (m.phase != com.hyperxr.input.RadialMenu.Phase.OPEN) continue
            for (k in radialItems.indices) {
                if (radialTex[k] == 0) {
                    val bmp = TextRenderer.render(radialItems[k], 44f, Color.WHITE, isBold = true)
                    radialAspect[k] = bmp.width.toFloat() / bmp.height
                    radialTex[k] = Gl.uploadBitmap(bmp); bmp.recycle()
                }
                val (ox, oy) = m.itemOffset(k)
                val p = ctx.scene.addPanel()
                p.texture = radialTex[k]
                p.pose.orientation.set(m.plane.orientation)
                m.plane.transformPoint(Vec3(ox, oy, 0f), p.pose.position)
                val sel = m.selected == k
                p.height = if (sel) 0.034f else 0.026f; p.width = p.height * radialAspect[k]
                p.radius = p.height / 2
                p.glow = if (sel) 0.5f else 0f
                p.alpha = if (sel) 1f else 0.75f
                p.overlay = true
            }
            val c = ctx.scene.addPanel()
            c.texture = ringTex
            c.pose.set(m.plane)
            c.width = 0.05f; c.height = 0.05f; c.radius = 0f
            c.overlay = true
        }
    }

    private fun trackHit(p: RayPointer, r: Ray) {
        if (!p.hitDistance.isNaN()) hitPoints += r.pointAt(p.hitDistance - 0.004f)
    }

    private fun dragWindows(p: RayPointer, r: Ray, down: Boolean) {
        val target = p.captured as? XrPanel ?: return
        if (down && target.isDragging) target.drag(r.origin, r.direction, ctx.input.head)
        if (!down) target.endDrag()
    }

    private fun submitPointerVisuals() {
        gazeHit?.let { hp ->
            val head = ctx.input.head.position
            val dist = head.distanceTo(hp)
            val base = 0.012f * dist
            val p = ctx.scene.addPanel()
            p.texture = reticleTex
            p.pose.position.set(hp)
            faceTowards(p.pose, head)
            p.width = base; p.height = base; p.radius = 0f
            p.overlay = true
            if (dwell.progress > 0.05f) {
                val r = ctx.scene.addPanel()
                r.texture = ringTex
                r.pose.set(p.pose)
                val sz = base * (3.2f - 1.6f * dwell.progress)
                r.width = sz; r.height = sz; r.radius = 0f
                r.alpha = dwell.progress
                r.overlay = true
            }
        }
        for (hp in hitPoints) {
            val p = ctx.scene.addPanel()
            p.texture = reticleTex
            p.pose.position.set(hp)
            faceTowards(p.pose, ctx.input.head.position)
            p.width = 0.018f; p.height = 0.018f; p.radius = 0f
            p.overlay = true
        }
        // pinch ball: a small sphere between thumb and index, no beam. It fills with colour as the
        // fingers close and turns pink when the pinch (the click) is held.
        for (i in 0..1) {
            if (!pinchBallVisible[i]) continue
            val k = pinchStrength[i]
            val pinched = handRays[i].captured != null || ctx.input.hands[i].gesture == com.hyperxr.handtracking.Gesture.PINCH
            val ball = ctx.scene.addDraw()
            ball.mesh = ctx.renderer.sphere
            ball.unlit = true
            ball.late = true // over icons and windows, which are farther away
            if (pinched) { ball.color[0] = 0.94f; ball.color[1] = 0.36f; ball.color[2] = 0.75f }
            else { ball.color[0] = 1f - 0.4f * k; ball.color[1] = 1f - 0.45f * k; ball.color[2] = 1f }
            ball.color[3] = 1f
            val r = if (pinched) 0.008f else 0.011f - 0.002f * k
            com.hyperxr.math.Mat4.compose(pinchBall[i], identityQ, 2 * r, 2 * r, 2 * r, ball.matrix) // unit sphere mesh has radius 0.5
        }
    }

    private fun ringBitmap(): Bitmap {
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
        p.color = Color.argb(160, 0, 0, 0); p.strokeWidth = 14f; c.drawCircle(64f, 64f, 54f, p)
        p.color = Color.rgb(235, 238, 245); p.strokeWidth = 8f; c.drawCircle(64f, 64f, 54f, p)
        return b
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
        // one panel per kind: the previous one is closed (and its players/bitmaps freed)
        windows.removeAll { old -> (old.javaClass == w.javaClass && old !is InfoPanel && old !is PermissionPromptPanel).also { if (it) old.release() } }
        windows += w
    }

    private fun openSystem(target: String) {
        val head = ctx.input.head
        val w: XrPanel = when (target) {
            "settings" -> QuickSettingsPanel(this) { openSystem(it) }
            "library" -> { if (!libraryOpen()) toggleLibrary(); return }
            "environments" -> EnvironmentsPanel(this)
            "files" -> com.hyperxr.files.FilesPanel(this)
            "gallery" -> com.hyperxr.files.GalleryPanel(this)
            "messages" -> MessagesPanel()
            "help" -> HelpPanel()
            "tv" -> TvPanel(this)
            "xrapps" -> XrAppsPanel(this)
            "androidapps" -> AndroidAppsPanel(activity) { pkg, comp, title -> openAndroidApp(pkg, comp, title) }
            "capture" -> { capture(); return }
            "handlab" -> { toggleLab(); return }
            "catgame" -> { ctx.ui { activity.startActivity(Intent(activity, com.hyperxr.catgame.CatGameActivity::class.java)) }; return }
            else -> InfoPanel("HyperXR", "Painel '$target' indisponível")
        }
        placeAboveDock(w, yawOffsetDeg = (windows.size % 3 - 1) * 8f)
        openWindow(w)
    }

    // Windows sit above the dock row: the bar's centre is BAR_Y below the eyes at WIN_DIST, so its
    // bottom edge stays above the dock icons; in New mode they are nearer than the floating icons.
    private val WIN_DIST = 1.45f
    private val BAR_Y = -0.17f

    /** The bar is the window's handle: dragging it moves the window placed right above it. */
    private fun placeAbove(w: SpatialWindow, c: XrPanel) {
        w.pose.orientation.set(c.pose.orientation)
        val up = c.pose.orientation.rotate(Vec3(0f, 1f, 0f))
        w.pose.position.set(c.pose.position).addScaled(up, c.heightMeters / 2 + w.heightMeters / 2 + 0.02f)
        if (w.curved) w.radius = ctx.input.head.position.distanceTo(w.pose.position).coerceIn(0.8f, 3f)
    }

    private fun openWindow(w: SpatialWindow) {
        val c: XrPanel = if (w is com.hyperxr.browser.BrowserWindow) BrowserBar(w, this) else WindowControls(w)
        val head = ctx.input.head
        val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
        // new windows fill slots centre, left, right, far left, far right (~45 degrees apart)
        val slot = appWindows.count { it.visible }
        val offsetDeg = when (slot % 5) { 0 -> 0f; 1 -> -46f; 2 -> 46f; 3 -> -92f; else -> 92f }
        val yaw = kotlin.math.atan2(-f.x, -f.z) + Math.toRadians(offsetDeg.toDouble()).toFloat()
        c.pose.position.set(head.position.x - sin(yaw) * WIN_DIST, head.position.y + BAR_Y, head.position.z - cos(yaw) * WIN_DIST)
        Quat.fromAxisAngle(0f, 1f, 0f, yaw, c.pose.orientation)
        w.onLinkDrag = { url, pid -> pendingDrags += url to pid }
        w.onKeyboardRequest = { glTasks += { if (appWindows.contains(w)) openKeyboard(w) } }
        if (w is com.hyperxr.browser.BrowserWindow) {
            w.onPrompt = { pr -> glTasks += { showPrompt(pr) } }
            w.onFullscreen = { on -> glTasks += { if (on) enterTheater(w) else exitTheater(w) } }
        }
        windowsMinimized = false
        appWindows += w
        windowControls[w] = c
    }

    override fun closeWindow(w: SpatialWindow) {
        if (!appWindows.contains(w)) return
        exitTheater(w)
        appWindows.remove(w)
        windowControls.remove(w)?.release()
        val k = keyboard
        if (k != null && (k.target === w || (k.target as? UrlEditor) != null)) closeKeyboard()
        w.close()
        w.screen.release()
        saveSession()
    }

    private fun openWeb(a: AppEntry) {
        val desktop = app.settings.current.network.webAppsDesktopMode || a.id == "web:whatsapp"
        openBrowser(a.target, desktop, a.title)
    }

    /** GeckoView browser (Wolvic's engine) when available, otherwise the system WebView. */
    private fun openBrowser(url: String, desktop: Boolean, title: String = "Navegador") {
        // a second web app opens as a tab of an existing browser window instead of a new window
        appWindows.firstOrNull { it is com.hyperxr.browser.BrowserWindow }?.let { b ->
            if (title != "Navegador") { (b as com.hyperxr.browser.BrowserWindow).openInNewTab(url); b.visible = true; windowsMinimized = false; return }
        }
        ctx.ui {
            val runtime = com.hyperxr.browser.GeckoEngine.runtime(activity)
            glTasks += {
                if (runtime != null) openWindow(com.hyperxr.browser.BrowserWindow(activity, root, runtime, url, desktop, browserStore))
                else {
                    Diagnostics.log("Browser fallback to WebView: ${com.hyperxr.browser.GeckoEngine.error}")
                    openWindow(WebWindow(activity, url, title, desktop))
                }
            }
        }
    }

    fun openAndroidApp(pkg: String, component: String, title: String) {
        openWindow(AndroidAppWindow(activity, pkg, component, title))
    }

    // ---------------------------------------------------------------- keyboard, prompts, sizes
    override fun openKeyboard(target: com.hyperxr.windows.KeyTarget) {
        val k = keyboard
        if (k != null && k.visible && k.target === target) return
        k?.release()
        keyboard = KeyboardPanel(target) { cb -> dictate(cb) }.also { kb ->
            // close and low, tilted up: reachable with the fingertip (Direct Touch) or a pinch
            kb.placeInFront(ctx.input.head, 0.72f, -0.32f)
            kb.pose.orientation.mul(Quat.fromAxisAngle(1f, 0f, 0f, -0.45f), kb.pose.orientation)
        }
    }

    override fun closeKeyboard() {
        keyboard?.let { k -> (k.target as? UrlEditor)?.let { if (it.active) it.cancel() }; k.release() }
        keyboard = null
    }

    override fun openBookmarks(w: com.hyperxr.browser.BrowserWindow) {
        openWindow(BookmarksPanel(w, browserStore).apply { placeAboveDock(this, yawOffsetDeg = 0f, distance = 1.15f) })
    }

    override fun resize(w: SpatialWindow, factor: Float) {
        w.widthMeters = (w.widthMeters * factor).coerceIn(0.6f, 2.8f)
    }

    private fun showPrompt(pr: com.hyperxr.browser.BrowserPrompt) {
        val p = PromptPanel(pr, this)
        placeAboveDock(p, 0f, 1.05f)
        windows += p
    }

    /** Speech recognition into the focused text target (Android SpeechRecognizer, on the UI thread). */
    private fun dictate(onText: (String?) -> Unit) = ctx.ui {
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 77)
            onText(null); return@ui
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(activity)) { onText(null); return@ui }
        val sr = android.speech.SpeechRecognizer.createSpeechRecognizer(activity)
        sr.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                onText(text); sr.destroy()
            }
            override fun onError(error: Int) { onText(null); sr.destroy() }
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        sr.startListening(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
        })
    }

    // ---------------------------------------------------------------- theater (fullscreen video)
    private class Saved(val width: Float, val curved: Boolean, val radius: Float, val pose: Pose)
    private val theater = HashMap<SpatialWindow, Saved>()
    private fun isTheater(w: SpatialWindow) = theater.containsKey(w)

    /** Fullscreen page video: a large curved screen at 2.4 m, centred on the eyes, bar hidden. */
    private fun enterTheater(w: SpatialWindow) {
        if (isTheater(w) || !appWindows.contains(w)) return
        theater[w] = Saved(w.widthMeters, w.curved, w.radius, Pose().also { it.set(w.pose) })
        val head = ctx.input.head
        val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
        val yaw = kotlin.math.atan2(-f.x, -f.z)
        val d = 2.4f
        w.pose.position.set(head.position.x - sin(yaw) * d, head.position.y + 0.05f, head.position.z - cos(yaw) * d)
        Quat.fromAxisAngle(0f, 1f, 0f, yaw, w.pose.orientation)
        w.widthMeters = 3.2f; w.curved = true; w.radius = d
        closeKeyboard()
    }

    private fun exitTheater(w: SpatialWindow) {
        val sv = theater.remove(w) ?: return
        w.widthMeters = sv.width; w.curved = sv.curved; w.radius = sv.radius; w.pose.set(sv.pose)
    }

    // ---------------------------------------------------------------- Hand Physics Lab
    private fun toggleLab() {
        val l = lab
        if (l != null) { l.release(); lab = null; return }
        lab = com.hyperxr.lab.HandPhysicsLab(ctx) { glTasks += { lab?.release(); lab = null } }.also {
            it.initGl()
            it.place(ctx.input.head, anchorYaw)
        }
    }

    // ---------------------------------------------------------------- dock Home / window state
    override fun windowCount(): Int = appWindows.size
    override fun windowsMinimized(): Boolean = windowsMinimized
    override fun toggleWindows() { windowsMinimized = !windowsMinimized; if (windowsMinimized) closeKeyboard() }

    /** Places a system panel so its bottom edge stays above the dock row. */
    private fun placeAboveDock(p: XrPanel, yawOffsetDeg: Float, distance: Float = 1.35f) {
        val bottom = BAR_Y * distance / WIN_DIST - 0.06f
        p.placeInFront(ctx.input.head, distance, bottom + p.heightMeters / 2, yawOffsetDeg)
    }

    // ---------------------------------------------------------------- session restore
    private val sessionFile by lazy { File(app.filesDir, "xr_session.json") }
    private val sessionIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun sessionJson(): String {
        val arr = org.json.JSONArray()
        for (w in appWindows) {
            val o = org.json.JSONObject().put("w", w.widthMeters.toDouble()).put("curved", w.curved)
            when (w) {
                is com.hyperxr.browser.BrowserWindow -> {
                    val urls = org.json.JSONArray(); w.tabs.forEach { t -> if (t.url.startsWith("http")) urls.put(t.url) }
                    if (urls.length() == 0) continue
                    o.put("t", "browser").put("urls", urls).put("active", w.tabs.indexOf(w.active).coerceAtLeast(0)).put("desktop", w.active?.desktop == true)
                }
                is AndroidAppWindow -> o.put("t", "android").put("pkg", w.packageName).put("comp", w.component).put("title", w.title)
                is WebWindow -> o.put("t", "web").put("url", w.url).put("title", w.title)
                else -> continue
            }
            arr.put(o)
        }
        return arr.toString()
    }

    private fun saveSession() {
        if (!restored) return
        val json = sessionJson()
        if (json == lastSessionJson) return
        lastSessionJson = json
        sessionIo.execute { runCatching { sessionFile.writeText(json) } }
    }

    private fun restoreSession() {
        if (!app.settings.current.launcher.restoreWindows) return
        val arr = runCatching { org.json.JSONArray(sessionFile.readText()) }.getOrNull() ?: return
        lastSessionJson = arr.toString()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            when (o.optString("t")) {
                "browser" -> {
                    val urls = o.optJSONArray("urls") ?: continue
                    if (urls.length() == 0) continue
                    val desktop = o.optBoolean("desktop")
                    val active = o.optInt("active")
                    ctx.ui {
                        val rt = com.hyperxr.browser.GeckoEngine.runtime(activity) ?: return@ui
                        val bw = com.hyperxr.browser.BrowserWindow(activity, root, rt, urls.getString(0), desktop, browserStore)
                        for (k in 1 until urls.length()) bw.newTab(urls.getString(k), desktop)
                        bw.tabs.getOrNull(active)?.let { bw.switchTo(it) }
                        bw.widthMeters = o.optDouble("w", 1.25).toFloat(); bw.curved = o.optBoolean("curved")
                        glTasks += { openWindow(bw) }
                    }
                }
                "android" -> {
                    val w = AndroidAppWindow(activity, o.optString("pkg"), o.optString("comp"), o.optString("title"))
                    w.widthMeters = o.optDouble("w", 1.6).toFloat(); w.curved = o.optBoolean("curved")
                    openWindow(w)
                }
                "web" -> openWindow(WebWindow(activity, o.optString("url"), o.optString("title", "Navegador"), false))
            }
        }
    }

    /** Control bar under a window: title + status, back/reload/keyboard/curve/size/external/close. */
    private inner class WindowControls(val w: SpatialWindow) : XrPanel(1.4f, 0.2f, title = w.title) {
        init { closable = false }
        private var lastStatus: String? = null
        override fun update(dt: Float) { if (w.status != lastStatus) { lastStatus = w.status; dirty = true } }
        override fun draw(c: com.hyperxr.spatialui.PanelCanvas) {
            w.status?.let { c.label(it, 420f, 34f, 24f, com.hyperxr.spatialui.XrTheme.accent2, maxWidth = c.w - 560f) }
            val n = if (w.supportsReload) 8 else 7
            val bw = (c.w - 2 * c.pad - (n - 1) * 10f) / n
            var x = c.pad
            val y = c.y
            fun b(id: String, label: String, accent: Boolean = false, act: () -> Unit) { c.button(id, label, x, y, bw, 70f, accent = accent) { act() }; x += bw + 10f }
            b("back", "Voltar") { w.back() }
            if (w.supportsReload) b("reload", "Recarregar") { w.reload() }
            b("kbd", "Teclado") { openKeyboard(w) }
            b("curve", if (w.curved) "Plano" else "Curvar") { w.curved = !w.curved; dirty = true }
            b("smaller", "Menor") { resize(w, 1f / 1.15f) }
            b("bigger", "Maior") { resize(w, 1.15f) }
            b("ext", if (w is WebWindow) "Externo" else "Tela 2D") { w.openExternally() }
            b("close", "Fechar", accent = true) { pendingClose += w }
        }
    }
    private val pendingClose = ArrayList<SpatialWindow>()

    // ---------------------------------------------------------------- home UI (library + taskbar)
    override fun backgroundOptions(): List<Pair<String, String>> = buildList {
        if (::ctx.isInitialized && ctx.passthroughAvailable) { add("camera" to "Câmera"); add("dim" to "Escurecer MR") }
        app.environments.installed().take(3).forEach { add(it.id to it.name.take(14)) }
    }

    override fun backgroundChoice(): String = background.ifEmpty { if (ctx.passthroughAvailable) "camera" else currentEnvId }

    override fun setBackgroundChoice(id: String) {
        background = id
        if (id != "camera" && id != "dim") app.environments.installed().firstOrNull { it.id == id }?.let { loadEnvironment(it) }
    }

    /** Camera (optionally darkened behind the UI) or the chosen 3D environment. */
    private fun applyBackground() {
        val choice = backgroundChoice()
        val camera = ctx.passthroughAvailable && (choice == "camera" || choice == "dim")
        ctx.scene.background = if (camera) Background.PASSTHROUGH else Background.ENVIRONMENT
        ctx.renderer.passthroughDim = if (camera && choice == "dim") 0.6f else 0f
    }

    override fun openTarget(target: String) = openSystem(target)

    private val androidIcons = HashMap<String, Bitmap>()
    override fun appIcon(app: AppEntry, size: Int): Bitmap {
        if (app.kind != AppKind.ANDROID_PACKAGE || app.iconKey != null) return ctx.icons.icon(app.iconKey, size)
        val pkg = app.target.substringBefore('/')
        return androidIcons.getOrPut("$pkg@$size") {
            try {
                val d = activity.packageManager.getApplicationIcon(pkg)
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
                    val c = Canvas(b); d.setBounds(0, 0, size, size); d.draw(c)
                }
            } catch (_: Throwable) { ctx.icons.icon(null, size) }
        }
    }

    override fun pinnedApps(): List<AppEntry> = model.dockApps(app.settings.current.launcher)
    override fun libraryOpen(): Boolean = libraryShown || (mode() == LauncherMode.NEW && (windowsMinimized || appWindows.none { it.visible }))
    override fun toggleLibrary() {
        if (libraryOpen()) {
            // in New mode with nothing open the library is home: hide the windows' absence instead
            if (libraryShown) libraryShown = false else if (appWindows.isNotEmpty()) windowsMinimized = false
            return
        }
        libraryShown = true
        placeAboveDock(library, yawOffsetDeg = 0f, distance = 1.3f)
    }
    override fun isRunning(app: AppEntry): Boolean = appWindows.any { it.title == app.title } || windows.any { it.visible && it.title == app.title }

    // ---------------------------------------------------------------- MediaHost (files, gallery, viewers)
    override fun openPanel(p: XrPanel, distance: Float) {
        placeAboveDock(p, yawOffsetDeg = (windows.size % 3 - 1) * 8f, distance = distance)
        openWindow(p)
    }
    override fun openKeyboardFor(target: com.hyperxr.windows.KeyTarget) = openKeyboard(target)
    override fun showImmersive(m: com.hyperxr.rendering.SceneRenderer.Immersive?) { ctx.renderer.immersive = m }
    override val immersive: com.hyperxr.rendering.SceneRenderer.Immersive? get() = ctx.renderer.immersive
    override fun headYaw(): Float {
        val f = ctx.input.head.orientation.rotate(Vec3(0f, 0f, -1f))
        return kotlin.math.atan2(-f.x, -f.z)
    }
    override fun onGl(task: () -> Unit) { glTasks += task }

    // ---------------------------------------------------------------- LauncherActions
    override fun launch(app: AppEntry) {
        libraryShown = false  // the library opened from the taskbar gives way to what was launched
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
    override fun setMode(mode: LauncherMode) {
        app.settings.setLauncherMode(mode); taskbar.dirty = true
        libraryShown = false
        if (mode == LauncherMode.NEW) placeLibraryHome(ctx.input.head)
    }
    override fun mode(): LauncherMode = app.settings.current.launcher.mode
    override fun recenter() { ctx.requestRecenter(); anchored = false }
    override fun exitToSettings() = ctx.ui {
        activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        activity.finish()
    }
    override fun currentEnvironmentId(): String = currentEnvId

    /** Picking an environment (library tab, Environments panel, gallery) also shows it in MR. */
    override fun selectEnvironment(entry: EnvironmentEntry) {
        loadEnvironment(entry)
        background = entry.id
    }

    private fun loadEnvironment(entry: EnvironmentEntry) {
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
        pendingClose.toList().forEach { closeWindow(it) }
        pendingClose.clear()
    }
}
