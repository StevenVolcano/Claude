package io.terminus.core.gtfs

import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.geo.Polygon
import io.terminus.core.transit.Station
import io.terminus.core.transit.TransitEdge
import io.terminus.core.transit.TransitMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GTFS pipeline tests per ARCHITECTURE.md §6 item 2, on a fixture zip built in
 * memory: 8 stops, 2 routes, 6 trips, a parent_station merge (Bravo platforms),
 * a 40 m duplicate-name merge (Charlie), one outlier travel time (proves median),
 * a stop outside the boundary (Faraway), plus RFC 4180 oddities: UTF-8 BOM, CRLF,
 * quoted fields with commas/escaped quotes/embedded newline, and an entry order
 * with stop_times.txt before trips.txt.
 */
class GtfsImportTest {

    // Stop coordinates (lat 52: 0.01 deg lon ~ 685 m, 0.00036 deg lat ~ 40 m).
    private val stopsTxt = "\uFEFF" + listOf(
        "stop_id,stop_name,stop_lat,stop_lon,parent_station,zone_id,location_type",
        "s_alpha,\"Alpha \"\"Central\"\", West\",52.0000,5.0000,,1,0",
        "s_bravo1,Bravo,52.0000,5.0100,s_bravoP,1,0",
        "s_bravo2,Bravo,52.0001,5.0101,s_bravoP,1,0",
        "s_bravoP,Bravo Station,52.00005,5.01005,,,1",
        "s_charlie,Charlie,52.0000,5.0200,,1,0",
        "s_charlie2,Charlie.,52.00036,5.0200,,2,0", // 40 m from s_charlie, same normalized name
        "s_delta,Delta,52.0000,5.0220,,2,0", // ~137 m from merged Charlie: walking transfer
        "s_far,Faraway,53.0000,6.0000,,2,0", // outside the boundary
    ).joinToString("\r\n")

    // r2's long name is quoted and spans two lines (RFC 4180 embedded newline).
    private val routesTxt = listOf(
        "route_id,route_short_name,route_long_name,route_type,route_color",
        "r1,M1,Metro One,1,E03131",
        "r2,T2,\"Tram\nTwo\",0,",
    ).joinToString("\n")

    private val tripsTxt = listOf(
        "route_id,service_id,trip_id",
        "r1,wk,t1",
        "r1,wk,t2",
        "r1,wk,t3",
        "r1,wk,t4",
        "r2,wk,t5",
        "r1,we,t6", // weekend-only: must be excluded by weekday service selection
    ).joinToString("\n")

    private val calendarTxt = listOf(
        "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date",
        "wk,1,1,1,1,1,0,0,20250101,20251231",
        "we,0,0,0,0,0,1,1,20250101,20251231",
    ).joinToString("\n")

    private val stopTimesTxt = listOf(
        "trip_id,arrival_time,departure_time,stop_id,stop_sequence",
        // t1/t2 eastbound: Alpha -> Bravo 120 s, Bravo -> Charlie 120 s, Charlie -> Delta 100 s.
        // t1's Bravo departure is blank: arrival fallback.
        "t1,08:00:00,08:00:00,s_alpha,1",
        "t1,08:02:00,,s_bravo1,2",
        "t1,08:04:00,08:04:00,s_charlie,3",
        "t1,08:05:40,08:05:40,s_delta,4",
        "t2,08:10:00,08:10:00,s_alpha,1",
        "t2,08:12:00,08:12:00,s_bravo1,2",
        "t2,08:14:00,08:14:00,s_charlie,3",
        "t2,08:15:40,08:15:40,s_delta,4",
        // t3: Alpha -> Bravo outlier 900 s (median of {120,120,900} must be 120).
        "t3,08:20:00,08:20:00,s_alpha,1",
        "t3,08:35:00,08:35:00,s_bravo1,2",
        "t3,08:37:00,08:37:00,s_charlie,3",
        "t3,08:38:40,08:38:40,s_delta,4",
        // t4 westbound via the other platforms/duplicate stop (merge proof).
        "t4,09:00:00,09:00:00,s_delta,1",
        "t4,09:01:40,09:01:40,s_charlie2,2",
        "t4,09:03:40,09:03:40,s_bravo2,3",
        "t4,09:05:40,09:05:40,s_alpha,4",
        // t5 (tram): Bravo -> Charlie 10 s (clamped to 30), Charlie -> Faraway 7200 s (clamped to 1800).
        "t5,09:00:00,09:00:00,s_bravo1,1",
        "t5,09:00:10,09:00:10,s_charlie,2",
        "t5,11:00:10,11:00:10,s_far,3",
        // t6 weekend-only direct Alpha -> Delta: this edge must NOT exist on a weekday build.
        "t6,10:00:00,10:00:00,s_alpha,1",
        "t6,10:04:00,10:04:00,s_delta,2",
    ).joinToString("\n")

