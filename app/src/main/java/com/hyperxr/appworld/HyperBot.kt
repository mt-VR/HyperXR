package com.hyperxr.appworld

import android.graphics.Color
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.GpuMesh
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HyperBot: HyperMagic's mascot, a small blue robot (about 20 cm) that lives on your table. With
 * ARCore it finds the top of the table under it (planes, which ARCore fits with its depth) and
 * floats a few centimetres above it; it never wanders off the edge. Without ARCore it floats where
 * you put it.
 *
 * It is all built here from rounded shapes: a glossy blue head with a dark visor where its eyes
 * glow, an antenna, ear pods, a round body with a white belly and a chest light, little arms and a
 * hover ring under it, with its shadow on the table.
 *
 * What it does: breathes and floats, blinks, follows you with its head, looks around, glides about
 * the table, waves when you look at it for a moment, giggles when you pat its head with your
 * finger, jumps when your hand rushes past, dances when music plays, sleeps late at night (or when
 * left alone) and says something now and then: the time of day, the battery, new messages, tips.
 * Tap it (pinch) and it reacts; a long pinch moves it, like the other world objects.
 */
class HyperBot(world: AppWorld) : WorldObject(world, KEY) {
    override val title = "HyperBot"
    override val hitW = 0.22f
    override val hitH = 0.26f
    override val hitCenterY = 0.12f

    private enum class Mood { IDLE, WANDER, WAVE, HAPPY, SURPRISED, SLEEP, DANCE, SPIN, TALK }

    private var t = 0f
    private var mood = Mood.IDLE
    private var moodT = 0f
    private var nextChoice = 3f

    /** Where it is (bottom of the hover ring, world), where it goes, and the surface under it. */
    private val pos = Vec3()
    private val target = Vec3()
    private var placed = false
    private var surfaceY = Float.NaN
    private var surfaceCheck = 0f
    private var yaw = 0f
    private var yawVel = 0f
    private var lean = 0f
    private var headYaw = 0f
    private var headPitch = 0f
    private var lookAround = 0f
    private var blink = 0f
    private var nextBlink = 2f
    private var hop = 0f
    private var hopV = 0f
    private var spin = 0f

    // speech
    private var said = ""
    private var saidT = 0f
    private var nextLine = 6f
    private var greeted = false
    private var lastPat = -100f
    private var lastWave = -100f
    private var lastBatteryLine = -1000f
    private var lastUnread = -1
    private var lookingAt = 0f
    private var lonely = 0f
    private val tipPrev = arrayOf(Vec3(), Vec3())
    private val tipValid = BooleanArray(2)

    private var beep = 0
    private var beepLow = 0

    override fun update(dt: Float) {
        if (dt <= 0f) return
        t += dt; moodT += dt; saidT -= dt
        val input = world.input
        val head = input.head.position
        if (!placed || (world.moving === this)) { pos.set(base); target.set(base); placed = true }

        // the table under it (ARCore planes), checked twice a second
        surfaceCheck -= dt
        if (surfaceCheck <= 0f) {
            surfaceCheck = 0.5f
            world.arCore?.let { ar ->
                ar.wantPlanes(KEY, true)
                val y = ar.surfaceBelow(base.x, base.y + 0.15f, base.z, 0.9f)
                if (!y.isNaN()) surfaceY = y
            }
        }
        val ground = if (surfaceY.isNaN()) base.y else surfaceY

        // the user: where they are and whether they look at it
        val dx = head.x - pos.x; val dz = head.z - pos.z
        val toUser = atan2(-dx, -dz) + PI.toFloat()   // facing the user: its +z towards them
        val f = input.head.orientation.rotate(FORWARD, fwd)
        val to = toBot.set(pos.x - head.x, pos.y + 0.13f - head.y, pos.z - head.z)
        val dist = to.length()
        val looked = dist > 0.05f && (f.x * to.x + f.y * to.y + f.z * to.z) / dist > 0.985f
        lookingAt = if (looked) lookingAt + dt else 0f
        lonely += dt

        hands(dt)
        react(dt, looked)
        move(dt, ground, toUser)
        animate(dt, head)
        talk(dt)
    }

