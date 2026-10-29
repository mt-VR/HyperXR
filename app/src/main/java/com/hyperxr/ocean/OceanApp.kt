package com.hyperxr.ocean

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.handtracking.CameraIntrinsics
import com.hyperxr.handtracking.DepthSampler
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.mixedreality.ArCoreSession
import com.hyperxr.rendering.Background
import com.hyperxr.rendering.EyeView
import com.hyperxr.rendering.FrameScene
import com.hyperxr.rendering.ScenePainter
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.xr.XrContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ocean: your room goes down into the sea.
 *
 * While the room is still there, the depth sensor (ARCore Depth) maps it as you look around:
 * the points show in false colour, and the floor, walls and ceiling are measured from them. On
 * "Afundar a sala" the camera's last photo is laid over the room's depth (a 3D copy of what you
 * see), and that copy sinks below the surface while a sea reaches to the horizon all around.
 * A quick-settings menu, like Android's, sets the light (night to midday), the volume, rain,
 * thunderstorms and rough sea. Everything is drawn with HyperXR's own shaders and the sound is
 * synthesised (OceanRenderer, OceanSynth).
 */
class OceanApp(private val ctx: XrContext, private val onClose: () -> Unit) : ScenePainter {
    enum class Phase { ROOM, SINKING, SEA, RISING }

    val renderer = OceanRenderer()
    private val audio = OceanAudio()
    private val stormClock = StormClock()
    val scan = RoomScan()
    val panel = OceanPanel(this)
    private val prefs = ctx.activity.getSharedPreferences("ocean", Context.MODE_PRIVATE)
    private val sky = SkyState()

    var phase = Phase.ROOM; private set
    private var phaseT = 0f

    // quick settings
    var light = prefs.getFloat("light", 0.75f); set(v) { field = v.coerceIn(0f, 1f); prefs.edit().putFloat("light", field).apply() }
    var volume = prefs.getFloat("volume", 0.7f); set(v) { field = v.coerceIn(0f, 1f); prefs.edit().putFloat("volume", field).apply() }
    var rain = prefs.getBoolean("rain", false); set(v) { field = v; prefs.edit().putBoolean("rain", v).apply() }
    var thunder = prefs.getBoolean("thunder", false); set(v) { field = v; prefs.edit().putBoolean("thunder", v).apply() }
    var rough = prefs.getBoolean("rough", false); set(v) { field = v; prefs.edit().putBoolean("rough", v).apply() }
    var showDepth = true

    // what the depth sensor found
    var floorY = Float.NaN; private set
    var ceilY = Float.NaN; private set
    var wallDistance = Float.NaN; private set
    var depthNote = ""; private set
    private var floorMeasured = false
    private val depthBuf = FloatArray(6000 * 3)
    private var scanTimer = 0f
    private var pointsTimer = 0f

    // smoothed weather
    private var weather = 0f; private var seaState = 0.15f; private var rainLevel = 0f; private var packedSea = -1f
    private var sunAzimuth = 0f
    private var eyeY = 1.6f
    private var seaLevel = 0f

    /** A photo of the room with its depth, made on the hand-tracking thread for the GL thread. */
    private class Snapshot(val photo: Bitmap, val positions: FloatArray, val gw: Int, val gh: Int, val eye: Vec3)
    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var waitingForPhoto = false
    private var photoWaitT = 0f

    private val ar get() = ctx.camera as? ArCoreSession

    fun initGl() {
        renderer.init()
        audio.start()
    }

    /** Puts the menu in front of the user, a little to the left and low, like a phone in the hand. */
    fun place(head: Pose, yaw: Float) {
        sunAzimuth = yaw + 0.45f
        eyeY = head.position.y
        panel.placeInFront(head, 0.95f, -0.18f, -18f)
    }

    // ---------------------------------------------------------------- actions
    fun sink() {
        if (phase != Phase.ROOM) return
        val hands = ctx.hands
        // the room is copied only when the camera is what the user sees
        if (ctx.scene.background == Background.PASSTHROUGH && hands != null && hands.status == "rodando") {
            waitingForPhoto = true; photoWaitT = 0f
            val floor = currentFloor(); val ceil = currentCeiling(floor); val wall = currentWall()
            hands.frameTap = { bmp, intr, pose, depth ->
                if (waitingForPhoto && snapshot == null) {
                    snapshot = buildSnapshot(bmp, intr, pose, depth, floor, ceil, wall)
                    hands.frameTap = null
                }
            }
        } else startSinking(null)
        panel.dirty = true
    }

