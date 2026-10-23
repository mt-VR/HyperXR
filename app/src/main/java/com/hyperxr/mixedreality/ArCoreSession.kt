package com.hyperxr.mixedreality

import android.os.SystemClock

import android.app.Activity
import android.media.Image
import android.opengl.Matrix
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.hyperxr.capabilities.Capability
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.Stage
import com.hyperxr.handtracking.CameraIntrinsics
import com.hyperxr.headtracking.HeadTracker
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.CameraImageSource
import com.hyperxr.rendering.Gl
import java.nio.ByteOrder

/**
 * ARCore-backed mixed reality: 6DoF head pose, the camera image for passthrough, the Depth API
 * (when supported) and CPU camera frames for hand tracking. ARCore owns the camera, so no Camera2
 * session is opened in this mode.
 */
class ArCoreSession(private val activity: Activity) : HeadTracker, CameraImageSource {
    override val description: String get() = "6DoF (ARCore${if (fps60) " 60 fps" else ""}${if (depthEnabled) " + Depth" else ""}${if (gyro != null) " + gyro" else ""})"
    @Volatile var fps60 = false; private set
    override val sixDof = true

    var session: Session? = null; private set
    var lastFrame: Frame? = null; private set
    var depthEnabled = false; private set
    var trackingState: TrackingState = TrackingState.STOPPED; private set
    var error: String? = null; private set

    override var textureId = 0; private set
    override val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    override var fovX = 1.1f; private set
    override var fovY = 0.85f; private set
    /** ARCore maps VIEW_NORMALIZED to the display, so quad UVs cover the full-screen aspect. */
    override var imageAspect = 2f; private set
    /** ARCore crops the camera texture to the display aspect: the visible region's tangents. */
    override val regionTanX: Float get() {
        val tx = kotlin.math.tan(fovX / 2); val ty = kotlin.math.tan(fovY / 2)
        return if (imageAspect > tx / ty) tx else ty * imageAspect
    }
    override val regionTanY: Float get() {
        val tx = kotlin.math.tan(fovX / 2); val ty = kotlin.math.tan(fovY / 2)
        return if (imageAspect > tx / ty) tx / imageAspect else ty
    }
    override val hasFrame: Boolean get() = lastFrame != null && lastFrame!!.timestamp != 0L

    private val headPose = Pose()
    private val origin = Pose()
    private var recenterPending = true
    private val quadUv = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private val texUv = FloatArray(8)
    private val imgUv = FloatArray(8)
    /** Maps view-normalized UVs to the CPU/depth image (depth is aligned with the CPU image). */
    val depthMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var texMatrixValid = false

    /** Must be called on the GL thread (creates the camera texture). */
    companion object {
        /** Streaming (SteamVR) needs only the pose and the hand image: no planes, smallest camera image. */
        @Volatile var lightweight = false
    }

