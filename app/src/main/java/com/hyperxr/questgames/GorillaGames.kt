package com.hyperxr.questgames

import com.hyperxr.math.Vec3
import com.hyperxr.social.Social
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private fun rgba(r: Float, g: Float, b: Float, a: Float = 1f) = floatArrayOf(r, g, b, a)

/** A gorilla someone else controls (a bot or a player online), in world coordinates. */
internal class Ape(val id: String, var name: String, var color: FloatArray) {
    val head = FloatArray(3)
    val hands = Array(2) { FloatArray(3) }
    val vel = FloatArray(3)
    var yaw = 0f
    var lava = false
    var lastSeen = 0f
    var grounded = false
    var jumpCool = 0f
    var wander = 0f
    val target = FloatArray(3)
    // smoothing for network players
    val netHead = FloatArray(3)
    val netHands = Array(2) { FloatArray(3) }
}

/**
 * Shared by the gorilla games: the locomotion rig (GorillaRig), long arms from the shoulders, the
 * eye height of a gorilla (so the ground is within reach standing up in a VR Box), the level's
 * solid shapes, drawing gorillas (no legs) and turning world coordinates into the game's.
 */
abstract class GorillaGameBase(host: GameHost) : QuestGame(host) {
    protected val world = GorillaRig.World()
    protected val rig = GorillaRig(world)
    /** Real eye height minus the gorilla's: what is subtracted from tracked heights. */
    protected var offsetY = Float.NaN
    protected val eye = 1.0f
    private val headLocal = FloatArray(3)
    private val handLocal = Array(2) { FloatArray(3) }
    private val handInputs = arrayOfNulls<FloatArray>(2)
    private val feeds = arrayOf(HandFeed(), HandFeed())
    private val padFeed = HandFeed(holdS = 0.2f)
    private val reachV = FloatArray(3)
    protected var yaw = 0f
    private val fwd = Vec3()
    protected var tapCool = FloatArray(2)

    override val shadowRadius = 0f
    /** Your gorilla arms are drawn by the game: the system's hands would be a second pair. */
    override val hidesHands = true

    /** World point -> game space (where the player is). */
    protected fun gx(x: Float) = x - rig.body[0]
    protected fun gy(y: Float) = y - rig.body[1] + offsetY
    protected fun gz(z: Float) = z - rig.body[2]

    /** The player's head in the world. */
    protected fun headWorld(out: FloatArray): FloatArray {
        out[0] = host.head.x + rig.body[0]; out[1] = host.head.y - offsetY + rig.body[1]; out[2] = host.head.z + rig.body[2]
        return out
    }

    /** Whether a hand (0 left, 1 right) is driving its gorilla arm now (tracked, held a moment, or the controller). */
    protected fun handActive(i: Int) = handInputs[i] != null

    /**
     * The gorilla's locomotion (GorillaRig, Another Axiom's GorillaLocomotion) fed with your hands:
     * steadied (HandFeed), taken further down and out so the ground is in reach while the camera
     * still sees them, and held for a moment when the camera loses one.
     */
    protected fun stepPlayer(dt: Float) {
        if (offsetY.isNaN()) {
            if (host.head.y < 0.5f) return
            offsetY = host.head.y - eye
        }
        headLocal[0] = host.head.x; headLocal[1] = host.head.y - offsetY; headLocal[2] = host.head.z
        host.ctx.input.head.orientation.rotate(Vec3(0f, 0f, -1f), fwd)
        host.dirToGame(Vec3(fwd.x, fwd.y, fwd.z), fwd)
        yaw = atan2(-fwd.x, -fwd.z)
        for (i in 0..1) {
            val h = host.hands[i]
            // the controller stands in for the right hand when that hand isn't tracked
            val p = if (h.usable) feeds[i].update(h.palm, host.head, dt)
                else if (i == 1 && host.pad.connected) padFeed.update(host.pad.pose.position, host.head, dt).also { feeds[i].update(null, host.head, dt) }
                else feeds[i].update(null, host.head, dt)
            if (p == null) { handInputs[i] = null; continue }
            reachV[0] = p[0] - host.head.x; reachV[1] = p[1] - host.head.y; reachV[2] = p[2] - host.head.z
            HandFeed.gorillaReach(reachV)
            handLocal[i][0] = headLocal[0] + reachV[0]; handLocal[i][1] = headLocal[1] + reachV[1]; handLocal[i][2] = headLocal[2] + reachV[2]
            handInputs[i] = handLocal[i]
        }
        rig.step(dt, headLocal, handInputs)
        for (i in 0..1) {
            tapCool[i] -= dt
            if (rig.tapped[i] && tapCool[i] <= 0f) {
                tapCool[i] = 0.12f
                val hw = rig.hand[i]
                host.play(host.sTick, gx(hw[0]), gy(hw[1]), gz(hw[2]), 0.5f, 0.5f + 0.1f * i)
                if (i == 1) host.pad.buzz(15)
            }
        }
        // fell off the world: back to the start
        if (rig.body[1] < -30f) { rig.teleport(0f, 0f, 0f); onRespawn() }
    }

