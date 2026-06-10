package io.terminus.core.sim

import io.terminus.core.game.PlayerId
import io.terminus.core.game.PlayerPosition
import io.terminus.core.geo.LatLng
import io.terminus.core.transit.Station
import io.terminus.core.transit.TransitEdge
import io.terminus.core.transit.TransitMode
import io.terminus.core.transit.TransitNetwork
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test plan item 6: token edge interpolation, dwell enforcement under C5 (Local
 * Service), freeze under C4 (Stalled Train), Easy-AI speed multiplier, completion.
 *
 * Inline path A -> B -> C, 100 s per edge on route "R1".
 */
class SimulationEngineTest {

    private val p1 = PlayerId("ai-1")

    private val edgeAB = TransitEdge("A", "B", "R1", 100, 750.0)
    private val edgeBC = TransitEdge("B", "C", "R1", 100, 750.0)
    private val plan = MovementPlan(edges = listOf(edgeAB, edgeBC))

    private fun tickOne(actor: ActorState, dt: Double): Pair<ActorState, List<ArrivalEvent>> {
        val result = SimulationEngine.tick(mapOf(p1 to actor), dt)
        return result.actors.getValue(p1) to result.arrivals
    }

    @Test
    fun startsAtOriginNode() {
        assertEquals(PlayerPosition.NodePosition("A"), ActorState(plan).position)
    }

    @Test
    fun interpolatesAlongEdgeAtKnownTimes() {
        var actor = ActorState(plan)
        actor = tickOne(actor, 25.0).first
        assertEquals(PlayerPosition.EdgePosition("A", "B", "R1", 0.25), actor.position)
        actor = tickOne(actor, 50.0).first
        assertEquals(PlayerPosition.EdgePosition("A", "B", "R1", 0.75), actor.position)
    }

    @Test
    fun crossesNodeWithinOneTickWithoutLosingTime() {
        val (actor, arrivals) = tickOne(ActorState(plan), 105.0)
        // 100 s on A->B, arrival at B, 5 s into B->C.
        assertEquals(PlayerPosition.EdgePosition("B", "C", "R1", 0.05), actor.position)
        assertEquals(listOf(ArrivalEvent(p1, "B", isFinal = false)), arrivals)
    }

    @Test
    fun completesAtFinalNodeAndStaysThere() {
        val (actor, arrivals) = tickOne(ActorState(plan), 200.0)
        assertTrue(actor.isComplete)
        assertEquals(PlayerPosition.NodePosition("C"), actor.position)
        assertEquals(
            listOf(ArrivalEvent(p1, "B", isFinal = false), ArrivalEvent(p1, "C", isFinal = true)),
            arrivals,
        )
        // Further ticks: no movement, no events.
        val (after, laterArrivals) = tickOne(actor, 500.0)
        assertEquals(actor, after)
        assertTrue(laterArrivals.isEmpty())
    }

    @Test
    fun dwellEnforcementTimingUnderLocalService() {
        // Curse of the Local Service: 45 s dwell at every intermediate node (C5).
        val cursedPlan = plan.copy(dwellSecondsByStationId = mapOf("B" to 45, "C" to 45))
        var actor = ActorState(cursedPlan)

        actor = tickOne(actor, 100.0).first // arrive B at t=100, dwell starts
        assertEquals(PlayerPosition.NodePosition("B"), actor.position)
        assertEquals(45.0, actor.dwellRemainingSeconds)

        actor = tickOne(actor, 20.0).first // t=120: still dwelling
        assertEquals(PlayerPosition.NodePosition("B"), actor.position)
        assertEquals(25.0, actor.dwellRemainingSeconds)

        actor = tickOne(actor, 35.0).first // t=155: dwell ended at 145, 10 s into B->C
        assertEquals(PlayerPosition.EdgePosition("B", "C", "R1", 0.10), actor.position)

        // Final node arrival ends the plan; the dwell map entry for C is irrelevant.
        val (done, arrivals) = tickOne(actor, 90.0) // t=245: B->C completes at 245
        assertTrue(done.isComplete)
        assertEquals(listOf(ArrivalEvent(p1, "C", isFinal = true)), arrivals)
    }

    @Test
    fun dwellIsCrossedWithinOneLargeTick() {
        val cursedPlan = plan.copy(dwellSecondsByStationId = mapOf("B" to 45))
        // 100 (A->B) + 45 (dwell) + 100 (B->C) = 245 s total.
        val (actor, arrivals) = tickOne(ActorState(cursedPlan), 245.0)
        assertTrue(actor.isComplete)
        assertEquals(2, arrivals.size)
        // One second less: 1 s short of C.
        val (almost, _) = tickOne(ActorState(cursedPlan), 244.0)
        assertEquals(PlayerPosition.EdgePosition("B", "C", "R1", 0.99), almost.position)
    }

