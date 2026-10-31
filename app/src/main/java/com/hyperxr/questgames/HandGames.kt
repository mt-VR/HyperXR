package com.hyperxr.questgames

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.hyperxr.math.Quat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Streams synthesised sound and says how much of it has been heard (for syncing to music). */
class SynthStream(private val render: (FloatArray, Int) -> Unit) {
    private var track: AudioTrack? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(min, 512 * 2 * 4 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        }.getOrNull() ?: return
        track = t; running = true; t.play()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = FloatArray(512 * 2)
            while (running) { render(buf, 512); t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING) }
        }, "hx-game-audio").also { it.start() }
    }

    /** Seconds of sound that have actually left the speaker. */
    val heardSeconds: Float get() = track?.let { it.playbackHeadPosition.toFloat() / RATE } ?: 0f
    val started get() = running

    fun stop() {
        running = false
        thread?.join(500); thread = null
        track?.let { runCatching { it.stop() }; it.release() }; track = null
    }

    companion object { const val RATE = 48000 }
}

private val RED = floatArrayOf(0.95f, 0.12f, 0.16f, 1f)
private val BLUE = floatArrayOf(0.1f, 0.45f, 1f, 1f)
private val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
private fun rgba(r: Float, g: Float, b: Float, a: Float = 1f) = floatArrayOf(r, g, b, a)
private fun fade(c: FloatArray, a: Float, out: FloatArray = FloatArray(4)): FloatArray { out[0] = c[0]; out[1] = c[1]; out[2] = c[2]; out[3] = c[3] * a; return out }

/** A glowing blade from the hand (sabre or katana), with the trail its tip leaves. */
private fun pushTrail(g: GameHand, tip: com.hyperxr.math.Vec3) {
    for (i in minOf(g.trailCount, g.trail.size - 1) downTo 1) g.trail[i].set(g.trail[i - 1])
    g.trail[0].set(tip)
    g.trailCount = minOf(g.trailCount + 1, g.trail.size)
}

private fun drawTrail(host: GameHost, g: GameHand, color: FloatArray, width: Float, strength: Float) {
    val c = FloatArray(4)
    for (i in 0 until g.trailCount - 1) {
        val a = g.trail[i]; val b = g.trail[i + 1]
        if (a.distanceTo(b) < 0.004f) continue
        val f = 1f - i / (g.trail.size - 1f)
        c[0] = color[0]; c[1] = color[1]; c[2] = color[2]; c[3] = 0.55f * f * f * strength
        host.glowBeam(a.x, a.y, a.z, b.x, b.y, b.z, width * (0.4f + 0.6f * f), c, core = false)
    }
}

// ================================================================================ 1. Sabre de Ritmo
/**
 * Blocks fly at you to the beat down a neon runway: cut each with the sabre of its colour (left
 * red, right blue) in the arrow's direction. The tunnel and the lasers pulse with the music.
 */
class RhythmGame(host: GameHost) : QuestGame(host) {
    override val title = "Sabre de Ritmo"
    override val look = GameSky.Look(
        zenith = rgba(0f, 0f, 0.015f), horizon = rgba(0.035f, 0.01f, 0.07f), ground = rgba(0f, 0f, 0.01f),
        sunDir = floatArrayOf(0.2f, 0.9f, 0.4f), stars = 0.35f, nebula = floatArrayOf(0.22f, 0.04f, 0.4f, 0.55f),
        floor = GameSky.FLOOR_GRID, floorY = -4f, floorColor = rgba(0f, 0f, 0.01f), grid = floatArrayOf(0.15f, 0.25f, 0.9f, 3f),
        fog = 0.035f, objectFog = 0.03f, light = rgba(0.75f, 0.75f, 0.9f), ambient = rgba(0.18f, 0.16f, 0.26f),
    )
    override val shadowRadius = 0f
    private val length = 95f
    private val bpm = 128f
    private val music = BeatMusic(bpm)
    private val stream = SynthStream { b, n -> music.render(b, n) }
    private val notes = BeatChart.generate(bpm, length, seed = 7)
    private var combo = 0; private var hits = 0; private var misses = 0
    /** The two halves of a cut block. */
    private class Half(var x: Float, var y: Float, var z: Float, val vx: Float, var vy: Float, val vz: Float, val c: FloatArray, val sx: Float, val sy: Float, val spin: Float, var t: Float)
    private val halves = ArrayList<Half>()
    private var flash = FloatArray(2)
    private var missFlash = 0f
    private val a = com.hyperxr.math.Vec3(); private val b = com.hyperxr.math.Vec3()
    private val tip = com.hyperxr.math.Vec3()

    override fun start() { stream.start(); status = "Feche a mão como se segurasse o sabre. Corte os blocos com o sabre da cor certa, na direção da seta" }

    private fun zOf(n: Note, song: Float) = HIT_Z - (n.time - song) * SPEED

    override fun update(dt: Float) {
        val song = stream.heardSeconds
        for (h in 0..1) { val g = host.hands[h]; if (g.usable) pushTrail(g, g.along(BLADE, tip)) else g.trailCount = 0 }
        for (n in notes) {
            if (n.done) continue
            val z = zOf(n, song)
            if (z < -20f) break
            if (z > 0.4f) { n.done = true; misses++; combo = 0; missFlash = 0.35f; host.play(host.sMiss, 0f, 1f, 0f, 0.5f); continue }
            if (z < HIT_Z - 1.4f) continue
            val x = BeatChart.laneX(n.lane); val y = BeatChart.rowY(n.row)
            for (h in 0..1) {
                val g = host.hands[h]
                if (!g.usable) continue
                // the blade's sweep: points along it, last frame to this one
                var q = 0
                for (d in SAMPLES) {
                    g.prevAlong(d, a); g.along(d, b)
                    q = SliceJudge.judge(a.x, a.y, a.z, b.x, b.y, b.z, dt, x, y, z, HALF, n.dir)
                    if (q != 0) break
                }
                if (q == 0) continue
                n.done = true
                if (h != n.hand) { combo = 0; misses++; missFlash = 0.35f; host.play(host.sMiss, x, y, z, 0.6f); break }
                n.hit = true; hits++; combo++
                score += q * multiplier()
                flash[h] = 1f
                host.play(host.sHit, x, y, z, 0.7f, 1f + min(combo, 16) * 0.02f)
                val c = if (n.hand == 0) RED else BLUE
                // split across the cut: a vertical cut opens left and right, a horizontal one up and down
                val vertical = n.dir == Note.UP || n.dir == Note.DOWN || n.dir == Note.ANY
                val ox = if (vertical) 1f else 0f; val oy = if (vertical) 0f else 1f
                val sx = if (vertical) HALF else HALF * 2; val sy = if (vertical) HALF * 2 else HALF
                halves += Half(x - ox * 0.06f, y - oy * 0.06f, z, -ox * 1.4f, -oy * 1.4f + 0.6f, SPEED * 0.25f, c, sx, sy, -4f, 0f)
                halves += Half(x + ox * 0.06f, y + oy * 0.06f, z, ox * 1.4f, oy * 1.4f + 0.6f, SPEED * 0.25f, c, sx, sy, 4f, 0f)
                host.particles.burst(Particles.SPARK, x, y, z, 26, 3.2f, c, 0.45f, 0.006f, gravity = 3f)
                host.particles.burst(Particles.SPARK, x, y, z, 8, 2f, WHITE, 0.25f, 0.005f, gravity = 1f)
                break
            }
        }
        halves.forEach { it.t += dt; it.x += it.vx * dt; it.vy -= 6f * dt; it.y += it.vy * dt; it.z += it.vz * dt }
        halves.removeAll { it.t > 0.8f }
        for (h in 0..1) flash[h] = max(0f, flash[h] - dt * 3f)
        missFlash = max(0f, missFlash - dt)
        status = "Combo $combo  ·  ${multiplier()}x  ·  acertos $hits  ·  erros $misses"
        if (song > length) {
            over = true
            val acc = if (hits + misses == 0) 0 else hits * 100 / (hits + misses)
            status = "Fim da música! Precisão $acc%" + when { acc >= 90 -> "  ·  SS"; acc >= 80 -> "  ·  S"; acc >= 65 -> "  ·  A"; acc >= 50 -> "  ·  B"; else -> "  ·  C" }
            host.play(host.sWin, 0f, 1.5f, -1f)
            stream.stop()
        }
    }