    protected open fun onRespawn() {}

    /** Your own arms: fur from the shoulders to the hands (planted hands stay where they touched). */
    protected fun drawOwnArms(fur: FloatArray, palm: FloatArray) {
        if (offsetY.isNaN()) return
        val cy = cos(yaw); val sy = sin(yaw)
        for (i in 0..1) {
            val side = if (i == 0) -0.19f else 0.19f
            val sx = host.head.x + cy * side; val sY = host.head.y - 0.3f; val sz = host.head.z - sy * side
            if (!handActive(i)) continue
            val h = rig.hand[i]
            arm(sx, sY, sz, gx(h[0]), gy(h[1]), gz(h[2]), fur, palm, rig.touching[i], side)
        }
    }

    private fun arm(sx: Float, sy: Float, sz: Float, hx: Float, hy: Float, hz: Float, fur: FloatArray, palm: FloatArray, planted: Boolean, side: Float) {
        // elbow: bent down and outward, more when the hand is close
        val dx = hx - sx; val dy = hy - sy; val dz = hz - sz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val seg = 0.62f
        val bend = sqrt(max(0f, seg * seg - (d / 2) * (d / 2)))
        val ex = (sx + hx) / 2 + side * bend * 0.6f; val ey = (sy + hy) / 2 - bend * 0.8f; val ez = (sz + hz) / 2 + bend * 0.2f
        host.drawBeam(sx, sy, sz, ex, ey, ez, 0.07f, fur, unlit = false, gloss = 0.05f)
        host.drawBeam(ex, ey, ez, hx, hy, hz, 0.06f, fur, unlit = false, gloss = 0.05f)
        host.drawSphere(ex, ey, ez, 0.07f, fur, gloss = 0.05f, shadow = false)
        host.drawRounded(hx, hy, hz, 0.13f, if (planted) 0.06f else 0.08f, 0.15f, palm, gloss = 0.1f, soft = true)
    }

    /** Another gorilla: round body, face, eyes, long arms to its hands. No legs. */
    internal fun drawApe(a: Ape, fur: FloatArray, glowLava: Boolean) {
        val hx = gx(a.head[0]); val hy = gy(a.head[1]); val hz = gz(a.head[2])
        val body = if (a.lava) rgba(0.95f, 0.35f, 0.08f) else fur
        host.drawEllipsoid(hx, hy - 0.42f, hz, 0.55f, 0.6f, 0.48f, body, yaw = a.yaw, gloss = 0.05f)
        host.drawEllipsoid(hx, hy, hz, 0.34f, 0.34f, 0.32f, body, yaw = a.yaw, gloss = 0.05f)
        // face toward where it looks
        val fx = -sin(a.yaw); val fz = -cos(a.yaw)
        host.drawEllipsoid(hx + fx * 0.12f, hy - 0.03f, hz + fz * 0.12f, 0.22f, 0.2f, 0.12f, FACE, yaw = a.yaw, gloss = 0.1f)
        for (s in listOf(-1f, 1f)) {
            val ex = hx + fx * 0.17f + cos(a.yaw) * 0.055f * s; val ez = hz + fz * 0.17f - sin(a.yaw) * 0.055f * s
            host.drawSphere(ex, hy + 0.04f, ez, 0.022f, if (a.lava && glowLava) rgba(1f, 0.6f, 0.1f) else EYE, unlit = a.lava && glowLava, shadow = false)
        }
        for (i in 0..1) {
            val side = if (i == 0) -0.22f else 0.22f
            val sx = hx + cos(a.yaw) * side; val sz = hz - sin(a.yaw) * side
            val h = a.hands[i]
            arm(sx, hy - 0.3f, sz, gx(h[0]), gy(h[1]), gz(h[2]), body, FACE, false, side)
        }
        if (a.lava && glowLava) host.glow(hx, hy - 0.3f, hz, 0.7f, rgba(1f, 0.4f, 0.05f), 0.35f)
        host.drawSphere(hx, gy(world.ground(a.head[0], a.head[2])) + 0.01f, hz, 0.35f, SHADOW, unlit = true, shadow = false)
    }

