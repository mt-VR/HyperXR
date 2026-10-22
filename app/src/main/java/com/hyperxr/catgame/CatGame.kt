package com.hyperxr.catgame

import android.graphics.Color
import com.hyperxr.joycon.JoyConSide
import com.hyperxr.math.Mat4
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.physics.Body
import com.hyperxr.physics.Hold
import com.hyperxr.physics.Material
import com.hyperxr.physics.RigidWorld
import com.hyperxr.physics.Shape
import com.hyperxr.physics.ShapeType
import com.hyperxr.rendering.Background
import com.hyperxr.rendering.GpuMesh
import com.hyperxr.rendering.Gl
import com.hyperxr.rendering.TextRenderer
import com.hyperxr.settings.ControllerAction
import com.hyperxr.xr.ImmersiveActivity
import com.hyperxr.xr.XrContent
import com.hyperxr.xr.XrContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Fullscreen VR activity of the cat game. */
class CatGameActivity : ImmersiveActivity() {
    override fun createContent(): XrContent = CatGame { finish() }
}

/**
 * "Gato vs. Vovó": an original game inspired by the idea of playing a cat in a grandmother's house
 * (no assets or code from any existing game). You are a cat at cat height, moved with the
 * Joy-Cons. Grandma goes through her chores; knock things over, carry small objects in your mouth
 * and throw them at her, dodge her slipper and her spray bottle, and wear her out before she
 * finishes the day's chores.
 *
 * Controls (two Joy-Cons, a Pro Controller, or one Joy-Con):
 *  - left stick: walk (where you look is forward) · right stick: turn
 *  - A: jump (cats reach tables and counters) · B: dodge dash
 *  - ZR / R: hold to pick up with the mouth, release to throw where you look
 *  - ZL / L: paw swat (knocks things off furniture) · X / Y: meow (grandma comes)
 *  - + / −: pause
 */
class CatGame(private val exit: () -> Unit) : XrContent {
    private lateinit var ctx: XrContext
    private val rnd = Random(42)
    private val world = RigidWorld().apply { substeps = 6; iterations = 6; positionIterations = 4; slop = 0.002f }

    // ------------------------------------------------------------------ house
    /** Furniture block for the cat controller and the physics: axis-aligned, from [bottom] to [top]. */
    private class Block(val minX: Float, val maxX: Float, val minZ: Float, val maxZ: Float, val top: Float, val bottom: Float = 0f, val walkable: Boolean = true)
    private val blocks = ArrayList<Block>()

    /** Static visual: a textured box. */
    private class Visual(val center: Vec3, val half: Vec3, val tex: Int, val color: FloatArray, val gloss: Float = 0.1f, val metal: Float = 0f, val mesh: Int = 0, val unlit: Boolean = false, val shadow: Boolean = true)
    private val visuals = ArrayList<Visual>()

    private enum class Kind { MUG, PLATE, BOOK, VASE, ORANGE, APPLE, REMOTE, CUSHION, JAR, POT, FRAME }
    private class Prop(val body: Body, val kind: Kind, val tex: Int, val color: FloatArray, val gloss: Float, val metal: Float) {
        var thrownAt = -10.0
        var lastHitGrandma = -10.0
        val home = Vec3()
    }
    private val props = ArrayList<Prop>()
    private lateinit var soupPot: Prop

    // ------------------------------------------------------------------ cat
    private val cat = Vec3(0f, 0f, 1.2f)          // feet position
    private var catYaw = 0f
    private val catVel = Vec3()
    private var catVy = 0f
    private var onGround = true
    private var catHp = 100f
    private var lastHurt = -10.0
    private var dashTime = 0f
    private var dashCooldown = 0f
    private val dashDir = Vec3()
    private var swatTime = 0f
    private var held: Prop? = null
    private var hold: Hold? = null
    private lateinit var catBody: Body
    private val CAT_RADIUS = 0.13f
    private val EYE = 0.26f
    private val CAT_GROUP = 77
    private var walkPhase = 0f

    // ------------------------------------------------------------------ grandma
    private enum class GState { WALK, CHORE, CHASE, WINDUP, STRIKE, SPRAY, STUNNED, DEFEATED, FINISHED }
    private class Chore(val name: String, val stops: List<Vec3>, val face: Float, val seconds: Float, val anim: Int)
    private val chores = listOf(
        Chore("Regar as plantas", listOf(Vec3(3.0f, 0f, -2.7f), Vec3(-3.1f, 0f, 2.6f)), 0f, 9f, 0),
        Chore("Cozinhar a sopa", listOf(Vec3(-2.3f, 0f, -2.45f)), 0f, 22f, 1),
        Chore("Lavar a louça", listOf(Vec3(-0.9f, 0f, -2.45f)), 0f, 18f, 2),
        Chore("Varrer a sala", listOf(Vec3(1.5f, 0f, 1.0f), Vec3(-0.5f, 0f, 0.4f), Vec3(1.8f, 0f, -0.6f)), 0f, 7f, 3),
        Chore("Tirar o pó da estante", listOf(Vec3(3.05f, 0f, 1.6f)), 0f, 16f, 4),
    )
    private var choreIndex = 0
    private var stopIndex = 0
    private var choreProgress = 0f     // seconds spent on the current stop
    private val gPos = Vec3(-2.6f, 0f, -1.0f)
    private var gYaw = 0f
    private var gState = GState.WALK
    private var gStateTime = 0f
    private var gHp = 100f
    private var gWalkPhase = 0f
    private var gSpeed = 0f
    private var lastSeenCat = -10.0
    private var gStuckTime = 0f
    private var sidestep = 0f
    private var sayText = ""
    private var sayUntil = 0.0
    private val gBodyCol = arrayOfNulls<Body>(2)
    private val spray = ArrayList<Pair<Vec3, Vec3>>() // droplet position, velocity

    // ------------------------------------------------------------------ game flow
    private enum class Phase { INTRO, PLAY, PAUSE, WON, LOST }
    private var phase = Phase.INTRO
    private var endReason = ""
    private var time = 0.0
    private var prevButtons = 0
    private var twoSticks = false

    // ------------------------------------------------------------------ rendering
    private var texFloor = 0; private var texWall = 0; private var texTiles = 0; private var texSofa = 0; private var texRug = 0
    private var texDress = 0; private var texKnit = 0; private var texWood = 0; private var texDark = 0; private var texCushion = 0
    private val texBooks = IntArray(4)
    private var sndMeow = 0; private var sndThud = 0; private var sndClink = 0; private var sndOuch = 0; private var sndSwoosh = 0; private var sndSpray = 0; private var sndHurt = 0
    private val hud = CatHud()
    private val toTracking = FloatArray(16)
    private val world16 = FloatArray(16)
    private val qInv = Quat()
    private val tmp = Vec3(); private val tmp2 = Vec3(); private val tmp3 = Vec3()
    private val tmpQ = Quat()
    private val hw = Vec3()

    // ================================================================== lifecycle
    override fun onGlInit(ctx: XrContext) {
        this.ctx = ctx
        texFloor = Gl.uploadBitmap(CatTextures.floor(), mipmap = true)
        texWall = Gl.uploadBitmap(CatTextures.wallpaper(), mipmap = true)
        texTiles = Gl.uploadBitmap(CatTextures.tiles(), mipmap = true)
        texSofa = Gl.uploadBitmap(CatTextures.fabric(Color.rgb(96, 120, 92), 1), mipmap = true)
        texCushion = Gl.uploadBitmap(CatTextures.fabric(Color.rgb(196, 140, 72), 2), mipmap = true)
        texRug = Gl.uploadBitmap(CatTextures.rug(), mipmap = true)
        texDress = Gl.uploadBitmap(CatTextures.dress(), mipmap = true)
        texKnit = Gl.uploadBitmap(CatTextures.knit(Color.rgb(120, 70, 110)), mipmap = true)
        texWood = Gl.uploadBitmap(com.hyperxr.lab.LabTextures.wood(256, 256, Color.rgb(150, 98, 58), Color.rgb(104, 64, 36), 9), mipmap = true)
        texDark = Gl.uploadBitmap(com.hyperxr.lab.LabTextures.wood(256, 256, Color.rgb(92, 60, 40), Color.rgb(60, 38, 24), 4), mipmap = true)
        listOf(Color.rgb(140, 40, 40), Color.rgb(40, 70, 120), Color.rgb(60, 100, 60), Color.rgb(150, 120, 60)).forEachIndexed { i, c -> texBooks[i] = Gl.uploadBitmap(CatTextures.book(c)) }
        val a = ctx.audio
        sndMeow = a.synth("saw", 520f, 860f, 0.45f, 0.35f, 0.04f, 0.3f)
        sndThud = a.synth("sine", 120f, 60f, 0.18f, 1f, 0.002f, 0.06f)
        sndClink = a.synth("sine", 2200f, 2000f, 0.3f, 0.5f, 0.001f, 0.08f)
        sndOuch = a.synth("square", 330f, 220f, 0.28f, 0.35f, 0.01f, 0.12f)
        sndSwoosh = a.synth("noise", 0f, 0f, 0.16f, 0.4f, 0.01f, 0.06f)
        sndSpray = a.synth("noise", 0f, 0f, 0.3f, 0.3f, 0.005f, 0.12f)
        sndHurt = a.synth("saw", 900f, 380f, 0.35f, 0.4f, 0.005f, 0.15f)
        buildHouse()
        reset()
    }

