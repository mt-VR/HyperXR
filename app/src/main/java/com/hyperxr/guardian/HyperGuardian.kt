package com.hyperxr.guardian

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.hyperxr.handtracking.Gesture
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.math.Mat4
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.mixedreality.ArCoreSession
import com.hyperxr.ocean.RoomScan
import com.hyperxr.rendering.FrameScene
import com.hyperxr.rendering.Gl
import com.hyperxr.rendering.GpuMesh
import com.hyperxr.rendering.TubeGeometry
import kotlin.math.hypot

/**
 * HyperGuardian: keeps you inside a safe space, for every app that runs in HyperXR's engine.
 *
 *  - Manual: you draw your play area on the floor by pointing at it with a pinch. Near its edge
 *    a grid wall fades in; step out and the camera shows the real room until you are back.
 *  - Auto: no drawing. The depth sensor maps walls and furniture around you as you look about
 *    (ObstacleMap, with its protections for your own body, beds, sofas and things that move);
 *    near a wall the grid shows on it and closer still the camera fades in.
 *
 * Both need the phone's position (ARCore 6DoF); Auto also needs ARCore Depth. Runs on the GL
 * thread, once per frame, after the app has filled the scene.
 */
class HyperGuardian(private val activity: Activity) {
    enum class Mode { OFF, MANUAL, AUTO }

    private val prefs = activity.getSharedPreferences("guardian", Context.MODE_PRIVATE)
    var mode: Mode = runCatching { Mode.valueOf(prefs.getString("mode", "OFF")!!) }.getOrDefault(Mode.OFF)
        set(v) { field = v; prefs.edit().putString("mode", v.name).apply(); if (v != Mode.MANUAL) drawing = false }
    /** Distance at which the grid starts to show (m); the camera comes at less than half of it. */
    var distance = prefs.getFloat("distance", 0.6f)
        set(v) { field = v; prefs.edit().putFloat("distance", v).apply(); warning.warn = v; warning.pass = v * 0.4f }

    val area = GuardianArea()
    val map = ObstacleMap()
    val scan = RoomScan(20000)
    val warning = GuardianWarning(distance, distance * 0.4f)

    var floorY = Float.NaN; private set
    var floorMeasured = false; private set
    /** True when the phone's position is tracked (6DoF): without it nothing here can work. */
    var tracked = false; private set
    var depthAvailable = false; private set
    var status = ""; private set
    /** Distances this frame, for the panel: nearest wall, nearest low furniture, inside the area. */
    var wallM = Float.NaN; private set
    var lowM = Float.NaN; private set
    var insideM = Float.NaN; private set

    // drawing the area
    var drawing = false; private set
    private var pinching = false
    private var traceDirty = false
    private var showAreaT = 0f

    private val depthBuf = FloatArray(4000 * 3)
    private val handBuf = FloatArray(HandJoint.COUNT * 2 * 3)
    private var scanT = 0f
    private val camPose = Pose()

    // rendering
    private var gridTex = 0
    private var fence: GpuMesh? = null
    private var fenceFor: FloatArray? = null
    private var fenceFloor = 0f
    private var wallMesh: GpuMesh? = null
    private var lowMesh: GpuMesh? = null
    private var traceMesh: GpuMesh? = null
    private var meshT = 0f
    private var labelTex = 0
    private var labelText = ""
    private var labelAspect = 4f
    private val identity = Mat4.compose(Vec3(), Quat(), 1f, 1f, 1f, FloatArray(16))

    init { loadArea() }

    /** The engine needs ARCore (and its depth for Auto) while HyperGuardian is on. */
    val needsTracking get() = mode != Mode.OFF

    // ---------------------------------------------------------------- area
    fun startDrawing() { mode = Mode.MANUAL; drawing = true; pinching = false; area.beginTrace(); traceDirty = true }

    /** Closes the traced line into the area; false when it is too small (the old area stays). */
    fun finishDrawing(): Boolean {
        drawing = false; pinching = false
        val ok = area.tracing > 3 && area.finishTrace()
        area.beginTrace(); traceDirty = true
        if (ok) { saveArea(); showAreaT = 4f }
        return ok
    }

    fun cancelDrawing() { drawing = false; pinching = false; area.beginTrace(); traceDirty = true }
    fun clearArea() { area.clear(); saveArea(); fenceFor = null }
    fun resetMap() { map.reset(); scan.reset(); floorMeasured = false; floorY = Float.NaN }

    private fun saveArea() {
        val p = area.points
        prefs.edit().putString("area", p.joinToString(",")).apply()
    }

    private fun loadArea() {
        val s = prefs.getString("area", "") ?: ""
        if (s.isEmpty()) return
        val v = runCatching { s.split(',').map { it.toFloat() }.toFloatArray() }.getOrNull() ?: return
        if (v.size >= 6 && v.size % 2 == 0) area.set(v)
    }

