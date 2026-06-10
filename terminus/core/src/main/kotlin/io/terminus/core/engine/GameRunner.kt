package io.terminus.core.engine

import io.terminus.core.cityfile.CityFile
import io.terminus.core.clock.TimeSource
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GameConfig
import io.terminus.core.game.GameEngine
import io.terminus.core.game.GameEvent
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameState
import io.terminus.core.game.PlayerId
import io.terminus.core.persistence.MatchRecord
import io.terminus.core.persistence.RoundRecord
import io.terminus.core.game.Player
import io.terminus.core.game.Role
import io.terminus.core.game.ScoreKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The per-game coroutine loop (ARCHITECTURE.md §1.1 `engine`, §2):
 *
 * 1. a [Ticker] (1 Hz real time in production) reads the [TimeSource] and enqueues
 *    `GameCommand.Tick(gameMillisDelta)` — with a `ScaledTimeSource` each real second
 *    advances `timeScale` game-seconds; a paused source yields no delta and no tick;
 * 2. a single consumer coroutine applies every command through the pure
 *    [GameEngine.reduce], publishing [state] and [events];
 * 3. after each applied tick, registered [AiBrain]s whose decision interval elapsed
 *    are invoked against the new state and their commands are fed back through the
 *    same channel (in registration order — deterministic);
 * 4. the UI / GPS foreground service push their own commands via [submit].
 *
 * Round/match orchestration: on `RoundEnded` the finished round's [RoundRecord] is
 * appended to [roundRecords] (match scores accumulate inside `GameState.matchScores`).
 * Calling [startNextRound] (or submitting `StartRound` while in `ROUND_END`) rotates
 * roles and starts the next round; game time restarts at 0 per round.
 *
 * Sim-mode resume (ARCHITECTURE.md §5 `autosave.json`): the engine's round runtime is
 * deliberately not serialized, so every applied command is recorded ([commandLog]) and
 * a fresh runner reconstructs the exact runtime by [replay]ing the log before [start].
 */
class GameRunner(
    cityFile: CityFile,
    config: GameConfig,
    private val timeSource: TimeSource,
    private val scope: CoroutineScope,
    private val ticker: Ticker = RealTicker(),
    players: List<Player>? = null,
) {
    private val engine = GameEngine(cityFile)
    private val commands = Channel<GameCommand>(Channel.UNLIMITED)
    private val brains = mutableListOf<AiBrain>()
    private val lastBrainInvokeGameMillis = HashMap<PlayerId, Long>()
    private val appliedCommands = ArrayList<GameCommand>()
    private var lastTickedGameMillis = 0L
    private var loopJob: Job? = null
    private var tickerJob: Job? = null

    private val _state = MutableStateFlow(GameEngine.initialState(config, players))

    /** The live game state; collect with `collectAsStateWithLifecycle` in the app. */
    val state: StateFlow<GameState> = _state

    private val _events = MutableSharedFlow<GameEvent>(
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every emitted [GameEvent], in order (feeds the ActivityLog). */
    val events: SharedFlow<GameEvent> = _events

    /** Completed rounds' records, in play order (persisted as part of the MatchRecord). */
    val roundRecords: MutableList<RoundRecord> = mutableListOf()

    /**
     * Registers [brain] to be invoked on its decision-tick boundaries. Register all
     * brains (one per AI player) before [start].
     */
    fun registerBrain(brain: AiBrain) {
        check(loopJob == null) { "register brains before start()" }
        brains += brain
    }

    /** Enqueues [command]; commands are applied strictly in submission order. */
    fun submit(command: GameCommand) {
        commands.trySend(command)
    }

    /** A snapshot of every applied command, in order (the sim-mode autosave payload). */
    fun commandLog(): List<GameCommand> = synchronized(appliedCommands) { appliedCommands.toList() }

    /**
     * Replays [commands] through the reducer before [start] (sim-mode resume): the
     * registered brains are invoked exactly as in the live run — so their seeded RNG
     * streams and internal caches advance identically — but their outputs are
     * discarded, because the log already contains every command they produced.
     */
    fun replay(commands: List<GameCommand>) {
        check(loopJob == null) { "replay before start()" }
        for (command in commands) process(command, liveBrains = false)
    }

    /** Launches the command loop and the ticker. Call once. */
    fun start() {
        check(loopJob == null) { "GameRunner already started" }
        lastTickedGameMillis = timeSource.nowGameMillis()
        loopJob = scope.launch {
            for (command in commands) process(command)
        }
        tickerJob = scope.launch {
            while (isActive) {
                ticker.awaitTick()
                val now = timeSource.nowGameMillis()
                val delta = now - lastTickedGameMillis
                if (delta > 0L) {
                    lastTickedGameMillis = now
                    submit(GameCommand.Tick(delta))
                }
            }
        }
    }

    /** Stops the loop and the ticker. The flows keep their last values. */
    fun stop() {
        tickerJob?.cancel()
        loopJob?.cancel()
        commands.close()
    }

    /**
     * Starts the next round after `ROUND_END` (no-op otherwise): the engine rotates
     * the hider role and re-seeds the round (GAME_DESIGN.md §7).
     */
    fun startNextRound() {
        submit(GameCommand.StartRound)
    }

    /** The match record so far (ARCHITECTURE.md §5), winner per the §7 tiebreaks. */
    fun buildMatchRecord(createdEpochSec: Long): MatchRecord {
        val current = _state.value
        val players: List<Player> = current.players
        return MatchRecord(
            createdEpochSec = createdEpochSec,
            config = current.config,
            players = players,
            rounds = roundRecords.toList(),
            totalScores = ScoreKeeper.totalScores(players, roundRecords),
            winnerId = ScoreKeeper.winner(players, roundRecords),
        )
    }

    private fun process(command: GameCommand, liveBrains: Boolean = true) {
        val previous = _state.value
        val (next, emitted) = engine.reduce(previous, command)
        synchronized(appliedCommands) { appliedCommands += command }
        _state.value = next
        // The flow drops oldest on overflow, so tryEmit never fails or suspends.
        for (event in emitted) _events.tryEmit(event)
        if (emitted.any { it is GameEvent.RoundEnded }) {
            roundRecords += engine.buildRoundRecord(next)
        }
        if (command is GameCommand.StartRound && next.phase == GamePhase.HIDING) {
            lastBrainInvokeGameMillis.clear()
        }
        if (command is GameCommand.Tick && next.phase != GamePhase.ROUND_END && !next.paused) {
            val now = next.gameTimeMillis
            for (brain in brains) {
                val last = lastBrainInvokeGameMillis[brain.playerId] ?: 0L
                if (now - last >= brain.decisionTickMillis) {
                    lastBrainInvokeGameMillis[brain.playerId] = now
                    val decided = brain.decide(next, now)
                    if (liveBrains) decided.forEach(::submit)
                }
            }
        }
        // W7 contract: an AI hider must get a chance to veto/decoy the moment a
        // question's 20 s response window opens (GAME_DESIGN.md §3) — its regular
        // decision tick may be longer than the window. Deterministic: a pure
        // function of the command stream, like the tick-boundary invocations.
        val window = next.pendingQuestion
        if (window != null && window != previous.pendingQuestion &&
            next.phase != GamePhase.ROUND_END && !next.paused
        ) {
            val hiderId = next.roles.entries.firstOrNull { it.value == Role.HIDER }?.key
            val hiderBrain = brains.firstOrNull { it.playerId == hiderId }
            if (hiderBrain != null) {
                val decided = hiderBrain.decide(next, next.gameTimeMillis)
                if (liveBrains) decided.forEach(::submit)
            }
        }
    }
}
