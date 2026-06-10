package io.terminus.core.cityfile

import io.terminus.core.geo.GeoMath
import io.terminus.core.transit.TransitMode
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Structural assertions for the two synthetic demo cities (ARCHITECTURE.md §4). */
class DemoCitiesTest {

    private val demoville = DemoCities.demoville()
    private val saltmarsh = DemoCities.portSaltmarsh()

    // ------------------------------------------------------------ Demoville

    @Test
    fun `demoville has 36 stations 3 routes and the demo envelope`() {
        assertEquals("demoville", demoville.cityId)
        assertEquals(1, demoville.schemaVersion)
        assertTrue(demoville.isSynthetic)
        assertTrue(demoville.displayName.endsWith("(demo city)"))
        assertTrue("synthetic" in demoville.attribution.lowercase())
        assertEquals(36, demoville.stations.size)
        assertEquals(3, demoville.routes.size)
        assertEquals("DV-A07", demoville.defaultStartStationId)
        assertEquals("Central Cross", demoville.stations.first { it.id == "DV-A07" }.name)

        val byId = demoville.routes.associateBy { it.id }
        assertEquals(14, byId.getValue("DV-LINE-A").orderedStationIds.size)
        assertEquals(13, byId.getValue("DV-LINE-B").orderedStationIds.size)
        assertEquals(12, byId.getValue("DV-LINE-C").orderedStationIds.size)
        assertEquals(TransitMode.METRO, byId.getValue("DV-LINE-A").mode)
        assertEquals(TransitMode.METRO, byId.getValue("DV-LINE-B").mode)
        assertEquals(TransitMode.TRAM, byId.getValue("DV-LINE-C").mode)
    }

    @Test
    fun `demoville interchanges termini underground and zones match the spec`() {
        val interchanges = demoville.stations.filter { it.isInterchange }.map { it.id }.toSet()
        assertEquals(setOf("DV-A04", "DV-A07", "DV-B10"), interchanges) // the 3 shared stations

        val termini = demoville.stations.filter { it.isTerminus }.map { it.id }.toSet()
        assertEquals(setOf("DV-A01", "DV-A14", "DV-B01", "DV-B13"), termini) // the loop has none

        val underground = demoville.stations.filter { it.underground == true }.map { it.id }.toSet()
        assertEquals(setOf("DV-A05", "DV-A06", "DV-A07", "DV-A08", "DV-A09"), underground)
        assertTrue(demoville.stations.all { it.underground != null }) // synthetic data is complete

        // A05–A09 zone 1, rest of Line A zone 2; loop stations zone 2.
        assertEquals("1", demoville.stations.first { it.id == "DV-A07" }.zoneId)
        assertEquals("2", demoville.stations.first { it.id == "DV-A04" }.zoneId)
        assertEquals("1", demoville.stations.first { it.id == "DV-B06" }.zoneId)
        assertEquals("2", demoville.stations.first { it.id == "DV-B10" }.zoneId)
        assertEquals("2", demoville.stations.first { it.id == "DV-C05" }.zoneId)
    }

    @Test
    fun `demoville travel times stay in the documented ranges`() {
        val metroRoutes = setOf("DV-LINE-A", "DV-LINE-B")
        for (edge in demoville.edges) {
            when (edge.routeId) {
                in metroRoutes -> assertTrue(edge.travelTimeSec in 90..150, "metro edge $edge")
                "DV-LINE-C" -> assertTrue(edge.travelTimeSec in 120..180, "tram edge $edge")
            }
        }
        // ~750 m Line A spacing.
        val a07 = demoville.stations.first { it.id == "DV-A07" }
        val a08 = demoville.stations.first { it.id == "DV-A08" }
        assertEquals(750.0, GeoMath.haversineMeters(a07.latLng, a08.latLng), 5.0)
    }

    @Test
    fun `demoville has exactly 6 walking transfer pairs emitted as directed edges`() {
        val walk = demoville.edges.filter { it.routeId == null }
        assertEquals(12, walk.size)
        val pairs = walk.map { setOf(it.fromId, it.toId) }.toSet()
        assertEquals(6, pairs.size)
        val stationById = demoville.stations.associateBy { it.id }
        for (edge in walk) {
            // Both directions present.
            assertTrue(walk.any { it.fromId == edge.toId && it.toId == edge.fromId && it.travelTimeSec == edge.travelTimeSec })
            // Under 250 m, at 1.2 m/s + 60 s.
            val meters = GeoMath.haversineMeters(
                stationById.getValue(edge.fromId).latLng,
                stationById.getValue(edge.toId).latLng,
            )
            assertTrue(meters < 250.0, "transfer ${edge.fromId}-${edge.toId} is $meters m")
            assertEquals((edge.distanceMeters / 1.2 + 60.0).roundToInt(), edge.travelTimeSec)
        }
    }