    // ---------------------------------------------------------------- per frame
    fun update(dt: Float, head: Pose, hands: Array<HandState>, ar: ArCoreSession?, sixDof: Boolean) {
        tracked = sixDof && ar != null
        depthAvailable = ar?.depthEnabled == true
        // plane finding only while HyperGuardian is on (it costs CPU on every camera frame)
        if (mode == Mode.OFF) ar?.wantPlanes(PLANES, false)
        if (mode == Mode.OFF || !tracked) {
            warning.update(dt, Float.NaN, Float.NaN, Float.NaN)
            status = when {
                mode == Mode.OFF -> "Desligado"
                ar == null -> "Precisa do ARCore com posição (6DoF). Se acabou de ligar, saia e entre de novo no HyperMagic para o ARCore iniciar"
                else -> "Sem posição do celular agora (o ARCore perdeu o rastreio): mova o celular devagar"
            }
            return
        }
        val hx = head.position.x; val hy = head.position.y; val hz = head.position.z

        // the floor in both modes (ARCore's planes first, the depth points otherwise), the
        // obstacle map in Auto
        ar!!.wantPlanes(PLANES, true)
        scanT += dt
        if (scanT >= 0.25f) {
            scanT = 0f
            val n = if (depthAvailable) ar.depthPoints(depthBuf, step = 8, maxM = 4f) else 0
            if (n > 0) scan.add(depthBuf, n)
            val plane = ar.floorFromPlanes()
            val f = if (!plane.isNaN()) plane else if (n > 0) scan.floorY(hy) else Float.NaN
            if (!f.isNaN()) { floorY = if (floorMeasured) floorY + (f - floorY) * 0.2f else f; floorMeasured = true }
            if (n > 0 && mode == Mode.AUTO && floorMeasured && ar.cameraPose(camPose)) {
                var k = 0
                for (h in hands) if (h.isUsable) for (j in h.joints) { handBuf[k * 3] = j.x; handBuf[k * 3 + 1] = j.y; handBuf[k * 3 + 2] = j.z; k++ }
                map.integrate(depthBuf, n, camPose.position.x, camPose.position.z, hx, hy, hz, floorY, handBuf, k)
            }
        }
        if (!floorMeasured) floorY = hy - 1.6f

        if (drawing) traceWithHands(hands, head)

        wallM = Float.NaN; lowM = Float.NaN; insideM = Float.NaN
        when (mode) {
            Mode.MANUAL -> {
                insideM = if (area.valid) area.signedDistance(hx, hz) else Float.NaN
                warning.update(dt, Float.NaN, Float.NaN, if (insideM.isNaN() || drawing) Float.NaN else -insideM)
                status = when {
                    drawing -> "Desenhando: aponte para o chão e faça a pinça, contorne a área e volte ao começo"
                    !area.valid -> "Nenhuma área: toque em Desenhar área"
                    insideM < 0f -> "Fora da área (${fmt(-insideM)} m)"
                    else -> "Área de ${fmt(area.area())} m² · borda a ${fmt(insideM)} m"
                }
            }
            Mode.AUTO -> {
                val near = map.nearest(hx, hz)
                wallM = near[0]; lowM = near[1]
                warning.update(dt, wallM, lowM, Float.NaN)
                status = when {
                    !depthAvailable -> "O modo automático precisa do sensor de profundidade do ARCore (este celular não tem)"
                    !floorMeasured -> "Olhe para o chão e em volta para medir o quarto"
                    else -> buildString {
                        append("Parede mais perto: ").append(if (wallM.isNaN()) "nenhuma a menos de 2,5 m" else "${fmt(wallM)} m")
                        if (!lowM.isNaN()) append(" · móvel baixo: ${fmt(lowM)} m")
                    }
                }
            }
            Mode.OFF -> Unit
        }
        if (showAreaT > 0f) showAreaT -= dt
    }

    /**
     * Drawing: each hand points from the shoulder through the index fingertip; while it pinches,
     * where that ray meets the floor is added to the line. Closing the loop finishes the area.
     */
    private val shoulder = Vec3(); private val tip = Vec3(); private val right = Vec3()
    private fun traceWithHands(hands: Array<HandState>, head: Pose) {
        var any = false
        for ((i, h) in hands.withIndex()) {
            if (!h.isUsable || h.gesture != Gesture.PINCH) continue
            any = true
            head.orientation.rotate(right.set(1f, 0f, 0f), right)
            val side = if (i == 0) -0.17f else 0.17f
            shoulder.set(head.position.x, head.position.y - 0.2f, head.position.z).addScaled(right, side)
            tip.set(h.joints[HandJoint.THUMB_TIP]).add(h.joints[HandJoint.INDEX_TIP]).scale(0.5f)
            val hit = FloorRay.hit(shoulder.x, shoulder.y, shoulder.z, tip.x - shoulder.x, tip.y - shoulder.y, tip.z - shoulder.z, floorY) ?: continue
            if (area.addTracePoint(hit[0], hit[1])) traceDirty = true
            break
        }
        // letting go after coming back to the start closes the area
        if (pinching && !any && area.traceCanClose()) finishDrawing()
        pinching = any
    }

