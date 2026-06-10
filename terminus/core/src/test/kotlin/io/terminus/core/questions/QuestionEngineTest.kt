package io.terminus.core.questions

import io.terminus.core.game.PlayMode
import io.terminus.core.geo.LatLng
import io.terminus.core.transit.RouteLine
import io.terminus.core.transit.Station
import io.terminus.core.transit.TransitEdge
import io.terminus.core.transit.TransitMode
import io.terminus.core.transit.TransitNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Table-driven QuestionEngine tests (ARCHITECTURE.md §6 test plan item 4) on a small
 * inline network (deliberately not DemoCities, which is built concurrently by W2).
 *
 * Geometry: at latitude 52° one degree of longitude ≈ 684.6 m per 0.01°, one degree
 * of latitude ≈ 1111.9 m per 0.01°.
 *
 * ```
 *   G3 (52.020, 5.02)  tram terminus, zone 2
 *   |
 *   G2 (52.010, 5.02)  tram+bus interchange --- H1 (52.010, 5.03) bus terminus, no zone
 *   |
 *   G1 (52.0006, 5.02) tram terminus
 *   :  (walking transfer, ~67 m)
 *   A1 ------- A2 ------- A3 ------- A4        metro line "red" along lat 52.000
 * (5.00)     (5.01)     (5.02)     (5.03)
 * ```
 */
class QuestionEngineTest {

    private val a1 = LatLng(52.000, 5.00)
    private val a2 = LatLng(52.000, 5.01)
    private val a3 = LatLng(52.000, 5.02)
    private val a4 = LatLng(52.000, 5.03)
    private val g1 = LatLng(52.0006, 5.02)
    private val g2 = LatLng(52.010, 5.02)
    private val g3 = LatLng(52.020, 5.02)
    private val h1 = LatLng(52.010, 5.03)

    private val network = TransitNetwork(
        stations = listOf(
            station("A1", a1, TransitMode.METRO, listOf("red"), terminus = true, zone = "1"),
            station("A2", a2, TransitMode.METRO, listOf("red"), zone = "1"),
            station("A3", a3, TransitMode.METRO, listOf("red"), zone = "1"),
            station("A4", a4, TransitMode.METRO, listOf("red"), terminus = true, zone = "2"),
            station("G1", g1, TransitMode.TRAM, listOf("green"), terminus = true, zone = "1"),
            station("G2", g2, TransitMode.TRAM, listOf("green", "blue"), interchange = true, zone = "1"),
            station("G3", g3, TransitMode.TRAM, listOf("green"), terminus = true, zone = "2"),
            station("H1", h1, TransitMode.BUS, listOf("blue"), terminus = true, zone = null),
        ),
        routes = listOf(
            RouteLine("red", "R", "Red Line", TransitMode.METRO, "#FF0000", listOf("A1", "A2", "A3", "A4")),
            RouteLine("green", "G", "Green Line", TransitMode.TRAM, "#00FF00", listOf("G1", "G2", "G3")),
            RouteLine("blue", "B", "Blue Line", TransitMode.BUS, "#0000FF", listOf("G2", "H1")),
        ),
        edges = bothWays("A1", "A2", "red") + bothWays("A2", "A3", "red") + bothWays("A3", "A4", "red") +
            bothWays("G1", "G2", "green") + bothWays("G2", "G3", "green") +
            bothWays("G2", "H1", "blue") +
            bothWays("A3", "G1", routeId = null), // walking transfer
    )

    private fun station(
        id: String,
        at: LatLng,
        mode: TransitMode,
        routeIds: List<String>,
        interchange: Boolean = false,
        terminus: Boolean = false,
        zone: String?,
    ) = Station(id, "Station $id", at, mode, routeIds, interchange, terminus, zone)

    private fun bothWays(a: String, b: String, routeId: String?) = listOf(
        TransitEdge(a, b, routeId, travelTimeSec = 120, distanceMeters = 700.0),
        TransitEdge(b, a, routeId, travelTimeSec = 120, distanceMeters = 700.0),
    )

    private fun yes(answer: Answer): Boolean = (answer as Answer.YesNo).value

    // ---------------------------------------------------------------- Q1 Radius Ping