    private fun multiplier() = when { combo >= 14 -> 8; combo >= 6 -> 4; combo >= 2 -> 2; else -> 1 }

    private val tmpC = FloatArray(4)
    private val noteRed = rgba(0.7f, 0.04f, 0.07f)
    private val noteBlue = rgba(0.04f, 0.22f, 0.75f)
    private val railDark = rgba(0.03f, 0.03f, 0.05f)

    override fun draw() {
        val song = stream.heardSeconds
        val beat = song * bpm / 60f
        val pulse = kotlin.math.exp(-(beat - kotlin.math.floor(beat)) * 4f)
        val energy = 0.45f + 0.35f * pulse + 0.4f * max(flash[0], flash[1])
        host.sky.pulse = pulse * 0.5f
        // the platform you stand on: dark glass with lit edges
        host.drawRounded(0f, -0.06f, 0f, 2f, 0.1f, 1.6f, rgba(0.05f, 0.05f, 0.07f), gloss = 0.95f)
        host.glowBeam(-1f, -0.005f, 0.8f, -1f, -0.005f, -0.8f, 0.006f, RED, 0.8f * energy)
        host.glowBeam(1f, -0.005f, 0.8f, 1f, -0.005f, -0.8f, 0.006f, BLUE, 0.8f * energy)
        host.glowBeam(-1f, -0.005f, -0.8f, 1f, -0.005f, -0.8f, 0.005f, WHITE, 0.5f * energy)
        // the runway into the distance, with neon rails
        host.drawBox(0f, -0.08f, -21f, 1.4f, 0.06f, 40f, railDark, gloss = 0.9f, shadow = false)
        host.glowBeam(-0.72f, -0.04f, -0.8f, -0.72f, -0.04f, -42f, 0.008f, RED, energy)
        host.glowBeam(0.72f, -0.04f, -0.8f, 0.72f, -0.04f, -42f, 0.008f, BLUE, energy)
        // the tunnel: rings of light that pulse from the far end towards you
        for (i in 0 until 8) {
            val z = -9f - i * 7f
            val wave = kotlin.math.exp(-(((beat * 0.5f + i * 0.12f) % 1f)) * 3f)
            val col = if (i % 2 == 0) RED else BLUE
            val s = 0.35f + 0.65f * wave
            val w = 3.2f + i * 0.4f; val h = 3.6f + i * 0.3f
            host.glowBeam(-w, -0.5f, z, -w, h, z, 0.02f, col, s, core = false)
            host.glowBeam(w, -0.5f, z, w, h, z, 0.02f, col, s, core = false)
            host.glowBeam(-w, h, z, w, h, z, 0.02f, col, s, core = false)
        }
        // big lasers sweeping from both sides
        for (k in 0..3) {
            val side = if (k % 2 == 0) -1f else 1f
            val ang = host.time * (0.6f + k * 0.15f) + k * 1.3f
            val ox = side * (7f + k); val oz = -30f - k * 4f
            val ex = ox - side * 18f * (0.5f + 0.5f * sin(ang)); val ey = 2f + 12f * (0.5f + 0.5f * cos(ang * 0.7f))
            host.glowBeam(ox, -1f, oz, ex, ey, oz + 6f, 0.03f, if (side < 0) RED else BLUE, 0.3f + 0.6f * pulse, core = false)
        }
        // the blocks
        for (n in notes) {
            if (n.done) continue
            val z = zOf(n, song)
            if (z < -20f) break
            if (z > 0.5f) continue
            val x = BeatChart.laneX(n.lane); val y = BeatChart.rowY(n.row)
            // they arrive spinning slightly and settle as they near you
            val settle = ((z - HIT_Z + 8f) / 8f).coerceIn(0f, 1f)
            val rot = com.hyperxr.math.Quat.fromAxisAngle(0f, 0f, 1f, settle * settle * 0.6f * (if (n.lane % 2 == 0) 1f else -1f))
            host.drawRounded(x, y, z, HALF * 2, HALF * 2, HALF * 2, if (n.hand == 0) noteRed else noteBlue, orientation = rot, gloss = 0.85f)
            val face = z + HALF + 0.004f
            if (n.dir == Note.ANY) host.drawEllipsoid(x, y, face, 0.08f, 0.08f, 0.01f, WHITE, unlit = true)
            else {
                val ang = when (n.dir) { Note.UP -> 0f; Note.DOWN -> Math.PI.toFloat(); Note.LEFT -> Math.PI.toFloat() / 2; else -> -Math.PI.toFloat() / 2 }
                val q = com.hyperxr.math.Quat.fromAxisAngle(0f, 0f, 1f, ang).let { rot.mul(it) }
                // pushed towards the edge the cut goes to, like the originals
                val ox = -sin(ang) * 0.05f; val oy = cos(ang) * 0.05f
                host.drawMesh(host.chevron, x + ox, y + oy, face, 0.17f, 0.17f, 1f, WHITE, orientation = q, unlit = true, shadow = false)
            }
            host.glow(x, y, z + HALF, 0.11f, if (n.hand == 0) RED else BLUE, 0.18f)
        }
        for (p in halves) {
            val f = 1f - p.t / 0.8f
            val c = fade(if (p.c === RED) noteRed else noteBlue, f, tmpC)
            host.drawRounded(p.x, p.y, p.z, p.sx * (0.6f + 0.4f * f), p.sy * (0.6f + 0.4f * f), HALF * 2 * (0.6f + 0.4f * f), c,
                orientation = com.hyperxr.math.Quat.fromAxisAngle(0.3f, 0.2f, 1f, p.spin * p.t), gloss = 0.8f)
        }
        // the sabres: a metal hilt in the fist, a blade of light
        for (h in 0..1) {
            val g = host.hands[h]
            if (!g.usable) continue
            val c = if (h == 0) RED else BLUE
            g.along(-0.09f, a); g.along(0.07f, b)
            host.drawBeam(a.x, a.y, a.z, b.x, b.y, b.z, 0.017f, rgba(0.25f, 0.26f, 0.3f), unlit = false, gloss = 0.9f, metal = 1f)
            g.along(0.075f, a)
            host.glow(a.x, a.y, a.z, 0.035f, c, 0.6f)
            g.along(BLADE, tip)
            host.glowBeam(a.x, a.y, a.z, tip.x, tip.y, tip.z, 0.011f, c, 0.85f + flash[h] * 0.6f)
            drawTrail(host, g, c, 0.012f, 1f)
        }
        if (missFlash > 0f) host.glow(host.head.x, host.head.y, host.head.z, 0.25f, RED, missFlash)
    }

    override fun release() = stream.stop()

    companion object {
        const val SPEED = 9f; const val HIT_Z = -0.55f; const val HALF = 0.12f; const val BLADE = 0.95f
        private val SAMPLES = floatArrayOf(0.95f, 0.7f, 0.45f, 0.2f)
    }
}

