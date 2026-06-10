package io.terminus.core.ai

import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.transit.RouteLine
import io.terminus.core.transit.Station
import io.terminus.core.transit.TransitEdge
import io.terminus.core.transit.TransitMode
import io.terminus.core.transit.TransitNetwork

/**
 * Self-contained synthetic networks for the AI tests (ARCHITECTURE.md §6 item 7) —
 * deliberately not DemoCities-only, which W2 builds concurrently.
 */
object AiTestNetworks {

    val BASE = LatLng(52.0, 5.0)

    fun station(
        id: String,
        latLng: LatLng,
        routeIds: List<String> = listOf("R1"),
        mode: TransitMode = TransitMode.METRO,
        interchange: Boolean = false,
        terminus: Boolean = false,
        zoneId: String? = null,
        name: String = id,
    ): Station = Station(id, name, latLng, mode, routeIds, interchange, terminus, zoneId)

    /** Station [meters] from [BASE] at [bearingDeg] (exact placement via GeoMath). */
    fun stationAt(
        id: String,
        meters: Double,
        bearingDeg: Double = 90.0,
        routeIds: List<String> = listOf("R1"),
        interchange: Boolean = false,
        terminus: Boolean = false,
    ): Station = station(
        id,
        GeoMath.destinationPoint(BASE, bearingDeg, meters),
        routeIds,
        interchange = interchange,
        terminus = terminus,
    )

    /** Both directed edges of a bidirectional connection. */
    fun bothWays(a: String, b: String, routeId: String?, seconds: Int, meters: Double = 500.0) = listOf(
        TransitEdge(a, b, routeId, seconds, meters),
        TransitEdge(b, a, routeId, seconds, meters),
    )

    /** A single chain of stations on one route with uniform edge times. */
    fun chain(stations: List<Station>, routeId: String = "R1", edgeSeconds: Int = 120): TransitNetwork {
        val edges = stations.zipWithNext().flatMap { (a, b) ->
            bothWays(a.id, b.id, routeId, edgeSeconds, GeoMath.haversineMeters(a.latLng, b.latLng))
        }
        val route = RouteLine(routeId, routeId, routeId, TransitMode.METRO, "#FF0000", stations.map { it.id })
        return TransitNetwork(stations, listOf(route), edges)
    }

    /**
     * Two clusters of 4 stations on one chain: A01..A04 spaced ~479 m, then a long
     * gap (~5.4 km) to B01..B04. Uniform candidate weights over all 8 give a clean
     * 1-bit bisection for a 2 km Radius Ping centered on the A cluster.
     */
    fun twoClusters(): TransitNetwork {
        fun lon(eastMeters: Double) = GeoMath.destinationPoint(BASE, 90.0, eastMeters)
        val a = (1..4).map { stationAt("A0$it", (it - 1) * 479.0) }
        val b = (1..4).map { station("B0$it", lon(6_854.0 + (it - 1) * 479.0)) }
        val all = (a + b).mapIndexed { i, s ->
            s.copy(isTerminus = i == 0 || i == 7)
        }
        return chain(all)
    }
}
