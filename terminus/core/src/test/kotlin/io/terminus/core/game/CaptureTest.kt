package io.terminus.core.game

import io.terminus.core.geo.GeoMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Capture conditions (GAME_DESIGN.md §5.3): GPS 75 m / 10 s sustained; sim same-node. */
class CaptureTest {

    /** GPS mode, human seeker, AI hider parked at the start station. */
    private fun gpsSeekerHarness(): EngineHarness {
        val h = EngineHarness(EngineHarness.gpsConfig(humanRole = Role.SEEKER, hidingPhaseMinutes = 5))
        h.start()
        h.tick(5 * 60_000L) // hiding phase ends; the AI hider never moved
        check(h.state.phase == GamePhase.SEEKING)
        return h
    }

    @Test
    fun `nine seconds at 70 m is not a capture`() {
        val h = gpsSeekerHarness()
        val hiderPos = h.stationLatLng("DV-A07") // the parked AI hider
        val at70m = GeoMath.destinationPoint(hiderPos, 90.0, 70.0)
        h.gpsFix(h.humanId, at70m)
        h.tickSecondsStepped(9)
        assertNull(h.state.capturedBy)

        // Stepping out of the radius resets the sustain clock.
        h.gpsFix(h.humanId, GeoMath.destinationPoint(hiderPos, 90.0, 200.0))
        h.tick(1_000L)
        h.gpsFix(h.humanId, at70m)
        h.tickSecondsStepped(9)
        assertNull(h.state.capturedBy)
    }

    @Test
    fun `ten sustained seconds at 70 m captures the hider`() {
        val h = gpsSeekerHarness()
        val hiderPos = h.stationLatLng("DV-A07")
        h.gpsFix(h.humanId, GeoMath.destinationPoint(hiderPos, 90.0, 70.0))
        h.tickSecondsStepped(11)

        assertEquals(h.humanId, h.state.capturedBy)
        assertEquals(GamePhase.ROUND_END, h.state.phase)
        val captured = h.events.filterIsInstance<GameEvent.Captured>().single()
        assertEquals(h.humanId, captured.seekerId)
        val ended = h.events.filterIsInstance<GameEvent.RoundEnded>().single()
        assertTrue(ended.captured)
        assertEquals(h.humanId, ended.capturedBy)
    }

    @Test
    fun `seventy-six meters never captures`() {
        val h = gpsSeekerHarness()
        val hiderPos = h.stationLatLng("DV-A07")
        h.gpsFix(h.humanId, GeoMath.destinationPoint(hiderPos, 90.0, 80.0))
        h.tickSecondsStepped(30)
        assertNull(h.state.capturedBy)
    }

    @Test
    fun `sim capture on same node and survival scoring on capture`() {
        val h = EngineHarness(EngineHarness.simConfig()) // human hider, sim
        h.start()
        h.hideAtAndStartSeeking("DV-A10")
        // Drive the AI seeker to the hider's node: A07 → A10 takes 375 s.
        h.moveToken(h.ai1, "DV-A10")
        h.tick(374_000L)
        assertNull(h.state.capturedBy) // still mid-edge
        h.tick(1_000L) // arrival: same node as the hider token

        assertEquals(h.ai1, h.state.capturedBy)
        assertEquals(GamePhase.ROUND_END, h.state.phase)
        val ended = h.events.filterIsInstance<GameEvent.RoundEnded>().single()
        assertTrue(ended.captured)
        // Survived 375 s of the seeking phase = 6.25 min, rounded to 6.3; no bonus (captured).
        assertEquals(6.3, ended.hiderScore)
        assertEquals(6.3, h.state.matchScores[h.humanId])
        // Round record carries the capture for the end screen.
        val record = h.engine.buildRoundRecord(h.state)
        assertTrue(record.captured)
        assertEquals(h.ai1, record.capturedBy)
        assertEquals(6.3, record.hiderScore)
        assertEquals(975_000L, record.captureGameMillis)
    }
}
