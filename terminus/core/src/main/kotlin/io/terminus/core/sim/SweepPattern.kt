package io.terminus.core.sim

import io.terminus.core.game.GameRules
import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.transit.TransitNetwork

/**
 * A [SweepPattern] waypoint.
 *
 * @property latLng waypoint coordinate.
 * @property arrivalGameSeconds game time at which the sweeping seeker first reaches it.
 */
data class TimedWaypoint(
    val latLng: LatLng,
    val arrivalGameSeconds: Double,
)

/**
 * Final Approach sweep helper (GAME_DESIGN.md §5.3: "visit the hiding-zone station
 * node, then orbit waypoints at 60° intervals on a 150 m ring at walking speed
 * (1.4 m/s) until the 75 m condition is met").
 *
 * Produces the timed GPS-mode sweep waypoints around a hiding-zone station and
 * evaluates the sweeping seeker's position at any game time. All movement here is
 * off-graph: straight-line lat/lon interpolation between waypoints at
 * [GameRules.WALKING_SPEED_MPS], with segment durations from haversine distances.
 *
 * The sweep starts at the station node at [startGameSeconds], walks out to the
 * ring point at bearing 0°, then orbits the six ring points (bearings 0°, 60°, …
 * 300°) indefinitely — [positionAt] wraps around the ring until capture or clock
 * expiry ends the Final Approach.
 */
class SweepPattern private constructor(
    /** The hiding-zone station coordinate (sweep origin). */
    val center: LatLng,
    /**
     * The station node followed by the six ring points in orbit order, each with its
     * first-arrival time. Size is always 7.
     */
    val waypoints: List<TimedWaypoint>,
    /** Per-segment walk durations of the closed ring loop (point i → point (i+1) mod 6). */
    private val ringSegmentSeconds: DoubleArray,
) {
    /** Game time at which the orbit begins (arrival at the first ring point). */
    val loopStartGameSeconds: Double = waypoints[1].arrivalGameSeconds

    /** Duration of one full orbit of the ring in game seconds. */
    val loopDurationSeconds: Double = ringSegmentSeconds.sum()

    /**
     * The sweeping seeker's position at [gameSeconds]: at the station node before the
     * sweep starts, interpolated along the current segment afterwards, orbiting the
     * ring forever once the first ring point is reached.
     */
    fun positionAt(gameSeconds: Double): LatLng {
        val start = waypoints[0].arrivalGameSeconds
        if (gameSeconds <= start) return center
        if (gameSeconds < loopStartGameSeconds) {
            val fraction = (gameSeconds - start) / (loopStartGameSeconds - start)
            return lerp(center, waypoints[1].latLng, fraction)
        }
        var t = (gameSeconds - loopStartGameSeconds) % loopDurationSeconds
        var i = 0
        while (t >= ringSegmentSeconds[i]) {
            t -= ringSegmentSeconds[i]
            i++
        }
        val from = waypoints[1 + i].latLng
        val to = waypoints[1 + (i + 1) % RING_POINTS].latLng
        return lerp(from, to, t / ringSegmentSeconds[i])
    }

    companion object {
        private const val RING_POINTS = 360 / GameRules.SWEEP_WAYPOINT_STEP_DEGREES // 6

        /**
         * Builds the sweep around [stationId]'s node, starting at [startGameSeconds]
         * (game time of arrival at the station). Returns null for an unknown station.
         */
        fun around(
            stationId: String,
            network: TransitNetwork,
            startGameSeconds: Double = 0.0,
        ): SweepPattern? {
            val center = network.stationsById[stationId]?.latLng ?: return null
            val ring = List(RING_POINTS) { i ->
                GeoMath.destinationPoint(
                    origin = center,
                    bearingDeg = (i * GameRules.SWEEP_WAYPOINT_STEP_DEGREES).toDouble(),
                    meters = GameRules.SWEEP_RING_RADIUS_METERS,
                )
            }
            val waypoints = ArrayList<TimedWaypoint>(1 + RING_POINTS)
            waypoints.add(TimedWaypoint(center, startGameSeconds))
            var t = startGameSeconds + walkSeconds(center, ring[0])
            waypoints.add(TimedWaypoint(ring[0], t))
            val ringSegmentSeconds = DoubleArray(RING_POINTS)
            for (i in 1 until RING_POINTS) {
                ringSegmentSeconds[i - 1] = walkSeconds(ring[i - 1], ring[i])
                t += ringSegmentSeconds[i - 1]
                waypoints.add(TimedWaypoint(ring[i], t))
            }
            ringSegmentSeconds[RING_POINTS - 1] = walkSeconds(ring[RING_POINTS - 1], ring[0])
            return SweepPattern(center, waypoints, ringSegmentSeconds)
        }

        private fun walkSeconds(a: LatLng, b: LatLng): Double =
            GeoMath.haversineMeters(a, b) / GameRules.WALKING_SPEED_MPS
    }

    private fun lerp(a: LatLng, b: LatLng, fraction: Double): LatLng {
        val f = fraction.coerceIn(0.0, 1.0)
        return LatLng(
            lat = a.lat + (b.lat - a.lat) * f,
            lon = a.lon + (b.lon - a.lon) * f,
        )
    }
}
