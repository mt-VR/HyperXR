package com.hyperxr

import com.hyperxr.questgames.BeatChart
import com.hyperxr.questgames.BeatMusic
import com.hyperxr.questgames.Boxer
import com.hyperxr.questgames.Diorama
import com.hyperxr.questgames.Drone
import com.hyperxr.questgames.FishingLine
import com.hyperxr.questgames.FruitThrower
import com.hyperxr.questgames.GolfBall
import com.hyperxr.questgames.GolfCourses
import com.hyperxr.questgames.Note
import com.hyperxr.questgames.PingPongBall
import com.hyperxr.questgames.SliceJudge
import com.hyperxr.questgames.SlowTime
import com.hyperxr.questgames.raySphere
import com.hyperxr.questgames.segmentSphere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestGamesTest {
    @Test fun chartAlternatesHandsAndFlows() {
        val notes = BeatChart.generate(128f, 90f, 1)
        assertTrue(notes.size in 120..400)
        assertTrue(notes.zipWithNext().all { (a, b) -> b.time >= a.time })
        // left hand blocks on the left lanes, right on the right
        assertTrue(notes.all { (it.hand == 0) == (it.lane < 2) })
        // a hand never cuts down twice in a row (the swing comes back up)
        for (h in 0..1) {
            val dirs = notes.filter { it.hand == h }.map { it.dir }.filter { it == Note.UP || it == Note.DOWN }
            assertTrue(dirs.zipWithNext().count { (a, b) -> a == b && a == Note.DOWN } < dirs.size / 10)
        }
    }

    @Test fun sliceNeedsSpeedAndDirection() {
        // a downward swing through a block at (0, 1.2, -0.6)
        assertTrue(SliceJudge.judge(0f, 1.45f, -0.6f, 0f, 0.95f, -0.6f, 0.1f, 0f, 1.2f, -0.6f, 0.12f, Note.DOWN) > 0)
        assertEquals(0, SliceJudge.judge(0f, 1.45f, -0.6f, 0f, 0.95f, -0.6f, 0.1f, 0f, 1.2f, -0.6f, 0.12f, Note.UP))   // wrong way
        assertEquals(0, SliceJudge.judge(0f, 1.25f, -0.6f, 0f, 1.15f, -0.6f, 0.2f, 0f, 1.2f, -0.6f, 0.12f, Note.DOWN))  // too slow
        assertEquals(0, SliceJudge.judge(0.5f, 1.45f, -0.6f, 0.5f, 0.95f, -0.6f, 0.1f, 0f, 1.2f, -0.6f, 0.12f, Note.DOWN)) // missed
    }

    @Test fun musicIsAudibleAndBounded() {
        val m = BeatMusic()
        val buf = FloatArray(48000 * 2 * 2)
        m.render(buf, 48000 * 2)
        var sum = 0.0; var peak = 0f
        for (v in buf) { assertTrue(v.isFinite()); sum += v * v; peak = maxOf(peak, kotlin.math.abs(v)) }
        assertTrue(kotlin.math.sqrt(sum / buf.size) > 0.05)
        assertTrue(peak < 1f)
        assertEquals(2f, m.time, 1e-3f)
    }

    @Test fun fruitRisesInReachAndIsCutBySwipes() {
        val rnd = java.util.Random(1)
        repeat(50) {
            val f = FruitThrower.toss(rnd, 8, 0f)
            var top = 0f
            repeat(200) { f.step(0.01f); top = maxOf(top, f.y) }
            assertTrue(top in 1.2f..2.0f)
        }
        val f = FruitThrower.toss(rnd, 8, 0f).also { it.x = 0f; it.y = 1.3f; it.z = -0.8f }
        assertTrue(FruitThrower.sliced(f, -0.2f, 1.3f, -0.8f, 0.2f, 1.3f, -0.8f, 0.1f))
        assertFalse(FruitThrower.sliced(f, -0.2f, 1.3f, -0.8f, 0.2f, 1.3f, -0.8f, 1f))   // too slow
    }

    @Test fun boxerPunchesCanBeSlippedOrBlocked() {
        fun fight(dodge: Boolean, guard: Boolean): Float {
            val b = Boxer(1)
            var hx = 0f
            repeat(400) {
                // dodge: move the head sideways during the wind-up
                if (dodge && b.state == Boxer.State.WINDUP) hx = 0.3f else if (b.state == Boxer.State.MOVE) hx = 0f
                b.update(0.02f, hx, 1.6f, 0f, guard)
            }
            return b.playerHealth
        }
        val open = fight(false, false)
        assertTrue(open < 80f)
        assertTrue(fight(false, true) > open)
        assertEquals(100f, fight(true, false), 1e-3f)
        val b = Boxer(2)
        repeat(30) { b.hit(4f, true) }
        assertEquals(Boxer.State.DOWN, b.state)
        assertEquals(0f, Boxer(3).hit(1f, true), 0f)      // a tap is not a punch
    }

    @Test fun golfBallBouncesAndDropsIn() {
        val hole = GolfCourses.holes[0]
        val ball = GolfBall().also { it.x = hole.teeX; it.z = hole.teeZ }
        // a putt straight at the cup at a sensible speed
        assertTrue(ball.putt(0f, -0.638f, 0f, -0.671f, 0.025f))
        repeat(1000) { ball.step(hole, 0.01f) }
        assertTrue(ball.inCup)
        // into a side wall: it bounces back and stays in the lane
        val b2 = GolfBall().also { it.x = 0f; it.z = -1f; it.vx = 2f }
        repeat(300) { b2.step(hole, 0.01f) }
        assertTrue(b2.x < 0.3f && b2.x > -0.3f)
        // a ball too fast rolls over the cup
        val b3 = GolfBall().also { it.x = 0f; it.z = -2.5f; it.vz = -3.5f }
        b3.step(hole, 0.05f)
        assertFalse(b3.inCup)
    }

    @Test fun timeFollowsMotion() {
        assertEquals(0.05f, SlowTime.scale(0f, 0f), 1e-6f)
        assertTrue(SlowTime.scale(0.5f, 0f) > 0.5f)
        assertEquals(1f, SlowTime.scale(2f, 3f), 1e-6f)
    }

    @Test fun pingPongReturnLandsOnTheTable() {
        val b = PingPongBall().also { it.x = 0f; it.y = 0.95f; it.z = PingPongBall.TABLE_FAR - 0.2f; it.inPlay = true }
        b.opponentReturn(0.2f, PingPongBall.TABLE_Z + 0.8f, 0.9f)
        var landed = false
        repeat(200) {
            b.step(0.01f)
            if (b.bouncesPlayerSide == 1) landed = true
        }
        assertTrue(landed)
        // a paddle facing the opponent sends a ball coming at it back
        val c = PingPongBall().also { it.x = 0f; it.y = 1.0f; it.z = -0.3f; it.vz = 3f; it.inPlay = true }
        assertTrue(c.paddleHit(0f, 1f, -0.29f, 0f, 0f, -1f, 0f, 0f, -1f))
        assertTrue(c.vz < -1f)
        assertTrue(c.lastHitByPlayer)
    }

    @Test fun fishingFromCastToCatch() {
        val f = FishingLine(3)
        f.cast(0f, 1.5f, -0.5f, 0f, 2f, -6f)
        var msg: String? = null
        var t = 0f
        while (f.state != FishingLine.State.BITE && t < 30f) { f.update(0.02f, 0f, -0.3f, false, false); t += 0.02f }
        assertEquals(FishingLine.State.BITE, f.state)
        assertTrue(f.distance > 3f)
        msg = f.update(0.02f, 0f, -0.3f, false, true)
        assertEquals(FishingLine.State.HOOKED, f.state)
        // reel in pulses (let go when the tension is high): the fish comes in without the line breaking
        t = 0f
        while (f.state == FishingLine.State.HOOKED && t < 120f) { f.update(0.02f, 0f, -0.3f, f.tension < 0.6f, false); t += 0.02f }
        assertEquals(FishingLine.State.CAUGHT, f.state)
        assertNotNull(f.fish)
        // reeling all the time against a strong fish snaps the line
        val g = FishingLine(5)
        g.cast(0f, 1.5f, -0.5f, 0f, 2f, -7f)
        while (g.state != FishingLine.State.BITE) g.update(0.02f, 0f, -0.3f, false, false)
        g.update(0.02f, 0f, -0.3f, false, true)
        var snapped = false
        repeat(20000) { if (g.state == FishingLine.State.HOOKED) { g.update(0.02f, 0f, -0.3f, true, false) }; if (g.state == FishingLine.State.ESCAPED) snapped = true }
        assertTrue(snapped || g.state == FishingLine.State.CAUGHT)
    }

    @Test fun dioramaHeroWalksJumpsAndFalls() {
        val w = Diorama.level1()
        // walk right along the first island
        repeat(30) { w.step(0.02f, 1f, 0f, false) }
        assertTrue(w.x > 2f)
        assertEquals(1f, w.y, 1e-3f)
        // a one-block step blocks walking but a jump climbs it: the bridge of 2s at x = 6
        val d = Diorama.level1().also { it.x = 5.5f; it.z = 2.5f }
        repeat(30) { d.step(0.02f, 1f, 0f, false) }
        assertTrue(d.x < 6.05f)
        repeat(25) { d.step(0.02f, 1f, 0f, it == 0) }
        assertTrue(d.x > 6.2f && d.y >= 2f - 1e-3f)
        // walking off into the gap at x = 4 (row 0) falls back to the start
        val e = Diorama.level1()
        var fell = false
        repeat(300) { if (e.step(0.02f, 1f, 0f, false)?.startsWith("Caiu") == true) fell = true }
        assertTrue(fell)
        assertTrue(e.falls >= 1)
    }

    @Test fun dronesFlyInFrontAndRaysHitThem() {
        val d = Drone(1)
        repeat(500) { d.step(0.02f); assertTrue(d.z < 0f) }
        assertTrue(raySphere(0f, 1.6f, 0f, 0f, 0f, -1f, 0f, 1.6f, -5f, 0.3f) in 4.6f..4.8f)
        assertTrue(raySphere(0f, 1.6f, 0f, 0f, 0f, -1f, 1f, 1.6f, -5f, 0.3f) < 0f)
        assertTrue(segmentSphere(0f, 0f, 0f, 0f, 0f, -2f, 0f, 0f, -1f, 0.1f) in 0.4f..0.5f)
    }
}
