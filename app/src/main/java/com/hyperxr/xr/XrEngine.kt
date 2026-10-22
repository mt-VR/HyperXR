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
import com.hyperxr.rendering.HandAuraRenderer
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
    /** Set by content; the engine fills it with the left-eye image after the next composite. */
    @Volatile var captureRequest: ((android.graphics.Bitmap) -> Unit)? = null
    var trackingDescription = ""
    /** Live hand-tracking status for in-XR diagnostics. */
    var handStatus: () -> String = { "" }
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
class XrEngine(private val activity: Activity, private val content: XrContent) : GLSurfaceView.Renderer {
    private val app = HyperXRApp.instance
    private var settings = app.settings.current
    val decision: XrModeDecision = XrModeResolver.resolve(app.capabilities.last.takeIf { it.androidSdk > 0 } ?: app.capabilities.probe(app.runtimeBroker), settings.xrMode)

    private val renderer = SceneRenderer()
    private val aura = HandAuraRenderer()
    private val skeleton = com.hyperxr.rendering.HandSkeletonRenderer()
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
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        com.hyperxr.rendering.FullscreenTriangle.reset()
        renderer.init()
        renderer.passthroughFill = settings.graphics.passthroughFill
        aura.init()
        compositor.init()
        aura.opacity = settings.handTracking.auraOpacity
        ctx = XrContext(activity, settings, decision, renderer, images, icons, audio, scene, input)
        ctx.requestRecenter = { tracker?.recenter(); recenterControllers() }
        ctx.handStatus = {
            if (!input.handsAvailable) "Mãos: desligadas (sem câmera, sem permissão ou hand tracking desativado)"
            else "Mãos: ${hands.status} (${hands.backendInUse}) · ${hands.stats}"
        }

