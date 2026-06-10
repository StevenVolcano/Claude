package io.terminus.core.integration

import io.terminus.core.clock.SteppedTimeSource
import io.terminus.core.engine.GameRunner
import io.terminus.core.engine.ManualTicker
import io.terminus.core.engine.Replay
import io.terminus.core.engine.aiBrainsFor
import io.terminus.core.game.EngineHarness
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameState
import io.terminus.core.persistence.ReplayLog
import io.terminus.core.persistence.TerminusJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Sim-mode autosave/resume via command-log replay (ARCHITECTURE.md §5; W6 caveat:
 * the engine runtime is not serialized): a round saved mid-game as a [ReplayLog],
 * rebuilt through [Replay.rebuildRunner], and continued must finish exactly like
 * the uninterrupted run — state, records, and event log byte-identical.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayResumeTest {

    /** Mid-SEEKING save point: 66 ticks × 10 s = 11 sim-minutes into the round. */
    private val saveAtTick = 66

    private val config = AiMatchHarness.smokeConfig(seed = 42L)

    private class UninterruptedRun(
        val savedLog: ReplayLog,
        val stateAtSaveJson: String,
        val finalState: GameState,
        val finalRecordsJson: String,
    )

    /** Runs the round start-to-finish, snapshotting the command log at [saveAtTick]. */
    private fun runUninterrupted(): UninterruptedRun {
        lateinit var run: UninterruptedRun
        runTest {
            val time = SteppedTimeSource()
            val ticker = ManualTicker()
            val runner = GameRunner(EngineHarness.demovilleCity, config, time, this, ticker)
            aiBrainsFor(config, EngineHarness.demovilleCity).forEach(runner::registerBrain)
            runner.start()
            runner.submit(GameCommand.StartRound)
            advanceUntilIdle()

            lateinit var savedLog: ReplayLog
            lateinit var stateAtSaveJson: String
            var ticks = 0
            while (runner.state.value.phase != GamePhase.ROUND_END &&
                ticks < AiMatchHarness.MAX_TICKS_PER_ROUND
            ) {
                time.advanceMillis(AiMatchHarness.TICK_GAME_MILLIS)
                ticker.fire()
                advanceUntilIdle() // quiescent: every brain command is applied and logged
                ticks++
                if (ticks == saveAtTick) {
                    savedLog = ReplayLog(
                        cityId = config.cityId,
                        config = config,
                        commands = runner.commandLog(),
                    )
                    stateAtSaveJson = TerminusJson.json.encodeToString(runner.state.value)
                }
            }
            assertEquals(GamePhase.ROUND_END, runner.state.value.phase)
            assertTrue(ticks > saveAtTick, "the save point must fall mid-round")
            runner.stop()
            run = UninterruptedRun(
                savedLog = savedLog,
                stateAtSaveJson = stateAtSaveJson,
                finalState = runner.state.value,
                finalRecordsJson = TerminusJson.json.encodeToString(runner.roundRecords.toList()),
            )
        }
        return run
    }

    @Test
    fun `a ReplayLog survives JSON round-tripping`() {
        val original = runUninterrupted().savedLog
        val decoded = TerminusJson.json.decodeFromString<ReplayLog>(
            TerminusJson.json.encodeToString(original),
        )
        assertEquals(original, decoded)
        assertTrue(original.commands.isNotEmpty())
    }

    @Test
    fun `replaying the autosave and continuing matches the uninterrupted run exactly`() {
        val baseline = runUninterrupted()
        // Decode through JSON, exactly as a resumed app process would.
        val log = TerminusJson.json.decodeFromString<ReplayLog>(
            TerminusJson.json.encodeToString(baseline.savedLog),
        )

        runTest {
            // A fresh stepped clock starting at 0 is fine: the runner consumes deltas.
            val time = SteppedTimeSource()
            val ticker = ManualTicker()
            val runner = Replay.rebuildRunner(
                cityFile = EngineHarness.demovilleCity,
                log = log,
                timeSource = time,
                scope = this,
                ticker = ticker,
            )

            // The replay alone reconstructs the saved mid-round state byte-for-byte.
            assertEquals(saveAtTick * AiMatchHarness.TICK_GAME_MILLIS, runner.state.value.gameTimeMillis)
            assertEquals(
                baseline.stateAtSaveJson,
                TerminusJson.json.encodeToString(runner.state.value),
            )
            assertNotEquals(GamePhase.ROUND_END, runner.state.value.phase)

            // Continue playing on the same 10 s cadence as the uninterrupted run.
            runner.start()
            var safety = 0
            while (runner.state.value.phase != GamePhase.ROUND_END &&
                safety < AiMatchHarness.MAX_TICKS_PER_ROUND
            ) {
                time.advanceMillis(AiMatchHarness.TICK_GAME_MILLIS)
                ticker.fire()
                advanceUntilIdle()
                safety++
            }
            runner.stop()

            assertEquals(GamePhase.ROUND_END, runner.state.value.phase)
            assertEquals(
                AiMatchHarness.eventLogJson(baseline.finalState.eventLog),
                AiMatchHarness.eventLogJson(runner.state.value.eventLog),
                "the resumed game must finish identically to the uninterrupted one",
            )
            assertEquals(
                TerminusJson.json.encodeToString(baseline.finalState),
                TerminusJson.json.encodeToString(runner.state.value),
            )
            assertEquals(
                baseline.finalRecordsJson,
                TerminusJson.json.encodeToString(runner.roundRecords.toList()),
            )
        }
    }
}