    /** A tree you can climb: trunk, branches to grab, a crown of leaves. */
    protected fun addTree(x: Float, z: Float, h: Float, r: Float) {
        world.trunk(x, z, r, 0f, h)
        // branches sticking out every 1.5 m, alternating sides
        var y = 1.6f; var k = 0
        while (y < h - 0.5f) {
            val a = k * 2.1f + x
            world.box(x + sin(a) * (r + 0.5f), y, z + cos(a) * (r + 0.5f), if (sin(a) * sin(a) > 0.5f) 1.0f else 0.25f, 0.2f, if (sin(a) * sin(a) > 0.5f) 0.25f else 1.0f)
            y += 1.5f; k++
        }
        trees += floatArrayOf(x, z, h, r)
    }

    protected val trees = ArrayList<FloatArray>()

    protected fun drawWorld(leaf: FloatArray, bark: FloatArray, plank: FloatArray, near: Float = 40f) {
        val px = rig.body[0] + host.head.x; val pz = rig.body[2] + host.head.z
        for (t in trees) {
            if (kotlin.math.abs(t[0] - px) > near || kotlin.math.abs(t[1] - pz) > near) continue
            host.drawCylinder(gx(t[0]), gy(t[2] / 2), gz(t[1]), t[3], t[2], bark, tex = "bark", tile = 1f)
            host.drawEllipsoid(gx(t[0]), gy(t[2] + 1.2f), gz(t[1]), 4.2f, 2.8f, 4.2f, leaf, gloss = 0.05f)
        }
        for (b in world.boxes) {
            val cx = (b[0] + b[3]) / 2; val cz = (b[2] + b[5]) / 2
            if (kotlin.math.abs(cx - px) > near || kotlin.math.abs(cz - pz) > near) continue
            val wide = (b[3] - b[0]) > 3f || (b[5] - b[2]) > 3f
            if ((b[4] - b[1]) > 20f) continue  // invisible boundary
            host.drawBox(gx(cx), gy((b[1] + b[4]) / 2), gz(cz), b[3] - b[0], b[4] - b[1], b[5] - b[2], if (wide) plank else bark, gloss = 0.05f, tex = "wood", tile = 1f)
        }
    }

    protected fun boundary(size: Float) {
        val s = size; val h = 40f
        world.box(0f, h / 2, -s, s * 2, h, 1f); world.box(0f, h / 2, s, s * 2, h, 1f)
        world.box(-s, h / 2, 0f, 1f, h, s * 2); world.box(s, h / 2, 0f, 1f, h, s * 2)
    }

    /** Bot gorilla: runs on the ground toward [Ape.target], hops over things, swings its arms. */
    internal fun stepBot(a: Ape, dt: Float, speed: Float) {
        val dx = a.target[0] - a.head[0]; val dz = a.target[2] - a.head[2]
        val d = sqrt(dx * dx + dz * dz)
        if (d > 0.1f) {
            val want = min(speed, d * 2f)
            a.vel[0] += (dx / d * want - a.vel[0]) * min(1f, dt * 3f)
            a.vel[2] += (dz / d * want - a.vel[2]) * min(1f, dt * 3f)
            a.yaw = atan2(-dx, -dz)
        }
        a.vel[1] -= 9.81f * dt
        a.jumpCool -= dt
        val p = floatArrayOf(a.head[0] + a.vel[0] * dt, a.head[1] - 0.6f + a.vel[1] * dt, a.head[2] + a.vel[2] * dt)
        val n = FloatArray(3)
        val hit = world.resolve(p, 0.4f, n)
        a.grounded = hit && n[1] > 0.5f
        if (hit) {
            if (n[1] > 0.5f && a.vel[1] < 0f) a.vel[1] = 0f
            // blocked by a wall or a trunk: hop
            if (kotlin.math.abs(n[1]) < 0.5f && a.jumpCool <= 0f) { a.vel[1] = 5.2f; a.jumpCool = 0.6f }
        }
        if (a.grounded && a.jumpCool <= 0f && Math.random() < dt * 0.6) { a.vel[1] = 3.5f; a.jumpCool = 0.8f }
        a.head[0] = p[0]; a.head[1] = p[1] + 0.6f; a.head[2] = p[2]
        // knuckle-walking arms
        val t = host.time * 7f + a.id.hashCode()
        for (i in 0..1) {
            val side = if (i == 0) -0.3f else 0.3f
            val swing = sin(t + i * 3.14f) * 0.35f
            a.hands[i][0] = a.head[0] + cos(a.yaw) * side - sin(a.yaw) * swing
            a.hands[i][1] = max(world.ground(a.head[0], a.head[2]) + 0.08f, a.head[1] - 0.95f + kotlin.math.abs(swing) * 0.3f)
            a.hands[i][2] = a.head[2] - sin(a.yaw) * side - cos(a.yaw) * swing
        }
    }

