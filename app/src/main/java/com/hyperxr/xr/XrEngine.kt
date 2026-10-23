package com.hyperxr.xr

import android.app.Activity
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.KeyEvent
import android.view.MotionEvent
import com.hyperxr.HyperXRApp
import com.hyperxr.audio.AudioEngine
import com.hyperxr.capabilities.XrMode
import com.hyperxr.capabilities.XrModeDecision
import com.hyperxr.capabilities.XrModeResolver
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.Stage
import com.hyperxr.handtracking.HandState
import com.hyperxr.handtracking.HandTrackingService
import com.hyperxr.handtracking.Handedness
import com.hyperxr.headtracking.HeadTracker
import com.hyperxr.headtracking.RotationVectorTracker
import com.hyperxr.input.ControllerPoseFusion
import com.hyperxr.joycon.JoyConManager
import com.hyperxr.joycon.JoyConSide
import com.hyperxr.math.Mat4
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import com.hyperxr.mixedreality.ArCoreSession
import com.hyperxr.mixedreality.CameraPassthrough
import com.hyperxr.openxr.OpenXrNative
import com.hyperxr.rendering.Background
import com.hyperxr.rendering.CameraImageSource
import com.hyperxr.rendering.EyeView
import com.hyperxr.rendering.FrameScene
import com.hyperxr.rendering.IconFactory
import com.hyperxr.rendering.ImageStore
import com.hyperxr.rendering.RenderTarget
import com.hyperxr.rendering.SceneRenderer
import com.hyperxr.rendering.StereoCompositor
import com.hyperxr.settings.ControllerAction
import com.hyperxr.settings.HyperSettings
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Per-controller state handed to content each frame (tracking space). */
class ControllerState {
    var connected = false
    val pose = Pose()
    var buttons = 0
    var stickX = 0f
    var stickY = 0f
    var positionFromHand = false
    var stick2X = 0f
    var stick2Y = 0f
    /** Set when the controller aims by itself (HyperConnect phone): the pointer follows this ray. */
    var aim: Ray? = null
    /** Buzz the controller (milliseconds), when it can. */
    var haptics: ((Int) -> Unit)? = null
    fun ray(out: Ray): Ray { out.origin.set(pose.position); pose.orientation.rotate(Pose.FORWARD, out.direction); return out }
}

/** Everything content needs to know about the user this frame. */
class XrInput {
    val head = Pose()
    val hands = arrayOf(HandState(Handedness.LEFT), HandState(Handedness.RIGHT))
    val controllers = arrayOf(ControllerState(), ControllerState())
    var tapDown = false
    var handsAvailable = false
    /** Set by recenter: content puts the Joy-Con stick cursor back in the middle of the view. */
    var recenterCursor = false
    fun gazeRay(out: Ray): Ray { out.origin.set(head.position); head.orientation.rotate(Pose.FORWARD, out.direction); return out }
}

/** Shared services exposed to content (GL thread). */
class XrContext(
    val activity: Activity,
    val settings: HyperSettings,
    val decision: XrModeDecision,
    val renderer: SceneRenderer,
    val images: ImageStore,
    val icons: IconFactory,
    val audio: AudioEngine,
    val scene: FrameScene,
    val input: XrInput,
) {
    var timeSeconds = 0.0
    var frameIndex = 0L
    val passthroughAvailable: Boolean get() = decision.mode == XrMode.MIXED_REALITY_ARCORE || decision.mode == XrMode.MIXED_REALITY_CAMERA
    /** Run on the UI thread (start activities, web views...). */
    fun ui(block: () -> Unit) = activity.runOnUiThread(block)
    var requestRecenter: () -> Unit = {}
    /** Set by content; the engine fills it with the 16:9 spectator image of the next frame. */
    @Volatile var captureRequest: ((android.graphics.Bitmap) -> Unit)? = null
    var trackingDescription = ""
    /** Live hand-tracking status for in-XR diagnostics. */
    var handStatus: () -> String = { "" }
    /** The camera image when one is running (MR, or VR with hand tracking), for content that shows it. */
    var camera: com.hyperxr.rendering.CameraImageSource? = null
    /** 16:9 screen sharing (recording, external displays, browser stream). */
    lateinit var share: com.hyperxr.share.ScreenShare
    /** While set (an emulator window has focus), controller keys and sticks go to it first. */
    @Volatile var gameInput: GameInput? = null
    /** The hand tracking service (AI depth for table typing is switched on through it). */
    var hands: com.hyperxr.handtracking.HandTrackingService? = null
    /** HyperConnect (controller phone over Bluetooth). */
    var hyperConnect: com.hyperxr.connect.HyperConnectServer? = null
}

/** Takes raw controller/keyboard input (emulators); return false to let HyperXR handle the event. */
interface GameInput {
    fun onKey(e: KeyEvent): Boolean
    fun onMotion(e: MotionEvent): Boolean
}

/**
 * Content that fills the whole phone screen itself, lens correction included, in one pass (SteamVR
 * streaming on weak phones): the engine then skips the per-eye targets and the composite. The
 * eyes are set up (pose and frustum) before the call. Return false to render normally.
 */
interface ScreenOverride {
    fun drawScreen(eyes: Array<EyeView>, screenW: Int, screenH: Int, xdpi: Float, g: com.hyperxr.settings.GraphicsSettings): Boolean
}