// ================================================================================ 2. Ninja das Frutas
/** A dojo at sunset. Fruit is thrown up in front of you: slash through it with your katanas. Avoid the bombs. One minute. */
class FruitGame(host: GameHost) : QuestGame(host) {
    override val title = "Ninja das Frutas"
    override val look = GameSky.Look(
        zenith = rgba(0.22f, 0.3f, 0.58f), horizon = rgba(1f, 0.66f, 0.42f), ground = rgba(0.3f, 0.24f, 0.2f),
        sunDir = floatArrayOf(-0.45f, 0.16f, -0.88f), sunColor = rgba(1f, 0.62f, 0.32f), sunSize = 0.03f,
        clouds = 0.5f, cloudColor = rgba(1f, 0.74f, 0.62f),
        floor = GameSky.FLOOR_PLAIN, floorY = -0.02f, floorColor = rgba(0.32f, 0.36f, 0.2f), fog = 0.02f, objectFog = 0.008f,
        light = rgba(1f, 0.82f, 0.64f), ambient = rgba(0.46f, 0.4f, 0.44f),
    )
    override val shadowRadius = 4.5f
    private val fruits = listOf("apple" to "apple-half", "orange" to null, "pear" to "pear-half", "lemon" to "lemon-half", "coconut" to "coconut-half",
        "watermelon" to null, "pineapple" to null, "banana" to null, "strawberry" to null, "tomato" to null, "avocado" to "advocado-half")
    private val rnd = java.util.Random()
    private val flying = ArrayList<Thrown>()
    private class Half(val model: String, var x: Float, var y: Float, var z: Float, val vx: Float, var vy: Float, val vz: Float, var spin: Float, var t: Float)
    private val halves = ArrayList<Half>()
    /** Juice splashed on the back wall, fading. */
    private class Splat(val x: Float, val y: Float, val r: Float, val c: FloatArray, val rot: Float, var t: Float)
    private val splats = ArrayList<Splat>()
    private var timeLeft = 60f
    private var next = 1f
    private var flash = 0f
    private var lastCut = -1f
    private var comboCount = 0
    private val a = com.hyperxr.math.Vec3(); private val b = com.hyperxr.math.Vec3(); private val tip = com.hyperxr.math.Vec3()

    override fun start() { status = "Suas mãos são katanas: corte as frutas no ar. Bombas tiram 10 pontos" }

    private fun cutBy(f: Thrown, g: GameHand, dt: Float): Boolean {
        if (FruitThrower.sliced(f, g.prevTip.x, g.prevTip.y, g.prevTip.z, g.tip.x, g.tip.y, g.tip.z, dt)) return true
        for (d in BLADE_SAMPLES) {
            g.prevAlong(d, a); g.along(d, b)
            if (FruitThrower.sliced(f, a.x, a.y, a.z, b.x, b.y, b.z, dt)) return true
        }
        return false
    }

    override fun update(dt: Float) {
        timeLeft -= dt
        next -= dt
        for (g in host.hands) if (g.usable) pushTrail(g, g.along(BLADE, tip)) else g.trailCount = 0
        if (next <= 0f && timeLeft > 1f) {
            // bursts: one to four at a time, more often as time runs out
            val count = 1 + rnd.nextInt(if (timeLeft < 30f) 4 else 2)
            repeat(count) { flying += FruitThrower.toss(rnd, fruits.size, 0.12f) }
            next = 0.7f + rnd.nextFloat() * 0.9f
        }
        for (f in flying) f.step(dt)
        for (f in flying) {
            if (f.cut) continue
            for (g in host.hands) {
                if (!g.usable || !cutBy(f, g, dt)) continue
                f.cut = true
                if (f.bomb) {
                    score = max(0, score - 10); flash = 0.6f; host.play(host.sBoom, f.x, f.y, f.z)
                    host.particles.burst(Particles.SPARK, f.x, f.y, f.z, 40, 6f, rgba(1f, 0.6f, 0.15f), 0.6f, 0.01f, gravity = 2f)
                    host.particles.burst(Particles.DEBRIS, f.x, f.y, f.z, 14, 3f, rgba(0.08f, 0.08f, 0.09f), 1.2f, 0.03f, gravity = 9f)
                } else {
                    // two in a quick swipe: a combo bonus
                    if (host.time - lastCut < 0.3f) { comboCount++; score += 1 + comboCount } else { comboCount = 0; score += 1 }
                    lastCut = host.time
                    host.play(host.sSplash, f.x, f.y, f.z, 0.8f, 0.9f + rnd.nextFloat() * 0.3f)
                    val (whole, half) = fruits[f.kind]
                    val m = half ?: whole
                    val sx = g.velocity.x * 0.15f
                    halves += Half(m, f.x - 0.03f, f.y, f.z, -0.9f + sx, 0.9f, -0.2f, f.spin, 0f)
                    halves += Half(m, f.x + 0.03f, f.y, f.z, 0.9f + sx, 0.9f, -0.2f, f.spin + 3.1f, 0f)
                    val col = JUICE[f.kind % JUICE.size]
                    host.particles.burst(Particles.BLOB, f.x, f.y, f.z, 18, 2.6f, col, 0.9f, 0.012f, gravity = 9f, bx = g.velocity.x * 0.3f, bz = -1.5f)
                    host.particles.burst(Particles.SPARK, f.x, f.y, f.z, 10, 3f, WHITE, 0.2f, 0.004f, gravity = 0f)
                    // the splash on the wall behind, where the fruit was in line with it
                    if (splats.size > 28) splats.removeAt(0)
                    splats += Splat(f.x * 1.6f, (f.y * 1.2f).coerceIn(0.5f, 3f), 0.18f + rnd.nextFloat() * 0.2f, col, rnd.nextFloat() * 6f, 0f)
                }
                break
            }
        }
        flying.removeAll { it.cut || it.y < -0.5f }
        halves.forEach { it.t += dt; it.vy -= 7f * dt; it.x += it.vx * dt; it.y += it.vy * dt; it.z += it.vz * dt; it.spin += dt * 5f }
        halves.removeAll { it.t > 1.4f }
        splats.forEach { it.t += dt }; splats.removeAll { it.t > 6f }
        if (flash > 0f) flash -= dt
        // a burning fuse on every bomb
        for (f in flying) if (f.bomb && rnd.nextFloat() < 0.7f)
            host.particles.emit(Particles.SPARK, f.x, f.y + f.radius + 0.03f, f.z, (rnd.nextFloat() - 0.5f) * 1.2f, rnd.nextFloat() * 1.2f, (rnd.nextFloat() - 0.5f) * 1.2f, FUSE, 0.25f, 0.004f, 2f)
        status = "Tempo: ${max(0, timeLeft.toInt())} s" + if (comboCount > 0 && host.time - lastCut < 1f) "  ·  combo x${comboCount + 1}!" else ""
        if (timeLeft <= 0f && flying.isEmpty()) { over = true; status = "Fim! $score pontos"; host.play(host.sWin, 0f, 1.5f, -1f) }
    }

    private val wood = rgba(1f, 1f, 1f)
    private val darkWood = rgba(0.32f, 0.2f, 0.13f)
    private val roof = rgba(0.16f, 0.14f, 0.15f)
    private val lantern = rgba(1f, 0.55f, 0.2f)
    private val tmpC = FloatArray(4)