    @Test
    fun `Q1 radius ping compares haversine distance against the chosen radius`() {
        data class Case(val center: LatLng, val hider: LatLng, val radius: PingRadius, val expected: Boolean)
        val cases = listOf(
            // A1 -> A2 is ~684.6 m: outside 500 m, inside 1 km.
            Case(center = a1, hider = a2, radius = PingRadius.M500, expected = false),
            Case(center = a1, hider = a2, radius = PingRadius.KM1, expected = true),
            // Distance 0 is inside any radius (boundary is inclusive: <=).
            Case(center = a2, hider = a2, radius = PingRadius.M500, expected = true),
            // A1 -> A4 is ~2053.7 m: outside 2 km, inside 5 km.
            Case(center = a1, hider = a4, radius = PingRadius.KM2, expected = false),
            Case(center = a1, hider = a4, radius = PingRadius.KM5, expected = true),
        )
        for (case in cases) {
            val spec = QuestionSpec.RadiusPing(center = case.center, radius = case.radius)
            assertEquals(case.expected, yes(QuestionEngine.answer(spec, case.hider, network)), "$case")
        }
    }

    // --------------------------------------------------------------- Q2 Compass Call

    @Test
    fun `Q2 compass call compares hider lat lon against reference station, ties go positive`() {
        data class Case(val hider: LatLng, val axis: CompassAxis, val expected: CompassDirectionValue)
        val cases = listOf(
            Case(hider = g2, axis = CompassAxis.NORTH_SOUTH, expected = CompassDirectionValue.NORTH),
            Case(hider = LatLng(51.990, 5.01), axis = CompassAxis.NORTH_SOUTH, expected = CompassDirectionValue.SOUTH),
            Case(hider = a3, axis = CompassAxis.EAST_WEST, expected = CompassDirectionValue.EAST),
            Case(hider = a1, axis = CompassAxis.EAST_WEST, expected = CompassDirectionValue.WEST),
            // Exact ties resolve to the positive direction (North / East).
            Case(hider = a2, axis = CompassAxis.NORTH_SOUTH, expected = CompassDirectionValue.NORTH),
            Case(hider = a2, axis = CompassAxis.EAST_WEST, expected = CompassDirectionValue.EAST),
        )
        for (case in cases) {
            val spec = QuestionSpec.CompassCall(referenceStationId = "A2", axis = case.axis)
            val answer = QuestionEngine.answer(spec, case.hider, network) as Answer.CompassDirection
            assertEquals(case.expected, answer.direction, "$case")
        }
    }

    @Test
    fun `Q2 with an unknown reference station throws IllegalArgumentException`() {
        val spec = QuestionSpec.CompassCall(referenceStationId = "NOPE", axis = CompassAxis.NORTH_SOUTH)
        assertFailsWith<IllegalArgumentException> { QuestionEngine.answer(spec, a1, network) }
    }

    // ---------------------------------------------------------------- Q3 Thermometer

    @Test
    fun `Q3 thermometer is warmer iff resolve position is strictly closer, equal is colder`() {
        data class Case(val arm: LatLng, val resolve: LatLng, val hider: LatLng, val warmer: Boolean)
        val cases = listOf(
            Case(arm = a1, resolve = a2, hider = a2, warmer = true), // 684.6 m -> 0 m
            Case(arm = a2, resolve = a1, hider = a2, warmer = false), // 0 m -> 684.6 m
            Case(arm = a1, resolve = a1, hider = a3, warmer = false), // equal -> Colder
        )
        for (case in cases) {
            val viaSpec = QuestionEngine.answer(
                QuestionSpec.Thermometer(armPosition = case.arm, resolvePosition = case.resolve),
                case.hider,
                network,
            ) as Answer.WarmerColder
            assertEquals(case.warmer, viaSpec.warmer, "$case")
            // The explicit-position overload agrees.
            assertEquals(case.warmer, QuestionEngine.answerThermometer(case.arm, case.resolve, case.hider).warmer)
        }
    }

    @Test
    fun `Q3 spec without a resolve position cannot be answered`() {
        val spec = QuestionSpec.Thermometer(armPosition = a1, resolvePosition = null)
        assertFailsWith<IllegalArgumentException> { QuestionEngine.answer(spec, a2, network) }
    }

    @Test
    fun `Q3 arming validity - GPS needs 750 m straight line, sim needs 2 edges`() {
        val arm = LatLng(52.000, 5.00)
        val moved756 = LatLng(52.00680, 5.00) // ~756 m north
        val moved745 = LatLng(52.00670, 5.00) // ~745 m north
        assertTrue(QuestionEngine.thermometerMayResolveGps(arm, moved756))
        assertFalse(QuestionEngine.thermometerMayResolveGps(arm, moved745))
        assertFalse(QuestionEngine.thermometerMayResolveGps(arm, arm))

        assertFalse(QuestionEngine.thermometerMayResolveSim(0))
        assertFalse(QuestionEngine.thermometerMayResolveSim(1))
        assertTrue(QuestionEngine.thermometerMayResolveSim(2))
        assertTrue(QuestionEngine.thermometerMayResolveSim(3))

        // Mode dispatch: GPS ignores edges, SIM ignores positions.
        assertFalse(QuestionEngine.thermometerMayResolve(PlayMode.GPS, arm, moved745, edgesTraversed = 5))
        assertTrue(QuestionEngine.thermometerMayResolve(PlayMode.GPS, arm, moved756, edgesTraversed = 0))
        assertTrue(QuestionEngine.thermometerMayResolve(PlayMode.SIM, arm, arm, edgesTraversed = 2))
        assertFalse(QuestionEngine.thermometerMayResolve(PlayMode.SIM, arm, moved756, edgesTraversed = 1))
    }

