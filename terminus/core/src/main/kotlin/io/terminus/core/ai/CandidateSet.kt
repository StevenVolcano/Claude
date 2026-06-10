package io.terminus.core.ai

import io.terminus.core.game.GameConfig
import io.terminus.core.game.GameRules
import io.terminus.core.game.PlayMode
import io.terminus.core.geo.GeoMath
import io.terminus.core.questions.Answer
import io.terminus.core.questions.CompassAxis
import io.terminus.core.questions.CompassDirectionValue
import io.terminus.core.questions.DossierAttribute
import io.terminus.core.questions.QuestionSpec
import io.terminus.core.transit.Station
import io.terminus.core.transit.TransitNetwork
import kotlin.math.ln

/**
 * The shared AI seeker belief state (GAME_DESIGN.md §6.3): one weight `w(s) ∈ [0,1]`
 * per eligible hiding station, initialized uniform, multiplicatively filtered per
 * delivered answer, renormalized after each update.
 *
 * Immutable; [updated] returns a new set. Iteration order is the sorted station-id
 * order, so entropy sums and tie-breaking are deterministic.
 */
class CandidateSet private constructor(
    /** Normalized weight per station id, in sorted-id iteration order. */
    val weights: Map<String, Double>,
) {
    /** Shannon entropy of the weights, in bits; 0 for an empty or single-spike set. */
    fun entropy(): Double {
        var h = 0.0
        for (w in weights.values) {
            if (w > 0.0) h -= w * (ln(w) / LN2)
        }
        return h
    }

    /**
     * The [k] highest-weighted stations as (stationId, weight), descending by weight,
     * ties broken by ascending station id.
     */
    fun topWeighted(k: Int): List<Pair<String, Double>> =
        weights.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(k)
            .map { it.key to it.value }

    /** Weight of [stationId], 0.0 when not tracked. */
    fun weightOf(stationId: String): Double = weights[stationId] ?: 0.0

    /**
     * Applies one delivered answer (GAME_DESIGN.md §6.3 answer update).
     *
     * - Consistent stations keep their weight; inconsistent stations are multiplied by
     *   [DifficultyProfile.answerFilterEpsilon].
     * - Decoy-flagged answers ([wasDecoy]) are discarded entirely when
     *   [DifficultyProfile.decoyAnswerEpsilon] is null (Medium/Hard); Easy applies
     *   them at ε = 0.5.
     * - Consistency for Radius/Compass/Thermometer in GPS mode uses the 300 m hiding
     *   disc rule (consistent if any point of the disc is consistent); sim mode tests
     *   the node exactly. See [AnswerConsistency].
     * - Renormalizes; if every weight reaches zero (over-filtering against bad data),
     *   the set resets to uniform rather than collapsing.
     */
    fun updated(
        spec: QuestionSpec,
        answer: Answer,
        wasDecoy: Boolean,
        profile: DifficultyProfile,
        network: TransitNetwork,
        playMode: PlayMode,
    ): CandidateSet {
        val epsilon = if (wasDecoy) {
            profile.decoyAnswerEpsilon ?: return this // Medium/Hard discard decoy answers
        } else {
            profile.answerFilterEpsilon
        }
        val consistent = AnswerConsistency.consistentStations(spec, answer, weights.keys, network, playMode)
        val raw = LinkedHashMap<String, Double>(weights.size)
        for ((id, w) in weights) {
            raw[id] = if (id in consistent) w else w * epsilon
        }
        return normalized(raw)
    }

    companion object {
        private val LN2 = ln(2.0)

        /** Uniform set over [stationIds] (GAME_DESIGN.md §6.3 initialization). */
        fun uniform(stationIds: Collection<String>): CandidateSet {
            val sorted = stationIds.distinct().sorted()
            val w = if (sorted.isEmpty()) 0.0 else 1.0 / sorted.size
            return CandidateSet(sorted.associateWithTo(LinkedHashMap()) { w })
        }

        private fun normalized(raw: LinkedHashMap<String, Double>): CandidateSet {
            val total = raw.values.sum()
            if (total <= 0.0) return uniform(raw.keys)
            for ((id, w) in raw) raw[id] = w / total
            return CandidateSet(raw)
        }
    }
}

/**
 * Per-answer station consistency tests (GAME_DESIGN.md §6.3).
 *
 * GPS mode: for Radius Ping, Compass Call, and Thermometer a station is consistent if
 * **any point of its 300 m hiding disc** is consistent — implemented as
 * distance-to-disc adjusted comparisons (± [GameRules.HIDING_ZONE_RADIUS_METERS]).
 * Sim mode tests the node exactly. The nearest-station questions (Q4–Q7) are tested
 * on the candidate station itself in both modes (the hider's nearest station is the
 * hiding station).
 */
object AnswerConsistency {

    /** The subset of [stationIds] consistent with ([spec], [answer]). */
    fun consistentStations(
        spec: QuestionSpec,
        answer: Answer,
        stationIds: Collection<String>,
        network: TransitNetwork,
        playMode: PlayMode,
    ): Set<String> {
        // Per-answer shared precomputation (one BFS per Rail Range answer).
        val railHops: Map<String, Int>? = (spec as? QuestionSpec.RailRange)
            ?.let { network.bfsHops(it.referenceStationId) }
        val out = HashSet<String>()
        for (id in stationIds) {
            val station = network.stationsById[id] ?: continue
            if (isConsistent(spec, answer, station, network, playMode, railHops)) out.add(id)
        }
        return out
    }

