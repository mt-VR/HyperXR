package com.hyperxr.questgames

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.hyperxr.math.Quat
import kotlin.math.abs
import kotlin.math.cos
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

private val RED = floatArrayOf(0.95f, 0.18f, 0.22f, 1f)
private val BLUE = floatArrayOf(0.15f, 0.5f, 1f, 1f)
private val WHITE = floatArrayOf(1f, 1f, 1f, 1f)

// ================================================================================ 1. Sabre de Ritmo
/** Blocks fly at you to the beat; cut each with its hand (left red, right blue) in the arrow's direction. */
class RhythmGame(host: GameHost) : QuestGame(host) {
    override val title = "Sabre de Ritmo"
    private val length = 95f
    private val music = BeatMusic(128f)
    private val stream = SynthStream { b, n -> music.render(b, n) }
    private val notes = BeatChart.generate(128f, length, seed = 7)
    private var combo = 0; private var hits = 0; private var misses = 0
    private class Piece(var x: Float, var y: Float, var z: Float, val vx: Float, val vy: Float, val vz: Float, val color: FloatArray, var t: Float)
    private val pieces = ArrayList<Piece>()

    override fun start() { stream.start(); status = "Corte os blocos com a mão da cor certa, na direção da seta" }

    private fun zOf(n: Note, song: Float) = HIT_Z - (n.time - song) * SPEED

    override fun update(dt: Float) {
        val song = stream.heardSeconds
        for (n in notes) {
            if (n.done) continue
            val z = zOf(n, song)
            if (z < -20f) break
            if (z > 0.4f) { n.done = true; misses++; combo = 0; host.play(host.sMiss, 0f, 1f, 0f, 0.5f); continue }
            if (z < HIT_Z - 0.9f) continue
            val x = BeatChart.laneX(n.lane); val y = BeatChart.rowY(n.row)
            for (h in 0..1) {
                val g = host.hands[h]
                if (!g.usable) continue
                // the fingertip's and the palm's paths both count as the blade
                var q = SliceJudge.judge(g.prevTip.x, g.prevTip.y, g.prevTip.z, g.tip.x, g.tip.y, g.tip.z, dt, x, y, z, HALF, n.dir)
                if (q == 0) q = SliceJudge.judge(g.prevPalm.x, g.prevPalm.y, g.prevPalm.z, g.palm.x, g.palm.y, g.palm.z, dt, x, y, z, HALF, n.dir)
                if (q == 0) continue
                n.done = true
                if (h != n.hand) { combo = 0; misses++; host.play(host.sMiss, x, y, z, 0.6f); break }
                n.hit = true; hits++; combo++
                score += q * multiplier()
                host.play(host.sHit, x, y, z, 0.7f, 1f + min(combo, 16) * 0.02f)
                val c = if (n.hand == 0) RED else BLUE
                pieces += Piece(x - 0.05f, y, z, -1.2f, 0.8f, 0.5f, c, 0f)
                pieces += Piece(x + 0.05f, y, z, 1.2f, 0.8f, 0.5f, c, 0f)
                break
            }
        }
        pieces.forEach { it.t += dt; it.x += it.vx * dt; it.y += (it.vy - 4f * it.t) * dt; it.z += it.vz * dt }
        pieces.removeAll { it.t > 0.7f }
        status = "Combo $combo  ·  ${multiplier()}x  ·  acertos $hits  ·  erros $misses"
        if (song > length) {
            over = true
            val acc = if (hits + misses == 0) 0 else hits * 100 / (hits + misses)
            status = "Fim da música! Precisão $acc%"
            host.play(host.sWin, 0f, 1.5f, -1f)
            stream.stop()
        }
    }

    private fun multiplier() = when { combo >= 14 -> 8; combo >= 6 -> 4; combo >= 2 -> 2; else -> 1 }

