package io.terminus.core.game

import io.terminus.core.cards.CardType
import io.terminus.core.cards.EffectParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Transfer Slip (C13) relocation: sim 5-edge limit, GPS window + overrun score freeze. */
class RelocationTest {

    @Test
    fun `sim relocation moves the zone and the token within five edges`() {
        val h = EngineHarness(EngineHarness.simConfig())
        h.start()
        h.hideAtAndStartSeeking("DV-A10")
        h.giveHand(CardType.TRANSFER_SLIP)
        val emitted = h.playCard(
            h.humanId,
            CardType.TRANSFER_SLIP,
            EffectParams.RelocateParams("DV-A12"), // 2 edges from DV-A10
        )
        assertTrue(emitted.any { it is GameEvent.HiderRelocating })
        assertEquals("DV-A12", h.state.hiderZoneStationId)
        assertNull(h.state.hiderRelocationDeadlineMillis) // GPS-only window

        h.tick(225_000L) // A10→A11→A12 = 90 + 135 s
        assertEquals(PlayerPosition.NodePosition("DV-A12"), h.state.positions[h.humanId])
    }

    @Test
    fun `sim relocation beyond five edges or to an excluded station is rejected`() {
        val h = EngineHarness(EngineHarness.simConfig())
        h.start()
        h.hideAtAndStartSeeking("DV-A10")
        h.giveHand(CardType.TRANSFER_SLIP)

        // DV-C06 is far more than 5 edges from DV-A10.
        val tooFar = h.playCard(h.humanId, CardType.TRANSFER_SLIP, EffectParams.RelocateParams("DV-C06"))
        assertTrue(tooFar.isEmpty())
        assertEquals("DV-A10", h.state.hiderZoneStationId)
        assertTrue(CardType.TRANSFER_SLIP in h.state.hand)

        // DV-A09 is within 5 edges but excluded as a hiding zone (2 hops from start).
        val excluded = h.playCard(h.humanId, CardType.TRANSFER_SLIP, EffectParams.RelocateParams("DV-A09"))
        assertTrue(excluded.isEmpty())
        assertEquals("DV-A10", h.state.hiderZoneStationId)
    }

    @Test
    fun `gps relocation window, overrun score freeze, and resume on re-hide`() {
        val config = EngineHarness.gpsConfig(humanRole = Role.HIDER, hidingPhaseMinutes = 5)
        val h = EngineHarness(config)
        h.start()
        h.gpsFix(h.humanId, h.stationLatLng("DV-A10"), 0L)
        h.tick(5 * 60_000L) // SEEKING at t=300 s, zone DV-A10
        assertEquals("DV-A10", h.state.hiderZoneStationId)

        h.giveHand(CardType.TRANSFER_SLIP)
        h.tick(100_000L) // play at t=400 s
        val emitted = h.playCard(h.humanId, CardType.TRANSFER_SLIP)
        assertTrue(emitted.any { it is GameEvent.HiderRelocating })
        assertNull(h.state.hiderZoneStationId)
        assertEquals(h.state.gameTimeMillis + 10 * 60_000L, h.state.hiderRelocationDeadlineMillis)

        // Staying inside the OLD zone does not count as re-hiding.
        h.tick(60_000L)
        assertNull(h.state.hiderZoneStationId)

        // Window expires at t=1000 s: score freezes from that moment.
        h.tick(640_000L) // t=1100 s, 100 s past the deadline
        assertTrue(h.state.scoreAccrualPaused)
        assertTrue(h.events.any { it is GameEvent.ScoreAccrualChanged && it.paused })

        // Re-hiding at DV-A12 resumes the score.
        h.gpsFix(h.humanId, h.stationLatLng("DV-A12"))
        assertEquals("DV-A12", h.state.hiderZoneStationId)
        assertNull(h.state.hiderRelocationDeadlineMillis)
        assertEquals(false, h.state.scoreAccrualPaused)

        // Run to clock end (t=1200 s) and check the frozen window was excluded:
        // survival = (1000−300) + (1200−1100) = 800 s = 13.3 min; +10 never captured.
        h.tick(100_000L)
        assertEquals(GamePhase.ROUND_END, h.state.phase)
        val ended = h.events.filterIsInstance<GameEvent.RoundEnded>().single()
        assertEquals(23.3, ended.hiderScore)
    }
}