    companion object {
        val FACE = rgba(0.32f, 0.27f, 0.24f)
        val EYE = rgba(0.05f, 0.05f, 0.05f)
        val SHADOW = rgba(0f, 0f, 0f, 0.3f)
    }
}

/**
 * Macacada: gorilla tag in a jungle with tree houses. You move only with your arms (push the ground,
 * climb the trunks and branches). Round 1 you are the lava monkey: tag every gorilla before time
 * runs out; anyone you tag joins the hunt. Online, the other HyperMagic players in the jungle play
 * with you (bots fill the rest). Inspired by Gorilla Tag; everything here is HyperMagic's own.
 */
class MacacadaGame(host: GameHost) : GorillaGameBase(host) {
    override val title = "Macacada"
    override val look = GameSky.Look(
        zenith = rgba(0.35f, 0.62f, 0.95f), horizon = rgba(0.8f, 0.9f, 0.88f), ground = rgba(0.32f, 0.5f, 0.25f),
        sunDir = floatArrayOf(0.4f, 0.75f, 0.3f), sunColor = rgba(1f, 0.95f, 0.8f), sunSize = 0.03f, clouds = 0.4f,
        floor = GameSky.FLOOR_PLAIN, floorColor = rgba(0.28f, 0.48f, 0.2f), fog = 0.012f, objectFog = 0.012f,
        light = rgba(1.05f, 1f, 0.9f), ambient = rgba(0.48f, 0.52f, 0.5f),
    )
    private val bots = ArrayList<Ape>()
    private val remote = HashMap<String, Ape>()
    private var lava = true
    private var timeLeft = 180f
    private var tagged = 0
    private var sendTimer = 0f
    private var net: com.hyperxr.social.Supabase.Realtime? = null
    private val netQueue = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, JSONObject>>()
    private val myId = Social.me?.id ?: ("anon-" + (System.nanoTime() % 100000))
    private val myName = Social.me?.name ?: "Você"
    private val hw = FloatArray(3)
    private val leaf = rgba(0.22f, 0.5f, 0.18f)
    private val bark = rgba(0.45f, 0.32f, 0.2f)
    private val plank = rgba(0.6f, 0.45f, 0.28f)
    private val fur = rgba(0.18f, 0.17f, 0.17f)
    private val palm = rgba(0.33f, 0.28f, 0.25f)

    override fun start() {
        boundary(28f)
        val r = java.util.Random(7)
        for (i in 0 until 22) {
            val a = r.nextFloat() * 6.28f; val d = 5f + r.nextFloat() * 20f
            addTree(sin(a) * d, cos(a) * d, 7f + r.nextFloat() * 5f, 0.35f + r.nextFloat() * 0.2f)
        }
        // tree houses: platforms with a low wall, linked by planks
        for ((x, z, y) in listOf(Triple(-6f, -8f, 4f), Triple(7f, -10f, 6f), Triple(0f, 9f, 3.5f), Triple(12f, 5f, 5f))) {
            world.box(x, y, z, 4f, 0.25f, 4f)
            world.box(x, y + 0.5f, z - 2f, 4f, 0.8f, 0.15f)
            world.trunk(x, z, 0.35f, 0f, y)
        }
        world.box(0.5f, 4.7f, -9f, 9f, 0.2f, 0.8f)  // bridge
        // logs and rocks on the ground to push off
        for (i in 0 until 8) world.box(sin(i * 1.3f) * 12f, 0.3f, cos(i * 1.7f) * 12f, 2.2f, 0.6f, 0.6f)
        rig.teleport(0f, 0f, 0f)
        newRound()
        // online: the shared jungle
        if (Social.me != null) runCatching {
            net = Social.sb.realtime().also { rt ->
                // network events arrive on the socket thread; the game applies them in update()
                rt.subscribe("macacada:selva") { event, p -> netQueue.add(event to p) }
            }
        }
        status = "Você é o macaco de lava! Pegue todos os gorilas: empurre o chão com as mãos para andar, solte rápido para pular"
    }