    override fun draw() {
        val song = stream.heardSeconds
        // the track: four lane lines glowing into the distance
        for (l in 0..4) host.drawBox((l - 2) * 0.3f, 0.02f, -10f, 0.01f, 0.01f, 20f, floatArrayOf(0.3f, 0.4f, 1f, 1f), unlit = true)
        host.drawBox(0f, 0.01f, HIT_Z, 1.3f, 0.005f, 0.04f, floatArrayOf(1f, 1f, 1f, 0.6f), unlit = true)
        for (n in notes) {
            if (n.done) continue
            val z = zOf(n, song)
            if (z < -20f) break
            if (z > 0.5f) continue
            val x = BeatChart.laneX(n.lane); val y = BeatChart.rowY(n.row)
            host.drawBox(x, y, z, HALF * 2, HALF * 2, HALF * 2, if (n.hand == 0) RED else BLUE, gloss = 0.7f)
            // the arrow: a white bar on the front face, on the side the cut must go towards
            val (ax, ay, sx, sy) = when (n.dir) {
                Note.UP -> floatArrayOf(0f, 0.06f, 0.14f, 0.035f)
                Note.DOWN -> floatArrayOf(0f, -0.06f, 0.14f, 0.035f)
                Note.LEFT -> floatArrayOf(-0.06f, 0f, 0.035f, 0.14f)
                Note.RIGHT -> floatArrayOf(0.06f, 0f, 0.035f, 0.14f)
                else -> floatArrayOf(0f, 0f, 0.05f, 0.05f)
            }
            host.drawBox(x + ax, y + ay, z + HALF + 0.003f, sx, sy, 0.005f, WHITE, unlit = true)
        }
        for (p in pieces) host.drawBox(p.x, p.y, p.z, 0.12f, 0.24f, 0.24f, p.color.copyOf().also { it[3] = 1f - p.t / 0.7f }, yaw = p.t * 6f)
        // which hand is which colour
        for (h in 0..1) { val g = host.hands[h]; if (g.usable) host.drawSphere(g.tip.x, g.tip.y, g.tip.z, 0.02f, if (h == 0) RED else BLUE, unlit = true) }
    }

    override fun release() = stream.stop()

    companion object { const val SPEED = 9f; const val HIT_Z = -0.55f; const val HALF = 0.12f }
}

// ================================================================================ 2. Ninja das Frutas
/** Fruit is thrown up in front of you: swipe through it. Avoid the bombs. One minute. */
class FruitGame(host: GameHost) : QuestGame(host) {
    override val title = "Ninja das Frutas"
    private val fruits = listOf("apple" to "apple-half", "orange" to null, "pear" to "pear-half", "lemon" to "lemon-half", "coconut" to "coconut-half",
        "watermelon" to null, "pineapple" to null, "banana" to null, "strawberry" to null, "tomato" to null, "avocado" to "advocado-half")
    private val rnd = java.util.Random()
    private val flying = ArrayList<Thrown>()
    private class Half(val model: String, var x: Float, var y: Float, var z: Float, val vx: Float, var vy: Float, var spin: Float, var t: Float)
    private val halves = ArrayList<Half>()
    private class Juice(var x: Float, var y: Float, var z: Float, val vx: Float, var vy: Float, val vz: Float, val c: FloatArray, var t: Float)
    private val juice = ArrayList<Juice>()
    private var timeLeft = 60f
    private var next = 1f
    private var flash = 0f
    private var lastCut = -1f

    override fun start() { status = "Passe a mão rápido pelas frutas. Bombas tiram 10 pontos" }

