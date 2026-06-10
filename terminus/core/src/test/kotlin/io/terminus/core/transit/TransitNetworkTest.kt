package io.terminus.core.transit

import io.terminus.core.geo.LatLng
import io.terminus.core.geo.Polygon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests on a small inline 10-station, 2-route network:
 *
 * ```
 *                 T1 (52.02, 5.02)
 *                  |
 *  M1 - M2 - M3 ~ T2          (T2 co-located with M3 at (52.00, 5.02); ~ = walk transfer)
 *  (lat 52.00,     |
 *   lon 5.00..)   T3 (51.99, 5.02)
 *        M3 - M4 - M5 ~ X     (X is a far outlier at (52.5, 5.5), walk-linked to M5)
 *                 T4 (51.98, 5.02)
 * ```
 *
 * R1 (metro): M1..M5 west-east, 100 s per hop, both directions.
 * R2 (tram):  T1..T4 north-south, 120 s per hop, both directions.
 * Walk transfers (routeId = null, stored as two directed edges each):
 * M3<->T2 90 s, M5<->X 60 s.
 */
class TransitNetworkTest {

    private fun station(
        id: String,
        lat: Double,
        lon: Double,
        mode: TransitMode,
        routeIds: List<String>,
        isInterchange: Boolean = false,
        isTerminus: Boolean = false,
    ) = Station(id, "Station $id", LatLng(lat, lon), mode, routeIds, isInterchange, isTerminus)

    private fun routeEdgesBothWays(routeId: String, stations: List<Station>, timeSec: Int): List<TransitEdge> =
        stations.zipWithNext().flatMap { (a, b) ->
            listOf(
                TransitEdge(a.id, b.id, routeId, timeSec, 0.0),
                TransitEdge(b.id, a.id, routeId, timeSec, 0.0),
            )
        }

    private fun walkBothWays(aId: String, bId: String, timeSec: Int): List<TransitEdge> =
        listOf(
            TransitEdge(aId, bId, null, timeSec, 0.0),
            TransitEdge(bId, aId, null, timeSec, 0.0),
        )

    private val mStations = listOf(
        station("M1", 52.00, 5.00, TransitMode.METRO, listOf("R1"), isTerminus = true),
        station("M2", 52.00, 5.01, TransitMode.METRO, listOf("R1")),
        station("M3", 52.00, 5.02, TransitMode.METRO, listOf("R1"), isInterchange = true),
        station("M4", 52.00, 5.03, TransitMode.METRO, listOf("R1")),
        station("M5", 52.00, 5.04, TransitMode.METRO, listOf("R1"), isTerminus = true),
    )
    private val tStations = listOf(
        station("T1", 52.02, 5.02, TransitMode.TRAM, listOf("R2"), isTerminus = true),
        station("T2", 52.00, 5.02, TransitMode.TRAM, listOf("R2"), isInterchange = true), // co-located with M3
        station("T3", 51.99, 5.02, TransitMode.TRAM, listOf("R2")),
        station("T4", 51.98, 5.02, TransitMode.TRAM, listOf("R2"), isTerminus = true),
    )
    private val outlier = station("X", 52.50, 5.50, TransitMode.BUS, emptyList())

    private val routeR1 = RouteLine("R1", "1", "Metro One", TransitMode.METRO, "#FF0000", mStations.map { it.id })
    private val routeR2 = RouteLine("R2", "2", "Tram Two", TransitMode.TRAM, "#0000FF", tStations.map { it.id })

    private val network = TransitNetwork(
        stations = mStations + tStations + outlier,
        routes = listOf(routeR1, routeR2),
        edges = routeEdgesBothWays("R1", mStations, 100) +
            routeEdgesBothWays("R2", tStations, 120) +
            walkBothWays("M3", "T2", 90) +
            walkBothWays("M5", "X", 60),
    )

    // --- nearestStation --------------------------------------------------------------

    @Test
    fun `nearestStation returns the closest station`() {
        assertEquals("M1", network.nearestStation(LatLng(52.001, 5.001))?.id)
        assertEquals("T4", network.nearestStation(LatLng(51.975, 5.021))?.id)
        assertEquals("X", network.nearestStation(LatLng(52.49, 5.49))?.id)
    }

    @Test
    fun `nearestStation breaks exact ties by lowest station id`() {
        // M3 and T2 are co-located, so distances are bit-identical; "M3" < "T2".
        assertEquals("M3", network.nearestStation(LatLng(52.00, 5.02))?.id)
        assertEquals("M3", network.nearestStation(LatLng(52.001, 5.02))?.id)
    }

