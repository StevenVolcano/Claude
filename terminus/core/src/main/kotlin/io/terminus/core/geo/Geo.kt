package io.terminus.core.geo

import kotlinx.serialization.Serializable

/**
 * A WGS-84 coordinate pair in decimal degrees.
 *
 * Part of the frozen Phase 0 contract (ARCHITECTURE.md §1.1 `geo`). All geometric
 * algorithms over [LatLng] live in `GeoMath` (W1); this type is pure data.
 *
 * @property lat latitude in degrees, positive north.
 * @property lon longitude in degrees, positive east.
 */
@Serializable
data class LatLng(
    val lat: Double,
    val lon: Double,
)

/**
 * Axis-aligned geographic bounding box (ARCHITECTURE.md §1.1 `geo`, §3.1 `CityFile.bbox`).
 *
 * @property minLat southern edge, degrees.
 * @property minLon western edge, degrees.
 * @property maxLat northern edge, degrees.
 * @property maxLon eastern edge, degrees.
 */
@Serializable
data class BoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
)

/**
 * A simple polygon over the earth's surface, used as the game boundary
 * (GAME_DESIGN.md §1.1, §8 item 2; ARCHITECTURE.md §1.1 `geo`).
 *
 * Vertices are an ordered ring; the closing edge from the last vertex back to the
 * first is implicit. Point-in-polygon and related math live in `GeoMath` (W1).
 *
 * @property vertices ordered ring of 3–30 vertices (GAME_DESIGN.md §8 item 2).
 */
@Serializable
data class Polygon(
    val vertices: List<LatLng>,
)