    override fun update(dt: Float) {
        timeLeft -= dt
        next -= dt
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
                if (!g.usable) continue
                if (!FruitThrower.sliced(f, g.prevTip.x, g.prevTip.y, g.prevTip.z, g.tip.x, g.tip.y, g.tip.z, dt) &&
                    !FruitThrower.sliced(f, g.prevPalm.x, g.prevPalm.y, g.prevPalm.z, g.palm.x, g.palm.y, g.palm.z, dt)) continue
                f.cut = true
                if (f.bomb) { score = max(0, score - 10); flash = 0.5f; host.play(host.sBoom, f.x, f.y, f.z) }
                else {
                    // two in a quick swipe: a combo bonus
                    score += if (host.time - lastCut < 0.25f) 2 else 1
                    lastCut = host.time
                    host.play(host.sSplash, f.x, f.y, f.z, 0.8f, 0.9f + rnd.nextFloat() * 0.3f)
                    val (whole, half) = fruits[f.kind]
                    val m = half ?: whole
                    halves += Half(m, f.x - 0.03f, f.y, f.z, -0.8f, 0.6f, f.spin, 0f)
                    halves += Half(m, f.x + 0.03f, f.y, f.z, 0.8f, 0.6f, f.spin + 3.1f, 0f)
                    val col = JUICE[f.kind % JUICE.size]
                    repeat(10) { juice += Juice(f.x, f.y, f.z, (rnd.nextFloat() - 0.5f) * 2f, rnd.nextFloat() * 2f, (rnd.nextFloat() - 0.5f) * 2f, col, 0f) }
                }
                break
            }
        }
        flying.removeAll { it.cut || it.y < -0.5f }
        halves.forEach { it.t += dt; it.vy -= 5f * dt; it.x += it.vx * dt; it.y += it.vy * dt; it.spin += dt * 4f }
        halves.removeAll { it.t > 1.4f }
        juice.forEach { it.t += dt; it.vy -= 9f * dt; it.x += it.vx * dt; it.y += it.vy * dt; it.z += it.vz * dt }
        juice.removeAll { it.t > 0.6f }
        if (flash > 0f) flash -= dt
        status = "Tempo: ${max(0, timeLeft.toInt())} s"
        if (timeLeft <= 0f && flying.isEmpty()) { over = true; status = "Fim! $score frutas"; host.play(host.sWin, 0f, 1.5f, -1f) }
    }

    override fun draw() {
        for (f in flying) {
            if (f.bomb) {
                host.drawSphere(f.x, f.y, f.z, f.radius, floatArrayOf(0.08f, 0.08f, 0.1f, 1f), gloss = 0.8f)
                val blink = if ((host.time * 8).toInt() % 2 == 0) floatArrayOf(1f, 0.2f, 0.1f, 1f) else floatArrayOf(1f, 0.8f, 0.2f, 1f)
                host.drawSphere(f.x, f.y + f.radius + 0.02f, f.z, 0.02f, blink, unlit = true)
            } else host.drawModel("food/${fruits[f.kind].first}", f.x, f.y - 0.08f, f.z, yaw = f.spin, scale = 1.1f, pitch = f.spin * 0.5f)
        }
        for (h in halves) host.drawModel("food/${h.model}", h.x, h.y - 0.05f, h.z, yaw = h.spin, scale = 1.1f, roll = h.spin)
        for (j in juice) host.drawSphere(j.x, j.y, j.z, 0.012f, j.c)
        if (flash > 0f) host.drawSphere(host.head.x, host.head.y, host.head.z, 0.3f, floatArrayOf(1f, 0.3f, 0.2f, flash), unlit = true)
    }

    companion object {
        val JUICE = arrayOf(floatArrayOf(0.9f, 0.1f, 0.1f, 1f), floatArrayOf(1f, 0.55f, 0.1f, 1f), floatArrayOf(0.7f, 0.9f, 0.2f, 1f), floatArrayOf(1f, 0.95f, 0.2f, 1f))
    }
}

