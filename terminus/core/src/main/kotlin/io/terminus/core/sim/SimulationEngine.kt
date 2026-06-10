package io.terminus.core.sim

import io.terminus.core.game.PlayerId
import io.terminus.core.game.PlayerPosition
import io.terminus.core.geo.LatLng
import io.terminus.core.transit.TransitNetwork
import kotlin.math.min
import kotlinx.serialization.Serializable

/**
 * The full movement state of one token actor following a [MovementPlan]
 * (ARCHITECTURE.md §1.1 `sim`; GAME_DESIGN.md §6 intro: AI tokens move continuously
 * along their planned graph paths at edge speeds).
 *
 * The plan must be non-empty; an actor with nothing to do should be represented by
 * the engine (W6) as a plain `PlayerPosition.NodePosition`, not ticked here.
 *
 * @property plan the path being followed, including any per-node dwell rules.
 * @property edgeIndex index into [MovementPlan.edges] of the current edge;
 *   `edges.size` once the plan is complete.
 * @property secondsIntoEdge game seconds of travel already spent on the current edge.
 * @property dwellRemainingSeconds remaining dwell at the current edge's origin node
 *   before travel starts (Curse of the Local Service, GAME_DESIGN.md §4.2 C5).
 * @property frozen a frozen actor does not advance at all (Curse of the Stalled
 *   Train, §4.2 C4: "tokens frozen mid-edge").
 * @property speedMultiplier per-actor speed factor; effective edge travel time =
 *   `travelTimeSec / speedMultiplier` (Easy AI = 0.85, GAME_DESIGN.md §6.5).
 */
@Serializable
data class ActorState(
    val plan: MovementPlan,
    val edgeIndex: Int = 0,
    val secondsIntoEdge: Double = 0.0,
    val dwellRemainingSeconds: Double = 0.0,
    val frozen: Boolean = false,
    val speedMultiplier: Double = 1.0,
) {
    init {
        require(plan.edges.isNotEmpty()) { "ActorState requires a non-empty MovementPlan" }
        require(edgeIndex in 0..plan.edges.size) { "edgeIndex $edgeIndex out of range" }
        require(secondsIntoEdge >= 0.0) { "secondsIntoEdge must be >= 0" }
        require(dwellRemainingSeconds >= 0.0) { "dwellRemainingSeconds must be >= 0" }
        require(speedMultiplier > 0.0) { "speedMultiplier must be > 0" }
    }

    /** True once the actor has reached the final node of the plan. */
    val isComplete: Boolean
        get() = edgeIndex >= plan.edges.size

    /** Effective travel time of the current edge in game seconds, or null when complete. */
    val currentEdgeEffectiveSeconds: Double?
        get() = if (isComplete) null else plan.edges[edgeIndex].travelTimeSec / speedMultiplier

    /**
     * Current graph position:
     * - complete → `NodePosition` of the plan's final node (plan completion rule);
     * - dwelling or not yet departed (`secondsIntoEdge == 0`) → `NodePosition` of the
     *   current edge's origin;
     * - travelling → `EdgePosition` with `fraction = secondsIntoEdge / effectiveTravelTime`.
     */
    val position: PlayerPosition
        get() {
            if (isComplete) return PlayerPosition.NodePosition(plan.edges.last().toId)
            val edge = plan.edges[edgeIndex]
            if (dwellRemainingSeconds > 0.0 || secondsIntoEdge == 0.0) {
                return PlayerPosition.NodePosition(edge.fromId)
            }
            val fraction = (secondsIntoEdge / (edge.travelTimeSec / speedMultiplier)).coerceIn(0.0, 1.0)
            return PlayerPosition.EdgePosition(edge.fromId, edge.toId, edge.routeId, fraction)
        }
}

/**
 * Emitted when an actor reaches a node during a tick.
 *
 * @property playerId the arriving actor.
 * @property stationId the node reached.
 * @property isFinal true when this is the plan's final node (the actor is now complete).
 */
data class ArrivalEvent(
    val playerId: PlayerId,
    val stationId: String,
    val isFinal: Boolean,
)

/**
 * Result of one [SimulationEngine.tick].
 *
 * @property actors updated actor states, same keys and iteration order as the input.
 * @property arrivals node arrivals that occurred during the tick, ordered by the
 *   input map's iteration order, then chronologically per actor.
 */
