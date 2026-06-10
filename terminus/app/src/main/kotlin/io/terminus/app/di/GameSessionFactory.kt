package io.terminus.app.di

import io.terminus.core.cityfile.CityFile
import io.terminus.core.clock.RealTimeSource
import io.terminus.core.clock.ScaledTimeSource
import io.terminus.core.engine.GameRunner
import io.terminus.core.engine.Replay
import io.terminus.core.engine.aiBrainsFor
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GameConfig
import io.terminus.core.game.GameEvent
import io.terminus.core.game.GameState
import io.terminus.core.game.PlayMode
import io.terminus.core.game.Player
import io.terminus.core.game.PlayerId
import io.terminus.core.persistence.MatchRecord
import io.terminus.core.persistence.ReplayLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's only view of a running game (ARCHITECTURE.md §2). The UI, the GPS
 * foreground service, and the ViewModels bind to this facade exclusively; the real
 * implementation (core `engine.GameRunner` + `ai` brains) is wired in
 * [GameSessionFactory] below. No other app file may reference the
 * `io.terminus.core.engine` or `io.terminus.core.ai` packages.
 */
interface GameSession {
    /** The current immutable game state; emits on every reducer step. */
    val state: StateFlow<GameState>

    /** Every emitted [GameEvent], for the ActivityLog and round/match records. */
    val events: SharedFlow<GameEvent>

    /** Enqueues a command (question, card play, GPS fix, move, pause, …). */
    fun send(cmd: GameCommand)

    /**
     * The sim-mode autosave payload — the recorded command log, replayable into an
     * identical session (ARCHITECTURE.md §5 `autosave.json`). Null in GPS mode,
     * which is not resumable.
     */
    fun replayLog(): ReplayLog?

    /**
     * The match so far: the runner-produced per-round `RoundRecord`s plus the
     * GAME_DESIGN.md §7 totals and tiebroken winner.
     */
    fun buildMatchRecord(createdEpochSec: Long): MatchRecord

    /** Stops the session's loop and releases its resources. */
    fun close()
}

/** Invented display names assigned to AI opponents, in opponent order. */
val AI_NAME_POOL: List<String> = listOf("Vera", "Otto", "Mina", "Felix", "Iris", "Hugo")

/**
 * Creates [GameSession]s from setup output: a [GameRunner] over the city file with
 * one `RoundAwareAiBrain` per AI opponent (Phase 3 wiring; ARCHITECTURE.md §2).
 */
object GameSessionFactory {

    fun create(config: GameConfig, cityFile: CityFile, scope: CoroutineScope): GameSession {
        // "Auto" seed (GAME_DESIGN.md §8 item 10) is stamped here, before the first
        // round, so round seeds, deck shuffles, and personality draws are all
        // reproducible from the recorded config (W6 caveat: a null seed reduces to 0).
        val resolved = if (config.seed == null) {
            config.copy(seed = System.currentTimeMillis())
        } else {
            config
        }
        return RunnerGameSession(resolved, cityFile, scope, replay = null)
    }

    /** Rebuilds a sim-mode session from an autosaved [log] by command-log replay. */
    fun resume(log: ReplayLog, cityFile: CityFile, scope: CoroutineScope): GameSession =
        RunnerGameSession(log.config, cityFile, scope, replay = log)

    /** The fixed id used for the human participant. */
    val HUMAN_PLAYER_ID: PlayerId = PlayerId("human")

    /**
     * Builds the match's participant list: the human plus the configured AIs.
     * Ids follow the engine convention (`ai-1`…`ai-n`, `GameEngine.initialState`);
     * only the display names are the app's own.
     */
    fun buildPlayers(config: GameConfig): List<Player> {
        val players = ArrayList<Player>(1 + config.aiOpponents.size)
        players += Player.HumanPlayer(HUMAN_PLAYER_ID, "You")
        config.aiOpponents.forEachIndexed { i, difficulty ->
            players += Player.AiPlayer(
                id = PlayerId("ai-${i + 1}"),
                name = AI_NAME_POOL[i % AI_NAME_POOL.size],
                difficulty = difficulty,
            )
        }
        return players
    }
}

/**
 * The real session: a [GameRunner] driven by a [RealTimeSource] (GPS, 1:1) or a
 * pausable [ScaledTimeSource] (sim, GAME_DESIGN.md §2.2), with the W7 brains
 * registered through `engine.aiBrainsFor`. When [replay] is given, the runner is
 * reconstructed by replaying the autosaved command log before going live.
 */
private class RunnerGameSession(
    private val config: GameConfig,
    cityFile: CityFile,
    scope: CoroutineScope,
    replay: ReplayLog?,
) : GameSession {

    private val timeSource = when (config.playMode) {
        PlayMode.GPS -> RealTimeSource()
        PlayMode.SIM -> ScaledTimeSource(config.timeScale)
    }

    private val players = GameSessionFactory.buildPlayers(config)

    private val runner: GameRunner = if (replay == null) {
        GameRunner(cityFile, config, timeSource, scope, players = players).also { r ->
            aiBrainsFor(config, cityFile, players).forEach(r::registerBrain)
        }
    } else {
        Replay.rebuildRunner(cityFile, replay, timeSource, scope, players = players)
    }

    init {
        // A log autosaved while paused replays to a paused state; the scaled clock
        // must agree or the first unpause would deliver the gap in one giant tick.
        if (runner.state.value.paused) (timeSource as? ScaledTimeSource)?.pause()
        runner.start()
        if (replay == null) runner.submit(GameCommand.StartRound)
    }

    override val state: StateFlow<GameState> = runner.state

    override val events: SharedFlow<GameEvent> = runner.events

    override fun send(cmd: GameCommand) {
        // The reducer flips `GameState.paused`; the scaled clock has to freeze in
        // step so no game time accumulates while paused (GAME_DESIGN.md §2.2).
        if (cmd is GameCommand.PauseToggle && timeSource is ScaledTimeSource) {
            if (timeSource.isPaused) timeSource.resume() else timeSource.pause()
        }
        runner.submit(cmd)
    }

    override fun replayLog(): ReplayLog? = when (config.playMode) {
        PlayMode.SIM -> ReplayLog(
            cityId = config.cityId,
            config = config,
            commands = runner.commandLog(),
        )
        PlayMode.GPS -> null
    }

    override fun buildMatchRecord(createdEpochSec: Long): MatchRecord =
        runner.buildMatchRecord(createdEpochSec)

    override fun close() {
        runner.stop()
    }
}
