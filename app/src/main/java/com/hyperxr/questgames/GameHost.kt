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
    /** The world around the game: sky, ground, light (null: keep the room / environment). */
    open val look: GameSky.Look? = null
    /** Shadows around the play space (radius in metres, 0: none) and where they are centred. */
    open val shadowRadius = 4f
    open val shadowZ = -1.2f
    /** The game draws the player's hands itself (or something in their place): the system's are hidden. */
    open val hidesHands = false
    /** A game's own panel (menus, lists), shown and clickable next to the HUD. */
    open val menu: com.hyperxr.spatialui.XrPanel? = null
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
    /** Where the hand points: the fingers when open, out of the thumb side when it is a fist (holding a hilt). */
    val dir = Vec3(0f, 0f, -1f); val prevDir = Vec3(0f, 0f, -1f)
    /** Recent blade tips (for trails), newest first. */
    val trail = Array(10) { Vec3() }
    var trailCount = 0
    /** A point [d] metres along the hand's direction, from the palm. */
    fun along(d: Float, out: Vec3) = out.set(palm).addScaled(dir, d)
    fun prevAlong(d: Float, out: Vec3) = out.set(prevPalm).addScaled(prevDir, d)
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
    private val textures = HashMap<String, Int>()
    val sky = GameSky(this)
    val particles = Particles(this)
    /** Slows the particles with the game (the slow-time game). */
    var timeScale = 1f
    private val meshes: Array<Lazy<GpuMesh>> = arrayOf(lazy { GameMeshes.roundedBox(0.14f) }, lazy { GameMeshes.roundedBox(0.3f) }, lazy { GameMeshes.torus(0.035f) },
        lazy { GameMeshes.chevron() }, lazy { GameMeshes.cone() })
    val rounded: GpuMesh by meshes[0]
    val roundedSoft: GpuMesh by meshes[1]
    val torus: GpuMesh by meshes[2]
    val chevron: GpuMesh by meshes[3]
    val cone: GpuMesh by meshes[4]
    private val savedLight = FloatArray(9)
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
        val sc = ctx.scene
        sc.lightDir.copyInto(savedLight, 0); sc.lightColor.copyInto(savedLight, 3); sc.ambient.copyInto(savedLight, 6)
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
        game = QuestGames.create(id, this)?.also { it.start(); it.menu?.let { m -> placeMenu(m) } }
        hud.dirty = true
    }

    fun exit() = onExit()

    // ---------------------------------------------------------------- space
    fun toWorld(x: Float, y: Float, z: Float, out: Vec3 = Vec3()): Vec3 = origin.transformPoint(tmpIn.set(x, y, z), out)

    /** Hands pointing at a panel (the game's menu, the HUD) this frame: their pinch is a click there, not a game action. */
    val uiBusy = BooleanArray(2)
    private val hj = Array(4) { Vec3() }

    /** Where hand [i]'s thumb and index meet, in game space; false when the hand is not tracked. */
    fun pinchPoint(i: Int, out: Vec3): Boolean {
        val h = ctx.input.hands[i]
        if (!h.isUsable) return false
        hj[0].set(h.joints[HandJoint.THUMB_TIP]).add(h.joints[HandJoint.INDEX_TIP]).scale(0.5f)
        toGame(hj[0], out)
        return true
    }

    /** Rigid pose of hand [i] in game space (palm centre, fingers along -z); false when not tracked. */
    fun handPose(i: Int, out: Pose): Boolean {
        val h = ctx.input.hands[i]
        if (!h.isUsable) return false
        toGame(h.joints[HandJoint.WRIST], hj[0]); toGame(h.joints[HandJoint.INDEX_MCP], hj[1])
        toGame(h.joints[HandJoint.PINKY_MCP], hj[2]); toGame(h.joints[HandJoint.MIDDLE_MCP], hj[3])
        com.hyperxr.input.HandGrip.handPose(hj[0], hj[1], hj[2], hj[3], out)
        return true
    }
    fun toGame(w: Vec3, out: Vec3): Vec3 = inv.transformPoint(w, out)
    fun dirToGame(w: Vec3, out: Vec3): Vec3 = inv.orientation.rotate(w, out)
    private val tmpIn = Vec3()

    // ---------------------------------------------------------------- per frame
    private val w = Vec3()
    private val wr = Vec3(); private val mc = Vec3(); private val im = Vec3(); private val pm = Vec3()
    private val fwd = Vec3(); private val side = Vec3(); private val nd = Vec3()
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
            // the hand's direction: wrist to knuckles, turned out of the thumb side as the hand closes
            toGame(h.joints[HandJoint.WRIST], wr); toGame(h.joints[HandJoint.MIDDLE_MCP], mc)
            toGame(h.joints[HandJoint.INDEX_MCP], im); toGame(h.joints[HandJoint.PINKY_MCP], pm)
            fwd.set(mc).sub(wr).normalize(); side.set(im).sub(pm).normalize()
            val closed = if (h.gesture == Gesture.FIST || h.gesture == Gesture.GRAB) 0.85f else 0.15f
            nd.set(fwd).scale(1f - closed).addScaled(side, closed).addScaled(fwd, 0.25f).normalize()
            g.prevDir.set(g.dir)
            if (!g.wasUsable) { g.prevTip.set(g.tip); g.prevPalm.set(g.palm); g.dir.set(nd); g.prevDir.set(nd); g.trailCount = 0 }
            else g.dir.lerp(nd, 0.55f).normalize()
            if (dt > 0f) {
                val vx = (g.palm.x - g.prevPalm.x) / dt; val vy = (g.palm.y - g.prevPalm.y) / dt; val vz = (g.palm.z - g.prevPalm.z) / dt
                g.velocity.set(g.velocity.x * 0.4f + vx * 0.6f, g.velocity.y * 0.4f + vy * 0.6f, g.velocity.z * 0.4f + vz * 0.6f)
                g.speed = g.velocity.length()
            }
            g.fist = h.gesture == Gesture.FIST || h.gesture == Gesture.GRAB
            g.pinch = h.gesture == Gesture.PINCH
        }
        particles.update(dt * timeScale)
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
        game?.menu?.let { if (it.visible) it.update(dt) }
    }

    private val lv = Vec3(); private val lw = Vec3()
    /**
     * The first seconds of a game run under the native crash guard: if the GPU driver brought the
     * app down while drawing it (shadows, the sky shader), the game opens in safe mode next time,
     * plain sky and no shadows, instead of crashing again.
     */
    private val renderStep = "game-render:$id"
    val safeMode = com.hyperxr.diagnostics.NativeCrashGuard.blocked(renderStep)
    private var frames = 0

    fun draw() {
        val g = game ?: return
        val sc = ctx.scene
        if (!safeMode) {
            if (frames == 0) com.hyperxr.diagnostics.NativeCrashGuard.begin(renderStep)
            if (frames == GUARDED_FRAMES) com.hyperxr.diagnostics.NativeCrashGuard.end(renderStep)
        }
        frames++
        g.look?.let { l ->
            sky.look = l
            sc.background = com.hyperxr.rendering.Background.COLOR
            if (safeMode) { sc.clear[0] = l.horizon[0]; sc.clear[1] = l.horizon[1]; sc.clear[2] = l.horizon[2] } else sc.painter = sky
            // the light comes from the sun
            dirToWorld(lv.set(-l.sunDir[0], -l.sunDir[1], -l.sunDir[2]).normalize(), lw)
            sc.lightDir[0] = lw.x; sc.lightDir[1] = lw.y; sc.lightDir[2] = lw.z
            // a look may give RGBA colours: only RGB is used
            l.light.copyInto(sc.lightColor, 0, 0, minOf(3, l.light.size, sc.lightColor.size))
            l.ambient.copyInto(sc.ambient, 0, 0, minOf(3, l.ambient.size, sc.ambient.size))
            sc.fog[0] = l.horizon[0]; sc.fog[1] = l.horizon[1]; sc.fog[2] = l.horizon[2]; sc.fog[3] = l.objectFog
        }
        if (g.hidesHands) ctx.hideHands = true
        if (g.shadowRadius > 0f && !safeMode) { toWorld(0f, 0f, g.shadowZ, sc.shadowCenter); sc.shadowRadius = g.shadowRadius }
        g.draw()
        particles.draw()
        hud.submit(sc)
        g.menu?.let { if (it.visible) it.submit(sc) }
    }

    fun dirToWorld(g: Vec3, out: Vec3): Vec3 = origin.orientation.rotate(g, out)

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

    private val painting = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Future<android.graphics.Bitmap>>()
    private val painter = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "hx-game-textures").apply { isDaemon = true } }

    /**
     * A procedural material (GameTextures), tiling, made once. It is painted on a worker thread
     * (it takes a moment on slow phones): 0 until it is ready, the surface is plain meanwhile.
     */
    fun texture(name: String): Int {
        textures[name]?.let { return it }
        val f = painting.getOrPut(name) { painter.submit(java.util.concurrent.Callable { GameTextures.make(name) }) }
        if (!f.isDone) return 0
        painting.remove(name)
        val bmp = runCatching { f.get() }.getOrNull() ?: return 0.also { textures[name] = 0 }
        val t = com.hyperxr.rendering.Gl.uploadBitmap(bmp, mipmap = true)
        com.hyperxr.rendering.Gl.repeatTexture(t)
        bmp.recycle()
        textures[name] = t
        return t
    }

    /** Draws a model the game loaded itself (not from the assets) at game position, turned by [yaw], scaled. */
    fun drawGpuModel(m: GpuModel, x: Float, y: Float, z: Float, yaw: Float = 0f, scale: Float = 1f, shadow: Boolean = true, tint: FloatArray? = null) {
        val d = ctx.scene.addDraw()
        d.model = m
        d.castShadow = shadow
        (tint ?: WHITE).copyInto(d.color)
        toWorld(x, y, z, pos)
        Quat.fromAxisAngle(0f, 1f, 0f, yaw, rot)
        origin.orientation.mul(rot, rot)
        Mat4.compose(pos, rot, scale, scale, scale, d.matrix)
    }

    /** Puts a game's [QuestGame.menu] to the right of where the player looked at the start. */
    fun placeMenu(p: com.hyperxr.spatialui.XrPanel) {
        toWorld(1.0f, 1.45f, -1.15f, p.pose.position)
        val q = Quat.fromAxisAngle(0f, 1f, 0f, -0.65f)
        origin.orientation.mul(q, p.pose.orientation)
    }

    /** Draws a model at game position, turned by [yaw] (and [pitch], [roll]), scaled, tinted. */
    fun drawModel(path: String, x: Float, y: Float, z: Float, yaw: Float = 0f, scale: Float = 1f, tint: FloatArray? = null, pitch: Float = 0f, roll: Float = 0f, orientation: Quat? = null,
                  shadow: Boolean = true, gloss: Float = 0f, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f) {
        val m = model(path) ?: return
        val d = ctx.scene.addDraw()
        d.model = m
        d.castShadow = shadow
        d.gloss = gloss
        (tint ?: WHITE).copyInto(d.color)
        toWorld(x, y, z, pos)
        if (orientation != null) origin.orientation.mul(orientation, rot)
        else {
            Quat.fromAxisAngle(0f, 1f, 0f, yaw, rot)
            if (pitch != 0f) rot.mul(Quat.fromAxisAngle(1f, 0f, 0f, pitch, q2), rot)
            if (roll != 0f) rot.mul(Quat.fromAxisAngle(0f, 0f, 1f, roll, q2), rot)
            origin.orientation.mul(rot, rot)
        }
        Mat4.compose(pos, rot, scale * sx, scale * sy, scale * sz, d.matrix)
    }

    /** A box (centre, size) in game space, optionally turned by [yaw] or a full [orientation]. */
    fun drawBox(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: FloatArray, yaw: Float = 0f, orientation: Quat? = null, unlit: Boolean = false, gloss: Float = 0.2f,
                tex: String? = null, tile: Float = 0f, metal: Float = 0f, shadow: Boolean = true) =
        drawMesh(ctx.renderer.cube, x, y, z, sx, sy, sz, color, yaw, orientation, unlit, gloss, tex, tile, metal, shadow)

    /** A box with rounded edges ([soft]: rounder). */
    fun drawRounded(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: FloatArray, yaw: Float = 0f, orientation: Quat? = null, gloss: Float = 0.5f, soft: Boolean = false,
                    metal: Float = 0f, tex: String? = null, tile: Float = 0f, unlit: Boolean = false) =
        drawMesh(if (soft) roundedSoft else rounded, x, y, z, sx, sy, sz, color, yaw, orientation, unlit, gloss, tex, tile, metal, true)

    fun drawSphere(x: Float, y: Float, z: Float, r: Float, color: FloatArray, unlit: Boolean = false, gloss: Float = 0.4f, shadow: Boolean = true, metal: Float = 0f) =
        drawMesh(ctx.renderer.sphere, x, y, z, r * 2, r * 2, r * 2, color, 0f, null, unlit, gloss, null, 0f, metal, shadow)

    /** A sphere squashed to [sx], [sy], [sz] (diameters), turned. */
    fun drawEllipsoid(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: FloatArray, yaw: Float = 0f, orientation: Quat? = null, gloss: Float = 0.4f, unlit: Boolean = false) =
        drawMesh(ctx.renderer.sphere, x, y, z, sx, sy, sz, color, yaw, orientation, unlit, gloss, null, 0f, 0f, true)

    fun drawCylinder(x: Float, y: Float, z: Float, r: Float, h: Float, color: FloatArray, orientation: Quat? = null, unlit: Boolean = false, gloss: Float = 0.3f, metal: Float = 0f,
                     tex: String? = null, tile: Float = 0f, shadow: Boolean = true) =
        drawMesh(ctx.renderer.cylinder, x, y, z, r * 2, h, r * 2, color, 0f, orientation, unlit, gloss, tex, tile, metal, shadow)

    fun drawTorus(x: Float, y: Float, z: Float, r: Float, color: FloatArray, orientation: Quat? = null, unlit: Boolean = false, glow: Boolean = false, thick: Float = 1f) {
        val d = drawMesh(torus, x, y, z, r * 2, r * 2 * thick, r * 2, color, 0f, orientation, unlit, 0.5f, null, 0f, 0f, !glow)
        if (glow) d.additive = true
    }

    /** Any mesh, placed in game space. */
    fun drawMesh(mesh: GpuMesh, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: FloatArray, yaw: Float = 0f, orientation: Quat? = null, unlit: Boolean = false,
                 gloss: Float = 0.2f, tex: String? = null, tile: Float = 0f, metal: Float = 0f, shadow: Boolean = true): com.hyperxr.rendering.DrawItem {
        val d = ctx.scene.addDraw()
        d.mesh = mesh
        color.copyInto(d.color)
        d.unlit = unlit; d.gloss = gloss; d.metal = metal
        d.transparent = color[3] < 0.999f
        d.castShadow = shadow && !d.transparent && !unlit
        if (tex != null) {
            d.texture = texture(tex)
            if (d.texture == 0) { d.color[0] *= 0.8f; d.color[1] *= 0.78f; d.color[2] *= 0.75f }
            // tiles of [tile] metres across the box's two largest sides
            if (tile > 0f) { val a = maxOf(sx, sz); val b = if (sx >= sz) maxOf(sy, sz) else maxOf(sy, sx); d.uvScale[0] = a / tile; d.uvScale[1] = maxOf(b, minOf(sx, sz)) / tile }
        }
        toWorld(x, y, z, pos)
        if (orientation != null) origin.orientation.mul(orientation, rot)
        else origin.orientation.mul(Quat.fromAxisAngle(0f, 1f, 0f, yaw, q2), rot)
        Mat4.compose(pos, rot, sx, sy, sz, d.matrix)
        return d
    }

    /** A thin beam from a to b (lasers, lines, the fishing line). */
    fun drawBeam(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: Float, color: FloatArray, unlit: Boolean = true, gloss: Float = 0.3f, metal: Float = 0f) {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4f) return
        // the cylinder's axis is y: turn y onto the beam
        val q = Quat.fromTo(0f, 1f, 0f, dx / len, dy / len, dz / len)
        drawCylinder((ax + bx) / 2, (ay + by) / 2, (az + bz) / 2, r, len, color, q, unlit, gloss, metal, shadow = !unlit)
    }

    private val glowC = FloatArray(4)
    /** A soft glowing ball (added light: lamps, sparks, muzzle flashes). */
    fun glow(x: Float, y: Float, z: Float, r: Float, color: FloatArray, strength: Float = 1f) {
        glowC[0] = color[0]; glowC[1] = color[1]; glowC[2] = color[2]; glowC[3] = (color[3] * strength).coerceIn(0f, 1f)
        val d = drawMesh(ctx.renderer.sphere, x, y, z, r * 2, r * 2, r * 2, glowC, unlit = true, shadow = false)
        d.additive = true
    }

    /**
     * A glowing beam: a white-hot core and two soft halos in [color] (laser bolts, sabres, neon
     * tubes). [r]: the core radius.
     */
    fun glowBeam(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: Float, color: FloatArray, strength: Float = 1f, core: Boolean = true) {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4f) return
        val q = Quat.fromTo(0f, 1f, 0f, dx / len, dy / len, dz / len)
        val cx = (ax + bx) / 2; val cy = (ay + by) / 2; val cz = (az + bz) / 2
        if (core) {
            glowC[0] = 0.6f + color[0] * 0.4f; glowC[1] = 0.6f + color[1] * 0.4f; glowC[2] = 0.6f + color[2] * 0.4f; glowC[3] = 1f
            drawMesh(ctx.renderer.cylinder, cx, cy, cz, r * 2, len, r * 2, glowC, 0f, q, unlit = true, shadow = false)
        }
        for ((k, a) in GLOW_LAYERS) {
            glowC[0] = color[0]; glowC[1] = color[1]; glowC[2] = color[2]; glowC[3] = (a * strength).coerceIn(0f, 1f)
            drawMesh(ctx.renderer.cylinder, cx, cy, cz, r * 2 * k, len + r * k, r * 2 * k, glowC, 0f, q, unlit = true, shadow = false).additive = true
        }
    }

    /** Turns local axes (+Y along [dir]) for meshes placed along a direction. */
    fun along(dx: Float, dy: Float, dz: Float): Quat {
        val l = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
        return Quat.fromTo(0f, 1f, 0f, dx / l, dy / l, dz / l)
    }

    fun play(sound: Int, x: Float, y: Float, z: Float, volume: Float = 1f, pitch: Float = 1f) {
        ctx.audio.play(sound, volume, pitch, false, toWorld(x, y, z, Vec3()), ctx.input.head)
    }

    fun release() {
        com.hyperxr.diagnostics.NativeCrashGuard.end(renderStep)
        runCatching { game?.release() }; game = null
        models.values.forEach { it?.release() }; models.clear()
        textures.values.forEach { com.hyperxr.rendering.Gl.deleteTexture(it) }; textures.clear()
        painter.shutdownNow(); painting.clear()
        sky.release()
        meshes.forEach { if (it.isInitialized()) it.value.release() }
        val sc = ctx.scene
        savedLight.copyInto(sc.lightDir, 0, 0, 3); savedLight.copyInto(sc.lightColor, 0, 3, 6); savedLight.copyInto(sc.ambient, 0, 6, 9)
        hud.release()
    }

    companion object {
        val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
        private const val GUARDED_FRAMES = 300
        private val GLOW_LAYERS = arrayOf(2.6f to 0.55f, 6f to 0.22f)
    }
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

