package io.terminus.core.engine

import io.terminus.core.ai.DifficultyProfile
import io.terminus.core.ai.TerminusAiBrain
import io.terminus.core.ai.createHiderBrain
import io.terminus.core.ai.createSeekerBrain
import io.terminus.core.cityfile.CityFile
import io.terminus.core.game.Difficulty
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GameConfig
import io.terminus.core.game.GameEngine
import io.terminus.core.game.GameState
import io.terminus.core.game.Player
import io.terminus.core.game.PlayerId
import io.terminus.core.game.Role
import io.terminus.core.transit.TransitNetwork

/**
 * Phase 3 glue between W7's `ai` brains and W6's [GameRunner] (HANDOFF.md
 * "Key integration contracts"): the two brain interfaces are structurally
 * identical, so the adapter is a one-line delegation.
 */
fun TerminusAiBrain.asAiBrain(): AiBrain = object : AiBrain {
    override val playerId: PlayerId get() = this@asAiBrain.playerId
    override val decisionTickMillis: Long get() = this@asAiBrain.decisionTickMillis
    override fun decide(state: GameState, nowGameMillis: Long): List<GameCommand> =
        this@asAiBrain.decide(state, nowGameMillis)
}

/**
 * A match-long brain for one AI player: W7 brains are per-round (fixed role,
 * personality, and seeded RNG stream), so this wrapper rebuilds its delegate from
 * `createHiderBrain`/`createSeekerBrain` whenever the round (and therefore the
 * rotated role, the re-drawn personality, and the round seed
 * `GameEngine.resolvedSeed`) changes. Deterministic: the delegate is a pure
 * function of (playerId, difficulty, round state), GAME_DESIGN.md §6.4.
 *
 * @param network the round-independent playable network
 *   (`GameEngine.buildNetwork`; the boundary and filters are fixed per match).
 */
class RoundAwareAiBrain(
    override val playerId: PlayerId,
    private val difficulty: Difficulty,
    private val network: TransitNetwork,
) : AiBrain {

    override val decisionTickMillis: Long =
        DifficultyProfile.of(difficulty).decisionTickSeconds * 1_000L

    private var delegateRoundIndex = -1
    private var delegateRole: Role? = null
    private var delegate: TerminusAiBrain? = null

    override fun decide(state: GameState, nowGameMillis: Long): List<GameCommand> {
        val role = state.roles[playerId] ?: return emptyList()
        val personality = state.aiPersonalities[playerId] ?: return emptyList()
        if (delegate == null || state.roundIndex != delegateRoundIndex || role != delegateRole) {
            delegateRoundIndex = state.roundIndex
            delegateRole = role
            val roundSeed = GameEngine.resolvedSeed(state.config, state.roundIndex)
            delegate = when (role) {
                Role.HIDER -> createHiderBrain(playerId, difficulty, personality, network, roundSeed)
                Role.SEEKER -> createSeekerBrain(playerId, difficulty, personality, network, roundSeed)
            }
        }
        return checkNotNull(delegate).decide(state, nowGameMillis)
    }
}

/**
 * One runner-ready [RoundAwareAiBrain] per AI player, in player order. [players]
 * defaults to the roster `GameEngine.initialState` builds for [config]; pass the
 * app's roster when it substitutes display names. Register each with
 * [GameRunner.registerBrain] before `start()`.
 */
fun aiBrainsFor(
    config: GameConfig,
    cityFile: CityFile,
    players: List<Player>? = null,
): List<AiBrain> {
    val roster = players ?: GameEngine.initialState(config).players
    val network = GameEngine.buildNetwork(cityFile, config)
    return roster
        .filterIsInstance<Player.AiPlayer>()
        .map { ai -> RoundAwareAiBrain(ai.id, ai.difficulty, network) }
}
