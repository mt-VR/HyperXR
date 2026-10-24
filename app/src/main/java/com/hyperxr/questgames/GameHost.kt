package com.hyperxr.questgames

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.gltf.GltfLoader
import com.hyperxr.handtracking.Gesture
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.math.Mat4
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.GpuMesh
import com.hyperxr.rendering.GpuModel
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.xr.XrContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A game: rules in core (GameLogic), drawing and input here. Game space: metres, floor y = 0, facing -z. */
abstract class QuestGame(val host: GameHost) {
    abstract val title: String
    open val needsController = false
    var score = 0
    var status = ""
    var over = false
    /** Sky colour when the game draws its own world (null: keep the room / environment). */
    open val sky: FloatArray? = null
    abstract fun start()
    abstract fun update(dt: Float)
    abstract fun draw()
    open fun release() {}
}

/** One hand in game space this frame, with where it was last frame (for swipes and punches). */
class GameHand {
    var usable = false
    val tip = Vec3(); val palm = Vec3(); val prevTip = Vec3(); val prevPalm = Vec3()
    val velocity = Vec3()
    var speed = 0f
    var fist = false
    var pinch = false
    var wasUsable = false
}

/** The controller in game space: Joy-Con, HyperConnect phone or any gamepad the engine knows. */
class GamePad {
    var connected = false
    val aim = Ray()
    val pose = Pose()
    val prevPos = Vec3()
    val velocity = Vec3()
    var trigger = false; var triggerDown = false
    var alt = false; var altDown = false
    var stickX = 0f; var stickY = 0f
    var haptics: ((Int) -> Unit)? = null
    fun buzz(ms: Int) { haptics?.invoke(ms) }
}

/**
 * Runs one game inside the launcher: places it in front of you (on the floor under you, facing
 * where you looked), turns the hands and controller into game space, loads Kenney models from
 * the APK (CC0), keeps the score panel, and draws.
 */
class GameHost(val ctx: XrContext, private val id: String, private val onExit: () -> Unit) {
    /** Game space in the world: under the head at the floor, turned to where the head looked. */
    val origin = Pose()
    private val inv = Pose()
    var floorY = 0f; private set
    val hands = arrayOf(GameHand(), GameHand())
    val pad = GamePad()
    val head = Vec3()
    val headPrev = Vec3()
    var headSpeed = 0f; private set
    val hud = GameHud(this)
    var game: QuestGame? = null; private set
    private val models = HashMap<String, GpuModel?>()
    private var lastButtons = 0
    var time = 0f; private set

    // sounds (synthesised once)
    var sHit = 0; var sMiss = 0; var sCoin = 0; var sShoot = 0; var sBoom = 0; var sSplash = 0; var sPunch = 0; var sWin = 0; var sTick = 0

    fun start(headPose: Pose) {
        val f = headPose.orientation.rotate(Vec3(0f, 0f, -1f))
        val yaw = atan2(-f.x, -f.z)
        val g = ctx.guardian
        floorY = if (g != null && g.floorMeasured) g.floorY else headPose.position.y - 1.6f
        origin.position.set(headPose.position.x, floorY, headPose.position.z)
        Quat.fromAxisAngle(0f, 1f, 0f, yaw, origin.orientation)
        origin.inverse(inv)
        val a = ctx.audio
        sHit = a.synth("square", 880f, 1320f, 0.08f, 0.35f, 0.001f, 0.03f)
        sMiss = a.synth("saw", 220f, 110f, 0.25f, 0.3f, 0.002f, 0.1f)
        sCoin = a.synth("sine", 988f, 1976f, 0.15f, 0.45f, 0.001f, 0.06f)
        sShoot = a.synth("noise", 0f, 0f, 0.12f, 0.35f, 0.001f, 0.035f)
        sBoom = a.synth("noise", 0f, 0f, 0.6f, 0.6f, 0.002f, 0.2f)
        sSplash = a.synth("noise", 0f, 0f, 0.35f, 0.3f, 0.02f, 0.12f)
        sPunch = a.synth("sine", 140f, 60f, 0.12f, 0.8f, 0.001f, 0.05f)
        sWin = a.synth("square", 523f, 1046f, 0.6f, 0.3f, 0.005f, 0.3f)
        sTick = a.synth("sine", 1500f, 1500f, 0.04f, 0.3f, 0.001f, 0.015f)
        hud.place()
        newGame()
    }

    fun newGame() {
        game?.release()
        game = QuestGames.create(id, this)?.also { it.start() }
        hud.dirty = true
    }

    fun exit() = onExit()

    // ---------------------------------------------------------------- space
    fun toWorld(x: Float, y: Float, z: Float, out: Vec3 = Vec3()): Vec3 = origin.transformPoint(tmpIn.set(x, y, z), out)
    fun toGame(w: Vec3, out: Vec3): Vec3 = inv.transformPoint(w, out)
    fun dirToGame(w: Vec3, out: Vec3): Vec3 = inv.orientation.rotate(w, out)
    private val tmpIn = Vec3()