    // ------------------------------------------------------------ Port Saltmarsh

    @Test
    fun `port saltmarsh has 26 stations tram bus ferry and no underground`() {
        assertEquals("port-saltmarsh", saltmarsh.cityId)
        assertTrue(saltmarsh.isSynthetic)
        assertTrue(saltmarsh.displayName.endsWith("(demo city)"))
        assertEquals(26, saltmarsh.stations.size)
        assertEquals(3, saltmarsh.routes.size)

        val byId = saltmarsh.routes.associateBy { it.id }
        assertEquals(TransitMode.TRAM, byId.getValue("PS-LINE-1").mode)
        assertEquals(TransitMode.BUS, byId.getValue("PS-LINE-2").mode)
        assertEquals(TransitMode.FERRY, byId.getValue("PS-FERRY-F").mode)
        assertEquals(12, byId.getValue("PS-LINE-1").orderedStationIds.size)
        assertEquals(listOf("PS-S12", "PS-F01", "PS-F02"), byId.getValue("PS-FERRY-F").orderedStationIds)

        assertTrue(saltmarsh.stations.none { it.underground == true })
        assertEquals(setOf("1", "2"), saltmarsh.stations.mapNotNull { it.zoneId }.toSet())
        assertTrue(saltmarsh.edges.none { it.routeId == null }) // no walking transfers here

        assertEquals("PS-S04", saltmarsh.defaultStartStationId)
        assertEquals("Town Hall", saltmarsh.stations.first { it.id == "PS-S04" }.name)
    }

    @Test
    fun `port saltmarsh interchanges termini and ferry crossings`() {
        val interchanges = saltmarsh.stations.filter { it.isInterchange }.map { it.id }.toSet()
        assertEquals(setOf("PS-S04", "PS-S09", "PS-S12"), interchanges)
        assertEquals("Fish Market", saltmarsh.stations.first { it.id == "PS-S09" }.name)
        assertEquals("Salt Quay", saltmarsh.stations.first { it.id == "PS-S12" }.name)

        val termini = saltmarsh.stations.filter { it.isTerminus }.map { it.id }.toSet()
        assertEquals(setOf("PS-S01", "PS-S12", "PS-F02"), termini) // bus loop has none

        // 6-minute crossings, both directions.
        val ferryEdges = saltmarsh.edges.filter { it.routeId == "PS-FERRY-F" }
        assertEquals(4, ferryEdges.size)
        assertTrue(ferryEdges.all { it.travelTimeSec == 360 })
    }

    // ------------------------------------------------------------ shared invariants

    @Test
    fun `every station is reachable from the default start in both cities`() {
        for (city in listOf(demoville, saltmarsh)) {
            val network = CityCodec.toTransitNetwork(city)
            val times = network.dijkstraTimes(city.defaultStartStationId)
            assertEquals(city.stations.size, times.size, "${city.cityId}: unreachable stations exist")
            assertNotNull(network.nearestStation(city.stations.first().latLng))
        }
    }

    @Test
    fun `edges reference known stations and routes and bbox covers all stations`() {
        for (city in listOf(demoville, saltmarsh)) {
            val stationIds = city.stations.map { it.id }.toSet()
            val routeIds = city.routes.map { it.id }.toSet()
            for (edge in city.edges) {
                assertTrue(edge.fromId in stationIds && edge.toId in stationIds, "$edge")
                assertTrue(edge.routeId == null || edge.routeId in routeIds, "$edge")
                assertTrue(edge.travelTimeSec in 30..1800, "$edge")
                assertTrue(edge.distanceMeters > 0.0, "$edge")
            }
            for (station in city.stations) {
                assertTrue(station.latLng.lat in city.bbox.minLat..city.bbox.maxLat)
                assertTrue(station.latLng.lon in city.bbox.minLon..city.bbox.maxLon)
                assertTrue(station.routeIds.isNotEmpty())
            }
            // Route patterns are chains of real edges (loop closings included).
            for (route in city.routes) {
                for ((a, b) in route.orderedStationIds.zipWithNext()) {
                    assertTrue(
                        city.edges.any { it.routeId == route.id && it.fromId == a && it.toId == b },
                        "${route.id}: no edge $a -> $b",
                    )
                }
            }
        }
    }

    @Test
    fun `generation is deterministic`() {
        assertEquals(demoville, DemoCities.demoville())
        assertEquals(saltmarsh, DemoCities.portSaltmarsh())
    }
}