    @Test
    fun frozenActorDoesNotAdvance() {
        // Curse of the Stalled Train: frozen mid-edge (C4).
        var actor = tickOne(ActorState(plan), 40.0).first
        val frozen = actor.copy(frozen = true)
        val (still, arrivals) = tickOne(frozen, 300.0)
        assertEquals(frozen, still)
        assertEquals(PlayerPosition.EdgePosition("A", "B", "R1", 0.4), still.position)
        assertTrue(arrivals.isEmpty())
        // Unfreeze: movement resumes from where it stopped (40 + 60 = 100 s, at B).
        actor = tickOne(still.copy(frozen = false), 60.0).first
        assertEquals(PlayerPosition.NodePosition("B"), actor.position)
    }

    @Test
    fun speedMultiplierStretchesTravelTime() {
        // Easy AI 0.85x: a 85 s edge takes 85 / 0.85 = 100 s.
        val shortPlan = MovementPlan(edges = listOf(TransitEdge("A", "B", "R1", 85, 750.0)))
        var actor = ActorState(shortPlan, speedMultiplier = 0.85)
        actor = tickOne(actor, 50.0).first
        val position = assertIs<PlayerPosition.EdgePosition>(actor.position)
        assertTrue(abs(position.fraction - 0.5) < 1e-9, "fraction was ${position.fraction}")
        val (done, arrivals) = tickOne(actor, 50.0)
        assertTrue(done.isComplete)
        assertEquals(listOf(ArrivalEvent(p1, "B", isFinal = true)), arrivals)
    }

    @Test
    fun ticksMultipleActorsIndependently() {
        val p2 = PlayerId("ai-2")
        val result = SimulationEngine.tick(
            mapOf(
                p1 to ActorState(plan),
                p2 to ActorState(plan, frozen = true),
            ),
            dtGameSeconds = 150.0,
        )
        assertEquals(PlayerPosition.EdgePosition("B", "C", "R1", 0.5), result.actors.getValue(p1).position)
        assertEquals(PlayerPosition.NodePosition("A"), result.actors.getValue(p2).position)
        assertEquals(listOf(ArrivalEvent(p1, "B", isFinal = false)), result.arrivals)
    }

    @Test
    fun rejectsNegativeDtAndEmptyPlan() {
        assertFailsWith<IllegalArgumentException> {
            SimulationEngine.tick(mapOf(p1 to ActorState(plan)), -1.0)
        }
        assertFailsWith<IllegalArgumentException> { ActorState(MovementPlan(emptyList())) }
    }

    // --- latLngOf -----------------------------------------------------------------

    private val network = TransitNetwork(
        stations = listOf(
            Station("A", "Alpha", LatLng(52.00, 5.00), TransitMode.METRO, listOf("R1"), false, true),
            Station("B", "Beta", LatLng(52.00, 5.01), TransitMode.METRO, listOf("R1"), false, false),
        ),
        routes = emptyList(),
        edges = listOf(edgeAB),
    )

    private fun assertLatLngEquals(expected: LatLng, actual: LatLng?, epsilon: Double = 1e-9) {
        assertTrue(
            actual != null &&
                abs(expected.lat - actual.lat) < epsilon &&
                abs(expected.lon - actual.lon) < epsilon,
            "expected $expected, was $actual",
        )
    }

    @Test
    fun latLngOfInterpolatesEdgePositionsLinearly() {
        val mid = SimulationEngine.latLngOf(
            PlayerPosition.EdgePosition("A", "B", "R1", 0.5),
            network,
        )
        assertLatLngEquals(LatLng(52.00, 5.005), mid)
        val quarter = SimulationEngine.latLngOf(
            PlayerPosition.EdgePosition("A", "B", "R1", 0.25),
            network,
        )
        assertLatLngEquals(LatLng(52.00, 5.0025), quarter)
    }

    @Test
    fun latLngOfHandlesNodeAndGpsPositions() {
        assertEquals(
            LatLng(52.00, 5.01),
            SimulationEngine.latLngOf(PlayerPosition.NodePosition("B"), network),
        )
        val fix = LatLng(51.5, 4.9)
        assertEquals(
            fix,
            SimulationEngine.latLngOf(PlayerPosition.GpsPosition(fix), network),
        )
    }

    @Test
    fun latLngOfReturnsNullForUnknownStations() {
        assertNull(SimulationEngine.latLngOf(PlayerPosition.NodePosition("ZZ"), network))
        assertNull(
            SimulationEngine.latLngOf(PlayerPosition.EdgePosition("A", "ZZ", null, 0.5), network),
        )
        assertFalse(network.stationsById.containsKey("ZZ"))
    }
}