data class TickResult(
    val actors: Map<PlayerId, ActorState>,
    val arrivals: List<ArrivalEvent>,
)

/**
 * Pure token-movement stepper (ARCHITECTURE.md §1.1 `sim`, §2 step 2).
 *
 * Advances actors along their [MovementPlan]s by elapsed game seconds:
 * continuous interpolation along edges, dwell at nodes (Curse of the Local Service),
 * freezes (Curse of the Stalled Train), per-actor speed multipliers (Easy AI 0.85×),
 * and plan completion at the final node. Stateless and deterministic: the same
 * inputs always produce the same outputs.
 */
object SimulationEngine {

    /**
     * Advances every actor by [dtGameSeconds] of game time.
     *
     * Per actor, repeatedly: consume any remaining dwell at the current node, then
     * travel the current edge at `travelTimeSec / speedMultiplier`; on reaching a
     * node emit an [ArrivalEvent] and, unless the plan is complete, start that
     * node's dwell ([MovementPlan.dwellSecondsByStationId], applied at every
     * intermediate node). Frozen and completed actors do not move. A single large
     * `dt` may cross several nodes; no time is lost at boundaries.
     */
    fun tick(actors: Map<PlayerId, ActorState>, dtGameSeconds: Double): TickResult {
        require(dtGameSeconds >= 0.0) { "dtGameSeconds must be >= 0, was $dtGameSeconds" }
        val updated = LinkedHashMap<PlayerId, ActorState>(actors.size)
        val arrivals = ArrayList<ArrivalEvent>()
        for ((playerId, actor) in actors) {
            if (actor.frozen || actor.isComplete || dtGameSeconds == 0.0) {
                updated[playerId] = actor
                continue
            }
            val edges = actor.plan.edges
            var edgeIndex = actor.edgeIndex
            var secondsIntoEdge = actor.secondsIntoEdge
            var dwellRemaining = actor.dwellRemainingSeconds
            var remaining = dtGameSeconds
            while (remaining > 0.0 && edgeIndex < edges.size) {
                if (dwellRemaining > 0.0) {
                    val consumed = min(dwellRemaining, remaining)
                    dwellRemaining -= consumed
                    remaining -= consumed
                } else {
                    val edge = edges[edgeIndex]
                    val effectiveSeconds = edge.travelTimeSec / actor.speedMultiplier
                    val neededSeconds = effectiveSeconds - secondsIntoEdge
                    if (remaining < neededSeconds) {
                        secondsIntoEdge += remaining
                        remaining = 0.0
                    } else {
                        remaining -= neededSeconds
                        edgeIndex++
                        secondsIntoEdge = 0.0
                        val isFinal = edgeIndex == edges.size
                        arrivals.add(ArrivalEvent(playerId, edge.toId, isFinal))
                        if (!isFinal) {
                            dwellRemaining =
                                (actor.plan.dwellSecondsByStationId[edge.toId] ?: 0).toDouble()
                        }
                    }
                }
            }
            updated[playerId] = actor.copy(
                edgeIndex = edgeIndex,
                secondsIntoEdge = secondsIntoEdge,
                dwellRemainingSeconds = dwellRemaining,
            )
        }
        return TickResult(updated, arrivals)
    }

    /**
     * Coordinates of any [PlayerPosition] against [network]:
     * - `GpsPosition` → its fix;
     * - `NodePosition` → the station coordinate;
     * - `EdgePosition` → linear lat/lon interpolation between the endpoint stations
     *   at the position's fraction (fine at city scale).
     *
     * Returns null when a referenced station id is not in the network.
     */
    fun latLngOf(position: PlayerPosition, network: TransitNetwork): LatLng? = when (position) {
        is PlayerPosition.GpsPosition -> position.latLng
        is PlayerPosition.NodePosition -> network.stationsById[position.stationId]?.latLng
        is PlayerPosition.EdgePosition -> {
            val from = network.stationsById[position.fromStationId]?.latLng
            val to = network.stationsById[position.toStationId]?.latLng
            if (from == null || to == null) {
                null
            } else {
                val f = position.fraction.coerceIn(0.0, 1.0)
                LatLng(
                    lat = from.lat + (to.lat - from.lat) * f,
                    lon = from.lon + (to.lon - from.lon) * f,
                )
            }
        }
    }
}