    private fun newRound() {
        bots.clear()
        val names = listOf("Bongo", "Kiko", "Tatá", "Zuzu", "Momo", "Lulu")
        for (i in 0 until 5) {
            val a = Ape("bot$i", names[i], rgba(0.2f + 0.1f * (i % 3), 0.18f + 0.05f * i, 0.17f))
            val ang = i * 1.25f
            a.head[0] = sin(ang) * 10f; a.head[1] = 1.0f; a.head[2] = cos(ang) * 10f
            bots += a
        }
        lava = true; timeLeft = 180f; tagged = 0; over = false; score = 0
    }

    private fun onNet(event: String, p: JSONObject) {
        val id = p.optString("id")
        if (id == myId || id.isEmpty()) return
        when (event) {
            "state" -> {
                val a = remote.getOrPut(id) { Ape(id, p.optString("name"), rgba(0.25f, 0.2f, 0.3f)) }
                a.name = p.optString("name"); a.lava = p.optBoolean("lava"); a.lastSeen = host.time
                val h = p.optJSONArray("h") ?: return
                for (k in 0..2) a.netHead[k] = h.optDouble(k).toFloat()
                for (i in 0..1) { val q = p.optJSONArray("p$i") ?: continue; for (k in 0..2) a.netHands[i][k] = q.optDouble(k).toFloat() }
                a.yaw = p.optDouble("y").toFloat()
            }
            "tag" -> if (p.optString("target") == myId && !lava) {
                lava = true
                host.play(host.sBoom, host.head.x, host.head.y, host.head.z, 0.6f)
                status = "${p.optString("name")} te pegou! Agora você é lava"
            }
        }
    }

    override fun update(dt: Float) {
        while (true) { val (e, p) = netQueue.poll() ?: break; onNet(e, p) }
        stepPlayer(dt)
        val me = headWorld(hw)
        if (!over) timeLeft -= dt
        // bots: flee the lava ones, or chase when lava
        val hunters = bots.filter { it.lava } + remote.values.filter { it.lava }
        for (b in bots) {
            if (b.lava) {
                val prey = bots.filter { !it.lava }.minByOrNull { dist2(it.head, b.head) }
                if (prey != null) prey.head.copyInto(b.target) else { b.target[0] = me[0]; b.target[2] = me[2] }
                stepBot(b, dt, 3.4f)
            } else {
                // run away from the nearest hunter (you, a lava bot or a lava player)
                var hx = me[0]; var hz = me[2]; var best = if (lava) dist2(me, b.head) else Float.MAX_VALUE
                for (h in hunters) { val d = dist2(h.head, b.head); if (d < best) { best = d; hx = h.head[0]; hz = h.head[2] } }
                if (best < 15f * 15f) {
                    val dx = b.head[0] - hx; val dz = b.head[2] - hz; val d = sqrt(dx * dx + dz * dz).coerceAtLeast(0.1f)
                    b.target[0] = (b.head[0] + dx / d * 6f).coerceIn(-25f, 25f); b.target[2] = (b.head[2] + dz / d * 6f).coerceIn(-25f, 25f)
                } else {
                    b.wander -= dt
                    if (b.wander <= 0f) { b.wander = 3f; b.target[0] = (Math.random().toFloat() - 0.5f) * 40f; b.target[2] = (Math.random().toFloat() - 0.5f) * 40f }
                }
                stepBot(b, dt, 3.0f)
            }
        }
        // tagging: your hands (or a lava bot) touching a gorilla
        if (lava) for (b in bots) if (!b.lava && touchedByMe(b.head)) tag(b)
        for (h in bots.filter { it.lava }) for (b in bots) if (!b.lava && dist2(h.head, b.head) < 0.8f * 0.8f) { b.lava = true; host.play(host.sHit, gx(b.head[0]), gy(b.head[1]), gz(b.head[2]), 0.5f) }
        // online players: smoothing, tagging them, dropping the ones that left
        val gone = remote.values.filter { host.time - it.lastSeen > 5f }.map { it.id }
        gone.forEach { remote.remove(it) }
        for (a in remote.values) {
            for (k in 0..2) a.head[k] += (a.netHead[k] - a.head[k]) * min(1f, dt * 12f)
            for (i in 0..1) for (k in 0..2) a.hands[i][k] += (a.netHands[i][k] - a.hands[i][k]) * min(1f, dt * 14f)
            if (lava && !a.lava && touchedByMe(a.head)) {
                a.lava = true
                net?.broadcast("macacada:selva", "tag", JSONObject().put("id", myId).put("name", myName).put("target", a.id))
                host.play(host.sHit, gx(a.head[0]), gy(a.head[1]), gz(a.head[2]))
            }
        }
        sendTimer -= dt
        if (sendTimer <= 0f && net != null && !offsetY.isNaN()) {
            sendTimer = 0.1f
            net?.broadcast("macacada:selva", "state", JSONObject().put("id", myId).put("name", myName).put("lava", lava).put("y", yaw.toDouble())
                .put("h", arr(me)).put("p0", arr(rig.hand[0])).put("p1", arr(rig.hand[1])))
        }
        val free = bots.count { !it.lava }
        score = tagged * 100 + if (free == 0) timeLeft.toInt() * 5 else 0
        val online = if (remote.isNotEmpty()) " · ${remote.size} online" else ""
        status = when {
            over -> status
            free == 0 -> { over = true; host.play(host.sWin, host.head.x, host.head.y + 0.5f, host.head.z - 1f); "Você pegou todo mundo com ${timeLeft.toInt()} s sobrando!" }
            timeLeft <= 0f -> { over = true; "Tempo esgotado: $tagged de ${bots.size} pegos" }
            else -> "${if (lava) "Você é lava" else "Fuja!"} · faltam $free · ${timeLeft.toInt()} s$online"
        }
    }

