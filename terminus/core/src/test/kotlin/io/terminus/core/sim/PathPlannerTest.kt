package io.terminus.core.sim

import io.terminus.core.geo.LatLng
import io.terminus.core.transit.RouteLine
import io.terminus.core.transit.Station
import io.terminus.core.transit.TransitEdge
import io.terminus.core.transit.TransitMode
import io.terminus.core.transit.TransitNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Test plan item 6: banned-route path planning under C10 (Curse of the Detour).
 *
 * Inline network:
 * ```
 *        X(60)     X(60)
 *    A --------B-------- D          fast: 120 s via route X (metro)
 *    A --------C-------- D          slow: 200 s via route Y (tram), 100 s per hop
 *    A ~ W                          walk transfer (routeId = null), 80 s
 *    E                              isolated station
 * ```
 */
class PathPlannerTest {

    private fun station(id: String, mode: TransitMode, routeIds: List<String>) =
        Station(id, "Station $id", LatLng(52.0, 5.0), mode, routeIds, false, false)

    private val edgeABx = TransitEdge("A", "B", "X", 60, 700.0)
    private val edgeBDx = TransitEdge("B", "D", "X", 60, 700.0)
    private val edgeACy = TransitEdge("A", "C", "Y", 100, 900.0)
    private val edgeCDy = TransitEdge("C", "D", "Y", 100, 900.0)
    private val edgeAWwalk = TransitEdge("A", "W", null, 80, 100.0)

    private val network = TransitNetwork(
        stations = listOf(
            station("A", TransitMode.METRO, listOf("X", "Y")),
            station("B", TransitMode.METRO, listOf("X")),
            station("C", TransitMode.TRAM, listOf("Y")),
            station("D", TransitMode.METRO, listOf("X", "Y")),
            station("W", TransitMode.METRO, emptyList()),
            station("E", TransitMode.BUS, listOf("Z")),
        ),
        routes = listOf(
            RouteLine("X", "X", "Express", TransitMode.METRO, "#FF0000", listOf("A", "B", "D")),
            RouteLine("Y", "Y", "Local", TransitMode.TRAM, "#00FF00", listOf("A", "C", "D")),
        ),
        edges = listOf(edgeABx, edgeBDx, edgeACy, edgeCDy, edgeAWwalk),
    )

    @Test
    fun picksFastestPathWithoutExclusions() {
        assertEquals(listOf(edgeABx, edgeBDx), PathPlanner.plan(network, "A", "D"))
    }

    @Test
    fun bannedRouteForcesTheSlowDetour() {
        // Curse of the Detour on route X: the only remaining path is via Y.
        assertEquals(
            listOf(edgeACy, edgeCDy),
            PathPlanner.plan(network, "A", "D", bannedRouteIds = setOf("X")),
        )
    }

    @Test
    fun banningAllRoutesMakesTargetUnreachable() {
        assertNull(PathPlanner.plan(network, "A", "D", bannedRouteIds = setOf("X", "Y")))
    }

    @Test
    fun bannedModeForcesDetourToo() {
        assertEquals(
            listOf(edgeACy, edgeCDy),
            PathPlanner.plan(network, "A", "D", bannedModes = setOf(TransitMode.METRO)),
        )
        assertNull(
            PathPlanner.plan(
                network,
                "A",
                "D",
                bannedModes = setOf(TransitMode.METRO, TransitMode.TRAM),
            ),
        )
    }

    @Test
    fun walkingTransfersAreNeverBanned() {
        assertEquals(
            listOf(edgeAWwalk),
            PathPlanner.plan(
                network,
                "A",
                "W",
                bannedRouteIds = setOf("X", "Y"),
                bannedModes = TransitMode.entries.toSet(),
            ),
        )
    }

    @Test
    fun isolatedStationIsUnreachable() {
        assertNull(PathPlanner.plan(network, "A", "E"))
    }

    @Test
    fun unknownStationsReturnNull() {
        assertNull(PathPlanner.plan(network, "A", "NOPE"))
        assertNull(PathPlanner.plan(network, "NOPE", "D"))
    }

    @Test
    fun sameOriginAndTargetIsAnEmptyPath() {
        assertEquals(emptyList(), PathPlanner.plan(network, "A", "A"))
    }

    @Test
    fun edgesAreDirectedNoReverseTraversal() {
        // All edges run A->...->D only; the reverse direction has no edges.
        assertNull(PathPlanner.plan(network, "D", "A"))
    }

    @Test
    fun equalCostPathsTieBreakOnLowestTargetId() {
        // Diamond with two identical-cost paths: S -> B1 -> T and S -> B2 -> T.
        val sb1 = TransitEdge("S", "B1", "P", 50, 500.0)
        val b1t = TransitEdge("B1", "T", "P", 50, 500.0)
        val sb2 = TransitEdge("S", "B2", "Q", 50, 500.0)
        val b2t = TransitEdge("B2", "T", "Q", 50, 500.0)
        val diamond = TransitNetwork(
            stations = listOf(
                station("S", TransitMode.METRO, listOf("P", "Q")),
                station("B1", TransitMode.METRO, listOf("P")),
                station("B2", TransitMode.METRO, listOf("Q")),
                station("T", TransitMode.METRO, listOf("P", "Q")),
            ),
            routes = listOf(
                RouteLine("P", "P", "P line", TransitMode.METRO, "#0000FF", listOf("S", "B1", "T")),
                RouteLine("Q", "Q", "Q line", TransitMode.METRO, "#FF00FF", listOf("S", "B2", "T")),
            ),
            // Deliberately listed B2-first to prove ordering does not depend on input order.
            edges = listOf(sb2, b2t, sb1, b1t),
        )
        // Lowest target id wins: B1 < B2.
        assertEquals(listOf(sb1, b1t), PathPlanner.plan(diamond, "S", "T"))
        // And it is stable across repeated runs.
        repeat(5) {
            assertEquals(listOf(sb1, b1t), PathPlanner.plan(diamond, "S", "T"))
        }
    }
}
