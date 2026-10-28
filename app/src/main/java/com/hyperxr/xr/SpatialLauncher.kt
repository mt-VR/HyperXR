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
class SpatialLauncher(override val activity: android.app.Activity, private val root: android.widget.FrameLayout) : XrContent, LauncherActions, BrowserUiHost, com.hyperxr.files.MediaHost, WorkspaceHost {
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
    /** The Ocean app (sky, sea and the sinking room), in the same space as the launcher while open. */
    private var ocean: com.hyperxr.ocean.OceanApp? = null
    /** One of the ten games, played in the launcher's space. */
    private var game: com.hyperxr.questgames.GameHost? = null
    /** The gesture tutorial, the first time in XR (until it is finished or skipped). */
    private var tutorial: XrTutorialPanel? = null
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
    /** App-to-World: apps as objects around the user. */
    private lateinit var appWorld: com.hyperxr.appworld.AppWorld
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
    override val hyperConnectStatus: String get() = if (::ctx.isInitialized) ctx.hyperConnect?.status ?: "" else ""
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
            AppEntry("pkg:${p.manifest.id}", p.manifest.name, AppKind.HYPERX_PACKAGE, AppCategory.HYPERXR_GAME, "hyperx_package", p.manifest.id,
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
        appWorld = com.hyperxr.appworld.AppWorld(ctx, this, this)
        ctx.ui { com.hyperxr.appworld.MediaHub.start(activity) }
        reticleTex = Gl.uploadBitmap(reticleBitmap())
        ringTex = Gl.uploadBitmap(ringBitmap())
        val env = app.environments.installed().firstOrNull { it.id == app.settings.current.launcher.environmentId } ?: PolyHavenCatalog.STUDIO
        loadEnvironment(env)
        File(app.filesDir, "crash").listFiles()?.forEach { Diagnostics.log("Package crash report: ${it.nameWithoutExtension}: ${it.readText().take(160)}") }
    }

    override fun onResume() { if (::ctx.isInitialized) activity.runOnUiThread { refreshInstalled() }; ocean?.onResume() }
    override fun onPause() { ocean?.onPause() }

    override fun onGlRelease() {
        (windows + library + taskbar).forEach { it.release() }
        handToolsOrNull?.release()
        assistantOrNull?.release()
        if (::appWorld.isInitialized) appWorld.release()
        appWindows.forEach { it.close(); it.screen.release() }
        windowControls.values.forEach { it.release() }
        keyboard?.release()
        lab?.release(); lab = null
        ocean?.release(); ocean = null
        game?.release(); game = null
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
        deliverPhoneTyping()
        followClock()
        processPendingCloses()
        val s = app.settings.current
        val head = ctx.input.head
        if (!anchored && ctx.frameIndex > 5) {
            anchorTo(head); anchored = true
            // the free-app notice, in front of you at every start of the VR mode
            FreeNoticePanel {}.also { n -> n.placeInFront(head, 1.15f, 0.12f); windows += n }
            if (!restored) { restored = true; restoreSession() }
        }
        if (ctx.frameIndex % 90 == 0L) saveSession()
        val windowsShown = !windowsMinimized && appWindows.any { it.visible }
        applyBackground()

        surfaces.clear()
        // inside a 360 photo/video only the taskbar and the open panels stay
        val immersiveMedia = ctx.renderer.immersive != null
        // New mode: the library is home while no window is open; otherwise it opens from the taskbar
        val showLibrary = !immersiveMedia && (libraryShown || (game == null && s.launcher.mode == LauncherMode.NEW && !windowsShown))
        if (!showLibrary && libraryShown) libraryShown = false
        if (showLibrary) surfaces += library
        surfaces += taskbar
        for (w in windows) if (w.visible) surfaces += w
        if (!windowsMinimized) for (w in appWindows) if (w.visible) {
            surfaces += w
            if (!isTheater(w)) windowControls[w]?.let { surfaces += it }
        }
        keyboard?.let { if (it.visible) surfaces += it }
        tableKeyboard?.let { tk ->
            tk.update(ctx.input.hands, head, ctx.camera as? com.hyperxr.mixedreality.ArCoreSession, System.nanoTime())
            surfaces += tk.controls
        }
        lab?.let { surfaces += it.panel }
        ocean?.let { surfaces += it.panel }
        game?.let { surfaces += it.hud }
        if (tutorial == null && !app.settings.current.onboarding.xrTutorialDone)
            tutorial = XrTutorialPanel(ctx) { app.settings.update { it.copy(onboarding = it.onboarding.copy(xrTutorialDone = true)) } }
        tutorial?.let { t -> if (t.visible) surfaces += t else { t.release(); tutorial = null } }
        if (ctx.input.handsAvailable && !immersiveMedia) handTools.update(ctx.input.hands, head, dt)
        surfaces += handTools.surfaces()
        // App-to-World objects (hidden inside a 360 photo/video, like the library)
        if (!immersiveMedia) surfaces += appWorld.hits

        updatePointers(ctx, surfaces, s.handTracking.directTouch)
        airDraw.update(ctx.input.hands, airBusy)
        airDraw.submit(ctx.scene)
        lab?.update(dt, ctx.input.hands, head, if (cursorRayValid) cursorRay else null, cursorLabDown)

        // submit
        if (showLibrary) { library.update(dt); library.submit(ctx.scene) }
        lab?.submit()
        ocean?.let { it.update(dt, head); it.submit(ctx.scene) }
        game?.let { g ->
            // a failing game closes by itself (with a report) instead of taking HyperXR down
            try { g.update(dt); g.draw() } catch (t: Throwable) { gameFailed(t) }
        }
        tutorial?.let { it.update(dt); it.submit(ctx.scene) }
        if (!immersiveMedia) { appWorld.update(dt); appWorld.submit() }
        taskbar.update(dt); taskbar.submit(ctx.scene)
        windows.removeAll { w -> if (!w.visible) { w.release(); true } else false }
        for (w in windows) { w.update(dt); w.submit(ctx.scene) }
        if (!windowsMinimized) for ((w, c) in windowControls) {
            if (isTheater(w)) { w.submit(ctx.scene); continue }
            placeAbove(w, c); w.submit(ctx.scene); c.update(dt); c.submit(ctx.scene)
        }
        keyboard?.let { k -> if (k.visible) k.submit(ctx.scene) else { k.release(); keyboard = null } }
        tableKeyboard?.submit(ctx.scene)
        handTools.submit()
        submitPointerVisuals()
        updateLinkDrag()
        submitRadialMenus()
    }