        val forced3Dof = settings.headTracking.source == com.hyperxr.settings.HeadTrackingSource.ROTATION_VECTOR_3DOF
        when (decision.mode) {
            XrMode.MIXED_REALITY_ARCORE -> if (forced3Dof) startCameraPassthrough() else {
                val ar = ArCoreSession(activity)
                val depth = ar.create(settings.handTracking.useArCoreDepth)
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
                if (settings.runtime.allowOpenXrSession) {
                    val err = arrayOfNulls<String>(1)
                    openXrSession = OpenXrNative.nativeCreateSession(activity, err)
                    if (openXrSession == 0L) Diagnostics.log("OpenXR session failed: ${err[0]}; using VR SBS")
                }
                if (openXrSession == 0L) tracker = RotationVectorTracker(activity, displayRotation()).also { it.start() }
            }
            XrMode.VR_SBS -> {
                // VR shows no camera image, but the camera still feeds hand tracking when allowed.
                val caps = app.capabilities.last
                val cameraOk = caps.cameraPermission && caps.hasBackCamera
                // The boundary warning needs depth: ARCore then also provides 6DoF and hand frames.
                if (cameraOk && settings.graphics.vrBoundary && caps.arCore.ok && !forced3Dof) {
                    val ar = ArCoreSession(activity)
                    ar.create(useDepth = true)
                    if (ar.session != null && ar.depthEnabled) {
                        arCore = ar; tracker = ar; ar.start()
                    } else {
                        ar.close()
                        Diagnostics.log("VR boundary unavailable: ARCore Depth not supported here")
                    }
                }
                if (arCore == null) {
                    if (settings.handTracking.enabled && cameraOk) startCameraPassthrough()
                    else tracker = RotationVectorTracker(activity, displayRotation()).also { it.start() }
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
        cam.onCpuFrame = { img, intr ->
            val camPose = Pose().also { p -> synchronized(input) { p.set(input.head) } }
            hands.submit(img, intr, camPose, null, rotation180 = displayRotation() == android.view.Surface.ROTATION_270)
        }
        cam.start()
        passthrough = cam
        tracker = RotationVectorTracker(activity, displayRotation()).also { it.start() }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = activity.windowManager.defaultDisplay.rotation

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
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
        val predictionNs = (settings.headTracking.predictionMs * 1e6).toLong()
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
        input.tapDown = tap.next()
        while (true) {
            val a = pendingActions.poll() ?: break
            if (a.first == ControllerAction.RECENTER && a.second) ctx.requestRecenter()
            content.onAction(ctx, a.first, a.second, a.third)
        }

        // 3. content
        scene.reset()
        val sim0 = System.nanoTime()
        content.onFrame(ctx, dt)
        Diagnostics.record(Stage.SIMULATION, System.nanoTime() - sim0)
        if (!ctx.passthroughAvailable && scene.background == Background.PASSTHROUGH) scene.background = Background.ENVIRONMENT

        updateBoundary(dt)

        val visual = settings.handTracking.handVisual
        if (visual == com.hyperxr.settings.HandVisual.SKELETON || visual == com.hyperxr.settings.HandVisual.BOTH) {
            skeleton.submit(scene, renderer, input.hands)
        }

        // 4. render
        val r0 = System.nanoTime()
        renderer.renderShadows(scene)
        val camera: CameraImageSource? = arCore ?: passthrough
        if (openXrSession != 0L) {
            if (xrFrame == 1) {
                for (eye in 0..1) {
                    val o = eye * 14
                    val tex = xrViews[o + 11].toInt()
                    val w = xrViews[o + 12].toInt(); val h = xrViews[o + 13].toInt()
                    val target = xrTargets.getOrPut(tex) { RenderTarget(w, h, depth = true, externalTexture = tex) }
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
            for (eye in 0..1) {
                setupEye(eyes[eye], if (eye == 0) -ipd / 2 else ipd / 2)
                renderEye(eyes[eye], compositor.eyeTarget(eye), camera)
            }
            compositor.composite(settings.graphics)
            ctx.captureRequest?.let { cb -> ctx.captureRequest = null; cb(readLeftHalf()) }
        }
        Diagnostics.record(Stage.RENDER_CPU, System.nanoTime() - r0)
        Diagnostics.record(Stage.INPUT_TO_PHOTON, System.nanoTime() - frameStart)
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
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        renderer.renderBackground(scene, eye, camera, input.head)
        renderer.renderScene(scene, eye)
        renderer.renderPanels(scene, eye, overlayPass = false)
        renderer.renderLate(scene, eye)
        if (boundaryActive && camera != null) {
            val ar = arCore!!
            renderer.drawBoundary(eye, camera, boundaryTex, ar.depthMatrix, boundaryWarnM, BOUNDARY_FULL_M, boundaryFade)
        }
        // Aura is hidden while Joy-Cons drive the pointer; tracking keeps running underneath.
        val visual = settings.handTracking.handVisual
        if (visual == com.hyperxr.settings.HandVisual.AURA || visual == com.hyperxr.settings.HandVisual.BOTH) {
            aura.render(handList, eye, target.fbo, target.width, target.height)
        }
        renderer.renderPanels(scene, eye, overlayPass = true)
        // Tile-based GPUs: the depth buffer is never read back, so do not store it to memory.
        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, DEPTH_ATTACHMENT, 0)
    }

    private val handList by lazy { input.hands.toList() }
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

    private fun updateControllers(dt: Float) {
        for (i in 0..1) input.controllers[i].connected = false
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
        if (joyCons.onKeyEvent(e)) return true
        // Viewer buttons / volume keys act as select so a plain VR Box remains usable.
        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (e.action == KeyEvent.ACTION_DOWN) { if (e.repeatCount == 0) tap.onDown() } else tap.onUp()
            return true
        }
        return false
    }

    fun onGeneric(e: MotionEvent): Boolean = joyCons.onMotionEvent(e)

    fun onResume() { tracker?.start(); content.onResume() }

    fun onPause() {
        tracker?.stop()
        content.onPause()
    }

    /** GL thread teardown (queueEvent from the activity before finishing). */
    fun releaseGl() {
        content.onGlRelease()
        images.clear()
        compositor.release()
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
        hands.shutdown()
        passthrough?.shutdown()
        arCore?.close()
        (tracker as? RotationVectorTracker)?.shutdown()
        audio.release()
    }
}
