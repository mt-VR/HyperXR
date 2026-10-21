package com.hyperxr.mixedreality

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.diagnostics.Stage
import com.hyperxr.handtracking.CameraIntrinsics
import com.hyperxr.rendering.CameraImageSource
import com.hyperxr.rendering.Gl
import kotlin.math.atan

/**
 * Video see-through with Camera2 when ARCore is not available: one stream to a SurfaceTexture
 * (displayed as the MR background) and one small YUV stream for hand tracking. This is a mono
 * camera feed with 3DoF head tracking; latency is measured from the sensor timestamp.
 */
class CameraPassthrough(private val context: Context) : CameraImageSource {
    override var textureId = 0; private set
    override val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    override var fovX = 1.1f; private set
    override var fovY = 0.85f; private set
    @Volatile override var hasFrame = false; private set

    var error: String? = null; private set
    var intrinsics: CameraIntrinsics? = null; private set
    private val stTransform = FloatArray(16)
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var reader: ImageReader? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private val thread = HandlerThread("hx-camera").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var frameAvailable = false
    private var realtimeTimestamps = false

    /** Receives YUV frames for hand tracking (on the camera thread). Must close the image. */
    var onCpuFrame: ((Image, CameraIntrinsics) -> Unit)? = null

    /** GL thread: create the OES texture, then open the camera. */
    @SuppressLint("MissingPermission")
    fun start(cpuSize: Size = Size(640, 480)) {
        textureId = Gl.externalTexture()
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        } ?: run { error = "No back camera"; return }
        val ch = cm.getCameraCharacteristics(id)
        realtimeTimestamps = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width * 9 == it.height * 16 || it.width * 3 == it.height * 4 }
            .minByOrNull { kotlin.math.abs(it.width - 1280) } ?: Size(1280, 720)
        val cpu = map.getOutputSizes(ImageFormat.YUV_420_888).minByOrNull { kotlin.math.abs(it.width - cpuSize.width) + kotlin.math.abs(it.height - cpuSize.height) } ?: cpuSize

        // field of view from the physical sensor size and focal length
        val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        if (sensor != null && focal != null) {
            val aspect = previewSize.width.toFloat() / previewSize.height
            fovX = 2f * atan(sensor.width / (2f * focal))
            fovY = 2f * atan((sensor.width / aspect) / (2f * focal))
            val fxPx = cpu.width * focal / sensor.width
            intrinsics = CameraIntrinsics(fxPx, fxPx, cpu.width / 2f, cpu.height / 2f, cpu.width, cpu.height)
        }

        surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(previewSize.width, previewSize.height)
            setOnFrameAvailableListener({ frameAvailable = true }, handler)
        }
        surface = Surface(surfaceTexture)
        reader = ImageReader.newInstance(cpu.width, cpu.height, ImageFormat.YUV_420_888, 3).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val intr = intrinsics
                val cb = onCpuFrame
                if (cb != null && intr != null) cb(img, intr) else img.close()
            }, handler)
        }
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                val targets = listOf(surface!!, reader!!.surface)
                @Suppress("DEPRECATION")
                d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            targets.forEach { addTarget(it) }
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                        }
                        s.setRepeatingRequest(req.build(), null, handler)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) { error = "Camera session configuration failed" }
                }, handler)
            }
            override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
            override fun onError(d: CameraDevice, e: Int) { error = "Camera error $e"; d.close(); device = null }
        }, handler)
    }

    /** GL thread: latch the newest camera image. */
    fun update() {
        val st = surfaceTexture ?: return
        if (!frameAvailable) return
        frameAvailable = false
        st.updateTexImage()
        st.getTransformMatrix(stTransform)
        // quad UV has a top-left origin; SurfaceTexture expects bottom-left
        val flip = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        Matrix.multiplyMM(texMatrix, 0, stTransform, 0, flip, 0)
        hasFrame = true
        if (realtimeTimestamps) {
            Diagnostics.record(Stage.CAMERA_CAPTURE, SystemClock.elapsedRealtimeNanos() - st.timestamp)
        }
    }

    fun stop() {
        try { session?.close() } catch (_: Throwable) {}
        device?.close(); device = null
        reader?.close(); reader = null
        surface?.release(); surface = null
        surfaceTexture?.release(); surfaceTexture = null
        hasFrame = false
    }

    fun shutdown() { stop(); thread.quitSafely() }
}
