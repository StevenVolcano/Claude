package io.terminus.app.map

import android.graphics.Color
import io.terminus.core.cityfile.CityFile
import io.terminus.core.game.BoundarySpec
import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/**
 * Builders turning core domain data into osmdroid overlays
 * (ARCHITECTURE.md §1.2 `map`). All functions are pure constructors; callers own
 * adding/removing the overlays on the [MapView].
 */
object MapOverlays {

    fun LatLng.toGeoPoint(): GeoPoint = GeoPoint(lat, lon)

    /** The map bounding box of [city] with a small margin. */
    fun cityBoundingBox(city: CityFile): BoundingBox {
        val margin = 0.01
        return BoundingBox(
            city.bbox.maxLat + margin,
            city.bbox.maxLon + margin,
            city.bbox.minLat - margin,
            city.bbox.minLon - margin,
        )
    }

    /**
     * The boundary ring described by [spec] over [city]: the drawn polygon, the
     * circle approximated as a 72-gon, or the full-extent convex hull of the
     * city's stations.
     */
    fun boundaryRing(spec: BoundarySpec, city: CityFile): List<LatLng> = when (spec) {
        is BoundarySpec.PolygonBoundary -> spec.polygon.vertices
        is BoundarySpec.CircleBoundary ->
            (0 until 72).map { i ->
                GeoMath.destinationPoint(spec.center, i * 5.0, spec.radiusMeters)
            }
        BoundarySpec.FullExtent -> GeoMath.convexHull(city.stations.map { it.latLng })
    }

    /** Outline-only polygon overlay for the game boundary. */
    fun boundaryOverlay(ring: List<LatLng>): Polygon {
        val polygon = Polygon()
        polygon.points = ring.map { it.toGeoPoint() }
        polygon.outlinePaint.color = Color.argb(200, 230, 57, 70)
        polygon.outlinePaint.strokeWidth = 4f
        polygon.fillPaint.color = Color.argb(16, 230, 57, 70)
        polygon.setOnClickListener { _, _, _ -> false } // taps fall through
        return polygon
    }

    /** One colored polyline per route, drawn station-to-station. */
    fun routeOverlays(city: CityFile): List<Polyline> {
        val stationById = city.stations.associateBy { it.id }
        return city.routes.map { route ->
            val line = Polyline()
            line.setPoints(
                route.orderedStationIds.mapNotNull { stationById[it]?.latLng?.toGeoPoint() },
            )
            line.outlinePaint.color = parseColorOr(route.colorHex, Color.GRAY)
            line.outlinePaint.strokeWidth = 7f
            line.title = "${route.shortName} — ${route.longName}"
            line.setOnClickListener { _, _, _ -> false }
            line
        }
    }

    /** Small dot markers for every station, titled with the station name. */
    fun stationMarkers(mapView: MapView, city: CityFile): List<Marker> =
        city.stations.map { station ->
            val marker = Marker(mapView)
            marker.position = station.latLng.toGeoPoint()
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.title = station.name
            marker.setTextIcon(if (station.isInterchange) "◉" else "•")
            marker
        }

    /** A labeled token marker (players, AI seekers, the GPS dot). */
    fun tokenMarker(mapView: MapView, position: LatLng, label: String): Marker {
        val marker = Marker(mapView)
        marker.position = position.toGeoPoint()
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        marker.title = label
        marker.setTextIcon(label)
        return marker
    }

    /** A filled disc overlay (hider zone, capture radius). */
    fun circleOverlay(center: LatLng, radiusMeters: Double, argbStroke: Int, argbFill: Int): Polygon {
        val polygon = Polygon()
        polygon.points = Polygon.pointsAsCircle(center.toGeoPoint(), radiusMeters)
        polygon.outlinePaint.color = argbStroke
        polygon.outlinePaint.strokeWidth = 3f
        polygon.fillPaint.color = argbFill
        polygon.setOnClickListener { _, _, _ -> false }
        return polygon
    }

    private fun parseColorOr(hex: String, fallback: Int): Int =
        try {
            Color.parseColor(hex)
        } catch (_: IllegalArgumentException) {
            fallback
        }
}