    override fun draw() {
        // the dojo: polished floor, paper walls behind and at the sides between dark pillars, a roof with eaves
        host.drawBox(0f, -0.05f, -1f, 6.4f, 0.1f, 6.4f, wood, gloss = 0.55f, tex = "floor", tile = 1.6f)
        host.drawBox(0f, 1.6f, -4.2f, 6.4f, 3.2f, 0.08f, wood, gloss = 0.05f, tex = "paper", tile = 3.2f)
        for (side in floatArrayOf(-3.2f, 3.2f)) host.drawBox(side, 1.6f, -2.6f, 0.08f, 3.2f, 3.2f, wood, gloss = 0.05f, tex = "paper", tile = 3.2f)
        for (i in 0..4) host.drawBox(-3.2f + i * 1.6f, 1.65f, -4.12f, 0.16f, 3.3f, 0.16f, darkWood, gloss = 0.4f, tex = "wood-dark", tile = 1f)
        for (z in floatArrayOf(-1f, 0.6f)) for (side in floatArrayOf(-3.2f, 3.2f)) host.drawBox(side, 1.65f, z, 0.18f, 3.3f, 0.18f, darkWood, gloss = 0.4f, tex = "wood-dark", tile = 1f)
        host.drawBox(0f, 3.3f, -4.1f, 6.8f, 0.2f, 0.25f, darkWood, tex = "wood-dark", tile = 1f)
        for (side in floatArrayOf(-3.2f, 3.2f)) host.drawBox(side, 3.3f, -1.5f, 0.25f, 0.2f, 5.6f, darkWood, tex = "wood-dark", tile = 1f)
        for (i in 0..3) host.drawBox(0f, 3.45f, -3.6f + i * 1.3f, 6.6f, 0.12f, 0.14f, darkWood, tex = "wood-dark", tile = 1f)
        // the roof: two slopes and wide eaves (open above you, towards the sky behind)
        host.drawBox(-1.9f, 3.95f, -2.7f, 4.4f, 0.12f, 3.6f, roof, orientation = com.hyperxr.math.Quat.fromAxisAngle(0f, 0f, 1f, 0.42f), gloss = 0.3f)
        host.drawBox(1.9f, 3.95f, -2.7f, 4.4f, 0.12f, 3.6f, roof, orientation = com.hyperxr.math.Quat.fromAxisAngle(0f, 0f, 1f, -0.42f), gloss = 0.3f)
        // paper lanterns
        for (x in floatArrayOf(-2.3f, 2.3f)) {
            val sw = sin(host.time * 1.3f + x) * 0.03f
            host.drawBeam(x, 3.4f, -3.3f, x + sw, 2.75f, -3.3f, 0.005f, darkWood, unlit = false)
            host.drawEllipsoid(x + sw, 2.55f, -3.3f, 0.3f, 0.42f, 0.3f, rgba(1f, 0.42f, 0.18f), unlit = true)
            host.glow(x + sw, 2.55f, -3.3f, 0.55f, lantern, 0.35f)
        }
        // juice on the paper
        for (s in splats) {
            val f = (1f - s.t / 6f).coerceIn(0f, 1f)
            host.drawEllipsoid(s.x, s.y, -4.15f, s.r * 2, s.r * 1.4f, 0.01f, fade(s.c, 0.75f * f, tmpC), orientation = com.hyperxr.math.Quat.fromAxisAngle(0f, 0f, 1f, s.rot), gloss = 0.6f)
        }
        for (f in flying) {
            if (f.bomb) {
                host.drawSphere(f.x, f.y, f.z, f.radius, rgba(0.06f, 0.06f, 0.08f), gloss = 0.9f, metal = 0.6f)
                host.drawCylinder(f.x, f.y + f.radius, f.z, 0.035f, 0.05f, rgba(0.5f, 0.5f, 0.55f), gloss = 0.8f, metal = 1f)
                host.glow(f.x, f.y + f.radius + 0.04f, f.z, 0.06f, FUSE, 0.6f + 0.4f * sin(host.time * 30f))
                // a red danger glow around it
                host.glow(f.x, f.y, f.z, f.radius * 1.8f, rgba(1f, 0.1f, 0.05f), 0.25f + 0.15f * sin(host.time * 10f))
            } else host.drawModel("food/${fruits[f.kind].first}", f.x, f.y - 0.08f, f.z, yaw = f.spin, scale = 1.25f, pitch = f.spin * 0.5f, gloss = 0.5f)
        }
        for (h in halves) host.drawModel("food/${h.model}", h.x, h.y - 0.05f, h.z, yaw = h.spin, scale = 1.25f, roll = h.spin, gloss = 0.6f)
        // the katanas: wrapped grip, gold guard, a steel blade
        for (g in host.hands) {
            if (!g.usable) continue
            g.along(-0.12f, a); g.along(0.09f, b)
            host.drawBeam(a.x, a.y, a.z, b.x, b.y, b.z, 0.016f, rgba(0.1f, 0.08f, 0.09f), unlit = false, gloss = 0.2f)
            val q = host.along(g.dir.x, g.dir.y, g.dir.z)
            g.along(0.1f, a)
            host.drawCylinder(a.x, a.y, a.z, 0.042f, 0.012f, rgba(0.85f, 0.65f, 0.2f), q, gloss = 0.9f, metal = 1f)
            g.along(0.105f + BLADE / 2, b)
            host.drawBox(b.x, b.y, b.z, 0.032f, BLADE, 0.006f, rgba(0.86f, 0.88f, 0.92f), orientation = q, gloss = 1f, metal = 1f)
            drawTrail(host, g, rgba(0.85f, 0.9f, 1f), 0.008f, 0.6f)
        }
        if (flash > 0f) host.glow(host.head.x, host.head.y, host.head.z, 0.3f, rgba(1f, 0.45f, 0.1f), flash)
    }

    companion object {
        val JUICE = arrayOf(rgba(0.9f, 0.1f, 0.12f), rgba(1f, 0.55f, 0.1f), rgba(0.62f, 0.86f, 0.2f), rgba(1f, 0.92f, 0.2f), rgba(0.95f, 0.95f, 0.9f), rgba(0.95f, 0.2f, 0.3f))
        private val FUSE = rgba(1f, 0.75f, 0.3f)
        const val BLADE = 0.75f
        private val BLADE_SAMPLES = floatArrayOf(0.75f, 0.5f, 0.25f)
    }
}

// ================================================================================ 3. Ringue
/**
 * A boxing match in a dark arena: a real ring under spotlights, a crowd in the dark. Punch fast
 * at the head or body; slip (move your head) or block (gloves up) the punches you see coming.
 */
class BoxingGame(host: GameHost) : QuestGame(host) {
    override val title = "Ringue"
    override val look = GameSky.Look(
        zenith = rgba(0.01f, 0.01f, 0.015f), horizon = rgba(0.05f, 0.045f, 0.06f), ground = rgba(0.02f, 0.02f, 0.025f),
        sunDir = floatArrayOf(0.15f, 1f, 0.25f), floor = GameSky.FLOOR_PLAIN, floorY = -1.1f, floorColor = rgba(0.05f, 0.045f, 0.05f),
        fog = 0.08f, objectFog = 0.05f, light = rgba(1.05f, 1f, 0.95f), ambient = rgba(0.2f, 0.2f, 0.25f),
    )
    override val shadowRadius = 3.8f
    override val shadowZ = -0.6f
    private val boxer = Boxer(System.nanoTime())
    private val cooldown = FloatArray(2)
    private var hitFlash = 0f
    private var lastMsg = ""
    private var msgT = 0f
    private val rnd = java.util.Random(12)
    private val flashes = FloatArray(24)

    override fun start() { status = "Feche as mãos e soque rápido. Mova a cabeça para esquivar ou levante as luvas para bloquear" }

    private fun guarded(): Boolean {
        // both hands up in front of the face
        var n = 0
        for (g in host.hands) if (g.usable && abs(g.palm.y - host.head.y) < 0.2f && host.head.z - g.palm.z in 0.08f..0.4f && abs(g.palm.x - host.head.x) < 0.25f) n++
        return n == 2
    }

    override fun update(dt: Float) {
        val before = boxer.playerHealth
        boxer.update(dt, host.head.x, host.head.y, host.head.z, guarded())
        if (boxer.playerHealth < before - 5f) { hitFlash = 0.45f; host.play(host.sPunch, host.head.x, host.head.y, host.head.z, 1f, 0.8f) }
        if (boxer.lastResult.isNotEmpty() && boxer.lastResult != lastMsg) { lastMsg = boxer.lastResult; msgT = 1.2f }
        for (i in 0..1) {
            cooldown[i] -= dt
            val g = host.hands[i]
            if (!g.usable || cooldown[i] > 0f) continue
            val hx = boxer.x; val hz = boxer.z
            val head = (g.palm.x - hx) * (g.palm.x - hx) + (g.palm.y - boxer.headY) * (g.palm.y - boxer.headY) + (g.palm.z - hz) * (g.palm.z - hz) < 0.16f * 0.16f
            val body = !head && abs(g.palm.y - 1.2f) < 0.3f && (g.palm.x - hx) * (g.palm.x - hx) + (g.palm.z - hz) * (g.palm.z - hz) < 0.22f * 0.22f
            if (!head && !body) continue
            val dmg = boxer.hit(g.speed, head)
            if (dmg > 0f) {
                score += dmg.toInt()
                cooldown[i] = 0.35f
                host.play(host.sPunch, g.palm.x, g.palm.y, g.palm.z, 1f, if (head) 1.2f else 0.9f)
                // sweat flying off the hit
                host.particles.burst(Particles.BLOB, g.palm.x, g.palm.y, g.palm.z, 10, 1.6f, rgba(0.85f, 0.9f, 1f, 0.7f), 0.5f, 0.006f, gravity = 6f, bz = -1f)
                // the crowd reacts: camera flashes
                repeat(4) { flashes[rnd.nextInt(flashes.size)] = 1f }
            }
        }
        for (k in flashes.indices) flashes[k] = max(0f, flashes[k] - dt * 6f)
        if (rnd.nextFloat() < dt * 1.5f) flashes[rnd.nextInt(flashes.size)] = 1f
        if (hitFlash > 0f) hitFlash -= dt
        if (msgT > 0f) msgT -= dt
        status = "Adversário ${boxer.health.toInt()}%  ·  você ${max(0, boxer.playerHealth.toInt())}%" + if (msgT > 0f) "  ·  $lastMsg" else ""
        if (boxer.state == Boxer.State.DOWN) { over = true; status = "Nocaute! Você venceu"; host.play(host.sWin, boxer.x, 1.5f, boxer.z) }
        else if (boxer.playerHealth <= 0f) { over = true; status = "Você foi a nocaute. Tente de novo" }
    }