    /** Is [station] consistent with ([spec], [answer])? */
    fun isConsistent(
        spec: QuestionSpec,
        answer: Answer,
        station: Station,
        network: TransitNetwork,
        playMode: PlayMode,
        railHopsFromReference: Map<String, Int>? = null,
    ): Boolean {
        val disc = if (playMode == PlayMode.GPS) GameRules.HIDING_ZONE_RADIUS_METERS else 0.0
        return when (spec) {
            is QuestionSpec.RadiusPing -> {
                val yes = (answer as Answer.YesNo).value
                val d = GeoMath.haversineMeters(station.latLng, spec.center)
                if (yes) {
                    // Any point of the disc within R: nearest disc point distance <= R.
                    d - disc <= spec.radius.meters
                } else {
                    // Any point of the disc outside R: farthest disc point distance > R.
                    d + disc > spec.radius.meters
                }
            }

            is QuestionSpec.CompassCall -> {
                val reference = network.stationsById[spec.referenceStationId] ?: return true
                val direction = (answer as Answer.CompassDirection).direction
                // GPS: compare the disc's extreme point along the axis (any point of the
                // disc consistent); sim (disc = 0): the node itself, with the engine's
                // tie rule (ties are the positive direction, GAME_DESIGN.md §3 Q2).
                when (spec.axis) {
                    CompassAxis.NORTH_SOUTH -> when (direction) {
                        CompassDirectionValue.NORTH ->
                            northmostLat(station, disc) >= reference.latLng.lat
                        CompassDirectionValue.SOUTH ->
                            southmostLat(station, disc) < reference.latLng.lat
                        else -> true // malformed answer for the axis: no information
                    }
                    CompassAxis.EAST_WEST -> when (direction) {
                        CompassDirectionValue.EAST ->
                            eastmostLon(station, disc) >= reference.latLng.lon
                        CompassDirectionValue.WEST ->
                            westmostLon(station, disc) < reference.latLng.lon
                        else -> true
                    }
                }
            }

            is QuestionSpec.Thermometer -> {
                val resolvePos = spec.resolvePosition ?: return true // unresolved: no information
                val warmer = (answer as Answer.WarmerColder).warmer
                val dArm = GeoMath.haversineMeters(station.latLng, spec.armPosition)
                val dResolve = GeoMath.haversineMeters(station.latLng, resolvePos)
                if (warmer) {
                    // Any disc point p with dist(p, resolve) < dist(p, arm):
                    // best case shifts each distance by the disc radius.
                    dResolve - disc < dArm + disc
                } else {
                    // "Colder" includes equality (GAME_DESIGN.md §3 Q3 tie rule).
                    dResolve + disc >= dArm - disc
                }
            }

            is QuestionSpec.LineCheck -> {
                val yes = (answer as Answer.YesNo).value
                (spec.routeId in station.routeIds) == yes
            }

            is QuestionSpec.StationDossier -> {
                val yes = (answer as Answer.YesNo).value
                val value = when (val attribute = spec.attribute) {
                    is DossierAttribute.Interchange -> station.isInterchange
                    is DossierAttribute.Terminus -> station.isTerminus
                    is DossierAttribute.Mode -> station.mode == attribute.mode
                    is DossierAttribute.Zone -> station.zoneId == attribute.zoneId
                }
                value == yes
            }

            is QuestionSpec.Lineup -> {
                val yes = (answer as Answer.YesNo).value
                (station.id in spec.stationIds) == yes
            }

            is QuestionSpec.RailRange -> {
                val yes = (answer as Answer.YesNo).value
                val hops = (railHopsFromReference ?: network.bfsHops(spec.referenceStationId))[station.id]
                (hops != null && hops <= spec.hops.hops) == yes
            }
        }
    }

    private fun northmostLat(station: Station, disc: Double): Double =
        if (disc == 0.0) station.latLng.lat else GeoMath.destinationPoint(station.latLng, 0.0, disc).lat

    private fun southmostLat(station: Station, disc: Double): Double =
        if (disc == 0.0) station.latLng.lat else GeoMath.destinationPoint(station.latLng, 180.0, disc).lat

    private fun eastmostLon(station: Station, disc: Double): Double =
        if (disc == 0.0) station.latLng.lon else GeoMath.destinationPoint(station.latLng, 90.0, disc).lon

    private fun westmostLon(station: Station, disc: Double): Double =
        if (disc == 0.0) station.latLng.lon else GeoMath.destinationPoint(station.latLng, 270.0, disc).lon
}

/** Shared eligibility/start-station helpers for both brains. */
object HidingEligibility {

    /**
     * The configured start station, or the documented fallback: the highest-degree
     * interchange in the network (GAME_DESIGN.md §8 item 4), degree = distinct routes,
     * ties by lowest id; falls back to the highest-degree station when there is no
     * interchange.
     */
    fun resolveStartStationId(config: GameConfig, network: TransitNetwork): String {
        config.startStationId?.let { if (it in network.stationsById) return it }
        val pool = network.stations.filter { it.isInterchange }.ifEmpty { network.stations }
        return checkNotNull(
            pool.maxWithOrNull(compareBy<Station>({ it.routeIds.size }).thenByDescending { it.id }),
        ) { "Cannot resolve a start station on an empty network" }.id
    }

    /**
     * Eligible hiding stations (GAME_DESIGN.md §5.1): every network station except the
     * start station and stations within [GameRules.HIDING_EXCLUSION_HOPS] graph hops
     * of it. Sorted by id for determinism.
     */
    fun eligibleHidingStationIds(network: TransitNetwork, startStationId: String): List<String> {
        val hops = network.bfsHops(startStationId)
        return network.stations
            .map { it.id }
            .filter { (hops[it] ?: Int.MAX_VALUE) > GameRules.HIDING_EXCLUSION_HOPS }
            .sorted()
    }
}
