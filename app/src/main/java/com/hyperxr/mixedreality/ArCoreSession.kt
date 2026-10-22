package com.hyperxr.mixedreality

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
    override val description: String get() = "6DoF (ARCore${if (depthEnabled) " + Depth" else ""})"
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
    override val hasFrame: Boolean get() = lastFrame != null && lastFrame!!.timestamp != 0L

    private val headPose = Pose()
    private val origin = Pose()
    private var recenterPending = true
    private val quadUv = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private val texUv = FloatArray(8)
    private var texMatrixValid = false

    /** Must be called on the GL thread (creates the camera texture). */
    fun create(useDepth: Boolean): Capability {
        return try {
            val s = Session(activity)
            val config = Config(s).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.FIXED
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            }
            val depthSupported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            if (useDepth && depthSupported) config.depthMode = Config.DepthMode.AUTOMATIC
            depthEnabled = useDepth && depthSupported
            s.configure(config)
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
        // pose relative to the recentred origin
        val inv = origin.inverse()
        inv.compose(headPose, out)
    }

    override fun recenter() { recenterPending = true }

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
