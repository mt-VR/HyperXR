package com.hyperxr.questgames

import com.hyperxr.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Escalada de Cano: you hold a metal pipe and that is all you have. You can't walk; the pipe is
 * your only way up a tower of scaffolding: hook it over a bar and pull, plant its end on a
 * platform and push, vault across a gap. It only holds where a pipe would: bars, edges, floors and
 * steps; on the smooth glass walls it slides off.
 *
 * Your hands are not drawn: a ball takes the place of each hand and holds the pipe, and stays where
 * it was when the camera loses the hand. No shadows. The pipe's end and middle are the "hands" of
 * the gorilla locomotion (GorillaRig, Another Axiom's GorillaLocomotion port): where they touch,
 * they stay, and your body moves instead.
 */
class PipeClimbGame(host: GameHost) : QuestGame(host) {
    override val title = "Escalada de Cano"
    override val look = GameSky.Look(
        zenith = rgba(0.32f, 0.5f, 0.82f), horizon = rgba(0.86f, 0.82f, 0.74f), ground = rgba(0.35f, 0.33f, 0.3f),
        sunDir = floatArrayOf(-0.4f, 0.55f, 0.6f), sunColor = rgba(1f, 0.9f, 0.75f), sunSize = 0.03f, clouds = 0.45f,
        floor = GameSky.FLOOR_PLAIN, floorColor = rgba(0.42f, 0.4f, 0.37f), fog = 0.006f, objectFog = 0.006f,
        light = rgba(1.05f, 0.98f, 0.9f), ambient = rgba(0.46f, 0.48f, 0.55f),
    )
    override val shadowRadius = 0f
    override val hidesHands = true

    private val world = GorillaRig.World()
    /** Contact 0 is the pipe's end, contact 1 its middle. */
    private val rig = GorillaRig(world).apply {
        armLength = 2.4f; handRadius = 0.04f; unStickDistance = 1.2f
        velocityLimit = 0.55f; jumpMultiplier = 1.0f; maxJumpSpeed = 4.5f
        // a person, not a gorilla: the body reaches down to the feet, eyes 1.35 m over them
        bodyDrop = 1.05f; bodyRadius = 0.3f
    }
    private var offsetY = Float.NaN
    private val eye = 1.35f

    // the hands (balls) and the pipe, in game space
    private val feeds = arrayOf(HandFeed(holdS = 0.8f), HandFeed(holdS = 0.8f), HandFeed(holdS = 0.3f))
    private val ball = Array(2) { FloatArray(3) }
    private val ballOn = BooleanArray(2)
    private val padBall = FloatArray(3)
    private var padOn = false
    private val dir = Vec3(0f, -0.3f, -1f).normalize()
    private val back = FloatArray(3); private val tip = FloatArray(3); private val mid = FloatArray(3)
    private var pipeOn = false

    private val headL = FloatArray(3)
    private val contacts = arrayOfNulls<FloatArray>(2)
    private val tipL = FloatArray(3); private val midL = FloatArray(3)

    // the tower
    private class Platform(val x: Float, val top: Float, val z: Float, val sx: Float, val sz: Float)
    private val platforms = ArrayList<Platform>()
    private val bars = ArrayList<FloatArray>()
    private val glass = ArrayList<FloatArray>()
    private var summit = 0f
    private var best = 0f
    private var time = 0f
    private var won = false
    private var tapCool = 0f
    private var lastStood = 0

    override fun start() {
        build()
        rig.teleport(0f, 0f, 0f)
        status = "Segure o cano com as duas mãos (ou uma). Engate nas barras e puxe, apoie a ponta no chão e empurre: só o cano te move"
    }

    /**
     * A straight tower going forward and up: steps 0.9 m higher and 1.9 m further each, some with a
     * bar along the front edge (hook and pull), some with a bar overhead, some with a gap to vault.
     * Glass walls on both sides keep you on it; the pipe slides on glass.
     */
    private fun build() {
        world.ground = { _, _ -> 0f }
        platforms += Platform(0f, 0f, 0f, 3.2f, 3f)
        var z = -2.1f; var y = 0f
        for (i in 1..14) {
            val kind = i % 3
            y += if (kind == 2) 0.6f else 0.9f
            z -= if (kind == 2) 2.6f else 1.9f
            val p = Platform(0f, y, z, 3.2f, 1.6f)
            platforms += p
            // the step: a block from the ground to its top
            world.box(p.x, p.top / 2, p.z, p.sx, p.top, p.sz)
            when (kind) {
                // a bar along the front edge, a little above it: hook the pipe over and pull
                0 -> bar(-1.4f, p.top + 0.12f, p.z + p.sz / 2 - 0.08f, 1.4f, p.top + 0.12f, p.z + p.sz / 2 - 0.08f, 0.035f)
                // a bar overhead, between this step and the one before: hang from it
                1 -> {
                    bar(-1.4f, p.top + 1.1f, p.z + p.sz / 2 + 0.25f, 1.4f, p.top + 1.1f, p.z + p.sz / 2 + 0.25f, 0.04f)
                    // posts holding it
                    for (sx in listOf(-1.45f, 1.45f)) bar(sx, p.top, p.z + p.sz / 2 + 0.25f, sx, p.top + 1.1f, p.z + p.sz / 2 + 0.25f, 0.05f)
                }
                // a gap: short pegs to plant the pipe on, then vault over
                else -> for (sx in listOf(-0.8f, 0f, 0.8f)) bar(sx, p.top - 0.5f, p.z + p.sz / 2 + 0.9f, sx, p.top - 0.5f, p.z + p.sz / 2 + 1.3f, 0.05f)
            }
        }
        val last = platforms.last()
        summit = last.top
        // glass side walls the whole way: the pipe slides on them (no grip)
        val zMin = last.z - last.sz; val len = -zMin + 3f
        for (sx in listOf(-1.75f, 1.75f)) {
            world.box(sx, (summit + 4f) / 2, (zMin + 3f) / 2, 0.1f, summit + 4f, len, slip = GorillaRig.NO_GRIP)
            glass += floatArrayOf(sx, (summit + 4f) / 2, (zMin + 3f) / 2, 0.1f, summit + 4f, len)
        }
        // behind the start
        world.box(0f, 2f, 1.7f, 3.6f, 4f, 0.1f, slip = GorillaRig.NO_GRIP)
        glass += floatArrayOf(0f, 2f, 1.7f, 3.6f, 4f, 0.1f)
    }

    private fun bar(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: Float) {
        world.capsule(ax, ay, az, bx, by, bz, r)
        bars += floatArrayOf(ax, ay, az, bx, by, bz, r)
    }

    override fun update(dt: Float) {
        if (offsetY.isNaN()) {
            if (host.head.y < 0.5f) return
            offsetY = host.head.y - eye
        }
        if (!won) time += dt
        updateGrip(dt)
        headL[0] = host.head.x; headL[1] = host.head.y - offsetY; headL[2] = host.head.z
        if (pipeOn) {
            tipL[0] = tip[0]; tipL[1] = tip[1] - offsetY; tipL[2] = tip[2]
            midL[0] = mid[0]; midL[1] = mid[1] - offsetY; midL[2] = mid[2]
            contacts[0] = tipL; contacts[1] = midL
        } else { contacts[0] = null; contacts[1] = null }
        rig.step(dt, headL, contacts)
        tapCool -= dt
        if ((rig.tapped[0] || rig.tapped[1]) && tapCool <= 0f) {
            tapCool = 0.15f
            val k = if (rig.tapped[0]) 0 else 1
            val h = rig.hand[k]
            host.play(host.sTick, gx(h[0]), gy(h[1]), gz(h[2]), 0.55f, 0.35f)
            host.pad.buzz(20)
        }
        // how high your feet are, and the step you last stood on
        val feet = rig.body[1] + headL[1] - (rig.bodyDrop + rig.bodyRadius)
        if (rig.grounded) for ((i, p) in platforms.withIndex()) {
            val hx = rig.body[0] + headL[0]; val hz = rig.body[2] + headL[2]
            if (abs(feet - p.top) < 0.15f && abs(hx - p.x) < p.sx / 2 + 0.2f && abs(hz - p.z) < p.sz / 2 + 0.2f && i > lastStood) {
                lastStood = i
                host.play(host.sCoin, host.head.x, host.head.y - 0.6f, host.head.z, 0.5f)
            }
        }
        best = max(best, feet)
        score = (best * 10).toInt()
        // fell off the tower (or out of the world): back to the start
        if (rig.body[1] < -20f) { rig.teleport(0f, 0f, 0f); lastStood = 0 }
        if (!won && lastStood == platforms.size - 1) {
            won = true; over = true; score += 500
            status = "Você chegou ao topo em ${time.toInt()} s!"
            host.play(host.sWin, host.head.x, host.head.y + 0.5f, host.head.z - 1f)
            host.particles.burst(Particles.SPARK, host.head.x, host.head.y + 0.5f, host.head.z - 1f, 60, 3f, rgba(1f, 0.8f, 0.3f), 1.5f, 0.008f, gravity = 2f, up = 2f)
            return
        }
        status = "Altura ${"%.1f".format(max(0f, feet))} m · recorde ${"%.1f".format(best)} m · degrau $lastStood de ${platforms.size - 1}" +
            if (!pipeOn) " · mostre as mãos para a câmera" else ""
    }

    /**
     * Where the pipe is: with both hands, along the line from the hand nearer you through the
     * other (held like a pole); with one hand, along where that hand points; with a controller,
     * along its aim. The balls hold it.
     */
    private fun updateGrip(dt: Float) {
        for (i in 0..1) {
            val g = host.hands[i]
            val p = feeds[i].update(if (g.usable) g.palm else null, host.head, dt)
            ballOn[i] = p != null
            if (p != null) p.copyInto(ball[i])
        }
        val pp = feeds[2].update(if (host.pad.connected) host.pad.pose.position else null, host.head, dt)
        padOn = pp != null
        if (pp != null) pp.copyInto(padBall)
        val want = Vec3()
        val grip = FloatArray(3)
        when {
            ballOn[0] && ballOn[1] -> {
                // the hand nearer the head is the back of the pole
                val d0 = dist2(ball[0], host.head); val d1 = dist2(ball[1], host.head)
                val (b, f) = if (d0 <= d1) ball[0] to ball[1] else ball[1] to ball[0]
                want.set(f[0] - b[0], f[1] - b[1], f[2] - b[2])
                if (want.length() < 0.08f) want.set(dir) else want.normalize()
                b.copyInto(grip)
            }
            padOn && !ballOn[0] && !ballOn[1] -> { want.set(host.pad.aim.direction).normalize(); padBall.copyInto(grip) }
            ballOn[0] || ballOn[1] -> {
                val i = if (ballOn[1]) 1 else 0
                want.set(host.hands[i].dir).normalize()
                // a hand lost for a moment keeps the pipe's direction
                if (feeds[i].holding) want.set(dir)
                ball[i].copyInto(grip)
            }
            else -> { pipeOn = false; return }
        }
        // the pipe turns smoothly (a camera-tracked hand's direction shakes more than its place)
        val k = (dt * 12f).coerceIn(0f, 1f)
        dir.lerp(want, k).normalize()
        pipeOn = true
        // the hands hold it 0.25 m from its back end; it is 1.5 m long
        for (a in 0..2) {
            val d = when (a) { 0 -> dir.x; 1 -> dir.y; else -> dir.z }
            back[a] = grip[a] - d * PIPE_BACK
            tip[a] = back[a] + d * PIPE_LEN
            mid[a] = back[a] + d * PIPE_LEN * 0.55f
        }
    }

    private fun dist2(a: FloatArray, b: Vec3): Float { val dx = a[0] - b.x; val dy = a[1] - b.y; val dz = a[2] - b.z; return dx * dx + dy * dy + dz * dz }

    private fun gx(x: Float) = x - rig.body[0]
    private fun gy(y: Float) = y - rig.body[1] + offsetY
    private fun gz(z: Float) = z - rig.body[2]

    override fun draw() {
        if (offsetY.isNaN()) return
        host.sky.floorY = gy(0f) - 0.02f
        // the yard around the tower
        host.drawBox(gx(0f), gy(-0.05f), gz(-12f), 30f, 0.1f, 40f, CONCRETE, gloss = 0.05f, tex = "concrete", tile = 1.2f)
        val px = rig.body[0] + host.head.x; val pz = rig.body[2] + host.head.z; val py = rig.body[1] + host.head.y - offsetY
        for ((i, p) in platforms.withIndex()) {
            if (i == 0 || abs(p.z - pz) > 25f || abs(p.top - py) > 20f) continue
            host.drawBox(gx(p.x), gy(p.top / 2), gz(p.z), p.sx, p.top, p.sz, if (i % 2 == 0) BLOCK_A else BLOCK_B, gloss = 0.1f, tex = "concrete", tile = 1f)
            // a yellow safety edge on every step, and the number
            host.drawBox(gx(p.x), gy(p.top + 0.005f), gz(p.z + p.sz / 2 - 0.06f), p.sx, 0.012f, 0.12f, if (i <= lastStood) DONE else EDGE, gloss = 0.3f)
        }
        for (b in bars) {
            val cz = (b[2] + b[5]) / 2; val cy = (b[1] + b[4]) / 2
            if (abs(cz - pz) > 25f || abs(cy - py) > 20f) continue
            host.drawBeam(gx(b[0]), gy(b[1]), gz(b[2]), gx(b[3]), gy(b[4]), gz(b[5]), b[6], BAR, unlit = false, gloss = 0.7f, metal = 0.8f)
        }
        for (g in glass) host.drawBox(gx(g[0]), gy(g[1]), gz(g[2]), g[3], g[4], g[5], GLASS, gloss = 0.9f, shadow = false)
        // the flag at the top
        val top = platforms.last()
        host.drawModel("platformer/flag", gx(0f), gy(top.top), gz(top.z), scale = 1.6f)
        host.glow(gx(0f), gy(top.top + 1.2f), gz(top.z), 0.7f, rgba(1f, 0.85f, 0.4f), 0.25f + 0.1f * sin(host.time * 2f))
        // the pipe, held by the balls
        if (pipeOn) {
            host.drawBeam(back[0], back[1], back[2], tip[0], tip[1], tip[2], PIPE_R, PIPE, unlit = false, gloss = 0.85f, metal = 1f)
            host.drawSphere(tip[0], tip[1], tip[2], PIPE_R * 1.25f, CAP, gloss = 0.5f, shadow = false)
            host.drawSphere(back[0], back[1], back[2], PIPE_R * 1.25f, CAP, gloss = 0.5f, shadow = false)
        }
        val touching = rig.touching[0] || rig.touching[1]
        for (i in 0..1) if (ballOn[i]) host.drawSphere(onPipe(ball[i], 0), onPipe(ball[i], 1), onPipe(ball[i], 2), BALL_R, if (touching) BALL_HOLD else BALL, gloss = 0.6f, shadow = false)
        if (padOn && !ballOn[0] && !ballOn[1]) host.drawSphere(padBall[0], padBall[1], padBall[2], BALL_R, if (touching) BALL_HOLD else BALL, gloss = 0.6f, shadow = false)
    }

    /** A ball sits on the pipe: the hand's place pulled onto the pipe's line. */
    private fun onPipe(p: FloatArray, a: Int): Float {
        if (!pipeOn) return p[a]
        val t = ((p[0] - back[0]) * dir.x + (p[1] - back[1]) * dir.y + (p[2] - back[2]) * dir.z).coerceIn(0f, PIPE_LEN)
        val d = when (a) { 0 -> dir.x; 1 -> dir.y; else -> dir.z }
        val onLine = back[a] + d * t
        // close to the line: on it; far (the other hand isn't on the pipe): where it is
        val dx = p[0] - (back[0] + dir.x * t); val dy = p[1] - (back[1] + dir.y * t); val dz = p[2] - (back[2] + dir.z * t)
        return if (sqrt(dx * dx + dy * dy + dz * dz) < 0.2f) onLine else p[a]
    }

    companion object {
        private const val PIPE_LEN = 1.5f
        private const val PIPE_BACK = 0.25f
        private const val PIPE_R = 0.022f
        private const val BALL_R = 0.045f
        private fun rgba(r: Float, g: Float, b: Float, a: Float = 1f) = floatArrayOf(r, g, b, a)
        private val CONCRETE = rgba(0.75f, 0.74f, 0.72f)
        private val BLOCK_A = rgba(0.62f, 0.64f, 0.68f)
        private val BLOCK_B = rgba(0.55f, 0.58f, 0.63f)
        private val EDGE = rgba(1f, 0.78f, 0.1f)
        private val DONE = rgba(0.3f, 0.85f, 0.45f)
        private val BAR = rgba(0.95f, 0.45f, 0.12f)
        private val GLASS = rgba(0.6f, 0.8f, 0.95f, 0.18f)
        private val PIPE = rgba(0.72f, 0.74f, 0.78f)
        private val CAP = rgba(0.2f, 0.2f, 0.22f)
        private val BALL = rgba(0.92f, 0.94f, 0.98f)
        private val BALL_HOLD = rgba(1f, 0.82f, 0.35f)
    }
}
