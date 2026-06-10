package io.terminus.core.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical-earth geometry over [LatLng] (ARCHITECTURE.md §1.1 `geo`).
 *
 * All functions assume WGS-84 coordinates in decimal degrees and approximate the
 * earth as a sphere of radius [EARTH_RADIUS_METERS] (the IUGG mean radius). This is
 * accurate to well under 0.5% at city scale, which is the game's operating range.
 */
object GeoMath {

    /** IUGG mean earth radius in meters. */
    const val EARTH_RADIUS_METERS: Double = 6_371_008.8

    private fun Double.toRadians(): Double = Math.toRadians(this)

    /** Wraps a longitude (or longitude delta) in degrees to the interval (-180, 180]. */
    fun wrapLon180(lonDeg: Double): Double {
        var lon = lonDeg % 360.0
        if (lon > 180.0) lon -= 360.0
        if (lon <= -180.0) lon += 360.0
        return lon
    }

    /**
     * Great-circle distance between [a] and [b] in meters via the haversine formula.
     */
    fun haversineMeters(a: LatLng, b: LatLng): Double {
        val dLat = (b.lat - a.lat).toRadians()
        val dLon = (b.lon - a.lon).toRadians()
        val lat1 = a.lat.toRadians()
        val lat2 = b.lat.toRadians()
        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)
        val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    /**
     * Initial great-circle bearing from [a] to [b] in degrees, normalized to [0, 360).
     * 0 = north, 90 = east.
     */
    fun bearingDeg(a: LatLng, b: LatLng): Double {
        val lat1 = a.lat.toRadians()
        val lat2 = b.lat.toRadians()
        val dLon = (b.lon - a.lon).toRadians()
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg % 360.0 + 360.0) % 360.0
    }

    /**
     * The point reached by travelling [meters] along the great circle leaving [origin]
     * at initial bearing [bearingDeg] (degrees clockwise from north). The returned
     * longitude is normalized to (-180, 180].
     */
    fun destinationPoint(origin: LatLng, bearingDeg: Double, meters: Double): LatLng {
        val angular = meters / EARTH_RADIUS_METERS
        val theta = bearingDeg.toRadians()
        val lat1 = origin.lat.toRadians()
        val lon1 = origin.lon.toRadians()
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(theta))
        val lon2 = lon1 + atan2(
            sin(theta) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2),
        )
        return LatLng(Math.toDegrees(lat2), wrapLon180(Math.toDegrees(lon2)))
    }

    /**
     * Ray-casting point-in-polygon test, safe across the antimeridian.
     *
     * All longitudes (vertices and the query point) are re-expressed as wrapped
     * deltas relative to the polygon's first vertex, so a polygon crossing the ±180°
     * line behaves like any other as long as it spans less than 180° of longitude
     * (game boundaries are city-sized, so this always holds). Points exactly on an
     * edge may land on either side; callers must not rely on edge behavior.
     *
     * Polygons with fewer than 3 vertices contain nothing.
     */
    fun pointInPolygon(point: LatLng, polygon: Polygon): Boolean {
        val vertices = polygon.vertices
        val n = vertices.size
        if (n < 3) return false
        // Longitudes as wrapped deltas from the first vertex, removing the seam.
        val refLon = vertices[0].lon
        val px = wrapLon180(point.lon - refLon)
        val py = point.lat
        val xs = DoubleArray(n) { wrapLon180(vertices[it].lon - refLon) }
        val ys = DoubleArray(n) { vertices[it].lat }
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            if ((ys[i] > py) != (ys[j] > py)) {
                val xCross = xs[j] + (py - ys[j]) / (ys[i] - ys[j]) * (xs[i] - xs[j])
                if (xCross > px) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Minimum distance in meters from [point] to any segment of [polyline].
     *
     * Each segment is projected into a local equirectangular plane centered on [point]
     * (x scaled by cos(point.lat)), the point is projected onto the segment, and the
     * planar distance is taken — accurate to a fraction of a percent at city scale.
     * A single-point polyline degenerates to [haversineMeters].
     *
     * @throws IllegalArgumentException if [polyline] is empty.
     */
    fun distanceToPolylineMeters(point: LatLng, polyline: List<LatLng>): Double {
        require(polyline.isNotEmpty()) { "polyline must contain at least one point" }
        if (polyline.size == 1) return haversineMeters(point, polyline[0])
        val cosLat = cos(point.lat.toRadians())
        val metersPerLonDeg = Math.toRadians(1.0) * cosLat * EARTH_RADIUS_METERS
        val metersPerLatDeg = Math.toRadians(1.0) * EARTH_RADIUS_METERS

        var best = Double.MAX_VALUE
        var x1 = wrapLon180(polyline[0].lon - point.lon) * metersPerLonDeg
        var y1 = (polyline[0].lat - point.lat) * metersPerLatDeg
        for (i in 1 until polyline.size) {
            // Wrap each segment's lon delta relative to its own start so segments stay
            // short across the antimeridian.
            val x2 = x1 + wrapLon180(polyline[i].lon - polyline[i - 1].lon) * metersPerLonDeg
            val y2 = (polyline[i].lat - point.lat) * metersPerLatDeg
            val dx = x2 - x1
            val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            // The query point is the local origin; project it onto the segment.
            val t = if (len2 == 0.0) 0.0 else ((-x1 * dx - y1 * dy) / len2).coerceIn(0.0, 1.0)
            best = min(best, hypot(x1 + t * dx, y1 + t * dy))
            x1 = x2
            y1 = y2
        }
        return best
    }

    /**
     * Convex hull of [points] via Andrew's monotone chain, treating (lon, lat) as
     * planar (x, y) coordinates — valid for city-scale point sets that do not cross
     * the antimeridian.
     *
     * Returns hull vertices in counterclockwise order without repeating the first
     * vertex; collinear boundary points are excluded. Inputs with fewer than 3
     * distinct points are returned as-is (deduplicated and sorted).
     */
    fun convexHull(points: List<LatLng>): List<LatLng> {
        val pts = points.distinct().sortedWith(compareBy({ it.lon }, { it.lat }))
        if (pts.size <= 2) return pts

        fun cross(o: LatLng, a: LatLng, b: LatLng): Double =
            (a.lon - o.lon) * (b.lat - o.lat) - (a.lat - o.lat) * (b.lon - o.lon)

        fun halfHull(ordered: List<LatLng>): MutableList<LatLng> {
            val hull = ArrayList<LatLng>()
            for (p in ordered) {
                while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) {
                    hull.removeAt(hull.size - 1)
                }
                hull.add(p)
            }
            hull.removeAt(hull.size - 1) // last point repeats as first of the other chain
            return hull
        }

        return halfHull(pts) + halfHull(pts.asReversed())
    }
}