    override fun onGlRelease() {
        for (t in intArrayOf(texFloor, texWall, texTiles, texSofa, texRug, texDress, texKnit, texWood, texDark, texCushion) + texBooks) Gl.deleteTexture(t)
        hud.release()
    }

    override fun onAction(ctx: XrContext, action: ControllerAction, pressed: Boolean, side: JoyConSide?) {
        // the viewer button / screen tap starts the game too
        if (pressed && action == ControllerAction.SELECT && phase == Phase.INTRO) phase = Phase.PLAY
    }

    private fun reset() {
        cat.set(0.4f, 0f, 2.0f); catYaw = 0f; catVel.set(0f, 0f, 0f); catVy = 0f; catHp = 100f
        held?.let { releaseHeld() }
        gPos.set(-2.6f, 0f, -1.0f); gYaw = 0f; gHp = 100f; gState = GState.WALK; gStateTime = 0f
        choreIndex = 0; stopIndex = 0; choreProgress = 0f
        spray.clear()
        for (p in props) { p.body.teleport(p.home, Quat()); p.body.linearVelocity.set(0f, 0f, 0f); p.body.angularVelocity.set(0f, 0f, 0f); p.body.wake() }
        time = 0.0
    }

    // ================================================================== house construction
    private fun block(minX: Float, maxX: Float, minZ: Float, maxZ: Float, top: Float, bottom: Float = 0f, walkable: Boolean = true) {
        blocks += Block(minX, maxX, minZ, maxZ, top, bottom, walkable)
        val b = Body(Shape.box((maxX - minX) / 2, (top - bottom) / 2, (maxZ - minZ) / 2), Material.WOOD, Body.Type.STATIC)
        b.teleport(Vec3((minX + maxX) / 2, (top + bottom) / 2, (minZ + maxZ) / 2), Quat())
        world.add(b)
    }

    private fun vis(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, tex: Int, color: FloatArray = WHITE, gloss: Float = 0.1f, metal: Float = 0f, mesh: Int = 0, unlit: Boolean = false, shadow: Boolean = true) {
        visuals += Visual(Vec3(cx, cy, cz), Vec3(hx, hy, hz), tex, color, gloss, metal, mesh, unlit, shadow)
    }

    /** Furniture box that is both drawn and solid. */
    private fun solid(minX: Float, maxX: Float, minZ: Float, maxZ: Float, top: Float, tex: Int, color: FloatArray = WHITE, bottom: Float = 0f, gloss: Float = 0.15f) {
        block(minX, maxX, minZ, maxZ, top, bottom)
        vis((minX + maxX) / 2, (top + bottom) / 2, (minZ + maxZ) / 2, (maxX - minX) / 2, (top - bottom) / 2, (maxZ - minZ) / 2, tex, color, gloss)
    }