    private fun fmt(v: Float) = "%.1f".format(v).replace('.', ',')

    // ---------------------------------------------------------------- drawing (GL thread)
    /** How much of the camera to show over the scene (0..1). */
    val cameraAmount get() = if (mode == Mode.OFF || !tracked) 0f else warning.camera

    fun submit(scene: FrameScene, head: Pose) {
        if (mode == Mode.OFF || !tracked) return
        if (gridTex == 0) { gridTex = Gl.uploadBitmap(gridBitmap(), mipmap = true); Gl.repeatTexture(gridTex) }
        meshT += 0.016f
        when (mode) {
            Mode.MANUAL -> {
                if (traceDirty) { traceDirty = false; rebuildTrace() }
                traceMesh?.let { add(scene, it, floatArrayOf(0.45f, 0.95f, 1f, 0.95f)) }
                if (area.valid) {
                    // rebuilt for a new outline, or when the floor measurement moved it
                    if (fenceFor !== area.points || kotlin.math.abs(fenceFloor - floorY) > 0.04f) {
                        fenceFor = area.points; fenceFloor = floorY; fence?.release(); fence = fenceMesh(area.points)
                    }
                    val a = maxOf(warning.grid, if (drawing) 0.35f else 0f, (showAreaT / 4f).coerceIn(0f, 1f) * 0.8f)
                    if (a > 0.01f) fence?.let { add(scene, it, floatArrayOf(0.35f, 0.85f, 1f, a * 0.85f)) }
                }
            }
            Mode.AUTO -> {
                if (meshT > 0.25f) { meshT = 0f; rebuildObstacles(head) }
                val a = warning.grid
                if (a > 0.01f) {
                    wallMesh?.let { add(scene, it, floatArrayOf(0.35f, 0.85f, 1f, a * 0.85f)) }
                    lowMesh?.let { add(scene, it, floatArrayOf(1f, 0.72f, 0.25f, a * 0.8f)) }
                }
            }
            Mode.OFF -> Unit
        }
        // a message in front of you when the camera is showing
        if (warning.camera > 0.5f) {
            val text = if (mode == Mode.MANUAL) "Volte para a área" else "Cuidado: parede perto"
            if (text != labelText || labelTex == 0) makeLabel(text)
            val p = scene.addPanel()
            p.texture = labelTex
            val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
            p.pose.position.set(head.position.x, head.position.y + 0.12f, head.position.z).addScaled(f, 0.9f)
            p.pose.orientation.set(head.orientation)
            p.height = 0.09f; p.width = p.height * labelAspect
            p.radius = 0.045f
            p.overlay = true
        }
    }

    private fun add(scene: FrameScene, mesh: GpuMesh, color: FloatArray) {
        val d = scene.addDraw()
        d.mesh = mesh; d.texture = gridTex; d.unlit = true; d.transparent = true; d.doubleSided = true; d.late = true
        color.copyInto(d.color)
        System.arraycopy(identity, 0, d.matrix, 0, 16)
    }