    @Test
    fun `nearestStation on an empty network is null`() {
        assertNull(TransitNetwork(emptyList(), emptyList(), emptyList()).nearestStation(LatLng(0.0, 0.0)))
    }

    // --- dijkstra --------------------------------------------------------------------

    @Test
    fun `dijkstraTime along a single route`() {
        assertEquals(0, network.dijkstraTime("M1", "M1"))
        assertEquals(100, network.dijkstraTime("M1", "M2"))
        assertEquals(400, network.dijkstraTime("M1", "M5"))
        assertEquals(400, network.dijkstraTime("M5", "M1")) // reverse-direction edges exist
        assertEquals(360, network.dijkstraTime("T1", "T4"))
    }

    @Test
    fun `dijkstraTime via a walking transfer`() {
        // M1 -> M2 -> M3 (200) -> walk T2 (90) -> T3 -> T4 (240) = 530
        assertEquals(530, network.dijkstraTime("M1", "T4"))
        assertEquals(530, network.dijkstraTime("T4", "M1"))
        // T1 -> T2 (120) -> walk M3 (90) -> M4 -> M5 (200) -> walk X (60) = 470
        assertEquals(470, network.dijkstraTime("T1", "X"))
    }

    @Test
    fun `dijkstraTime returns null for unknown or unreachable stations`() {
        assertNull(network.dijkstraTime("M1", "NOPE"))
        assertNull(network.dijkstraTime("NOPE", "M1"))

        // A network with a one-way edge: B cannot reach A.
        val oneWay = TransitNetwork(
            stations = mStations.take(2),
            routes = listOf(routeR1.copy(orderedStationIds = listOf("M1", "M2"))),
            edges = listOf(TransitEdge("M1", "M2", "R1", 100, 0.0)),
        )
        assertEquals(100, oneWay.dijkstraTime("M1", "M2"))
        assertNull(oneWay.dijkstraTime("M2", "M1"))
    }

    @Test
    fun `dijkstraTimes returns all reachable stations`() {
        val times = network.dijkstraTimes("M1")
        assertEquals(10, times.size)
        assertEquals(0, times["M1"])
        assertEquals(200, times["M3"])
        assertEquals(290, times["T2"])
        assertEquals(410, times["T1"])
        assertEquals(530, times["T4"])
        assertEquals(460, times["X"]) // M1..M5 (400) + walk 60
        assertEquals(emptyMap(), network.dijkstraTimes("NOPE"))
    }

    // --- bfsHops ---------------------------------------------------------------------

    @Test
    fun `bfsHops counts every edge as one hop including transfers`() {
        val hops = network.bfsHops("M1")
        assertEquals(
            mapOf(
                "M1" to 0, "M2" to 1, "M3" to 2, "M4" to 3, "M5" to 4,
                "T2" to 3, "T1" to 4, "T3" to 4, "T4" to 5,
                "X" to 5,
            ),
            hops,
        )
        assertEquals(emptyMap(), network.bfsHops("NOPE"))
    }

    // --- clipTo ----------------------------------------------------------------------

    @Test
    fun `clipTo drops the outlier and keeps everything else`() {
        val boundary = Polygon(
            listOf(
                LatLng(51.95, 4.95),
                LatLng(51.95, 5.10),
                LatLng(52.05, 5.10),
                LatLng(52.05, 4.95),
            ),
        )
        val clipped = network.clipTo(boundary)
        assertEquals((mStations + tStations).map { it.id }.toSet(), clipped.stationsById.keys)
        // The two M5<->X walk edges are gone; all other edges survive.
        assertEquals(network.edges.size - 2, clipped.edges.size)
        assertTrue(clipped.edges.none { it.fromId == "X" || it.toId == "X" })
        // Routes are untouched.
        assertEquals(routeR1.orderedStationIds, clipped.routesById.getValue("R1").orderedStationIds)
        assertEquals(routeR2.orderedStationIds, clipped.routesById.getValue("R2").orderedStationIds)
    }