/** Content that draws an eye itself (SteamVR streaming): return false to render the scene normally. */
interface EyeOverride {
    fun renderEye(eye: EyeView, target: RenderTarget): Boolean
}

interface XrContent {
    fun onGlInit(ctx: XrContext)
    /** Fill ctx.scene for this frame. [dt] in seconds. */
    fun onFrame(ctx: XrContext, dt: Float)
    fun onAction(ctx: XrContext, action: ControllerAction, pressed: Boolean, side: JoyConSide?) {}
    fun onPause() {}
    fun onResume() {}
    fun onGlRelease() {}
}

/**
 * The immersive renderer used by both the spatial launcher and the game runtime.
 * Frame: camera/AR update -> head pose -> hands/controllers -> content -> per-eye render
 * (background, scene, panels, hand aura) -> lens distortion composite or OpenXR submit.
 */
/** The boot animation played in this process already (not again when returning from an app). */
@Volatile private var bootPlayed = false

/** How old the camera frame on screen is when drawn (the passthrough texture lags the sensor). */
private const val DISPLAYED_CAMERA_AGE_NS = 30_000_000L

class XrEngine(
    private val activity: Activity,
    private val content: XrContent,
    /** Lets an activity run with adjusted settings without saving them (SteamVR performance profiles). */
    tweak: (com.hyperxr.settings.HyperSettings) -> com.hyperxr.settings.HyperSettings = { it },
) : GLSurfaceView.Renderer {
    private val app = HyperXRApp.instance
    private var settings = tweak(app.settings.current)
    val decision: XrModeDecision = XrModeResolver.resolve(app.capabilities.last.takeIf { it.androidSdk > 0 } ?: app.capabilities.probe(app.runtimeBroker), settings.xrMode)

    private val renderer = SceneRenderer()
    private val aura = com.hyperxr.rendering.HandAura()
    private val compositor = StereoCompositor()
    private val images = ImageStore()
    private val icons = IconFactory(activity)
    private val audio = AudioEngine(activity)
    private val scene = FrameScene()
    private val input = XrInput()
    lateinit var ctx: XrContext; private set

    private var arCore: ArCoreSession? = null
    private var passthrough: CameraPassthrough? = null
    private var tracker: HeadTracker? = null
    val hands = HandTrackingService(activity)
    val joyCons = JoyConManager(activity)
    private val fusions = arrayOf(ControllerPoseFusion(), ControllerPoseFusion())

    private var openXrSession = 0L
    private val xrViews = FloatArray(28)
    private val xrTargets = HashMap<Int, RenderTarget>()

    private val eyes = arrayOf(EyeView(), EyeView())
    /** Full-rate gyroscope shared by the head trackers (rotation latency like a standalone headset). */
    private val gyro = com.hyperxr.headtracking.GyroSource(activity)
    private fun gyroHistory() = if (gyro.available) gyro.history else null
    /** Fence of the previous frame: the CPU waits for it so it never runs a frame ahead of the GPU. */
    private var frameFence = 0L
    private var refreshPeriodNs = 16_666_667L
    /** Prediction target locked to the display's vsync (Choreographer on the UI thread). */
    private val photonClock = com.hyperxr.headtracking.PhotonClock()
    @Volatile private var vsyncRunning = false
    private val vsyncCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            photonClock.onVsync(frameTimeNanos)
            if (vsyncRunning) android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private var latchNs = 0L
    private var cpuDoneNs = 0L
    private var useVisualGyro = false
    val share = com.hyperxr.share.ScreenShare(activity)
    private val spectatorEye = EyeView()
    private var lastFrameNs = 0L
    private var width = 0
    private var height = 0
    private val tap = com.hyperxr.input.TapLatch()
    @Volatile var exitRequested = false
    private val pendingActions = java.util.concurrent.ConcurrentLinkedQueue<Triple<ControllerAction, Boolean, JoyConSide?>>()

    fun onCreate() {
        joyCons.settings = settings.controllers
        joyCons.onAction = { side, ev -> pendingActions += Triple(ev.action, ev.pressed, side) }
        joyCons.start()
        gyro.start()
        share.displays.start()
        share.externalRecordingDetector.start()
        hands.configure(settings.handTracking, settings.performance.handInferenceHz)
        hands.onPalmMeasured = { palm ->
            app.settings.update { it.copy(handTracking = it.handTracking.copy(calibratedPalmMm = palm * 1000f)) }
        }
        if (settings.performance.sustainedPerformanceMode) {
            activity.window.setSustainedPerformanceMode(true)
        }
        Diagnostics.log("XR start: ${decision.mode} (${decision.headTracking}); ${decision.reasons.joinToString("; ")}")
    }

    // ---------------------------------------------------------------- GL lifecycle
    private val boot = com.hyperxr.rendering.BootSequence(activity)
    private var bootActive = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        com.hyperxr.rendering.FullscreenTriangle.reset()
        renderer.init()
        // the boot plays once per start of the app, not when coming back from another app
        if (settings.graphics.bootAnimation && !bootPlayed) {
            try { boot.init(); bootActive = true; bootPlayed = true } catch (t: Throwable) { Diagnostics.log("Boot animation off: ${t.message}") }
        }
        renderer.passthroughFill = settings.graphics.passthroughFill
        aura.init()
        compositor.init()
        aura.opacity = settings.handTracking.auraOpacity
        ctx = XrContext(activity, settings, decision, renderer, images, icons, audio, scene, input)
        ctx.share = share
        ctx.hands = hands
        ctx.hyperConnect = hyperConnect
        ctx.requestRecenter = { tracker?.recenter(); recenterControllers() }
        ctx.handStatus = {
            if (!input.handsAvailable) "Mãos: desligadas (sem câmera, sem permissão ou hand tracking desativado)"
            else "Mãos: ${hands.status} (${hands.backendInUse}) · ${hands.stats}"
        }

        // the computational gyroscope: chosen, or automatic on phones without a gyroscope
        useVisualGyro = settings.headTracking.source == com.hyperxr.settings.HeadTrackingSource.VISUAL_GYRO ||
            (settings.headTracking.source == com.hyperxr.settings.HeadTrackingSource.AUTO && !com.hyperxr.headtracking.VisualGyroTracker.hasGyroscope(activity))
        val forced3Dof = settings.headTracking.source == com.hyperxr.settings.HeadTrackingSource.ROTATION_VECTOR_3DOF || useVisualGyro
        when (decision.mode) {
            XrMode.MIXED_REALITY_ARCORE -> if (forced3Dof) startCameraPassthrough() else {
                val ar = ArCoreSession(activity).also { it.gyro = gyroHistory(); it.displayRotation = displayRotation() }
                val depth = ar.create(settings.handTracking.useArCoreDepth, settings.handTracking.prefer60FpsOverDepth)
                app.capabilities.updateDepthSupport(depth)
                if (ar.session != null) {
                    arCore = ar; tracker = ar
                    ar.start()
                } else {
                    Diagnostics.log("ARCore unavailable at runtime (${ar.error}); falling back to camera + 3DoF")
                    startCameraPassthrough()
                }
            }
            XrMode.MIXED_REALITY_CAMERA -> startCameraPassthrough()
            XrMode.OPENXR_RUNTIME -> {
                // The loader would pick HyperXR's own runtime when no other is active (HyperXR is the
                // system broker): rendering HyperXR through itself gains nothing, so the built-in
                // renderer is used instead.
                val active = app.runtimeBroker.discover().firstOrNull { it.source == com.hyperxr.runtimebroker.RuntimeSource.BROKER_ACTIVE }
                val selfOnly = active == null || active.packageName == activity.packageName
                if (selfOnly) Diagnostics.log("OpenXR mode: no external runtime active; using the built-in renderer")
                if (settings.runtime.allowOpenXrSession && !selfOnly) {
                    val err = arrayOfNulls<String>(1)
                    openXrSession = OpenXrNative.nativeCreateSession(activity, err)
                    if (openXrSession == 0L) Diagnostics.log("OpenXR session failed: ${err[0]}; using VR SBS")
                }
                if (openXrSession == 0L) tracker = RotationVectorTracker(activity, displayRotation(), gyroHistory()).also { it.start() }
            }
            XrMode.VR_SBS -> {
                // VR shows no camera image, but the camera still feeds hand tracking when allowed.
                val caps = app.capabilities.last
                val cameraOk = caps.cameraPermission && caps.hasBackCamera
                // The boundary warning needs depth: ARCore then also provides 6DoF and hand frames.
                if (cameraOk && settings.graphics.vrBoundary && caps.arCore.ok && !forced3Dof) {
                    val ar = ArCoreSession(activity).also { it.gyro = gyroHistory(); it.displayRotation = displayRotation() }.also { it.gyro = gyroHistory(); it.displayRotation = displayRotation() }
                    ar.create(useDepth = true)
                    if (ar.session != null && ar.depthEnabled) {
                        arCore = ar; tracker = ar; ar.start()
                    } else {
                        ar.close()
                        Diagnostics.log("VR boundary unavailable: ARCore Depth not supported here")
                    }
                }
                if (arCore == null) {
                    if ((settings.handTracking.enabled || useVisualGyro) && cameraOk) startCameraPassthrough()
                    else tracker = RotationVectorTracker(activity, displayRotation(), gyroHistory()).also { it.start() }
                }
            }
        }
        if (settings.handTracking.enabled && (arCore != null || passthrough != null)) {
            input.handsAvailable = hands.start(settings.handTracking.backend)
        }
        ctx.trackingDescription = when {
            openXrSession != 0L -> "OpenXR runtime"
            else -> tracker?.description ?: "none"
        }
        content.onGlInit(ctx)
    }

    private fun startCameraPassthrough() {
        val cam = CameraPassthrough(activity)
        cam.displayRotation = displayRotation()
        val visual = if (useVisualGyro) com.hyperxr.headtracking.VisualGyroTracker(activity, displayRotation()) else null
        cam.onCpuFrame = { img, intr ->
            // the computational gyroscope reads the frame first (hand tracking closes it)
            visual?.submit(img, intr)
            val camPose = Pose().also { p -> synchronized(input) { p.set(input.head) } }
            hands.submit(img, intr, camPose, null, rotation180 = displayRotation() == android.view.Surface.ROTATION_270)
        }
        cam.start()
        passthrough = cam
        tracker = (visual ?: RotationVectorTracker(activity, displayRotation(), gyroHistory())).also { it.start() }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = activity.windowManager.defaultDisplay.rotation

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        @Suppress("DEPRECATION")
        val hz = activity.windowManager.defaultDisplay.refreshRate
        if (hz > 20f) refreshPeriodNs = (1e9f / hz).toLong()
        photonClock.periodNs = refreshPeriodNs
        compositor.resize(w, h, activity.resources.displayMetrics.xdpi, settings.graphics)
        arCore?.setDisplayGeometry(displayRotation(), w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStart = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 1f / 60 else ((frameStart - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
        if (lastFrameNs != 0L) Diagnostics.record(Stage.FRAME_INTERVAL, frameStart - lastFrameNs)
        lastFrameNs = frameStart
        ctx.timeSeconds += dt
        ctx.frameIndex++

        // 1. camera + head pose
        arCore?.let { ar ->
            ar.update()
            submitArCoreHands(ar)
        }
        passthrough?.update()
        // Frame pacing: wait for the GPU to finish the previous frame before sampling anything,
        // so this frame's poses are as fresh as possible (no frame queued behind another).
        if (frameFence != 0L) {
            // how long the last frame took from its pose latch to the GPU finishing it: when the
            // fence is already signalled the moment is unknown, so the CPU end is the estimate
            val status = IntArray(1)
            GLES30.glGetSynciv(frameFence, GLES30.GL_SYNC_STATUS, 1, null, 0, status, 0)
            val waited = status[0] != GLES30.GL_SIGNALED
            GLES30.glClientWaitSync(frameFence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 20_000_000L)
            GLES30.glDeleteSync(frameFence); frameFence = 0L
            if (latchNs != 0L) photonClock.onRendered((if (waited) System.nanoTime() else cpuDoneNs) - latchNs)
        }
        val predictionNs = predictionNs()
        synchronized(input) { tracker?.pose(predictionNs, input.head) }

        var xrFrame = 0
        if (openXrSession != 0L) {
            xrFrame = OpenXrNative.nativeBeginFrame(openXrSession, xrViews)
            if (xrFrame == -1) { exitRequested = true; activity.runOnUiThread { activity.finish() }; return }
            if (xrFrame == 1) {
                // head = midpoint of the two eye poses
                input.head.position.set((xrViews[0] + xrViews[14]) / 2, (xrViews[1] + xrViews[15]) / 2, (xrViews[2] + xrViews[16]) / 2)
                input.head.orientation.set(xrViews[3], xrViews[4], xrViews[5], xrViews[6])
            }
        }

        // 2. hands + controllers
        hands.snapshot(input.hands[0], input.hands[1])
        updateControllers(dt)
        updatePhoneController()
        input.tapDown = tap.next()
        while (true) {
            val a = pendingActions.poll() ?: break
            if (a.first == ControllerAction.RECENTER && a.second) ctx.requestRecenter()
            content.onAction(ctx, a.first, a.second, a.third)
        }

        // 3. content
        scene.reset()
        ctx.camera = arCore ?: passthrough
        val sim0 = System.nanoTime()
        content.onFrame(ctx, dt)
        Diagnostics.record(Stage.SIMULATION, System.nanoTime() - sim0)
        if (!ctx.passthroughAvailable && scene.background == Background.PASSTHROUGH) scene.background = Background.ENVIRONMENT

        updateBoundary(dt)
        updateHandMask()


        // 4. render
        val r0 = System.nanoTime()
        // late latching: the head is sampled again right before drawing, so the content's own
        // work (UI, physics, hands) does not add to what the eyes see as rotation latency
        if (openXrSession == 0L) synchronized(input) {
            latchNs = System.nanoTime()
            photonNs = latchNs + predictionNs()
            tracker?.pose(predictionNs(), input.head)
            renderedOrientation.set(input.head.orientation)
        }
        // the hands drawn this frame are carried forward again to right now (fresher than the copy
        // taken before the content ran, which interaction keeps using)
        if (input.handsAvailable) hands.snapshot(drawnHands[0], drawnHands[1])
        if (bootActive) {
            val handsReady = !input.handsAvailable || hands.backendInUse != "-"
            boot.readiness = if (handsReady && ctx.frameIndex > 30) 1f else 0.4f
            boot.frame(input.head)
            if (boot.finished) { bootActive = false; boot.release() }
        }
        renderer.renderShadows(scene)
        val camera: CameraImageSource? = arCore ?: passthrough
        // 16:9 spectator view (recording, screenshots, external screens, stream), rendered only
        // while something uses it
        val capture = ctx.captureRequest
        val spectator = share.wanted(capture != null)
        if (spectator) {
            share.follow(input.head, dt, overCamera = scene.background == Background.PASSTHROUGH && ctx.passthroughAvailable)
            setupSpectator(camera)
            renderEye(spectatorEye, share.target(), camera)
            share.deliver()
        }
        if (openXrSession != 0L) {
            if (xrFrame == 1) {
                for (eye in 0..1) {
                    val o = eye * 14
                    val tex = xrViews[o + 11].toInt()
                    val w = xrViews[o + 12].toInt(); val h = xrViews[o + 13].toInt()
                    val target = xrTargets.getOrPut(tex) { RenderTarget(w, h, depth = true, externalTexture = tex) }
                    eyes[eye].index = eye
                    setupEyeFromXr(eyes[eye], o)
                    renderEye(eyes[eye], target, camera)
                }
            }
            OpenXrNative.nativeEndFrame(openXrSession, xrFrame == 1)
        } else {
            // Over a mono camera image, per-eye offsets would make virtual hands/panels double up
            // against the real ones; MR renders both eyes from the camera viewpoint unless asked.
            val monoMr = scene.background == com.hyperxr.rendering.Background.PASSTHROUGH && ctx.passthroughAvailable && !settings.graphics.stereoInMixedReality
            val ipd = if (monoMr) 0f else settings.graphics.ipdMm / 1000f
            for (eye in 0..1) { eyes[eye].index = eye; setupEye(eyes[eye], if (eye == 0) -ipd / 2 else ipd / 2) }
            val direct = (content as? ScreenOverride)?.drawScreen(eyes, width, height, activity.resources.displayMetrics.xdpi, settings.graphics) == true
            if (!direct) {
                for (eye in 0..1) renderEye(eyes[eye], compositor.eyeTarget(eye), camera)
                timewarp()
                compositor.composite(settings.graphics)
            }
        }
        if (capture != null) {
            ctx.captureRequest = null
            val bmp = if (spectator) share.snapshot() else null
            capture(bmp ?: readLeftHalf())
        }
        Diagnostics.record(Stage.RENDER_CPU, System.nanoTime() - r0)
        Diagnostics.record(Stage.INPUT_TO_PHOTON, System.nanoTime() - frameStart)
        frameFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        cpuDoneNs = System.nanoTime()
        reportFrameWork(System.nanoTime() - frameStart)
    }

    private var photonNs = 0L
    private val renderedOrientation = com.hyperxr.math.Quat()
    private val warpPose = Pose()

    /**
     * Rotational timewarp: the gyroscope is read once more after all eyes are drawn, predicted to
     * the same photon time, and the composite re-aims the image by the turn since rendering. What
     * the eyes see as rotation latency is then only the composite pass plus scan-out.
     */
    private fun timewarp() {
        val t = tracker
        if (t == null || !settings.graphics.timewarp) { com.hyperxr.math.Timewarp.identity(compositor.warp); return }
        val left = (photonNs - System.nanoTime()).coerceAtLeast(0L)
        synchronized(input) { t.pose(left, warpPose) }
        com.hyperxr.math.Timewarp.matrix(renderedOrientation, warpPose.orientation, compositor.warp)
    }

    /** Reads the left half of the composited screen (what the left eye sees) into a bitmap. */
    private fun readLeftHalf(): android.graphics.Bitmap {
        val w = width / 2; val h = height
        val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
        return android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, h, flip, false).also { bmp.recycle() }
    }

    private fun renderEye(eye: EyeView, target: RenderTarget, camera: CameraImageSource?) {
        target.bind()
        // the boot covers everything at first: nothing else needs drawing
        if (bootActive && boot.opaque) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            boot.render(eye)
            GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, DEPTH_ATTACHMENT, 0)
            return
        }
        if ((content as? EyeOverride)?.renderEye(eye, target) == true) {
            GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, DEPTH_ATTACHMENT, 0)
            return
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        renderer.renderBackground(scene, eye, camera, input.head)
        renderer.renderScene(scene, eye)
        scene.painter?.let { it.afterScene(eye); renderer.restoreState() }
        renderer.renderPanels(scene, eye, overlayPass = false)
        renderer.renderLate(scene, eye)
        if (boundaryActive && camera != null) {
            val ar = arCore!!
            renderer.drawBoundary(eye, camera, boundaryTex, ar.depthMatrix, boundaryWarnM, BOUNDARY_FULL_M, boundaryFade)
        }
        // someone walked up while you are in a virtual environment: they show through it
        if (camera != null && scene.background != Background.PASSTHROUGH && personTex != 0 && hands.personMask != null)
            renderer.drawHandCutout(eye, camera, personTex)
        // Aura is hidden while Joy-Cons drive the pointer; tracking keeps running underneath.
        val visual = settings.handTracking.handVisual
        if (visual != com.hyperxr.settings.HandVisual.NONE) {
            // over the passthrough, each hand is drawn where the camera image shows it (exact size
            // and place); in a virtual environment, or without a fresh image, from the 3D hand
            val overCamera = camera != null && camera.hasFrame && scene.background == Background.PASSTHROUGH && renderer.passthroughFill
            if (!overCamera) aura.render(handList, eye, target.fbo, target.width, target.height)
            else for (h in drawnHands) {
                if (!h.isUsable) continue
                if (hands.imageHand(h.handedness, imgHand, System.nanoTime() - DISPLAYED_CAMERA_AGE_NS) && imageToEye(imgHand, eye, camera!!, target.width, target.height)) {
                    val fade = if (h.state == com.hyperxr.handtracking.TrackingState.LOST_PREDICTING) 0.85f else 1f
                    aura.renderScreen(eyePx, fade, target.fbo, target.width, target.height)
                } else {
                    single[0] = h
                    aura.render(single, eye, target.fbo, target.width, target.height)
                }
            }
        }
        renderer.renderPanels(scene, eye, overlayPass = true)
        if (bootActive) boot.render(eye)   // fading out over the scene
        // Tile-based GPUs: the depth buffer is never read back, so do not store it to memory.
        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, DEPTH_ATTACHMENT, 0)
    }

    private val drawnHands = arrayOf(HandState(Handedness.LEFT), HandState(Handedness.RIGHT))
    private val imgHand = FloatArray(com.hyperxr.handtracking.HandJoint.COUNT * 2)
    private val eyePx = FloatArray(com.hyperxr.handtracking.HandJoint.COUNT * 2)
    private val single = arrayListOf(drawnHands[0])

    /**
     * Camera image (sensor orientation, 0..1) to this eye's viewport pixels (y up): the inverse of
     * how the passthrough is drawn (fill crop, then the view-to-CPU-image matrix).
     */
    private fun imageToEye(img: FloatArray, eye: EyeView, camera: CameraImageSource, w: Int, h: Int): Boolean {
        val m = camera.cpuMatrix
        val det = m[0] * m[5] - m[4] * m[1]
        if (kotlin.math.abs(det) < 1e-9f) return false
        val (cropU, cropV) = com.hyperxr.rendering.SceneRenderer.fillCrop(eye.proj[5] / eye.proj[0], camera)
        for (j in 0 until com.hyperxr.handtracking.HandJoint.COUNT) {
            val cx = img[j * 2] - m[12]; val cy = img[j * 2 + 1] - m[13]
            val u = (m[5] * cx - m[4] * cy) / det
            val v = (-m[1] * cx + m[0] * cy) / det
            val su = 0.5f + (u - 0.5f) / cropU
            val sv = 0.5f + (v - 0.5f) / cropV
            eyePx[j * 2] = su * w
            eyePx[j * 2 + 1] = (1f - sv) * h
        }
        return true
    }
    private val handList by lazy { drawnHands.toList() }

    // ---------------------------------------------------------------- hand occlusion
    private val maskBuf = java.nio.ByteBuffer.allocateDirect(com.hyperxr.handtracking.HandMaskEngine.SIZE * com.hyperxr.handtracking.HandMaskEngine.SIZE)
    private var personTex = 0
    private var personSeq = -1

    /** Inside a virtual environment, the people mask is on and each new one is uploaded (R8 texture). */
    private fun updateHandMask() {
        hands.personMaskEnabled = settings.graphics.peopleBreakthrough && input.handsAvailable &&
            scene.background != Background.PASSTHROUGH && ctx.passthroughAvailable
        if (hands.personMaskSeq != personSeq) {
            personSeq = hands.personMaskSeq
            hands.personMask?.let { personTex = uploadMask(personTex, it) }
        }
    }

    private fun uploadMask(texture: Int, m: ByteArray): Int {
        val n = com.hyperxr.handtracking.HandMaskEngine.SIZE
        var tex = texture
        if (tex == 0) {
            tex = com.hyperxr.rendering.Gl.genTexture()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, n, n, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null)
        }
        maskBuf.clear(); maskBuf.put(m); maskBuf.flip()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, n, n, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskBuf)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        return tex
    }
    private val headMatrix = FloatArray(16)
    private val eyeOffset = Vec3()
    private fun setupEye(eye: EyeView, offsetX: Float) {
        val head = input.head
        head.orientation.rotate(eyeOffset.set(offsetX, 0f, 0f), eye.position).add(head.position)
        Mat4.compose(eye.position, head.orientation, 1f, 1f, 1f, headMatrix)
        Mat4.invertRigid(headMatrix, eye.view)
        val a = compositor.aspect()
        val cam: CameraImageSource? = arCore ?: passthrough
        val tv: Float
        val th: Float
        if (renderer.passthroughFill && scene.background == com.hyperxr.rendering.Background.PASSTHROUGH && cam != null && cam.hasFrame) {
            // Render with exactly the field of view the camera image is shown with, so virtual
            // content (hands, panels) keeps its real size and position over the passthrough.
            val (cu, cv) = SceneRenderer.fillCrop(a, cam)
            th = cam.regionTanX * cu
            tv = cam.regionTanY * cv
        } else {
            tv = compositor.tanHalfFov(settings.graphics)
            th = tv * a
        }
        compositor.eyeTanH = th; compositor.eyeTanV = tv
        Mat4.frustumTan(-th, th, -tv, tv, 0.05f, 200f, eye.proj)
        eye.update()
    }

    /** ADPF (Android 12+): the render thread reports each frame's CPU time against the refresh period. */
    private var renderHint: Any? = null
    private var renderHintTried = false
    private fun reportFrameWork(ns: Long) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        if (!renderHintTried) {
            renderHintTried = true
            renderHint = runCatching {
                activity.getSystemService(android.os.PerformanceHintManager::class.java)
                    ?.createHintSession(intArrayOf(android.os.Process.myTid()), refreshPeriodNs)
            }.getOrNull()
        }
        (renderHint as? android.os.PerformanceHintManager.Session)?.let { runCatching { it.reportActualWorkDuration(ns.coerceAtLeast(1)) } }
    }

    /**
     * How far ahead the head pose is predicted: to when this frame's light leaves the screen.
     * With the CPU paced one frame behind the GPU that is about two refresh periods (render +
     * scan-out); the Head tracking "prediction" setting trims it (16 ms = no change).
     */
    private fun predictionNs(): Long {
        val trim = ((settings.headTracking.predictionMs - 16f) * 1e6f).toLong()
        val now = System.nanoTime()
        return (photonClock.photon(now, trim) - now).coerceIn(0L, 80_000_000L)
    }

    /** Mono 16:9 frustum from the (steadied) spectator pose; over the camera it matches the image. */
    private fun setupSpectator(cam: CameraImageSource?) {
        val e = spectatorEye
        e.index = 0
        e.position.set(share.pose.position)
        Mat4.compose(share.pose.position, share.pose.orientation, 1f, 1f, 1f, headMatrix)
        Mat4.invertRigid(headMatrix, e.view)
        val a = 16f / 9f
        val tv: Float; val th: Float
        if (renderer.passthroughFill && scene.background == Background.PASSTHROUGH && cam != null && cam.hasFrame) {
            val (cu, cv) = SceneRenderer.fillCrop(a, cam)
            th = cam.regionTanX * cu; tv = cam.regionTanY * cv
        } else {
            tv = 0.625f  // ~64 degrees vertical, ~96 horizontal: close to what one eye sees
            th = tv * a
        }
        Mat4.frustumTan(-th, th, -tv, tv, 0.05f, 200f, e.proj)
        e.update()
    }

    private fun setupEyeFromXr(eye: EyeView, o: Int) {
        eye.position.set(xrViews[o], xrViews[o + 1], xrViews[o + 2])
        val q = Quat(xrViews[o + 3], xrViews[o + 4], xrViews[o + 5], xrViews[o + 6])
        Mat4.compose(eye.position, q, 1f, 1f, 1f, headMatrix)
        Mat4.invertRigid(headMatrix, eye.view)
        Mat4.frustumTan(kotlin.math.tan(xrViews[o + 7]), kotlin.math.tan(xrViews[o + 8]), kotlin.math.tan(xrViews[o + 10]), kotlin.math.tan(xrViews[o + 9]), 0.05f, 200f, eye.proj)
        eye.update()
    }

    // ---------------------------------------------------------------- VR boundary ("Guardian")
    private var boundaryTex = 0
    private var boundaryBytes: ByteArray? = null
    private var boundaryW = 0
    private var boundaryH = 0
    private var boundaryFade = 0f
    private var boundaryActive = false
    private val boundaryWarnM get() = settings.graphics.boundaryDistanceM
    private val boundaryBuf by lazy { java.nio.ByteBuffer.allocateDirect(640 * 480).order(java.nio.ByteOrder.nativeOrder()) }

    /** Uploads the newest depth as an R8 texture and updates the overall fade (VR only). */
    private fun updateBoundary(dt: Float) {
        val ar = arCore
        boundaryActive = false
        if (ar == null || !settings.graphics.vrBoundary || scene.background == Background.PASSTHROUGH || !ar.hasFrame) return
        val t0 = System.nanoTime()
        val d = ar.depthCentimetres(boundaryBytes)
        if (d != null) {
            val (bytes, w, h) = d
            boundaryBytes = bytes
            if (w * h <= boundaryBuf.capacity()) {
                if (boundaryTex == 0) {
                    boundaryTex = com.hyperxr.rendering.Gl.genTexture()
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, boundaryTex)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                }
                boundaryBuf.clear(); boundaryBuf.put(bytes, 0, w * h); boundaryBuf.flip()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, boundaryTex)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
                if (w != boundaryW || h != boundaryH) {
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, boundaryBuf)
                    boundaryW = w; boundaryH = h
                } else {
                    GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, boundaryBuf)
                }
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
                val target = com.hyperxr.input.BoundaryMonitor.fade(com.hyperxr.input.BoundaryMonitor.nearest(bytes, w, h), boundaryWarnM, BOUNDARY_FULL_M)
                // rise fast (safety), fall slowly (no flicker)
                val rate = if (target > boundaryFade) 8f else 1.5f
                boundaryFade += (target - boundaryFade) * (rate * dt).coerceAtMost(1f)
            }
        }
        boundaryActive = boundaryTex != 0 && boundaryW > 0
        Diagnostics.record(Stage.SENSOR_FUSION, System.nanoTime() - t0)
    }

    private val camPose = Pose()
    private fun submitArCoreHands(ar: ArCoreSession) {
        if (!input.handsAvailable || !hands.wantsFrame()) return
        val intr = ar.cpuIntrinsics() ?: return
        // While ARCore is not tracking (dark viewer, featureless wall) the hands must keep
        // working: place them relative to the head, like the Camera2 path does.
        if (!ar.cameraPose(camPose)) synchronized(input) { camPose.set(input.head) }
        val img = ar.acquireCpuImage() ?: return
        val depth = if (settings.handTracking.useArCoreDepth) ar.depthImage(intr.width, intr.height) else null
        try {
            hands.submit(img, intr, camPose, depth?.first, rotation180 = displayRotation() == android.view.Surface.ROTATION_270)
        } finally { depth?.second?.invoke() }
    }

    val hyperConnect = com.hyperxr.connect.HyperConnectServer(activity)
    private val phoneModel = com.hyperxr.connect.PhoneControllerModel()
    private val phoneRay = Ray()
    private val headPosArr = FloatArray(3)
    private val headQArr = FloatArray(4)
    private var phoneMenu = false
    private var phoneWasConnected = false

    private fun updateControllers(dt: Float) {
        for (i in 0..1) { input.controllers[i].connected = false; input.controllers[i].aim = null; input.controllers[i].haptics = null }
        val now = System.nanoTime()
        for (jc in joyCons.controllers.values) {
            val idx = when (jc.side) { JoyConSide.LEFT -> 0; else -> 1 }
            val c = input.controllers[idx]
            c.connected = true
            c.buttons = jc.buttons
            c.stickX = jc.stickX; c.stickY = jc.stickY
            c.stick2X = jc.stick2X; c.stick2Y = jc.stick2Y
            // Controller motion sensors are not used; games get the pose of the hand holding it.
            fusions[idx].useImu = false
            fusions[idx].update(null, input.hands[idx], input.head, rightSide = idx == 1, nowNs = now, dtSeconds = dt)
            c.pose.orientation.set(fusions[idx].worldOrientation)
            c.pose.position.set(fusions[idx].gripPosition)
            c.positionFromHand = fusions[idx].positionFromHand
        }
    }

    /** HyperConnect: the controller phone becomes the right controller, aiming with its own orientation. */
    private fun updatePhoneController() {
        if (settings.controllers.hyperConnect) hyperConnect.start() else { hyperConnect.stop(); return }
        val st = hyperConnect.state
        val on = hyperConnect.connected && st != null
        if (on != phoneWasConnected) { phoneWasConnected = on; if (!on) phoneModel.reset() }
        if (!on || st == null) return
        val h = input.head
        headPosArr[0] = h.position.x; headPosArr[1] = h.position.y; headPosArr[2] = h.position.z
        headQArr[0] = h.orientation.x; headQArr[1] = h.orientation.y; headQArr[2] = h.orientation.z; headQArr[3] = h.orientation.w
        phoneModel.leftHanded = hyperConnect.leftHanded
        phoneModel.update(st, headPosArr, headQArr, System.nanoTime())
        val c = input.controllers[if (hyperConnect.leftHanded) 0 else 1]
        c.connected = true
        c.buttons = (if (phoneModel.select) 128 else 0) or (if (phoneModel.back) 256 else 0)
        c.stickX = 0f; c.stickY = 0f; c.stick2X = 0f
        c.stick2Y = phoneModel.scroll
        phoneRay.origin.set(phoneModel.rayOrigin[0], phoneModel.rayOrigin[1], phoneModel.rayOrigin[2])
        phoneRay.direction.set(phoneModel.rayDirection[0], phoneModel.rayDirection[1], phoneModel.rayDirection[2])
        c.aim = phoneRay
        c.pose.position.set(phoneRay.origin)
        c.pose.orientation.set(phoneModel.orientation[0], phoneModel.orientation[1], phoneModel.orientation[2], phoneModel.orientation[3])
        c.positionFromHand = false
        if (settings.controllers.hyperConnectHaptics) c.haptics = { ms -> hyperConnect.vibrate(ms) }
        if (phoneModel.menu && !phoneMenu) pendingActions += Triple(ControllerAction.MENU, true, null)
        phoneMenu = phoneModel.menu
        if (phoneModel.homeTapped) pendingActions += Triple(ControllerAction.RECENTER, true, null)
    }

    private fun recenterControllers() { input.recenterCursor = true }

    // ---------------------------------------------------------------- activity hooks
    fun onTouch(e: MotionEvent): Boolean {
        if (!settings.controllers.screenTapSelects) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> tap.onDown()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tap.onUp()
        }
        return true
    }

    fun onKey(e: KeyEvent): Boolean {
        if (::ctx.isInitialized) ctx.gameInput?.let { if (it.onKey(e)) return true }
        if (joyCons.onKeyEvent(e)) return true
        // Viewer buttons / volume keys act as select so a plain VR Box remains usable.
        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (e.action == KeyEvent.ACTION_DOWN) { if (e.repeatCount == 0) tap.onDown() } else tap.onUp()
            return true
        }
        return false
    }

    fun onGeneric(e: MotionEvent): Boolean {
        if (::ctx.isInitialized) ctx.gameInput?.let { if (it.onMotion(e)) return true }
        return joyCons.onMotionEvent(e)
    }

    fun onResume() {
        tracker?.start(); gyro.start(); content.onResume()
        vsyncRunning = true
        activity.runOnUiThread { android.view.Choreographer.getInstance().postFrameCallback(vsyncCallback) }
    }

    fun onPause() {
        vsyncRunning = false
        activity.runOnUiThread { android.view.Choreographer.getInstance().removeFrameCallback(vsyncCallback) }
        tracker?.stop()
        gyro.stop()
        content.onPause()
    }

    /** GL thread teardown (queueEvent from the activity before finishing). */
    fun releaseGl() {
        content.onGlRelease()
        images.clear()
        compositor.release()
        share.release()
        xrTargets.values.forEach { it.release() }
        if (openXrSession != 0L) { OpenXrNative.nativeDestroySession(openXrSession); openXrSession = 0L }
    }

    companion object {
        private val DEPTH_ATTACHMENT = intArrayOf(GLES30.GL_DEPTH_ATTACHMENT)
        /** Distance at which the boundary warning shows the camera fully. */
        private const val BOUNDARY_FULL_M = 0.35f
    }

    fun onDestroy() {
        joyCons.stop()
        hyperConnect.stop()
        hands.shutdown()
        passthrough?.shutdown()
        arCore?.close()
        (tracker as? RotationVectorTracker)?.shutdown()
        (tracker as? com.hyperxr.headtracking.VisualGyroTracker)?.shutdown()
        gyro.shutdown()
        audio.release()
    }
}