/**
 * Sparks (glowing streaks), debris (lit chips that tumble) and blobs (juice, water drops), pooled;
 * every game shares them. Time runs at [GameHost.timeScale].
 */
class Particles(private val host: GameHost) {
    private class P {
        var x = 0f; var y = 0f; var z = 0f; var vx = 0f; var vy = 0f; var vz = 0f
        var age = 0f; var life = 1f; var size = 0.01f; var gravity = 0f; var drag = 0f; var spin = 0f; var kind = 0
        val c = FloatArray(4)
    }
    private val pool = ArrayList<P>()
    private var count = 0
    private val col = FloatArray(4)

    private fun add(): P? {
        if (count >= MAX) return null
        if (count == pool.size) pool += P()
        return pool[count++].also { it.age = 0f }
    }

    fun emit(kind: Int, x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float, color: FloatArray, life: Float, size: Float, gravity: Float = 4f, drag: Float = 0.5f) {
        val p = add() ?: return
        p.kind = kind; p.x = x; p.y = y; p.z = z; p.vx = vx; p.vy = vy; p.vz = vz
        color.copyInto(p.c); p.life = life; p.size = size; p.gravity = gravity; p.drag = drag
        p.spin = (rnd.nextFloat() - 0.5f) * 14f
    }

    /** [n] particles flying out of a point at up to [speed] m/s, plus [up] m/s upwards. */
    fun burst(kind: Int, x: Float, y: Float, z: Float, n: Int, speed: Float, color: FloatArray, life: Float, size: Float, gravity: Float = 4f, up: Float = 0f,
              bx: Float = 0f, by: Float = 0f, bz: Float = 0f) {
        repeat(n) {
            var dx: Float; var dy: Float; var dz: Float
            do { dx = rnd.nextFloat() * 2 - 1; dy = rnd.nextFloat() * 2 - 1; dz = rnd.nextFloat() * 2 - 1 } while (dx * dx + dy * dy + dz * dz > 1f)
            val s = speed * (0.35f + rnd.nextFloat() * 0.65f)
            emit(kind, x, y, z, dx * s + bx, dy * s + up + by, dz * s + bz, color, life * (0.6f + rnd.nextFloat() * 0.6f), size * (0.6f + rnd.nextFloat() * 0.8f), gravity)
        }
    }