    fun raise() {
        if (phase != Phase.SEA) return
        if (renderer.hasRoom) { phase = Phase.RISING; phaseT = 0f; renderer.roomVisible = true } else phase = Phase.ROOM
        panel.dirty = true
    }

    fun rescan() { scan.reset(); floorMeasured = false; floorY = Float.NaN; ceilY = Float.NaN; wallDistance = Float.NaN; panel.dirty = true }

    private fun startSinking(s: Snapshot?) {
        waitingForPhoto = false
        if (s != null) {
            renderer.setRoom(s.photo, s.positions, s.gw, s.gh, s.eye.x, s.eye.y, s.eye.z)
            s.photo.recycle()
            renderer.roomVisible = true
        } else renderer.clearRoom()
        renderer.roomDrop = 0f
        phase = Phase.SINKING; phaseT = 0f
        panel.dirty = true
    }

    // ---------------------------------------------------------------- the room's depth
    private fun currentFloor() = if (floorY.isNaN()) eyeY - 1.6f else floorY
    private fun currentCeiling(floor: Float) = if (ceilY.isNaN()) floor + 2.6f else ceilY
    private fun currentWall() = if (wallDistance.isNaN()) 2.6f else wallDistance

    /**
     * The photo laid over the room: a grid over the image, each vertex pushed out along its
     * camera ray to the measured depth, or (no measurement there) to the floor, ceiling or walls.
     * Runs on the hand-tracking thread: the bitmap and depth are only valid during the call.
     */
    private fun buildSnapshot(bmp: Bitmap, intr: CameraIntrinsics, pose: Pose, depth: DepthSampler?, floor: Float, ceil: Float, wall: Float): Snapshot {
        val gw = 48; val gh = 36
        val pos = FloatArray(gw * gh * 3)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val sx = w / intr.width; val sy = h / intr.height
        val dir = Vec3(); val world = Vec3()
        val o = pose.position
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val px = gx / (gw - 1f) * (w - 1); val py = gy / (gh - 1f) * (h - 1)
            // camera ray through the pixel (x right, y up, -z forward)
            val cx = (px / sx - intr.cx) / intr.fx; val cy = -(py / sy - intr.cy) / intr.fy
            val d = depth?.depthAt(px / sx, py / sy) ?: Float.NaN
            if (d.isFinite() && d > 0.15f && d < 8f) {
                pose.transformPoint(dir.set(cx * d, cy * d, -d), world)
            } else {
                val l = sqrt(cx * cx + cy * cy + 1f)
                pose.orientation.rotate(dir.set(cx / l, cy / l, -1f / l), world)
                val t = RoomShape.distance(o.x, o.y, o.z, world.x, world.y, world.z, floor, ceil, wall)
                world.set(o.x + world.x * t, o.y + world.y * t, o.z + world.z * t)
            }
            val i = (gy * gw + gx) * 3
            pos[i] = world.x; pos[i + 1] = world.y; pos[i + 2] = world.z
        }
        return Snapshot(bmp.copy(Bitmap.Config.ARGB_8888, false), pos, gw, gh, Vec3().set(o))
    }

    /** Depth points from ARCore a few times a second while the room is there. */
    /**
     * Measures the room a few times a second while it is still there: the floor from ARCore's
     * planes (found with or without the depth sensor), and with the Depth API also the points
     * shown in false colour, the walls and the ceiling (and the floor when no plane is found).
     */
    private fun scanDepth(dt: Float) {
        val a = ar
        if (a == null) { depthNote = "Sem ARCore: o chão é estimado pela sua altura (1,6 m)."; return }
        a.wantPlanes(PLANES, true)
        scanTimer += dt
        if (scanTimer < 0.3f) return
        scanTimer = 0f
        if (a.depthEnabled) {
            val n = a.depthPoints(depthBuf, step = 8, maxM = 4.5f)
            if (n > 0) scan.add(depthBuf, n)
        }
        val plane = a.floorFromPlanes()
        val f = if (!plane.isNaN()) plane else if (a.depthEnabled) scan.floorY(eyeY) else Float.NaN
        floorFromPlane = !plane.isNaN()
        if (!f.isNaN()) {
            floorY = if (floorMeasured) floorY + (f - floorY) * 0.3f else f
            floorMeasured = true
            if (a.depthEnabled) {
                ceilY = scan.ceilingY(eyeY)
                wallDistance = scan.wallDistance(ctx.input.head.position.x, ctx.input.head.position.z, floorY)
            }
        }
        depthNote = buildString {
            append(if (a.depthEnabled) "Profundidade ARCore · ${"%,d".format(scan.count).replace(',', '.')} pontos" else "Sem sensor de profundidade · planos do ARCore")
            if (floorMeasured) append(" · chão a ${fmt(eyeY - floorY)} m dos olhos${if (floorFromPlane) " (plano)" else ""}") else append(" · olhe para o chão para medir")
            if (!wallDistance.isNaN()) append(" · paredes a ~${fmt(wallDistance)} m")
            if (!ceilY.isNaN()) append(" · teto a ${fmt(ceilY - floorY)} m")
        }
    }
    private var floorFromPlane = false

    private fun fmt(v: Float) = "%.2f".format(v).replace('.', ',')

    // ---------------------------------------------------------------- per frame (GL thread)
    fun update(dt: Float, head: Pose) {
        eyeY = if (phase == Phase.ROOM) head.position.y else eyeY
        if (phase == Phase.ROOM) scanDepth(dt)
        // the photo for the sinking room (or none after a second: the sea comes anyway)
        if (waitingForPhoto) {
            photoWaitT += dt
            snapshot?.let { s -> snapshot = null; startSinking(s) }
            if (waitingForPhoto && photoWaitT > 1f) { ctx.hands?.frameTap = null; startSinking(null) }
        }
        val floor = currentFloor()
        seaLevel = floor - 0.55f
        val ceil = currentCeiling(floor)
        when (phase) {
            Phase.SINKING -> {
                phaseT += dt
                renderer.roomDrop = Sinking.progress(phaseT, SINK_S) * (ceil - seaLevel + 1.5f)
                if (phaseT >= SINK_S) { phase = Phase.SEA; renderer.roomVisible = false; panel.dirty = true }
            }
            Phase.RISING -> {
                phaseT += dt
                renderer.roomDrop = (1f - Sinking.progress(phaseT, SINK_S)) * (ceil - seaLevel + 1.5f)
                if (phaseT >= SINK_S) { phase = Phase.ROOM; renderer.roomVisible = false; renderer.clearRoom(); panel.dirty = true }
            }
            else -> Unit
        }

        // weather follows the switches over a few seconds
        val k = (dt / 4f).coerceAtMost(1f)
        val wTarget = ((if (rain) 0.55f else 0f) + (if (thunder) 0.45f else 0f) + (if (rough) 0.25f else 0f)).coerceAtMost(1f)
        weather += (wTarget - weather) * k
        val sTarget = if (rough) 1f else if (thunder) 0.55f else 0.15f
        seaState += (sTarget - seaState) * (dt / 6f).coerceAtMost(1f)
        rainLevel += ((if (rain) 1f else 0f) - rainLevel) * (dt / 2.5f).coerceAtMost(1f)
        if (kotlin.math.abs(seaState - packedSea) > 0.01f) { OceanWaves.pack(seaState, out = renderer.waves); packedSea = seaState }

        // lightning, with its thunder after the distance's delay
        val seaShown = phase != Phase.ROOM
        stormClock.update(dt, thunder && seaShown, rate = 0.6f + weather)?.let { s ->
            val bx = head.position.x - sin(s.azimuth) * s.distance
            val bz = head.position.z - cos(s.azimuth) * s.distance
            renderer.setBolt(LightningPath.build(s.time.toBits().toLong()), bx, seaLevel, bz, head.position.x, head.position.y, head.position.z, s.distance)
            audio.synth.thunder(s.distance, s.distance / 343f)
        }
        val flash = stormClock.flash()
        renderer.boltIntensity = (flash * 1.4f).coerceAtMost(1.5f)

        // sky and sea colours
        SkyLook.compute(light, weather, sunAzimuth, sky)
        val l = renderer.look
        sky.sunDir.copyInto(l.sunDir); sky.sunColor.copyInto(l.sunColor); sky.zenith.copyInto(l.zenith); sky.horizon.copyInto(l.horizon)
        sky.cloudLit.copyInto(l.cloudLit); sky.cloudShade.copyInto(l.cloudShade); sky.deep.copyInto(l.deep); sky.scatter.copyInto(l.scatter)
        l.cloud = sky.cloud; l.night = sky.night; l.exposure = sky.exposure; l.fog = sky.fog; l.ambient = sky.ambient
        l.flash = flash * 0.8f
        l.rain = rainLevel
        renderer.seaLevel = seaLevel
        renderer.time += dt
        renderer.rainCount = if (seaShown) (rainLevel * 7000f).toInt() else 0
        renderer.wind[0] = 1f + seaState * 3f; renderer.wind[1] = 0.4f + seaState
        renderer.pointsVisible = showDepth && phase == Phase.ROOM

        // the depth points shown twice a second (uploaded on this thread)
        pointsTimer += dt
        if (renderer.pointsVisible && pointsTimer > 0.5f) { pointsTimer = 0f; renderer.setPoints(scan.points, scan.count) }

        // sound: the sea is quieter while the room is still around you
        audio.synth.volume = volume
        audio.synth.sea = if (seaShown) 1f else 0.25f
        audio.synth.storm = seaState
        audio.synth.rain = if (seaShown) rainLevel else 0f
        panel.update(dt)
    }

    fun submit(scene: FrameScene) {
        scene.painter = this
        panel.submit(scene)
    }

    override val ownsBackground: Boolean get() = phase != Phase.ROOM
    override fun background(eye: EyeView) = renderer.drawBackground(eye)
    override fun afterScene(eye: EyeView) = renderer.drawOverlay(eye)

    fun close() = onClose()

    /** The activity went to the background: no sound from an app nobody sees. */
    fun onPause() = audio.stop()
    fun onResume() = audio.start()

    fun release() {
        ar?.wantPlanes(PLANES, false)
        ctx.hands?.let { if (waitingForPhoto) it.frameTap = null }
        audio.stop()
        renderer.release()
        panel.release()
    }

    private companion object { const val SINK_S = 7f; const val PLANES = "ocean" }
}

