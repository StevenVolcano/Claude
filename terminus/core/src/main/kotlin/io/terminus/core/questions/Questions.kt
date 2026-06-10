package io.terminus.core.questions

import io.terminus.core.geo.LatLng
import io.terminus.core.transit.TransitMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The seven question categories Q1–Q7 (GAME_DESIGN.md §3), each with its fixed
 * per-category cooldown from the §3 table. Cooldowns are game time and are scaled by
 * `GameConfig.cooldownMultiplier`; Service Change (C19) may add to them at runtime.
 */
@Serializable
enum class QuestionCategory(val baseCooldownMillis: Long) {
    /** Q1 — Radius Ping, 5:00 cooldown. */
    RADIUS_PING(5 * 60_000L),

    /** Q2 — Compass Call, 4:00 cooldown. */
    COMPASS_CALL(4 * 60_000L),

    /** Q3 — Thermometer, 6:00 cooldown (starts when resolved). */
    THERMOMETER(6 * 60_000L),

    /** Q4 — Line Check, 3:00 cooldown. */
    LINE_CHECK(3 * 60_000L),

    /** Q5 — Station Dossier, 3:00 cooldown. */
    STATION_DOSSIER(3 * 60_000L),

    /** Q6 — Lineup, 5:00 cooldown. */
    LINEUP(5 * 60_000L),

    /** Q7 — Rail Range, 5:00 cooldown. */
    RAIL_RANGE(5 * 60_000L),
}

/** Global question rule constants (GAME_DESIGN.md §3 general rules). */
object QuestionRules {
    /** Global cooldown between any two questions, shared across all seekers. */
    const val GLOBAL_COOLDOWN_MILLIS: Long = 2 * 60_000L

    /** Hider response window after a question is asked (veto/decoy plays). */
    const val RESPONSE_WINDOW_MILLIS: Long = 20_000L

    /** Thermometer minimum straight-line movement before resolution, GPS mode (Q3). */
    const val THERMOMETER_MIN_MOVE_METERS: Double = 750.0

    /** Thermometer minimum graph edges moved before resolution, sim mode (Q3). */
    const val THERMOMETER_MIN_MOVE_EDGES: Int = 2

    /** Scrambled Signal (C7) answer delivery delay after computation. */
    const val SCRAMBLED_SIGNAL_DELAY_MILLIS: Long = 5 * 60_000L
}

/** Radius choices for Q1 Radius Ping with their compensation (GAME_DESIGN.md §3). */
@Serializable
enum class PingRadius(val meters: Int, val compensation: CompensationRule) {
    M500(500, CompensationRule(draw = 4, keep = 2)),
    KM1(1_000, CompensationRule(draw = 3, keep = 2)),
    KM2(2_000, CompensationRule(draw = 3, keep = 1)),
    KM5(5_000, CompensationRule(draw = 2, keep = 1)),
}

/** Axis choice for Q2 Compass Call (GAME_DESIGN.md §3). */
@Serializable
enum class CompassAxis {
    NORTH_SOUTH,
    EAST_WEST,
}

/** Hop-count choices for Q7 Rail Range with their compensation (GAME_DESIGN.md §3). */
@Serializable
enum class RailRangeHops(val hops: Int, val compensation: CompensationRule) {
    TWO(2, CompensationRule(draw = 3, keep = 2)),
    FOUR(4, CompensationRule(draw = 3, keep = 1)),
    EIGHT(8, CompensationRule(draw = 2, keep = 1)),
}

/**
 * The attribute queried by Q5 Station Dossier (GAME_DESIGN.md §3 Q5):
 * interchange?, terminus?, mode, or fare zone.
 */
@Serializable
sealed class DossierAttribute {
    /** (a) Is the hider's nearest station an interchange (≥2 distinct routes)? */
    @Serializable
    @SerialName("interchange")
    data object Interchange : DossierAttribute()

    /** (b) Is it a terminus (first/last stop of any route pattern)? */
    @Serializable
    @SerialName("terminus")
    data object Terminus : DossierAttribute()

    /** (c) Is its mode [mode]? */
    @Serializable
    @SerialName("mode")
    data class Mode(val mode: TransitMode) : DossierAttribute()

    /** (d) Is it in fare zone [zoneId]? Only offered when the GTFS feed provided zones. */
    @Serializable
    @SerialName("zone")
    data class Zone(val zoneId: String) : DossierAttribute()
}

/**
 * A fully parameterised question, one subclass per category Q1–Q7
 * (GAME_DESIGN.md §3; ARCHITECTURE.md §1.1 `questions`). Answer computation
 * (`QuestionEngine`) and cooldown tracking are W3's.
 */
@Serializable
sealed class QuestionSpec {
    /** The category this spec belongs to (drives cooldowns and compensation). */
    abstract val category: QuestionCategory

    /**
     * Q1 Radius Ping: is `haversine(hider, center) <= radius`? (GAME_DESIGN.md §3 Q1).
     *
     * @property center the asking seeker's current position or any station's coordinate.
     * @property centerStationId set when [center] was chosen as a station, for display; null otherwise.
     * @property radius one of 500 m / 1 km / 2 km / 5 km.
     */
    @Serializable
    @SerialName("radiusPing")
    data class RadiusPing(
        val center: LatLng,
        val centerStationId: String? = null,
        val radius: PingRadius,
    ) : QuestionSpec() {
        override val category: QuestionCategory get() = QuestionCategory.RADIUS_PING
    }

