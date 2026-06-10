package io.terminus.core.ai

import io.terminus.core.geo.GeoMath
import io.terminus.core.transit.Station
import io.terminus.core.transit.TransitNetwork
import kotlin.math.exp
import kotlin.random.Random

/** A scored candidate hiding spot (GAME_DESIGN.md §6.1). */
data class ScoredSpot(
    val stationId: String,
    val score: Double,
    /** Normalized travel-time term T(s); used alone by Easy. */
    val travelTimeTerm: Double,
)

/**
 * AI hider hiding-spot scoring (GAME_DESIGN.md §6.1):
 * `score(s) = wT·T(s) + wR·R(s) + wA·A(s) + wRand·rand(s)`, default weights
 * (0.40, 0.25, 0.20, 0.15), overridable by [PersonalityProfile.spotWeights].
 *
 * Stations unreachable from the start within 85% of the hiding phase are excluded
 * outright — the hider could not arrive in time (§6.1: unreachable stations score 0).
 */
object SpotScorer {

    /** Default §6.1 weights. */
    val DEFAULT_WEIGHTS = SpotWeights(travelTime = 0.40, remoteness = 0.25, ambiguity = 0.20, random = 0.15)

    /** Fraction of the hiding phase the travel-time cap uses (§6.1). */
    const val REACHABLE_FRACTION = 0.85

    /**
     * Scores every eligible, reachable hiding station, applying difficulty
     * ([DifficultyProfile.spotScoring]) and personality (spotWeights, obscurityBias,
     * [PersonalityProfile.rejectsSalientSpots]) modifiers. Sorted descending by score,
     * ties by station id. [rng] supplies the per-station rand(s) term, consumed in
     * sorted-station order (deterministic per seed).
     */
    fun scoreSpots(
        network: TransitNetwork,
        startStationId: String,
        hidingPhaseSeconds: Long,
        difficulty: DifficultyProfile,
        personality: PersonalityProfile,
        rng: Random,
    ): List<ScoredSpot> {
        val eligibleIds = HidingEligibility.eligibleHidingStationIds(network, startStationId)
        val times = network.dijkstraTimes(startStationId)
        val capSeconds = REACHABLE_FRACTION * hidingPhaseSeconds

        var stations = eligibleIds
            .mapNotNull { network.stationsById[it] }
            .filter { (times[it.id]?.toDouble() ?: Double.MAX_VALUE) <= capSeconds }

        // The Ghost rejects interchanges and termini outright (§6.4).
        if (personality.rejectsSalientSpots) {
            val filtered = stations.filter { !it.isInterchange && !it.isTerminus }
            if (filtered.isNotEmpty()) stations = filtered
        }
        // Hard rejects stations whose name appears as a terminus of any line (§6.1).
        if (difficulty.spotScoring == SpotScoringStrategy.FULL_WITH_SALIENCE_REJECTION) {
            val terminusNames = network.routes
                .flatMap { listOfNotNull(it.orderedStationIds.firstOrNull(), it.orderedStationIds.lastOrNull()) }
                .mapNotNull { network.stationsById[it]?.name }
                .toSet()
            val filtered = stations.filter { it.name !in terminusNames }
            if (filtered.isNotEmpty()) stations = filtered
        }
        if (stations.isEmpty()) return emptyList()

        // rand(s) in sorted order so RNG consumption is deterministic.
        val sorted = stations.sortedBy { it.id }
        val rand = sorted.associate { it.id to rng.nextDouble() }

        val travelTerm = sorted.associate { it.id to (times.getValue(it.id) / capSeconds).coerceIn(0.0, 1.0) }
        val remotenessRaw = sorted.associate { it.id to meanDistanceTo5Nearest(it, network) }
        val remotenessMax = remotenessRaw.values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val ambiguity = sorted.associate { it.id to ambiguityFraction(it, network) }

        val weights = personality.spotWeights ?: DEFAULT_WEIGHTS
        val scored = sorted.map { station ->
            val score =
                weights.travelTime * travelTerm.getValue(station.id) +
                    weights.remoteness * (remotenessRaw.getValue(station.id) / remotenessMax) +
                    weights.ambiguity * ambiguity.getValue(station.id) +
                    weights.random * rand.getValue(station.id) +
                    // Personality obscurity bias: low-salience stations get extra weight (§6.4).
                    personality.obscurityBias * 0.10 *
                    (if (!station.isInterchange && !station.isTerminus) 1.0 else 0.0)
            ScoredSpot(station.id, score, travelTerm.getValue(station.id))
        }
        return scored.sortedWith(compareByDescending<ScoredSpot> { it.score }.thenBy { it.stationId })
    }

    /**
     * Picks the hiding spot (§6.1 difficulty rules + §6.4 temperature):
     * Easy picks uniformly from the top 50% by T only; Medium/Hard apply a temperature
     * softmax over the full-formula scores (temperature 0 → argmax).
     */
    fun chooseSpot(
        network: TransitNetwork,
        startStationId: String,
        hidingPhaseSeconds: Long,
        difficulty: DifficultyProfile,
        personality: PersonalityProfile,
        rng: Random,
    ): String? {
        val scored = scoreSpots(network, startStationId, hidingPhaseSeconds, difficulty, personality, rng)
        if (scored.isEmpty()) return null
        return when (difficulty.spotScoring) {
            SpotScoringStrategy.DISTANCE_ONLY -> {
                val byTravel = scored.sortedWith(
                    compareByDescending<ScoredSpot> { it.travelTimeTerm }.thenBy { it.stationId },
                )
                val topHalf = byTravel.take(((byTravel.size + 1) / 2).coerceAtLeast(1))
                topHalf[rng.nextInt(topHalf.size)].stationId
            }
            SpotScoringStrategy.FULL_FORMULA,
            SpotScoringStrategy.FULL_WITH_SALIENCE_REJECTION,
            -> softmaxPick(scored, personality.temperature, rng)
        }
    }

    private fun softmaxPick(scored: List<ScoredSpot>, temperature: Double, rng: Random): String {
        if (temperature <= 1e-9) return scored.first().stationId
        val max = scored.first().score
        val expWeights = scored.map { exp((it.score - max) / temperature) }
        val total = expWeights.sum()
        var draw = rng.nextDouble() * total
        for (i in scored.indices) {
            draw -= expWeights[i]
            if (draw <= 0.0) return scored[i].stationId
        }
        return scored.last().stationId
    }

    /** R(s): mean haversine distance to the 5 nearest other stations (§6.1). */
    private fun meanDistanceTo5Nearest(station: Station, network: TransitNetwork): Double {
        val distances = network.stations
            .asSequence()
            .filter { it.id != station.id }
            .map { GeoMath.haversineMeters(station.latLng, it.latLng) }
            .sorted()
            .take(5)
            .toList()
        return if (distances.isEmpty()) 0.0 else distances.average()
    }

    /** A(s): fraction of all stations sharing the full Dossier attribute tuple (§6.1). */
    private fun ambiguityFraction(station: Station, network: TransitNetwork): Double {
        if (network.stations.isEmpty()) return 0.0
        val tuple = dossierTuple(station)
        return network.stations.count { dossierTuple(it) == tuple }.toDouble() / network.stations.size
    }

    private fun dossierTuple(s: Station) = listOf(s.mode.name, s.isInterchange, s.isTerminus, s.zoneId ?: "")
}