// ================================================================================ 3. Ringue
/** A boxing match: punch fast at the head or body; slip (move your head) or block (gloves up) the punches you see coming. */
class BoxingGame(host: GameHost) : QuestGame(host) {
    override val title = "Ringue"
    private val boxer = Boxer(System.nanoTime())
    private val cooldown = FloatArray(2)
    private var hitFlash = 0f
    private var lastMsg = ""
    private var msgT = 0f

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
        if (boxer.playerHealth < before - 5f) { hitFlash = 0.4f; host.play(host.sPunch, host.head.x, host.head.y, host.head.z, 1f, 0.8f) }
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
            }
        }
        if (hitFlash > 0f) hitFlash -= dt
        if (msgT > 0f) msgT -= dt
        status = "Adversário ${boxer.health.toInt()}%  ·  você ${max(0, boxer.playerHealth.toInt())}%" + if (msgT > 0f) "  ·  $lastMsg" else ""
        if (boxer.state == Boxer.State.DOWN) { over = true; status = "Nocaute! Você venceu"; host.play(host.sWin, boxer.x, 1.5f, boxer.z) }
        else if (boxer.playerHealth <= 0f) { over = true; status = "Você foi a nocaute. Tente de novo" }
    }

    override fun draw() {
        val yaw = kotlin.math.atan2(host.head.x - boxer.x, host.head.z - boxer.z)
        val m = host.model("chars/character-male-b")
        // scaled to 1.78 m tall
        val h = m?.let { it.boundsMax[1] - it.boundsMin[1] }?.takeIf { it > 0.01f } ?: 1f
        val stunned = boxer.state == Boxer.State.STUNNED || boxer.state == Boxer.State.DOWN
        host.drawModel("chars/character-male-b", boxer.x, if (boxer.state == Boxer.State.DOWN) -0.6f else 0f, boxer.z, yaw = yaw, scale = 1.78f / h,
            pitch = if (boxer.state == Boxer.State.DOWN) -1.4f else 0f, tint = if (stunned) floatArrayOf(1f, 0.7f, 0.7f, 1f) else null)
        // its gloves: one guarding, the punching one flying at where your head was
        val fx = sin(yaw); val fz = cos(yaw)
        for (side in 0..1) {
            val sx = if (side == 0) -0.18f else 0.18f
            var gx = boxer.x + fx * 0.3f + fz * sx; var gy = boxer.headY - 0.12f; var gz = boxer.z + fz * 0.3f - fx * sx
            if (side == boxer.punchHand) {
                val e = boxer.punchExtent().coerceIn(-0.3f, 1f)
                gx += (boxer.targetX - gx) * max(0f, e); gy += (boxer.targetY - 0.05f - gy) * max(0f, e); gz += (boxer.targetZ - gz) * max(0f, e)
                if (e < 0f) { gx -= fx * e * 0.4f; gz -= fz * e * 0.4f }
            }
            host.drawSphere(gx, gy, gz, 0.075f, floatArrayOf(0.15f, 0.25f, 0.85f, 1f), gloss = 0.7f)
        }
        // health bar above it
        host.drawBox(boxer.x, 2.05f, boxer.z, 0.6f * boxer.health / 100f, 0.04f, 0.01f, floatArrayOf(1f - boxer.health / 100f, boxer.health / 100f, 0.2f, 1f), yaw = yaw, unlit = true)
        // your gloves
        for (g in host.hands) if (g.usable) host.drawSphere(g.palm.x, g.palm.y, g.palm.z, 0.07f, floatArrayOf(0.85f, 0.12f, 0.12f, 1f), gloss = 0.7f)
        // the ring floor and ropes
        host.drawBox(0f, 0.005f, -0.6f, 3.6f, 0.01f, 3.6f, floatArrayOf(0.22f, 0.3f, 0.55f, 1f))
        for (r in 0..2) for ((x, z, sx, sz) in listOf(floatArrayOf(0f, -2.4f, 3.6f, 0.03f), floatArrayOf(0f, 1.2f, 3.6f, 0.03f), floatArrayOf(-1.8f, -0.6f, 0.03f, 3.6f), floatArrayOf(1.8f, -0.6f, 0.03f, 3.6f)))
            host.drawBox(x, 0.5f + r * 0.35f, z, sx, 0.03f, sz, floatArrayOf(0.9f, 0.9f, 0.95f, 1f))
        if (hitFlash > 0f) host.drawSphere(host.head.x, host.head.y, host.head.z, 0.3f, floatArrayOf(1f, 0.1f, 0.1f, hitFlash), unlit = true)
    }
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
    override val sky = floatArrayOf(0.55f, 0.75f, 0.95f, 1f)

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

    override fun draw() {
        val ground = -height
        // the street and the city around, far below
        host.drawBox(0f, ground - 0.05f, 0f, 400f, 0.1f, 400f, floatArrayOf(0.35f, 0.36f, 0.38f, 1f))
        val rnd = java.util.Random(4)
        for (i in -4..4) for (j in -4..4) {
            if (abs(i) < 1 && abs(j) < 1) continue
            val k = rnd.nextInt(5)
            val name = if (abs(i) + abs(j) < 4) "city/building-skyscraper-${"abcde"[k]}" else "city/low-detail-building-${"abcd"[k % 4]}"
            val s = 10f + rnd.nextFloat() * 8f
            host.drawModel(name, i * 18f, ground, j * 18f - 4f, yaw = (rnd.nextInt(4) * Math.PI / 2).toFloat(), scale = s)
        }
        // our building: a tall column under the elevator
        host.drawBox(0f, ground + (height - 0.1f) / 2, 0.2f + walk, 4f, height - 0.1f, 4f, floatArrayOf(0.62f, 0.64f, 0.7f, 1f))
        // the elevator car (floor, walls, the door at the front)
        // the pinch walk moves the building towards you: everything fixed to it shifts by [walk]
        val wz = walk
        val wall = floatArrayOf(0.45f, 0.45f, 0.5f, 1f)
        host.drawBox(0f, -0.05f, 0.1f + wz, 1.4f, 0.1f, 1.4f, floatArrayOf(0.3f, 0.3f, 0.32f, 1f))
        host.drawBox(-0.7f, 1.2f, 0.1f + wz, 0.05f, 2.4f, 1.4f, wall); host.drawBox(0.7f, 1.2f, 0.1f + wz, 0.05f, 2.4f, 1.4f, wall)
        host.drawBox(0f, 1.2f, 0.8f + wz, 1.4f, 2.4f, 0.05f, wall); host.drawBox(0f, 2.42f, 0.1f + wz, 1.4f, 0.05f, 1.4f, wall)
        val open = when (phase) { Phase.RIDE -> 0f; Phase.OPEN -> (t / 1.5f).coerceIn(0f, 1f); else -> 1f }
        host.drawBox(-0.35f - open * 0.6f, 1.2f, -0.6f + wz, 0.7f, 2.4f, 0.04f, floatArrayOf(0.7f, 0.7f, 0.75f, 1f))
        host.drawBox(0.35f + open * 0.6f, 1.2f, -0.6f + wz, 0.7f, 2.4f, 0.04f, floatArrayOf(0.7f, 0.7f, 0.75f, 1f))
        // the plank, sticking out over the street (moves with the pinch walk)
        if (phase != Phase.RIDE) host.drawBox(0f, -0.03f, -2.3f + walk, 0.26f, 0.05f, 3.4f, floatArrayOf(0.62f, 0.45f, 0.28f, 1f))
    }
}