    private val boundary = Polygon(
        listOf(LatLng(51.9, 4.9), LatLng(51.9, 5.1), LatLng(52.1, 5.1), LatLng(52.1, 4.9)),
    )

    private fun zipBytes(entries: Map<String, String>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun fixtureZip(includeCalendar: Boolean = true): ByteArray {
        val entries = linkedMapOf(
            // stop_times before trips: the pipeline must not depend on entry order.
            "stops.txt" to stopsTxt,
            "stop_times.txt" to stopTimesTxt,
            "trips.txt" to tripsTxt,
            "routes.txt" to routesTxt,
            "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nsh1,52.0,5.0,1\n",
        )
        if (includeCalendar) entries["calendar.txt"] = calendarTxt
        return zipBytes(entries)
    }

    private fun buildFixture(includeCalendar: Boolean = true): NetworkBuilder.Result {
        val feed = GtfsParser.parse(ZipInputStream(ByteArrayInputStream(fixtureZip(includeCalendar))))
        return NetworkBuilder.build(feed, boundary, "fixture", "Fixture City", "test data")
    }

    private fun station(result: NetworkBuilder.Result, name: String): Station =
        assertNotNull(result.cityFile.stations.find { it.name == name }, "station '$name' missing")

    private fun edge(result: NetworkBuilder.Result, fromId: String, toId: String, routeId: String?): TransitEdge? =
        result.cityFile.edges.find { it.fromId == fromId && it.toId == toId && it.routeId == routeId }

    // ------------------------------------------------------------ parser level

    @Test
    fun `parser handles BOM CRLF quotes and aggregates trip patterns`() {
        val feed = GtfsParser.parse(ZipInputStream(ByteArrayInputStream(fixtureZip())))
        assertEquals(8, feed.stopsRead)
        assertEquals(8, feed.stops.size)
        // BOM did not corrupt the first header; quoted name with comma and escaped quotes survives.
        assertEquals("Alpha \"Central\", West", feed.stops.first { it.id == "s_alpha" }.name)
        // Embedded newline inside a quoted field.
        assertEquals("Tram\nTwo", feed.routes.first { it.id == "r2" }.longName)
        assertEquals(6, feed.trips.size)
        assertEquals(2, feed.calendars.size)
        // t1 and t2 share one pattern (same stops, same deltas); t3, t4, t5, t6 are distinct.
        assertEquals(5, feed.tripPatterns.size)
        val shared = assertNotNull(feed.tripPatterns.find { it.tripIds.size == 2 })
        assertEquals(listOf("t1", "t2"), shared.tripIds)
        assertEquals(listOf("s_alpha", "s_bravo1", "s_charlie", "s_delta"), shared.stopIds)
        // Departure-fallback-to-arrival on t1's Bravo row.
        assertEquals(listOf(120, 120, 100), shared.travelTimesSec)
    }

    // ------------------------------------------------------------ builder level

    @Test
    fun `stop merging by parent_station and by 50m plus normalized name`() {
        val result = buildFixture()
        assertEquals(8, result.report.stopsRead)
        assertEquals(5, result.report.stopsMerged) // alpha, bravo group, charlie pair, delta, far
        assertEquals(4, result.cityFile.stations.size) // far clipped away

        val bravo = station(result, "Bravo Station") // parent row's name wins
        val charlie = station(result, "Charlie") // majority/lexicographic name among {Charlie, Charlie.}
        // Centroids.
        assertEquals(52.00005, bravo.latLng.lat, 1e-9)
        assertEquals(5.01005, bravo.latLng.lon, 1e-9)
        assertEquals(52.00018, charlie.latLng.lat, 1e-9)
        assertEquals(5.0200, charlie.latLng.lon, 1e-9)
    }

    @Test
    fun `weekday service selection excludes weekend trips`() {
        val result = buildFixture()
        assertEquals(5, result.report.trips)
        val alpha = station(result, "Alpha \"Central\", West")
        val delta = station(result, "Delta")
        // t6 (weekend-only) would have created a direct Alpha -> Delta route edge.
        assertNull(edge(result, alpha.id, delta.id, "r1"))
    }

    @Test
    fun `no calendar files means all services active`() {
        val result = buildFixture(includeCalendar = false)
        assertEquals(6, result.report.trips)
        assertTrue(result.report.warnings.any { "assuming all services" in it })
        val alpha = station(result, "Alpha \"Central\", West")
        val delta = station(result, "Delta")
        assertEquals(240, assertNotNull(edge(result, alpha.id, delta.id, "r1")).travelTimeSec)
    }

    @Test
    fun `edge travel times are medians clamped to 30s and 30min`() {
        val result = buildFixture()
        val alpha = station(result, "Alpha \"Central\", West")
        val bravo = station(result, "Bravo Station")
        val charlie = station(result, "Charlie")
        val delta = station(result, "Delta")

        // Median of {120, 120, 900}: the outlier does not drag the time up.
        assertEquals(120, assertNotNull(edge(result, alpha.id, bravo.id, "r1")).travelTimeSec)
        assertEquals(120, assertNotNull(edge(result, bravo.id, charlie.id, "r1")).travelTimeSec)
        assertEquals(100, assertNotNull(edge(result, charlie.id, delta.id, "r1")).travelTimeSec)
        // Reverse direction from t4 only.
        assertEquals(120, assertNotNull(edge(result, bravo.id, alpha.id, "r1")).travelTimeSec)
        assertEquals(100, assertNotNull(edge(result, delta.id, charlie.id, "r1")).travelTimeSec)
        // 10 s sample clamps up to 30 s.
        assertEquals(30, assertNotNull(edge(result, bravo.id, charlie.id, "r2")).travelTimeSec)
        assertTrue(result.report.warnings.any { "clamped" in it })
    }

    @Test
    fun `walking transfers are two directed edges with the 1_2mps plus 60s rule`() {
        val result = buildFixture()
        assertEquals(2, result.report.transferEdges)
        val charlie = station(result, "Charlie")
        val delta = station(result, "Delta")
        val out = assertNotNull(edge(result, charlie.id, delta.id, null), "missing transfer out")
        val back = assertNotNull(edge(result, delta.id, charlie.id, null), "missing transfer back")
        val meters = GeoMath.haversineMeters(charlie.latLng, delta.latLng)
        assertTrue(meters < 250.0)
        val expected = (meters / 1.2 + 60.0).roundToInt()
        assertEquals(expected, out.travelTimeSec)
        assertEquals(expected, back.travelTimeSec)
        // No other pair is close enough.
        assertEquals(2, result.cityFile.edges.count { it.routeId == null })
    }

    @Test
    fun `attributes interchange terminus mode and zone`() {
        val result = buildFixture()
        val alpha = station(result, "Alpha \"Central\", West")
        val bravo = station(result, "Bravo Station")
        val charlie = station(result, "Charlie")
        val delta = station(result, "Delta")

        assertTrue(bravo.isInterchange) // r1 + r2
        assertTrue(charlie.isInterchange)
        assertFalse(alpha.isInterchange)
        // r1 dominant pattern is eastbound (3 trips vs 1): Alpha/Delta termini, Charlie not.
        assertTrue(alpha.isTerminus)
        assertTrue(delta.isTerminus)
        assertFalse(charlie.isTerminus)
        assertTrue(bravo.isTerminus) // head of r2's dominant pattern

        assertEquals(TransitMode.METRO, charlie.mode) // metro vs tram tie -> lower ordinal
        assertEquals(TransitMode.METRO, alpha.mode)

        assertEquals("1", bravo.zoneId) // children both zone 1
        assertEquals("1", charlie.zoneId) // {1, 2} tie -> lexicographic
        assertEquals("2", delta.zoneId)
        // No underground data in the feed.
        assertTrue(result.cityFile.stations.all { it.underground == null })
    }

    @Test
    fun `boundary clip drops outside stations and dangling route tails`() {
        val result = buildFixture()
        assertEquals(1, result.report.droppedOutsideBoundary)
        assertNull(result.cityFile.stations.find { it.name == "Faraway" })
        // r2 ran Bravo -> Charlie -> Faraway; after the clip it keeps its two inside stations.
        val r2 = assertNotNull(result.cityFile.routes.find { it.id == "r2" })
        val bravo = station(result, "Bravo Station")
        val charlie = station(result, "Charlie")
        assertEquals(listOf(bravo.id, charlie.id), r2.orderedStationIds)
        // The clamped-to-30-min Charlie -> Faraway edge went with it.
        assertTrue(result.cityFile.edges.none { it.toId !in result.cityFile.stations.map { s -> s.id }.toSet() })
    }

    @Test
    fun `report counts routes edges and default start`() {
        val result = buildFixture()
        assertEquals(2, result.report.routes)
        // Pre-clip directed route edges: r1 east 3 + r1 west 3 + r2 (Bravo->Charlie, Charlie->Far) 2.
        assertEquals(8, result.report.edges)
        // Final network: 6 r1 + 1 r2 + 2 transfers.
        assertEquals(9, result.cityFile.edges.size)
        assertFalse(result.cityFile.isSynthetic)
        // Charlie: 2 routes and the highest degree -> default start.
        assertEquals(station(result, "Charlie").id, result.cityFile.defaultStartStationId)
        // bbox covers the surviving stations.
        val bbox = result.cityFile.bbox
        for (s in result.cityFile.stations) {
            assertTrue(s.latLng.lat in bbox.minLat..bbox.maxLat)
            assertTrue(s.latLng.lon in bbox.minLon..bbox.maxLon)
        }
    }
}