    private fun buildHouse() {
        // floor and walls (8 x 7 m, 2.6 m high)
        val floor = Body(Shape.box(4.2f, 0.1f, 3.8f), Material.WOOD, Body.Type.STATIC).apply { teleport(Vec3(0f, -0.1f, 0f), Quat()) }
        world.add(floor)
        vis(0f, -0.05f, 0f, 4f, 0.05f, 3.5f, texFloor, gloss = 0.35f, shadow = false)
        vis(0f, 2.65f, 0f, 4f, 0.05f, 3.5f, 0, floatArrayOf(0.93f, 0.92f, 0.88f, 1f), shadow = false) // ceiling
        block(-4.2f, 4.2f, -3.7f, -3.5f, 2.6f, walkable = false); block(-4.2f, 4.2f, 3.5f, 3.7f, 2.6f, walkable = false)
        block(-4.2f, -4.0f, -3.5f, 3.5f, 2.6f, walkable = false); block(4.0f, 4.2f, -3.5f, 3.5f, 2.6f, walkable = false)
        vis(0f, 1.3f, -3.55f, 4f, 1.3f, 0.05f, texWall, shadow = false); vis(0f, 1.3f, 3.55f, 4f, 1.3f, 0.05f, texWall, shadow = false)
        vis(-4.05f, 1.3f, 0f, 0.05f, 1.3f, 3.5f, texWall, shadow = false); vis(4.05f, 1.3f, 0f, 0.05f, 1.3f, 3.5f, texWall, shadow = false)
        // skirting boards and windows (bright daylight panes)
        for (z in floatArrayOf(-3.49f, 3.49f)) vis(0f, 0.05f, z, 4f, 0.05f, 0.012f, texDark, shadow = false)
        for (x in floatArrayOf(-3.99f, 3.99f)) vis(x, 0.05f, 0f, 0.012f, 0.05f, 3.5f, texDark, shadow = false)
        for (x in floatArrayOf(-1.8f, 1.8f)) {
            vis(x, 1.55f, 3.495f, 0.62f, 0.55f, 0.01f, 0, floatArrayOf(0.85f, 0.93f, 1f, 1f), unlit = true, shadow = false)
            vis(x, 1.55f, 3.48f, 0.66f, 0.03f, 0.02f, texWood, shadow = false); vis(x, 1.55f, 3.48f, 0.03f, 0.58f, 0.02f, texWood, shadow = false)
        }
        vis(3.995f, 1.55f, -1.2f, 0.01f, 0.55f, 0.5f, 0, floatArrayOf(0.85f, 0.93f, 1f, 1f), unlit = true, shadow = false)
        // rug and ceiling lamp
        vis(-1.0f, 0.004f, 1.5f, 1.4f, 0.004f, 0.9f, texRug, gloss = 0f, shadow = false)
        vis(0f, 2.45f, 0f, 0.22f, 0.12f, 0.22f, 0, floatArrayOf(1f, 0.95f, 0.8f, 1f), mesh = 2, unlit = true, shadow = false)

        // kitchen counter along the north wall, stove and sink, upper cabinets
        solid(-3.95f, 0.6f, -3.48f, -2.88f, 0.9f, texWood, bottom = 0f)
        vis(-1.68f, 0.915f, -3.18f, 2.28f, 0.015f, 0.31f, 0, floatArrayOf(0.82f, 0.8f, 0.76f, 1f), gloss = 0.7f) // stone top
        vis(-2.3f, 0.935f, -3.15f, 0.3f, 0.01f, 0.25f, 0, floatArrayOf(0.12f, 0.12f, 0.13f, 1f), gloss = 0.8f, metal = 0.4f) // hob
        vis(-0.9f, 0.925f, -3.15f, 0.28f, 0.012f, 0.2f, 0, floatArrayOf(0.7f, 0.72f, 0.75f, 1f), gloss = 0.9f, metal = 1f) // sink
        vis(-1.68f, 1.7f, -3.35f, 2.28f, 0.4f, 0.15f, texWood, shadow = false) // cabinets
        vis(-1.68f, 1.15f, -3.47f, 2.28f, 0.25f, 0.02f, texTiles, gloss = 0.6f, shadow = false) // backsplash
        // dining table and four chairs
        solid(1.5f, 2.9f, -2.3f, -1.4f, 0.76f, texDark, bottom = 0.72f)
        for (sx in floatArrayOf(1.56f, 2.84f)) for (sz in floatArrayOf(-2.24f, -1.46f)) vis(sx, 0.36f, sz, 0.03f, 0.36f, 0.03f, texDark)
        for ((cx, cz) in listOf(1.85f to -2.6f, 2.55f to -2.6f, 1.85f to -1.1f, 2.55f to -1.1f)) {
            solid(cx - 0.21f, cx + 0.21f, cz - 0.2f, cz + 0.2f, 0.47f, texDark, bottom = 0.43f)
            for (lx in floatArrayOf(-0.18f, 0.18f)) for (lz in floatArrayOf(-0.17f, 0.17f)) vis(cx + lx, 0.215f, cz + lz, 0.02f, 0.215f, 0.02f, texDark)
            val back = if (cz < -2f) cz - 0.19f else cz + 0.19f
            vis(cx, 0.75f, back, 0.21f, 0.28f, 0.015f, texDark)
        }
        // sofa (seat, back, arms) on the south wall, coffee table, TV stand, bookshelf
        solid(-2.6f, 0.6f, 2.55f, 3.4f, 0.45f, texSofa, gloss = 0f)
        solid(-2.6f, 0.6f, 3.15f, 3.45f, 0.88f, texSofa, gloss = 0f)
        solid(-2.85f, -2.6f, 2.55f, 3.45f, 0.62f, texSofa, gloss = 0f); solid(0.6f, 0.85f, 2.55f, 3.45f, 0.62f, texSofa, gloss = 0f)
        solid(-1.55f, -0.45f, 1.25f, 1.8f, 0.43f, texWood, bottom = 0.39f)
        for (lx in floatArrayOf(-1.5f, -0.5f)) for (lz in floatArrayOf(1.3f, 1.75f)) vis(lx, 0.195f, lz, 0.025f, 0.195f, 0.025f, texWood)
        solid(-3.98f, -3.55f, 0.4f, 2.4f, 0.5f, texDark)
        vis(-3.9f, 1.05f, 1.4f, 0.04f, 0.38f, 0.66f, 0, floatArrayOf(0.05f, 0.05f, 0.06f, 1f), gloss = 0.9f) // TV
        solid(3.62f, 3.98f, 0.9f, 2.3f, 1.85f, texDark)
        for (y in floatArrayOf(0.45f, 0.9f, 1.35f)) vis(3.6f, y, 1.6f, 0.02f, 0.015f, 0.68f, texWood)
        // plant pots (static) with foliage
        for ((px, pz) in listOf(3.45f to -3.05f, -3.5f to 3.05f)) {
            solid(px - 0.2f, px + 0.2f, pz - 0.2f, pz + 0.2f, 0.4f, 0, floatArrayOf(0.62f, 0.3f, 0.2f, 1f))
            for (k in 0 until 6) {
                val a = k * 1.047f
                vis(px + cos(a) * 0.15f, 0.62f + (k % 2) * 0.12f, pz + sin(a) * 0.15f, 0.16f, 0.2f, 0.16f, 0, floatArrayOf(0.22f, 0.45f, 0.2f, 1f), mesh = 2)
            }
        }

        // loose objects
        fun prop(kind: Kind, x: Float, y: Float, z: Float, tex: Int = 0, color: FloatArray = WHITE): Prop {
            val (shape, mat, gloss, metal) = when (kind) {
                Kind.MUG -> Quad(Shape.capsule(0.042f, 0.02f), Material("caneca", 900f, 0.5f, 0.2f), 0.7f, 0f)
                Kind.PLATE -> Quad(Shape.box(0.11f, 0.008f, 0.11f), Material("prato", 1200f, 0.45f, 0.2f), 0.8f, 0f)
                Kind.BOOK -> Quad(Shape.box(0.075f, 0.11f, 0.02f), Material("livro", 700f, 0.6f, 0.1f), 0.1f, 0f)
                Kind.VASE -> Quad(Shape.capsule(0.06f, 0.08f), Material("vaso", 900f, 0.5f, 0.15f), 0.8f, 0f)
                Kind.ORANGE -> Quad(Shape.sphere(0.04f), Material("laranja", 900f, 0.7f, 0.35f), 0.3f, 0f)
                Kind.APPLE -> Quad(Shape.sphere(0.037f), Material("maçã", 850f, 0.7f, 0.3f), 0.5f, 0f)
                Kind.REMOTE -> Quad(Shape.box(0.025f, 0.01f, 0.09f), Material.PLASTIC, 0.5f, 0f)
                Kind.CUSHION -> Quad(Shape.box(0.2f, 0.06f, 0.2f), Material("almofada", 60f, 0.9f, 0.1f), 0f, 0f)
                Kind.JAR -> Quad(Shape.capsule(0.05f, 0.05f), Material("pote", 800f, 0.5f, 0.2f), 0.85f, 0f)
                Kind.POT -> Quad(Shape.capsule(0.11f, 0.03f), Material("panela", 1500f, 0.4f, 0.1f), 0.8f, 0.9f)
                Kind.FRAME -> Quad(Shape.box(0.1f, 0.13f, 0.012f), Material.WOOD, 0.4f, 0f)
            }
            val b = Body(shape, mat)
            b.teleport(Vec3(x, y + shape.halfExtents.y + 0.002f, z), Quat())
            world.add(b)
            return Prop(b, kind, tex, color, gloss, metal).also { it.home.set(b.position); props += it }
        }
        val cream = floatArrayOf(0.95f, 0.93f, 0.88f, 1f)
        // coffee table
        prop(Kind.MUG, -1.3f, 0.43f, 1.45f, color = floatArrayOf(0.85f, 0.25f, 0.2f, 1f))
        prop(Kind.REMOTE, -0.9f, 0.43f, 1.6f, color = floatArrayOf(0.1f, 0.1f, 0.12f, 1f))
        prop(Kind.ORANGE, -0.7f, 0.43f, 1.4f, color = floatArrayOf(1f, 0.55f, 0.1f, 1f))
        prop(Kind.BOOK, -1.05f, 0.43f, 1.35f, texBooks[0])
        // dining table
        prop(Kind.PLATE, 1.85f, 0.76f, -2.05f, color = cream); prop(Kind.PLATE, 2.55f, 0.76f, -1.65f, color = cream)
        prop(Kind.MUG, 2.2f, 0.76f, -1.9f, color = floatArrayOf(0.3f, 0.45f, 0.75f, 1f))
        prop(Kind.VASE, 2.25f, 0.76f, -1.75f, color = floatArrayOf(0.2f, 0.45f, 0.6f, 1f))
        for (i in 0 until 3) prop(Kind.APPLE, 1.8f + i * 0.1f, 0.76f, -1.6f, color = floatArrayOf(0.75f, 0.1f, 0.1f, 1f))
        // kitchen counter
        soupPot = prop(Kind.POT, -2.3f, 0.95f, -3.12f, color = floatArrayOf(0.75f, 0.76f, 0.78f, 1f))
        for (i in 0 until 3) prop(Kind.JAR, 0.0f + i * 0.14f, 0.9f, -3.25f, color = floatArrayOf(0.85f, 0.85f, 0.75f, 1f))
        prop(Kind.MUG, -1.5f, 0.9f, -3.0f, color = cream)
        prop(Kind.PLATE, -0.4f, 0.9f, -3.1f, color = cream)
        // TV stand, sofa, bookshelf
        prop(Kind.FRAME, -3.75f, 0.5f, 0.6f, texWood); prop(Kind.VASE, -3.75f, 0.5f, 2.2f, color = floatArrayOf(0.85f, 0.75f, 0.4f, 1f))
        prop(Kind.CUSHION, -2.0f, 0.45f, 2.9f, texCushion); prop(Kind.CUSHION, 0.0f, 0.45f, 2.9f, texCushion)
        for (i in 0 until 4) prop(Kind.BOOK, 3.55f, 0.47f, 1.1f + i * 0.08f, texBooks[i])
        for (i in 0 until 3) prop(Kind.BOOK, 3.55f, 0.92f, 1.6f + i * 0.08f, texBooks[(i + 1) % 4])
        catBody = Body(Shape.sphere(CAT_RADIUS), Material.HAND, Body.Type.KINEMATIC).also { it.group = CAT_GROUP; world.add(it) }
        for (i in 0..1) gBodyCol[i] = Body(if (i == 0) Shape.capsule(0.25f, 0.5f) else Shape.sphere(0.13f), Material.HAND, Body.Type.KINEMATIC).also { world.add(it) }
    }

    private data class Quad(val shape: Shape, val mat: Material, val gloss: Float, val metal: Float)

    // ================================================================== frame
    override fun onFrame(ctx: XrContext, dt: Float) {
        val d = dt.coerceIn(0f, 0.05f)
        time += d
        val input = readInput()
        when (phase) {
            Phase.INTRO -> if (input.pressed(BTN_A) || input.pressed(BTN_PLUS)) phase = Phase.PLAY
            Phase.PAUSE -> {
                if (input.pressed(BTN_A) || input.pressed(BTN_PLUS)) phase = Phase.PLAY
                if (input.pressed(BTN_B)) exit()
                if (input.pressed(BTN_X)) { reset(); phase = Phase.PLAY }
            }
            Phase.WON, Phase.LOST -> {
                if (input.pressed(BTN_A)) { reset(); phase = Phase.PLAY }
                if (input.pressed(BTN_B)) exit()
            }
            Phase.PLAY -> {
                if (input.pressed(BTN_PLUS)) phase = Phase.PAUSE
                updateCat(input, d)
                updateGrandma(d)
                updateSpray(d)
                world.step(d)
                checkHits()
                if (gHp <= 0f && gState != GState.DEFEATED) { gState = GState.DEFEATED; gStateTime = 0f; say("Chega... vou tirar um cochilo!", 6.0); phase = Phase.WON; endReason = "A vovó desistiu e foi dormir. A casa é sua!" }
                if (choreIndex >= chores.size) { gState = GState.FINISHED; phase = Phase.LOST; endReason = "A vovó terminou todas as tarefas do dia." }
                if (catHp <= 0f) { phase = Phase.LOST; endReason = "A vovó pôs o gato para fora de casa." }
                if (catHp < 100f && time - lastHurt > 5.0) catHp = min(100f, catHp + d * 2f)
            }
        }
        render()
    }