    /**
     * Q2 Compass Call: is the hider north/south (or east/west) of station X?
     * Ties resolve as the positive direction (GAME_DESIGN.md §3 Q2).
     */
    @Serializable
    @SerialName("compassCall")
    data class CompassCall(
        val referenceStationId: String,
        val axis: CompassAxis,
    ) : QuestionSpec() {
        override val category: QuestionCategory get() = QuestionCategory.COMPASS_CALL
    }

    /**
     * Q3 Thermometer: armed at the seeker's position; resolves after the seeker moves
     * ≥750 m straight-line (GPS) or ≥2 graph edges (sim); equal distances → "Colder"
     * (GAME_DESIGN.md §3 Q3).
     *
     * @property armPosition the seeker position at arm time.
     * @property resolvePosition the seeker position at resolution; null while still armed.
     */
    @Serializable
    @SerialName("thermometer")
    data class Thermometer(
        val armPosition: LatLng,
        val resolvePosition: LatLng? = null,
    ) : QuestionSpec() {
        override val category: QuestionCategory get() = QuestionCategory.THERMOMETER
    }

    /** Q4 Line Check: does the hider's nearest station serve route [routeId]? (GAME_DESIGN.md §3 Q4). */
    @Serializable
    @SerialName("lineCheck")
    data class LineCheck(
        val routeId: String,
    ) : QuestionSpec() {
        override val category: QuestionCategory get() = QuestionCategory.LINE_CHECK
    }

    /** Q5 Station Dossier: boolean attribute of the hider's nearest station (GAME_DESIGN.md §3 Q5). */
    @Serializable
    @SerialName("stationDossier")
    data class StationDossier(
        val attribute: DossierAttribute,
    ) : QuestionSpec() {
        override val category: QuestionCategory get() = QuestionCategory.STATION_DOSSIER
    }

    /**
     * Q6 Lineup: is the hider's nearest station one of exactly 3 chosen stations?
     * Never reveals which (GAME_DESIGN.md §3 Q6).
     *
     * @property stationIds exactly 3 station ids.
     */
    @Serializable
    @SerialName("lineup")
    data class Lineup(
        val stationIds: List<String>,
    ) : QuestionSpec() {
        override val category: QuestionCategory get() = QuestionCategory.LINEUP
    }

    /**
     * Q7 Rail Range: is the BFS hop distance (transfers count as 1 hop) from station X
     * to the hider's nearest station ≤ N? (GAME_DESIGN.md §3 Q7).
     */
    @Serializable
    @SerialName("railRange")
    data class RailRange(
        val referenceStationId: String,
        val hops: RailRangeHops,
    ) : QuestionSpec() {
        override val category: QuestionCategory get() = QuestionCategory.RAIL_RANGE
    }
}

/** Direction value delivered by a Compass Call answer (GAME_DESIGN.md §3 Q2). */
@Serializable
enum class CompassDirectionValue {
    NORTH,
    SOUTH,
    EAST,
    WEST,
}

/** A computed answer (GAME_DESIGN.md §3; ARCHITECTURE.md §1.1 `questions`). */
@Serializable
sealed class Answer {
    /** Yes/No answers: Q1, Q4, Q5, Q6, Q7. */
    @Serializable
    @SerialName("yesNo")
    data class YesNo(val value: Boolean) : Answer()

    /** Q2 Compass Call answer. */
    @Serializable
    @SerialName("compass")
    data class CompassDirection(val direction: CompassDirectionValue) : Answer()

    /** Q3 Thermometer answer; warmer = resolve position is closer than arm position. */
    @Serializable
    @SerialName("warmerColder")
    data class WarmerColder(val warmer: Boolean) : Answer()
}

/**
 * Compensation for a delivered answer: "draw [draw], keep [keep]"
 * (GAME_DESIGN.md §3 general rules). Off-Peak Pass (C16) doubles both.
 */
@Serializable
data class CompensationRule(
    val draw: Int,
    val keep: Int,
)

/**
 * The static compensation table from GAME_DESIGN.md §3. Radius Ping and Rail Range
 * compensation varies by parameter (see [PingRadius] and [RailRangeHops], which carry
 * their own rules); the other categories are fixed.
 */
object CompensationTable {
    /** Q1 per-radius rules, keyed by radius choice. */
    val RADIUS_PING: Map<PingRadius, CompensationRule> =
        PingRadius.entries.associateWith { it.compensation }

    /** Q2 Compass Call: draw 2 keep 1. */
    val COMPASS_CALL: CompensationRule = CompensationRule(draw = 2, keep = 1)

    /** Q3 Thermometer: draw 3 keep 1. */
    val THERMOMETER: CompensationRule = CompensationRule(draw = 3, keep = 1)

    /** Q4 Line Check: draw 2 keep 1. */
    val LINE_CHECK: CompensationRule = CompensationRule(draw = 2, keep = 1)

    /** Q5 Station Dossier: draw 2 keep 1. */
    val STATION_DOSSIER: CompensationRule = CompensationRule(draw = 2, keep = 1)

    /** Q6 Lineup: draw 2 keep 1. */
    val LINEUP: CompensationRule = CompensationRule(draw = 2, keep = 1)

    /** Q7 per-hop-count rules, keyed by hop choice. */
    val RAIL_RANGE: Map<RailRangeHops, CompensationRule> =
        RailRangeHops.entries.associateWith { it.compensation }
}
