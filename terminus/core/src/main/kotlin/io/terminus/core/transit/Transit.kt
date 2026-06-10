package io.terminus.core.transit

import io.terminus.core.geo.LatLng
import kotlinx.serialization.Serializable

/**
 * Transit mode of a station or route (GAME_DESIGN.md §3 Q5, §8 item 3;
 * ARCHITECTURE.md §3 build step 3: GTFS `route_type` 0 tram, 1 metro, 2 rail,
 * 3 bus, 4 ferry; others map to bus).
 */
@Serializable
enum class TransitMode {
    METRO,
    TRAM,
    BUS,
    RAIL,
    FERRY,
}

/**
 * A merged transit network node (ARCHITECTURE.md §1.1 `transit`, §3 build steps 2 and 5).
 *
 * These records mirror the `CityFile` JSON schema exactly (ARCHITECTURE.md §3.1),
 * hence [Serializable].
 *
 * @property id stable node id (GTFS import: stable hash of merged member stop_ids).
 * @property name display name.
 * @property latLng node coordinate (GTFS import: centroid of merged stops).
 * @property mode dominant transit mode of the station.
 * @property routeIds ids of every [RouteLine] serving this station.
 * @property isInterchange true when served by at least 2 distinct routes (GAME_DESIGN.md §3 Q5a).
 * @property isTerminus true when first/last stop of any route's dominant pattern (GAME_DESIGN.md §3 Q5b).
 * @property zoneId GTFS fare zone, or null when the feed provides none (GAME_DESIGN.md §3 Q5d).
 * @property underground true/false when known from the feed, null when unknown (ARCHITECTURE.md §3 step 5).
 */
@Serializable
data class Station(
    val id: String,
    val name: String,
    val latLng: LatLng,
    val mode: TransitMode,
    val routeIds: List<String>,
    val isInterchange: Boolean,
    val isTerminus: Boolean,
    val zoneId: String? = null,
    val underground: Boolean? = null,
)

/**
 * A transit route/line (ARCHITECTURE.md §1.1 `transit`).
 *
 * @property id stable route id.
 * @property shortName short label, e.g. "A" or "12".
 * @property longName full descriptive name.
 * @property mode the route's transit mode.
 * @property colorHex display color as "#RRGGBB".
 * @property orderedStationIds station ids along the route's dominant stop pattern, in order.
 */
@Serializable
data class RouteLine(
    val id: String,
    val shortName: String,
    val longName: String,
    val mode: TransitMode,
    val colorHex: String,
    val orderedStationIds: List<String>,
)

/**
 * A directed edge of the transit graph (ARCHITECTURE.md §1.1 `transit`, §3 build steps 3–4).
 *
 * @property fromId origin station id.
 * @property toId destination station id.
 * @property routeId the route this edge belongs to, or null for a walking transfer edge.
 * @property travelTimeSec travel time in seconds (GTFS import: median of samples, clamped 30 s–30 min;
 *   transfers: distance / 1.2 m/s + 60 s).
 * @property distanceMeters straight-line (haversine) distance between endpoints, meters.
 */
@Serializable
data class TransitEdge(
    val fromId: String,
    val toId: String,
    val routeId: String? = null,
    val travelTimeSec: Int,
    val distanceMeters: Double,
)
