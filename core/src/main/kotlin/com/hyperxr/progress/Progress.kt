package com.hyperxr.progress

import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.random.Random

/** Things the player does that count for XP, missions and achievements. */
sealed class Activity {
    data class PlayedMinute(val gameId: String) : Activity()
    data class StartedGame(val gameId: String) : Activity()
    data class Score(val gameId: String, val points: Int) : Activity()
    object SentMessage : Activity()
    object MadeCall : Activity()
    object AddedFriend : Activity()
    object OpenedStore : Activity()
    object OpenedXr : Activity()
}

@Serializable
data class Mission(val id: String, val title: String, val goal: Int, val xp: Int, val progress: Int = 0, val claimed: Boolean = false) {
    val done: Boolean get() = progress >= goal
}

@Serializable
data class ProgressState(
    val xp: Int = 0,
    val day: String = "",
    val missions: List<Mission> = emptyList(),
    val streak: Int = 0,
    val lastXrDay: String = "",
    val achievements: Set<String> = emptySet(),
    val gamesPlayed: Set<String> = emptySet(),
    val minutesTotal: Int = 0,
    val todayGames: Set<String> = emptySet(),
    val bestScores: Map<String, Int> = emptyMap(),
    val friendsAdded: Int = 0,
    val calls: Int = 0,
)

/** What changed after an activity, for notices in the headset. */
data class Outcome(val xpGained: Int, val levelUp: Int?, val missionsDone: List<Mission>, val achievements: List<Achievement>)

data class Achievement(val id: String, val title: String, val description: String, val xp: Int)

/**
 * Why come back tomorrow: XP and levels from everything you do in HyperMagic, three daily missions
 * that change at midnight, a streak of days in a row, and achievements. Pure logic (the app saves
 * the state and shows the notices).
 */
object Progression {
    /** XP needed to go from [level] to the next one. */
    fun xpForLevel(level: Int) = 100 + 60 * (level - 1)

    fun level(xp: Int): Int {
        var l = 1; var left = xp
        while (left >= xpForLevel(l)) { left -= xpForLevel(l); l++ }
        return l
    }

    /** XP inside the current level and the size of the level, for the bar. */
    fun levelProgress(xp: Int): Pair<Int, Int> {
        var l = 1; var left = xp
        while (left >= xpForLevel(l)) { left -= xpForLevel(l); l++ }
        return left to xpForLevel(l)
    }

    val ACHIEVEMENTS = listOf(
        Achievement("first_game", "Primeiro jogo", "Jogue qualquer jogo", 50),
        Achievement("five_games", "Explorador", "Jogue 5 jogos diferentes", 150),
        Achievement("ten_games", "Colecionador", "Jogue 10 jogos diferentes", 300),
        Achievement("hour", "Uma hora em VR", "60 minutos jogando no total", 200),
        Achievement("ten_hours", "Veterano", "10 horas jogando no total", 800),
        Achievement("streak3", "Três dias seguidos", "Entre no HyperMagic 3 dias seguidos", 120),
        Achievement("streak7", "Uma semana", "7 dias seguidos", 400),
        Achievement("streak30", "Fiel", "30 dias seguidos", 2000),
        Achievement("friend", "Primeira amizade", "Adicione um amigo", 80),
        Achievement("call", "Alô", "Faça uma chamada", 80),
        Achievement("level5", "Nível 5", "Chegue ao nível 5", 0),
        Achievement("level10", "Nível 10", "Chegue ao nível 10", 0),
        Achievement("missions", "Dia completo", "Termine as três missões do dia", 100),
    )

    private class Template(val id: String, val title: (Int) -> String, val goals: IntArray, val xpPer: Int)

    private val TEMPLATES = listOf(
        Template("minutes", { "Jogue $it minutos" }, intArrayOf(10, 15, 20), 4),
        Template("games", { "Jogue $it jogos diferentes" }, intArrayOf(2, 3), 40),
        Template("message", { "Mande $it mensagem" + if (it > 1) "ns para amigos" else " para um amigo" }, intArrayOf(1, 3), 30),
        Template("store", { "Abra a Loja e veja um jogo novo" }, intArrayOf(1), 30),
        Template("score", { "Faça $it pontos num jogo" }, intArrayOf(50, 100, 200), 1),
        Template("call", { "Ligue para um amigo" }, intArrayOf(1), 60),
    )

