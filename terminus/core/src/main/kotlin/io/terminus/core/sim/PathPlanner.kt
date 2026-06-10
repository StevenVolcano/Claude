package io.terminus.core.sim

import io.terminus.core.transit.TransitEdge
import io.terminus.core.transit.TransitMode
import io.terminus.core.transit.TransitNetwork
import java.util.PriorityQueue

/**
 * Time-weighted shortest-path planning with exclusions (ARCHITECTURE.md §1.1 `sim`:
 * "time Dijkstra honoring banned routes/curses").
 *
 * Unlike `TransitNetwork.dijkstraTime` (W1), this planner supports excluding edges:
 * - **banned route ids** — Curse of the Detour (GAME_DESIGN.md §4.2 C10: "edges of
 *   that route excluded from path planning");
 * - **banned modes** — for setups/AI that avoid whole modes.
 *
 * Walking-transfer edges (`routeId == null`) are never banned by either exclusion.
 * Edges are traversed exactly as stored (all directed; see `TransitNetwork` KDoc).
 *
 * Deterministic tie-breaking: nodes at equal distance are settled in ascending
 * station-id order, and among equal-cost edges into the same node the one with the
 * lowest target id (then lowest route id) wins, so equal-cost alternatives always
 * resolve to the same path.
 */
object PathPlanner {

    /**
     * Shortest (by total `travelTimeSec`) edge path from [fromStationId] to
     * [toStationId] respecting the exclusions.
     *
     * @return the ordered edges to traverse; an empty list when `from == to`;
     *   null when either id is unknown or no path exists under the exclusions.
     */
    fun plan(
        network: TransitNetwork,
        fromStationId: String,
        toStationId: String,
        bannedRouteIds: Set<String> = emptySet(),
        bannedModes: Set<TransitMode> = emptySet(),
    ): List<TransitEdge>? {
        if (fromStationId !in network.stationsById || toStationId !in network.stationsById) return null
        if (fromStationId == toStationId) return emptyList()

        fun allowed(edge: TransitEdge): Boolean {
            val routeId = edge.routeId ?: return true // walking transfers are never banned
            if (routeId in bannedRouteIds) return false
            val mode = network.routesById[routeId]?.mode
            return mode == null || mode !in bannedModes
        }

        // Deterministic adjacency: sorted by (toId, routeId) so equal-cost relaxations
        // always happen in the same order.
        val outgoing: Map<String, List<TransitEdge>> = network.edges
            .asSequence()
            .filter { it.toId in network.stationsById && allowed(it) }
            .groupBy { it.fromId }
            .mapValues { (_, edges) -> edges.sortedWith(compareBy({ it.toId }, { it.routeId ?: "" })) }

        val dist = HashMap<String, Int>()
        val predecessorEdge = HashMap<String, TransitEdge>()
        // Tie-break equal distances by station id so settle order is deterministic.
        val queue = PriorityQueue<Pair<Int, String>>(compareBy({ it.first }, { it.second }))
        dist[fromStationId] = 0
        queue.add(0 to fromStationId)
        while (queue.isNotEmpty()) {
            val (d, id) = queue.poll()
            if (d > (dist[id] ?: Int.MAX_VALUE)) continue // stale queue entry
            if (id == toStationId) break
            for (edge in outgoing[id].orEmpty()) {
                val candidate = d + edge.travelTimeSec
                if (candidate < (dist[edge.toId] ?: Int.MAX_VALUE)) {
                    dist[edge.toId] = candidate
                    predecessorEdge[edge.toId] = edge
                    queue.add(candidate to edge.toId)
                }
            }
        }

        if (toStationId !in dist) return null
        val path = ArrayDeque<TransitEdge>()
        var cursor = toStationId
        while (cursor != fromStationId) {
            val edge = predecessorEdge.getValue(cursor)
            path.addFirst(edge)
            cursor = edge.fromId
        }
        return path.toList()
    }
}
