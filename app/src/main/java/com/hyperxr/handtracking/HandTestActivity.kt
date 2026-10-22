package com.hyperxr.handtracking

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.View
import com.hyperxr.HyperXRApp
import com.hyperxr.settings.InferenceBackend
import kotlin.math.atan

/**
 * Hand tracking test outside the headset: the exact frame MediaPipe analysed, the skeleton it
 * found, the tracker state per hand and live numbers (analysed fps, detection rate, inference time).
 * Uses the same service, settings and back camera as the XR session.
 */
class HandTestActivity : Activity() {
    private val hands by lazy { HandTrackingService(this) }
    private val camThread = HandlerThread("hx-handtest").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private var device: CameraDevice? = null
    private var reader: ImageReader? = null
    private lateinit var view: OverlayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = OverlayView(this)
        setContentView(view)
        val s = HyperXRApp.instance.settings.current
        hands.configure(s.handTracking, s.performance.handInferenceHz)
        hands.start(s.handTracking.backend)
        hands.debugListener = { bmp, lms -> view.post(bmp, lms) }
    }

    override fun onResume() { super.onResume(); openCamera() }
    override fun onPause() { super.onPause(); device?.close(); device = null; reader?.close(); reader = null }
    override fun onDestroy() { super.onDestroy(); hands.debugListener = null; hands.shutdown(); camThread.quitSafely() }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cm = getSystemService(CameraManager::class.java)
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK }
        } catch (t: Throwable) { null } ?: run { view.message = "Sem câmera traseira ou sem permissão de câmera"; return }
        val ch = cm.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val size = map.getOutputSizes(ImageFormat.YUV_420_888).minByOrNull { kotlin.math.abs(it.width - 640) + kotlin.math.abs(it.height - 480) } ?: return
        val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val fx = if (sensor != null && focal != null) size.width * focal / sensor.width else size.width / (2f * kotlin.math.tan(0.55f))
        val intr = CameraIntrinsics(fx, fx, size.width / 2f, size.height / 2f, size.width, size.height)
        view.fovText = "FOV horizontal %.0f°".format(Math.toDegrees(2.0 * atan(size.width / (2.0 * fx))))
        @Suppress("DEPRECATION")
        val rot180 = windowManager.defaultDisplay.rotation == Surface.ROTATION_270
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            hands.submit(img, intr, null, null, rotation180 = rot180)
        }, camHandler)
        reader = r
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    @Suppress("DEPRECATION")
                    d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }
                            s.setRepeatingRequest(req.build(), null, camHandler)
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { view.message = "Falha ao configurar a câmera" }
                    }, camHandler)
                }
                override fun onDisconnected(d: CameraDevice) { d.close() }
                override fun onError(d: CameraDevice, e: Int) { view.message = "Erro de câmera $e"; d.close() }
            }, camHandler)
        } catch (t: Throwable) { view.message = "Câmera: ${t.message}" }
    }

    private inner class OverlayView(ctx: Context) : View(ctx) {
        @Volatile var message: String? = null
        var fovText = ""
        private var frame: Bitmap? = null
        private val lms = ArrayList<FloatArray>()
        private val lock = Any()
        private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f; color = Color.rgb(160, 140, 255) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        private val dst = RectF()
        private val src = Rect()

        /** Inference thread: copy the analysed frame and the landmarks. */
        fun post(b: Bitmap, found: List<FloatArray>) {
            synchronized(lock) {
                val f = frame?.takeIf { it.width == b.width && it.height == b.height } ?: Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { frame = it }
                Canvas(f).drawBitmap(b, 0f, 0f, null)
                lms.clear(); lms.addAll(found)
            }
            postInvalidate()
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.BLACK)
            synchronized(lock) {
                val f = frame
                if (f != null) {
                    val s = minOf(width.toFloat() / f.width, height.toFloat() / f.height)
                    val w = f.width * s; val h = f.height * s
                    dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                    src.set(0, 0, f.width, f.height)
                    c.drawBitmap(f, src, dst, null)
                    for (p in lms) {
                        for (b in HandJoint.BONES) {
                            c.drawLine(dst.left + p[b[0] * 3] * w, dst.top + p[b[0] * 3 + 1] * h, dst.left + p[b[1] * 3] * w, dst.top + p[b[1] * 3 + 1] * h, bone)
                        }
                        for (j in 0 until HandJoint.COUNT) c.drawCircle(dst.left + p[j * 3] * w, dst.top + p[j * 3 + 1] * h, 7f, dot)
                    }
                }
            }
            var y = 48f
            fun line(t: String) { c.drawText(t, 24f, y, text); y += 44f }
            line("Teste de hand tracking — ${hands.status} (${hands.backendInUse})")
            line(hands.stats)
            for ((side, t) in hands.trackers) {
                val st = t.state
                line("${if (side == Handedness.LEFT) "Esquerda" else "Direita"}: ${st.state} · ${st.gesture} · pinça ${"%.0f".format(st.pinchRatio * 100)}% da palma (fecha < ${"%.0f".format(t.pinchRatio * 100)}%) · ${st.depthSource}")
            }
            if (fovText.isNotEmpty()) line(fovText)
            message?.let { line(it) }
            postInvalidateDelayed(200)
        }
    }
}
