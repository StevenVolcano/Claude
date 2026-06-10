package io.terminus.core.integration

import io.terminus.core.engine.GameRunner
import io.terminus.core.engine.ManualTicker
import io.terminus.core.engine.aiBrainsFor
import io.terminus.core.clock.SteppedTimeSource
import io.terminus.core.game.Difficulty
import io.terminus.core.game.EngineHarness
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GameConfig
import io.terminus.core.game.GameEvent
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameState
import io.terminus.core.game.PlayMode
import io.terminus.core.game.Role
import io.terminus.core.persistence.MatchRecord
import io.terminus.core.persistence.RoundRecord
import io.terminus.core.persistence.TerminusJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.assertEquals

/**
 * Driver for fully automated AI-vs-AI matches through the real [GameRunner] + W7
 * brains (ARCHITECTURE.md §6 item 8): Demoville from the checked-in test resource,
 * sim mode, [SteppedTimeSource] + [ManualTicker] advanced in fixed 10 s game steps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object AiMatchHarness {

    /** One runner tick = 10 game-seconds; a 45-minute round is at most 270 ticks. */
    const val TICK_GAME_MILLIS = 10_000L

    /** Safety valve well above the 270 ticks a 45-minute round can need. */
    const val MAX_TICKS_PER_ROUND = 400

    /**
     * The §6 item 8 setup: AI hider (ai-1) vs two AI seekers (ai-2, ai-3) on
     * Demoville, sim defaults (45 min game / 10 min hiding), mixed difficulties.
     * The human is a seeker on paper (GAME_DESIGN.md §1.3 requires exactly one)
     * but never issues a command — the match is fully AI-driven.
     */
    fun smokeConfig(seed: Long?, rounds: Int = 1): GameConfig = GameConfig(
        cityId = "demoville",
        startStationId = "DV-A07",
        playMode = PlayMode.SIM,
        gameDurationMinutes = 45,
        hidingPhaseMinutes = 10,
        aiOpponents = listOf(Difficulty.HARD, Difficulty.MEDIUM, Difficulty.EASY),
        humanRole = Role.SEEKER,
        rounds = rounds,
        seed = seed,
    )

    /** Everything a finished match leaves behind, for the smoke assertions. */
    class MatchResult(
        val records: List<RoundRecord>,
        val match: MatchRecord,
        val finalStates: List<GameState>,
    )

    /** Runs all `config.rounds` rounds to ROUND_END and returns the records. */
    fun runAiMatch(config: GameConfig): MatchResult {
        lateinit var result: MatchResult
        runTest {
            val time = SteppedTimeSource()
            val ticker = ManualTicker()
            val runner = GameRunner(EngineHarness.demovilleCity, config, time, this, ticker)
            aiBrainsFor(config, EngineHarness.demovilleCity).forEach(runner::registerBrain)
            runner.start()
            val finalStates = mutableListOf<GameState>()
            repeat(config.rounds) { round ->
                runner.submit(GameCommand.StartRound)
                advanceUntilIdle()
                assertEquals(GamePhase.HIDING, runner.state.value.phase, "round $round must start")
                var ticks = 0
                while (runner.state.value.phase != GamePhase.ROUND_END && ticks < MAX_TICKS_PER_ROUND) {
                    time.advanceMillis(TICK_GAME_MILLIS)
                    ticker.fire()
                    advanceUntilIdle()
                    ticks++
                }
                assertEquals(
                    GamePhase.ROUND_END,
                    runner.state.value.phase,
                    "round $round must terminate (capture or expiry) within 45 sim-minutes",
                )
                finalStates += runner.state.value
            }
            val match = runner.buildMatchRecord(createdEpochSec = 0L)
            runner.stop()
            result = MatchResult(runner.roundRecords.toList(), match, finalStates)
        }
        return result
    }

    /** The §6 item 8 "byte-identical event logs" comparison key. */
    fun eventLogJson(events: List<GameEvent>): String = TerminusJson.json.encodeToString(events)
}
