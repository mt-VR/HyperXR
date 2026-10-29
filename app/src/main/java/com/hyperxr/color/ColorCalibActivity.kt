package com.hyperxr.color

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
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hyperxr.HyperXRApp
import com.hyperxr.handtracking.CameraIntrinsics
import com.hyperxr.handtracking.HandTrackingService
import com.hyperxr.i18n.I18n
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The colour controller's calibrator: show the controller to the camera, freeze the picture, tap
 * each coloured spot (its colour, place and size are measured around the tap), tap where the
 * pointer should go (or leave it pointing forward), say how far apart the first two spots really
 * are, and save. "Testar" then tracks it live with the same code the headset uses.
 */
class ColorCalibActivity : Activity() {
    private val hands by lazy { HandTrackingService(this) }
    private val tracker by lazy { ColorTracker(this) }
    private val camThread = HandlerThread("hx-colorcalib").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private var device: CameraDevice? = null
    private var reader: ImageReader? = null
    private lateinit var view: CalibView
    private lateinit var status: TextView
    private val buttons = HashMap<String, Button>()

    private enum class Mode { SPOTS, AIM }
    private var mode = Mode.SPOTS
    private var testing = false
    private val taps = ArrayList<ColorCalibration.Tap>()
    private val tapPx = ArrayList<FloatArray>()      // where each spot is in the frozen picture (0..1)
    private val buttonFlags = ArrayList<Boolean>()
    private var aim: FloatArray? = null              // normalised camera coordinates
    private var aimPx: FloatArray? = null
    private var distanceCm = 10
    private var side = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // start with the hand that still has no controller
        side = if (ColorControllerStore.load(this, 1).usable && !ColorControllerStore.load(this, 0).usable) 0 else 1
        view = CalibView(this)
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(16, 12, 16, 12); setBackgroundColor(0x99000000.toInt()) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12) }
        fun button(key: String, onClick: () -> Unit) = Button(this).apply { isAllCaps = false; setOnClickListener {
            // a button that wrote its own message (saved, an error) keeps it; the others show the next step
            val before = status.text
            onClick()
            if (status.text === before) refresh() else { refreshButtons(); view.invalidate() }
        } }.also { buttons[key] = it; column.addView(it) }
        button("freeze") { if (view.frozen) restart() else view.frozen = true }
        button("mode") { mode = if (mode == Mode.SPOTS) Mode.AIM else Mode.SPOTS }
        button("forward") { aim = null; aimPx = null }
        button("undo") { if (taps.isNotEmpty()) { taps.removeAt(taps.size - 1); tapPx.removeAt(tapPx.size - 1); buttonFlags.removeAt(buttonFlags.size - 1) } }
        button("button") { if (buttonFlags.isNotEmpty()) buttonFlags[buttonFlags.size - 1] = !buttonFlags.last() }
        val dist = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply { text = "−"; setOnClickListener { distanceCm = (distanceCm - 1).coerceAtLeast(1); refresh() } }.also { dist.addView(it) }
        TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(12, 0, 12, 0) }.also { dist.addView(it); distText = it }
        Button(this).apply { text = "+"; setOnClickListener { distanceCm = (distanceCm + 1).coerceAtMost(60); refresh() } }.also { dist.addView(it) }
        column.addView(dist)
        button("side") { side = 1 - side; restart() }
        button("save") { save() }
        button("test") { testing = !testing; if (testing) { view.frozen = false; tracker.use(ColorControllerStore.loadAll(this)) } }
        val scroll = ScrollView(this).apply { addView(column) }
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.addView(scroll, FrameLayout.LayoutParams(dp(250), -1, Gravity.END))
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply { rightMargin = dp(250) })
        setContentView(root)
        refresh()

        val s = HyperXRApp.instance.settings.current
        hands.configure(s.handTracking, s.performance.handInferenceHz)
        hands.start(s.handTracking.backend)
        hands.colorTap = { bmp, intr, _, _ ->
            view.post(bmp, intr)
            if (testing) tracker.offer(bmp, intr, null)
        }
        tracker.debugListener = { d -> view.debug = d; view.postInvalidate() }
    }

    private var distText: TextView? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun restart() { view.frozen = false; taps.clear(); tapPx.clear(); buttonFlags.clear(); aim = null; aimPx = null }

    private fun refresh() {
        refreshButtons()
        status.text = I18n.t(when {
            testing -> "Teste: mexa os controles. Os pontos achados aparecem com um anel e cada apontador com uma linha."
            taps.isEmpty() -> "Segure o controle de frente para a câmera, a uns 40 cm, com todos os pontos coloridos à vista, e toque no primeiro ponto (a imagem congela)."
            mode == Mode.AIM -> "Toque onde o apontador deve ir (por exemplo a ponta do controle)."
            taps.size < 3 -> "Toque nos outros pontos. Com 3 ou mais pontos de cores diferentes o rastreamento é 6DoF completo."
            else -> "Pronto para salvar. Meça a distância real entre os pontos 1 e 2 e ajuste acima."
        })
        view.invalidate()
    }

    private fun refreshButtons() {
        fun set(k: String, t: String) { buttons[k]?.text = I18n.t(t) }
        set("freeze", if (view.frozen) "Recomeçar (ao vivo)" else "Congelar imagem")
        set("mode", if (mode == Mode.SPOTS) "Tocar marca: pontos" else "Tocar marca: apontador")
        set("forward", "Apontar para a frente")
        set("undo", "Desfazer ponto")
        set("button", "Último ponto é botão")
        set("side", if (side == 1) "Calibrando: controle direito" else "Calibrando: controle esquerdo")
        set("save", "Salvar")
        set("test", if (testing) "Parar teste" else "Testar")
        distText?.text = I18n.t("Pontos 1 e 2: {0} cm".replace("{0}", distanceCm.toString()))
    }

    private fun save() {
        val m = ColorCalibration.build(taps.mapIndexed { i, t -> ColorCalibration.Tap(t.marker.copy(button = buttonFlags[i]), t.u, t.v, t.r) }, distanceCm / 100f, aim, side)
        if (m == null) { status.text = I18n.t("Marque pelo menos um ponto antes de salvar."); return }
        ColorControllerStore.save(this, m)
        tracker.use(ColorControllerStore.loadAll(this))
        HyperXRApp.instance.settings.update { it.copy(controllers = it.controllers.copy(colorController = true)) }
        status.text = I18n.t("Salvo. O controle por cor foi ligado; toque em Testar para conferir.")
    }

    /** A tap on the frozen picture at [nx], [ny] (0..1): measure the spot there, or set the pointer. */
    private fun onTap(nx: Float, ny: Float) {
        if (testing) return
        view.frozen = true
        val f = view.frame ?: return
        val intr = view.intr ?: return
        val px = (nx * f.width).toInt().coerceIn(0, f.width - 1); val py = (ny * f.height).toInt().coerceIn(0, f.height - 1)
        if (mode == Mode.AIM) {
            aim = floatArrayOf((nx * intr.width - intr.cx) / intr.fx, (ny * intr.height - intr.cy) / intr.fy)
            aimPx = floatArrayOf(nx, ny)
            mode = Mode.SPOTS
            refresh(); return
        }
        // both controllers' colours are sorted in one pass, 8 at most
        val other = ColorControllerStore.load(this, 1 - side).markers
        if (taps.size + other.size >= ColorTracker.MAX) {
            status.text = I18n.t(if (other.isEmpty()) "No máximo 8 pontos." else "Os dois controles juntos podem ter no máximo 8 pontos."); return
        }
        // the colour: a 9x9 patch around the tap
        val x0 = (px - 4).coerceAtLeast(0); val y0 = (py - 4).coerceAtLeast(0)
        val w = minOf(9, f.width - x0); val h = minOf(9, f.height - y0)
        val patch = IntArray(w * h); f.getPixels(patch, 0, w, x0, y0, w, h)
        val marker = ColorMatch.sample(patch)
        // the spot: every connected pixel of that colour around the tap (within 100 px)
        val spot = grow(f, px, py, marker)
        val cx = spot?.get(0) ?: px.toFloat(); val cy = spot?.get(1) ?: py.toFloat()
        val rPx = spot?.get(2) ?: 4f
        // the frame may be smaller than the camera's size: scale to the intrinsics' pixels
        val sx = intr.width.toFloat() / f.width
        val u = ((cx + 0.5f) * sx - intr.cx) / intr.fx
        val v = ((cy + 0.5f) * sx - intr.cy) / intr.fy
        taps += ColorCalibration.Tap(marker, u, v, rPx * sx / intr.fx)
        tapPx += floatArrayOf((cx + 0.5f) / f.width, (cy + 0.5f) / f.height, rPx / f.width)
        buttonFlags += false
        refresh()
        fun close(o: ColorMarker) = ColorMatch.hueDistance(o.h, marker.h) < 20f && marker.s >= 0.22f && o.s >= 0.22f
        if (taps.dropLast(1).any { close(it.marker) })
            status.text = I18n.t("Atenção: esta cor é parecida com a de outro ponto; use cores bem diferentes.")
        else if (other.any { close(it) })
            status.text = I18n.t("Atenção: esta cor já está no outro controle; use cores diferentes em cada controle.")
        if (spot == null) status.text = I18n.t("Não achei a mancha de cor aí; o ponto foi marcado só onde você tocou.")
    }

    /** Centroid x, y and radius (pixels) of the blob of [m]'s colour under ([px], [py]); null when there isn't one. */
    private fun grow(f: Bitmap, px: Int, py: Int, m: ColorMarker): FloatArray? {
        val r = 100
        val x0 = (px - r).coerceAtLeast(0); val y0 = (py - r).coerceAtLeast(0)
        val x1 = (px + r).coerceAtMost(f.width - 1); val y1 = (py + r).coerceAtMost(f.height - 1)
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        val pix = IntArray(w * h); f.getPixels(pix, 0, w, x0, y0, w, h)
        val hsv = FloatArray(3)
        val ok = BooleanArray(w * h) { i -> val p = pix[i]; ColorMatch.hsv(((p shr 16) and 255) / 255f, ((p shr 8) and 255) / 255f, (p and 255) / 255f, hsv); ColorMatch.matches(hsv, m) }
        // start from the matching pixel nearest the tap
        var start = -1; var best = Int.MAX_VALUE
        for (dy in -4..4) for (dx in -4..4) {
            val x = px - x0 + dx; val y = py - y0 + dy
            if (x in 0 until w && y in 0 until h && ok[y * w + x] && dx * dx + dy * dy < best) { best = dx * dx + dy * dy; start = y * w + x }
        }
        if (start < 0) return null
        val seen = BooleanArray(w * h); val stack = IntArray(w * h); var sp = 0
        stack[sp++] = start; seen[start] = true
        var n = 0; var sx = 0L; var sy = 0L
        while (sp > 0) {
            val p = stack[--sp]; val x = p % w; val y = p / w
            n++; sx += x; sy += y
            for ((dx, dy) in NEIGHBOURS) {
                val qx = x + dx; val qy = y + dy
                if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                val q = qy * w + qx
                if (!seen[q] && ok[q]) { seen[q] = true; stack[sp++] = q }
            }
        }
        if (n < 4) return null
        return floatArrayOf(x0 + sx.toFloat() / n, y0 + sy.toFloat() / n, sqrt(n / PI.toFloat()))
    }

    override fun onResume() { super.onResume(); openCamera() }
    override fun onPause() { super.onPause(); device?.close(); device = null; reader?.close(); reader = null }
    override fun onDestroy() {
        super.onDestroy()
        hands.colorTap = null; tracker.debugListener = null
        tracker.shutdown(); hands.shutdown(); camThread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cm = getSystemService(CameraManager::class.java)
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK }
        } catch (t: Throwable) { null } ?: run { status.text = I18n.t("Sem câmera traseira ou sem permissão de câmera"); return }
        val ch = cm.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val size = map.getOutputSizes(ImageFormat.YUV_420_888).minByOrNull { abs(it.width - 640) + abs(it.height - 480) } ?: return
        val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val fx = if (sensor != null && focal != null) size.width * focal / sensor.width else size.width / (2f * kotlin.math.tan(0.55f))
        val intr = CameraIntrinsics(fx, fx, size.width / 2f, size.height / 2f, size.width, size.height)
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
                        override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { status.text = I18n.t("Falha ao configurar a câmera") } }
                    }, camHandler)
                }
                override fun onDisconnected(d: CameraDevice) { d.close() }
                override fun onError(d: CameraDevice, e: Int) { d.close() }
            }, camHandler)
        } catch (t: Throwable) { status.text = "Camera: ${t.message}" }
    }

    private inner class CalibView(ctx: Context) : View(ctx) {
        @Volatile var frozen = false
        @Volatile var debug: ColorTracker.Debug? = null
        var frame: Bitmap? = null; private set
        var intr: CameraIntrinsics? = null; private set
        private val lock = Any()
        private val dst = RectF(); private val src = Rect()
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f; isFakeBoldText = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(64, 224, 208); strokeWidth = 6f }
        private val sideLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; isFakeBoldText = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        private val lineLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 170, 60); strokeWidth = 6f }

        /** The hand tracking thread: copy the frame unless the picture is frozen. */
        fun post(b: Bitmap, i: CameraIntrinsics) {
            if (frozen) return
            synchronized(lock) {
                val f = frame?.takeIf { it.width == b.width && it.height == b.height } ?: Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { frame = it }
                Canvas(f).drawBitmap(b, 0f, 0f, null)
                intr = i
            }
            postInvalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && dst.width() > 0f && dst.contains(e.x, e.y)) {
                synchronized(lock) { onTap((e.x - dst.left) / dst.width(), (e.y - dst.top) / dst.height()) }
            }
            return true
        }

        private fun x(n: Float) = dst.left + n * dst.width()
        private fun y(n: Float) = dst.top + n * dst.height()

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.BLACK)
            synchronized(lock) {
                val f = frame ?: return
                val avail = width - dp(250)
                val s = minOf(avail.toFloat() / f.width, height.toFloat() / f.height)
                val w = f.width * s; val h = f.height * s
                dst.set((avail - w) / 2, (height - h) / 2, (avail + w) / 2, (height + h) / 2)
                src.set(0, 0, f.width, f.height)
                c.drawBitmap(f, src, dst, null)
            }
            if (testing) {
                val d = debug ?: return
                val all = tracker.model(0).markers + tracker.model(1).markers
                val leftCount = tracker.model(0).markers.size
                for (i in 0 until d.spots.size / 2) {
                    if (d.spots[i * 2].isNaN()) continue
                    val mk = all.getOrNull(i) ?: continue
                    ring.color = Color.HSVToColor(floatArrayOf(mk.h, mk.s.coerceAtLeast(0.6f), 1f))
                    c.drawCircle(x(d.spots[i * 2]), y(d.spots[i * 2 + 1]), 26f, ring)
                    // numbered per controller, in its pointer's colour (orange left, teal right)
                    val name = if (d.owner[i] == 0) "${i + 1}" else "${i + 1 - leftCount}"
                    sideLabel.color = if (d.owner[i] == 0) lineLeft.color else line.color
                    c.drawText(name, x(d.spots[i * 2]) + 28f, y(d.spots[i * 2 + 1]), sideLabel)
                }
                for (sd in 0..1) {
                    val a = d.center[sd]; val b = d.tip[sd]
                    if (a != null && b != null) c.drawLine(x(a[0]), y(a[1]), x(b[0]), y(b[1]), if (sd == 0) lineLeft else line)
                    if (!tracker.model(sd).usable) continue
                    val head = I18n.t(if (sd == 0) "Esquerdo" else "Direito")
                    c.drawText(head + ": " + I18n.t("{0} · erro {1}".replace("{0}", if (d.dof[sd] >= 6) "6DoF" else if (d.dof[sd] > 0) "${d.dof[sd]}DoF" else "—")
                        .replace("{1}", "%.3f".format(d.error[sd]))) + if (d.pressed[sd]) "  ·  " + I18n.t("botão apertado") else "",
                        dst.left + 16f, dst.top + 40f + sd * 40f, label)
                }
                c.drawText(if (d.gpu) "GPU" else "CPU", dst.left + 16f, dst.top + 120f, label)
                return
            }
            for ((i, p) in tapPx.withIndex()) {
                val mk = taps[i].marker
                fill.color = Color.HSVToColor(floatArrayOf(mk.h, mk.s, mk.v))
                ring.color = Color.WHITE
                val r = (p[2] * dst.width()).coerceAtLeast(14f)
                c.drawCircle(x(p[0]), y(p[1]), r, ring)
                c.drawCircle(x(p[0]) + r + 22f, y(p[1]) - 10f, 12f, fill)
                c.drawText("${i + 1}" + if (buttonFlags[i]) " " + I18n.t("botão") else "", x(p[0]) + r + 40f, y(p[1]), label)
            }
            val ap = aimPx
            if (ap != null && tapPx.isNotEmpty()) {
                val cx = tapPx.map { it[0] }.average().toFloat(); val cy = tapPx.map { it[1] }.average().toFloat()
                c.drawLine(x(cx), y(cy), x(ap[0]), y(ap[1]), line)
                c.drawCircle(x(ap[0]), y(ap[1]), 10f, line)
            }
        }
    }

    private companion object {
        val NEIGHBOURS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
