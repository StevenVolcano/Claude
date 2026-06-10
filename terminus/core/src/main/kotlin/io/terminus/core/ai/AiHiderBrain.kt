package io.terminus.core.ai

import io.terminus.core.cards.CardEngine
import io.terminus.core.cards.CardType
import io.terminus.core.cards.EffectParams
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GameEvent
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameRules
import io.terminus.core.game.GameState
import io.terminus.core.game.PlayerId
import io.terminus.core.game.PlayerPosition
import io.terminus.core.game.Role
import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.questions.PingRadius
import io.terminus.core.questions.QuestionCategory
import io.terminus.core.questions.QuestionSpec
import io.terminus.core.transit.TransitNetwork
import kotlin.random.Random

/**
 * AI hider (GAME_DESIGN.md §6.1 spot choice, §6.2 card heuristics) with §6.4
 * personality modifiers (spotWeights, obscurityBias, rejectsSalientSpots,
 * cardAggression, decoyPropensity, vetoDelta, temperature) layered on the §6.5
 * difficulty profile.
 *
 * Veto decisions model the seekers' knowledge with the hider's *own* CandidateSet
 * (exact ε = 0 filtering, decoys discarded) replayed from the event log.
 */
internal class AiHiderBrain(
    override val playerId: PlayerId,
    private val difficulty: DifficultyProfile,
    private val personality: PersonalityProfile,
    private val network: TransitNetwork,
    private val rng: Random,
) : TerminusAiBrain {

    override val decisionTickMillis: Long = difficulty.decisionTickSeconds * 1_000L

    /** The hider's own model of what the seekers know (§6.2 veto heuristic). */
    private val seekerModel = CandidateTracker(network, DifficultyProfile.HARD)

    private var plannedSpot: String? = null
    private var plannedDecoy: LatLng? = null
    private var lastCommandedTarget: String? = null
    private var lastRespondedQuestionAt: Long = -1L
    private val travelTimeCache = HashMap<String, Map<String, Int>>()

    override fun decide(state: GameState, nowGameMillis: Long): List<GameCommand> {
        if (state.paused) return emptyList()
        if (state.roles[playerId] != Role.HIDER) return emptyList()
        return when (state.phase) {
            GamePhase.HIDING -> decideHiding(state, nowGameMillis)
            GamePhase.SEEKING -> decideSeeking(state, nowGameMillis)
            else -> emptyList() // setup, Final Approach (no new card plays), round end
        }
    }

    // ------------------------------------------------------------- hiding phase

    private fun decideHiding(state: GameState, now: Long): List<GameCommand> {
        val start = HidingEligibility.resolveStartStationId(state.config, network)
        val spot = plannedSpot ?: run {
            val chosen = SpotScorer.chooseSpot(
                network = network,
                startStationId = start,
                hidingPhaseSeconds = state.config.hidingPhaseMinutes * 60L,
                difficulty = difficulty,
                personality = personality,
                rng = rng,
            ) ?: return emptyList()
            plannedSpot = chosen
            if (difficulty.spotScoring == SpotScoringStrategy.FULL_WITH_SALIENCE_REJECTION) {
                plannedDecoy = preplanDecoy(start, chosen)
            }
            chosen
        }
        val myStation = BrainPositions.stationIdOf(state.positions[playerId], network)
        if (myStation == spot || spot == lastCommandedTarget) return emptyList()
        lastCommandedTarget = spot
        return listOf(GameCommand.MoveToken(playerId, spot, now))
    }

    /**
     * Hard pre-plans the C12 decoy: the point within 1.5 km of the spot that maximally
     * flips a Radius Ping answer — taken as the farthest legal point directly away from
     * the start station, i.e. away from where seekers approach (§6.1).
     */
    private fun preplanDecoy(startStationId: String, spotStationId: String): LatLng? {
        val spot = network.stationsById[spotStationId]?.latLng ?: return null
        val start = network.stationsById[startStationId]?.latLng ?: return null
        val bearing = GeoMath.bearingDeg(start, spot)
        return GeoMath.destinationPoint(spot, bearing, DECOY_PLAN_DISTANCE_METERS)
    }

    // ------------------------------------------------------------ seeking phase

    private fun decideSeeking(state: GameState, now: Long): List<GameCommand> {
        // Resolve a pending draw-D-keep-K first: nothing else may resolve meanwhile.
        state.pendingKeep?.let { pending ->
            val kept = pickByKeepPriority(pending.drawn, pending.keep)
            return listOf(GameCommand.KeepCards(playerId, kept, now))
        }
        // Discard down to the hand limit when a draw overflowed it (§4.1).
        if (state.hand.size > state.handLimit) {
            val excess = state.hand.size - state.handLimit
            val discards = state.hand
                .sortedByDescending { keepPriorityIndex(it) } // lowest priority first
                .take(excess)
            return listOf(GameCommand.DiscardCards(playerId, discards, now))
        }
        // Response window: Conductor's Override / Ghost Echo only (§4.1).
        state.pendingQuestion?.let { pending ->
            if (now < pending.responseWindowEndsGameMillis &&
                pending.askedAtGameMillis != lastRespondedQuestionAt
            ) {
                lastRespondedQuestionAt = pending.askedAtGameMillis
                return respondToQuestion(state, pending.spec, now)
            }
            return emptyList() // window already handled (or expired); wait for the answer
        }
        return playOneCard(state, now)
    }

    private fun respondToQuestion(state: GameState, spec: QuestionSpec, now: Long): List<GameCommand> {
        // Veto (§6.2): expected information gain against the hider's own model.
        if (CardType.CONDUCTORS_OVERRIDE in state.hand && shouldVeto(state, spec)) {
            return listOf(GameCommand.PlayCard(playerId, CardType.CONDUCTORS_OVERRIDE, null, now))
        }
        // Decoy (§4.2 C12, §6.4 decoyPropensity): Q1/Q2/Q3 only.
        val decoyEligible = spec.category == QuestionCategory.RADIUS_PING ||
            spec.category == QuestionCategory.COMPASS_CALL ||
            spec.category == QuestionCategory.THERMOMETER
        if (decoyEligible && CardType.GHOST_ECHO in state.hand &&
            rng.nextDouble() < personality.decoyPropensity
        ) {
            val point = decoyPoint(state) ?: return emptyList()
            return listOf(
                GameCommand.PlayCard(playerId, CardType.GHOST_ECHO, EffectParams.DecoyParams(point), now),
            )
        }
        return emptyList()
    }

    private fun shouldVeto(state: GameState, spec: QuestionSpec): Boolean = when (difficulty.vetoPolicy) {
        VetoPolicy.NEVER -> false
        VetoPolicy.FIXED_LIST ->
            (spec is QuestionSpec.RadiusPing && spec.radius == PingRadius.M500) ||
                spec is QuestionSpec.Lineup
        VetoPolicy.GAIN_THRESHOLD -> {
            val model = seekerModel.current(state)
            val gain = InfoGain.entropyReductionBits(spec, model, network)
            // §6.4 vetoDelta adjusts the §6.2 1.2-bit threshold (negative = vetoes more).
            gain > VETO_THRESHOLD_BITS + personality.vetoDelta
        }
    }

    /** Hard uses the pre-planned Radius-flip point; others sample within 1.5 km (§6.1). */
    private fun decoyPoint(state: GameState): LatLng? {
        plannedDecoy?.let { return it }
        val truePos = BrainPositions.latLngOf(state.positions[playerId], network) ?: return null
        val bearing = rng.nextDouble() * 360.0
        val distance = 300.0 + rng.nextDouble() * (DECOY_PLAN_DISTANCE_METERS - 300.0)
        return GeoMath.destinationPoint(truePos, bearing, distance)
    }

    // ------------------------------------------------------------- card playing

    /** At most one proactive card play per decision tick (§6.2 heuristics in order). */
    private fun playOneCard(state: GameState, now: Long): List<GameCommand> {
        // §6.4: the Rat re-rolls its aggression every decision tick.
        val aggression = personality.cardAggression ?: rng.nextDouble()
        val curseCapFree = CardEngine.activeCurseCount(state) < GameRules.MAX_ACTIVE_CURSES
        val zoneStation = state.hiderZoneStationId ?: plannedSpot
            ?: BrainPositions.stationIdOf(state.positions[playerId], network)
        val nearestSeekerSeconds = zoneStation?.let { nearestSeekerTravelSeconds(state, it) }

        fun play(type: CardType, params: EffectParams? = null) =
            listOf(GameCommand.PlayCard(playerId, type, params, now))

        // Time bonuses: immediately when hand > limit - 2, largest first (§6.2);
        // a high-aggression hider (Showman) dumps them as soon as they are legal.
        val bonusInHand = BONUS_LARGEST_FIRST.firstOrNull { it in state.hand }
        if (bonusInHand != null && (state.hand.size > state.handLimit - 2 || aggression >= 0.75)) {
            return play(bonusInHand)
        }

        // Transfer Slip: nearest seeker under 4:00 away and a better zone reachable.
        if (CardType.TRANSFER_SLIP in state.hand && zoneStation != null &&
            nearestSeekerSeconds != null && nearestSeekerSeconds < RELOCATE_PRESSURE_SECONDS
        ) {
            val target = bestRelocationTarget(state, zoneStation, nearestSeekerSeconds)
            if (target != null) return play(CardType.TRANSFER_SLIP, EffectParams.RelocateParams(target))
        }

        val curseGate = { rng.nextDouble() < 0.15 + 0.85 * aggression }

        // Movement curse when the nearest seeker is under 8:00 from the zone (§6.2).
        if (curseCapFree && nearestSeekerSeconds != null &&
            nearestSeekerSeconds < MOVEMENT_CURSE_PRESSURE_SECONDS
        ) {
            val movementCurse = listOf(CardType.STALLED_TRAIN, CardType.TICKET_INSPECTION)
                .firstOrNull { it in state.hand }
            if (movementCurse != null && curseGate()) return play(movementCurse)
        }

        // Detour on the line the nearest seeker is currently using (§6.2).
        if (curseCapFree && CardType.DETOUR in state.hand && zoneStation != null) {
            val route = nearestSeekerRouteId(state, zoneStation)
            if (route != null && curseGate()) {
                return play(CardType.DETOUR, EffectParams.DetourParams(route))
            }
        }

        // Dead Zone when >= 2 questions were answered in the last 6:00 (§6.2).
        if (curseCapFree && CardType.DEAD_ZONE in state.hand) {
            val recentAnswers = state.eventLog.count {
                it is GameEvent.AnswerDelivered && it.gameTimeMillis > now - DEAD_ZONE_WINDOW_MILLIS
            }
            if (recentAnswers >= 2 && curseGate()) return play(CardType.DEAD_ZONE)
        }

        // Pure-value utilities, when the hand has room for the draws.
        if (CardType.BIGGER_BAG in state.hand) return play(CardType.BIGGER_BAG)
        if (CardType.GOLDEN_TICKET in state.hand && state.hand.size + 2 <= state.handLimit) {
            return play(CardType.GOLDEN_TICKET)
        }
        if (CardType.FOUND_WALLET in state.hand && state.hand.size + 1 <= state.handLimit) {
            return play(CardType.FOUND_WALLET)
        }
        return emptyList()
    }

    /** Keep choice for draw-D-keep-K: §6.2 priority C11 > C12 > C3 > C10 > C2 > C4 > others. */
    private fun pickByKeepPriority(drawn: List<CardType>, keep: Int): List<CardType> =
        drawn.sortedBy { keepPriorityIndex(it) }.take(keep.coerceAtMost(drawn.size))

    private fun keepPriorityIndex(type: CardType): Int =
        KEEP_PRIORITY.indexOf(type).let { if (it >= 0) it else KEEP_PRIORITY.size }

    // ------------------------------------------------------------------ helpers

    /** Travel seconds of the closest seeker to [zoneStationId], null when unknown. */
    private fun nearestSeekerTravelSeconds(state: GameState, zoneStationId: String): Int? =
        seekerStations(state)
            .mapNotNull { travelTimesFrom(it)[zoneStationId] }
            .minOrNull()

    /** The route the nearest (by travel time) seeker is currently riding, if any. */
    private fun nearestSeekerRouteId(state: GameState, zoneStationId: String): String? {
        val nearest = state.roles.entries
            .filter { it.value == Role.SEEKER }
            .mapNotNull { (id, _) ->
                val position = state.positions[id] ?: return@mapNotNull null
                val station = BrainPositions.stationIdOf(position, network) ?: return@mapNotNull null
                val seconds = travelTimesFrom(station)[zoneStationId] ?: return@mapNotNull null
                Triple(id, position, seconds)
            }
            .minWithOrNull(compareBy({ it.third }, { it.first.value })) ?: return null
        return (nearest.second as? PlayerPosition.EdgePosition)?.routeId
    }

    /**
     * Best Transfer Slip target: an eligible hiding station within 5 graph hops
     * (sim "move up to 5 edges", §4.2 C13) whose nearest-seeker travel time beats the
     * current zone's by a clear margin.
     */
    private fun bestRelocationTarget(state: GameState, zoneStationId: String, currentSeconds: Int): String? {
        val start = HidingEligibility.resolveStartStationId(state.config, network)
        val eligible = HidingEligibility.eligibleHidingStationIds(network, start).toSet()
        val hops = network.bfsHops(zoneStationId)
        val seekers = seekerStations(state)
        return network.stations
            .asSequence()
            .map { it.id }
            .filter { it != zoneStationId && it in eligible }
            .filter { (hops[it] ?: Int.MAX_VALUE) <= GameRules.RELOCATE_MAX_EDGES }
            .mapNotNull { candidate ->
                val worst = seekers.mapNotNull { travelTimesFrom(it)[candidate] }.minOrNull()
                    ?: return@mapNotNull null
                candidate to worst
            }
            .filter { it.second > currentSeconds + RELOCATE_IMPROVEMENT_SECONDS }
            .maxWithOrNull(compareBy({ it.second }, { it.first }))
            ?.first
    }

    private fun seekerStations(state: GameState): List<String> = state.roles.entries
        .filter { it.value == Role.SEEKER }
        .mapNotNull { BrainPositions.stationIdOf(state.positions[it.key], network) }
        .sorted()

    private fun travelTimesFrom(stationId: String): Map<String, Int> =
        travelTimeCache.getOrPut(stationId) { network.dijkstraTimes(stationId) }

    private companion object {
        /** §6.2 gain-threshold veto base, bits. */
        const val VETO_THRESHOLD_BITS = 1.2

        /** §6.2: movement curse pressure trigger (8:00). */
        const val MOVEMENT_CURSE_PRESSURE_SECONDS = 8 * 60

        /** Transfer Slip pressure trigger (4:00). */
        const val RELOCATE_PRESSURE_SECONDS = 4 * 60

        /** Required margin for a relocation target to count as "better". */
        const val RELOCATE_IMPROVEMENT_SECONDS = 120

        /** §6.2 Dead Zone trigger window (6:00). */
        const val DEAD_ZONE_WINDOW_MILLIS = 6 * 60_000L

        /** Within the 1.5 km decoy cap with margin for haversine rounding. */
        const val DECOY_PLAN_DISTANCE_METERS = 1_450.0

        val BONUS_LARGEST_FIRST = listOf(
            CardType.NIGHT_OWL_SERVICE,
            CardType.EXPRESS_SKIP,
            CardType.RUSH_HOUR_DELAY,
        )

        /** §6.2 keep priority; unlisted types rank below, in stable order. */
        val KEEP_PRIORITY = listOf(
            CardType.CONDUCTORS_OVERRIDE, // C11
            CardType.GHOST_ECHO, // C12
            CardType.NIGHT_OWL_SERVICE, // C3
            CardType.DETOUR, // C10
            CardType.EXPRESS_SKIP, // C2
            CardType.STALLED_TRAIN, // C4
            CardType.DEAD_ZONE,
            CardType.TICKET_INSPECTION,
            CardType.LOCAL_SERVICE,
            CardType.TRANSFER_SLIP,
            CardType.GOLDEN_TICKET,
            CardType.RUSH_HOUR_DELAY,
            CardType.FOUND_WALLET,
            CardType.OFF_PEAK_PASS,
            CardType.LOST_AND_FOUND,
            CardType.BIGGER_BAG,
            CardType.SERVICE_CHANGE,
            CardType.TUNNEL_VISION,
            CardType.SCRAMBLED_SIGNAL,
            CardType.U_TURN,
        )
    }
}