    fun create(useDepth: Boolean): Capability {
        return try {
            val s = Session(activity)
            val config = Config(s).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.FIXED
                planeFindingMode = if (lightweight) Config.PlaneFindingMode.DISABLED else Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            }
            // Camera configuration. The Depth API is only offered with some of them (often not
            // with the 60 fps ones), so when depth is wanted it wins over 60 fps: the first
            // configuration that supports it, 60 fps and the sharpest image first.
            val fpsFilter = com.google.ar.core.CameraConfigFilter(s).setTargetFps(java.util.EnumSet.of(
                com.google.ar.core.CameraConfig.TargetFps.TARGET_FPS_30, com.google.ar.core.CameraConfig.TargetFps.TARGET_FPS_60))
            val candidates = runCatching { s.getSupportedCameraConfigs(fpsFilter) }.getOrDefault(emptyList())
                .sortedWith(if (lightweight) compareBy({ -it.fpsRange.upper }, { it.imageSize.width * it.imageSize.height })
                    else compareBy({ -it.fpsRange.upper }, { -(it.textureSize.width * it.textureSize.height) }))
            var chosen: com.google.ar.core.CameraConfig? = null
            var depthSupported = false
            if (useDepth && !lightweight) {
                for (c in candidates) {
                    val ok = runCatching { s.cameraConfig = c; s.isDepthModeSupported(Config.DepthMode.AUTOMATIC) }.getOrDefault(false)
                    if (ok) { chosen = c; depthSupported = true; break }
                }
                if (chosen == null) Diagnostics.log("ARCore Depth API: not offered with any of the ${candidates.size} camera configurations of this phone")
                else Diagnostics.log("ARCore Depth API: on, camera ${chosen.imageSize.width}x${chosen.imageSize.height} @ ${chosen.fpsRange.upper} fps")
            }
            if (chosen == null) chosen = candidates.firstOrNull()
            chosen?.let { runCatching { s.cameraConfig = it } }
            if (!depthSupported) depthSupported = runCatching { s.isDepthModeSupported(Config.DepthMode.AUTOMATIC) }.getOrDefault(false)
            var chosen60 = (chosen?.fpsRange?.upper ?: 30) >= 60
            if (useDepth && depthSupported) config.depthMode = Config.DepthMode.AUTOMATIC
            depthEnabled = useDepth && depthSupported
            try { s.configure(config) } catch (t: Throwable) {
                if (depthEnabled) {
                    // depth and this camera do not mix after all: keep the camera, drop depth
                    Diagnostics.log("ARCore with depth refused (${t.message}); continuing without depth")
                    config.depthMode = Config.DepthMode.DISABLED; depthEnabled = false
                    try { s.configure(config) } catch (t2: Throwable) {
                        if (!chosen60) throw t2
                        fallback30(s); chosen60 = false; s.configure(config)
                    }
                } else {
                    if (!chosen60) throw t
                    // 60 fps and this configuration do not mix on this phone: back to 30
                    Diagnostics.log("ARCore 60 fps refused (${t.message}); using 30 fps")
                    fallback30(s); chosen60 = false; s.configure(config)
                }
            }
            fps60 = chosen60
            textureId = Gl.externalTexture()
            s.setCameraTextureName(textureId)
            session = s
            when {
                depthEnabled -> Capability.yes("ARCore Depth API supported")
                !useDepth -> Capability.no("ARCore Depth API off in the settings")
                depthSupported -> Capability.no("ARCore Depth API refused by this camera configuration")
                else -> Capability.no("ARCore Depth API not offered by this phone (Google's list of depth-capable devices)")
            }
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
            Diagnostics.log("ARCore session failed: $error")
            Capability.no("ARCore session failed: ${t.message}")
        }
    }

    private fun fallback30(s: Session) {
        s.getSupportedCameraConfigs(com.google.ar.core.CameraConfigFilter(s).setTargetFps(java.util.EnumSet.of(com.google.ar.core.CameraConfig.TargetFps.TARGET_FPS_30)))
            .firstOrNull()?.let { s.cameraConfig = it }
    }

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        session?.setDisplayGeometry(rotation, width, height)
        imageAspect = width.toFloat() / height.coerceAtLeast(1)
        texMatrixValid = false
    }

    // A resumed session starts from where the user is now, not from the room as it was left:
    // the tracking origin is placed at the head again (content keeps its layout around the user).
    override fun start() { recenterPending = true; lost = false; translation.reset(); prevTs = 0L; try { session?.resume() } catch (t: Throwable) { error = t.message; Diagnostics.log("ARCore resume failed: ${t.message}") } }
    override fun stop() { session?.pause() }
    fun close() { session?.close(); session = null }

    /** Advances ARCore one frame (GL thread). */
    fun update() {
        val s = session ?: return
        val t0 = System.nanoTime()
        val frame = try { s.update() } catch (t: Throwable) { error = t.message; return }
        lastFrame = frame
        trackingState = frame.camera.trackingState
        if (frame.hasDisplayGeometryChanged() || !texMatrixValid) {
            frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, quadUv, Coordinates2d.TEXTURE_NORMALIZED, texUv)
            // Quad UVs use a top-left origin like VIEW_NORMALIZED. quadUv corners: BL, BR, TL, TR.
            // Affine map T(u,v) = TL + u*(TR-TL) + v*(BL-TL), stored column-major.
            Matrix.setIdentityM(texMatrix, 0)
            texMatrix[0] = texUv[6] - texUv[4]; texMatrix[1] = texUv[7] - texUv[5]
            texMatrix[4] = texUv[0] - texUv[4]; texMatrix[5] = texUv[1] - texUv[5]
            texMatrix[12] = texUv[4]; texMatrix[13] = texUv[5]
            frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, quadUv, Coordinates2d.IMAGE_NORMALIZED, imgUv)
            Matrix.setIdentityM(depthMatrix, 0)
            depthMatrix[0] = imgUv[6] - imgUv[4]; depthMatrix[1] = imgUv[7] - imgUv[5]
            depthMatrix[4] = imgUv[0] - imgUv[4]; depthMatrix[5] = imgUv[1] - imgUv[5]
            depthMatrix[12] = imgUv[4]; depthMatrix[13] = imgUv[5]
            texMatrixValid = true
            val intr = frame.camera.textureIntrinsics
            val f = intr.focalLength
            val dims = intr.imageDimensions
            fovX = (2 * kotlin.math.atan(dims[0] / (2f * f[0])))
            fovY = (2 * kotlin.math.atan(dims[1] / (2f * f[1])))
            // display is landscape: if the image is portrait-oriented swap
            if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                val t = fovX; fovX = fovY; fovY = t
            }
        }
        if (trackingState == TrackingState.TRACKING) {
            val p = frame.camera.displayOrientedPose
            // what was being shown while tracking was lost (last position, gyro-turned orientation)
            if (lost) origin.inverse().compose(headPose, shownWhileLost)
            headPose.position.set(p.tx(), p.ty(), p.tz())
            headPose.orientation.set(p.qx(), p.qy(), p.qz(), p.qw())
            if (recenterPending) {
                // origin so that the current head yaw faces -Z and the floor estimate is 1.6 m below the eyes
                val yaw = com.hyperxr.input.ControllerPoseFusion.yawOnly(headPose.orientation, Quat())
                origin.orientation.set(yaw)
                origin.position.set(headPose.position.x, headPose.position.y - 1.6f, headPose.position.z)
                recenterPending = false
            } else if (lost && com.hyperxr.headtracking.TrackingRebase.jumped(shownWhileLost, origin.inverse().compose(headPose))) {
                // ARCore came back with its world moved: keep the head (and so every window) where it was
                com.hyperxr.headtracking.TrackingRebase.origin(headPose, shownWhileLost, origin)
                Diagnostics.log("6DoF: tracking came back shifted; kept the room in place")
            }
            if (lost) { lost = false; translation.reset(); prevTs = 0L }
            onArPose(frame.timestamp)
        } else {
            // tracking lost (covered camera, fast motion, a blank wall): position holds, rotation
            // keeps following the gyroscope instead of freezing
            val now = SystemClock.elapsedRealtimeNanos()
            if (!lost) { lost = true; lostPropNs = now; translation.reset() }
            else {
                val g = gyro
                if (g != null && g.delta(lostPropNs, now, dDev)) {
                    toHead(dDev, fix, dHead)
                    headPose.orientation.mul(dHead, headPose.orientation).normalize()
                }
                lostPropNs = now
            }
            frameTs = now
        }
        Diagnostics.record(Stage.SENSOR_FUSION, System.nanoTime() - t0)
        Diagnostics.record(Stage.CAMERA_CAPTURE, kotlin.math.max(0L, System.nanoTime() - frame.timestamp))
    }

    override fun pose(predictionNs: Long, out: Pose) {
        // pose relative to the recentred origin, as of the camera frame
        val inv = origin.inverse()
        inv.compose(headPose, out)
        val g = gyro ?: return
        if (frameTs == 0L) return
        // ARCore's pose is as old as the camera frame (30-60 Hz, tens of ms): the gyroscope
        // supplies the rotation since then and up to the moment the frame reaches the eyes
        val now = SystemClock.elapsedRealtimeNanos()
        var tc = frameTs
        if (kotlin.math.abs(now - tc) > 500_000_000L) tc = now - 35_000_000L  // camera clock not REALTIME
        val target = now + predictionNs
        qAtPose.set(out.orientation)
        if (g.delta(tc, target, dDev)) {
            toHead(dDev, fix, dHead)
            out.orientation.mul(dHead, out.orientation).normalize()
        }
        // translation: the eyes swing around the neck with the (current, gyro) orientation, plus
        // the neck's own velocity while the head really moves (see HeadTranslationPredictor)
        posAtPose.set(out.position)
        translation.predict(posAtPose, qAtPose, out.orientation, (target - tc) / 1e9f, out.position)
    }

    private val translation = com.hyperxr.headtracking.HeadTranslationPredictor()
    private var lost = false
    private var lostPropNs = 0L
    private val shownWhileLost = Pose()
    private val qAtPose = Quat()
    private val posAtPose = Vec3()

    override fun recenter() { recenterPending = true; translation.reset(); prevTs = 0L }

    // ---------------------------------------------------------------- gyro fusion
    /** Full-rate gyroscope history (set by the engine); null = ARCore pose only. */
    var gyro: com.hyperxr.headtracking.GyroHistory? = null
    private var frameTs = 0L
    private var prevTs = 0L
    private val prevRel = Vec3()
    private val dDev = Quat(); private val dHead = Quat()
    private val prevOri = Quat()

    /**
     * Head frame (display-oriented) from the phone's sensor frame: a rotation about the screen
     * normal that depends on how the phone lies in the viewer. Rather than trust one guess, the
     * four candidates are scored against ARCore's own rotation between frames during the first
     * seconds and the best one is kept.
     */
    private val candidates = Array(4) { k -> Quat.fromAxisAngle(0f, 0f, 1f, (k * Math.PI / 2).toFloat()) }
    private val candidateError = FloatArray(4)
    private var calibrationSamples = 0
    private var fix = Quat.fromAxisAngle(0f, 0f, 1f, (-Math.PI / 2).toFloat())
    private val tmpA = Quat(); private val tmpB = Quat()

    var displayRotation: Int = android.view.Surface.ROTATION_90
        set(v) {
            field = v
            fix = Quat.fromAxisAngle(0f, 0f, 1f, (if (v == android.view.Surface.ROTATION_270) Math.PI / 2 else -Math.PI / 2).toFloat())
        }

    /** dHead = fix⁻¹ · dDev · fix (head = device · fix). */
    private fun toHead(d: Quat, f: Quat, out: Quat) {
        f.conjugate(tmpA).mul(d, tmpB)
        tmpB.mul(f, out)
    }

    private fun onArPose(ts: Long) {
        val rel = origin.inverse().compose(headPose)
        val g = gyro
        translation.onPose(rel.position, rel.orientation, ts)
        if (prevTs != 0L && ts > prevTs) {
            // score the sensor-to-head candidates while the head turns
            if (g != null && calibrationSamples < 90 && g.delta(prevTs, ts, dDev)) {
                prevOri.conjugate(tmpA).mul(headPose.orientation, dHead)  // ARCore's own rotation
                if (dHead.angleTo(Quat()) > 0.03f) {
                    for (k in 0..3) {
                        val c = Quat(); toHead(dDev, candidates[k], c)
                        candidateError[k] += c.angleTo(dHead)
                    }
                    calibrationSamples++
                    if (calibrationSamples == 90) {
                        val best = candidateError.indices.minByOrNull { candidateError[it] }!!
                        fix = candidates[best]
                        Diagnostics.log("Gyro fusion: sensor-to-head rotation ${best * 90} deg (errors ${candidateError.joinToString { "%.2f".format(it) }})")
                    }
                }
            }
        }
        prevOri.set(headPose.orientation)
        prevRel.set(rel.position)
        prevTs = ts
        frameTs = ts
    }

    /** Camera intrinsics of the CPU image (for hand tracking). */
    fun cpuIntrinsics(): CameraIntrinsics? {
        val f = lastFrame ?: return null
        val intr = f.camera.imageIntrinsics
        val fl = intr.focalLength; val pp = intr.principalPoint; val d = intr.imageDimensions
        return CameraIntrinsics(fl[0], fl[1], pp[0], pp[1], d[0], d[1])
    }

    /** CPU YUV image; caller must close it. */
    fun acquireCpuImage(): Image? = try { lastFrame?.acquireCameraImage() } catch (e: NotYetAvailableException) { null } catch (t: Throwable) { null }

    /** Latest 16-bit depth image (millimetres) mapped to the CPU image size; call the closer when done. */
    fun depthImage(cpuW: Int, cpuH: Int): Pair<com.hyperxr.handtracking.DepthImage, () -> Unit>? {
        if (!depthEnabled) return null
        val img = try { lastFrame?.acquireDepthImage16Bits() } catch (t: Throwable) { null } ?: return null
        val plane = img.planes[0]
        val d = com.hyperxr.handtracking.DepthImage(plane.buffer.order(ByteOrder.nativeOrder()), img.width, img.height, plane.rowStride, plane.pixelStride, cpuW, cpuH)
        return d to { img.close() }
    }

    /**
     * Latest depth as centimetres per byte (0 = no data, 255 = 2.55 m or farther) into [out],
     * reallocated when the size changes. Returns the buffer and its size, or null without depth.
     */
    fun depthCentimetres(out: ByteArray?): Triple<ByteArray, Int, Int>? {
        if (!depthEnabled) return null
        val img = try { lastFrame?.acquireDepthImage16Bits() } catch (t: Throwable) { null } ?: return null
        try {
            val w = img.width; val h = img.height
            val dst = if (out != null && out.size == w * h) out else ByteArray(w * h)
            val plane = img.planes[0]
            val buf = plane.buffer.order(ByteOrder.nativeOrder())
            val row = plane.rowStride; val px = plane.pixelStride
            for (y in 0 until h) {
                var o = y * row
                val d = y * w
                for (x in 0 until w) {
                    val mm = buf.getShort(o).toInt() and 0xffff
                    dst[d + x] = if (mm == 0) 0 else (mm / 10).coerceIn(1, 255).toByte()
                    o += px
                }
            }
            return Triple(dst, w, h)
        } finally { img.close() }
    }

    /**
     * Depth API points in tracking space: every [step]th pixel of the depth image, unprojected with
     * the CPU image intrinsics (the depth image covers the same view) and the camera pose. Up to
     * [out].size / 3 points, nearer than [maxM]; 0 without depth or tracking. GL thread.
     */
    fun depthPoints(out: FloatArray, step: Int = 4, maxM: Float = 2.5f): Int {
        if (!depthEnabled) return 0
        val f = lastFrame ?: return 0
        val intr = cpuIntrinsics() ?: return 0
        val cam = Pose()
        if (!cameraPose(cam)) return 0
        val img = try { f.acquireDepthImage16Bits() } catch (t: Throwable) { null } ?: return 0
        try {
            val w = img.width; val h = img.height
            val plane = img.planes[0]
            val buf = plane.buffer.order(ByteOrder.nativeOrder())
            val row = plane.rowStride; val px = plane.pixelStride
            val sx = intr.width.toFloat() / w; val sy = intr.height.toFloat() / h
            val p = Vec3(); val q = Vec3()
            var n = 0
            val cap = out.size / 3
            var y = step / 2
            while (y < h && n < cap) {
                var x = step / 2
                while (x < w && n < cap) {
                    val mm = buf.getShort(y * row + x * px).toInt() and 0xffff
                    if (mm != 0 && mm <= maxM * 1000) {
                        val d = mm / 1000f
                        val u = (x + 0.5f) * sx; val v = (y + 0.5f) * sy
                        q.set((u - intr.cx) * d / intr.fx, -(v - intr.cy) * d / intr.fy, -d)
                        cam.transformPoint(q, p)
                        out[n * 3] = p.x; out[n * 3 + 1] = p.y; out[n * 3 + 2] = p.z
                        n++
                    }
                    x += step
                }
                y += step
            }
            return n
        } finally { img.close() }
    }

    /**
     * An ARCore anchor at [p] (tracking space): ARCore keeps it attached to the real place as its
     * map of the room improves, so content placed on a real surface stays on it. Null when not
     * tracking.
     */
    fun anchorAt(p: Pose): com.google.ar.core.Anchor? {
        val s = session ?: return null
        if (trackingState != TrackingState.TRACKING) return null
        val w = origin.compose(p)
        return try {
            s.createAnchor(com.google.ar.core.Pose(floatArrayOf(w.position.x, w.position.y, w.position.z),
                floatArrayOf(w.orientation.x, w.orientation.y, w.orientation.z, w.orientation.w)))
        } catch (t: Throwable) { null }
    }

    /** Where [a] is now, in tracking space; false while ARCore is not tracking it. */
    fun anchorPose(a: com.google.ar.core.Anchor, out: Pose): Boolean {
        if (a.trackingState != TrackingState.TRACKING) return false
        val ap = a.pose
        val world = Pose(Vec3(ap.tx(), ap.ty(), ap.tz()), Quat(ap.qx(), ap.qy(), ap.qz(), ap.qw()))
        origin.inverse().compose(world, out)
        return true
    }

    /** Camera pose in tracking space (needed to place hands detected in the camera image). */
    fun cameraPose(out: Pose): Boolean {
        val f = lastFrame ?: return false
        if (f.camera.trackingState != TrackingState.TRACKING) return false
        val p = f.camera.pose
        val world = Pose(Vec3(p.tx(), p.ty(), p.tz()), Quat(p.qx(), p.qy(), p.qz(), p.qw()))
        origin.inverse().compose(world, out)
        return true
    }
}