    private val canvas = rgba(1f, 1f, 1f)
    private val apron = rgba(0.06f, 0.1f, 0.3f)
    private val post = rgba(0.75f, 0.76f, 0.8f)
    private val ropes = arrayOf(rgba(0.85f, 0.1f, 0.1f), rgba(0.95f, 0.95f, 0.95f), rgba(0.1f, 0.2f, 0.85f))
    private val crowdTint = rgba(0.18f, 0.17f, 0.2f)
    private val spot = rgba(1f, 0.95f, 0.85f)

    /** A boxing glove: padded fist, thumb, white cuff. Local +Y along [q]'s axis (the forearm out). */
    private fun glove(x: Float, y: Float, z: Float, q: com.hyperxr.math.Quat, c: FloatArray, left: Boolean) {
        host.drawRounded(x, y, z, 0.11f, 0.14f, 0.12f, c, orientation = q, gloss = 0.85f, soft = true)
        val t = q.rotate(com.hyperxr.math.Vec3(if (left) 0.05f else -0.05f, -0.01f, 0.04f))
        host.drawEllipsoid(x + t.x, y + t.y, z + t.z, 0.04f, 0.08f, 0.045f, c, orientation = q, gloss = 0.85f)
        val cu = q.rotate(com.hyperxr.math.Vec3(0f, -0.1f, 0f))
        host.drawCylinder(x + cu.x, y + cu.y, z + cu.z, 0.048f, 0.07f, rgba(0.95f, 0.95f, 0.95f), q, gloss = 0.4f)
    }

    override fun draw() {
        val ringY = 0f; val half = 2.7f; val cz = -0.6f
        // the ring: canvas on a raised platform, the apron down to the arena floor
        host.drawBox(0f, ringY - 0.03f, cz, half * 2 + 0.4f, 0.06f, half * 2 + 0.4f, canvas, gloss = 0.15f, tex = "canvas", tile = 1.2f)
        host.drawBox(0f, ringY - 0.6f, cz, half * 2 + 0.3f, 1.05f, half * 2 + 0.3f, apron, gloss = 0.3f, shadow = false)
        // corner posts with pads, and three ropes a side
        val corners = arrayOf(floatArrayOf(-half, cz - half), floatArrayOf(half, cz - half), floatArrayOf(half, cz + half), floatArrayOf(-half, cz + half))
        val padColors = arrayOf(rgba(0.8f, 0.1f, 0.1f), rgba(0.95f, 0.95f, 0.95f), rgba(0.1f, 0.2f, 0.8f), rgba(0.95f, 0.95f, 0.95f))
        for ((i, c) in corners.withIndex()) {
            host.drawCylinder(c[0], 0.75f, c[1], 0.05f, 1.5f, post, gloss = 0.9f, metal = 1f)
            host.drawRounded(c[0], 0.85f, c[1], 0.2f, 1.0f, 0.2f, padColors[i], yaw = 0.785f, gloss = 0.5f, soft = true)
        }
        for (r in 0..2) {
            val y = 0.45f + r * 0.4f
            for (i in 0..3) {
                val p = corners[i]; val n = corners[(i + 1) % 4]
                host.drawBeam(p[0], y, p[1], n[0], y, n[1], 0.02f, ropes[r], unlit = false, gloss = 0.6f)
            }
        }
        // the lighting rig above: a square truss and spotlights with soft beams
        val tY = 4.6f
        for (i in 0..3) {
            val p = corners[i]; val n = corners[(i + 1) % 4]
            host.drawBeam(p[0] * 0.9f, tY, cz + (p[1] - cz) * 0.9f, n[0] * 0.9f, tY, cz + (n[1] - cz) * 0.9f, 0.05f, rgba(0.2f, 0.2f, 0.22f), unlit = false, metal = 1f, gloss = 0.6f)
        }
        for (i in 0..3) {
            val c = corners[i]
            val lx = c[0] * 0.8f; val lz = cz + (c[1] - cz) * 0.8f
            host.drawCylinder(lx, tY - 0.15f, lz, 0.13f, 0.25f, rgba(0.12f, 0.12f, 0.13f), metal = 1f)
            host.glow(lx, tY - 0.3f, lz, 0.35f, spot, 0.9f)
            // the beam of light through the haze, to the middle of the ring
            val q = host.along(-lx * 0.85f, -(tY - 0.3f), (cz - lz) * 0.85f)
            val mx = lx * 0.575f; val mz = lz + (cz - lz) * 0.425f
            host.drawMesh(host.cone, mx, (tY - 0.3f) / 2f, mz, 2.2f, tY * 1.05f, 2.2f, rgba(1f, 0.95f, 0.85f, 0.05f), orientation = q.mul(com.hyperxr.math.Quat.fromAxisAngle(1f, 0f, 0f, Math.PI.toFloat())), unlit = true, shadow = false).additive = true
        }
        // the crowd around, in the dark, with camera flashes
        val m = host.model("chars/character-male-b")
        val ch = m?.let { it.boundsMax[1] - it.boundsMin[1] }?.takeIf { it > 0.01f } ?: 1f
        var k = 0
        for (side in 0..3) for (j in 0..5) {
            val t = -2.5f + j * 1f
            val (x, z, yaw) = when (side) { 0 -> Triple(t, cz - 5.2f, 0f); 1 -> Triple(5.2f, cz + t, -1.57f); 2 -> Triple(-5.2f, cz + t, 1.57f); else -> Triple(t * 1.2f, cz + 5.6f, 3.14f) }
            val name = CROWD[(side * 6 + j) % CROWD.size]
            host.drawModel("chars/$name", x, -1.1f, z, yaw = yaw, scale = 1.7f / ch, tint = crowdTint, shadow = false)
            val f = flashes[k++ % flashes.size]
            if (f > 0.05f) host.glow(x, 0.5f, z, 0.25f, WHITE, f)
        }
        // the opponent
        val yaw = kotlin.math.atan2(host.head.x - boxer.x, host.head.z - boxer.z)
        val bm = host.model("chars/character-male-b")
        val h = bm?.let { it.boundsMax[1] - it.boundsMin[1] }?.takeIf { it > 0.01f } ?: 1f
        val stunned = boxer.state == Boxer.State.STUNNED || boxer.state == Boxer.State.DOWN
        // a little bounce on the feet while he waits
        val bob = if (boxer.state == Boxer.State.DOWN) 0f else abs(sin(host.time * 5f)) * 0.03f
        host.drawModel("chars/character-male-b", boxer.x, (if (boxer.state == Boxer.State.DOWN) -0.6f else 0f) + bob, boxer.z, yaw = yaw, scale = 1.78f / h,
            pitch = if (boxer.state == Boxer.State.DOWN) -1.4f else 0f, tint = if (stunned) rgba(1f, 0.7f, 0.7f) else null, gloss = 0.3f)
        // its gloves: one guarding, the punching one flying at where your head was
        val fx = sin(yaw); val fz = cos(yaw)
        val gq = com.hyperxr.math.Quat.fromAxisAngle(0f, 1f, 0f, yaw).mul(com.hyperxr.math.Quat.fromAxisAngle(1f, 0f, 0f, 1.3f))
        for (side in 0..1) {
            val sx = if (side == 0) -0.18f else 0.18f
            var gx = boxer.x + fx * 0.3f + fz * sx; var gy = boxer.headY - 0.12f + bob; var gz = boxer.z + fz * 0.3f - fx * sx
            if (side == boxer.punchHand) {
                val e = boxer.punchExtent().coerceIn(-0.3f, 1f)
                gx += (boxer.targetX - gx) * max(0f, e); gy += (boxer.targetY - 0.05f - gy) * max(0f, e); gz += (boxer.targetZ - gz) * max(0f, e)
                if (e < 0f) { gx -= fx * e * 0.4f; gz -= fz * e * 0.4f }
            }
            glove(gx, gy, gz, gq, rgba(0.1f, 0.22f, 0.8f), side == 0)
        }
        // health bar above it
        val hp = boxer.health / 100f
        host.drawBox(boxer.x, 2.1f, boxer.z, 0.62f, 0.05f, 0.012f, rgba(0.05f, 0.05f, 0.05f, 0.7f), yaw = yaw, unlit = true)
        host.drawBox(boxer.x - (1f - hp) * 0.3f * cos(yaw), 2.1f, boxer.z + (1f - hp) * 0.3f * sin(yaw), 0.6f * hp, 0.035f, 0.016f, rgba(1f - hp, hp, 0.2f), yaw = yaw, unlit = true)
        // your gloves, on your fists
        for ((i, g) in host.hands.withIndex()) {
            if (!g.usable) continue
            val q = host.along(g.dir.x, g.dir.y, g.dir.z)
            glove(g.palm.x + g.dir.x * 0.04f, g.palm.y + g.dir.y * 0.04f, g.palm.z + g.dir.z * 0.04f, q, rgba(0.82f, 0.08f, 0.08f), i == 0)
        }
        if (hitFlash > 0f) host.glow(host.head.x, host.head.y, host.head.z, 0.3f, rgba(1f, 0.1f, 0.1f), hitFlash * 1.5f)
    }