    private fun touchedByMe(head: FloatArray): Boolean {
        for (i in 0..1) {
            val tracked = host.hands[i].usable || (i == 1 && host.pad.connected)
            if (!tracked) continue
            val h = rig.hand[i]
            // the hand anywhere on the head or the round body below it
            val dx = h[0] - head[0]; val dz = h[2] - head[2]
            val dy = h[1] - (head[1] - 0.3f)
            if (dx * dx + dz * dz < 0.5f * 0.5f && kotlin.math.abs(dy) < 0.6f) return true
        }
        return false
    }

    private fun tag(b: Ape) {
        b.lava = true; tagged++
        host.play(host.sHit, gx(b.head[0]), gy(b.head[1]), gz(b.head[2]))
        host.particles.burst(Particles.SPARK, gx(b.head[0]), gy(b.head[1]), gz(b.head[2]), 30, 2f, rgba(1f, 0.5f, 0.1f), 0.8f, 0.01f)
        host.pad.buzz(40)
    }

    private fun dist2(a: FloatArray, b: FloatArray): Float { val dx = a[0] - b[0]; val dz = a[2] - b[2]; return dx * dx + dz * dz }
    private fun arr(a: FloatArray) = JSONArray().put(a[0].toDouble()).put(a[1].toDouble()).put(a[2].toDouble())

    override fun draw() {
        if (offsetY.isNaN()) return
        host.sky.floorY = gy(0f) - 0.02f
        drawWorld(leaf, bark, plank)
        for (b in bots) drawApe(b, b.color, true)
        for (a in remote.values) drawApe(a, rgba(0.28f, 0.22f, 0.34f), true)
        drawOwnArms(if (lava) rgba(0.9f, 0.32f, 0.08f) else fur, palm)
    }

    override fun release() { net?.unsubscribe("macacada:selva") }
}

/**
 * Babuíno da Meia-Noite: a night forest, fog, and a giant baboon that hunts by sight. Find the
 * eight glowing bananas moving like a gorilla (arms only). If it sees you it runs at you: climb a
 * tree, it can't follow higher than its reach. Inspired by the horror gorilla games; all original.
 */
class BabuinoGame(host: GameHost) : GorillaGameBase(host) {
    override val title = "Babuíno da Meia-Noite"
    override val look = GameSky.Look(
        zenith = rgba(0.02f, 0.03f, 0.07f), horizon = rgba(0.06f, 0.07f, 0.1f), ground = rgba(0.05f, 0.06f, 0.05f),
        sunDir = floatArrayOf(-0.3f, 0.5f, -0.6f), sunColor = rgba(0.75f, 0.8f, 1f), sunSize = 0.02f, clouds = 0.2f,
        floor = GameSky.FLOOR_PLAIN, floorColor = rgba(0.06f, 0.08f, 0.05f), fog = 0.09f, objectFog = 0.09f,
        light = rgba(0.22f, 0.25f, 0.35f), ambient = rgba(0.08f, 0.09f, 0.12f),
    )
    private val monster = Ape("baboon", "Babuíno", rgba(0.32f, 0.22f, 0.16f))
    private val bananas = ArrayList<FloatArray>()
    private var found = 0
    private var chasing = false
    private var scare = 0f
    private var heartbeat = 0f
    private val hw = FloatArray(3)
    private var sScream = 0
    private var sHeart = 0
    private var lost = 0f

