package io.terminus.core.sim

import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.transit.Station
import io.terminus.core.transit.TransitMode
import io.terminus.core.transit.TransitNetwork
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test plan item 6 (sweep geometry): GAME_DESIGN.md §5.3 Final Approach — station
 * node, then 60-degree-interval waypoints on a 150 m ring at 1.4 m/s.
 */
class SweepPatternTest {

    private val center = LatLng(52.050, 5.080)

    private val network = TransitNetwork(
        stations = listOf(
            Station("S", "Hideout Halt", center, TransitMode.METRO, listOf("R1"), false, false),
        ),
        routes = emptyList(),
        edges = emptyList(),
    )

    private val pattern = SweepPattern.around("S", network)!!

    @Test
    fun unknownStationYieldsNull() {
        assertNull(SweepPattern.around("NOPE", network))
    }

    @Test
    fun startsAtTheStationNode() {
        assertEquals(7, pattern.waypoints.size) // station + 6 ring points
        assertEquals(center, pattern.waypoints[0].latLng)
        assertEquals(0.0, pattern.waypoints[0].arrivalGameSeconds)
        assertEquals(center, pattern.center)
    }

    @Test
    fun sixRingPointsAt150MetersWithinOneMeter() {
        val ring = pattern.waypoints.drop(1)
        assertEquals(6, ring.size)
        for (wp in ring) {
            val d = GeoMath.haversineMeters(center, wp.latLng)
            assertTrue(abs(d - 150.0) <= 1.0, "ring point at $d m, expected 150 +/- 1 m")
        }
    }

    @Test
    fun ringPointsAreOrderedAtSixtyDegreeIntervals() {
        val ring = pattern.waypoints.drop(1)
        for ((i, wp) in ring.withIndex()) {
            val bearing = GeoMath.bearingDeg(center, wp.latLng)
            val expected = i * 60.0
            // Normalize the difference into [-180, 180) before comparing.
            val delta = abs(GeoMath.wrapLon180(bearing - expected))
            assertTrue(delta <= 0.5, "ring point $i at bearing $bearing, expected $expected")
        }
    }

    @Test
    fun waypointTimingMatchesWalkingSpeed() {
        val wps = pattern.waypoints
        // Station -> first ring point: 150 m radial walk at 1.4 m/s.
        val radialSeconds = GeoMath.haversineMeters(center, wps[1].latLng) / 1.4
        assertTrue(abs(wps[1].arrivalGameSeconds - radialSeconds) < 1e-6)
        assertTrue(abs(radialSeconds - 150.0 / 1.4) < 1.0)
        // Consecutive ring points: chord walk (60-degree chord of a 150 m ring is ~150 m).
        for (i in 2 until wps.size) {
            val chordSeconds =
                GeoMath.haversineMeters(wps[i - 1].latLng, wps[i].latLng) / 1.4
            val delta = wps[i].arrivalGameSeconds - wps[i - 1].arrivalGameSeconds
            assertTrue(abs(delta - chordSeconds) < 1e-6, "segment $i delta $delta != $chordSeconds")
            assertTrue(abs(chordSeconds - 150.0 / 1.4) < 2.0)
        }
        // Times strictly increase.
        assertTrue(wps.zipWithNext().all { (a, b) -> b.arrivalGameSeconds > a.arrivalGameSeconds })
    }

    @Test
    fun positionAtInterpolatesTheApproachSegment() {
        assertEquals(center, pattern.positionAt(0.0))
        assertEquals(center, pattern.positionAt(-10.0)) // before the sweep: still at the node
        val t1 = pattern.waypoints[1].arrivalGameSeconds
        val halfway = pattern.positionAt(t1 / 2.0)
        val d = GeoMath.haversineMeters(center, halfway)
        assertTrue(abs(d - 75.0) <= 1.0, "halfway out at $d m, expected ~75 m")
        val atRing = pattern.positionAt(t1)
        assertTrue(GeoMath.haversineMeters(atRing, pattern.waypoints[1].latLng) < 0.5)
    }

    @Test
    fun positionAtFollowsTheRingAndLoops() {
        val wps = pattern.waypoints
        // Exactly at each ring waypoint's first arrival time.
        for (i in 1 until wps.size) {
            val at = pattern.positionAt(wps[i].arrivalGameSeconds)
            assertTrue(GeoMath.haversineMeters(at, wps[i].latLng) < 0.5, "waypoint $i mismatch")
        }
        // One full loop after reaching the ring: back at the first ring point.
        val again = pattern.positionAt(pattern.loopStartGameSeconds + pattern.loopDurationSeconds)
        assertTrue(GeoMath.haversineMeters(again, wps[1].latLng) < 0.5)
        // Two and a half segments into the second loop: between ring points 2 and 3.
        val seg = pattern.waypoints[2].arrivalGameSeconds - pattern.waypoints[1].arrivalGameSeconds
        val roaming = pattern.positionAt(
            pattern.loopStartGameSeconds + pattern.loopDurationSeconds + 2.5 * seg,
        )
        val dRing = GeoMath.haversineMeters(center, roaming)
        // Mid-chord of a 60-degree arc sags to R*cos(30) ~ 129.9 m; stays near the ring.
        assertTrue(dRing in 128.0..151.0, "orbit position at $dRing m from center")
    }

    @Test
    fun respectsStartTimeOffset() {
        val offset = SweepPattern.around("S", network, startGameSeconds = 600.0)!!
        assertEquals(600.0, offset.waypoints[0].arrivalGameSeconds)
        assertEquals(center, offset.positionAt(599.0))
        assertEquals(center, offset.positionAt(600.0))
        val t1 = offset.waypoints[1].arrivalGameSeconds
        assertTrue(abs(t1 - (600.0 + 150.0 / 1.4)) < 1.0)
    }
}