    // ------------------------------------------------------------------ input
    private class Input(val moveX: Float, val moveY: Float, val turn: Float, val buttons: Int, val prev: Int) {
        fun down(b: Int) = buttons and b != 0
        fun pressed(b: Int) = buttons and b != 0 && prev and b == 0
        fun released(b: Int) = buttons and b == 0 && prev and b != 0
    }

    private fun readInput(): Input {
        val l = ctx.input.controllers[0]; val r = ctx.input.controllers[1]
        var mx = 0f; var my = 0f; var turn = 0f
        // a Pro Controller / paired Joy-Cons report both sticks on one device (stick2 = left)
        if (r.stick2X != 0f || r.stick2Y != 0f) twoSticks = true
        when {
            l.connected && r.connected -> { mx = l.stickX; my = l.stickY; turn = r.stickX }
            r.connected && twoSticks -> { mx = r.stick2X; my = r.stick2Y; turn = r.stickX }
            r.connected -> { mx = r.stickX; my = r.stickY }
            l.connected -> { mx = l.stickX; my = l.stickY }
        }
        var raw = (if (l.connected) l.buttons else 0) or (if (r.connected) r.buttons else 0)
        val leftOnly = l.connected && !r.connected
        // abstract buttons: A jump, B dodge, grab (ZR/R/SR; ZL alone on a lone left Joy-Con), swat, meow, pause
        var b = 0
        if (raw and (128) != 0) b = b or BTN_A
        if (raw and (256) != 0) b = b or BTN_B
        if (raw and (4 or 1 or 4096) != 0 || (leftOnly && raw and 8 != 0)) b = b or BTN_GRAB
        if ((!leftOnly && raw and (8 or 2 or 2048) != 0) || (leftOnly && raw and 2 != 0)) b = b or BTN_SWAT
        if (raw and (512 or 1024) != 0) b = b or BTN_X
        if (raw and (16 or 32) != 0) b = b or BTN_PLUS
        if (ctx.input.tapDown) b = b or BTN_GRAB
        raw = b
        val i = Input(mx, my, turn, raw, prevButtons)
        prevButtons = raw
        return i
    }

    // ------------------------------------------------------------------ cat
    private fun headForwardWorld(out: Vec3): Vec3 {
        ctx.input.head.orientation.rotate(Pose.FORWARD, out)
        Quat.fromAxisAngle(0f, 1f, 0f, catYaw, tmpQ).rotate(out, out)
        return out
    }

    private fun updateCat(input: Input, dt: Float) {
        catYaw -= input.turn * 2.2f * dt
        // walk relative to where the head looks (flattened)
        val fwd = headForwardWorld(tmp).also { it.y = 0f; it.normalize() }
        val right = tmp2.set(-fwd.z, 0f, fwd.x)
        val speed = 2.2f
        val target = tmp3.set(0f, 0f, 0f).addScaled(fwd, input.moveY * speed).addScaled(right, input.moveX * speed)
        val k = (dt * (if (onGround) 12f else 3f)).coerceIn(0f, 1f)
        catVel.x += (target.x - catVel.x) * k; catVel.z += (target.z - catVel.z) * k
        if (input.pressed(BTN_B) && dashCooldown <= 0f) {
            dashDir.set(if (input.moveX == 0f && input.moveY == 0f) -fwd.x else catVel.x, 0f, if (input.moveX == 0f && input.moveY == 0f) -fwd.z else catVel.z).normalize()
            dashTime = 0.22f; dashCooldown = 0.9f
            play(sndSwoosh, 0.7f, 1.3f, null)
        }
        dashCooldown -= dt
        if (dashTime > 0f) { dashTime -= dt; catVel.x = dashDir.x * 6f; catVel.z = dashDir.z * 6f }
        if (input.pressed(BTN_A) && onGround) { catVy = 4.3f; onGround = false; play(sndSwoosh, 0.4f, 1.6f, null) }
        catVy -= 9.81f * dt
        val nx = cat.x + catVel.x * dt
        val nz = cat.z + catVel.z * dt
        var ny = cat.y + catVy * dt
        // horizontal collisions with furniture taller than the cat's feet
        val p = tmp.set(nx, 0f, nz)
        for (b in blocks) {
            if (ny >= b.top - 0.04f || ny + 0.3f <= b.bottom) continue
            val cx = p.x.coerceIn(b.minX, b.maxX); val cz = p.z.coerceIn(b.minZ, b.maxZ)
            val dx = p.x - cx; val dz = p.z - cz
            val d2 = dx * dx + dz * dz
            if (d2 < CAT_RADIUS * CAT_RADIUS) {
                if (d2 > 1e-8f) { val dd = sqrt(d2); p.x = cx + dx / dd * CAT_RADIUS; p.z = cz + dz / dd * CAT_RADIUS }
                else { // inside: push out along the shortest axis
                    val ex = min(p.x - b.minX, b.maxX - p.x); val ez = min(p.z - b.minZ, b.maxZ - p.z)
                    if (ex < ez) p.x = if (p.x - b.minX < b.maxX - p.x) b.minX - CAT_RADIUS else b.maxX + CAT_RADIUS
                    else p.z = if (p.z - b.minZ < b.maxZ - p.z) b.minZ - CAT_RADIUS else b.maxZ + CAT_RADIUS
                }
            }
        }
        // ground: the highest walkable top under the paws that we are above
        var ground = 0f
        for (b in blocks) if (b.walkable && p.x > b.minX - 0.05f && p.x < b.maxX + 0.05f && p.z > b.minZ - 0.05f && p.z < b.maxZ + 0.05f && cat.y >= b.top - 0.06f) ground = max(ground, b.top)
        if (ny <= ground) { if (!onGround && catVy < -3f) play(sndThud, 0.3f, 1.8f, null); ny = ground; catVy = 0f; onGround = true } else if (ny > ground + 0.02f) onGround = false
        cat.set(p.x.coerceIn(-3.9f, 3.9f), ny, p.z.coerceIn(-3.4f, 3.4f))
        val moving = sqrt(catVel.x * catVel.x + catVel.z * catVel.z)
        if (onGround) walkPhase += moving * dt * 9f
        // the cat's body pushes things it walks into
        world.moveKinematic(catBody, tmp.set(cat.x, cat.y + CAT_RADIUS, cat.z), Quat(), dt)

        // mouth: pick up / throw
        val mouth = mouthPoint(tmp2)
        if (input.pressed(BTN_GRAB) && held == null) {
            val p2 = props.filter { it.body.mass < 2.5f && it.body.position.distanceTo(mouth) < 0.32f + it.body.shape.boundingRadius() }.minByOrNull { it.body.position.distanceTo(mouth) }
            if (p2 != null) {
                held = p2; p2.body.ignoreGroup = CAT_GROUP
                hold = world.hold(p2.body).also { it.maxForce = 80f }
                play(sndClink, 0.3f, 1.4f, p2.body.position)
            }
        }
        held?.let { h ->
            hold?.targetPosition?.set(mouth)
            hold?.targetOrientation?.set(Quat.fromAxisAngle(0f, 1f, 0f, catYaw))
            hold?.holderVelocity?.set(catVel.x, catVy, catVel.z)
            if (input.released(BTN_GRAB)) {
                val dir = headForwardWorld(tmp3)
                releaseHeld()
                h.body.linearVelocity.set(dir).scale(7.5f).add(Vec3(catVel.x, 1.6f, catVel.z))
                h.body.angularVelocity.set(rnd.nextFloat() * 8 - 4, rnd.nextFloat() * 8 - 4, rnd.nextFloat() * 8 - 4)
                h.thrownAt = time
                play(sndSwoosh, 0.8f, 1f, null)
            }
        }
        // paw swat: shoves what is in front of the face
        if (input.pressed(BTN_SWAT)) {
            swatTime = 0.25f
            play(sndSwoosh, 0.5f, 1.8f, null)
            val dir = headForwardWorld(tmp3).also { it.y = 0f; it.normalize() }
            for (pr in props) {
                if (pr === held) continue
                if (pr.body.position.distanceTo(mouth) > 0.38f + pr.body.shape.boundingRadius()) continue
                val impulse = 3.2f / max(0.4f, pr.body.mass)
                pr.body.linearVelocity.addScaled(dir, impulse.coerceAtMost(5f)).add(Vec3(0f, 0.8f, 0f))
                pr.body.wake(); pr.thrownAt = time
            }
            // a swat to grandma's legs annoys her
            if (gState != GState.DEFEATED && horizontalDist(mouth, gPos) < 0.45f && cat.y < 0.8f) hurtGrandma(3f, "Ai! Gato!")
        }
        if (swatTime > 0f) swatTime -= dt
        if (input.pressed(BTN_X)) { play(sndMeow, 1f, 0.9f + rnd.nextFloat() * 0.2f, null); lastSeenCat = time; if (gState == GState.WALK || gState == GState.CHORE) { gState = GState.CHASE; gStateTime = 0f; say("Ah, é você, seu peste!", 2.5) } }
    }

