package io.terminus.core.game

import io.terminus.core.cards.CardType
import io.terminus.core.geo.GeoMath
import io.terminus.core.persistence.RoundRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §7 scoring: survival + bonus + penalty + never-captured, and tiebreaks. */
class ScoringTest {

    @Test
    fun `surviving hider scores survival plus bonuses plus the flat 10`() {
        val h = EngineHarness(EngineHarness.simConfig()) // 20 min game, 10 min hiding
        h.start()
        h.hideAtAndStartSeeking("DV-A10")
        h.giveHand(CardType.RUSH_HOUR_DELAY, CardType.EXPRESS_SKIP)
        h.playCard(h.humanId, CardType.RUSH_HOUR_DELAY) // +3:00
        h.playCard(h.humanId, CardType.EXPRESS_SKIP) // +5:00
        assertEquals(8.0, h.state.bonusMinutes)

        h.tick(10 * 60_000L) // run to clock expiry
        assertEquals(GamePhase.ROUND_END, h.state.phase)
        val ended = h.events.filterIsInstance<GameEvent.RoundEnded>().single()
        // 10.0 survival + 8.0 bonus + 10.0 never captured.
        assertEquals(28.0, ended.hiderScore)
        assertEquals(28.0, h.state.matchScores[h.humanId])

        val record = h.engine.buildRoundRecord(h.state)
        assertEquals(10.0, record.survivalMinutes)
        assertEquals(8.0, record.bonusMinutes)
        assertEquals(2, record.cardsPlayed)
        assertEquals(false, record.captured)
        assertTrue(record.aiPersonalities.isNotEmpty()) // end-screen reveal data
    }

    @Test
    fun `human seeker leaving the start station during hiding costs five minutes`() {
        val h = EngineHarness(EngineHarness.gpsConfig(humanRole = Role.SEEKER, hidingPhaseMinutes = 5))
        h.start()
        val start = h.stationLatLng("DV-A07")
        h.gpsFix(h.humanId, GeoMath.destinationPoint(start, 45.0, 100.0), 10_000L)
        assertEquals(0.0, h.state.penaltyMinutes) // inside 150 m: fine

        val emitted = h.gpsFix(h.humanId, GeoMath.destinationPoint(start, 45.0, 200.0), 20_000L)
        assertTrue(emitted.any { it is GameEvent.ViolationDetected })
        assertTrue(emitted.any { it is GameEvent.PenaltyApplied && it.minutes == 5.0 })
        assertEquals(5.0, h.state.penaltyMinutes)

        // Penalized once, not per fix.
        h.gpsFix(h.humanId, GeoMath.destinationPoint(start, 45.0, 300.0), 30_000L)
        assertEquals(5.0, h.state.penaltyMinutes)
    }

    @Test
    fun `stalled train violation by the human seeker adds penalty minutes and restarts the timer`() {
        val h = EngineHarness(EngineHarness.gpsConfig(humanRole = Role.SEEKER, hidingPhaseMinutes = 5))
        h.start()
        val start = h.stationLatLng("DV-A07")
        h.gpsFix(h.humanId, start, 0L)
        h.tick(5 * 60_000L) // SEEKING; AI hider in grace

        h.giveHand(CardType.STALLED_TRAIN) // the AI hider's hand
        h.playCard(h.ai1, CardType.STALLED_TRAIN)
        val effect = h.state.activeEffects.single { it.type == CardType.STALLED_TRAIN }
        val originalExpiry = checkNotNull(effect.expiryGameMillis)

        // The human seeker moves 200 m from the play-time anchor: violation (+2:00).
        val t0 = h.state.gameTimeMillis
        h.gpsFix(h.humanId, GeoMath.destinationPoint(start, 90.0, 200.0), t0 + 4_000L)
        h.tick(5_000L) // 5 s GPS check tick
        assertEquals(2.0, h.state.penaltyMinutes)
        assertTrue(h.events.any { it is GameEvent.PenaltyApplied && it.minutes == 2.0 })
        val restarted = h.state.activeEffects.single { it.type == CardType.STALLED_TRAIN }
        assertTrue(checkNotNull(restarted.expiryGameMillis) > originalExpiry) // timer restart (§4.1)
        assertEquals(1, restarted.restartsUsed)
    }

    @Test
    fun `score keeper math and tiebreaks`() {
        assertEquals(6.3, ScoreKeeper.roundToTenth(6.25))
        assertEquals(
            28.0,
            ScoreKeeper.roundScore(10.0, 8.0, 0.0, captured = false, graceFailed = false, hidingPhaseMinutes = 10),
        )
        assertEquals(
            10.0,
            ScoreKeeper.roundScore(10.0, 8.0, 0.0, captured = false, graceFailed = true, hidingPhaseMinutes = 10),
        )

        val a = Player.HumanPlayer(PlayerId("a"), "A")
        val b = Player.AiPlayer(PlayerId("b"), "B", Difficulty.MEDIUM)
        fun round(idx: Int, hider: PlayerId, score: Double, questions: Int, cards: Int) = RoundRecord(
            roundIndex = idx, seed = 1L, hiderId = hider,
            roles = emptyMap(), captured = false, hiderScore = score,
            survivalMinutes = score, bonusMinutes = 0.0, penaltyMinutes = 0.0,
            questionsAnswered = questions, cardsPlayed = cards,
            hidingPhaseMillis = 0L, durationMillis = 0L,
        )
        // Equal totals: fewer questions answered in one's hider rounds wins.
        val winner = ScoreKeeper.winner(
            listOf(a, b),
            listOf(round(0, a.id, 12.0, 4, 1), round(1, b.id, 12.0, 2, 5)),
        )
        assertEquals(b.id, winner)
        // Equal totals and questions: fewer cards played wins.
        val winner2 = ScoreKeeper.winner(
            listOf(a, b),
            listOf(round(0, a.id, 12.0, 3, 1), round(1, b.id, 12.0, 3, 5)),
        )
        assertEquals(a.id, winner2)
    }
}