    @Test
    fun `clipTo keeps only the largest connected component and recomputes routes`() {
        // A big rectangle with a notch cut from the north edge that swallows M2 only
        // (M2 is at lon 5.01). M1 stays inside but becomes its own tiny component and
        // is dropped in favor of the 7-station component {M3,M4,M5,T1..T4}.
        val notched = Polygon(
            listOf(
                LatLng(51.95, 4.95),
                LatLng(51.95, 5.10),
                LatLng(52.05, 5.10),
                LatLng(52.05, 5.015),
                LatLng(51.999, 5.015),
                LatLng(51.999, 5.005),
                LatLng(52.05, 5.005),
                LatLng(52.05, 4.95),
            ),
        )
        val clipped = network.clipTo(notched)
        assertEquals(setOf("M3", "M4", "M5", "T1", "T2", "T3", "T4"), clipped.stationsById.keys)
        // R1 loses M1 and M2 but keeps 3 stations in order; R2 is intact.
        assertEquals(listOf("M3", "M4", "M5"), clipped.routesById.getValue("R1").orderedStationIds)
        assertEquals(listOf("T1", "T2", "T3", "T4"), clipped.routesById.getValue("R2").orderedStationIds)
        // No dangling edges remain.
        assertTrue(clipped.edges.all { it.fromId in clipped.stationsById && it.toId in clipped.stationsById })
        // The clipped network still routes across the transfer.
        assertEquals(120 + 90 + 200, clipped.dijkstraTime("T1", "M5"))
    }

    @Test
    fun `clipTo drops a route reduced below two stations`() {
        // Only the tram corridor plus M3 survives; R1 keeps just M3 -> dropped.
        val tramCorridor = Polygon(
            listOf(
                LatLng(51.97, 5.015),
                LatLng(51.97, 5.025),
                LatLng(52.03, 5.025),
                LatLng(52.03, 5.015),
            ),
        )
        val clipped = network.clipTo(tramCorridor)
        assertEquals(setOf("M3", "T1", "T2", "T3", "T4"), clipped.stationsById.keys)
        assertEquals(setOf("R2"), clipped.routesById.keys)
        // M3 survives (walk-linked to T2) but no longer claims membership of a route.
        assertEquals(emptyList(), clipped.stationsById.getValue("M3").routeIds)
    }

    // --- filterModes / filterRoutes --------------------------------------------------

    @Test
    fun `filterModes keeps only allowed routes and their stations`() {
        val metroOnly = network.filterModes(setOf(TransitMode.METRO))
        assertEquals(setOf("M1", "M2", "M3", "M4", "M5"), metroOnly.stationsById.keys)
        assertEquals(setOf("R1"), metroOnly.routesById.keys)
        // Tram edges and all walk edges (their counterparts are gone) are dropped.
        assertTrue(metroOnly.edges.all { it.routeId == "R1" })
        assertEquals(8, metroOnly.edges.size)
        assertEquals(400, metroOnly.dijkstraTime("M1", "M5"))
        // The walk-only outlier X has no route at all and is dropped too.
        assertFalse("X" in metroOnly.stationsById)
    }

    @Test
    fun `filterModes with all modes keeps route stations but drops route-less ones`() {
        val all = network.filterModes(TransitMode.entries.toSet())
        // X has no routeIds, so it is removed even though every mode is allowed.
        assertEquals((mStations + tStations).map { it.id }.toSet(), all.stationsById.keys)
        // The M3<->T2 walk transfer between surviving stations is preserved.
        assertEquals(2, all.edges.count { it.routeId == null })
    }

    @Test
    fun `filterRoutes keeps the requested route and prunes the rest`() {
        val tramOnly = network.filterRoutes(setOf("R2"))
        assertEquals(setOf("T1", "T2", "T3", "T4"), tramOnly.stationsById.keys)
        assertEquals(setOf("R2"), tramOnly.routesById.keys)
        assertEquals(360, tramOnly.dijkstraTime("T1", "T4"))
        assertNull(tramOnly.dijkstraTime("T1", "M1")) // M1 no longer exists
        // Unknown route ids are ignored; filtering to nothing empties the network.
        val none = network.filterRoutes(setOf("R99"))
        assertTrue(none.stations.isEmpty())
        assertTrue(none.routes.isEmpty())
        assertTrue(none.edges.isEmpty())
    }

    @Test
    fun `filterRoutes keeps only the largest component when routes become disconnected`() {
        // Allowing both routes changes nothing: walk transfers keep them connected.
        val both = network.filterRoutes(setOf("R1", "R2"))
        assertEquals(9, both.stations.size)

        // A variant network without the M3<->T2 transfer: filtering to both routes
        // leaves two disconnected route components; only the larger (R1, 5 stations)
        // survives.
        val noTransfer = TransitNetwork(
            stations = mStations + tStations + outlier,
            routes = listOf(routeR1, routeR2),
            edges = routeEdgesBothWays("R1", mStations, 100) +
                routeEdgesBothWays("R2", tStations, 120) +
                walkBothWays("M5", "X", 60),
        )
        val filtered = noTransfer.filterRoutes(setOf("R1", "R2"))
        assertEquals(setOf("M1", "M2", "M3", "M4", "M5"), filtered.stationsById.keys)
        assertEquals(setOf("R1"), filtered.routesById.keys)
    }
}