    override fun start() {
        boundary(30f)
        val r = java.util.Random(13)
        for (i in 0 until 40) {
            val a = r.nextFloat() * 6.28f; val d = 4f + r.nextFloat() * 25f
            addTree(sin(a) * d, cos(a) * d, 6f + r.nextFloat() * 6f, 0.3f + r.nextFloat() * 0.25f)
        }
        for (i in 0 until 6) world.box(sin(i * 2.1f) * 15f, 0.4f, cos(i * 1.3f) * 15f, 3f, 0.8f, 0.8f)
        for (i in 0 until 8) {
            val a = i / 8f * 6.28f + 0.4f; val d = 8f + (i % 3) * 7f
            // some bananas are up in the trees
            bananas += floatArrayOf(sin(a) * d, if (i % 3 == 0) 3.5f else 0.6f, cos(a) * d, 0f)
        }
        monster.head[0] = 0f; monster.head[1] = 2.2f; monster.head[2] = -24f
        rig.teleport(0f, 0f, 6f)
        sScream = host.ctx.audio.synth("noise", 0f, 0f, 1.2f, 0.9f, 0.01f, 0.5f)
        sHeart = host.ctx.audio.synth("sine", 60f, 40f, 0.18f, 0.9f, 0.002f, 0.08f)
        status = "Ache as 8 bananas brilhantes. Se o babuíno te ver, suba numa árvore!"
    }

    override fun update(dt: Float) {
        if (scare > 0f) { scare += dt; if (scare > 2.5f && !over) { over = true; status = "O babuíno te pegou! Bananas: $found de 8" }; return }
        stepPlayer(dt)
        val me = headWorld(hw)
        // bananas: grab with a hand (or walk into)
        for (b in bananas) {
            if (b[3] > 0f) continue
            for (i in 0..1) {
                val h = rig.hand[i]
                val dx = h[0] - b[0]; val dy = h[1] - b[1]; val dz = h[2] - b[2]
                if (dx * dx + dy * dy + dz * dz < 0.35f * 0.35f) {
                    b[3] = 1f; found++
                    host.play(host.sCoin, gx(b[0]), gy(b[1]), gz(b[2]))
                    host.pad.buzz(30)
                }
            }
        }
        if (found >= 8 && !over) { over = true; score = 800 + (300 - host.time.toInt()).coerceAtLeast(0); status = "Você achou todas as bananas e escapou!"; host.play(host.sWin, host.head.x, host.head.y, host.head.z - 1f); return }
        // the baboon: sees you within 14 m in front of it (or 5 m anywhere), loses you after 4 s out of sight
        val dx = me[0] - monster.head[0]; val dz = me[2] - monster.head[2]
        val d = sqrt(dx * dx + dz * dz)
        val facing = -sin(monster.yaw) * dx / d.coerceAtLeast(0.1f) + -cos(monster.yaw) * dz / d.coerceAtLeast(0.1f)
        val high = me[1] - world.ground(me[0], me[2]) > 2.6f
        val sees = !high && (d < 5f || (d < 14f && facing > 0.4f))
        if (sees) { if (!chasing) host.play(sScream, gx(monster.head[0]), gy(monster.head[1]), gz(monster.head[2]), 0.7f, 1.3f); chasing = true; lost = 0f }
        else if (chasing) { lost += dt; if (lost > 4f) chasing = false }
        if (chasing) { monster.target[0] = me[0]; monster.target[2] = me[2] }
        else {
            monster.wander -= dt
            if (monster.wander <= 0f) { monster.wander = 5f; monster.target[0] = (Math.random().toFloat() - 0.5f) * 50f; monster.target[2] = (Math.random().toFloat() - 0.5f) * 50f }
        }
        stepBot(monster, dt, if (chasing) 4.6f else 1.8f)
        monster.head[1] = max(monster.head[1], world.ground(monster.head[0], monster.head[2]) + 2.2f)
        // caught: it reaches up to 2.6 m
        if (d < 1.1f && !high) { scare = 0.01f; host.play(sScream, host.head.x, host.head.y, host.head.z - 0.5f, 1f, 0.8f); host.pad.buzz(400) }
        // the closer it is, the faster your heart
        heartbeat -= dt
        val rate = when { d < 8f -> 0.45f; d < 16f -> 0.8f; else -> 0f }
        if (rate > 0f && heartbeat <= 0f) { heartbeat = rate; host.play(sHeart, host.head.x, host.head.y - 0.3f, host.head.z, if (d < 8f) 0.9f else 0.5f) }
        score = found * 100
        status = "Bananas: $found de 8" + when { chasing -> " · ELE TE VIU, CORRA!"; d < 16f -> " · ele está perto…"; else -> "" }
    }

