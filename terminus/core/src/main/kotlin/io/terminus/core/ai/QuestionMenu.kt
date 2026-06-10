package io.terminus.core.ai

import io.terminus.core.geo.LatLng
import io.terminus.core.questions.Answer
import io.terminus.core.questions.CompassAxis
import io.terminus.core.questions.CompassDirectionValue
import io.terminus.core.questions.DossierAttribute
import io.terminus.core.questions.PingRadius
import io.terminus.core.questions.QuestionCategory
import io.terminus.core.questions.QuestionSpec
import io.terminus.core.questions.RailRangeHops
import io.terminus.core.transit.TransitNetwork
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * One candidate question on the seeker menu (GAME_DESIGN.md §6.3).
 *
 * @property askSpec the spec emitted in a `GameCommand.AskQuestion` (a Thermometer is
 *   asked armed, with a null resolve position).
 * @property gainSpec the spec used for expected-information-gain modeling; for a
 *   Thermometer this carries the modeled resolve position (the seeker's planned target).
 * @property cooldownMillis the effective category cooldown (base × config multiplier
 *   + Service Change bonus), the cost term of the §6.3 score.
 */
data class MenuEntry(
    val askSpec: QuestionSpec,
    val gainSpec: QuestionSpec,
    val cooldownMillis: Long,
)

/** A scored menu entry (GAME_DESIGN.md §6.3 question selection). */
data class ScoredEntry(
    val entry: MenuEntry,
    /** Expected entropy reduction of the CandidateSet, bits. */
    val reductionBits: Double,
    /** `reductionBits / (1 + cooldownMinutes / 10)`. */
    val gainPerCost: Double,
)

/**
 * Builds the fixed ~40-question candidate menu of GAME_DESIGN.md §6.3: each category
 * with parameter choices anchored to the current top-10 weighted stations and the
 * seeker's position.
 */
object QuestionMenu {

    /**
     * @param candidates current shared CandidateSet.
     * @param seekerPosition the asking seeker's coordinate (Q1 center, Q3 arm point).
     * @param seekerStationId the seeker's current/next station (Q7 reference).
     * @param allowedCategories categories not blocked by cooldowns, Tunnel Vision,
     *   an already-armed Thermometer, etc. (the brain computes this from GameState).
     */
    fun build(
        candidates: CandidateSet,
        seekerPosition: LatLng,
        seekerStationId: String,
        network: TransitNetwork,
        allowedCategories: Set<QuestionCategory>,
        cooldownMillisOf: (QuestionCategory) -> Long,
    ): List<MenuEntry> {
        val top = candidates.topWeighted(10).mapNotNull { network.stationsById[it.first] }
        if (top.isEmpty()) return emptyList()
        val entries = ArrayList<MenuEntry>(44)

        fun add(askSpec: QuestionSpec, gainSpec: QuestionSpec = askSpec) {
            if (askSpec.category in allowedCategories) {
                entries.add(MenuEntry(askSpec, gainSpec, cooldownMillisOf(askSpec.category)))
            }
        }

        // Q1 Radius Ping: all radii at the seeker, 1 km / 2 km / 5 km on the top-3 stations.
        for (radius in PingRadius.entries) {
            add(QuestionSpec.RadiusPing(center = seekerPosition, radius = radius))
        }
        for (station in top.take(3)) {
            for (radius in listOf(PingRadius.KM1, PingRadius.KM2, PingRadius.KM5)) {
                add(QuestionSpec.RadiusPing(station.latLng, station.id, radius))
            }
        }

        // Q2 Compass Call: both axes on the top-3 stations.
        for (station in top.take(3)) {
            for (axis in CompassAxis.entries) {
                add(QuestionSpec.CompassCall(station.id, axis))
            }
        }

        // Q3 Thermometer: armed at the seeker; gain modeled with resolution at the
        // top-weighted station (the place the seeker would head next).
        add(
            askSpec = QuestionSpec.Thermometer(armPosition = seekerPosition),
            gainSpec = QuestionSpec.Thermometer(seekerPosition, resolvePosition = top.first().latLng),
        )

        // Q4 Line Check: the distinct routes serving the top-10 stations.
        for (routeId in top.flatMap { it.routeIds }.distinct().sorted().take(6)) {
            add(QuestionSpec.LineCheck(routeId))
        }

        // Q5 Station Dossier: interchange?, terminus?, top-1 mode, top-1 zone (if zoned).
        add(QuestionSpec.StationDossier(DossierAttribute.Interchange))
        add(QuestionSpec.StationDossier(DossierAttribute.Terminus))
        add(QuestionSpec.StationDossier(DossierAttribute.Mode(top.first().mode)))
        top.first().zoneId?.let { add(QuestionSpec.StationDossier(DossierAttribute.Zone(it))) }

        // Q6 Lineup: top 1-3 and top 4-6.
        if (top.size >= 3) add(QuestionSpec.Lineup(top.take(3).map { it.id }))
        if (top.size >= 6) add(QuestionSpec.Lineup(top.subList(3, 6).map { it.id }))

        // Q7 Rail Range: all hop counts from the seeker's station and the top station.
        for (reference in listOf(seekerStationId, top.first().id).distinct()) {
            if (reference !in network.stationsById) continue
            for (hops in RailRangeHops.entries) {
                add(QuestionSpec.RailRange(reference, hops))
            }
        }

        return entries
    }
}

/**
 * Expected-information-gain modeling (GAME_DESIGN.md §6.3): the weight-weighted
 * answer partition of the CandidateSet, and the expected post-question entropy.
 *
 * Stations are partitioned by the answer their node would produce (exact node
 * prediction in both modes — the partition is a model, not a filter).
 */
object InfoGain {

    /** Expected post-question entropy of [candidates] for [spec], bits. */
    fun expectedPostEntropyBits(
        spec: QuestionSpec,
        candidates: CandidateSet,
        network: TransitNetwork,
    ): Double {
        val railHops: Map<String, Int>? = (spec as? QuestionSpec.RailRange)
            ?.let { network.bfsHops(it.referenceStationId) }
        val partition = LinkedHashMap<Answer, MutableList<Double>>()
        for ((id, w) in candidates.weights) {
            val station = network.stationsById[id] ?: continue
            val answer = predictedAnswer(spec, station, network, railHops) ?: return candidates.entropy()
            partition.getOrPut(answer) { mutableListOf() }.add(w)
        }
        var expected = 0.0
        for (weights in partition.values) {
            val mass = weights.sum()
            if (mass <= 0.0) continue
            var h = 0.0
            for (w in weights) {
                if (w > 0.0) {
                    val p = w / mass
                    h -= p * (ln(p) / LN2)
                }
            }
            expected += mass * h
        }
        return expected
    }

    /** Expected entropy reduction of [spec], bits (never negative). */
    fun entropyReductionBits(
        spec: QuestionSpec,
        candidates: CandidateSet,
        network: TransitNetwork,
    ): Double = (candidates.entropy() - expectedPostEntropyBits(spec, candidates, network))
        .coerceAtLeast(0.0)

    /** Scores every menu entry per GAME_DESIGN.md §6.3. */
    fun score(
        entries: List<MenuEntry>,
        candidates: CandidateSet,
        network: TransitNetwork,
    ): List<ScoredEntry> = entries.map { entry ->
        val reduction = entropyReductionBits(entry.gainSpec, candidates, network)
        val cooldownMinutes = entry.cooldownMillis / 60_000.0
        ScoredEntry(entry, reduction, reduction / (1.0 + cooldownMinutes / 10.0))
    }

    /** The answer [station]'s node would produce for [spec], or null when unmodelable. */
    fun predictedAnswer(
        spec: QuestionSpec,
        station: io.terminus.core.transit.Station,
        network: TransitNetwork,
        railHopsFromReference: Map<String, Int>? = null,
    ): Answer? = when (spec) {
        is QuestionSpec.RadiusPing -> Answer.YesNo(
            io.terminus.core.geo.GeoMath.haversineMeters(station.latLng, spec.center) <= spec.radius.meters,
        )
        is QuestionSpec.CompassCall -> {
            val reference = network.stationsById[spec.referenceStationId] ?: return null
            Answer.CompassDirection(
                when (spec.axis) {
                    CompassAxis.NORTH_SOUTH ->
                        if (station.latLng.lat >= reference.latLng.lat) {
                            CompassDirectionValue.NORTH
                        } else {
                            CompassDirectionValue.SOUTH
                        }
                    CompassAxis.EAST_WEST ->
                        if (station.latLng.lon >= reference.latLng.lon) {
                            CompassDirectionValue.EAST
                        } else {
                            CompassDirectionValue.WEST
                        }
                },
            )
        }
        is QuestionSpec.Thermometer -> {
            val resolvePos = spec.resolvePosition ?: return null
            Answer.WarmerColder(
                io.terminus.core.geo.GeoMath.haversineMeters(station.latLng, resolvePos) <
                    io.terminus.core.geo.GeoMath.haversineMeters(station.latLng, spec.armPosition),
            )
        }
        is QuestionSpec.LineCheck -> Answer.YesNo(spec.routeId in station.routeIds)
        is QuestionSpec.StationDossier -> Answer.YesNo(
            when (val attribute = spec.attribute) {
                is DossierAttribute.Interchange -> station.isInterchange
                is DossierAttribute.Terminus -> station.isTerminus
                is DossierAttribute.Mode -> station.mode == attribute.mode
                is DossierAttribute.Zone -> station.zoneId == attribute.zoneId
            },
        )
        is QuestionSpec.Lineup -> Answer.YesNo(station.id in spec.stationIds)
        is QuestionSpec.RailRange -> {
            val hops = (railHopsFromReference ?: network.bfsHops(spec.referenceStationId))[station.id]
            Answer.YesNo(hops != null && hops <= spec.hops.hops)
        }
    }

    private val LN2 = ln(2.0)
}

/**
 * Final question choice (GAME_DESIGN.md §6.3, §6.5 + §6.4 temperature):
 * the difficulty strategy ranks entries, then the personality temperature is applied
 * as a softmax over the strategy's scores (temperature 0 → argmax; the Rat's 2.0 is
 * near-uniform). Easy ignores temperature and picks uniformly among the top 3.
 */
object QuestionPicker {

    fun pick(
        scored: List<ScoredEntry>,
        strategy: QuestionChoiceStrategy,
        temperature: Double,
        rng: Random,
    ): ScoredEntry? {
        if (scored.isEmpty()) return null
        return when (strategy) {
            QuestionChoiceStrategy.RANDOM_OF_TOP_3 -> {
                val top = scored.sortedByDescending { it.gainPerCost }.take(3)
                top[rng.nextInt(top.size)]
            }
            QuestionChoiceStrategy.MAX_INFO_GAIN ->
                softmaxPick(scored, scored.map { it.reductionBits }, temperature, rng)
            QuestionChoiceStrategy.MAX_GAIN_PER_COST ->
                softmaxPick(scored, scored.map { it.gainPerCost }, temperature, rng)
        }
    }

    private fun softmaxPick(
        scored: List<ScoredEntry>,
        keys: List<Double>,
        temperature: Double,
        rng: Random,
    ): ScoredEntry {
        if (temperature <= 1e-9) {
            // Argmax; ties go to the earliest entry in menu order (deterministic).
            var best = 0
            for (i in 1 until scored.size) if (keys[i] > keys[best]) best = i
            return scored[best]
        }
        val max = keys.max()
        val expWeights = keys.map { exp((it - max) / temperature) }
        val total = expWeights.sum()
        var draw = rng.nextDouble() * total
        for (i in scored.indices) {
            draw -= expWeights[i]
            if (draw <= 0.0) return scored[i]
        }
        return scored.last()
    }
}
