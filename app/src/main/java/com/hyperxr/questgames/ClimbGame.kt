package com.hyperxr.questgames

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Escalada: a cliff 27 m high above a mountain valley. Close a hand (or pull the trigger) on a
 * hold to grab it and pull yourself up; let go of everything and you fall to the last ledge.
 * Hanging from one hand tires it: rest with two, or on the ledges.
 */
class ClimbGame(host: GameHost) : QuestGame(host) {
    override val title = "Escalada"
    override val look = GameSky.Look(
        zenith = rgba(0.2f, 0.42f, 0.86f), horizon = rgba(0.78f, 0.86f, 0.95f), ground = rgba(0.4f, 0.45f, 0.4f),
        sunDir = floatArrayOf(0.5f, 0.6f, 0.45f), sunColor = rgba(1f, 0.95f, 0.85f), sunSize = 0.025f, clouds = 0.5f,
        floor = GameSky.FLOOR_PLAIN, floorColor = rgba(0.3f, 0.45f, 0.22f), fog = 0.004f, objectFog = 0.004f,
        light = rgba(1.05f, 1f, 0.92f), ambient = rgba(0.45f, 0.48f, 0.56f),
    )
    override val shadowRadius = 3f
    override val shadowZ = -0.6f
    private val rig = ClimbRig.route(System.nanoTime() % 1000)
    private val px = FloatArray(3); private val py = FloatArray(3); private val pz = FloatArray(3)
    private val closed = BooleanArray(3); private val present = BooleanArray(3)
    private val reach = floatArrayOf(0.14f, 0.14f, 0.2f)
    private val holdColor = Array(rig.holds.size) { HOLD_COLORS[it * 7 % HOLD_COLORS.size] }
    private val holdShape = FloatArray(rig.holds.size) { (it * 37 % 11) / 11f }
    private var falls = 0
    private var wasStanding = true
    private var time = 0f
    private var lastLedge = 0f

    override fun start() { status = "Feche a mão numa agarra (ou aperte o gatilho) e puxe para subir. Soltando tudo, você cai até a última plataforma" }

    override fun update(dt: Float) {
        for (i in 0..1) {
            val g = host.hands[i]
            present[i] = g.usable
            px[i] = g.palm.x; py[i] = g.palm.y; pz[i] = g.palm.z
            closed[i] = g.fist || g.pinch
        }
        val pad = host.pad
        present[2] = pad.connected
        px[2] = pad.pose.position.x; py[2] = pad.pose.position.y; pz[2] = pad.pose.position.z
        closed[2] = pad.trigger
        // a controller alone climbs one grip at a time: more time to reach the next hold
        val onlyPad = pad.connected && !present[0] && !present[1]
        val grabbed = rig.step(dt, px, py, pz, closed, present, reach, if (onlyPad) 1.4f else 0.6f)
        if (grabbed >= 0) {
            val h = rig.holds[grabbed]
            host.play(host.sTick, h[0] - rig.ox, h[1] - rig.oy, h[2] - rig.oz, 0.6f, 0.8f)
            if (rig.grips[2].hold == grabbed) pad.buzz(25)
            // chalk dust
            host.particles.burst(Particles.BLOB, h[0] - rig.ox, h[1] - rig.oy, h[2] - rig.oz + 0.05f, 6, 0.4f, CHALK, 0.6f, 0.006f, gravity = 1.5f)
        }
        if (rig.standing && !wasStanding) {
            host.play(host.sPunch, host.head.x, 0f, host.head.z, 0.8f, 0.7f)
            if (rig.oy < lastLedge - 0.5f || rig.falls > falls) { falls = rig.falls; host.play(host.sMiss, host.head.x, host.head.y, host.head.z, 0.5f) }
        }
        wasStanding = rig.standing
        if (rig.standing) lastLedge = rig.oy
        if (!over) time += dt
        score = (rig.maxHeight * 10).toInt()
        val height = max(0f, rig.oy)
        status = "Altura ${"%.1f".format(height)} m · recorde ${"%.1f".format(rig.maxHeight)} m · fôlego ${(rig.stamina * 100).toInt()}%" +
            when {
                rig.stamina < 0.3f && rig.grips.count { it.hold >= 0 } == 1 -> " · segure com as duas mãos para descansar!"
                rig.grace > 0f && rig.grips.none { it.hold >= 0 } && !rig.standing -> " · agarre a próxima!"
                else -> ""
            }
        if (rig.won && !over) {
            over = true; score += 500
            status = "Você chegou ao topo em ${time.toInt()} s, com $falls queda(s)!"
            host.play(host.sWin, host.head.x, host.head.y + 0.5f, host.head.z - 1f)
            host.particles.burst(Particles.SPARK, 0f, host.head.y + 0.6f, -1f, 60, 3f, rgba(1f, 0.85f, 0.3f), 1.5f, 0.008f, gravity = 2f, up = 2f)
        }
    }

    private val white = rgba(1f, 1f, 1f)
    private val rock = rgba(0.78f, 0.74f, 0.7f)