    companion object { private val CROWD = arrayOf("character-male-a", "character-female-a", "character-male-c", "character-female-b", "character-male-b") }
}

// ================================================================================ 4. Prancha no Céu
/** An elevator to the top of a skyscraper, a plank over the street. Walk to the end... and step off. */
class PlankGame(host: GameHost) : QuestGame(host) {
    override val title = "Prancha no Céu"
    private enum class Phase { RIDE, OPEN, ON_PLANK, AT_END, FALLING, DONE }
    private var phase = Phase.RIDE
    private var t = 0f
    private var height = 0f
    private var walk = 0f
    private var fallV = 0f
    private val top = 90f
    override val look = GameSky.Look(
        zenith = rgba(0.22f, 0.45f, 0.86f), horizon = rgba(0.74f, 0.83f, 0.94f), ground = rgba(0.45f, 0.47f, 0.5f),
        sunDir = floatArrayOf(0.55f, 0.65f, -0.5f), sunColor = rgba(1f, 0.95f, 0.85f), sunSize = 0.025f, clouds = 0.55f,
        floor = GameSky.FLOOR_PLAIN, floorColor = rgba(0.34f, 0.35f, 0.37f), fog = 0.0035f, objectFog = 0.003f,
        light = rgba(1.05f, 1f, 0.92f), ambient = rgba(0.42f, 0.48f, 0.58f),
    )
    override val shadowRadius = 3f
    override val shadowZ = -1.2f

    override fun start() { status = "Suba de elevador. Na prancha, ande até a ponta (andando de verdade ou segurando a pinça para avançar)" }

    /** Your position along the plank: real steps, plus the pinch walk for phones that only turn. */
    private fun along() = -(host.head.z) + walk

    override fun update(dt: Float) {
        t += dt
        when (phase) {
            Phase.RIDE -> { height = top * smooth((t / 12f).coerceIn(0f, 1f)); if (t > 12.5f) { phase = Phase.OPEN; t = 0f; host.play(host.sTick, 0f, 1.5f, -0.6f) } }
            Phase.OPEN -> if (t > 1.5f) { phase = Phase.ON_PLANK; t = 0f }
            Phase.ON_PLANK, Phase.AT_END -> {
                if (host.hands.any { it.usable && it.pinch }) walk += 0.35f * dt
                val a = along()
                // off the plank's sides past the elevator door: you fall
                val side = abs(host.head.x)
                if (a > 0.8f && side > 0.3f || a > 3.9f) { phase = Phase.FALLING; t = 0f; fallV = 0f; host.play(host.sMiss, 0f, 1f, -2f) }
                else if (a > 3.3f && phase == Phase.ON_PLANK) { phase = Phase.AT_END; score += 100; status = "Você chegou à ponta! Agora... dê um passo para fora" ; host.play(host.sCoin, 0f, 1.5f, -3.5f) }
                if (phase == Phase.ON_PLANK) status = "Altura: ${top.toInt()} m · ande até a ponta (${max(0, ((3.4f - a) * 100).toInt())} cm)"
            }
            Phase.FALLING -> {
                fallV += 9.81f * dt
                height = max(0f, height - fallV * dt)
                if (height <= 0f) {
                    if (score > 0) score += 100
                    phase = Phase.DONE; over = true
                    status = if (score >= 200) "Você pulou! Coragem total" else "Caiu antes da ponta. Tente de novo"
                }
            }
            Phase.DONE -> Unit
        }
    }

    private fun smooth(u: Float) = u * u * (3 - 2 * u)

    private val steel = rgba(0.78f, 0.8f, 0.84f)
    private val white = rgba(1f, 1f, 1f)

