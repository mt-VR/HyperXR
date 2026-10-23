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
            val depthSupported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            if (useDepth && depthSupported) config.depthMode = Config.DepthMode.AUTOMATIC
            depthEnabled = useDepth && depthSupported
            // 60 fps camera where ARCore offers it: twice the pose and hand updates, half the age
            var chosen60 = false
            runCatching {
                val f60 = com.google.ar.core.CameraConfigFilter(s).setTargetFps(java.util.EnumSet.of(com.google.ar.core.CameraConfig.TargetFps.TARGET_FPS_60))
                val c60 = s.getSupportedCameraConfigs(f60)
                val pool = if (c60.isNotEmpty()) c60 else if (lightweight)
                    s.getSupportedCameraConfigs(com.google.ar.core.CameraConfigFilter(s).setTargetFps(java.util.EnumSet.of(com.google.ar.core.CameraConfig.TargetFps.TARGET_FPS_30))) else emptyList()
                // streaming: smallest CPU image; otherwise the sharpest passthrough texture
                val pick = if (lightweight) pool.minByOrNull { it.imageSize.width * it.imageSize.height }
                else pool.maxByOrNull { it.textureSize.width * it.textureSize.height }
                pick?.let { s.cameraConfig = it; chosen60 = it.fpsRange.upper >= 60 }
            }
            try { s.configure(config) } catch (t: Throwable) {
                if (!chosen60) throw t
                // 60 fps and this configuration (e.g. depth) do not mix on this phone: back to 30
                Diagnostics.log("ARCore 60 fps refused (${t.message}); using 30 fps")
                s.getSupportedCameraConfigs(com.google.ar.core.CameraConfigFilter(s).setTargetFps(java.util.EnumSet.of(com.google.ar.core.CameraConfig.TargetFps.TARGET_FPS_30)))
                    .firstOrNull()?.let { s.cameraConfig = it }
                chosen60 = false
                s.configure(config)
            }
            fps60 = chosen60
            textureId = Gl.externalTexture()
            s.setCameraTextureName(textureId)
            session = s
            if (depthSupported) Capability.yes("ARCore Depth API supported") else Capability.no("ARCore Depth API not supported on this device")
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
            Diagnostics.log("ARCore session failed: $error")
            Capability.no("ARCore session failed: ${t.message}")
        }
    }

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        session?.setDisplayGeometry(rotation, width, height)
        imageAspect = width.toFloat() / height.coerceAtLeast(1)
        texMatrixValid = false
    }

    override fun start() { try { session?.resume() } catch (t: Throwable) { error = t.message; Diagnostics.log("ARCore resume failed: ${t.message}") } }
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
            headPose.position.set(p.tx(), p.ty(), p.tz())
            headPose.orientation.set(p.qx(), p.qy(), p.qz(), p.qw())
            onArPose(frame.timestamp)
            if (recenterPending) {
                // origin so that the current head yaw faces -Z and the floor estimate is 1.6 m below the eyes
                val yaw = com.hyperxr.input.ControllerPoseFusion.yawOnly(headPose.orientation, Quat())
                origin.orientation.set(yaw)
                origin.position.set(headPose.position.x, headPose.position.y - 1.6f, headPose.position.z)
                recenterPending = false
            }
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
