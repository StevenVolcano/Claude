package io.terminus.core.engine

import io.terminus.core.clock.SteppedTimeSource
import io.terminus.core.game.Difficulty
import io.terminus.core.game.EngineHarness
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GameEngine
import io.terminus.core.game.GameEvent
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameState
import io.terminus.core.game.PlayerId
import io.terminus.core.game.PlayerPosition
import io.terminus.core.game.Role
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GameRunner integration (ARCHITECTURE.md §2): SteppedTimeSource + ManualTicker +
 * scripted brains drive a short sim round to RoundEnded, twice, deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameRunnerTest {

    /** Scripted hider: as soon as the hiding phase starts, head for DV-A10 and stay. */
    private class ScriptedHider(override val playerId: PlayerId) : AiBrain {
        override val decisionTickMillis: Long = 10_000L
        override fun decide(state: GameState, nowGameMillis: Long): List<GameCommand> {
            if (state.phase != GamePhase.HIDING) return emptyList()
            val atStart = state.positions[playerId] == PlayerPosition.NodePosition("DV-A07")
            return if (atStart) listOf(GameCommand.MoveToken(playerId, "DV-A10", nowGameMillis)) else emptyList()
        }
    }

    /** Scripted seeker: once seeking starts, march straight to DV-A10. */
    private class ScriptedSeeker(override val playerId: PlayerId) : AiBrain {
        override val decisionTickMillis: Long = 20_000L
        override fun decide(state: GameState, nowGameMillis: Long): List<GameCommand> {
            if (state.phase != GamePhase.SEEKING && state.phase != GamePhase.FINAL_APPROACH) return emptyList()
            val atStart = state.positions[playerId] == PlayerPosition.NodePosition("DV-A07")
            return if (atStart) listOf(GameCommand.MoveToken(playerId, "DV-A10", nowGameMillis)) else emptyList()
        }
    }

    private fun runScriptedRound(): GameState {
        val config = EngineHarness.simConfig(
            seed = 7L,
            gameDurationMinutes = 30,
            hidingPhaseMinutes = 10,
            humanRole = Role.HIDER,
            aiOpponents = listOf(Difficulty.MEDIUM),
        )
        lateinit var finalState: GameState
        runTest {
            val time = SteppedTimeSource()
            val ticker = ManualTicker()
            val runner = GameRunner(EngineHarness.demovilleCity, config, time, this, ticker)
            // The human hider is driven by a scripted brain too — brains are just
            // deterministic command sources.
            runner.registerBrain(ScriptedHider(GameEngine.HUMAN_PLAYER_ID))
            runner.registerBrain(ScriptedSeeker(PlayerId("ai-1")))
            runner.start()
            runner.submit(GameCommand.StartRound)
            advanceUntilIdle()
            assertEquals(GamePhase.HIDING, runner.state.value.phase)

            // 10 game-seconds per real tick; a 30-minute round is at most 180 ticks.
            var safety = 0
            while (runner.state.value.phase != GamePhase.ROUND_END && safety < 200) {
                time.advanceMillis(10_000L)
                ticker.fire()
                advanceUntilIdle()
                safety++
            }
            finalState = runner.state.value
            assertEquals(GamePhase.ROUND_END, finalState.phase)

            // Round record was captured for persistence.
            val record = runner.roundRecords.single()
            assertEquals(0, record.roundIndex)
            assertEquals(GameEngine.HUMAN_PLAYER_ID, record.hiderId)
            assertTrue(record.captured)
            assertEquals(PlayerId("ai-1"), record.capturedBy)
            assertEquals("DV-A10", record.hidingStationId)
            assertTrue(record.aiPersonalities.isNotEmpty())

            val match = runner.buildMatchRecord(createdEpochSec = 1L)
            assertEquals(1, match.rounds.size)
            runner.stop()
        }
        return finalState
    }

    @Test
    fun `scripted round runs to a capture through the runner`() {
        val state = runScriptedRound()
        assertEquals(PlayerId("ai-1"), state.capturedBy)
        // The seeker passed through Final Approach on the way in.
        assertTrue(
            state.eventLog.any {
                it is GameEvent.PhaseChanged && it.to == GamePhase.FINAL_APPROACH
            },
        )
        assertTrue(state.eventLog.last() is GameEvent.RoundEnded)
    }

    @Test
    fun `two identical runs produce byte-identical event logs`() {
        val first = runScriptedRound()
        val second = runScriptedRound()
        assertEquals(first.eventLog, second.eventLog)
        assertEquals(first.matchScores, second.matchScores)
        assertEquals(first.aiPersonalities, second.aiPersonalities)
    }
}