    // ------------------------------------------------------------------ behaviour
    private fun react(dt: Float, looked: Boolean) {
        if (looked) lonely = 0f
        val music = MediaHub.now?.playing == true
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        when (mood) {
            Mood.SLEEP -> if (looked && lookingAt > 1.2f || lonely < 1f && moodT > 2f) { setMood(Mood.SURPRISED); say(pick(WAKE)) }
            Mood.IDLE, Mood.WANDER -> {
                when {
                    music -> setMood(Mood.DANCE)
                    lonely > 600f || (hour >= 23 || hour < 6) && lonely > 90f -> { setMood(Mood.SLEEP); say("Zzz…") }
                    lookingAt > 1.5f && t - lastWave > 45f -> { lastWave = t; setMood(Mood.WAVE); say(if (!greeted) greeting() else pick(HELLO)); greeted = true }
                    else -> {
                        nextChoice -= dt
                        if (nextChoice <= 0f) {
                            nextChoice = 3f + Math.random().toFloat() * 6f
                            if (Math.random() < 0.45) { pickTarget(); setMood(Mood.WANDER) } else { setMood(Mood.IDLE); lookAround = (Math.random().toFloat() - 0.5f) * 1.6f }
                        }
                    }
                }
            }
            Mood.DANCE -> if (!music && moodT > 1f) setMood(Mood.IDLE)
            Mood.WAVE -> if (moodT > 2.4f) setMood(Mood.IDLE)
            Mood.HAPPY -> if (moodT > 2.2f) setMood(Mood.IDLE)
            Mood.SURPRISED -> if (moodT > 1.4f) setMood(Mood.IDLE)
            Mood.SPIN -> if (moodT > 1.2f) setMood(Mood.IDLE)
            Mood.TALK -> if (saidT <= 0f) setMood(Mood.IDLE)
        }
    }

    private fun setMood(m: Mood) { if (m != mood) { mood = m; moodT = 0f }; if (m == Mood.SURPRISED || m == Mood.HAPPY) hop(if (m == Mood.SURPRISED) 0.9f else 0.6f) }

    private fun hop(v: Float) { if (hop < 0.002f) hopV = v }

    /** A spot on the table near home: only where the table goes on (it never steps off the edge). */
    private fun pickTarget() {
        for (i in 0 until 6) {
            val a = Math.random() * 2 * PI
            val r = 0.05f + Math.random().toFloat() * 0.15f
            val x = base.x + (cos(a) * r).toFloat(); val z = base.z + (sin(a) * r).toFloat()
            val ar = world.arCore
            if (ar != null && !surfaceY.isNaN()) {
                val y = ar.surfaceBelow(x, surfaceY + 0.1f, z, 0.2f)
                if (y.isNaN() || abs(y - surfaceY) > 0.03f) continue
            }
            target.set(x, base.y, z); return
        }
        target.set(base)
    }

    /** Fingers: a pat on the head, a poke on the belly, a hand rushing past. */
    private fun hands(dt: Float) {
        val hands = world.input.hands
        for (i in 0..1) {
            val h = hands[i]
            if (!h.isUsable) { tipValid[i] = false; continue }
            val tip = h.joints[HandJoint.INDEX_TIP]
            val speed = if (tipValid[i]) tip.distanceTo(tipPrev[i]) / dt else 0f
            tipPrev[i].set(tip); tipValid[i] = true
            headC.set(pos.x, pos.y + hover() + 0.15f, pos.z)
            bodyC.set(pos.x, pos.y + hover() + 0.06f, pos.z)
            when {
                tip.distanceTo(headC) < 0.075f && t - lastPat > 2.5f -> {
                    lastPat = t; lonely = 0f
                    if (mood == Mood.SLEEP) { setMood(Mood.SURPRISED); say(pick(WAKE)) } else { setMood(Mood.HAPPY); say(pick(PAT)) }
                }
                tip.distanceTo(bodyC) < 0.07f && t - lastPat > 2.5f -> { lastPat = t; lonely = 0f; setMood(Mood.HAPPY); say(pick(POKE)) }
                speed > 1.4f && tip.distanceTo(bodyC) < 0.2f && mood != Mood.SURPRISED -> { lonely = 0f; setMood(Mood.SURPRISED); say(pick(STARTLED)) }
            }
        }
    }