    /** Vertical grid walls along the outline, floor to 2.4 m (pos3, normal3, uv2; the grid is 25 cm). */
    private fun fenceMesh(p: FloatArray): GpuMesh {
        val n = p.size / 2
        val v = FloatArray(n * 4 * 8); val idx = IntArray(n * 6)
        val h = 2.4f; val y0 = floorY
        var u0 = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = p[i * 2]; val az = p[i * 2 + 1]; val bx = p[j * 2]; val bz = p[j * 2 + 1]
            val len = hypot(bx - ax, bz - az)
            val nx = -(bz - az) / len.coerceAtLeast(1e-4f); val nz = (bx - ax) / len.coerceAtLeast(1e-4f)
            val u1 = u0 + len / 0.25f
            fun put(k: Int, x: Float, y: Float, z: Float, u: Float, vv: Float) {
                val o = (i * 4 + k) * 8
                v[o] = x; v[o + 1] = y; v[o + 2] = z; v[o + 3] = nx; v[o + 4] = 0f; v[o + 5] = nz; v[o + 6] = u; v[o + 7] = vv
            }
            put(0, ax, y0, az, u0, h / 0.25f); put(1, bx, y0, bz, u1, h / 0.25f); put(2, bx, y0 + h, bz, u1, 0f); put(3, ax, y0 + h, az, u0, 0f)
            val b = i * 4
            idx[i * 6] = b; idx[i * 6 + 1] = b + 1; idx[i * 6 + 2] = b + 2; idx[i * 6 + 3] = b; idx[i * 6 + 4] = b + 2; idx[i * 6 + 5] = b + 3
            u0 = u1
        }
        return GpuMesh(v, idx)
    }

    private fun rebuildTrace() {
        traceMesh?.release(); traceMesh = null
        val t = area.tracePoints()
        if (t.size < 4) return
        val pts = FloatArray(t.size / 2 * 3)
        for (i in 0 until t.size / 2) { pts[i * 3] = t[i * 2]; pts[i * 3 + 1] = floorY + 0.02f; pts[i * 3 + 2] = t[i * 2 + 1] }
        TubeGeometry.build(pts, 0.012f)?.let { traceMesh = GpuMesh(it.vertices, it.indices) }
    }

    /**
     * The obstacles within 1.6 m as grid columns, one per confirmed cell, each a quad turned to
     * face you: walls up to their measured height, low furniture up to theirs.
     */
    private fun rebuildObstacles(head: Pose) {
        wallMesh?.release(); wallMesh = null; lowMesh?.release(); lowMesh = null
        val hx = head.position.x; val hz = head.position.z
        val wv = ArrayList<Float>(); val lv = ArrayList<Float>()
        for ((k, c) in map.cells) {
            if (!map.confirmed(c)) continue
            val x = map.cellX(k); val z = map.cellZ(k)
            val d = hypot(x - hx, z - hz)
            if (d > 1.6f || d < 0.3f) continue
            val wall = map.kind(c) == ObstacleMap.Kind.WALL
            val top = floorY + (if (wall) maxOf(c.top, 2.2f) else c.top)
            // across the line of sight
            val ux = -(z - hz) / d * map.cell / 2 * 1.4f; val uz = (x - hx) / d * map.cell / 2 * 1.4f
            val out = if (wall) wv else lv
            val nx = (hx - x) / d; val nz = (hz - z) / d
            for ((px, py, pz, uv) in listOf(Quad(x - ux, floorY, z - uz, 0f), Quad(x + ux, floorY, z + uz, 1f), Quad(x + ux, top, z + uz, 2f), Quad(x - ux, top, z - uz, 3f))) {
                out += px; out += py; out += pz; out += nx; out += 0f; out += nz
                out += if (uv == 0f || uv == 3f) 0f else 0.2f; out += (py - floorY) / 0.25f
            }
        }
        fun mesh(v: ArrayList<Float>): GpuMesh? {
            if (v.isEmpty()) return null
            val q = v.size / 32
            val idx = IntArray(q * 6)
            for (i in 0 until q) { val b = i * 4; idx[i * 6] = b; idx[i * 6 + 1] = b + 1; idx[i * 6 + 2] = b + 2; idx[i * 6 + 3] = b; idx[i * 6 + 4] = b + 2; idx[i * 6 + 5] = b + 3 }
            return GpuMesh(v.toFloatArray(), idx)
        }
        wallMesh = mesh(wv); lowMesh = mesh(lv)
    }

    private companion object { const val PLANES = "guardian" }

    private data class Quad(val x: Float, val y: Float, val z: Float, val corner: Float)

    private fun gridBitmap(): Bitmap {
        val s = 128
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(Color.argb(40, 255, 255, 255))
        p.color = Color.WHITE; p.strokeWidth = 7f
        c.drawLine(0f, 2f, s.toFloat(), 2f, p); c.drawLine(2f, 0f, 2f, s.toFloat(), p)
        return b
    }

    private fun makeLabel(text: String) {
        labelText = text
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 64f; color = Color.WHITE; typeface = com.hyperxr.spatialui.XrTheme.medium }
        val w = (p.measureText(text) + 120).toInt(); val h = 120
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 190, 40, 40) }
        c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), h / 2f, h / 2f, bg)
        c.drawText(com.hyperxr.i18n.I18n.t(text), 60f, h / 2f - (p.descent() + p.ascent()) / 2, p)
        labelTex = Gl.uploadBitmap(b, labelTex)
        labelAspect = w.toFloat() / h
        b.recycle()
    }

    fun release() {
        fence?.release(); wallMesh?.release(); lowMesh?.release(); traceMesh?.release()
        fence = null; wallMesh = null; lowMesh = null; traceMesh = null; fenceFor = null
        Gl.deleteTexture(gridTex); Gl.deleteTexture(labelTex); gridTex = 0; labelTex = 0
    }
}