    private fun anchorTo(head: Pose) {
        val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
        anchorYaw = kotlin.math.atan2(-f.x, -f.z)
        placeLibraryHome(head)
        appWorld.anchorTo(head, anchorYaw)
        val k = uiFar()
        taskbar.pose.position.set(head.position.x - sin(anchorYaw) * 1.2f * k, head.position.y - 0.5f * k, head.position.z - cos(anchorYaw) * 1.2f * k)
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
        val k = uiFar()
        library.pose.position.set(head.position.x - sin(anchorYaw) * 1.55f * k, head.position.y + 0.06f * k, head.position.z - cos(anchorYaw) * 1.55f * k)
        Quat.fromAxisAngle(0f, 1f, 0f, anchorYaw, library.pose.orientation)
    }

    // ---------------------------------------------------------------- pointers
    private val hitPoints = ArrayList<Vec3>()

    private val handRays = arrayOf(RayPointer(5, PointerSource.HAND_RAY), RayPointer(6, PointerSource.HAND_RAY))
    private val handStabilizers = arrayOf(com.hyperxr.input.RayStabilizer(), com.hyperxr.input.RayStabilizer())
    private val handRayOut = arrayOf(Ray(), Ray())
    private val handRayVisible = BooleanArray(2)
    private val pinchStrength = FloatArray(2)
    private val pointPush = arrayOf(com.hyperxr.input.PointPush(), com.hyperxr.input.PointPush())
    private val pushPressed = BooleanArray(2)
    private val dwell = com.hyperxr.input.DwellClick()
    private val linkDrag = com.hyperxr.input.DragDrop<String>()
    private val pendingDrags = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Int>>()
    private var dragChipTex = 0
    private var dragChipAspect = 4f
    private val pointerRays = HashMap<Int, Ray>()
    private val pointerDown = HashMap<Int, Boolean>()
    // one menu for both gestures: pinch with the palm up (opens at once) or pinch and hold
    private val radialItems = listOf("Voltar", "Início", "Teclado", "Captura", "Voz", "Recentralizar", "Biblioteca", "Janelas", "Fita métrica")
    private val radials = arrayOf(com.hyperxr.input.RadialMenu(radialItems), com.hyperxr.input.RadialMenu(radialItems))
    private val radialTex = IntArray(radialItems.size)
    private val radialAspect = FloatArray(radialItems.size)
    private val wasPinching = BooleanArray(2)
    private val pinchPoint = Vec3()
    private val pinchBall = arrayOf(Vec3(), Vec3())
    private val ballPointers = arrayOf(com.hyperxr.input.BallPointer(), com.hyperxr.input.BallPointer())
    private val pinchBallVisible = BooleanArray(2)
    private val identityQ = Quat()
    private val MICRO_SCROLL_SCREENS = 1.5f
    private val microGestures = arrayOf(com.hyperxr.handtracking.MicroGestures(), com.hyperxr.handtracking.MicroGestures())
    private var gazeHit: Vec3? = null
    private var lastHapticTarget: Any? = null
    /** Drawing in the air (Personalização > Desenho no ar). */
    private val airDraw = AirDraw()
    private val airBusy = BooleanArray(2)

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
            val aim = p.aim
            if (aim != null) { ray.origin.set(aim.origin); ray.direction.set(aim.direction) }  // HyperConnect phone aims itself
            else {
                if (!shoulder) stickCursor.update(p.stickX, p.stickY, dt)
                stickCursor.ray(input.head, ray)
            }
            val buttons = p.buttons or (other?.buttons ?: 0)
            val aDown = buttons and 128 != 0
            // A on a lab object carries it; A on the UI clicks
            val l = lab
            val toLab = l != null && ctrlPointers[0].captured == null && (cursorLabDown || (ctrlPointers[0].hovered == null && l.cursorTargetsObject(ray)))
            cursorRay.origin.set(ray.origin); cursorRay.direction.set(ray.direction); cursorRayValid = true
            cursorLabDown = toLab && aDown
            ctrlPointers[0].update(ray, aDown && !toLab, surfaces, now)
            // a short buzz in the controller phone when the pointer reaches something new
            val hov = ctrlPointers[0].hovered
            if (hov != null && hov !== lastHapticTarget) p.haptics?.invoke(8)
            lastHapticTarget = hov
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
                // the pinch starting with the palm up opens the menu right away
                val palmUp = pinching && !wasPinching[i] && !linkDrag.active && lab?.handBusy?.get(i) != true &&
                    com.hyperxr.handtracking.PalmMenuDetector.isPalmUp(h.joints, h.handedness == com.hyperxr.handtracking.Handedness.RIGHT, input.head.position)
                // drawing in the air: a pinch away from the UI draws, so it must not open the radial menu
                if (airDraw.active) radials[i].update(false, pinchPoint, input.head, true, now)
                else radials[i].update(pinching, pinchPoint, input.head, overTarget, now, instant = palmUp)?.let { radialAction(it) }
                airBusy[i] = overTarget
            } else radials[i].update(false, pinchPoint, input.head, true, now)
            wasPinching[i] = h.isUsable && pinching
            val menuOpen = radials[i].phase != com.hyperxr.input.RadialMenu.Phase.IDLE && (!overTarget || radials[i].openedInstantly)
            // a hand working on the lab bench (holding or reaching an object) does not point at the UI
            val labBusy = lab?.handBusy?.get(i) == true
            // The pinch ball is the pointer: what is under the ball, as seen from the eyes, is what a
            // pinch clicks (no beam). The shoulder ray remains available in Settings.
            // Point and push (default): the index fingertip aims, a short push towards the target
            // clicks, holding the push drags. Pinch mode keeps the older pinch ball.
            val pointMode = app.settings.current.handTracking.clickGesture == com.hyperxr.settings.ClickGesture.POINT_PUSH
            val pushed = pointMode && pointPush[i].update(if (h.isUsable && !menuOpen && !labBusy) h.joints else null, input.head.position,
                h.depthSource == DepthSource.HAND_SIZE_PRIOR, now)
            if (h.isUsable) {
                if (pointMode) pinchBall[i].set(h.joints[HandJoint.INDEX_TIP])
                else pinchBall[i].set(h.joints[HandJoint.THUMB_TIP]).add(h.joints[HandJoint.INDEX_TIP]).scale(0.5f)
            }
            val raw = when {
                menuOpen || labBusy || !h.isUsable -> { ballPointers[i].reset(); null }
                pointMode -> if (pointPush[i].pointing) ballPointers[i].update(pinchBall[i], pushed, input.head.position, now, ray) else { ballPointers[i].reset(); null }
                app.settings.current.handTracking.ballPointer -> ballPointers[i].update(pinchBall[i], pinching, input.head.position, now, ray)
                else -> com.hyperxr.input.HandRay.compute(h, input.head, rightHand = i == 1, ray)
            }
            pinchBallVisible[i] = h.isUsable && !labBusy && (!pointMode || pointPush[i].pointing)
            val handRay = handStabilizers[i].update(raw, now, handRayOut[i])
            // a fist (no finger extended) grabs: it moves windows, pushing/pulling them in depth
            val fist = h.isUsable && (h.gesture == com.hyperxr.handtracking.Gesture.FIST || h.gesture == com.hyperxr.handtracking.Gesture.GRAB)
            if (!handRays[i].grabbing || handRays[i].captured == null) handRays[i].grabbing = fist
            // the click (push or pinch) is read from the held state so a lost frame does not release it
            val pinch = if (handRays[i].grabbing) fist || (raw == null && handRays[i].captured != null && handRay != null)
                else if (pointMode) pushed || (raw == null && handRays[i].captured != null && handRay != null && pointPush[i].pressed)
                else h.gesture == com.hyperxr.handtracking.Gesture.PINCH || (raw == null && handRays[i].captured != null && handRay != null)
            handRays[i].update(handRay, pinch, surfaces, now)
            if (handRay != null) recordPointer(5 + i, handRay, pinch) else pointerRays.remove(5 + i)
            handRayVisible[i] = handRay != null
            pinchStrength[i] = if (!h.isUsable) 0f else if (pointMode) pointPush[i].progress else h.pinchStrength
            pushPressed[i] = pointMode && pointPush[i].pressed
            if (handRay != null) { trackHit(handRays[i], handRay); dragWindows(handRays[i], handRay, pinch, if (h.isUsable && handRays[i].grabbing) pinchBall[i] else null) }
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
        if (keyboard != null || tableKeyboard != null) { closeKeyboard(); return }
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
            4 -> startVoice()
            5 -> ctx.requestRecenter()
            6 -> toggleLibrary()
            7 -> toggleWindows()
            8 -> handTools.toggleMeasuring()
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
                val (ox, oy) = m.itemOffset(k, radius = 0.11f)
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

    private val grabbedBars = HashMap<Int, XrPanel>()

    private fun dragWindows(p: RayPointer, r: Ray, down: Boolean, hand: Vec3? = null) {
        (p.captured as? com.hyperxr.appworld.WorldHit)?.let { appWorld.drag(it, r, down); return }
        // a fist on a window's content moves the window by its bar
        val w = p.captured as? SpatialWindow
        val bar = w?.let { windowControls[it] }
        grabbedBars[p.pointerId]?.let { held -> if (held !== bar || !down || !p.grabbing) { held.endGrab(); grabbedBars.remove(p.pointerId) } }
        if (w != null) {
            if (bar != null && down && p.grabbing) {
                if (grabbedBars[p.pointerId] == null) { bar.beginGrab(); grabbedBars[p.pointerId] = bar }
                bar.drag(r.origin, r.direction, ctx.input.head, hand)
            }
            return
        }
        val target = p.captured as? XrPanel ?: return
        if (down && target.isDragging) target.drag(r.origin, r.direction, ctx.input.head, hand)
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
            val pointMode = app.settings.current.handTracking.clickGesture == com.hyperxr.settings.ClickGesture.POINT_PUSH
            val pinched = handRays[i].captured != null || pushPressed[i] ||
                (!pointMode && ctx.input.hands[i].gesture == com.hyperxr.handtracking.Gesture.PINCH)
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
            "settings" -> SettingsHubPanel(this) { openSystem(it) }
            "library" -> { if (!libraryOpen()) toggleLibrary(); return }
            "environments" -> EnvironmentsPanel(this)
            "roomcapture" -> RoomCapturePanel(this, ctx.hands)
            "airdraw" -> { airDraw.active = true; AirDrawPanel(airDraw) { glTasks += { windows.removeAll { w -> (w is AirDrawPanel).also { if (it) w.release() } } } } }
            "files" -> com.hyperxr.files.FilesPanel(this)
            "gallery" -> com.hyperxr.files.GalleryPanel(this)
            "messages" -> MessagesPanel()
            "help" -> HelpPanel()
            "tv" -> TvPanel(this)
            "xrapps" -> XrAppsPanel(this, this)
            "androidapps" -> AndroidAppsPanel(activity) { pkg, comp, title -> openAndroidApp(pkg, comp, title) }
            "capture" -> { capture(); return }
            "handlab" -> { toggleLab(); return }
            "ocean" -> { toggleOcean(); return }
            in com.hyperxr.questgames.QuestGameCatalog.ids -> { startGame(target); return }
            "guardian" -> ctx.guardian?.let { com.hyperxr.guardian.GuardianPanel(it) } ?: InfoPanel("HyperGuardian", "Indisponível")
            "appworld" -> com.hyperxr.appworld.AppWorldPanel(appWorld, this)
            "workspaces" -> WorkspacesPanel(workspaces, this)
            "reality" -> RealityWindow(ctx)
            "share" -> com.hyperxr.share.SharePanel(ctx.share, activity)
            "importapk" -> com.hyperxr.files.ApkPickerPanel(this)
            "opengames" -> com.hyperxr.files.OpenGamesPanel(this) { url -> openBrowser(url, false) }
            "youtube" -> com.hyperxr.ytxr.YouTubePanel(this)
            "emulators" -> com.hyperxr.emulators.EmulatorPanel(this) { core, rom -> playRom(core, rom) }
            "assistant" -> com.hyperxr.assistant.AssistantPanel(assistant, { startVoice() }, { openKeyboard(it) }, { closeKeyboard() })
            "catgame" -> { ctx.ui { activity.startActivity(Intent(activity, com.hyperxr.catgame.CatGameActivity::class.java)) }; return }
            else -> InfoPanel("HyperXR", "Painel '$target' indisponível")
        }
        placeAboveDock(w, yawOffsetDeg = (windows.size % 3 - 1) * 8f)
        openWindow(w)
    }

    // Windows sit above the dock row: the bar's centre is BAR_Y below the eyes at WIN_DIST, so its
    // bottom edge stays above the dock icons; in New mode they are nearer than the floating icons.
    private val WIN_DIST get() = 1.45f * uiFar()
    private val BAR_Y get() = -0.17f * uiFar()

    /**
     * In mixed reality the UI sits further away (real furniture is close around you); in VR the
     * usual distance. The heights scale with it, so everything keeps its angle in the view.
     */
    private fun uiFar() = if (::ctx.isInitialized && !vrOn()) 1.35f else 1f

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

    /** Starts [rom] on [core]; its screen and hand controller open once the game is loaded. */
    private fun playRom(core: com.hyperxr.emulators.EmulatorCore, rom: File) {
        val s = com.hyperxr.emulators.EmulatorSession(activity, core, rom)
        s.start(
            onReady = {
                glTasks += {
                    // one game at a time: the previous one's window and pad go away
                    appWindows.filterIsInstance<com.hyperxr.emulators.EmulatorWindow>().forEach { closeWindow(it) }
                    val w = com.hyperxr.emulators.EmulatorWindow(s) { focused -> glTasks += { focusGame(focused) } }
                    openWindow(w)
                    val pad = com.hyperxr.emulators.EmulatorPad(w)
                    pad.placeInFront(ctx.input.head, 0.95f, yOffset = -0.48f)
                    pad.onCinema = {
                        if (isTheater(w)) exitTheater(w) else enterTheater(w)
                        pad.cinemaOn = isTheater(w); pad.dirty = true
                    }
                    windows += pad
                    focusGame(w)
                }
            },
            onError = { msg ->
                glTasks += {
                    val p = InfoPanel("Emuladores", msg)
                    placeAboveDock(p, 0f)
                    openWindow(p)
                }
            },
        )
    }

    /** Gives the controller to an emulator window (or back to HyperXR with null). */
    private fun focusGame(w: com.hyperxr.emulators.EmulatorWindow?) {
        (ctx.gameInput as? com.hyperxr.emulators.EmulatorWindow)?.takeIf { it !== w }?.setCaptured(false)
        ctx.gameInput = w
        w?.setCaptured(true)
    }

    override fun closeWindow(w: SpatialWindow) {
        if (!appWindows.contains(w)) return
        if (w is com.hyperxr.emulators.EmulatorWindow) windows.removeAll { p -> (p is com.hyperxr.emulators.EmulatorPad && p.window === w).also { if (it) p.release() } }
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
        if (tableKeyboardTarget === target && tableKeyboard != null) return
        k?.release()
        closeTableKeyboard()
        keyboard = KeyboardPanel(target, { cb -> dictate(cb) }, onTable = { glTasks += { openTableKeyboard(target) } }).also { kb ->
            // close and low, tilted up: reachable with the fingertip (Direct Touch) or a pinch
            kb.placeInFront(ctx.input.head, 0.72f, -0.32f)
            kb.pose.orientation.mul(Quat.fromAxisAngle(1f, 0f, 0f, -0.45f), kb.pose.orientation)
        }
    }

    override fun closeKeyboard() {
        keyboard?.let { k -> (k.target as? UrlEditor)?.let { if (it.active) it.cancel() }; k.release() }
        keyboard = null
        (tableKeyboardTarget as? UrlEditor)?.let { if (it.active) it.cancel() }
        closeTableKeyboard()
    }

    /**
     * HyperConnect: the controller phone's keyboard types into the open field (the floating or
     * table keyboard's target, else the front window); the phone is told when a field opens.
     */
    private fun deliverPhoneTyping() {
        val hc = ctx.hyperConnect ?: return
        val open = keyboard?.visible == true || tableKeyboard != null
        hc.setKeyboardOpen(open)
        val target: com.hyperxr.windows.KeyTarget? = keyboard?.target ?: tableKeyboardTarget ?: appWindows.lastOrNull { it.visible }
        while (true) {
            val m = hc.typing.poll() ?: break
            val t = target ?: continue
            when (m) {
                is com.hyperxr.connect.HyperConnectProtocol.Text -> t.typeText(m.text)
                is com.hyperxr.connect.HyperConnectProtocol.Key -> t.sendKey(m.keyCode)
            }
        }
    }

    // ---------------------------------------------------------------- keyboard on the table
    private var tableKeyboard: TableKeyboardView? = null
    private var tableKeyboardTarget: com.hyperxr.windows.KeyTarget? = null
    private var keyClickSound = 0

    /** Lays the keyboard on the real table for [target] (the floating one closes). */
    private fun openTableKeyboard(target: com.hyperxr.windows.KeyTarget) {
        keyboard?.release(); keyboard = null
        closeTableKeyboard()
        if (keyClickSound == 0) keyClickSound = runCatching { ctx.audio.synth("sine", 1900f, 1300f, 0.03f, 0.35f, 0.002f, 0.025f) }.getOrDefault(0)
        tableKeyboardTarget = target
        tableKeyboard = TableKeyboardView(
            target,
            ctx.hands,
            click = { if (keyClickSound != 0) runCatching { ctx.audio.play(keyClickSound, 0.5f, 1f, false, null, null) } },
            onClose = { glTasks += { closeKeyboard() } },
            onFloating = { glTasks += { closeTableKeyboard(); openKeyboard(target) } },
        )
    }

    private fun closeTableKeyboard() {
        tableKeyboard?.release()
        tableKeyboard = null; tableKeyboardTarget = null
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

    // ---------------------------------------------------------------- games
    private fun startGame(id: String) {
        game?.let { runCatching { it.release() } }
        game = null
        libraryShown = false
        try {
            game = com.hyperxr.questgames.GameHost(ctx, id) { glTasks += { game?.let { runCatching { it.release() } }; game = null } }.also { it.start(ctx.input.head) }
        } catch (t: Throwable) { gameFailed(t, id) }
    }

    private fun gameFailed(t: Throwable, id: String? = null) {
        val title = game?.game?.title ?: id ?: "jogo"
        Diagnostics.log("Game $title failed: $t")
        com.hyperxr.diagnostics.CrashReporter.record(activity, "jogo $title", t)
        game?.let { runCatching { it.release() } }
        game = null
        openWindow(InfoPanel("Erro no jogo", "$title teve um erro e foi fechado (o HyperXR continua). O relatório está nas configurações 2D: Ver relatório.").apply { placeInFront(ctx.input.head) })
    }

    // ---------------------------------------------------------------- Ocean
    private fun toggleOcean() {
        val o = ocean
        if (o != null) { o.release(); ocean = null; return }
        ocean = com.hyperxr.ocean.OceanApp(ctx) { glTasks += { ocean?.release(); ocean = null } }.also {
            it.initGl()
            it.place(ctx.input.head, anchorYaw)
        }
    }

    // ---------------------------------------------------------------- dock Home / window state
    override fun windowCount(): Int = appWindows.size
    override fun windowsMinimized(): Boolean = windowsMinimized
    override fun toggleWindows() { windowsMinimized = !windowsMinimized; if (windowsMinimized) closeKeyboard() }

    /** Places a system panel so its bottom edge stays above the dock row. */
    private fun placeAboveDock(p: XrPanel, yawOffsetDeg: Float, distance: Float = 1.35f * uiFar()) {
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
        restoreFrom(arr)
    }

    /** Reopens the windows described by [sessionJson] (last session or a saved workspace). */
    private fun restoreFrom(arr: org.json.JSONArray) {
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

    // ---------------------------------------------------------------- workspaces
    private val workspaces by lazy { Workspaces(app.filesDir) }

    override fun saveWorkspace(name: String): Boolean {
        if (appWindows.isEmpty()) return false
        val json = sessionJson()
        sessionIo.execute { runCatching { workspaces.save(name, json) } }
        return true
    }

    override fun openWorkspace(name: String) {
        val arr = runCatching { org.json.JSONArray(workspaces.load(name)) }.getOrNull() ?: return
        closeKeyboard()
        appWindows.toList().forEach { closeWindow(it) }
        windowsMinimized = false
        libraryShown = false
        restoreFrom(arr)
    }

    // ---------------------------------------------------------------- voice commands
    @Volatile private var voiceText = ""
    @Volatile private var voiceUntil = 0L
    @Volatile private var listening = false

    override fun voiceStatus(): String {
        followAssistant()
        if (listening || System.currentTimeMillis() < voiceUntil) return voiceText
        // otherwise the taskbar shows what is being shared ("Gravando 0:12")
        return if (::ctx.isInitialized) ctx.share.summary else ""
    }

    private fun voiceSay(text: String) { voiceText = text; voiceUntil = System.currentTimeMillis() + 4000 }

    override fun startVoice() {
        if (listening) return
        listening = true; voiceText = "Ouvindo…"
        dictate { heard ->
            listening = false
            if (heard.isNullOrBlank()) { voiceSay("Não ouvi nada (permita o microfone)"); return@dictate }
            voiceSay(heard)
            glTasks += { assistant.ask(heard) }
        }
    }

    // ---------------------------------------------------------------- assistant
    private var assistantSeen = -1
    private val assistant by lazy {
        assistantMade = true
        com.hyperxr.assistant.Assistant(activity, object : com.hyperxr.assistant.AssistantHost {
            override fun snapshot() = runtimeSnapshot()
            override fun run(cmd: com.hyperxr.voice.VoiceCommand) { glTasks += { runVoice(cmd) } }
        })
    }

    /** The taskbar shows "Pensando…" and then the assistant's answer for a few seconds. */
    private fun followAssistant() {
        val a = assistantOrNull ?: return
        if (a.busy) { voiceText = a.status; voiceUntil = System.currentTimeMillis() + 1000; return }
        if (a.version != assistantSeen) {
            assistantSeen = a.version
            synchronized(a.messages) { a.messages.lastOrNull() }?.takeIf { !it.fromUser }?.let { voiceText = it.text; voiceUntil = System.currentTimeMillis() + 6000 }
        }
    }
    private val assistantOrNull get() = if (assistantMade) assistant else null
    private var assistantMade = false

    private fun runtimeSnapshot(): com.hyperxr.assistant.RuntimeSnapshot {
        val s = app.settings.current
        val named = { e: AppEntry -> com.hyperxr.voice.VoiceCommands.Named(e.id, e.title) }
        return com.hyperxr.assistant.RuntimeSnapshot(
            xrMode = modeDescription, tracking = trackingDescription, hands = handStatus,
            background = when (backgroundChoice()) { "camera" -> "câmera"; "dim" -> "câmera escurecida"; else -> app.environments.installed().firstOrNull { it.id == currentEnvId }?.name ?: currentEnvId },
            launcherMode = if (mode() == LauncherMode.DOCK) "dock" else "new",
            battery = batteryText(),
            windows = appWindows.map { w -> if (w is com.hyperxr.browser.BrowserWindow) "${w.title} (${w.tabs.size} abas)" else w.title },
            windowsHidden = windowsMinimized,
            apps = (pinnedApps() + allApps()).filter { it.kind != AppKind.UNAVAILABLE }.distinct().map(named),
            environments = app.environments.installed().map { com.hyperxr.voice.VoiceCommands.Named(it.id, it.name) },
            workspaces = workspaces.list(),
            joyCons = runCatching { com.hyperxr.joycon.JoyConManager.connectedJoyCons() }.getOrDefault(emptyList()),
            settings = linkedMapOf(
                "hand_tracking" to if (s.handTracking.enabled) "sim" else "nao",
                "toque_direto" to if (s.handTracking.directTouch) "sim" else "nao",
                "fps" to s.graphics.targetFps.toString(),
                "escala_render" to "%.2f".format(java.util.Locale.ROOT, s.graphics.renderScale),
                "ipd" to s.graphics.ipdMm.toInt().toString(),
                "fov" to s.graphics.fovDegrees.toInt().toString(),
                "volume" to (s.audio.masterVolume * 100).toInt().toString(),
            ),
            time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
        )
    }

    private fun applySetting(key: String, value: String) {
        val on = value == "sim"
        val n = value.toFloatOrNull() ?: 0f
        app.settings.update { s ->
            when (key) {
                "hand_tracking" -> s.copy(handTracking = s.handTracking.copy(enabled = on))
                "toque_direto" -> s.copy(handTracking = s.handTracking.copy(directTouch = on))
                "fps" -> s.copy(graphics = s.graphics.copy(targetFps = n.toInt()))
                "escala_render" -> s.copy(graphics = s.graphics.copy(renderScale = n))
                "ipd" -> s.copy(graphics = s.graphics.copy(ipdMm = n))
                "fov" -> s.copy(graphics = s.graphics.copy(fovDegrees = n))
                "volume" -> s.copy(audio = s.audio.copy(masterVolume = n / 100f))
                else -> s
            }
        }
    }

    private fun runVoice(cmd: com.hyperxr.voice.VoiceCommand) {
        when (cmd) {
            is com.hyperxr.voice.VoiceCommand.OpenApp -> allApps().firstOrNull { it.id == cmd.appId }?.let { launch(it) }
            is com.hyperxr.voice.VoiceCommand.Search -> openBrowser(com.hyperxr.browser.UrlInput.resolve(cmd.query), app.settings.current.network.webAppsDesktopMode)
            is com.hyperxr.voice.VoiceCommand.Background -> setBackgroundChoice(cmd.choice)
            is com.hyperxr.voice.VoiceCommand.SaveWorkspace -> if (!saveWorkspace(cmd.name)) voiceSay("Nenhuma janela aberta para salvar")
            is com.hyperxr.voice.VoiceCommand.OpenWorkspace -> if (workspaces.load(cmd.name) != null) openWorkspace(cmd.name) else voiceSay("Espaço \"${cmd.name}\" não existe")
            is com.hyperxr.voice.VoiceCommand.SetMode -> setMode(if (cmd.dock) LauncherMode.DOCK else LauncherMode.NEW)
            com.hyperxr.voice.VoiceCommand.Recenter -> recenter()
            com.hyperxr.voice.VoiceCommand.Capture -> capture()
            com.hyperxr.voice.VoiceCommand.CloseWindows -> pendingClose += appWindows
            com.hyperxr.voice.VoiceCommand.HideWindows -> { windowsMinimized = true; closeKeyboard() }
            com.hyperxr.voice.VoiceCommand.ShowWindows -> { windowsMinimized = false; libraryShown = false }
            com.hyperxr.voice.VoiceCommand.Library -> toggleLibrary()
            com.hyperxr.voice.VoiceCommand.RealityWindow -> openSystem("reality")
            is com.hyperxr.voice.VoiceCommand.Setting -> applySetting(cmd.key, cmd.value)
            is com.hyperxr.voice.VoiceCommand.Record -> if (cmd.start) ctx.share.startRecording() else ctx.share.stopRecording()
            is com.hyperxr.voice.VoiceCommand.Unknown -> Unit
        }
        taskbar.dirty = true
    }

    /** Control bar under a window: title + status, back/reload/keyboard/curve/size/external/close. */
    private inner class WindowControls(val w: SpatialWindow) : XrPanel(1.4f, 0.2f, title = w.title) {
        init { closable = false }
        private var lastStatus: String? = null
        override fun update(dt: Float) { if (w.status != lastStatus) { lastStatus = w.status; dirty = true } }
        override fun draw(c: com.hyperxr.spatialui.PanelCanvas) {
            w.status?.let { c.label(it, 420f, 34f, 24f, com.hyperxr.spatialui.XrTheme.accent2, maxWidth = c.w - 560f) }
            val n = (if (w.supportsReload) 8 else 7) + (if ((w as? com.hyperxr.emulators.EmulatorWindow)?.wantsKeyboard == true) 1 else 0)
            val bw = (c.w - 2 * c.pad - (n - 1) * 10f) / n
            var x = c.pad
            val y = c.y
            fun b(id: String, label: String, accent: Boolean = false, act: () -> Unit) { c.button(id, label, x, y, bw, 70f, accent = accent) { act() }; x += bw + 10f }
            val game = w is com.hyperxr.emulators.EmulatorWindow
            b("back", if (game) "Soltar controle" else "Voltar") { w.back() }
            if (w.supportsReload) b("reload", if (game) "Reiniciar" else "Recarregar") { w.reload() }
            if (w is com.hyperxr.emulators.EmulatorWindow) {
                if (w.wantsKeyboard) b("kbd", "Teclado") { openKeyboard(w) }
                b("save", "Salvar estado") { w.session.saveState { ok -> w.status = if (ok) "Estado salvo" else "Este núcleo não salvou o estado" } }
                b("load", "Carregar estado") { w.session.loadState { ok -> w.status = if (ok) "Estado carregado" else "Nenhum estado salvo" } }
            } else b("kbd", "Teclado") { openKeyboard(w) }
            b("curve", if (w.curved) "Plano" else "Curvar") { w.curved = !w.curved; dirty = true }
            b("smaller", "Menor") { resize(w, 1f / 1.15f) }
            b("bigger", "Maior") { resize(w, 1.15f) }
            if (w !is com.hyperxr.emulators.EmulatorWindow) b("ext", if (w is WebWindow) "Externo" else "Tela 2D") { w.openExternally() }
            b("close", "Fechar", accent = true) { pendingClose += w }
        }
    }
    private val pendingClose = ArrayList<SpatialWindow>()

    // ---------------------------------------------------------------- home UI (library + taskbar)
    override fun backgroundOptions(): List<Pair<String, String>> = buildList {
        if (::ctx.isInitialized && ctx.passthroughAvailable) { add("camera" to "Câmera"); add("dim" to "Escurecer MR") }
        app.environments.installed().take(5).forEach { add(it.id to it.name.take(14)) }
    }

    override fun backgroundChoice(): String = background.ifEmpty { if (ctx.passthroughAvailable) "camera" else currentEnvId }

    override fun vrOn(): Boolean = backgroundChoice().let { it != "camera" && it != "dim" }
    override fun vrSwitchable(): Boolean = ::ctx.isInitialized && ctx.passthroughAvailable
    override fun setVr(on: Boolean) {
        if (on) { app.environments.installed().firstOrNull { it.id == currentEnvId }?.let { selectEnvironment(it) } ?: run { background = currentEnvId } }
        else if (vrSwitchable()) background = "camera"
    }

    override fun setBackgroundChoice(id: String) {
        background = id
        if (id != "camera" && id != "dim") app.environments.installed().firstOrNull { it.id == id }?.let { loadEnvironment(it) }
    }

    /** Camera (optionally darkened behind the UI) or the chosen 3D environment. */
    private var lastVr: Boolean? = null
    private fun applyBackground() {
        // switching between MR and VR: the UI moves to that mode's distance
        val vr = vrOn()
        if (lastVr != null && lastVr != vr) anchored = false
        lastVr = vr
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
        // light 3D objects with the colour of the place around them
        val avg = if (entry.kind == com.hyperxr.environments.EnvironmentKind.PANORAMA_360) app.environments.ambientColors[entry.id] else null
        val light = avg?.let { com.hyperxr.environments.AmbientMatch.lightFor(it[0], it[1], it[2]) } ?: com.hyperxr.environments.AmbientMatch.STUDIO
        light.ambient.copyInto(ctx.scene.ambient)
        light.lightColor.copyInto(ctx.scene.lightColor)
    }

    private var handToolsOrNull: HandTools? = null
    /** Palm menu and measuring tape (hands). */
    private val handTools: HandTools get() = handToolsOrNull ?: HandTools(ctx, object : HandTools.Actions {
        override fun voice() = startVoice()
        override fun recenter() = ctx.requestRecenter()
        override fun capture() = this@SpatialLauncher.capture()
        override fun library() = toggleLibrary()
        override fun windows() = toggleWindows()
    }).also { handToolsOrNull = it }

    private var lastClockCheck = 0.0

    /** With "ambiente pelo horário" on, switches the built-in 360 environment as the day goes by. */
    private fun followClock() {
        if (ctx.timeSeconds - lastClockCheck < 30.0) return
        lastClockCheck = ctx.timeSeconds
        if (!app.settings.current.launcher.autoEnvironmentByTime) return
        val choice = backgroundChoice()
        if (choice == "camera" || choice == "dim") return
        val now = java.util.Calendar.getInstance()
        val id = com.hyperxr.environments.AmbientMatch.forTime(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
        if (id == currentEnvId) return
        app.environments.installed().firstOrNull { it.id == id }?.let { background = id; loadEnvironment(it) }
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