    override fun onTap(u: Float, v: Float) {
        lonely = 0f
        if (mood == Mood.SLEEP) { setMood(Mood.SURPRISED); say(pick(WAKE)); return }
        when ((Math.random() * 3).toInt()) {
            0 -> { setMood(Mood.SPIN); say(pick(TAP)) }
            1 -> { setMood(Mood.HAPPY); say(pick(JOKES)) }
            else -> { setMood(Mood.WAVE); say(info()) }
        }
    }

    // ------------------------------------------------------------------ motion
    private fun hover(): Float = when (mood) {
        Mood.SLEEP -> 0.018f + 0.002f * sin(t * 1.2f)
        Mood.DANCE -> 0.035f + 0.012f * abs(sin(t * 4.2f))
        else -> 0.032f + 0.006f * sin(t * 2.1f)
    } + hop

    private fun move(dt: Float, ground: Float, toUser: Float) {
        // glide to the target, leaning into the motion
        val mx = target.x - pos.x; val mz = target.z - pos.z
        val d = sqrt(mx * mx + mz * mz)
        var want = toUser
        if (mood == Mood.WANDER && d > 0.01f) {
            val step = min(d, 0.11f * dt)
            pos.x += mx / d * step; pos.z += mz / d * step
            want = atan2(mx, mz)
            lean = lean + (0.18f - lean) * min(1f, dt * 4f)
        } else {
            if (mood == Mood.WANDER) setMood(Mood.IDLE)
            lean += (0f - lean) * min(1f, dt * 4f)
        }
        // the surface (or where it was put) under it, eased
        pos.y += (ground - pos.y) * min(1f, dt * 6f)
        // turn smoothly (a damped spring), a full spin when tapped
        if (mood == Mood.SPIN) spin = (moodT / 1.2f).coerceAtMost(1f) * 2 * PI.toFloat() else spin = 0f
        var e = want - yaw
        while (e > PI) e -= 2 * PI.toFloat(); while (e < -PI) e += 2 * PI.toFloat()
        yawVel += (e * 28f - yawVel * 9f) * dt
        yaw += yawVel * dt
        // hops
        hopV -= 9.8f * dt * 0.35f
        hop = max(0f, hop + hopV * dt * 0.35f)
        if (hop == 0f && hopV < 0f) hopV = 0f
    }

    private fun animate(dt: Float, head: Vec3) {
        // blink every few seconds (eyes close for a tenth of a second)
        nextBlink -= dt
        if (nextBlink <= 0f) { blink = 0.12f; nextBlink = 2.5f + Math.random().toFloat() * 4f }
        blink = max(0f, blink - dt)
        // the head follows you, within what a neck allows, with a glance around now and then
        val hc = headC.set(pos.x, pos.y + hover() + 0.15f, pos.z)
        val lx = head.x - hc.x; val ly = head.y - hc.y; val lz = head.z - hc.z
        var hy = atan2(lx, lz) - yaw - spin
        while (hy > PI) hy -= 2 * PI.toFloat(); while (hy < -PI) hy += 2 * PI.toFloat()
        val hp = atan2(ly, sqrt(lx * lx + lz * lz))
        val wantYaw = if (mood == Mood.SLEEP) 0f else (hy + lookAround * (if (mood == Mood.IDLE) 1f else 0f)).coerceIn(-1.1f, 1.1f)
        val wantPitch = if (mood == Mood.SLEEP) -0.35f else hp.coerceIn(-0.5f, 0.6f)
        headYaw += (wantYaw - headYaw) * min(1f, dt * 5f)
        headPitch += (wantPitch - headPitch) * min(1f, dt * 5f)
        lookAround *= (1f - min(1f, dt * 0.4f))
    }