    private fun releaseHeld() {
        hold?.let { world.release(it) }
        held?.body?.ignoreGroup = 0
        held = null; hold = null
    }

    private fun mouthPoint(out: Vec3): Vec3 {
        val fwd = headForwardWorld(tmp3)
        return out.set(cat.x, cat.y + EYE - 0.07f, cat.z).addScaled(fwd, 0.2f)
    }

    // ------------------------------------------------------------------ grandma
    private fun say(text: String, seconds: Double) { sayText = text; sayUntil = time + seconds; hud.dirty = true }

    private fun hurtGrandma(dmg: Float, line: String) {
        if (gState == GState.DEFEATED) return
        gHp = max(0f, gHp - dmg)
        // a hit while working costs her part of the chore
        if (gState == GState.CHORE) choreProgress = max(0f, choreProgress - chores[choreIndex].seconds * 0.35f)
        gState = GState.STUNNED; gStateTime = 0f
        say(line, 1.6)
        play(sndOuch, 0.9f, 0.9f + rnd.nextFloat() * 0.2f, Vec3(gPos.x, 1.5f, gPos.z))
        lastSeenCat = time
        hud.dirty = true
    }

    private fun canSeeCat(): Boolean {
        val dx = cat.x - gPos.x; val dz = cat.z - gPos.z
        val d = sqrt(dx * dx + dz * dz)
        if (d < 1.1f) return true
        if (d > 3.2f) return false
        // within ~60 degrees of where she faces
        val cosA = (dx * -sin(gYaw) + dz * -cos(gYaw)) / d
        return cosA > 0.5f
    }

    private fun updateGrandma(dt: Float) {
        gStateTime += dt
        if (gState == GState.DEFEATED || gState == GState.FINISHED) return
        if (canSeeCat() && gState in setOf(GState.WALK, GState.CHORE) && time - lastSeenCat > 8.0) {
            lastSeenCat = time
            gState = GState.CHASE; gStateTime = 0f
            say(listOf("Sai daí, gato!", "Já pra fora!", "De novo você?!").random(rnd), 2.0)
        }
        if (canSeeCat()) lastSeenCat = time
        when (gState) {
            GState.WALK -> {
                val chore = chores.getOrNull(choreIndex) ?: return
                val target = chore.stops[stopIndex]
                if (walkTo(target, 0.85f, dt)) { gState = GState.CHORE; gStateTime = 0f }
            }
            GState.CHORE -> {
                val chore = chores[choreIndex]
                gSpeed = 0f
                if (chore.anim == 1 || chore.anim == 2) faceTowards(chore.stops[stopIndex].x, -3.3f, dt)
                if (chore.anim == 4) faceTowards(3.9f, chore.stops[stopIndex].z, dt)
                // the soup cannot cook if the pot was knocked off the hob
                if (chore.anim == 1 && soupPot.body.position.distanceTo(soupPot.home) > 0.25f) {
                    if (gStateTime > 1.5f) {
                        say("Minha sopa! Vou ter de recomeçar.", 2.5)
                        choreProgress = 0f
                        soupPot.body.teleport(soupPot.home, Quat()); soupPot.body.linearVelocity.set(0f, 0f, 0f); soupPot.body.angularVelocity.set(0f, 0f, 0f)
                        gStateTime = 0f
                    }
                    return
                }
                choreProgress += dt
                if (choreProgress >= chore.seconds) {
                    choreProgress = 0f
                    stopIndex++
                    if (stopIndex >= chore.stops.size) { stopIndex = 0; choreIndex++; if (choreIndex < chores.size) say("Pronto! Agora: ${chores[choreIndex].name.lowercase()}.", 2.5) }
                    gState = GState.WALK; gStateTime = 0f
                    hud.dirty = true
                }
            }
            GState.CHASE -> {
                val dist = horizontalDist(cat, gPos)
                if (time - lastSeenCat > 5.0 || dist > 5f) { gState = GState.WALK; gStateTime = 0f; say("Hmpf. Onde eu estava?", 2.0); return }
                if (dist < 0.85f && cat.y < 1.35f) { gState = GState.WINDUP; gStateTime = 0f; say("Toma!", 1.0); return }
                // out of reach (on high furniture) or keeping away: the spray bottle
                if (gStateTime > 3.5f && dist in 1.2f..3.5f) { gState = GState.SPRAY; gStateTime = 0f; return }
                walkTo(cat, 1.25f, dt)
            }
            GState.WINDUP -> { gSpeed = 0f; faceTowards(cat.x, cat.z, dt); if (gStateTime > 0.7f) { gState = GState.STRIKE; gStateTime = 0f; strike() } }
            GState.STRIKE -> if (gStateTime > 0.6f) { gState = GState.CHASE; gStateTime = 0f }
            GState.SPRAY -> {
                gSpeed = 0f
                faceTowards(cat.x, cat.z, dt)
                if (gStateTime > 0.5f && ((gStateTime * 10).toInt() % 4 == 0) && spray.size < 40) shootSpray()
                if (gStateTime > 1.6f) { gState = GState.CHASE; gStateTime = 0f }
            }
            GState.STUNNED -> { gSpeed = 0f; if (gStateTime > 0.9f) { gState = GState.CHASE; gStateTime = 0f } }
            else -> Unit
        }
        // her body colliders (props bounce off her)
        world.moveKinematic(gBodyCol[0]!!, tmp.set(gPos.x, 0.85f, gPos.z), Quat(), dt)
        world.moveKinematic(gBodyCol[1]!!, tmp.set(gPos.x, 1.52f, gPos.z), Quat(), dt)
    }

    private fun strike() {
        play(sndSwoosh, 1f, 0.8f, Vec3(gPos.x, 1f, gPos.z))
        val dx = cat.x - gPos.x; val dz = cat.z - gPos.z
        val d = sqrt(dx * dx + dz * dz)
        if (d < 1.0f && cat.y < 1.35f && dashTime <= 0f) {
            catHp -= 20f; lastHurt = time
            catVel.set(dx / d * 4f, 0f, dz / d * 4f); catVy = 2.5f; onGround = false
            play(sndHurt, 1f, 1f, null)
            hud.flash = 0.5f
        }
    }

    private fun shootSpray() {
        val from = Vec3(gPos.x - sin(gYaw) * 0.35f, 1.25f, gPos.z - cos(gYaw) * 0.35f)
        val to = Vec3(cat.x, cat.y + EYE, cat.z)
        val dir = Vec3.sub(to, from).also { it.y += 0.25f * from.distanceTo(to) / 3f }.normalize()
        dir.x += (rnd.nextFloat() - 0.5f) * 0.12f; dir.y += (rnd.nextFloat() - 0.5f) * 0.08f
        spray += from to dir.scale(6.5f)
        play(sndSpray, 0.6f, 1.2f, from)
    }

    private fun updateSpray(dt: Float) {
        val it = spray.iterator()
        val head = Vec3(cat.x, cat.y + EYE - 0.05f, cat.z)
        while (it.hasNext()) {
            val (p, v) = it.next()
            v.y -= 9.81f * dt
            p.addScaled(v, dt)
            if (p.y < 0f || abs(p.x) > 4f || abs(p.z) > 3.5f) { it.remove(); continue }
            if (p.distanceTo(head) < 0.18f) {
                it.remove()
                if (time - lastHurt > 0.4) { catHp -= 7f; lastHurt = time; hud.flash = 0.35f; play(sndHurt, 0.6f, 1.3f, null) }
            }
        }
    }