    override fun draw() {
        val ground = -height
        host.sky.floorY = ground
        // streets: dark asphalt with pale lines, in a grid between the blocks
        for (i in -4..4) {
            host.drawBox(i * 18f + 9f, ground + 0.02f, 0f, 6f, 0.04f, 170f, rgba(0.18f, 0.18f, 0.2f), shadow = false)
            host.drawBox(0f, ground + 0.025f, i * 18f + 5f, 170f, 0.04f, 6f, rgba(0.18f, 0.18f, 0.2f), shadow = false)
            host.drawBox(i * 18f + 9f, ground + 0.045f, 0f, 0.15f, 0.01f, 170f, rgba(0.9f, 0.85f, 0.55f), shadow = false)
        }
        val rnd = java.util.Random(4)
        for (i in -4..4) for (j in -4..4) {
            if (abs(i) < 1 && abs(j) < 1) continue
            val k = rnd.nextInt(5)
            val near = abs(i) + abs(j) < 4
            val name = if (near) "city/building-skyscraper-${"abcde"[k]}" else "city/low-detail-building-${"abcd"[k % 4]}"
            val s = 10f + rnd.nextFloat() * 8f
            // the towers are drawn taller than the kit's, to reach your height
            host.drawModel(name, i * 18f, ground, j * 18f - 4f, yaw = (rnd.nextInt(4) * Math.PI / 2).toFloat(), scale = s, sy = if (near) 1.6f + rnd.nextFloat() else 1f, shadow = false)
        }
        // our tower: glass and concrete all the way down, a roof ledge at the top
        host.drawBox(0f, ground + (height - 0.1f) / 2, 0.2f + walk, 4f, height - 0.1f, 4f, white, gloss = 0.6f, tex = "facade", tile = 4f, shadow = false)
        if (height > 1f) host.drawBox(0f, -0.25f, 0.2f + walk, 4.3f, 0.3f, 4.3f, rgba(0.62f, 0.63f, 0.66f), tex = "concrete", tile = 2f)
        // the elevator car: steel walls, a lit ceiling, the button panel, the doors at the front
        val wz = walk
        host.drawBox(0f, -0.05f, 0.1f + wz, 1.4f, 0.1f, 1.4f, rgba(0.2f, 0.2f, 0.22f), gloss = 0.3f, tex = "rock", tile = 0.6f)
        host.drawBox(-0.7f, 1.2f, 0.1f + wz, 0.05f, 2.4f, 1.4f, steel, gloss = 0.8f, metal = 1f, tex = "metal", tile = 1.2f)
        host.drawBox(0.7f, 1.2f, 0.1f + wz, 0.05f, 2.4f, 1.4f, steel, gloss = 0.8f, metal = 1f, tex = "metal", tile = 1.2f)
        host.drawBox(0f, 1.2f, 0.8f + wz, 1.4f, 2.4f, 0.05f, steel, gloss = 0.8f, metal = 1f, tex = "metal", tile = 1.2f)
        host.drawBox(0f, 2.42f, 0.1f + wz, 1.4f, 0.05f, 1.4f, rgba(0.5f, 0.5f, 0.52f), gloss = 0.4f)
        host.drawBox(0f, 2.39f, 0.1f + wz, 0.9f, 0.01f, 0.9f, rgba(1f, 0.98f, 0.92f), unlit = true, shadow = false)
        host.glow(0f, 2.3f, 0.1f + wz, 0.5f, rgba(1f, 0.97f, 0.9f), 0.3f)
        // handrail and the button panel
        host.drawBeam(-0.66f, 0.95f, -0.4f + wz, -0.66f, 0.95f, 0.7f + wz, 0.015f, steel, unlit = false, gloss = 1f, metal = 1f)
        host.drawBox(0.67f, 1.25f, -0.35f + wz, 0.02f, 0.5f, 0.2f, rgba(0.15f, 0.15f, 0.16f), gloss = 0.7f)
        for (b in 0..5) {
            val lit = phase != Phase.RIDE && b == 5 || phase == Phase.RIDE && b == (t / 2.5f).toInt().coerceAtMost(5)
            host.drawSphere(0.66f, 1.08f + b * 0.06f, -0.35f + wz, 0.012f, if (lit) rgba(1f, 0.6f, 0.2f) else rgba(0.6f, 0.6f, 0.62f), unlit = lit, shadow = false)
        }
        val open = when (phase) { Phase.RIDE -> 0f; Phase.OPEN -> (t / 1.5f).coerceIn(0f, 1f); else -> 1f }
        host.drawBox(-0.35f - open * 0.6f, 1.2f, -0.6f + wz, 0.7f, 2.4f, 0.04f, steel, gloss = 0.9f, metal = 1f, tex = "metal", tile = 1.2f)
        host.drawBox(0.35f + open * 0.6f, 1.2f, -0.6f + wz, 0.7f, 2.4f, 0.04f, steel, gloss = 0.9f, metal = 1f, tex = "metal", tile = 1.2f)
        host.drawBox(0f, 2.48f, -0.62f + wz, 1.6f, 0.12f, 0.06f, rgba(0.3f, 0.3f, 0.32f), metal = 1f)
        // the plank: an old board sticking out over the street, bolted on steel brackets
        if (phase != Phase.RIDE) {
            host.drawBox(0f, -0.03f, -2.3f + walk, 0.26f, 0.05f, 3.4f, white, gloss = 0.2f, tex = "wood", tile = 1f)
            for (z in floatArrayOf(-0.75f, -1.1f)) host.drawBox(0f, -0.08f, z + walk, 0.34f, 0.06f, 0.05f, rgba(0.3f, 0.3f, 0.32f), metal = 1f, gloss = 0.6f)
        }
        // birds circling below you
        if (height > 20f) for (i in 0..4) {
            val a = host.time * 0.3f + i * 1.25f
            val bx = cos(a) * (12f + i * 3f); val bz = -10f + sin(a) * (10f + i * 2f); val by = -height * 0.4f + i * 2f
            val flap = sin(host.time * 9f + i) * 0.5f
            host.drawBox(bx, by, bz, 0.5f, 0.02f, 0.12f, rgba(0.15f, 0.15f, 0.17f), yaw = -a, orientation = com.hyperxr.math.Quat.fromAxisAngle(0f, 1f, 0f, -a).mul(com.hyperxr.math.Quat.fromAxisAngle(1f, 0f, 0f, flap)), shadow = false)
        }
    }
}

// ================================================================================ 5. Minigolfe
/** Three holes on a sunny green, among trees and hills: pinch to hold the putter, swing gently to putt. */
class GolfGame(host: GameHost) : QuestGame(host) {
    override val title = "Minigolfe"
    override val look = GameSky.Look(
        zenith = rgba(0.25f, 0.5f, 0.92f), horizon = rgba(0.78f, 0.88f, 0.97f), ground = rgba(0.3f, 0.45f, 0.22f),
        sunDir = floatArrayOf(-0.4f, 0.75f, -0.5f), sunColor = rgba(1f, 0.95f, 0.85f), sunSize = 0.025f, clouds = 0.4f,
        floor = GameSky.FLOOR_PLAIN, floorY = -0.02f, floorColor = rgba(0.33f, 0.52f, 0.22f), fog = 0.006f, objectFog = 0.004f,
        light = rgba(1.05f, 1f, 0.9f), ambient = rgba(0.45f, 0.5f, 0.55f),
    )
    override val shadowRadius = 3.5f
    override val shadowZ = -1.8f
    private var holeIndex = 0
    private val ball = GolfBall()
    private var strokes = 0
    private var total = 0
    private var wait = 0f
    private val clubPrev = FloatArray(2)
    private var hadClub = false
    private val floors = listOf(
        listOf(floatArrayOf(0f, -1.9f, 0.6f, 3f)),
        listOf(floatArrayOf(0f, -1.5f, 0.6f, 2.2f), floatArrayOf(0.8f, -2.3f, 2.2f, 0.6f)),
        listOf(floatArrayOf(0f, -2.0f, 1.6f, 3.2f)),
    )
    private val trail = ArrayDeque<FloatArray>()

    override fun start() { setHole(0); score = 0 }

    private fun setHole(i: Int) {
        holeIndex = i
        val h = GolfCourses.holes[i]
        ball.x = h.teeX; ball.z = h.teeZ; ball.vx = 0f; ball.vz = 0f; ball.inCup = false
        strokes = 0
        status = "Buraco ${i + 1} (par ${h.par}): faça a pinça para pegar o taco e empurre a bola"
    }

    /** The putter's head: under the pinching hand, on the floor. */
    private fun club(): FloatArray? {
        val g = host.hands.firstOrNull { it.usable && it.pinch } ?: return null
        return floatArrayOf(g.palm.x, g.palm.z - 0.05f)
    }

    override fun update(dt: Float) {
        val h = GolfCourses.holes[holeIndex]
        val c = club()
        if (c != null && hadClub && ball.putt(clubPrev[0], clubPrev[1], c[0], c[1], dt)) {
            strokes++; host.play(host.sTick, ball.x, 0f, ball.z, 1f, 0.7f)
        }
        if (c != null) { clubPrev[0] = c[0]; clubPrev[1] = c[1] }
        hadClub = c != null
        ball.step(h, dt)
        if (hypot(ball.vx, ball.vz) > 0.2f) { trail.addFirst(floatArrayOf(ball.x, ball.z)); if (trail.size > 12) trail.removeLast() } else if (trail.isNotEmpty()) trail.removeLast()
        if (ball.inCup && wait <= 0f) {
            wait = 2.2f
            total += strokes
            score = total
            host.play(host.sCoin, h.cupX, 0f, h.cupZ)
            host.particles.burst(Particles.SPARK, h.cupX, 0.1f, h.cupZ, 40, 2.5f, rgba(1f, 0.85f, 0.3f), 0.8f, 0.006f, gravity = 3f, up = 2f)
            status = "Na caçapa em $strokes tacada(s)!" + if (strokes == 1) " Hole in one!" else ""
        }
        if (wait > 0f) {
            wait -= dt
            if (wait <= 0f) {
                if (holeIndex + 1 < GolfCourses.holes.size) setHole(holeIndex + 1)
                else { over = true; val par = GolfCourses.holes.sumOf { it.par }; status = "Fim: $total tacadas (par $par)"; host.play(host.sWin, 0f, 1.2f, -1f) }
            }
        } else if (!ball.inCup) status = "Buraco ${holeIndex + 1} · tacadas: $strokes · total: $total"
    }

