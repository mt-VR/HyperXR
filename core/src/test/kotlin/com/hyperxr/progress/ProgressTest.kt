package com.hyperxr.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProgressTest {
    private val day = LocalDate.of(2026, 10, 28)

    @Test fun levelsGrow() {
        assertEquals(1, Progression.level(0))
        assertEquals(2, Progression.level(100))
        assertEquals(3, Progression.level(100 + 160))
        assertEquals(0 to 100, Progression.levelProgress(0))
    }

    @Test fun missionsAreStablePerDay() {
        assertEquals(Progression.missionsFor(day), Progression.missionsFor(day))
        assertEquals(3, Progression.missionsFor(day).size)
    }

    @Test fun streakCountsConsecutiveDays() {
        var s = ProgressState()
        s = Progression.apply(s, Activity.OpenedXr, day).first
        s = Progression.apply(s, Activity.OpenedXr, day).first  // same day: once
        assertEquals(1, s.streak)
        s = Progression.apply(s, Activity.OpenedXr, day.plusDays(1)).first
        s = Progression.apply(s, Activity.OpenedXr, day.plusDays(2)).first
        assertEquals(3, s.streak)
        assertTrue("streak3" in s.achievements)
        s = Progression.apply(s, Activity.OpenedXr, day.plusDays(5)).first
        assertEquals(1, s.streak)
    }

    @Test fun missionPaysOnceAndFirstGameAchievement() {
        var s = Progression.rollDay(ProgressState(), day)
        val (s2, out) = Progression.apply(s, Activity.StartedGame("climb"), day)
        assertTrue(out.achievements.any { it.id == "first_game" })
        s = s2
        repeat(30) { s = Progression.apply(s, Activity.PlayedMinute("climb"), day).first }
        val minutes = s.missions.firstOrNull { it.id == "minutes" }
        if (minutes != null) { assertTrue(minutes.claimed) }
        assertTrue(s.xp > 0)
    }

    @Test fun levelUpReported() {
        val s = ProgressState(xp = 98, day = day.toString(), missions = Progression.missionsFor(day))
        val out = Progression.apply(s, Activity.PlayedMinute("x"), day).second
        assertNotNull(out.levelUp)
    }
}