    /** Steers towards [target] (stops at [arrive] m); returns true when there. Slides around furniture. */
    private fun walkTo(target: Vec3, arrive: Float, dt: Float): Boolean {
        val dx = target.x - gPos.x; val dz = target.z - gPos.z
        val d = sqrt(dx * dx + dz * dz)
        if (d < arrive) { gSpeed = 0f; return true }
        var dirX = dx / d; var dirZ = dz / d
        if (sidestep != 0f) { val sx = -dirZ * sidestep; val sz = dirX * sidestep; dirX = dirX * 0.3f + sx; dirZ = dirZ * 0.3f + sz; sidestep = if (gStuckTime > 0.8f) 0f else sidestep }
        faceTowards(gPos.x + dirX, gPos.z + dirZ, dt)
        val speed = if (gState == GState.CHASE) 1.25f else 0.8f
        gSpeed = speed
        val before = Vec3(gPos.x, 0f, gPos.z)
        val nx = gPos.x - sin(gYaw) * speed * dt
        val nz = gPos.z - cos(gYaw) * speed * dt
        gPos.set(nx, 0f, nz)
        // furniture blocks her (she does not climb): push out of anything taller than 0.3 m
        for (b in blocks) {
            if (b.top < 0.3f) continue
            val r = 0.3f
            val cx = gPos.x.coerceIn(b.minX, b.maxX); val cz = gPos.z.coerceIn(b.minZ, b.maxZ)
            val ex = gPos.x - cx; val ez = gPos.z - cz
            val d2 = ex * ex + ez * ez
            if (d2 < r * r && d2 > 1e-8f) { val dd = sqrt(d2); gPos.x = cx + ex / dd * r; gPos.z = cz + ez / dd * r }
        }
        gWalkPhase += speed * dt * 5.5f
        val moved = before.distanceTo(Vec3(gPos.x, 0f, gPos.z))
        if (moved < speed * dt * 0.3f) { gStuckTime += dt; if (gStuckTime > 0.6f && sidestep == 0f) { sidestep = if (rnd.nextBoolean()) 1f else -1f; gStuckTime = 0f } }
        else { gStuckTime = max(0f, gStuckTime - dt); if (gStuckTime == 0f) sidestep = 0f }
        return false
    }

    private fun faceTowards(x: Float, z: Float, dt: Float) {
        val want = atan2(-(x - gPos.x), -(z - gPos.z))
        var diff = want - gYaw
        while (diff > Math.PI) diff -= (2 * Math.PI).toFloat()
        while (diff < -Math.PI) diff += (2 * Math.PI).toFloat()
        gYaw += diff * (dt * 6f).coerceIn(0f, 1f)
    }

    private fun horizontalDist(a: Vec3, b: Vec3): Float { val dx = a.x - b.x; val dz = a.z - b.z; return sqrt(dx * dx + dz * dz) }

    /** Thrown or knocked objects that reach grandma's body hurt her (and anything heavy hurts more). */
    private fun checkHits() {
        if (gState == GState.DEFEATED) return
        val seg0 = tmp.set(gPos.x, 0.35f, gPos.z)
        for (p in props) {
            if (p === held) continue
            val b = p.body
            val v = b.linearVelocity.length()
            if (v < 1.8f || time - p.lastHitGrandma < 0.6) continue
            // distance to her body (vertical segment 0.35..1.6 m) with a 0.28 m radius
            val y = b.position.y.coerceIn(0.35f, 1.6f)
            val dx = b.position.x - seg0.x; val dz = b.position.z - seg0.z; val dy = b.position.y - y
            if (sqrt(dx * dx + dy * dy + dz * dz) < 0.3f + b.shape.boundingRadius()) {
                p.lastHitGrandma = time
                val dmg = (b.mass * v * 10f).coerceIn(4f, 30f)
                hurtGrandma(dmg, listOf("Ai!", "Ui, minha cabeça!", "Gato malcriado!", "Isso era da minha mãe!").random(rnd))
                play(if (p.kind == Kind.PLATE || p.kind == Kind.MUG || p.kind == Kind.VASE || p.kind == Kind.JAR) sndClink else sndThud, 0.9f, 1f, b.position)
            }
        }
        // impact sounds for everything else
        for (p in props) if (p.body.lastImpact > 1.2f) {
            val loud = (p.body.lastImpact / 5f).coerceIn(0.1f, 1f)
            play(if (p.kind in setOf(Kind.PLATE, Kind.MUG, Kind.VASE, Kind.JAR, Kind.POT)) sndClink else sndThud, loud, 0.8f + rnd.nextFloat() * 0.4f, p.body.position)
            p.body.lastImpact = 0f
        }
    }

    private fun play(id: Int, vol: Float, pitch: Float, worldPos: Vec3?) {
        if (id == 0) return
        val tp = worldPos?.let { trackingPoint(it, Vec3()) }
        ctx.audio.play(id, vol, pitch, false, tp, if (tp != null) ctx.input.head else null)
    }

    // ================================================================== rendering
    /** World -> tracking: the cat's head is where the user's head is, turned by the cat's yaw. */
    private fun computeRig() {
        hw.set(cat.x, cat.y + EYE, cat.z)
        Quat.fromAxisAngle(0f, 1f, 0f, -catYaw, qInv)
        val t = qInv.rotate(hw, tmp).scale(-1f).add(ctx.input.head.position)
        Mat4.compose(t, qInv, 1f, 1f, 1f, toTracking)
    }

    private fun trackingPoint(p: Vec3, out: Vec3): Vec3 = qInv.rotate(Vec3.sub(p, hw, out), out).add(ctx.input.head.position)

    private fun draw(mesh: GpuMesh, pos: Vec3, rot: Quat, sx: Float, sy: Float, sz: Float, tex: Int, color: FloatArray, gloss: Float = 0.1f, metal: Float = 0f, unlit: Boolean = false, shadow: Boolean = true) {
        val d = ctx.scene.addDraw()
        d.mesh = mesh; d.texture = tex; color.copyInto(d.color); d.gloss = gloss; d.metal = metal; d.unlit = unlit; d.castShadow = shadow
        d.transparent = color[3] < 1f
        Mat4.compose(pos, rot, sx, sy, sz, world16)
        Mat4.multiply(toTracking, world16, d.matrix)
    }

    private val identityQ = Quat()

    private fun render() {
        computeRig()
        val sc = ctx.scene
        val r = ctx.renderer
        sc.background = Background.COLOR
        sc.clear[0] = 0.05f; sc.clear[1] = 0.05f; sc.clear[2] = 0.06f
        // daylight from the south windows, warm interior bounce
        val light = qInv.rotate(tmp.set(-0.3f, -1f, -0.45f).normalize(), tmp)
        sc.lightDir[0] = light.x; sc.lightDir[1] = light.y; sc.lightDir[2] = light.z
        sc.lightColor[0] = 1f; sc.lightColor[1] = 0.95f; sc.lightColor[2] = 0.86f
        sc.ambient[0] = 0.42f; sc.ambient[1] = 0.39f; sc.ambient[2] = 0.36f
        trackingPoint(tmp2.set(cat.x, 0f, cat.z), sc.shadowCenter)
        sc.shadowRadius = 3.2f
        for (v in visuals) {
            val mesh = when (v.mesh) { 2 -> r.sphere; 4 -> r.cylinder; else -> r.cube }
            draw(mesh, v.center, identityQ, v.half.x * 2, v.half.y * 2, v.half.z * 2, v.tex, v.color, v.gloss, v.metal, v.unlit, v.shadow)
        }
        for (p in props) {
            val b = p.body
            when (b.shape.type) {
                ShapeType.BOX -> { val h = b.shape.halfExtents; draw(r.cube, b.position, b.orientation, h.x * 2, h.y * 2, h.z * 2, p.tex, p.color, p.gloss, p.metal) }
                ShapeType.SPHERE -> { val s = b.shape.radius * 2; draw(r.sphere, b.position, b.orientation, s, s, s, p.tex, p.color, p.gloss, p.metal) }
                ShapeType.CAPSULE -> { val rr = b.shape.radius; val hh = b.shape.halfHeight + rr; draw(r.cylinder, b.position, b.orientation, rr * 2, hh * 2, rr * 2, p.tex, p.color, p.gloss, p.metal) }
            }
        }
        // soup steam while she cooks
        if (gState == GState.CHORE && chores.getOrNull(choreIndex)?.anim == 1) for (k in 0 until 4) {
            val t = ((time * 0.6 + k * 0.25) % 1.0).toFloat()
            draw(r.sphere, tmp.set(soupPot.body.position.x + sin(k * 2f + t * 3f) * 0.03f, soupPot.body.position.y + 0.15f + t * 0.35f, soupPot.body.position.z), identityQ,
                0.06f + t * 0.08f, 0.06f + t * 0.08f, 0.06f + t * 0.08f, 0, floatArrayOf(1f, 1f, 1f, 0.35f * (1f - t)), shadow = false)
        }
        drawGrandma()
        for ((p, _) in spray) draw(r.sphere, p, identityQ, 0.025f, 0.025f, 0.025f, 0, floatArrayOf(0.7f, 0.85f, 1f, 0.8f), gloss = 1f, shadow = false)
        drawCatBody()
        hud.update(this)
        hud.place(ctx.input.head)
        hud.submit(sc)
    }