    override fun draw() {
        val ox = rig.ox; val oy = rig.oy; val oz = rig.oz
        host.sky.floorY = -oy - 0.02f
        val wallFront = -0.48f
        val top = rig.summitY
        // the cliff: a rock face from below the valley floor to the summit
        host.drawBox(-ox, top / 2 - 1f - oy, wallFront - 1.5f - oz, 7f, top + 2f, 3f, rock, gloss = 0.1f, tex = "rock", tile = 1.6f)
        // boulders on the face beside the route, so it is not one flat slab
        val r = java.util.Random(11)
        for (i in 0 until 16) {
            val x = (if (i % 2 == 0) -1 else 1) * (1.9f + r.nextFloat() * 1.2f)
            val y = r.nextFloat() * top
            if (abs(y - oy - 1f) > 14f) continue
            host.drawModel("survival/rock-${"abc"[i % 3]}", x - ox, y - oy, wallFront - 0.2f - oz, yaw = r.nextFloat() * 6f, scale = 2f + r.nextFloat() * 2f, shadow = false)
        }
        // the valley floor at the base, grass and trees
        host.drawBox(-ox, -0.05f - oy, 3f - oz, 14f, 0.1f, 8f, white, gloss = 0.05f, tex = "grass", tile = 1.5f)
        for (i in 0 until 8) host.drawModel(if (i % 2 == 0) "survival/tree-tall" else "survival/tree", -6f + i * 1.7f - ox, -oy, 4f + (i % 3) * 1.5f - oz, yaw = i.toFloat(), scale = 3f + i % 3)
        // ledges to rest on, with a flag each
        for (l in rig.ledges) {
            if (abs(l - oy) > 12f) continue
            host.drawBox(-ox, l - 0.15f - oy, (wallFront + 0.5f) / 2 - oz, 6f, 0.3f, 1f, rock, gloss = 0.1f, tex = "rock", tile = 1f)
            host.drawModel("platformer/flag", 1.6f - ox, l - oy, -0.1f - oz, scale = 0.9f)
        }
        // the summit: grass on top, a big flag
        host.drawBox(-ox, top + 0.03f - oy, wallFront - 1.5f - oz, 7f, 0.06f, 3f, white, gloss = 0.05f, tex = "grass", tile = 1.5f)
        host.drawModel("platformer/flag", -ox, top - oy, wallFront - 0.6f - oz, scale = 2f)
        host.glow(-ox, top + 1.2f - oy, wallFront - 0.6f - oz, 0.8f, rgba(1f, 0.9f, 0.5f), 0.25f + 0.1f * sin(host.time * 2f))
        // mountains all around, snow on their tops
        for (i in 0 until 9) {
            val a = i / 9f * 6.28f + 0.3f
            val d = 220f + (i % 3) * 40f
            val s = 120f + (i * 37 % 5) * 25f
            val mx = sin(a) * d - ox; val mz = -cos(a) * d - oz
            host.drawMesh(host.cone, mx, s * 0.25f - oy, mz, s * 1.6f, s * 0.5f + 30f, s * 1.6f, rgba(0.42f, 0.44f, 0.48f), gloss = 0f, shadow = false)
            host.drawMesh(host.cone, mx, s * 0.25f + (s * 0.5f + 30f) * 0.35f - oy, mz, s * 0.5f, (s * 0.5f + 30f) * 0.3f, s * 0.5f, rgba(0.95f, 0.96f, 0.98f), gloss = 0.2f, shadow = false)
        }
        // the holds near you (the rest are hidden by distance anyway)
        val near = FloatArray(3) { -1f }
        for (i in 0..2) if (present[i] && rig.grips[i].hold < 0) near[i] = rig.nearest(px[i] + ox, py[i] + oy, pz[i] + oz, reach[i]).toFloat()
        for ((k, h) in rig.holds.withIndex()) {
            val y = h[1] - oy
            if (y < -3f || y > 6f) continue
            val held = rig.grips.any { it.hold == k }
            val s = holdShape[k]
            host.drawRounded(h[0] - ox, y, h[2] - oz, 0.08f + s * 0.05f, 0.055f + (1f - s) * 0.03f, 0.07f, holdColor[k], yaw = 0f,
                orientation = com.hyperxr.math.Quat.fromAxisAngle(0f, 0f, 1f, (s - 0.5f) * 1.2f), gloss = 0.4f, soft = true)
            if (held) host.glow(h[0] - ox, y, h[2] - oz + 0.03f, 0.08f, WHITE_GLOW, 0.35f + 0.4f * (1f - rig.stamina))
            else if (near.any { it.toInt() == k }) host.glow(h[0] - ox, y, h[2] - oz + 0.03f, 0.07f, REACH_GLOW, 0.5f)
        }
        // the hand on the controller: a chalked glove
        if (present[2]) host.drawRounded(px[2], py[2], pz[2], 0.09f, 0.05f, 0.11f, if (closed[2]) rgba(0.95f, 0.95f, 0.92f) else rgba(0.75f, 0.6f, 0.45f), gloss = 0.2f, soft = true)
        // fatigue: the view reddens as the grip gives out
        if (rig.stamina < 0.35f && rig.grips.any { it.hold >= 0 }) host.glow(host.head.x, host.head.y, host.head.z, 0.3f, rgba(1f, 0.15f, 0.1f), (0.35f - rig.stamina) * 1.5f)
    }

    companion object {
        private fun rgba(r: Float, g: Float, b: Float, a: Float = 1f) = floatArrayOf(r, g, b, a)
        private val HOLD_COLORS = arrayOf(rgba(0.95f, 0.3f, 0.25f), rgba(0.2f, 0.6f, 0.95f), rgba(0.98f, 0.8f, 0.2f), rgba(0.3f, 0.8f, 0.4f), rgba(0.75f, 0.35f, 0.9f), rgba(1f, 0.55f, 0.15f))
        private val CHALK = rgba(0.95f, 0.95f, 0.95f, 0.8f)
        private val WHITE_GLOW = rgba(1f, 0.95f, 0.85f)
        private val REACH_GLOW = rgba(0.4f, 1f, 0.6f)
    }
}