    override fun draw() {
        if (offsetY.isNaN()) return
        host.sky.floorY = gy(0f) - 0.02f
        drawWorld(rgba(0.08f, 0.16f, 0.08f), rgba(0.2f, 0.15f, 0.1f), rgba(0.25f, 0.2f, 0.14f), 30f)
        for (b in bananas) if (b[3] == 0f) {
            val bob = sin(host.time * 2f + b[0]) * 0.05f
            host.drawModel("food/banana", gx(b[0]), gy(b[1]) + bob, gz(b[2]), yaw = host.time + b[2], scale = 1.6f)
            host.glow(gx(b[0]), gy(b[1]) + bob, gz(b[2]), 0.5f, rgba(1f, 0.9f, 0.3f), 0.5f)
        }
        // the baboon: big, long arms, red eyes in the dark
        val m = monster
        val hx = gx(m.head[0]); val hy = gy(m.head[1]); val hz = gz(m.head[2])
        host.drawEllipsoid(hx, hy - 0.8f, hz, 1.1f, 1.3f, 0.9f, m.color, yaw = m.yaw, gloss = 0.05f)
        host.drawEllipsoid(hx, hy, hz, 0.6f, 0.62f, 0.62f, m.color, yaw = m.yaw, gloss = 0.05f)
        val fx = -sin(m.yaw); val fz = -cos(m.yaw)
        // long snout
        host.drawEllipsoid(hx + fx * 0.35f, hy - 0.1f, hz + fz * 0.35f, 0.32f, 0.26f, 0.45f, rgba(0.55f, 0.25f, 0.22f), yaw = m.yaw, gloss = 0.2f)
        for (s in listOf(-1f, 1f)) {
            val ex = hx + fx * 0.3f + cos(m.yaw) * 0.14f * s; val ez = hz + fz * 0.3f - sin(m.yaw) * 0.14f * s
            host.drawSphere(ex, hy + 0.12f, ez, 0.045f, rgba(1f, 0.1f, 0.05f), unlit = true, shadow = false)
            host.glow(ex, hy + 0.12f, ez, 0.25f, rgba(1f, 0.1f, 0.05f), if (chasing) 0.9f else 0.5f)
        }
        for (i in 0..1) {
            val side = if (i == 0) -0.5f else 0.5f
            val h = m.hands[i]
            host.drawBeam(hx + cos(m.yaw) * side, hy - 0.5f, hz - sin(m.yaw) * side, gx(h[0]), gy(h[1]) - 0.4f, gz(h[2]), 0.13f, m.color, unlit = false, gloss = 0.05f)
        }
        // jump scare: the face rushing at you
        if (scare > 0f) {
            val k = min(1f, scare * 2f)
            val f = host.ctx.input.head.orientation.rotate(Vec3(0f, 0f, -1f))
            val g = host.dirToGame(f, Vec3())
            val dist = 1.6f - k * 1.3f
            host.drawEllipsoid(host.head.x + g.x * dist, host.head.y + g.y * dist, host.head.z + g.z * dist, 0.9f, 0.9f, 0.9f, m.color, gloss = 0.1f)
            for (s in listOf(-1f, 1f)) host.glow(host.head.x + g.x * (dist - 0.4f) + s * 0.15f, host.head.y + 0.1f, host.head.z + g.z * (dist - 0.4f), 0.3f, rgba(1f, 0f, 0f), 1f)
        }
        drawOwnArms(rgba(0.16f, 0.15f, 0.15f), rgba(0.3f, 0.26f, 0.24f))
        // a little moonlight around you so the forest floor reads
        host.glow(host.head.x, host.head.y - 1f, host.head.z, 2.5f, rgba(0.3f, 0.35f, 0.5f), 0.12f)
    }
}