    /** What the cat sees of itself: front paws (one swats), whiskers' worth of fur at the bottom. */
    private fun drawCatBody() {
        val r = ctx.renderer
        val fwd = headForwardWorld(tmp3).also { it.y = 0f; it.normalize() }
        val right = Vec3(-fwd.z, 0f, fwd.x)
        val fur = floatArrayOf(0.85f, 0.55f, 0.25f, 1f)
        for (side in intArrayOf(-1, 1)) {
            val step = sin(walkPhase + if (side > 0) Math.PI.toFloat() else 0f) * 0.04f
            val swat = if (side > 0 && swatTime > 0f) sin((0.25f - swatTime) / 0.25f * Math.PI.toFloat()) else 0f
            val p = Vec3(cat.x, cat.y + 0.03f + swat * 0.14f, cat.z).addScaled(fwd, 0.2f + step + swat * 0.12f).addScaled(right, side * 0.06f)
            draw(r.sphere, p, identityQ, 0.06f, 0.05f, 0.08f, 0, fur, 0.05f)
            draw(r.sphere, Vec3(p.x, p.y - 0.01f, p.z).addScaled(fwd, 0.03f), identityQ, 0.045f, 0.03f, 0.045f, 0, floatArrayOf(0.95f, 0.93f, 0.9f, 1f), 0.05f)
        }
        // chest
        draw(r.sphere, Vec3(cat.x, cat.y + 0.12f, cat.z).addScaled(fwd, 0.08f), identityQ, 0.16f, 0.16f, 0.16f, 0, fur, 0.05f)
    }

    private fun drawGrandma() {
        val r = ctx.renderer
        val q = Quat.fromAxisAngle(0f, 1f, 0f, gYaw)
        val defeated = gState == GState.DEFEATED
        val base = Vec3(gPos.x, 0f, gPos.z)
        fun local(x: Float, y: Float, z: Float) = q.rotate(Vec3(x, y, z)).add(base)
        val skin = floatArrayOf(0.93f, 0.78f, 0.68f, 1f)
        val wob = if (gState == GState.STUNNED) sin(gStateTime * 25f) * 0.08f else 0f
        val bodyQ = if (defeated) Quat.fromAxisAngle(1f, 0f, 0f, -1.2f).let { q.mul(it) } else q.mul(Quat.fromAxisAngle(0f, 0f, 1f, wob))
        if (defeated) {
            // sitting on the floor, leaning back: dress and head lower
            draw(r.cylinder, local(0f, 0.25f, 0f), bodyQ, 0.5f, 0.6f, 0.5f, texDress, WHITE, 0.05f)
            draw(r.sphere, local(0f, 0.75f, 0.25f), q, 0.22f, 0.24f, 0.22f, 0, skin, 0.2f)
            draw(r.sphere, local(0f, 0.85f, 0.3f), q, 0.24f, 0.18f, 0.24f, 0, floatArrayOf(0.82f, 0.82f, 0.85f, 1f), 0.1f)
            for (k in 0 until 3) { val a = time.toFloat() * 3f + k * 2.09f; draw(r.sphere, local(cos(a) * 0.2f, 1.0f, 0.25f + sin(a) * 0.2f), q, 0.05f, 0.05f, 0.05f, 0, floatArrayOf(1f, 0.9f, 0.3f, 1f), unlit = true) }
            return
        }
        val swing = sin(gWalkPhase) * (gSpeed / 1.25f).coerceIn(0f, 1f) * 0.45f
        // shoes and stockings (legs swing under the dress)
        for (side in intArrayOf(-1, 1)) {
            val a = swing * side
            val ankle = local(side * 0.1f, 0.07f, sin(a) * 0.18f)
            draw(r.cylinder, local(side * 0.1f, 0.24f, sin(a) * 0.09f), q.mul(Quat.fromAxisAngle(1f, 0f, 0f, a)), 0.09f, 0.36f, 0.09f, 0, floatArrayOf(0.86f, 0.74f, 0.66f, 1f), 0.3f)
            draw(r.cube, ankle.add(q.rotate(Vec3(0f, -0.035f, -0.04f))), q, 0.11f, 0.07f, 0.24f, 0, floatArrayOf(0.25f, 0.16f, 0.12f, 1f), 0.5f)
        }
        // dress, apron, cardigan, shoulders
        draw(r.cylinder, local(0f, 0.75f, 0f), bodyQ, 0.52f, 0.78f, 0.46f, texDress, WHITE, 0.05f)
        draw(r.cube, local(0f, 0.78f, -0.235f), bodyQ, 0.36f, 0.6f, 0.01f, 0, floatArrayOf(0.97f, 0.96f, 0.93f, 1f), 0.05f)
        draw(r.cylinder, local(0f, 1.27f, 0f), bodyQ, 0.46f, 0.36f, 0.36f, texKnit, WHITE, 0.02f)
        draw(r.sphere, local(0f, 1.44f, 0f), bodyQ, 0.46f, 0.16f, 0.34f, texKnit, WHITE, 0.02f)
        // head: face, nose, hair with a bun, glasses
        val headY = 1.6f
        draw(r.cylinder, local(0f, 1.5f, 0f), q, 0.1f, 0.08f, 0.1f, 0, skin, 0.2f)
        draw(r.sphere, local(0f, headY, 0f), q.mul(Quat.fromAxisAngle(0f, 0f, 1f, wob * 2)), 0.23f, 0.27f, 0.24f, 0, skin, 0.25f)
        draw(r.sphere, local(0f, headY - 0.01f, -0.125f), q, 0.04f, 0.05f, 0.04f, 0, floatArrayOf(0.9f, 0.7f, 0.62f, 1f), 0.25f)
        draw(r.sphere, local(0f, headY + 0.07f, 0.02f), q, 0.25f, 0.18f, 0.25f, 0, floatArrayOf(0.84f, 0.84f, 0.87f, 1f), 0.15f)
        draw(r.sphere, local(0f, headY + 0.1f, 0.13f), q, 0.12f, 0.12f, 0.12f, 0, floatArrayOf(0.8f, 0.8f, 0.83f, 1f), 0.15f)
        for (side in intArrayOf(-1, 1)) {
            draw(r.sphere, local(side * 0.05f, headY + 0.02f, -0.11f), q, 0.018f, 0.018f, 0.01f, 0, floatArrayOf(0.15f, 0.15f, 0.2f, 1f), 0.9f)
            draw(r.cylinder, local(side * 0.05f, headY + 0.02f, -0.125f), q.mul(Quat.fromAxisAngle(1f, 0f, 0f, 1.5708f)), 0.055f, 0.006f, 0.055f, 0, floatArrayOf(0.75f, 0.85f, 0.95f, 0.35f), 1f, 0.5f, shadow = false)
        }
        draw(r.cube, local(0f, headY + 0.02f, -0.128f), q, 0.04f, 0.006f, 0.006f, 0, floatArrayOf(0.5f, 0.45f, 0.3f, 1f), 0.9f, 1f)
        // mouth shows the mood
        val angry = gState == GState.CHASE || gState == GState.WINDUP || gState == GState.STRIKE || gState == GState.SPRAY
        draw(r.cube, local(0f, headY - 0.07f, -0.112f), q.mul(Quat.fromAxisAngle(0f, 0f, 1f, if (angry) 0.15f else 0f)), 0.05f, 0.008f, 0.006f, 0, floatArrayOf(0.55f, 0.25f, 0.25f, 1f))
        // arms: pose by state
        val chore = chores.getOrNull(choreIndex)
        for (side in intArrayOf(-1, 1)) {
            var pitch = -swing * side * 0.6f    // forward raise (rad), negative = forward
            var spread = 0.12f
            var elbow = 0.25f
            val t = time.toFloat()
            when (gState) {
                GState.CHORE -> when (chore?.anim) {
                    0 -> { if (side > 0) { pitch = -1.1f; elbow = 0.3f } }
                    1 -> { if (side > 0) { pitch = -0.9f + sin(t * 4f) * 0.15f; spread = 0.12f + cos(t * 4f) * 0.12f; elbow = 0.6f } }
                    2 -> { pitch = -0.8f + sin(t * 6f + side) * 0.12f; elbow = 0.7f }
                    3 -> { pitch = -0.6f + sin(t * 3f) * 0.35f; spread = -0.05f; elbow = 0.3f }
                    4 -> { if (side > 0) { pitch = -2.2f + sin(t * 5f) * 0.3f; elbow = 0.2f } }
                }
                GState.WINDUP -> if (side > 0) { pitch = -2.7f; elbow = 0.4f }
                GState.STRIKE -> if (side > 0) { val k = (gStateTime / 0.25f).coerceAtMost(1f); pitch = -2.7f + k * 2.0f; elbow = 0.2f }
                GState.SPRAY -> if (side > 0) { pitch = -1.45f; elbow = 0.1f }
                GState.STUNNED -> { pitch = -0.5f + sin(gStateTime * 20f) * 0.4f; spread = 0.4f }
                GState.CHASE -> pitch = -swing * side * 0.9f
                else -> Unit
            }
            val shoulder = local(side * 0.25f, 1.4f, 0f)
            val armQ = q.mul(Quat.fromAxisAngle(0f, 0f, 1f, side * spread)).mul(Quat.fromAxisAngle(1f, 0f, 0f, pitch))
            val upperDir = armQ.rotate(Vec3(0f, -1f, 0f))
            val elbowP = Vec3(shoulder.x, shoulder.y, shoulder.z).addScaled(upperDir, 0.28f)
            val foreQ = armQ.mul(Quat.fromAxisAngle(1f, 0f, 0f, -elbow))
            val foreDir = foreQ.rotate(Vec3(0f, -1f, 0f))
            val hand = Vec3(elbowP.x, elbowP.y, elbowP.z).addScaled(foreDir, 0.26f)
            draw(r.cylinder, Vec3(shoulder.x, shoulder.y, shoulder.z).addScaled(upperDir, 0.14f), armQ, 0.11f, 0.3f, 0.11f, texKnit, WHITE, 0.02f)
            draw(r.cylinder, Vec3(elbowP.x, elbowP.y, elbowP.z).addScaled(foreDir, 0.13f), foreQ, 0.08f, 0.27f, 0.08f, 0, skin, 0.2f)
            draw(r.sphere, hand, foreQ, 0.08f, 0.09f, 0.07f, 0, skin, 0.2f)
            if (side > 0) when {
                gState == GState.WINDUP || gState == GState.STRIKE ->   // the slipper
                    draw(r.cube, Vec3(hand.x, hand.y, hand.z).addScaled(foreDir, 0.1f), foreQ, 0.1f, 0.26f, 0.03f, 0, floatArrayOf(0.9f, 0.45f, 0.6f, 1f), 0.2f)
                gState == GState.SPRAY -> {
                    draw(r.cylinder, Vec3(hand.x, hand.y, hand.z), foreQ, 0.07f, 0.18f, 0.07f, 0, floatArrayOf(0.4f, 0.75f, 0.95f, 0.85f), 0.9f)
                }
                gState == GState.CHORE && chore?.anim == 0 -> draw(r.cylinder, Vec3(hand.x, hand.y - 0.08f, hand.z), q, 0.14f, 0.16f, 0.14f, 0, floatArrayOf(0.3f, 0.55f, 0.35f, 1f), 0.5f, 0.3f)
                gState == GState.CHORE && chore?.anim == 3 -> draw(r.cylinder, Vec3(hand.x, hand.y - 0.45f, hand.z), foreQ, 0.03f, 1.1f, 0.03f, texWood, WHITE, 0.2f)
                gState == GState.CHORE && chore?.anim == 4 -> draw(r.cube, Vec3(hand.x, hand.y, hand.z).addScaled(foreDir, 0.06f), foreQ, 0.12f, 0.04f, 0.12f, 0, floatArrayOf(0.95f, 0.85f, 0.4f, 1f), 0f)
            }
        }
        if (gState == GState.WINDUP) {
            // "!" telegraph over her head
            draw(r.cube, local(0f, 2.0f, 0f), q, 0.04f, 0.16f, 0.04f, 0, floatArrayOf(1f, 0.25f, 0.2f, 1f), unlit = true, shadow = false)
            draw(r.sphere, local(0f, 1.86f, 0f), q, 0.05f, 0.05f, 0.05f, 0, floatArrayOf(1f, 0.25f, 0.2f, 1f), unlit = true, shadow = false)
        }
    }