    // ------------------------------------------------------------------ speech
    private fun talk(dt: Float) {
        // what changed: new messages, a low battery
        val unread = com.hyperxr.social.Social.unreadTotal
        if (lastUnread in 0 until unread && mood != Mood.SLEEP) say(if (unread - lastUnread == 1) "Chegou uma mensagem nova pra você!" else "Chegaram ${unread - lastUnread} mensagens novas!")
        lastUnread = unread
        val bat = batteryLevel()
        if (bat in 1..15 && t - lastBatteryLine > 600f && mood != Mood.SLEEP) { lastBatteryLine = t; say("Bateria em $bat%… acho que nós dois precisamos de uma tomada.") }
        if (!greeted && t > 2.5f) { greeted = true; say(greeting()); setMood(Mood.WAVE) }
        nextLine -= dt
        if (nextLine <= 0f) {
            nextLine = 45f + Math.random().toFloat() * 60f
            if (mood != Mood.SLEEP && saidT <= 0f) say(if (Math.random() < 0.5) pick(TIPS) else pick(IDLE_LINES))
        }
    }

    /** Says [text] in its bubble (the assistant's answers come here too): longer lines stay longer. */
    fun speak(text: String) {
        lonely = 0f
        if (mood == Mood.SLEEP) setMood(Mood.IDLE)
        say(if (text.length > 140) text.take(137) + "…" else text)
        saidT = (2.5f + said.length * 0.06f).coerceIn(4.5f, 10f)
    }

    private fun say(s: String) {
        said = com.hyperxr.i18n.I18n.t(s); saidT = 4.5f
        if (mood == Mood.IDLE) setMood(Mood.TALK)
        // a few chirps, like syllables
        val n = (said.length / 9).coerceIn(2, 6)
        for (i in 0 until n) world.later(i * 0.09f) { chirp(i) }
    }

    private fun chirp(i: Int) {
        val a = world.audio
        if (beep == 0) { beep = a.synth("sine", 1250f, 1650f, 0.06f, 0.22f, 0.003f, 0.03f); beepLow = a.synth("sine", 820f, 1050f, 0.07f, 0.2f, 0.003f, 0.035f) }
        val id = if (i % 2 == 0) beep else beepLow
        a.play(id, 0.45f, 0.9f + Math.random().toFloat() * 0.3f, false, Vec3(pos.x, pos.y + 0.15f, pos.z), world.input.head)
    }

