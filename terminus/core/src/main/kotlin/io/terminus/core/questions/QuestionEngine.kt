package io.terminus.core.questions

import io.terminus.core.game.PlayMode
import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.transit.Station
import io.terminus.core.transit.TransitNetwork

/**
 * Pure, stateless answer computation for the seven question categories Q1–Q7
 * (GAME_DESIGN.md §3; ARCHITECTURE.md §1.1 `questions`).
 *
 * Every answer is computed from exactly: the hider position, station
 * coordinates/attributes, route membership, and graph adjacency — nothing else
 * (GAME_DESIGN.md §3). The engine never mutates anything and holds no state;
 * cooldown bookkeeping lives in [CooldownTracker].
 *
 * ### Error contract
 * - **Empty network** (Q4–Q7 need the hider's nearest station, Q2/Q7 need a
 *   reference station): [TransitNetwork.nearestStation] returning null means the
 *   network has no stations at all — the game cannot run on an empty network, so
 *   this throws [IllegalStateException] with a clear message rather than guessing.
 * - **Invalid spec parameters** (unknown reference/route station ids, a Lineup
 *   without exactly 3 stations, an unresolved Thermometer passed to [answer]):
 *   [IllegalArgumentException]. These are caller bugs, not game states.
 */
object QuestionEngine {

    /**
     * Computes the truthful answer to [spec] for a hider at [hiderPos]
     * (GAME_DESIGN.md §3 table).
     *
     * For Q3 Thermometer the spec must carry a non-null
     * [QuestionSpec.Thermometer.resolvePosition]; use [answerThermometer] to compute
     * directly from positions, and [thermometerMayResolve] to check the movement
     * precondition first.
     *
     * @throws IllegalStateException if the question needs the hider's nearest station
     *   and the network is empty (see class KDoc).
     * @throws IllegalArgumentException on invalid spec parameters (see class KDoc).
     */
    fun answer(spec: QuestionSpec, hiderPos: LatLng, network: TransitNetwork): Answer = when (spec) {
        is QuestionSpec.RadiusPing ->
            // Q1: haversine(hider, center) <= R (boundary inclusive).
            Answer.YesNo(GeoMath.haversineMeters(hiderPos, spec.center) <= spec.radius.meters)

        is QuestionSpec.CompassCall -> {
            // Q2: compare hider lat/lon against the reference station; exact ties
            // resolve to the positive direction (North / East), GAME_DESIGN.md §3 Q2.
            val reference = requireKnownStation(network, spec.referenceStationId)
            val direction = when (spec.axis) {
                CompassAxis.NORTH_SOUTH ->
                    if (hiderPos.lat >= reference.latLng.lat) {
                        CompassDirectionValue.NORTH
                    } else {
                        CompassDirectionValue.SOUTH
                    }
                CompassAxis.EAST_WEST ->
                    if (hiderPos.lon >= reference.latLng.lon) {
                        CompassDirectionValue.EAST
                    } else {
                        CompassDirectionValue.WEST
                    }
            }
            Answer.CompassDirection(direction)
        }

        is QuestionSpec.Thermometer -> {
            val resolvePos = requireNotNull(spec.resolvePosition) {
                "Thermometer spec has no resolvePosition: it is still armed and cannot be answered yet"
            }
            answerThermometer(spec.armPosition, resolvePos, hiderPos)
        }

        is QuestionSpec.LineCheck ->
            // Q4: does the hider's nearest station serve route L?
            Answer.YesNo(spec.routeId in nearestStationOrThrow(network, hiderPos).routeIds)

        is QuestionSpec.StationDossier -> {
            // Q5: boolean attribute of the hider's nearest station. A Zone query
            // against a station with no zone data (zoneId == null) is simply false.
            val station = nearestStationOrThrow(network, hiderPos)
            val value = when (val attribute = spec.attribute) {
                is DossierAttribute.Interchange -> station.isInterchange
                is DossierAttribute.Terminus -> station.isTerminus
                is DossierAttribute.Mode -> station.mode == attribute.mode
                is DossierAttribute.Zone -> station.zoneId == attribute.zoneId
            }
            Answer.YesNo(value)
        }

        is QuestionSpec.Lineup -> {
            // Q6: is the hider's nearest station one of exactly 3 chosen stations?
            require(spec.stationIds.size == 3) {
                "Lineup requires exactly 3 stations, got ${spec.stationIds.size}"
            }
            Answer.YesNo(nearestStationOrThrow(network, hiderPos).id in spec.stationIds)
        }

        is QuestionSpec.RailRange -> {
            // Q7: BFS hop distance (every edge, incl. walking transfers, counts as
            // 1 hop) from reference X to the hider's nearest station <= N. A nearest
            // station unreachable from X has infinite hop distance -> No.
            requireKnownStation(network, spec.referenceStationId)
            val nearest = nearestStationOrThrow(network, hiderPos)
            val hops = network.bfsHops(spec.referenceStationId)[nearest.id]
            Answer.YesNo(hops != null && hops <= spec.hops.hops)
        }
    }