    // ---------------------------------------------------------------- per frame
    private val w = Vec3()
    fun update(dt: Float) {
        time += dt
        headPrev.set(head)
        toGame(ctx.input.head.position, head)
        headSpeed = if (dt > 0f) head.distanceTo(headPrev) / dt else 0f
        for (i in 0..1) {
            val h = ctx.input.hands[i]; val g = hands[i]
            g.prevTip.set(g.tip); g.prevPalm.set(g.palm)
            g.wasUsable = g.usable
            g.usable = h.isUsable
            if (!g.usable) { g.speed = 0f; continue }
            toGame(h.joints[HandJoint.INDEX_TIP], g.tip)
            w.set(h.joints[HandJoint.WRIST]).add(h.joints[HandJoint.MIDDLE_MCP]).scale(0.5f)
            toGame(w, g.palm)
            if (!g.wasUsable) { g.prevTip.set(g.tip); g.prevPalm.set(g.palm) }
            if (dt > 0f) {
                val vx = (g.palm.x - g.prevPalm.x) / dt; val vy = (g.palm.y - g.prevPalm.y) / dt; val vz = (g.palm.z - g.prevPalm.z) / dt
                g.velocity.set(g.velocity.x * 0.4f + vx * 0.6f, g.velocity.y * 0.4f + vy * 0.6f, g.velocity.z * 0.4f + vz * 0.6f)
                g.speed = g.velocity.length()
            }
            g.fist = h.gesture == Gesture.FIST || h.gesture == Gesture.GRAB
            g.pinch = h.gesture == Gesture.PINCH
        }
        // the controller: the right one first, then the left
        val c = ctx.input.controllers.let { if (it[1].connected) it[1] else it[0] }
        pad.connected = c.connected
        if (c.connected) {
            pad.prevPos.set(pad.pose.position)
            toGame(c.pose.position, pad.pose.position)
            inv.orientation.mul(c.pose.orientation, pad.pose.orientation)
            val r = c.aim
            if (r != null) { toGame(r.origin, pad.aim.origin); dirToGame(r.direction, pad.aim.direction) }
            else { pad.aim.origin.set(pad.pose.position); pad.pose.orientation.rotate(Pose.FORWARD, pad.aim.direction) }
            if (dt > 0f) pad.velocity.set(pad.pose.position).sub(pad.prevPos).scale(1f / dt)
            val b = c.buttons
            val trig = b and (128 or 1 or 4) != 0
            val alt = b and (256 or 2 or 8) != 0
            pad.triggerDown = trig && !pad.trigger; pad.trigger = trig
            pad.altDown = alt && !pad.alt; pad.alt = alt
            pad.stickX = c.stickX; pad.stickY = c.stickY
            pad.haptics = c.haptics
            lastButtons = b
        } else { pad.trigger = false; pad.triggerDown = false; pad.alt = false; pad.altDown = false; pad.stickX = 0f; pad.stickY = 0f }

        val g = game ?: return
        if (g.needsController && !pad.connected) {
            g.status = "Conecte um controle: Joy-Con, o celular controle (HyperConnect) ou um gamepad Bluetooth"
        } else if (!g.over) g.update(dt)
        hud.update(dt)
    }

    fun draw() {
        val g = game ?: return
        g.sky?.let { s -> ctx.scene.background = com.hyperxr.rendering.Background.COLOR; s.copyInto(ctx.scene.clear) }
        g.draw()
        hud.submit(ctx.scene)
    }

    // ---------------------------------------------------------------- drawing helpers (game space)
    private val pos = Vec3(); private val rot = Quat(); private val q2 = Quat()

    /** A model from assets/games/<path>.glb (the colormap next to it), cached. */
    fun model(path: String): GpuModel? = models.getOrPut(path) {
        try {
            val dir = path.substringBeforeLast('/')
            val bytes = ctx.activity.assets.open("games/$path.glb").use { it.readBytes() }
            val m = GltfLoader.load(bytes) { uri -> ctx.activity.assets.open("games/$dir/$uri").use { it.readBytes() } }
            GpuModel(m)
        } catch (t: Throwable) { Diagnostics.log("Game model $path: ${t.message}"); null }
    }

    /** Draws a model at game position, turned by [yaw] (and [pitch], [roll]), scaled, tinted. */
    fun drawModel(path: String, x: Float, y: Float, z: Float, yaw: Float = 0f, scale: Float = 1f, tint: FloatArray? = null, pitch: Float = 0f, roll: Float = 0f, orientation: Quat? = null) {
        val m = model(path) ?: return
        val d = ctx.scene.addDraw()
        d.model = m
        d.castShadow = false
        (tint ?: WHITE).copyInto(d.color)
        toWorld(x, y, z, pos)
        if (orientation != null) origin.orientation.mul(orientation, rot)
        else {
            Quat.fromAxisAngle(0f, 1f, 0f, yaw, rot)
            if (pitch != 0f) rot.mul(Quat.fromAxisAngle(1f, 0f, 0f, pitch, q2), rot)
            if (roll != 0f) rot.mul(Quat.fromAxisAngle(0f, 0f, 1f, roll, q2), rot)
            origin.orientation.mul(rot, rot)
        }
        Mat4.compose(pos, rot, scale, scale, scale, d.matrix)
    }

