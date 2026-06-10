package io.terminus.core.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hiding-zone validity, the 2-hop exclusion, and the §2.1 grace period. */
class HidingZoneTest {

    @Test
    fun `eligible station becomes the hiding zone at phase end`() {
        val h = EngineHarness(EngineHarness.simConfig())
        h.start()
        h.hideAtAndStartSeeking("DV-A10") // 3 hops from the start station
        assertEquals("DV-A10", h.state.hiderZoneStationId)
    }

    @Test
    fun `station within two hops of the start is rejected and triggers grace`() {
        val h = EngineHarness(EngineHarness.simConfig())
        h.start()
        h.moveToken(h.hiderId, "DV-A09") // exactly 2 hops: excluded (§5.1)
        h.tick(600_000L)
        assertEquals(GamePhase.SEEKING, h.state.phase)
        assertNull(h.state.hiderZoneStationId) // grace running, no zone yet

        // The nearest valid station to DV-A09 is DV-A10; entering it inside 3:00 sets the zone.
        h.moveToken(h.hiderId, "DV-A10") // allowed during grace; 120 s edge
        h.tick(120_000L)
        assertEquals("DV-A10", h.state.hiderZoneStationId)

        // Survival is not capped: run to the end and check full scoring.
        h.tick(20 * 60_000L)
        assertEquals(GamePhase.ROUND_END, h.state.phase)
        val ended = h.events.filterIsInstance<GameEvent.RoundEnded>().single()
        // Survived 10 min of seeking + 10.0 never-captured bonus.
        assertEquals(20.0, ended.hiderScore)
    }

    @Test
    fun `failing the grace caps the hider score at the hiding-phase length`() {
        val h = EngineHarness(EngineHarness.simConfig())
        h.start()
        h.moveToken(h.hiderId, "DV-A09")
        h.tick(600_000L) // hiding ends; hider sits on an excluded station
        h.tick(3 * 60_000L) // grace expires unentered
        assertEquals("DV-A10", h.state.hiderZoneStationId) // app-picked nearest valid station

        h.tick(20 * 60_000L)
        val ended = h.events.filterIsInstance<GameEvent.RoundEnded>().single()
        // Uncapped it would be 10.0 survival + 10.0 bonus; the failed grace caps at 10 (§2.1).
        assertEquals(10.0, ended.hiderScore)
    }

    @Test
    fun `hider movement is locked once the seeking phase starts`() {
        val h = EngineHarness(EngineHarness.simConfig())
        h.start()
        h.hideAtAndStartSeeking("DV-A10")
        val rejected = h.moveToken(h.hiderId, "DV-A12")
        assertTrue(rejected.isEmpty())
        h.tick(300_000L)
        assertEquals(PlayerPosition.NodePosition("DV-A10"), h.state.positions[h.hiderId])
    }

    @Test
    fun `gps hider outside the zone for over 60s pauses score accrual`() {
        val config = EngineHarness.gpsConfig(humanRole = Role.HIDER, hidingPhaseMinutes = 5)
        val h = EngineHarness(config)
        h.start()
        val zoneCenter = h.stationLatLng("DV-A10")
        h.gpsFix(h.humanId, zoneCenter, 0L)
        h.tick(5 * 60_000L)
        assertEquals(GamePhase.SEEKING, h.state.phase)
        assertEquals("DV-A10", h.state.hiderZoneStationId)

        // Excursion: 400 m from the zone center, sampled every 5 s past the 60 s grace.
        val outside = io.terminus.core.geo.GeoMath.destinationPoint(zoneCenter, 0.0, 400.0)
        var t = h.state.gameTimeMillis
        repeat(15) {
            t += 5_000L
            h.gpsFix(h.humanId, outside, t)
            h.tick(5_000L)
        }
        assertTrue(h.state.scoreAccrualPaused)
        assertTrue(h.events.any { it is GameEvent.ScoreAccrualChanged && it.paused })

        // Returning inside resumes accrual.
        repeat(3) {
            t += 5_000L
            h.gpsFix(h.humanId, zoneCenter, t)
            h.tick(5_000L)
        }
        assertEquals(false, h.state.scoreAccrualPaused)
    }
}
