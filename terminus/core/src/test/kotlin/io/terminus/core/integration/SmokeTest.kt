package io.terminus.core.integration

import io.terminus.core.game.GameEvent
import io.terminus.core.game.PersonalityAssigner
import io.terminus.core.game.Player
import io.terminus.core.game.PlayerId
import io.terminus.core.game.Role
import io.terminus.core.game.ScoreKeeper
import io.terminus.core.persistence.RoundRecord
import io.terminus.core.persistence.TerminusJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ARCHITECTURE.md §6 item 8 smoke test: a full AI-hider vs 2-AI-seeker game on
 * Demoville, sim mode, stepped clock, seed 42 — terminates (capture or expiry)
 * within 45 sim-minutes, produces a valid `RoundRecord` (GAME_DESIGN.md §7 math,
 * personalities revealed), and is byte-identically reproducible per seed. Plus the
 * 3-round match: role rotation and §7 tiebreaks through `MatchRecord`.
 */
class SmokeTest {

    private val hider = PlayerId("ai-1")
    private val seekers = listOf(PlayerId("ai-2"), PlayerId("ai-3"))

    @Test
    fun `seed 42 round terminates within 45 sim-minutes with a valid RoundRecord`() {
        val config = AiMatchHarness.smokeConfig(seed = 42L)
        val result = AiMatchHarness.runAiMatch(config)
        val record = result.records.single()
        val finalState = result.finalStates.single()

        // Termination: capture or expiry inside the 45 sim-minute game (§6 item 8).
        assertTrue(record.durationMillis <= 45 * 60_000L, "round ran past 45 sim-minutes")
        assertTrue(finalState.eventLog.last() is GameEvent.RoundEnded)

        // Identity and §7 role bookkeeping.
        assertEquals(0, record.roundIndex)
        assertEquals(42L, record.seed)
        assertEquals(hider, record.hiderId)
        assertEquals(Role.HIDER, record.roles[hider])
        (seekers + PlayerId("human")).forEach { assertEquals(Role.SEEKER, record.roles[it]) }
        assertNotNull(record.hidingStationId, "the AI hider never settled into a hiding zone")
        assertNotEquals(config.startStationId, record.hidingStationId)

        // Personalities: drawn by the real PersonalityAssigner from the round seed,
        // without replacement, and persisted for the end-screen reveal (§6.4).
        val expectedPersonalities =
            PersonalityAssigner.assign(finalState.players, config, roundSeed = 42L)
        assertEquals(expectedPersonalities, record.aiPersonalities)
        assertEquals(3, record.aiPersonalities.size)
        assertEquals(3, record.aiPersonalities.values.distinct().size)

        // Capture bookkeeping is internally consistent (§5.3).
        if (record.captured) {
            assertTrue(record.capturedBy in seekers, "captured by ${record.capturedBy}")
            assertNotNull(record.captureGameMillis)
            assertEquals(record.captureGameMillis, record.durationMillis)
        } else {
            assertNull(record.capturedBy)
            assertNull(record.captureGameMillis)
        }

        // Score math (§7): survival + bonus + penalty + never-captured flat bonus.
        assertEquals(
            ScoreKeeper.roundScore(
                survivalMinutes = record.survivalMinutes,
                bonusMinutes = record.bonusMinutes,
                penaltyMinutes = record.penaltyMinutes,
                captured = record.captured,
                graceFailed = false,
                hidingPhaseMinutes = config.hidingPhaseMinutes,
            ),
            record.hiderScore,
            1e-9,
        )
        assertTrue(record.survivalMinutes >= 0.0)
        assertTrue(record.survivalMinutes <= (45 - 10).toDouble())
        assertEquals(10 * 60_000L, record.hidingPhaseMillis)

        // Tiebreak stats line up with the event log (§7).
        assertEquals(
            record.events.count { it is GameEvent.AnswerDelivered },
            record.questionsAnswered,
        )
        assertEquals(record.events, finalState.eventLog)
        assertEquals(mapOf(hider to record.hiderScore), finalState.matchScores)
    }

    @Test
    fun `two seed-42 runs are byte-identical and a different seed diverges`() {
        val first = AiMatchHarness.runAiMatch(AiMatchHarness.smokeConfig(seed = 42L))
        val second = AiMatchHarness.runAiMatch(AiMatchHarness.smokeConfig(seed = 42L))
        val other = AiMatchHarness.runAiMatch(AiMatchHarness.smokeConfig(seed = 4242L))

        val firstLog = AiMatchHarness.eventLogJson(first.records.single().events)
        val secondLog = AiMatchHarness.eventLogJson(second.records.single().events)
        val otherLog = AiMatchHarness.eventLogJson(other.records.single().events)

        assertEquals(firstLog, secondLog, "same seed must reproduce the event log byte-for-byte")
        assertEquals(
            TerminusJson.json.encodeToString(first.records),
            TerminusJson.json.encodeToString(second.records),
        )
        assertNotEquals(firstLog, otherLog, "a different seed must produce a different game")
    }

    @Test
    fun `three-round match rotates the hider role and applies the section-7 tiebreaks`() {
        val config = AiMatchHarness.smokeConfig(seed = 42L, rounds = 3)
        val result = AiMatchHarness.runAiMatch(config)
        val match = result.match

        // One record per round, hider role rotating through the AIs (§7: the human
        // seeker is at rotation index 0 and is skipped by a 3-round, 4-player match).
        assertEquals(listOf(0, 1, 2), result.records.map(RoundRecord::roundIndex))
        assertEquals(listOf(hider, seekers[0], seekers[1]), result.records.map(RoundRecord::hiderId))
        assertEquals(result.records, match.rounds)

        // Round seeds are seed + roundIndex; each round re-draws personalities.
        result.records.forEachIndexed { index, record ->
            assertEquals(42L + index, record.seed)
            assertEquals(
                PersonalityAssigner.assign(match.players, config, roundSeed = record.seed),
                record.aiPersonalities,
            )
        }

        // Match totals are each player's summed hider-round scores (§7).
        assertEquals(ScoreKeeper.totalScores(match.players, match.rounds), match.totalScores)
        assertEquals(0.0, match.totalScores[PlayerId("human")])

        // Winner honors the §7 tiebreak chain (computed independently here).
        val expectedWinner = match.players.minWithOrNull(
            compareByDescending<Player> { match.totalScores[it.id] ?: 0.0 }
                .thenBy { p -> match.rounds.filter { it.hiderId == p.id }.sumOf { it.questionsAnswered } }
                .thenBy { p -> match.rounds.filter { it.hiderId == p.id }.sumOf { it.cardsPlayed } },
        )?.id
        assertNotNull(match.winnerId)
        assertEquals(expectedWinner, match.winnerId)
    }
}
