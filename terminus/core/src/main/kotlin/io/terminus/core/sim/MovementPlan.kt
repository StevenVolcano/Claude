package io.terminus.core.sim

import io.terminus.core.transit.TransitEdge
import kotlinx.serialization.Serializable

/**
 * A planned path along the transit graph (ARCHITECTURE.md §1.1 `sim`).
 *
 * Token positions reuse `io.terminus.core.game.PlayerPosition.EdgePosition`
 * (edge from/to/routeId/fraction) — no duplicate `TokenPosition` type exists.
 * `SimulationEngine` and `PathPlanner` are W5's.
 *
 * @property edges ordered edges to traverse, each consecutive pair sharing a station.
 * @property dwellSecondsByStationId dwell time at given nodes, e.g. the 45 sim-second
 *   dwell at every station under Curse of the Local Service (GAME_DESIGN.md §4.2 C5).
 *   Stations absent from the map have zero dwell.
 */
@Serializable
data class MovementPlan(
    val edges: List<TransitEdge>,
    val dwellSecondsByStationId: Map<String, Int> = emptyMap(),
)