    // ----------------------------------------------------------------- Q4 Line Check

    @Test
    fun `Q4 line check tests route membership of the hider's nearest station`() {
        data class Case(val hider: LatLng, val routeId: String, val expected: Boolean)
        val cases = listOf(
            Case(hider = a3, routeId = "red", expected = true),
            Case(hider = a3, routeId = "green", expected = false),
            Case(hider = g1, routeId = "green", expected = true),
            Case(hider = g1, routeId = "red", expected = false),
            // Slightly off A3 but still nearest to it (13 m vs 56 m to G1).
            Case(hider = LatLng(52.0001, 5.0201), routeId = "red", expected = true),
            Case(hider = g2, routeId = "blue", expected = true),
        )
        for (case in cases) {
            val spec = QuestionSpec.LineCheck(routeId = case.routeId)
            assertEquals(case.expected, yes(QuestionEngine.answer(spec, case.hider, network)), "$case")
        }
    }

    // ------------------------------------------------------------ Q5 Station Dossier

    @Test
    fun `Q5 station dossier answers attribute booleans of the nearest station`() {
        data class Case(val hider: LatLng, val attribute: DossierAttribute, val expected: Boolean)
        val cases = listOf(
            Case(hider = g2, attribute = DossierAttribute.Interchange, expected = true),
            Case(hider = a2, attribute = DossierAttribute.Interchange, expected = false),
            Case(hider = a1, attribute = DossierAttribute.Terminus, expected = true),
            Case(hider = a2, attribute = DossierAttribute.Terminus, expected = false),
            Case(hider = a1, attribute = DossierAttribute.Mode(TransitMode.METRO), expected = true),
            Case(hider = a1, attribute = DossierAttribute.Mode(TransitMode.TRAM), expected = false),
            Case(hider = g1, attribute = DossierAttribute.Mode(TransitMode.TRAM), expected = true),
            Case(hider = a4, attribute = DossierAttribute.Zone("2"), expected = true),
            Case(hider = a4, attribute = DossierAttribute.Zone("1"), expected = false),
            // A station with no zone data never matches any zone query.
            Case(hider = h1, attribute = DossierAttribute.Zone("1"), expected = false),
        )
        for (case in cases) {
            val spec = QuestionSpec.StationDossier(attribute = case.attribute)
            assertEquals(case.expected, yes(QuestionEngine.answer(spec, case.hider, network)), "$case")
        }
    }

    // --------------------------------------------------------------------- Q6 Lineup

    @Test
    fun `Q6 lineup tests whether the nearest station is among the chosen three`() {
        val hider = a2
        assertTrue(yes(QuestionEngine.answer(QuestionSpec.Lineup(listOf("A1", "A2", "A3")), hider, network)))
        assertFalse(yes(QuestionEngine.answer(QuestionSpec.Lineup(listOf("A1", "A3", "A4")), hider, network)))
    }

    @Test
    fun `Q6 lineup requires exactly 3 stations`() {
        assertFailsWith<IllegalArgumentException> {
            QuestionEngine.answer(QuestionSpec.Lineup(listOf("A1", "A2")), a1, network)
        }
        assertFailsWith<IllegalArgumentException> {
            QuestionEngine.answer(QuestionSpec.Lineup(listOf("A1", "A2", "A3", "A4")), a1, network)
        }
    }

    // ----------------------------------------------------------------- Q7 Rail Range

    @Test
    fun `Q7 rail range uses BFS hops where walking transfers count as one hop`() {
        data class Case(val refId: String, val hider: LatLng, val hops: RailRangeHops, val expected: Boolean)
        val cases = listOf(
            // A1 -> G1 is 3 hops (A1-A2, A2-A3, A3~G1 transfer).
            Case(refId = "A1", hider = g1, hops = RailRangeHops.TWO, expected = false),
            Case(refId = "A1", hider = g1, hops = RailRangeHops.FOUR, expected = true),
            // The transfer itself is exactly 1 hop: A3 -> G1.
            Case(refId = "A3", hider = g1, hops = RailRangeHops.TWO, expected = true),
            // A4 -> G1 = 2 hops (A4-A3, A3~G1): boundary is inclusive (<= N).
            Case(refId = "A4", hider = g1, hops = RailRangeHops.TWO, expected = true),
            // A1 -> H1 = 5 hops (A1-A2-A3~G1-G2-H1) crossing the transfer mid-path.
            Case(refId = "A1", hider = h1, hops = RailRangeHops.FOUR, expected = false),
            Case(refId = "A1", hider = h1, hops = RailRangeHops.EIGHT, expected = true),
            // Same station: 0 hops.
            Case(refId = "A1", hider = a1, hops = RailRangeHops.TWO, expected = true),
        )
        for (case in cases) {
            val spec = QuestionSpec.RailRange(referenceStationId = case.refId, hops = case.hops)
            assertEquals(case.expected, yes(QuestionEngine.answer(spec, case.hider, network)), "$case")
        }
    }