    /** The three missions of [day], the same on every phone for that date. */
    fun missionsFor(day: LocalDate): List<Mission> {
        val rnd = Random(day.toEpochDay())
        return TEMPLATES.shuffled(rnd).take(3).map { t ->
            val goal = t.goals[rnd.nextInt(t.goals.size)]
            Mission(t.id, t.title(goal), goal, (t.xpPer * goal).coerceIn(30, 120))
        }
    }

    /** New day: fresh missions and today's counters. */
    fun rollDay(s: ProgressState, today: LocalDate): ProgressState =
        if (s.day == today.toString()) s else s.copy(day = today.toString(), missions = missionsFor(today), todayGames = emptySet())

    fun apply(s0: ProgressState, a: Activity, today: LocalDate): Pair<ProgressState, Outcome> {
        var s = rollDay(s0, today)
        val levelBefore = level(s.xp)
        var xp = 0
        fun bump(id: String, by: Int) {
            s = s.copy(missions = s.missions.map { m -> if (m.id == id && !m.done) m.copy(progress = minOf(m.goal, m.progress + by)) else m })
        }
        when (a) {
            is Activity.PlayedMinute -> { xp += 3; s = s.copy(minutesTotal = s.minutesTotal + 1); bump("minutes", 1) }
            is Activity.StartedGame -> {
                if (a.gameId !in s.gamesPlayed) xp += 20
                s = s.copy(gamesPlayed = s.gamesPlayed + a.gameId, todayGames = s.todayGames + a.gameId)
                s = s.copy(missions = s.missions.map { m -> if (m.id == "games" && !m.done) m.copy(progress = minOf(m.goal, s.todayGames.size)) else m })
            }
            is Activity.Score -> {
                val best = s.bestScores[a.gameId] ?: 0
                if (a.points > best) { xp += ((a.points - best) / 10).coerceAtMost(50); s = s.copy(bestScores = s.bestScores + (a.gameId to a.points)) }
                s = s.copy(missions = s.missions.map { m -> if (m.id == "score" && !m.done) m.copy(progress = maxOf(m.progress, minOf(m.goal, a.points))) else m })
            }
            Activity.SentMessage -> { xp += 2; bump("message", 1) }
            Activity.MadeCall -> { xp += 10; s = s.copy(calls = s.calls + 1); bump("call", 1) }
            Activity.AddedFriend -> { xp += 15; s = s.copy(friendsAdded = s.friendsAdded + 1) }
            Activity.OpenedStore -> bump("store", 1)
            Activity.OpenedXr -> {
                if (s.lastXrDay != today.toString()) {
                    val yesterday = today.minusDays(1).toString()
                    val streak = if (s.lastXrDay == yesterday) s.streak + 1 else 1
                    xp += 10 + 5 * minOf(streak, 10)  // coming back pays more each day
                    s = s.copy(streak = streak, lastXrDay = today.toString())
                }
            }
        }
        // finished missions pay once
        val done = s.missions.filter { it.done && !it.claimed }
        if (done.isNotEmpty()) {
            xp += done.sumOf { it.xp }
            s = s.copy(missions = s.missions.map { if (it.done) it.copy(claimed = true) else it })
        }
        s = s.copy(xp = s.xp + xp)
        // achievements (their XP can unlock level ones, so twice)
        val earned = ArrayList<Achievement>()
        repeat(2) {
            for (ach in ACHIEVEMENTS) if (ach.id !in s.achievements && earnedNow(ach.id, s)) {
                earned += ach
                s = s.copy(achievements = s.achievements + ach.id, xp = s.xp + ach.xp)
            }
        }
        val levelAfter = level(s.xp)
        return s to Outcome(s.xp - s0.xp, if (levelAfter > levelBefore) levelAfter else null, done, earned)
    }

    private fun earnedNow(id: String, s: ProgressState): Boolean = when (id) {
        "first_game" -> s.gamesPlayed.isNotEmpty()
        "five_games" -> s.gamesPlayed.size >= 5
        "ten_games" -> s.gamesPlayed.size >= 10
        "hour" -> s.minutesTotal >= 60
        "ten_hours" -> s.minutesTotal >= 600
        "streak3" -> s.streak >= 3
        "streak7" -> s.streak >= 7
        "streak30" -> s.streak >= 30
        "friend" -> s.friendsAdded >= 1
        "call" -> s.calls >= 1
        "level5" -> level(s.xp) >= 5
        "level10" -> level(s.xp) >= 10
        "missions" -> s.missions.size == 3 && s.missions.all { it.claimed }
        else -> false
    }
}
