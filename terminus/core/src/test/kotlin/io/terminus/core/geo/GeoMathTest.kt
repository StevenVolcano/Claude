package io.terminus.core.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeoMathTest {

    // --- haversineMeters -------------------------------------------------------------

    private val paris = LatLng(48.8566, 2.3522)
    private val london = LatLng(51.5074, -0.1278)
    private val newYork = LatLng(40.7128, -74.0060)
    private val sydney = LatLng(-33.8688, 151.2093)
    private val melbourne = LatLng(-37.8136, 144.9631)
    private val tokyo = LatLng(35.6762, 139.6503)
    private val osaka = LatLng(34.6937, 135.5023)
    private val moscow = LatLng(55.7558, 37.6173)
    private val stPetersburg = LatLng(59.9311, 30.3609)

    private fun assertWithinHalfPercent(expectedMeters: Double, actualMeters: Double, label: String) {
        val relativeError = abs(actualMeters - expectedMeters) / expectedMeters
        assertTrue(
            relativeError < 0.005,
            "$label: expected ~${expectedMeters / 1000} km, got ${actualMeters / 1000} km " +
                "(relative error $relativeError)",
        )
    }

    @Test
    fun `haversine matches known city-pair distances within half a percent`() {
        assertWithinHalfPercent(343_500.0, GeoMath.haversineMeters(paris, london), "Paris-London")
        assertWithinHalfPercent(5_570_000.0, GeoMath.haversineMeters(newYork, london), "NewYork-London")
        assertWithinHalfPercent(713_400.0, GeoMath.haversineMeters(sydney, melbourne), "Sydney-Melbourne")
        assertWithinHalfPercent(392_500.0, GeoMath.haversineMeters(tokyo, osaka), "Tokyo-Osaka")
        assertWithinHalfPercent(632_000.0, GeoMath.haversineMeters(moscow, stPetersburg), "Moscow-StPetersburg")
    }

    @Test
    fun `haversine is symmetric and zero for identical points`() {
        assertEquals(0.0, GeoMath.haversineMeters(paris, paris))
        assertEquals(
            GeoMath.haversineMeters(paris, london),
            GeoMath.haversineMeters(london, paris),
            1e-6,
        )
    }

    // --- pointInPolygon --------------------------------------------------------------

    private val convexSquare = Polygon(
        listOf(
            LatLng(0.0, 0.0),
            LatLng(0.0, 10.0),
            LatLng(10.0, 10.0),
            LatLng(10.0, 0.0),
        ),
    )

    /** A 'U' shape: a 10x10 square with a notch cut from the top edge down to lat 4. */
    private val concaveU = Polygon(
        listOf(
            LatLng(0.0, 0.0),
            LatLng(0.0, 10.0),
            LatLng(10.0, 10.0),
            LatLng(10.0, 6.0),
            LatLng(4.0, 6.0),
            LatLng(4.0, 4.0),
            LatLng(10.0, 4.0),
            LatLng(10.0, 0.0),
        ),
    )

    @Test
    fun `point in convex polygon`() {
        assertTrue(GeoMath.pointInPolygon(LatLng(5.0, 5.0), convexSquare))
        assertTrue(GeoMath.pointInPolygon(LatLng(9.9, 0.1), convexSquare))
        assertFalse(GeoMath.pointInPolygon(LatLng(5.0, 10.5), convexSquare))
        assertFalse(GeoMath.pointInPolygon(LatLng(-0.1, 5.0), convexSquare))
        assertFalse(GeoMath.pointInPolygon(LatLng(11.0, 11.0), convexSquare))
    }

    @Test
    fun `point in concave polygon including the notch`() {
        // Inside both arms of the U.
        assertTrue(GeoMath.pointInPolygon(LatLng(2.0, 5.0), concaveU))
        assertTrue(GeoMath.pointInPolygon(LatLng(8.0, 2.0), concaveU))
        assertTrue(GeoMath.pointInPolygon(LatLng(8.0, 8.0), concaveU))
        // Inside the notch (cut-out) -> outside the polygon.
        assertFalse(GeoMath.pointInPolygon(LatLng(8.0, 5.0), concaveU))
        assertFalse(GeoMath.pointInPolygon(LatLng(5.0, 5.0), concaveU))
        // Fully outside.
        assertFalse(GeoMath.pointInPolygon(LatLng(11.0, 5.0), concaveU))
    }

    @Test
    fun `points just inside and just outside an edge`() {
        assertTrue(GeoMath.pointInPolygon(LatLng(5.0, 9.9999), convexSquare))
        assertFalse(GeoMath.pointInPolygon(LatLng(5.0, 10.0001), convexSquare))
        assertTrue(GeoMath.pointInPolygon(LatLng(0.0001, 5.0), convexSquare))
        assertFalse(GeoMath.pointInPolygon(LatLng(-0.0001, 5.0), convexSquare))
    }

    @Test
    fun `polygon crossing the antimeridian`() {
        // A rectangle from lon 170 (east) to lon -170 (west), i.e. spanning 20 degrees
        // across the 180 line, lat -5..5.
        val acrossDateLine = Polygon(
            listOf(
                LatLng(-5.0, 170.0),
                LatLng(-5.0, -170.0),
                LatLng(5.0, -170.0),
                LatLng(5.0, 170.0),
            ),
        )
        assertTrue(GeoMath.pointInPolygon(LatLng(0.0, 179.0), acrossDateLine))
        assertTrue(GeoMath.pointInPolygon(LatLng(0.0, -179.0), acrossDateLine))
        assertTrue(GeoMath.pointInPolygon(LatLng(0.0, 180.0), acrossDateLine))
        assertTrue(GeoMath.pointInPolygon(LatLng(4.0, 175.0), acrossDateLine))
        assertFalse(GeoMath.pointInPolygon(LatLng(0.0, 160.0), acrossDateLine))
        assertFalse(GeoMath.pointInPolygon(LatLng(0.0, -160.0), acrossDateLine))
        assertFalse(GeoMath.pointInPolygon(LatLng(6.0, 179.0), acrossDateLine))
        assertFalse(GeoMath.pointInPolygon(LatLng(0.0, 0.0), acrossDateLine))
    }

    @Test
    fun `degenerate polygons contain nothing`() {
        assertFalse(GeoMath.pointInPolygon(LatLng(0.0, 0.0), Polygon(emptyList())))
        assertFalse(
            GeoMath.pointInPolygon(
                LatLng(0.0, 0.0),
                Polygon(listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0))),
            ),
        )
    }

    // --- bearing / destinationPoint --------------------------------------------------

    @Test
    fun `bearing along cardinal directions at the equator`() {
        val origin = LatLng(0.0, 0.0)
        assertEquals(0.0, GeoMath.bearingDeg(origin, LatLng(1.0, 0.0)), 1e-9)
        assertEquals(90.0, GeoMath.bearingDeg(origin, LatLng(0.0, 1.0)), 1e-9)
        assertEquals(180.0, GeoMath.bearingDeg(origin, LatLng(-1.0, 0.0)), 1e-9)
        assertEquals(270.0, GeoMath.bearingDeg(origin, LatLng(0.0, -1.0)), 1e-9)
    }

    @Test
    fun `destinationPoint round-trips with haversine and bearing`() {
        val origin = LatLng(52.05, 5.08)
        for (bearing in listOf(0.0, 47.0, 123.4, 200.0, 359.0)) {
            for (distance in listOf(150.0, 2_345.0, 12_345.0)) {
                val dest = GeoMath.destinationPoint(origin, bearing, distance)
                assertEquals(
                    distance,
                    GeoMath.haversineMeters(origin, dest),
                    distance * 1e-6 + 0.01,
                    "distance round-trip, bearing=$bearing distance=$distance",
                )
                assertEquals(
                    bearing,
                    GeoMath.bearingDeg(origin, dest),
                    0.01,
                    "bearing round-trip, bearing=$bearing distance=$distance",
                )
            }
        }
    }

    @Test
    fun `destinationPoint normalizes longitude across the antimeridian`() {
        val origin = LatLng(0.0, 179.5)
        val dest = GeoMath.destinationPoint(origin, 90.0, 200_000.0)
        assertTrue(dest.lon < -178.0 && dest.lon > -180.0, "expected wrapped lon, got ${dest.lon}")
        assertEquals(200_000.0, GeoMath.haversineMeters(origin, dest), 1.0)
    }

    // --- distanceToPolylineMeters ----------------------------------------------------

    @Test
    fun `distance to polyline perpendicular and beyond endpoints`() {
        // Polyline along the equator from lon 0 to lon 1.
        val line = listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0))
        val oneDegreeLat = Math.toRadians(1.0) * GeoMath.EARTH_RADIUS_METERS

        // Perpendicular offset of 0.1 degree latitude above the middle of the segment.
        val perp = GeoMath.distanceToPolylineMeters(LatLng(0.1, 0.5), line)
        assertEquals(0.1 * oneDegreeLat, perp, 0.1 * oneDegreeLat * 0.01)

        // Beyond the far endpoint: distance to the endpoint itself.
        val beyond = GeoMath.distanceToPolylineMeters(LatLng(0.0, 1.5), line)
        assertEquals(GeoMath.haversineMeters(LatLng(0.0, 1.5), LatLng(0.0, 1.0)), beyond, beyond * 0.01)

        // A point on the line is at distance ~0.
        assertEquals(0.0, GeoMath.distanceToPolylineMeters(LatLng(0.0, 0.5), line), 1.0)
    }

    @Test
    fun `distance to multi-segment polyline picks the nearest segment`() {
        // An L-shaped polyline near city scale.
        val polyline = listOf(
            LatLng(52.00, 5.00),
            LatLng(52.00, 5.05),
            LatLng(52.05, 5.05),
        )
        // Point just west of the vertical leg; closest approach is to the second segment.
        val point = LatLng(52.03, 5.04)
        val direct = GeoMath.haversineMeters(point, LatLng(52.03, 5.05))
        val measured = GeoMath.distanceToPolylineMeters(point, polyline)
        assertEquals(direct, measured, direct * 0.01)

        // Single-point polyline degenerates to haversine.
        assertEquals(
            GeoMath.haversineMeters(point, polyline[0]),
            GeoMath.distanceToPolylineMeters(point, listOf(polyline[0])),
            1e-6,
        )
    }

    // --- convexHull ------------------------------------------------------------------

    @Test
    fun `convex hull of a known point set`() {
        val corners = listOf(
            LatLng(0.0, 0.0),
            LatLng(0.0, 4.0),
            LatLng(4.0, 4.0),
            LatLng(4.0, 0.0),
        )
        val interiorAndEdges = listOf(
            LatLng(2.0, 2.0), // interior
            LatLng(1.0, 3.0), // interior
            LatLng(0.0, 2.0), // collinear on an edge -> excluded from the strict hull
            LatLng(2.0, 4.0), // collinear on an edge -> excluded
        )
        val hull = GeoMath.convexHull((corners + interiorAndEdges).shuffled(kotlin.random.Random(7)))
        assertEquals(4, hull.size)
        assertEquals(corners.toSet(), hull.toSet())
    }

    @Test
    fun `convex hull handles small and degenerate inputs`() {
        assertEquals(emptyList(), GeoMath.convexHull(emptyList()))
        val single = listOf(LatLng(1.0, 1.0))
        assertEquals(single, GeoMath.convexHull(single + single))
        // All collinear points: hull degenerates to the two extremes.
        val collinear = listOf(LatLng(0.0, 0.0), LatLng(1.0, 1.0), LatLng(2.0, 2.0), LatLng(3.0, 3.0))
        val hull = GeoMath.convexHull(collinear)
        assertEquals(setOf(LatLng(0.0, 0.0), LatLng(3.0, 3.0)), hull.toSet())
    }
}
