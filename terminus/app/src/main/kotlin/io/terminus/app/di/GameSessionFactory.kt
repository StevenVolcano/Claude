package io.terminus.app.di

import io.terminus.core.cityfile.CityFile
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GameConfig
import io.terminus.core.game.GameEvent
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameState
import io.terminus.core.game.Player
import io.terminus.core.game.PlayerId
import io.terminus.core.game.PlayerPosition
import io.terminus.core.game.PlayMode
import io.terminus.core.game.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The app's only view of a running game (ARCHITECTURE.md §2). The UI, the GPS
 * foreground service, and the ViewModels bind to this facade exclusively; the real
 * implementation (core `engine.GameRunner` + `ai` brains) is wired by the Phase 3
 * integrator in [GameSessionFactory.create] below. No other app file may reference
 * the `io.terminus.core.engine` or `io.terminus.core.ai` packages.
 */
interface GameSession {
    /** The current immutable game state; emits on every reducer step. */
    val state: StateFlow<GameState>

    /** Every emitted [GameEvent], for the ActivityLog and round/match records. */
    val events: SharedFlow<GameEvent>

    /** Enqueues a command (question, card play, GPS fix, move, pause, …). */
    fun send(cmd: GameCommand)

    /** Stops the session's loop and releases its resources. */
    fun close()
}

/** Invented display names assigned to AI opponents, in opponent order. */
val AI_NAME_POOL: List<String> = listOf("Vera", "Otto", "Mina", "Felix", "Iris", "Hugo")

/**
 * Creates [GameSession]s from setup output.
 *
 * TODO(Phase 3): replace [PlaceholderGameSession] with the real wiring:
 *   - build the player list (one human + `config.aiOpponents` AIs, names from
 *     [AI_NAME_POOL]) and the round-0 role assignment from `config.humanRole`;
 *   - construct `io.terminus.core.engine.GameRunner(config, cityFile, players, scope)`
 *     with the AI seeker/hider brains from `io.terminus.core.ai`;
 *   - adapt the runner's `StateFlow<GameState>` / `Flow<GameEvent>` / command channel
 *     to this interface and start its 1 Hz loop in [scope].
 * Everything above this factory (ViewModels, screens, the foreground service) is
 * already written against [GameSession] and needs no changes.
 */
object GameSessionFactory {

    fun create(config: GameConfig, cityFile: CityFile, scope: CoroutineScope): GameSession {
        // TODO(Phase 3): return the real GameRunner-backed session (see KDoc above).
        return PlaceholderGameSession(config, cityFile, scope)
    }

    /** The fixed id used for the human participant. */
    val HUMAN_PLAYER_ID: PlayerId = PlayerId("human")

    /** Builds the match's participant list: the human plus the configured AIs. */
    fun buildPlayers(config: GameConfig): List<Player> {
        val players = ArrayList<Player>(1 + config.aiOpponents.size)
        players += Player.HumanPlayer(HUMAN_PLAYER_ID, "You")
        config.aiOpponents.forEachIndexed { i, difficulty ->
            players += Player.AiPlayer(
                id = PlayerId("ai-$i"),
                name = AI_NAME_POOL[i % AI_NAME_POOL.size],
                difficulty = difficulty,
            )
        }
        return players
    }
}

/**
 * A do-nothing stand-in so the UI is navigable before Phase 3: it builds a plausible
 * initial [GameState], ticks the clock at 1 Hz (scaled in sim mode), honors
 * [GameCommand.PauseToggle], and appends nothing else. All game logic arrives with
 * the real `GameRunner`.
 */
private class PlaceholderGameSession(
    config: GameConfig,
    cityFile: CityFile,
    private val scope: CoroutineScope,
) : GameSession {

    private val players = GameSessionFactory.buildPlayers(config)

    private val _state = MutableStateFlow(initialState(config, cityFile))
    override val state: StateFlow<GameState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GameEvent>(replay = 64, extraBufferCapacity = 64)
    override val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val ticker = scope.launch {
        while (isActive) {
            delay(1_000L)
            val scale = if (config.playMode == PlayMode.SIM) config.timeScale.toLong() else 1L
            send(GameCommand.Tick(1_000L * scale))
        }
    }

    override fun send(cmd: GameCommand) {
        val s = _state.value
        when (cmd) {
            is GameCommand.Tick -> if (!s.paused && s.phase != GamePhase.ROUND_END) {
                val now = s.gameTimeMillis + cmd.gameMillisDelta
                val hidingEnd = s.config.hidingPhaseMinutes * 60_000L
                val gameEnd = s.config.gameDurationMinutes * 60_000L
                val phase = when {
                    now >= gameEnd -> GamePhase.ROUND_END
                    now >= hidingEnd -> GamePhase.SEEKING
                    else -> GamePhase.HIDING
                }
                if (phase != s.phase) {
                    emit(GameEvent.PhaseChanged(s.phase, phase, now))
                }
                _state.value = s.copy(gameTimeMillis = now, phase = phase)
            }
            is GameCommand.PauseToggle -> if (s.config.playMode == PlayMode.SIM) {
                _state.value = s.copy(paused = !s.paused)
                emit(GameEvent.PauseToggled(!s.paused, s.gameTimeMillis))
            }
            else -> Unit // TODO(Phase 3): all real commands are handled by GameRunner.
        }
    }

    override fun close() {
        ticker.cancel()
    }

    private fun emit(event: GameEvent) {
        _state.value = _state.value.copy(eventLog = _state.value.eventLog + event)
        _events.tryEmit(event)
    }

    private fun initialState(config: GameConfig, cityFile: CityFile): GameState {
        val startStation = config.startStationId ?: cityFile.defaultStartStationId
        val roles = players.associate { player ->
            val role = when {
                player.id == GameSessionFactory.HUMAN_PLAYER_ID ->
                    config.humanRole
                config.humanRole == Role.SEEKER && player.id == players.filterIsInstance<Player.AiPlayer>().first().id ->
                    Role.HIDER
                else -> Role.SEEKER
            }
            player.id to role
        }
        return GameState(
            config = config,
            roundIndex = 0,
            phase = GamePhase.HIDING,
            gameTimeMillis = 0L,
            players = players,
            roles = roles,
            positions = players.associate { it.id to PlayerPosition.NodePosition(startStation) },
        )
    }
}