// ================================================================================ 5. Minigolfe
/** Three holes on your floor: pinch to hold the putter, swing gently to putt. */
class GolfGame(host: GameHost) : QuestGame(host) {
    override val title = "Minigolfe"
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
        if (ball.inCup && wait <= 0f) {
            wait = 2.2f
            total += strokes
            score = total
            host.play(host.sCoin, h.cupX, 0f, h.cupZ)
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

    override fun draw() {
        val h = GolfCourses.holes[holeIndex]
        for (f in floors[holeIndex]) host.drawBox(f[0], 0.005f, f[1], f[2], 0.01f, f[3], floatArrayOf(0.25f, 0.62f, 0.3f, 1f), gloss = 0.05f)
        val w = h.walls
        for (i in 0 until w.size / 4) {
            val ax = w[i * 4]; val az = w[i * 4 + 1]; val bx = w[i * 4 + 2]; val bz = w[i * 4 + 3]
            val len = kotlin.math.hypot(bx - ax, bz - az)
            host.drawBox((ax + bx) / 2, 0.04f, (az + bz) / 2, len + 0.04f, 0.08f, 0.04f, floatArrayOf(0.62f, 0.42f, 0.25f, 1f), yaw = kotlin.math.atan2(-(bz - az), bx - ax))
        }
        for (b in 0 until h.boxes.size / 4) host.drawBox(h.boxes[b * 4], 0.06f, h.boxes[b * 4 + 1], h.boxes[b * 4 + 2] * 2, 0.12f, h.boxes[b * 4 + 3] * 2, floatArrayOf(0.85f, 0.75f, 0.3f, 1f))
        host.drawCylinder(h.cupX, 0.006f, h.cupZ, GolfBall.CUP_RADIUS, 0.004f, floatArrayOf(0.02f, 0.02f, 0.02f, 1f))
        host.drawModel("golf/flag-red", h.cupX, 0f, h.cupZ, scale = 0.6f)
        if (holeIndex == 2) host.drawModel("golf/windmill", 0f, 0f, -3.9f, scale = 0.8f)
        host.drawSphere(ball.x, ball.radius, ball.z, ball.radius, WHITE, gloss = 0.6f)
        club()?.let { c ->
            val g = host.hands.first { it.usable && it.pinch }
            host.drawBeam(g.palm.x, g.palm.y, g.palm.z, c[0], 0.03f, c[1], 0.008f, floatArrayOf(0.75f, 0.75f, 0.8f, 1f), unlit = false)
            host.drawBox(c[0], 0.025f, c[1], 0.1f, 0.03f, 0.025f, floatArrayOf(0.3f, 0.3f, 0.32f, 1f))
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
        else -> null
    }
}
