package io.terminus.core.ai

import io.terminus.core.game.AiPersonality
import io.terminus.core.game.Difficulty
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GameEvent
import io.terminus.core.game.GameState
import io.terminus.core.game.PlayMode
import io.terminus.core.game.PlayerId
import io.terminus.core.game.PlayerPosition
import io.terminus.core.transit.TransitNetwork
import kotlin.random.Random

/**
 * An AI player brain (GAME_DESIGN.md §6; ARCHITECTURE.md §1.1 `ai`).
 *
 * Brains are pure functions of [GameState] (they derive all belief — e.g. the seeker
 * CandidateSet — by replaying `GameState.eventLog`) plus a private seeded RNG stream,
 * so a round replayed with the same seed and the same state sequence is identical
 * (GAME_DESIGN.md §6.4 determinism rule). The engine (W6) invokes [decide] on every
 * [decisionTickMillis] boundary of the game clock, and additionally whenever a
 * question's 20 s response window opens for an AI hider (veto/decoy plays).
 */
interface TerminusAiBrain {
    /** The AI player this brain controls. */
    val playerId: PlayerId

    /** Decision-tick interval in game millis (GAME_DESIGN.md §6.5 table). */
    val decisionTickMillis: Long

    /** Commands the AI issues at this decision tick; empty when it has nothing to do. */
    fun decide(state: GameState, nowGameMillis: Long): List<GameCommand>
}

/**
 * Creates an AI seeker brain (GAME_DESIGN.md §6.3, §6.4, §6.5).
 *
 * @param network the playable (boundary-clipped, mode/route-filtered) transit network.
 * @param seed the round seed; combined with [playerId] so co-seekers draw distinct streams.
 */
fun createSeekerBrain(
    playerId: PlayerId,
    difficulty: Difficulty,
    personality: AiPersonality,
    network: TransitNetwork,
    seed: Long,
): TerminusAiBrain = AiSeekerBrain(
    playerId = playerId,
    difficulty = DifficultyProfile.of(difficulty),
    personality = PersonalityProfile.of(personality),
    network = network,
    rng = brainRandom(seed, playerId),
)

/**
 * Creates an AI hider brain (GAME_DESIGN.md §6.1, §6.2, §6.4, §6.5).
 *
 * @param network the playable (boundary-clipped, mode/route-filtered) transit network.
 * @param seed the round seed; combined with [playerId].
 */
fun createHiderBrain(
    playerId: PlayerId,
    difficulty: Difficulty,
    personality: AiPersonality,
    network: TransitNetwork,
    seed: Long,
): TerminusAiBrain = AiHiderBrain(
    playerId = playerId,
    difficulty = DifficultyProfile.of(difficulty),
    personality = PersonalityProfile.of(personality),
    network = network,
    rng = brainRandom(seed, playerId),
)

/** One seeded stream per (round seed, player): deterministic, distinct between AIs. */
private fun brainRandom(seed: Long, playerId: PlayerId): Random =
    Random(seed * 31 + playerId.value.hashCode())

/**
 * Incremental, cached replay of `GameState.eventLog` into a [CandidateSet]
 * (GAME_DESIGN.md §6.3): uniform over eligible hiding stations, then one update per
 * delivered answer (`GameEvent.AnswerDelivered`, whose `wasDecoy` flag drives the
 * §6.3 decoy rule). Vetoed questions deliver no answer and contribute nothing.
 *
 * The cache is keyed by the count of processed answers; the event log is append-only
 * within a round, and the tracker resets itself if the log ever shrinks (new round).
 */
internal class CandidateTracker(
    private val network: TransitNetwork,
    private val profile: DifficultyProfile,
) {
    private var cached: CandidateSet? = null
    private var processedAnswers = 0
    private var totalEventsSeen = 0

    fun current(state: GameState): CandidateSet {
        if (state.eventLog.size < totalEventsSeen) reset()
        var set = cached ?: CandidateSet.uniform(
            HidingEligibility.eligibleHidingStationIds(
                network,
                HidingEligibility.resolveStartStationId(state.config, network),
            ),
        )
        var seen = 0
        for (event in state.eventLog) {
            if (event !is GameEvent.AnswerDelivered) continue
            seen++
            if (seen <= processedAnswers) continue
            set = set.updated(event.spec, event.answer, event.wasDecoy, profile, network, state.config.playMode)
        }
        processedAnswers = seen
        totalEventsSeen = state.eventLog.size
        cached = set
        return set
    }

    private fun reset() {
        cached = null
        processedAnswers = 0
        totalEventsSeen = 0
    }
}

/** Shared position helpers for both brains. */
internal object BrainPositions {

    /**
     * The graph station a player is at or heading to: a `NodePosition`'s node, an
     * `EdgePosition`'s destination, or the nearest station to a GPS fix.
     */
    fun stationIdOf(position: PlayerPosition?, network: TransitNetwork): String? = when (position) {
        null -> null
        is PlayerPosition.NodePosition -> position.stationId
        is PlayerPosition.EdgePosition -> position.toStationId
        is PlayerPosition.GpsPosition -> network.nearestStation(position.latLng)?.id
    }

    /** The player's coordinate (GPS fix, node coordinate, or edge interpolation). */
    fun latLngOf(position: PlayerPosition?, network: TransitNetwork) = when (position) {
        null -> null
        else -> io.terminus.core.sim.SimulationEngine.latLngOf(position, network)
    }

    /** Is the play mode GPS (drives the 300 m disc consistency rule)? */
    fun isGps(state: GameState): Boolean = state.config.playMode == PlayMode.GPS
}