    // ================================================================== HUD
    /** Body-locked status panel under the view: lives, grandma, chores, lines and screens. */
    private class CatHud : com.hyperxr.spatialui.XrPanel(0.9f, 0.3f, ppm = 1100f) {
        var flash = 0f
        private var key = ""
        var lines: List<String> = emptyList()
        var catHp = 100f; var gHp = 100f; var chore = ""; var chorePct = 0f; var done = 0; var total = 5
        var big: String? = null; var bigSub = ""
        init { closable = false; priority = 0 }

        fun update(g: CatGame) {
            catHp = g.catHp; gHp = g.gHp; total = g.chores.size; done = g.choreIndex.coerceAtMost(total)
            chore = g.chores.getOrNull(g.choreIndex)?.name ?: "Tudo feito"
            chorePct = g.chores.getOrNull(g.choreIndex)?.let { c -> ((g.stopIndex * c.seconds + g.choreProgress) / (c.seconds * c.stops.size)).coerceIn(0f, 1f) } ?: 1f
            val say = if (g.time < g.sayUntil) "Vovó: \"${g.sayText}\"" else ""
            big = when (g.phase) {
                Phase.INTRO -> "Gato vs. Vovó"
                Phase.PAUSE -> "Pausa"
                Phase.WON -> "Você venceu!"
                Phase.LOST -> "Fim de jogo"
                Phase.PLAY -> null
            }
            bigSub = when (g.phase) {
                Phase.INTRO -> "Atrapalhe as tarefas da vovó e acerte-a com as coisas da casa antes que ela termine.\nAnalógico: andar · A: pular · B: esquivar · ZR: pegar/arremessar · ZL: patada · X: miar\nPressione A para começar"
                Phase.PAUSE -> "A: continuar · X: recomeçar · B: sair"
                Phase.WON, Phase.LOST -> g.endReason + "\nA: jogar de novo · B: sair"
                Phase.PLAY -> ""
            }
            val k = "${catHp.toInt()}|${gHp.toInt()}|$done|${(chorePct * 50).toInt()}|$say|$big|${flash > 0f}"
            if (k != key) { key = k; lines = listOf(say); dirty = true }
            if (flash > 0f) flash -= 0.016f
        }

        fun place(head: Pose) {
            val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
            val yaw = atan2(-f.x, -f.z)
            val big = big != null
            val dist = if (big) 1.1f else 0.95f
            val y = if (big) 0.0f else -0.42f
            pose.position.set(head.position.x - sin(yaw) * dist, head.position.y + y, head.position.z - cos(yaw) * dist)
            Quat.fromAxisAngle(0f, 1f, 0f, yaw, pose.orientation)
            if (!big) pose.orientation.mul(Quat.fromAxisAngle(1f, 0f, 0f, 0.35f), pose.orientation)
            heightMeters = if (big) 0.5f else 0.3f
        }

        override fun draw(c: com.hyperxr.spatialui.PanelCanvas) {
            val b = big
            if (b != null) {
                c.label(b, c.pad, c.y, 56f, com.hyperxr.spatialui.XrTheme.text, bold = true)
                c.label(bigSub, c.pad, c.y + 80f, 28f, com.hyperxr.spatialui.XrTheme.textDim)
                return
            }
            fun bar(label: String, v: Float, y: Float, color: Int) {
                c.label(label, c.pad, y, 24f, com.hyperxr.spatialui.XrTheme.textDim)
                val x0 = c.pad + 170f; val w = c.w - x0 - c.pad
                fill.color = com.hyperxr.spatialui.XrTheme.buttonDisabled
                c.canvas.drawRoundRect(android.graphics.RectF(x0, y + 4f, x0 + w, y + 26f), 11f, 11f, fill)
                fill.color = color
                c.canvas.drawRoundRect(android.graphics.RectF(x0, y + 4f, x0 + w * v.coerceIn(0f, 1f), y + 26f), 11f, 11f, fill)
            }
            val top = 20f
            bar("Gato", catHp / 100f, top, if (flash > 0f) Color.rgb(255, 80, 80) else Color.rgb(240, 160, 60))
            bar("Vovó", gHp / 100f, top + 40f, Color.rgb(160, 120, 220))
            bar("Tarefas $done/$total", (done + chorePct) / total, top + 80f, Color.rgb(90, 180, 120))
            c.label("Agora: $chore", c.pad, top + 120f, 24f, com.hyperxr.spatialui.XrTheme.text)
            lines.firstOrNull { it.isNotEmpty() }?.let { c.label(it, c.pad, top + 156f, 26f, Color.rgb(255, 220, 140), bold = true) }
        }

        private companion object { val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    }

    private companion object {
        val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
        const val BTN_A = 1; const val BTN_B = 2; const val BTN_GRAB = 4; const val BTN_SWAT = 8; const val BTN_X = 16; const val BTN_PLUS = 32
    }
}