    /** A box (centre, size) in game space, optionally turned by [yaw] or a full [orientation]. */
    fun drawBox(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: FloatArray, yaw: Float = 0f, orientation: Quat? = null, unlit: Boolean = false, gloss: Float = 0.2f) =
        drawMesh(ctx.renderer.cube, x, y, z, sx, sy, sz, color, yaw, orientation, unlit, gloss)

    fun drawSphere(x: Float, y: Float, z: Float, r: Float, color: FloatArray, unlit: Boolean = false, gloss: Float = 0.4f) =
        drawMesh(ctx.renderer.sphere, x, y, z, r * 2, r * 2, r * 2, color, 0f, null, unlit, gloss)

    fun drawCylinder(x: Float, y: Float, z: Float, r: Float, h: Float, color: FloatArray, orientation: Quat? = null, unlit: Boolean = false) =
        drawMesh(ctx.renderer.cylinder, x, y, z, r * 2, h, r * 2, color, 0f, orientation, unlit, 0.3f)

    private fun drawMesh(mesh: GpuMesh, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: FloatArray, yaw: Float, orientation: Quat?, unlit: Boolean, gloss: Float) {
        val d = ctx.scene.addDraw()
        d.mesh = mesh
        color.copyInto(d.color)
        d.unlit = unlit; d.gloss = gloss
        d.transparent = color[3] < 0.999f
        toWorld(x, y, z, pos)
        if (orientation != null) origin.orientation.mul(orientation, rot)
        else origin.orientation.mul(Quat.fromAxisAngle(0f, 1f, 0f, yaw, q2), rot)
        Mat4.compose(pos, rot, sx, sy, sz, d.matrix)
    }

    /** A thin beam from a to b (lasers, lines, the fishing line). */
    fun drawBeam(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: Float, color: FloatArray, unlit: Boolean = true) {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4f) return
        // the cylinder's axis is y: turn y onto the beam
        val q = Quat.fromTo(0f, 1f, 0f, dx / len, dy / len, dz / len)
        drawCylinder((ax + bx) / 2, (ay + by) / 2, (az + bz) / 2, r, len, color, q, unlit)
    }

    fun play(sound: Int, x: Float, y: Float, z: Float, volume: Float = 1f, pitch: Float = 1f) {
        ctx.audio.play(sound, volume, pitch, false, toWorld(x, y, z, Vec3()), ctx.input.head)
    }

    fun release() {
        game?.release(); game = null
        models.values.forEach { it?.release() }; models.clear()
        hud.release()
    }

    companion object { val WHITE = floatArrayOf(1f, 1f, 1f, 1f) }
}

/** Quat helper: the rotation that turns direction a onto direction b (both unit). */
fun Quat.Companion.fromTo(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Quat {
    val d = ax * bx + ay * by + az * bz
    if (d < -0.9999f) return Quat.fromAxisAngle(1f, 0f, 0f, Math.PI.toFloat())
    val cx = ay * bz - az * by; val cy = az * bx - ax * bz; val cz = ax * by - ay * bx
    return Quat().set(cx, cy, cz, 1f + d).normalize()
}

/** The game's panel: title, score, what is happening, restart and exit. */
class GameHud(private val host: GameHost) : XrPanel(0.85f, 0.42f, title = "") {
    private var t = 0f

    // no X: leaving the game is the Sair button (a hidden panel would leave the game without one)
    init { closable = false }
    private var last = ""

    fun place() {
        // to the left of where you look, a little high: out of the way of the play space
        host.toWorld(-0.95f, 1.75f, -1.25f, pose.position)
        val q = Quat.fromAxisAngle(0f, 1f, 0f, 0.6f)
        host.origin.orientation.mul(q, pose.orientation)
    }

    override fun update(dt: Float) {
        t += dt
        if (t < 0.2f) return
        t = 0f
        val g = host.game
        val s = "${g?.score} ${g?.status} ${g?.over}"
        if (s != last) { last = s; dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        val g = host.game ?: return
        c.label(g.title, c.pad, c.pad, 40f, XrTheme.text, bold = true)
        c.label("${g.score}", c.w - c.pad - 220f, c.pad - 6f, 56f, XrTheme.accent2, bold = true, maxWidth = 220f)
        c.y = c.pad + 70f
        if (g.status.isNotEmpty()) c.paragraph(g.status, 26f, if (g.over) XrTheme.accent2 else XrTheme.text)
        val y = c.h - 100f
        c.button("again", if (g.over) "Jogar de novo" else "Recomeçar", c.pad, y, 300f, 76f, accent = g.over) { host.newGame() }
        c.button("exit", "Sair", c.pad + 316f, y, 200f, 76f) { host.exit() }
    }
}
