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
    var hasImu = false
    fun ray(out: Ray): Ray { out.origin.set(pose.position); pose.orientation.rotate(Pose.FORWARD, out.direction); return out }
}

/** Everything content needs to know about the user this frame. */
class XrInput {
    val head = Pose()
    val hands = arrayOf(HandState(Handedness.LEFT), HandState(Handedness.RIGHT))
    val controllers = arrayOf(ControllerState(), ControllerState())
    var tapDown = false
    var handsAvailable = false
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
    private val imuQ = Quat()

    private var openXrSession = 0L
    private val xrViews = FloatArray(28)
    private val xrTargets = HashMap<Int, RenderTarget>()

    private val eyes = arrayOf(EyeView(), EyeView())
    private var lastFrameNs = 0L
    private var width = 0
    private var height = 0
    @Volatile private var tapDown = false
    @Volatile var exitRequested = false
    private val pendingActions = java.util.concurrent.ConcurrentLinkedQueue<Triple<ControllerAction, Boolean, JoyConSide?>>()

    fun onCreate() {
        joyCons.settings = settings.controllers
        joyCons.onAction = { side, ev -> pendingActions += Triple(ev.action, ev.pressed, side) }
        joyCons.start()
        hands.configure(settings.handTracking, settings.performance.handInferenceHz)
        if (settings.performance.sustainedPerformanceMode) {
            activity.window.setSustainedPerformanceMode(true)
        }
        Diagnostics.log("XR start: ${decision.mode} (${decision.headTracking}); ${decision.reasons.joinToString("; ")}")
    }

    // ---------------------------------------------------------------- GL lifecycle
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        com.hyperxr.rendering.FullscreenTriangle.reset()
        renderer.init()
        aura.init()
        compositor.init()
        aura.opacity = settings.handTracking.auraOpacity
        ctx = XrContext(activity, settings, decision, renderer, images, icons, audio, scene, input)
        ctx.requestRecenter = { tracker?.recenter(); recenterControllers() }

        when (decision.mode) {
            XrMode.MIXED_REALITY_ARCORE -> {
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
            XrMode.VR_SBS -> tracker = RotationVectorTracker(activity, displayRotation()).also { it.start() }
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
            hands.submit(img, intr, camPose, null, null)
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
        updateControllers()
        input.tapDown = tapDown
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

        // 4. render
        val r0 = System.nanoTime()
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
            val ipd = settings.graphics.ipdMm / 1000f
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
        // Aura is hidden while Joy-Cons drive the pointer; tracking keeps running underneath.
        if (settings.handTracking.showAura && joyCons.controllers.isEmpty()) {
            aura.render(input.hands.toList(), eye, target.fbo, target.width, target.height)
        }
        renderer.renderPanels(scene, eye, overlayPass = true)
    }

    private val headMatrix = FloatArray(16)
    private val eyeOffset = Vec3()
    private fun setupEye(eye: EyeView, offsetX: Float) {
        val head = input.head
        head.orientation.rotate(eyeOffset.set(offsetX, 0f, 0f), eye.position).add(head.position)
        Mat4.compose(eye.position, head.orientation, 1f, 1f, 1f, headMatrix)
        Mat4.invertRigid(headMatrix, eye.view)
        val t = compositor.tanHalfFov(settings.graphics)
        val a = compositor.aspect()
        Mat4.frustumTan(-t * a, t * a, -t, t, 0.05f, 200f, eye.proj)
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

    private val camPose = Pose()
    private fun submitArCoreHands(ar: ArCoreSession) {
        if (!input.handsAvailable) return
        val intr = ar.cpuIntrinsics() ?: return
        if (!ar.cameraPose(camPose)) return
        val img = ar.acquireCpuImage() ?: return
        val depth = if (settings.handTracking.useArCoreDepth) ar.depthSampler(intr.width, intr.height) else null
        hands.submit(img, intr, camPose, depth?.first, depth?.second)
    }

    private fun updateControllers() {
        for (i in 0..1) input.controllers[i].connected = false
        val list = joyCons.controllers.values.toList()
        for (jc in list) {
            val idx = when (jc.side) { JoyConSide.LEFT -> 0; else -> 1 }
            val c = input.controllers[idx]
            c.connected = true
            c.buttons = jc.buttons
            c.stickX = jc.stickX; c.stickY = jc.stickY
            c.hasImu = jc.hasImu
            val hand = input.hands[idx]
            val wrist = if (hand.isUsable) hand.joints[com.hyperxr.handtracking.HandJoint.MIDDLE_MCP] else null
            if (jc.hasImu) {
                jc.orientationSnapshot(imuQ)
                if (!fusions[idx].calibrated) fusions[idx].recenter(imuQ, input.head)
                fusions[idx].update(imuQ, wrist, input.head, mirrorForLeft = idx == 0)
                c.pose.orientation.set(fusions[idx].worldOrientation)
                c.pose.position.set(fusions[idx].gripPosition)
                c.positionFromHand = fusions[idx].positionFromHand
            } else {
                // No IMU exposed by the driver: point with the head direction from the hand/grip position.
                fusions[idx].update(input.head.orientation, wrist, input.head, mirrorForLeft = idx == 0)
                c.pose.position.set(fusions[idx].gripPosition)
                c.pose.orientation.set(input.head.orientation)
                c.positionFromHand = fusions[idx].positionFromHand
            }
        }
    }

    private fun recenterControllers() {
        for (jc in joyCons.controllers.values) {
            val idx = if (jc.side == JoyConSide.LEFT) 0 else 1
            if (jc.hasImu) { jc.orientationSnapshot(imuQ); fusions[idx].recenter(imuQ, input.head) }
        }
    }

    // ---------------------------------------------------------------- activity hooks
    fun onTouch(e: MotionEvent): Boolean {
        if (!settings.controllers.screenTapSelects) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> tapDown = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tapDown = false
        }
        return true
    }

    fun onKey(e: KeyEvent): Boolean {
        if (joyCons.onKeyEvent(e)) return true
        // Viewer buttons / volume keys act as select so a plain VR Box remains usable.
        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            tapDown = e.action == KeyEvent.ACTION_DOWN
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

    fun onDestroy() {
        joyCons.stop()
        hands.shutdown()
        passthrough?.shutdown()
        arCore?.close()
        (tracker as? RotationVectorTracker)?.shutdown()
        audio.release()
    }
}