    fun clear() { count = 0 }

    fun update(dt: Float) {
        if (dt <= 0f) return
        var i = 0
        while (i < count) {
            val p = pool[i]
            p.age += dt
            if (p.age >= p.life || p.y < -60f) { pool[i] = pool[count - 1]; pool[count - 1] = p; count--; continue }
            val k = 1f - p.drag * dt
            p.vx *= k; p.vy = p.vy * k - p.gravity * dt; p.vz *= k
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
            // blobs and debris stop on the floor
            if (p.kind != SPARK && p.y < p.size && p.vy < 0f && p.y > -0.05f) { p.y = p.size; p.vy *= -0.25f; p.vx *= 0.5f; p.vz *= 0.5f; p.spin *= 0.5f }
            i++
        }
    }

    fun draw() {
        for (i in 0 until count) {
            val p = pool[i]
            val f = 1f - p.age / p.life
            when (p.kind) {
                SPARK -> {
                    col[0] = p.c[0]; col[1] = p.c[1]; col[2] = p.c[2]; col[3] = p.c[3] * f
                    val sp = kotlin.math.sqrt(p.vx * p.vx + p.vy * p.vy + p.vz * p.vz)
                    val len = (sp * 0.035f).coerceIn(p.size * 2f, 0.25f)
                    if (sp < 1e-3f) { host.glow(p.x, p.y, p.z, p.size, col); continue }
                    val q = host.along(p.vx, p.vy, p.vz)
                    host.drawMesh(host.ctx.renderer.cylinder, p.x - p.vx / sp * len / 2, p.y - p.vy / sp * len / 2, p.z - p.vz / sp * len / 2, p.size * 2, len, p.size * 2, col, 0f, q, unlit = true, shadow = false).additive = true
                }
                DEBRIS -> {
                    col[0] = p.c[0]; col[1] = p.c[1]; col[2] = p.c[2]; col[3] = if (f < 0.25f) f * 4f * p.c[3] else p.c[3]
                    val a = p.age * p.spin
                    host.drawMesh(host.ctx.renderer.cube, p.x, p.y, p.z, p.size * 2, p.size * 1.3f, p.size * 1.6f, col, a, Quat.fromAxisAngle(0.6f, 0.8f, 0f, a, q), gloss = 0.8f, shadow = false)
                }
                else -> {
                    col[0] = p.c[0]; col[1] = p.c[1]; col[2] = p.c[2]; col[3] = if (f < 0.3f) f / 0.3f * p.c[3] else p.c[3]
                    host.drawSphere(p.x, p.y, p.z, p.size * (0.6f + 0.4f * f), col, gloss = 0.7f, shadow = false)
                }
            }
        }
    }

    private val q = Quat()

    companion object {
        const val SPARK = 0; const val DEBRIS = 1; const val BLOB = 2
        private const val MAX = 400
        private val rnd = java.util.Random()
    }
}
