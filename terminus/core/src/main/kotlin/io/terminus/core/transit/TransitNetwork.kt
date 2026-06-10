package io.terminus.core.transit

import io.terminus.core.geo.LatLng
import io.terminus.core.geo.Polygon

/**
 * The in-memory transit graph (ARCHITECTURE.md §1.1 `transit`).
 *
 * Phase 0 freezes the constructor, the by-id lookup maps, and the query method
 * signatures. The query method bodies are `TODO()` and are implemented by
 * workstream W1, which owns this directory (ARCHITECTURE.md §7).
 *
 * @property stations all network nodes.
 * @property routes all route lines.
 * @property edges all directed graph edges (route edges and walking transfers).
 */
class TransitNetwork(
    val stations: List<Station>,
    val routes: List<RouteLine>,
    val edges: List<TransitEdge>,
) {
    /** Stations keyed by [Station.id]. */
    val stationsById: Map<String, Station> = stations.associateBy { it.id }

    /** Routes keyed by [RouteLine.id]. */
    val routesById: Map<String, RouteLine> = routes.associateBy { it.id }

    /**
     * Nearest station to [point] by haversine distance (GAME_DESIGN.md §3 general rules).
     * Returns null only for an empty network. Implemented by W1.
     */
    fun nearestStation(point: LatLng): Station? = TODO("W1: implement nearest-station lookup")

    /**
     * Time-weighted Dijkstra travel time in seconds from [fromStationId] to [toStationId],
     * or null if unreachable (GAME_DESIGN.md §6.1, §6.3). Implemented by W1.
     */
    fun dijkstraTime(fromStationId: String, toStationId: String): Int? =
        TODO("W1: implement time-weighted Dijkstra")

    /**
     * BFS hop distances from [fromStationId] to every reachable station; transfer edges
     * count as 1 hop (GAME_DESIGN.md §3 Q7). Unreachable stations are absent from the map.
     * Implemented by W1.
     */
    fun bfsHops(fromStationId: String): Map<String, Int> = TODO("W1: implement BFS hop counts")

    /**
     * Returns a copy of this network clipped to [boundary]: stations outside are dropped,
     * dangling edges removed, and only the largest connected component kept
     * (ARCHITECTURE.md §3 build step 6, GAME_DESIGN.md §8 item 2). Implemented by W1.
     */
    fun clipTo(boundary: Polygon): TransitNetwork = TODO("W1: implement boundary clipping")

    /**
     * Returns a copy of this network restricted to the [allowed] modes
     * (GAME_DESIGN.md §8 item 3). Implemented by W1.
     */
    fun filterModes(allowed: Set<TransitMode>): TransitNetwork = TODO("W1: implement mode filter")

    /**
     * Returns a copy of this network restricted to the given route ids
     * (GAME_DESIGN.md §8 item 3). Implemented by W1.
     */
    fun filterRoutes(allowedRouteIds: Set<String>): TransitNetwork =
        TODO("W1: implement route filter")
}