    /**
     * Q3 Thermometer answer from explicit positions (GAME_DESIGN.md §3 Q3):
     * "Warmer" iff `haversine(hider, resolvePos) < haversine(hider, armPos)`;
     * equal distances resolve to "Colder".
     */
    fun answerThermometer(armPos: LatLng, resolvePos: LatLng, hiderPos: LatLng): Answer.WarmerColder =
        Answer.WarmerColder(
            warmer = GeoMath.haversineMeters(hiderPos, resolvePos) < GeoMath.haversineMeters(hiderPos, armPos),
        )

    /**
     * May an armed Thermometer resolve? (GAME_DESIGN.md §3 Q3.)
     *
     * - [PlayMode.GPS]: the arming seeker has moved at least
     *   [QuestionRules.THERMOMETER_MIN_MOVE_METERS] (750 m) straight-line from
     *   [armPos] to [currentPos]; [edgesTraversed] is ignored.
     * - [PlayMode.SIM]: the seeker has traversed at least
     *   [QuestionRules.THERMOMETER_MIN_MOVE_EDGES] (2) graph edges since arming
     *   (`GameState.armedThermometer.edgesMovedSinceArm`); positions are ignored.
     */
    fun thermometerMayResolve(
        mode: PlayMode,
        armPos: LatLng,
        currentPos: LatLng,
        edgesTraversed: Int,
    ): Boolean = when (mode) {
        PlayMode.GPS -> thermometerMayResolveGps(armPos, currentPos)
        PlayMode.SIM -> thermometerMayResolveSim(edgesTraversed)
    }

    /** GPS-mode Thermometer resolution check: straight-line movement ≥ 750 m. */
    fun thermometerMayResolveGps(armPos: LatLng, currentPos: LatLng): Boolean =
        GeoMath.haversineMeters(armPos, currentPos) >= QuestionRules.THERMOMETER_MIN_MOVE_METERS

    /** Sim-mode Thermometer resolution check: ≥ 2 graph edges traversed since arming. */
    fun thermometerMayResolveSim(edgesTraversed: Int): Boolean =
        edgesTraversed >= QuestionRules.THERMOMETER_MIN_MOVE_EDGES

    /**
     * The compensation the hider earns for an answered [spec] (GAME_DESIGN.md §3
     * table via [CompensationTable]): Q1 varies by radius, Q7 by hop count, the
     * rest are fixed. Apply [doubled] when Off-Peak Pass (C16) is pending.
     */
    fun compensationFor(spec: QuestionSpec): CompensationRule = when (spec) {
        is QuestionSpec.RadiusPing -> spec.radius.compensation
        is QuestionSpec.CompassCall -> CompensationTable.COMPASS_CALL
        is QuestionSpec.Thermometer -> CompensationTable.THERMOMETER
        is QuestionSpec.LineCheck -> CompensationTable.LINE_CHECK
        is QuestionSpec.StationDossier -> CompensationTable.STATION_DOSSIER
        is QuestionSpec.Lineup -> CompensationTable.LINEUP
        is QuestionSpec.RailRange -> spec.hops.compensation
    }

    /** Off-Peak Pass (C16) doubling: draw D keep K becomes draw 2D keep 2K (GAME_DESIGN.md §4.2). */
    fun doubled(rule: CompensationRule): CompensationRule =
        CompensationRule(draw = rule.draw * 2, keep = rule.keep * 2)

    /**
     * The hider's nearest station (GAME_DESIGN.md §3 general rules).
     *
     * @throws IllegalStateException when the network has no stations — questions
     *   that depend on the nearest station are meaningless then, and a game must
     *   never be started on an empty network.
     */
    private fun nearestStationOrThrow(network: TransitNetwork, hiderPos: LatLng): Station =
        checkNotNull(network.nearestStation(hiderPos)) {
            "Cannot answer a nearest-station question on an empty transit network " +
                "(no stations); the game cannot run on an empty network"
        }

    private fun requireKnownStation(network: TransitNetwork, stationId: String): Station =
        requireNotNull(network.stationsById[stationId]) {
            "Unknown reference station id '$stationId'"
        }
}