    private val white = rgba(1f, 1f, 1f)

    override fun draw() {
        val h = GolfCourses.holes[holeIndex]
        // the scenery: hills far away, trees and rocks around the course
        val rnd = java.util.Random(77)
        for (i in 0 until 9) {
            val a = -2.6f + i * 0.65f + rnd.nextFloat() * 0.3f
            val r = 180f + rnd.nextFloat() * 90f
            val s = 90f + rnd.nextFloat() * 90f
            host.drawMesh(host.cone, sin(a) * r, s * 0.25f, -cos(a) * r, s * 1.8f, s * 0.5f + 20f, s * 1.8f, rgba(0.3f, 0.45f, 0.32f), gloss = 0f, shadow = false)
        }
        for (i in 0 until 22) {
            val a = rnd.nextFloat() * 6.28f
            val r = 5f + rnd.nextFloat() * 14f
            val x = sin(a) * r; val z = -2f - cos(a) * r
            if (abs(x) < 2.5f && z > -5f) continue
            val kind = rnd.nextInt(4)
            val name = when (kind) { 0 -> "survival/tree-tall"; 1 -> "survival/tree-autumn"; 2 -> "platformer/tree-pine"; else -> "survival/tree" }
            host.drawModel(name, x, -0.02f, z, yaw = rnd.nextFloat() * 6f, scale = if (kind == 2) 2.2f + rnd.nextFloat() else 2.5f + rnd.nextFloat() * 2f, shadow = r < 10f)
        }
        for (i in 0 until 8) host.drawModel("survival/rock-${"abc"[i % 3]}", -3f + i * 0.9f, -0.02f, 1.5f + (i % 3) * 0.5f, yaw = i * 1.3f, scale = 0.6f + (i % 2) * 0.4f)
        for (i in 0 until 6) host.drawModel("platformer/flowers", -2.6f + i * 1.05f, -0.02f, -0.6f - (i % 2) * 3f, yaw = i.toFloat(), scale = 0.35f, shadow = false)
        // the course: felt on a low base, wooden rails, stone blocks
        for (f in floors[holeIndex]) {
            host.drawBox(f[0], -0.005f, f[1], f[2] + 0.12f, 0.05f, f[3] + 0.12f, rgba(0.55f, 0.5f, 0.45f), tex = "rock", tile = 0.8f)
            host.drawBox(f[0], 0.006f, f[1], f[2], 0.01f, f[3], white, gloss = 0.05f, tex = "felt", tile = 0.6f)
        }
        val w = h.walls
        for (i in 0 until w.size / 4) {
            val ax = w[i * 4]; val az = w[i * 4 + 1]; val bx = w[i * 4 + 2]; val bz = w[i * 4 + 3]
            val len = hypot(bx - ax, bz - az)
            host.drawRounded((ax + bx) / 2, 0.045f, (az + bz) / 2, len + 0.05f, 0.09f, 0.05f, white, yaw = kotlin.math.atan2(-(bz - az), bx - ax), gloss = 0.4f, tex = "wood", tile = 0.6f)
        }
        for (b in 0 until h.boxes.size / 4) host.drawRounded(h.boxes[b * 4], 0.07f, h.boxes[b * 4 + 1], h.boxes[b * 4 + 2] * 2, 0.14f, h.boxes[b * 4 + 3] * 2, rgba(0.85f, 0.82f, 0.78f), gloss = 0.2f, tex = "rock", tile = 0.4f)
        // the cup: a dark hole with a white rim, the flag in it
        host.drawCylinder(h.cupX, 0.0115f, h.cupZ, GolfBall.CUP_RADIUS, 0.002f, rgba(0.02f, 0.02f, 0.02f), shadow = false)
        host.drawTorus(h.cupX, 0.012f, h.cupZ, GolfBall.CUP_RADIUS + 0.004f, rgba(0.95f, 0.95f, 0.95f), thick = 0.3f)
        host.drawModel("golf/flag-red", h.cupX, 0f, h.cupZ, scale = 0.6f, yaw = sin(host.time * 2f) * 0.15f)
        if (holeIndex == 2) host.drawModel("golf/windmill", 0f, 0f, -3.9f, scale = 0.8f)
        // the tee mat
        host.drawBox(h.teeX, 0.013f, h.teeZ, 0.22f, 0.005f, 0.22f, rgba(0.12f, 0.35f, 0.15f), gloss = 0.1f, shadow = false)
        // the ball, with a faint trail when rolling fast
        host.drawSphere(ball.x, ball.radius + 0.01f, ball.z, ball.radius, white, gloss = 0.7f)
        var prev: FloatArray? = null
        for ((i, p) in trail.withIndex()) {
            prev?.let { host.glowBeam(it[0], ball.radius + 0.01f, it[1], p[0], ball.radius + 0.01f, p[1], 0.006f, rgba(1f, 1f, 1f), 0.4f * (1f - i / 12f), core = false) }
            prev = p
        }
        club()?.let { c ->
            val g = host.hands.first { it.usable && it.pinch }
            // grip, shaft and a steel head
            host.drawBeam(g.palm.x, g.palm.y + 0.05f, g.palm.z, g.palm.x, g.palm.y - 0.15f, g.palm.z, 0.014f, rgba(0.1f, 0.1f, 0.1f), unlit = false)
            host.drawBeam(g.palm.x, g.palm.y - 0.15f, g.palm.z, c[0], 0.035f, c[1], 0.006f, rgba(0.8f, 0.82f, 0.86f), unlit = false, gloss = 1f, metal = 1f)
            host.drawRounded(c[0], 0.03f, c[1], 0.11f, 0.035f, 0.03f, rgba(0.55f, 0.57f, 0.6f), gloss = 1f, metal = 1f)
        }
    }
}

/** The ten games, by id. */
object QuestGames {
    fun create(id: String, host: GameHost): QuestGame? = when (id) {
        "rhythm" -> RhythmGame(host)
        "fruit" -> FruitGame(host)
        "boxing" -> BoxingGame(host)
        "plank" -> PlankGame(host)
        "golf" -> GolfGame(host)
        "slowtime" -> SlowTimeGame(host)
        "drones" -> DroneGame(host)
        "pingpong" -> PingPongGame(host)
        "fishing" -> FishingGame(host)
        "diorama" -> DioramaGame(host)
        "climb" -> ClimbGame(host)
        "pipeclimb" -> PipeClimbGame(host)
        "macacada" -> MacacadaGame(host)
        "encontro" -> EncontroGame(host)
        "babuino" -> BabuinoGame(host)
        "expediente" -> ExpedienteGame(host)
        "neonrail" -> NeonRailGame(host)
        "orbs" -> OrbsGame(host)
        "shapewall" -> ShapeWallGame(host)
        "drumboat" -> DrumBoatGame(host)
        "fittargets" -> FitTargetsGame(host)
        "domeracket" -> DomeRacketGame(host)
        "archery" -> ArcheryGame(host)
        "slingshot" -> SlingshotGame(host)
        "basketball" -> BasketballGame(host)
        "bowling" -> BowlingGame(host)
        "darts" -> DartsGame(host)
        "dodgeball" -> DodgeballGame(host)
        "zombieyard" -> ZombieYardGame(host)
        "saber" -> SaberTrainingGame(host)
        "spells" -> SpellsGame(host)
        "skeet" -> SkeetGame(host)
        "paintball" -> PaintballGame(host)
        "zerog" -> ZeroGGame(host)
        "bomb" -> BombGame(host)
        "spydesk" -> SpyDeskGame(host)
        "pieces" -> PiecesGame(host)
        "cubes" -> CubesGame(host)
        "fallingblocks" -> FallingBlocksGame(host)
        "tinytown" -> TinyTownGame(host)
        "dungeon" -> TableDungeonGame(host)
        "sketch" -> LightSketchGame(host)
        "kayak" -> KayakGame(host)
        "wingsuit" -> WingsuitGame(host)
        "tableracing" -> TableRacingGame(host)
        "jewelthief" -> JewelThiefGame(host)
        else -> null
    }
}
