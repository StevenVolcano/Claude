package io.terminus.core.game

import io.terminus.core.cards.Deck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Round setup, role rotation, and personality assignment (GAME_DESIGN.md §2.1, §6.4, §7). */
class GameEngineSetupTest {

    @Test
    fun `start round places everyone at the start station with an empty hider hand`() {
        val h = EngineHarness(EngineHarness.simConfig())
        val emitted = h.start()

        assertEquals(GamePhase.HIDING, h.state.phase)
        assertTrue(
            emitted.any {
                it is GameEvent.PhaseChanged && it.from == GamePhase.SETUP && it.to == GamePhase.HIDING
            },
        )
        for (player in h.state.players) {
            assertEquals(PlayerPosition.NodePosition("DV-A07"), h.state.positions[player.id])
            assertEquals(listOf("DV-A07"), h.state.visitHistory[player.id])
        }
        assertEquals(emptyList(), h.state.hand)
        assertEquals(Deck.TOTAL_CARDS, h.state.deckCount)
        assertEquals(0, h.state.discardCount)
        assertEquals(Role.HIDER, h.state.roles[h.humanId])
        assertEquals(Role.SEEKER, h.state.roles[h.ai1])
    }

    @Test
    fun `personalities are deterministic per seed and never duplicated`() {
        val config = EngineHarness.simConfig(
            seed = 99L,
            aiOpponents = listOf(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD),
        )
        val a = EngineHarness(config).apply { start() }.state.aiPersonalities
        val b = EngineHarness(config).apply { start() }.state.aiPersonalities

        assertEquals(3, a.size)
        assertEquals(a, b) // same seed = same assignment
        assertEquals(a.values.size, a.values.toSet().size) // no duplicates

        val other = EngineHarness(EngineHarness.simConfig(seed = 100L, aiOpponents = config.aiOpponents))
            .apply { start() }.state.aiPersonalities
        // Not guaranteed for every seed pair, but holds for these: different seed, different draw.
        assertNotEquals(a, other)
    }

    @Test
    fun `manual personality mode honors the configured assignment`() {
        val config = EngineHarness.simConfig(aiOpponents = listOf(Difficulty.MEDIUM, Difficulty.MEDIUM))
            .copy(
                aiPersonalityMode = AiPersonalityMode.MANUAL,
                manualPersonalities = listOf(AiPersonality.GHOST, AiPersonality.RAT),
            )
        val h = EngineHarness(config).apply { start() }
        assertEquals(AiPersonality.GHOST, h.state.aiPersonalities[PlayerId("ai-1")])
        assertEquals(AiPersonality.RAT, h.state.aiPersonalities[PlayerId("ai-2")])
    }

    @Test
    fun `hider role rotates through all players across rounds`() {
        val config = EngineHarness.simConfig(rounds = 3, aiOpponents = listOf(Difficulty.MEDIUM, Difficulty.HARD))
        val h = EngineHarness(config)
        h.start()
        assertEquals(h.humanId, h.hiderId) // round 0: humanRole = HIDER

        h.tick(config.gameDurationMinutes * 60_000L) // run round 0 to clock expiry
        assertEquals(GamePhase.ROUND_END, h.state.phase)

        h.start() // next round
        assertEquals(1, h.state.roundIndex)
        assertEquals(GamePhase.HIDING, h.state.phase)
        assertEquals(PlayerId("ai-1"), h.hiderId)
        assertEquals(0L, h.state.gameTimeMillis)
        assertEquals(emptyList(), h.state.hand)

        h.tick(config.gameDurationMinutes * 60_000L)
        h.start()
        assertEquals(2, h.state.roundIndex)
        assertEquals(PlayerId("ai-2"), h.hiderId)
    }

    @Test
    fun `match scores accumulate per hider round and rounds are capped`() {
        val config = EngineHarness.simConfig(rounds = 2)
        val h = EngineHarness(config)
        h.start()
        h.hideAtAndStartSeeking("DV-A10")
        h.tick(config.gameDurationMinutes * 60_000L) // expire round 0: survived
        assertEquals(GamePhase.ROUND_END, h.state.phase)
        // 10 min survival + 10.0 never-captured bonus.
        assertEquals(20.0, h.state.matchScores[h.humanId])

        h.start()
        assertEquals(1, h.state.roundIndex)
        h.tick(config.gameDurationMinutes * 60_000L)
        assertEquals(GamePhase.ROUND_END, h.state.phase)
        assertTrue((h.state.matchScores[PlayerId("ai-1")] ?: 0.0) > 0.0)
        assertEquals(20.0, h.state.matchScores[h.humanId]) // unchanged in the AI's round

        // Rounds exhausted: a further StartRound is ignored.
        val emitted = h.start()
        assertTrue(emitted.isEmpty())
        assertEquals(GamePhase.ROUND_END, h.state.phase)
    }

    @Test
    fun `seeker move commands are rejected during the hiding phase`() {
        val h = EngineHarness(EngineHarness.simConfig())
        h.start()
        h.moveToken(h.ai1, "DV-A10")
        h.tick(600_000L)
        // The seeker never moved: still at the start station.
        assertEquals(PlayerPosition.NodePosition("DV-A07"), h.state.positions[h.ai1])
    }
}