    @Test
    fun `Q7 unreachable nearest station answers No`() {
        // Two stations, no edges: X2 is unreachable from X1 at any hop count.
        val disconnected = TransitNetwork(
            stations = listOf(
                station("X1", LatLng(52.0, 5.0), TransitMode.METRO, listOf("r"), zone = null),
                station("X2", LatLng(52.1, 5.1), TransitMode.METRO, listOf("r"), zone = null),
            ),
            routes = emptyList(),
            edges = emptyList(),
        )
        val spec = QuestionSpec.RailRange(referenceStationId = "X1", hops = RailRangeHops.EIGHT)
        assertFalse(yes(QuestionEngine.answer(spec, LatLng(52.1, 5.1), disconnected)))
    }

    @Test
    fun `Q7 with an unknown reference station throws IllegalArgumentException`() {
        val spec = QuestionSpec.RailRange(referenceStationId = "NOPE", hops = RailRangeHops.TWO)
        assertFailsWith<IllegalArgumentException> { QuestionEngine.answer(spec, a1, network) }
    }

    // ----------------------------------------------------------------- empty network

    @Test
    fun `nearest-station questions on an empty network throw IllegalStateException`() {
        val empty = TransitNetwork(emptyList(), emptyList(), emptyList())
        val specs = listOf(
            QuestionSpec.LineCheck(routeId = "red"),
            QuestionSpec.StationDossier(DossierAttribute.Interchange),
            QuestionSpec.Lineup(listOf("A1", "A2", "A3")),
        )
        for (spec in specs) {
            assertFailsWith<IllegalStateException>("$spec") { QuestionEngine.answer(spec, a1, empty) }
        }
    }

    // ----------------------------------------------------------------- compensation

    @Test
    fun `compensation follows the GAME_DESIGN section 3 table`() {
        data class Case(val spec: QuestionSpec, val draw: Int, val keep: Int)
        val cases = listOf(
            Case(QuestionSpec.RadiusPing(a1, radius = PingRadius.M500), draw = 4, keep = 2),
            Case(QuestionSpec.RadiusPing(a1, radius = PingRadius.KM1), draw = 3, keep = 2),
            Case(QuestionSpec.RadiusPing(a1, radius = PingRadius.KM2), draw = 3, keep = 1),
            Case(QuestionSpec.RadiusPing(a1, radius = PingRadius.KM5), draw = 2, keep = 1),
            Case(QuestionSpec.CompassCall("A1", CompassAxis.NORTH_SOUTH), draw = 2, keep = 1),
            Case(QuestionSpec.Thermometer(a1), draw = 3, keep = 1),
            Case(QuestionSpec.LineCheck("red"), draw = 2, keep = 1),
            Case(QuestionSpec.StationDossier(DossierAttribute.Terminus), draw = 2, keep = 1),
            Case(QuestionSpec.Lineup(listOf("A1", "A2", "A3")), draw = 2, keep = 1),
            Case(QuestionSpec.RailRange("A1", RailRangeHops.TWO), draw = 3, keep = 2),
            Case(QuestionSpec.RailRange("A1", RailRangeHops.FOUR), draw = 3, keep = 1),
            Case(QuestionSpec.RailRange("A1", RailRangeHops.EIGHT), draw = 2, keep = 1),
        )
        for (case in cases) {
            assertEquals(CompensationRule(case.draw, case.keep), QuestionEngine.compensationFor(case.spec), "$case")
        }
    }

    @Test
    fun `Off-Peak Pass doubling yields draw 2D keep 2K`() {
        assertEquals(CompensationRule(6, 2), QuestionEngine.doubled(CompensationRule(3, 1)))
        assertEquals(CompensationRule(8, 4), QuestionEngine.doubled(CompensationRule(4, 2)))
        // Doubling the per-parameter rules works the same way.
        assertEquals(
            CompensationRule(8, 4),
            QuestionEngine.doubled(QuestionEngine.compensationFor(QuestionSpec.RadiusPing(a1, radius = PingRadius.M500))),
        )
    }
}
