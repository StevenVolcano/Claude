package io.terminus.core.ai

import io.terminus.core.cards.CardEngine
import io.terminus.core.cards.CardType
import io.terminus.core.cards.EffectParams
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameState
import io.terminus.core.game.Player
import io.terminus.core.game.PlayerId
import io.terminus.core.game.Role
import io.terminus.core.questions.QuestionCategory
import io.terminus.core.sim.PathPlanner
import io.terminus.core.transit.TransitNetwork
import kotlin.random.Random

/**
 * AI seeker (GAME_DESIGN.md §6.3): candidate-set reasoning over the event log,
 * info-gain question selection, weight/travel-time movement targeting — with the
 * §6.4 personality modifiers (questionRate, commitment, obscurityBias, temperature)
 * layered on the §6.5 difficulty profile.
 *
 * The shared team CandidateSet is derived deterministically by replaying
 * `GameState.eventLog`, so every co-seeker brain reconstructs the same beliefs
 * without shared mutable state.
 */
internal class AiSeekerBrain(
    override val playerId: PlayerId,
    private val difficulty: DifficultyProfile,
    private val personality: PersonalityProfile,
    private val network: TransitNetwork,
    private val rng: Random,
) : TerminusAiBrain {

    override val decisionTickMillis: Long = difficulty.decisionTickSeconds * 1_000L

    private val tracker = CandidateTracker(network, difficulty)

    /** Last movement target this brain commanded (re-plan stickiness, §6.4 commitment). */
    private var lastTarget: String? = null

    /** Travel-time tables keyed by (banned route ids, origin station). */
    private val travelTimeCache = HashMap<String, Map<String, Int>>()

    override fun decide(state: GameState, nowGameMillis: Long): List<GameCommand> {
        if (state.paused) return emptyList()
        if (state.roles[playerId] != Role.SEEKER) return emptyList()
        if (state.phase != GamePhase.SEEKING && state.phase != GamePhase.FINAL_APPROACH) return emptyList()

        val commands = ArrayList<GameCommand>(2)
        val myStation = BrainPositions.stationIdOf(state.positions[playerId], network) ?: return emptyList()
        val frozen = isEffectActive(state, CardType.STALLED_TRAIN, nowGameMillis) ||
            isEffectActive(state, CardType.TICKET_INSPECTION, nowGameMillis)

        // C8 U-Turn compliance comes first: return to the previous station before
        // anything else (the team may not ask until everyone has returned, §4.2 C8).
        val uTurnTarget = CardEngine.uTurnReturnTargets(state)[playerId]
        if (uTurnTarget != null) {
            if (!frozen && uTurnTarget != myStation && uTurnTarget != lastTarget) {
                lastTarget = uTurnTarget
                commands.add(GameCommand.MoveToken(playerId, uTurnTarget, nowGameMillis))
            }
            return commands
        }

        val candidates = tracker.current(state)

        // Final Approach: questions and new card plays are disabled (§2.1); converge on
        // the hider zone — the engine runs the sweep pattern from there.
        if (state.phase == GamePhase.FINAL_APPROACH) {
            val zone = state.hiderZoneStationId ?: candidates.topWeighted(1).firstOrNull()?.first
            if (!frozen && zone != null && zone != myStation && zone != lastTarget) {
                lastTarget = zone
                commands.add(GameCommand.MoveToken(playerId, zone, nowGameMillis))
            }
            return commands
        }

        maybeAskQuestion(state, nowGameMillis, candidates, myStation)?.let { commands.add(it) }

        if (!frozen) {
            maybeMove(state, nowGameMillis, candidates, myStation)?.let { commands.add(it) }
        }
        return commands
    }

    // ---------------------------------------------------------------- questions

    private fun maybeAskQuestion(
        state: GameState,
        now: Long,
        candidates: CandidateSet,
        myStation: String,
    ): GameCommand? {
        if (state.pendingQuestion != null) return null
        if (now < state.globalCooldownUntilMillis) return null
        if (isEffectActive(state, CardType.DEAD_ZONE, now)) return null
        if (CardEngine.uTurnReturnTargets(state).isNotEmpty()) return null

        val allowed = QuestionCategory.entries.filterTo(HashSet()) { category ->
            now >= (state.categoryCooldownUntilMillis[category] ?: 0L)
        }
        if (isEffectActive(state, CardType.TUNNEL_VISION, now)) allowed.remove(QuestionCategory.RADIUS_PING)
        if (state.armedThermometer != null) allowed.remove(QuestionCategory.THERMOMETER)
        if (allowed.isEmpty()) return null

        // §6.4 questionRate: probability of asking as soon as cooldowns allow.
        if (rng.nextDouble() >= personality.questionRate) return null

        val myLatLng = BrainPositions.latLngOf(state.positions[playerId], network) ?: return null
        val cooldownFactor = state.config.cooldownMultiplier.factor
        val menu = QuestionMenu.build(
            candidates = candidates,
            seekerPosition = myLatLng,
            seekerStationId = myStation,
            network = network,
            allowedCategories = allowed,
        ) { category ->
            (category.baseCooldownMillis * cooldownFactor).toLong() +
                (state.categoryCooldownBonusMillis[category] ?: 0L)
        }
        val scored = InfoGain.score(menu, candidates, network)
        val choice = QuestionPicker.pick(scored, difficulty.questionChoice, personality.temperature, rng)
            ?: return null
        return GameCommand.AskQuestion(playerId, choice.entry.askSpec, now)
    }

    // ----------------------------------------------------------------- movement

    private fun maybeMove(
        state: GameState,
        now: Long,
        candidates: CandidateSet,
        myStation: String,
    ): GameCommand? {
        val top = candidates.topWeighted(10)
        val topStation = top.firstOrNull() ?: return null
        val bannedRoutes = activeDetourRoutes(state, now)
        val travelTimes = travelTimes(myStation, bannedRoutes)

        val target: String = if (topStation.second > ENDGAME_COMMIT_WEIGHT) {
            // Endgame: commit to the top-weighted station outright (§6.3).
            topStation.first
        } else {
            // Bookkeeper-style gate: move only on confidence (§6.4).
            personality.moveOnlyAboveTopWeight?.let { gate ->
                if (topStation.second <= gate) return null
            }
            val pool = clusterRestrictedPool(state, candidates)
            val n = candidates.weights.size.coerceAtLeast(1)
            val best = pool
                .mapNotNull { stationId ->
                    val seconds = travelTimes[stationId] ?: return@mapNotNull null
                    val station = network.stationsById[stationId] ?: return@mapNotNull null
                    val lowSalience = !station.isInterchange && !station.isTerminus
                    // §6.3 target score, with §6.4 obscurityBias nudging low-weight,
                    // low-salience candidates.
                    val weight = candidates.weightOf(stationId) +
                        personality.obscurityBias * (0.5 / n) * (if (lowSalience) 1.0 else 0.0)
                    val score = weight / (1.0 + (seconds / 60.0) / 10.0)
                    stationId to score
                }
                .maxWithOrNull(compareBy({ it.second }, { it.first }))
                ?.first ?: return null
            // §6.4 commitment: stickiness of the current target before re-planning.
            val previous = lastTarget
            if (previous != null && previous != best &&
                candidates.weightOf(previous) > 0.0 && travelTimes.containsKey(previous) &&
                rng.nextDouble() < personality.commitment
            ) {
                previous
            } else {
                best
            }
        }

        if (target == myStation || target == lastTarget) {
            lastTarget = target
            return null
        }
        // Honor active Detour bans: only command a target reachable under them
        // (PathPlanner is what the engine will use to execute the move).
        if (PathPlanner.plan(network, myStation, target, bannedRouteIds = bannedRoutes) == null) return null
        lastTarget = target
        return GameCommand.MoveToken(playerId, target, now)
    }

    /**
     * Hard multi-seeker coordination (§6.3): the top-10 weighted stations are grouped
     * greedily into 4-hop-neighborhood clusters (best unclaimed first); each AI seeker,
     * ranked by player id, claims its own cluster and chases targets inside it.
     */
    private fun clusterRestrictedPool(state: GameState, candidates: CandidateSet): Collection<String> {
        val all = candidates.weights.keys
        if (!difficulty.coordinatesSeekers) return all
        val aiSeekers = state.players
            .filterIsInstance<Player.AiPlayer>()
            .filter { state.roles[it.id] == Role.SEEKER }
            .map { it.id.value }
            .sorted()
        if (aiSeekers.size < 2) return all
        val rank = aiSeekers.indexOf(playerId.value)
        if (rank < 0) return all

        val remaining = candidates.topWeighted(10).map { it.first }.toMutableList()
        val clusters = ArrayList<List<String>>()
        while (remaining.isNotEmpty()) {
            val seedStation = remaining.first()
            val hops = network.bfsHops(seedStation)
            val cluster = remaining.filter { (hops[it] ?: Int.MAX_VALUE) <= CLUSTER_HOPS || it == seedStation }
            clusters.add(cluster)
            remaining.removeAll(cluster.toSet())
        }
        return clusters[rank.coerceAtMost(clusters.size - 1)]
    }

    private fun travelTimes(fromStationId: String, bannedRoutes: Set<String>): Map<String, Int> {
        val key = fromStationId + "|" + bannedRoutes.sorted().joinToString(",")
        return travelTimeCache.getOrPut(key) {
            if (bannedRoutes.isEmpty()) {
                network.dijkstraTimes(fromStationId)
            } else {
                TransitNetwork(
                    network.stations,
                    network.routes,
                    network.edges.filter { it.routeId == null || it.routeId !in bannedRoutes },
                ).dijkstraTimes(fromStationId)
            }
        }
    }

    private fun activeDetourRoutes(state: GameState, now: Long): Set<String> = state.activeEffects
        .asSequence()
        .filter { it.type == CardType.DETOUR && (it.expiryGameMillis == null || it.expiryGameMillis > now) }
        .mapNotNull { (it.params as? EffectParams.DetourParams)?.routeId }
        .toSet()

    private fun isEffectActive(state: GameState, type: CardType, now: Long): Boolean =
        state.activeEffects.any {
            it.type == type && (it.expiryGameMillis == null || it.expiryGameMillis > now)
        }

    private companion object {
        /** §6.3 endgame: commit when the top candidate weight exceeds 0.5. */
        const val ENDGAME_COMMIT_WEIGHT = 0.5

        /** §6.3 Hard coordination: clusters are 4-hop neighborhoods. */
        const val CLUSTER_HOPS = 4
    }
}