/**
 * The Ocean menu, laid out like Android's quick settings: two slider bars (light, volume) and
 * round-cornered tiles that light up when on.
 */
class OceanPanel(private val app: OceanApp) : XrPanel(0.95f, 0.98f, title = "Oceano") {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val icons = HashMap<String, Bitmap?>()
    private var t = 0f

    init { onClose = { app.close() } }

    private fun icon(name: String): Bitmap? = icons.getOrPut(name) {
        val r = com.hyperxr.HyperXRApp.instance.resources
        val id = r.getIdentifier("qs_$name", "drawable", com.hyperxr.HyperXRApp.instance.packageName)
        if (id == 0) null else BitmapFactory.decodeResource(r, id)
    }

    private var lastNote = ""
    override fun update(dt: Float) {
        t += dt
        if (t < 0.5f) return
        t = 0f
        val note = app.depthNote + app.phase + app.scan.count / 500
        if (note != lastNote) { lastNote = note; dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        val note = when (app.phase) {
            OceanApp.Phase.ROOM -> app.depthNote.ifEmpty { "Olhe em volta para o sensor de profundidade medir a sala." }
            OceanApp.Phase.SINKING -> "A sala está afundando…"
            OceanApp.Phase.SEA -> "Mar aberto. Use os blocos para mudar o tempo."
            OceanApp.Phase.RISING -> "A sala está voltando…"
        }
        c.paragraph(note, 24f)
        c.y += 4f
        slider(c, "light", "light_mode", "Luz", app.light) { app.light = it }
        c.y += 14f
        slider(c, "volume", "volume_up", "Volume", app.volume) { app.volume = it }
        c.y += 24f

        val gap = 16f
        val tw = (c.w - 2 * c.pad - gap) / 2; val th = 128f
        val sea = app.phase == OceanApp.Phase.SEA
        val busy = app.phase == OceanApp.Phase.SINKING || app.phase == OceanApp.Phase.RISING
        val tiles = listOf(
            Tile("room", if (sea || app.phase == OceanApp.Phase.RISING) "undo" else "arrow_downward",
                if (sea || app.phase == OceanApp.Phase.RISING) "Voltar à sala" else "Afundar a sala",
                if (busy) "Aguarde" else if (sea) "Trazer o quarto de volta" else "Trocar pelo oceano", sea, !busy) { if (sea) app.raise() else app.sink() },
            Tile("rain", "rainy", "Chuva", onOff(app.rain), app.rain) { app.rain = !app.rain },
            Tile("thunder", "thunderstorm", "Trovões", onOff(app.thunder), app.thunder) { app.thunder = !app.thunder },
            Tile("rough", "waves", "Mar agitado", if (app.rough) "Ondas altas" else "Mar calmo", app.rough) { app.rough = !app.rough },
            Tile("depth", "layers", "Ver profundidade", if (app.showDepth) "Pontos visíveis" else "Escondido", app.showDepth, app.phase == OceanApp.Phase.ROOM) { app.showDepth = !app.showDepth },
            Tile("rescan", "view_in_ar", "Medir de novo", "${app.scan.count} pontos", false, app.phase == OceanApp.Phase.ROOM) { app.rescan() },
        )
        for ((i, tile) in tiles.withIndex()) {
            val x = c.pad + (i % 2) * (tw + gap)
            val y = c.y + (i / 2) * (th + gap)
            drawTile(c, tile, x, y, tw, th)
        }
        c.y += 3 * (th + gap)
    }

    private fun onOff(b: Boolean) = if (b) "Ligado" else "Desligado"

    private class Tile(val id: String, val icon: String, val title: String, val sub: String, val on: Boolean, val enabled: Boolean = true, val action: () -> Unit)

    private fun drawTile(c: PanelCanvas, t: Tile, x: Float, y: Float, w: Float, h: Float) {
        val r = RectF(x, y, x + w, y + h)
        val hover = isHovered(t.id) && t.enabled
        fill.color = when {
            !t.enabled -> Color.argb(255, 44, 46, 50)
            t.on -> if (hover) ON_HOVER else ON
            else -> if (hover) Color.rgb(74, 78, 86) else Color.rgb(58, 61, 67)
        }
        c.canvas.drawRoundRect(r, h * 0.32f, h * 0.32f, fill)
        val fg = if (t.on) Color.rgb(16, 32, 60) else if (t.enabled) Color.WHITE else Color.rgb(120, 124, 130)
        icon(t.icon)?.let { b ->
            iconPaint.colorFilter = PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
            val s = 54f
            c.canvas.drawBitmap(b, null, RectF(x + 26f, y + (h - s) / 2, x + 26f + s, y + (h + s) / 2), iconPaint)
        }
        text.color = fg; text.typeface = XrTheme.medium; text.textSize = 30f
        c.canvas.drawText(com.hyperxr.i18n.I18n.t(t.title), x + 100f, y + h / 2 - 6f, text)
        text.typeface = XrTheme.regular; text.textSize = 23f
        text.color = if (t.on) Color.rgb(40, 62, 98) else Color.rgb(170, 175, 184)
        c.canvas.drawText(com.hyperxr.i18n.I18n.t(t.sub), x + 100f, y + h / 2 + 28f, text)
        if (t.enabled) addRegion(Region(r, t.id, t.action))
    }

    /** A thick rounded bar: the filled part is the value; pointing anywhere on it sets the value there. */
    private fun slider(c: PanelCanvas, id: String, iconName: String, label: String, value: Float, set: (Float) -> Unit) {
        val x0 = c.pad; val x1 = c.w - c.pad; val y = c.y; val h = 96f
        val r = RectF(x0, y, x1, y + h)
        fill.color = Color.rgb(58, 61, 67)
        c.canvas.drawRoundRect(r, h / 2, h / 2, fill)
        val fx = x0 + h + (x1 - x0 - h) * value
        fill.color = ON
        c.canvas.drawRoundRect(RectF(x0, y, fx, y + h), h / 2, h / 2, fill)
        icon(iconName)?.let { b ->
            iconPaint.colorFilter = PorterDuffColorFilter(Color.rgb(16, 32, 60), PorterDuff.Mode.SRC_IN)
            c.canvas.drawBitmap(b, null, RectF(x0 + 24f, y + 21f, x0 + 78f, y + 75f), iconPaint)
        }
        text.typeface = XrTheme.medium; text.textSize = 28f
        val pct = "$label  ${(value * 100).toInt()}%"
        text.color = if (fx > x1 - 230f) Color.rgb(16, 32, 60) else Color.WHITE
        c.canvas.drawText(pct, x1 - 30f - text.measureText(pct), y + h / 2 + 10f, text)
        // segments across the bar, each setting its own value (the first one is 0)
        val n = 25
        for (k in 0 until n) {
            val sx0 = x0 + (x1 - x0) * k / n; val sx1 = x0 + (x1 - x0) * (k + 1) / n
            val v = k / (n - 1f)
            addRegion(Region(RectF(sx0, y, sx1, y + h), "$id$k", { set(v); dirty = true }))
        }
        c.y += h
    }

    override fun release() { icons.values.forEach { it?.recycle() }; icons.clear(); super.release() }

    private companion object {
        val ON = Color.rgb(168, 199, 250)
        val ON_HOVER = Color.rgb(190, 214, 252)
    }
}