    private fun greeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..11 -> "Bom dia! Eu sou o HyperBot."
            in 12..17 -> "Boa tarde! Eu sou o HyperBot."
            else -> "Boa noite! Eu sou o HyperBot."
        }
    }

    private fun info(): String {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val bat = batteryLevel()
        return if (bat > 0) "São $time e a bateria está em $bat%." else "São $time."
    }

    private fun batteryLevel(): Int = runCatching {
        val bm = world.activity().getSystemService(android.os.BatteryManager::class.java)
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    private fun pick(list: List<String>) = list[(Math.random() * list.size).toInt().coerceIn(0, list.size - 1)]

    // ------------------------------------------------------------------ drawing
    private var rounded: GpuMesh? = null
    private var ring: GpuMesh? = null
    private val q = Quat(); private val q2 = Quat(); private val body = Quat(); private val headQ = Quat()
    private val p = Vec3(); private val tmp = Vec3()

    override fun draw() {
        if (!placed) return
        // the pointer finds it where it is, not where it was put (it walks about)
        hit.pose.position.set(pos.x, pos.y + hitCenterY, pos.z)
        val r = rounded ?: com.hyperxr.questgames.GameMeshes.roundedBox(0.3f).also { rounded = it }
        val torus = ring ?: com.hyperxr.questgames.GameMeshes.torus(0.18f).also { ring = it }
        val sphere = world.renderer.sphere
        val cyl = world.renderer.cylinder
        val h = hover()
        val dance = if (mood == Mood.DANCE) sin(t * 8.4f) else 0f
        // the body's orientation: its turn, the lean into motion, a sway when dancing
        Quat.fromAxisAngle(0f, 1f, 0f, yaw + spin, body)
        body.mul(Quat.fromAxisAngle(1f, 0f, 0f, lean, q2), body)
        body.mul(Quat.fromAxisAngle(0f, 0f, 1f, dance * 0.18f, q2), body)

        // shadow on the table: smaller and lighter the higher it floats
        val sh = (1f - h * 6f).coerceIn(0.35f, 1f)
        world.draw(sphere, p.set(pos.x, pos.y + 0.001f, pos.z), IDENTITY, 0.11f * sh, 0.002f, 0.11f * sh, 0, Color.argb((90 * sh).toInt(), 0, 0, 0), unlit = true)
        // hover ring and its glow
        val glow = 0.75f + 0.25f * sin(t * 6f)
        world.draw(torus, local(0f, h + 0.004f, 0f), body, 0.07f, 0.07f, 0.07f, 0, BLUE_DARK, 0.6f, 0.5f)
        world.draw(sphere, local(0f, h - 0.002f, 0f), body, 0.05f, 0.006f, 0.05f, 0, withAlpha(CYAN, (150 * glow).toInt()), unlit = true)
        world.draw(sphere, local(0f, h * 0.5f, 0f), body, 0.035f, h * 0.9f + 0.004f, 0.035f, 0, withAlpha(CYAN, (40 * glow).toInt()), unlit = true)

        // body: a round blue egg with a white belly and a chest light
        world.draw(sphere, local(0f, h + 0.058f, 0f), body, 0.105f, 0.11f, 0.095f, 0, BLUE, 0.85f, 0.35f)
        world.draw(sphere, local(0f, h + 0.052f, 0.03f), body, 0.07f, 0.072f, 0.04f, 0, WHITE, 0.7f)
        val chest = if (mood == Mood.SLEEP) 0.35f else 0.7f + 0.3f * sin(t * 3f)
        world.draw(sphere, local(0f, h + 0.07f, 0.049f), body, 0.018f, 0.018f, 0.006f, 0, withAlpha(eyeColor(), (255 * chest).toInt()), unlit = true)
        // arms
        arm(-1f, h); arm(1f, h)
        // neck
        world.draw(cyl, local(0f, h + 0.112f, 0f), body, 0.03f, 0.02f, 0.03f, 0, DARK, 0.5f, 0.6f)

        // head: turned towards you, a glossy rounded box with a dark visor and glowing eyes
        headQ.set(body)
        headQ.mul(Quat.fromAxisAngle(0f, 1f, 0f, headYaw + dance * 0.25f, q2), headQ)
        headQ.mul(Quat.fromAxisAngle(1f, 0f, 0f, -headPitch, q2), headQ)
        val hc = local(0f, h + 0.163f, 0f).copy()
        world.draw(r, hc, headQ, 0.12f, 0.092f, 0.098f, 0, BLUE, 0.9f, 0.35f)
        world.draw(r, inHead(hc, 0f, -0.002f, 0.042f), headQ, 0.098f, 0.066f, 0.022f, 0, VISOR, 1f, 0.2f)
        eyes(hc)
        // ear pods with a light ring
        for (s in SIDES) {
            Quat.fromAxisAngle(0f, 0f, 1f, PI.toFloat() / 2, q).let { headQ.mul(it, q) }
            world.draw(cyl, inHead(hc, s * 0.063f, 0f, 0f), q, 0.036f, 0.014f, 0.036f, 0, WHITE, 0.7f)
            world.draw(cyl, inHead(hc, s * 0.071f, 0f, 0f), q, 0.022f, 0.004f, 0.022f, 0, withAlpha(eyeColor(), 230), unlit = true)
        }
        // antenna: a thin stalk and a glowing tip that pulses (fast when it talks)
        world.draw(cyl, inHead(hc, 0f, 0.058f, 0f), headQ, 0.005f, 0.028f, 0.005f, 0, DARK, 0.6f, 0.7f)
        val tipPulse = if (saidT > 0f) 0.6f + 0.4f * abs(sin(t * 14f)) else 0.55f + 0.45f * sin(t * 2.5f)
        world.draw(sphere, inHead(hc, 0f, 0.076f, 0f), headQ, 0.016f, 0.016f, 0.016f, 0, withAlpha(eyeColor(), (255 * tipPulse).toInt().coerceIn(80, 255)), unlit = true)

        // what it says, over its head; Zzz when asleep
        // (a long line wraps: the label grows with its lines so the letters keep their size)
        if (saidT > 0f && said.isNotEmpty()) world.label(said, Vec3(pos.x, pos.y + h + 0.27f, pos.z), 0.026f * (1 + said.length / 34))
        else if (mood == Mood.SLEEP) world.label("z" + "z".repeat(((t * 1.5f).toInt() % 3)), Vec3(pos.x + 0.04f, pos.y + h + 0.25f + 0.01f * sin(t), pos.z), 0.022f, Color.rgb(170, 200, 255))
    }

    /** An arm from the shoulder: a short limb and a white hand; it waves, swings or dances. */
    private fun arm(side: Float, h: Float) {
        val a = when (mood) {
            Mood.WAVE -> if (side > 0f) 2.4f + 0.45f * sin(t * 14f) else 0.25f
            Mood.DANCE -> 1.3f + 0.8f * sin(t * 8.4f + side * 1.6f)
            Mood.HAPPY -> 1.6f + 0.4f * sin(t * 18f)
            Mood.SURPRISED -> 1.9f
            Mood.SLEEP -> 0.1f
            Mood.TALK -> 0.45f + 0.25f * sin(t * 6f + side)
            else -> 0.3f + 0.08f * sin(t * 2.1f + side)
        }
        val sx = side * 0.056f; val sy = h + 0.075f
        // the arm swings out sideways by [a] radians from hanging down
        val dirX = side * sin(a); val dirY = -cos(a)
        val len = 0.05f
        val shoulder = local(sx, sy, 0.004f).copy()
        val hand = local(sx + dirX * len, sy + dirY * len, 0.012f).copy()
        beam(shoulder, hand, 0.014f, BLUE_DARK)
        world.draw(world.renderer.sphere, shoulder, body, 0.024f, 0.024f, 0.024f, 0, WHITE, 0.7f)
        world.draw(world.renderer.sphere, hand, body, 0.022f, 0.022f, 0.022f, 0, WHITE, 0.7f)
    }

    /** The eyes on the visor: their shape is the mood (round, happy arcs, wide, closed, hearts). */
    private fun eyes(hc: Vec3) {
        val closed = blink > 0f
        for (s in SIDES) {
            val (w, hgt, dy) = when {
                mood == Mood.SLEEP -> Triple(0.02f, 0.003f, -0.004f)
                closed -> Triple(0.018f, 0.003f, 0f)
                mood == Mood.HAPPY || mood == Mood.DANCE -> Triple(0.02f, 0.008f, 0.004f)
                mood == Mood.SURPRISED -> Triple(0.022f, 0.028f, 0.002f)
                else -> Triple(0.016f, 0.022f, 0f)
            }
            val e = inHead(hc, s * 0.022f, 0.004f + dy, 0.054f)
            world.draw(world.renderer.sphere, e, headQ, w, hgt, 0.006f, 0, eyeColor(), unlit = true)
            // a soft glow around each eye
            world.draw(world.renderer.sphere, inHead(hc, s * 0.022f, 0.004f + dy, 0.0535f), headQ, w * 1.7f, max(hgt, 0.006f) * 1.6f, 0.002f, 0, withAlpha(eyeColor(), 70), unlit = true)
            // a little white glint in open round eyes
            if (hgt > 0.015f) world.draw(world.renderer.sphere, inHead(hc, s * 0.022f - 0.004f, 0.009f + dy, 0.0575f), headQ, 0.005f, 0.005f, 0.002f, 0, Color.WHITE, unlit = true)
        }
        // blush when happy
        if (mood == Mood.HAPPY) for (s in SIDES)
            world.draw(world.renderer.sphere, inHead(hc, s * 0.036f, -0.014f, 0.053f), headQ, 0.012f, 0.006f, 0.002f, 0, Color.argb(160, 255, 110, 160), unlit = true)
    }

    private fun eyeColor(): Int = when (mood) {
        Mood.HAPPY -> Color.rgb(140, 255, 200)
        Mood.SURPRISED -> Color.rgb(255, 230, 120)
        Mood.DANCE -> if (sin(t * 4.2f) > 0f) Color.rgb(255, 120, 220) else CYAN
        Mood.SLEEP -> Color.rgb(120, 150, 220)
        else -> CYAN
    }

    /** A point in the body's frame (x right, y up, z forward), in the world. */
    private fun local(x: Float, y: Float, z: Float): Vec3 = body.rotate(tmp.set(x, y, z), p).let { it.set(pos.x + it.x, pos.y + it.y, pos.z + it.z) }

    /** A point in the head's frame around its centre [hc]. */
    private fun inHead(hc: Vec3, x: Float, y: Float, z: Float): Vec3 = headQ.rotate(tmpH.set(x, y, z), outH).add(hc)
    // scratch vectors: everything drawn is used at once, so nothing is allocated per frame
    private val tmpH = Vec3(); private val outH = Vec3()
    private val headC = Vec3(); private val bodyC = Vec3()
    private val fwd = Vec3(); private val toBot = Vec3()

    private fun beam(a: Vec3, b: Vec3, r: Float, color: Int) {
        val d = Vec3(b.x - a.x, b.y - a.y, b.z - a.z)
        val len = d.length()
        if (len < 1e-4f) return
        Quat.fromTo(Vec3(0f, 1f, 0f), d.scale(1f / len), q)
        world.draw(world.renderer.cylinder, Vec3((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2), q, r, len, r, 0, color, 0.6f, 0.3f)
    }

    override fun release() {
        world.arCore?.wantPlanes(KEY, false)
        rounded?.release(); rounded = null
        ring?.release(); ring = null
    }

    companion object {
        const val KEY = "hyperbot"
        private val IDENTITY = Quat()
        private val SIDES = floatArrayOf(-1f, 1f)
        private val FORWARD = Vec3(0f, 0f, -1f)
        private val BLUE = Color.rgb(38, 110, 246)
        private val BLUE_DARK = Color.rgb(24, 62, 160)
        private val WHITE = Color.rgb(240, 244, 250)
        private val DARK = Color.rgb(36, 40, 52)
        private val VISOR = Color.rgb(10, 14, 28)
        private val CYAN = Color.rgb(90, 240, 255)
        private fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

        private val HELLO = listOf("Oi! Tô aqui!", "Ei, você! Oi!", "Olhando pra mim? Oi!", "Bip bop! Oi!")
        private val WAKE = listOf("Hã? Eu não tava dormindo!", "Bom dia… digo, oi!", "Ahh, que susto! Oi!")
        private val PAT = listOf("Hehe, cócegas!", "Mais carinho, por favor!", "Isso é bom demais!", "Bip! Gostei!")
        private val POKE = listOf("Ei, isso é minha barriga!", "Hihi!", "Cutucou o robô!")
        private val STARTLED = listOf("Opa! Calma aí!", "Uau, que mão rápida!", "Quase me pegou!")
        private val TAP = listOf("Wiii!", "Rodopio!", "Bip bop bup!")
        private val JOKES = listOf(
            "Por que o robô atravessou a rua? Pra dar um reboot do outro lado!",
            "Eu não tenho pernas, mas corro muito… em hertz.",
            "Meu prato favorito? Batata… de bytes.",
            "Fui ao médico: ele disse que eu tenho um vírus. Era só atualização.",
        )
        private val TIPS = listOf(
            "Dica: arminha pra cima, fecha o indicador e abre onde quiser o menu.",
            "Dica: segure a pinça num app da Biblioteca pra fixar.",
            "Dica: na Loja tem jogo novo toda hora.",
            "Dica: dá pra jogar com Joy-Con, gamepad ou outro celular.",
            "Dica: diga \"modo meta\" pra trocar a interface.",
            "Dica: eu fico em cima da sua mesa. Segure a pinça em mim pra me mudar de lugar.",
        )
        private val IDLE_LINES = listOf(
            "Que lugar legal você tem aqui.",
            "Bip… bop… só pensando em robô.",
            "Se precisar de mim, é só me cutucar!",
            "Será que existe um robô azul mais fofo que eu? Não.",
            "Lembrete: beber água faz bem. Pra humanos.",
            "Tô de olho em tudo. Com carinho.",
        )
    }
}
