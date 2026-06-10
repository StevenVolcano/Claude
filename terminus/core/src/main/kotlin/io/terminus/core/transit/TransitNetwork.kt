package io.terminus.core.transit

import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.geo.Polygon
import java.util.PriorityQueue

/**
 * The in-memory transit graph (ARCHITECTURE.md §1.1 `transit`).
 *
 * ### Edge-direction convention (binding for all builders and consumers)
 * **Every entry in [edges] is a directed edge**, including walking transfers.
 * Builders (GTFS import, DemoCities, tests) that want a bidirectional connection —
 * which is the normal case for walking transfers (`routeId == null`, ARCHITECTURE.md
 * §3 build step 4 "undirected") — must emit **two** directed edges, one per
 * direction. [dijkstraTime], [dijkstraTimes], and [bfsHops] traverse edges strictly
 * as stored and never infer a reverse direction. Connectivity-based pruning
 * ([clipTo], [filterModes], [filterRoutes]) treats edges as undirected, since a
 * one-way-reachable station is still part of the playable map.
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

    /** Outgoing directed edges keyed by [TransitEdge.fromId]. */
    private val outgoingEdges: Map<String, List<TransitEdge>> by lazy { edges.groupBy { it.fromId } }

    /**
     * Nearest station to [point] by haversine distance (GAME_DESIGN.md §3 general rules).
     * Ties are broken by the lexicographically lowest station id.
     * Returns null only for an empty network.
     */
    fun nearestStation(point: LatLng): Station? =
        stations.minWithOrNull(
            compareBy({ GeoMath.haversineMeters(point, it.latLng) }, { it.id }),
        )

    /**
     * Time-weighted Dijkstra travel time in seconds from [fromStationId] to [toStationId],
     * or null if unreachable or either id is unknown (GAME_DESIGN.md §6.1, §6.3).
     * Edges are traversed exactly as stored (directed; see the class KDoc convention).
     */
    fun dijkstraTime(fromStationId: String, toStationId: String): Int? {
        if (fromStationId !in stationsById || toStationId !in stationsById) return null
        return runDijkstra(fromStationId, toStationId)[toStationId]
    }

    /**
     * One-to-all variant of [dijkstraTime]: travel time in seconds from [fromStationId]
     * to every reachable station (including `fromStationId -> 0`). Unreachable stations
     * are absent. Returns an empty map for an unknown id.
     */
    fun dijkstraTimes(fromStationId: String): Map<String, Int> =
        if (fromStationId in stationsById) runDijkstra(fromStationId, stopAt = null) else emptyMap()

    private fun runDijkstra(fromId: String, stopAt: String?): Map<String, Int> {
        val dist = HashMap<String, Int>()
        val queue = PriorityQueue<Pair<Int, String>>(compareBy { it.first })
        dist[fromId] = 0
        queue.add(0 to fromId)
        while (queue.isNotEmpty()) {
            val (d, id) = queue.poll()
            if (d > (dist[id] ?: Int.MAX_VALUE)) continue // stale queue entry
            if (id == stopAt) break
            for (edge in outgoingEdges[id].orEmpty()) {
                if (edge.toId !in stationsById) continue
                val candidate = d + edge.travelTimeSec
                if (candidate < (dist[edge.toId] ?: Int.MAX_VALUE)) {
                    dist[edge.toId] = candidate
                    queue.add(candidate to edge.toId)
                }
            }
        }
        return dist
    }

    /**
     * BFS hop distances from [fromStationId] to every reachable station; every edge —
     * route segments and walking transfers alike — counts as 1 hop (GAME_DESIGN.md §3 Q7).
     * Includes `fromStationId -> 0`. Unreachable stations are absent from the map; an
     * unknown id yields an empty map. Edges are traversed as stored (directed).
     */
    fun bfsHops(fromStationId: String): Map<String, Int> {
        if (fromStationId !in stationsById) return emptyMap()
        val hops = HashMap<String, Int>()
        hops[fromStationId] = 0
        val queue = ArrayDeque<String>()
        queue.add(fromStationId)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val next = hops.getValue(id) + 1
            for (edge in outgoingEdges[id].orEmpty()) {
                if (edge.toId in stationsById && edge.toId !in hops) {
                    hops[edge.toId] = next
                    queue.add(edge.toId)
                }
            }
        }
        return hops
    }

    /**
     * Returns a copy of this network clipped to [boundary]: stations outside are dropped,
     * dangling edges removed, and only the largest connected component kept
     * (ARCHITECTURE.md §3 build step 6, GAME_DESIGN.md §8 item 2). Route
     * [RouteLine.orderedStationIds] are recomputed to drop removed stations and routes
     * keeping fewer than 2 stations are removed; station [Station.routeIds] are
     * recomputed against the surviving routes.
     */
    fun clipTo(boundary: Polygon): TransitNetwork {
        val kept = stations
            .filter { GeoMath.pointInPolygon(it.latLng, boundary) }
            .map { it.id }
            .toSet()
        return rebuild(kept, routesById.keys)
    }

    /**
     * Returns a copy of this network restricted to the [allowed] modes
     * (GAME_DESIGN.md §8 item 3): disallowed routes are removed along with stations
     * left with no allowed route; walking transfers survive only between surviving
     * stations; finally the largest connected component is kept.
     */
    fun filterModes(allowed: Set<TransitMode>): TransitNetwork =
        restrictToRoutes(routes.filter { it.mode in allowed }.map { it.id }.toSet())

    /**
     * Returns a copy of this network restricted to the given route ids
     * (GAME_DESIGN.md §8 item 3); semantics otherwise identical to [filterModes].
     */
    fun filterRoutes(allowedRouteIds: Set<String>): TransitNetwork =
        restrictToRoutes(routesById.keys.intersect(allowedRouteIds))

    private fun restrictToRoutes(allowedRouteIds: Set<String>): TransitNetwork {
        val keptStations = stations
            .filter { station -> station.routeIds.any { it in allowedRouteIds } }
            .map { it.id }
            .toSet()
        return rebuild(keptStations, allowedRouteIds)
    }

    /**
     * Shared pruning pipeline: restrict to [keptStationIds] and [allowedRouteIds],
     * drop dangling edges, keep the largest connected component (edges undirected for
     * connectivity; size ties go to the component containing the lowest station id),
     * then recompute routes (≥2 stations or dropped) and station route memberships.
     */
    private fun rebuild(keptStationIds: Set<String>, allowedRouteIds: Set<String>): TransitNetwork {
        val candidateEdges = edges.filter { edge ->
            edge.fromId in keptStationIds &&
                edge.toId in keptStationIds &&
                (edge.routeId == null || edge.routeId in allowedRouteIds)
        }
        val component = largestComponent(keptStationIds, candidateEdges)
        val finalEdges = candidateEdges.filter { it.fromId in component && it.toId in component }
        val finalRoutes = routes
            .filter { it.id in allowedRouteIds }
            .map { route -> route.copy(orderedStationIds = route.orderedStationIds.filter { it in component }) }
            .filter { it.orderedStationIds.size >= 2 }
        val finalRouteIds = finalRoutes.map { it.id }.toSet()
        val finalStations = stations
            .filter { it.id in component }
            .map { station -> station.copy(routeIds = station.routeIds.filter { it in finalRouteIds }) }
        return TransitNetwork(finalStations, finalRoutes, finalEdges)
    }

    /**
     * Largest connected component of the subgraph induced by [nodeIds] with [edges]
     * treated as undirected. Deterministic on size ties: components are discovered in
     * ascending station-id order and a tie keeps the earlier (lower-id) component.
     */
    private fun largestComponent(nodeIds: Set<String>, edges: List<TransitEdge>): Set<String> {
        if (nodeIds.isEmpty()) return emptySet()
        val adjacency = HashMap<String, MutableList<String>>()
        for (edge in edges) {
            adjacency.getOrPut(edge.fromId) { mutableListOf() }.add(edge.toId)
            adjacency.getOrPut(edge.toId) { mutableListOf() }.add(edge.fromId)
        }
        val seen = HashSet<String>()
        var best: Set<String> = emptySet()
        for (start in nodeIds.sorted()) {
            if (start in seen) continue
            val component = HashSet<String>()
            val stack = ArrayDeque<String>()
            stack.add(start)
            seen.add(start)
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                component.add(node)
                for (neighbor in adjacency[node].orEmpty()) {
                    if (neighbor in nodeIds && seen.add(neighbor)) stack.add(neighbor)
                }
            }
            if (component.size > best.size) best = component
        }
        return best
    }
}
