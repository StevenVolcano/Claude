package io.terminus.core.game

import io.terminus.core.cards.CardType
import io.terminus.core.geo.GeoMath
import io.terminus.core.questions.QuestionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Final Approach (GAME_DESIGN.md §2.1 phase 4): trigger, lockouts, persisting effects. */
class FinalApproachTest {

    @Test
    fun `sim adjacency triggers final approach and disables questions and cards`() {
        val h = EngineHarness(EngineHarness.simConfig())
        h.start()
        h.hideAtAndStartSeeking("DV-A10")
        h.giveHand(CardType.TUNNEL_VISION, CardType.RUSH_HOUR_DELAY)
        val curseEvents = h.playCard(h.humanId, CardType.TUNNEL_VISION) // 8:00 curse
        assertTrue(curseEvents.any { it is GameEvent.CurseStarted })
        val curseStart = h.state.gameTimeMillis

        h.moveToken(h.ai1, "DV-A10")
        h.tick(260_000L) // seeker reaches DV-A09 (255 s) — adjacent to the hider's node
        assertEquals(GamePhase.FINAL_APPROACH, h.state.phase)
        assertTrue(
            h.events.any {
                it is GameEvent.PhaseChanged &&
                    it.from == GamePhase.SEEKING && it.to == GamePhase.FINAL_APPROACH
            },
        )

        // Questions disabled (§2.1).
        val ask = h.send(
            GameCommand.AskQuestion(h.ai1, QuestionSpec.LineCheck("DV-LINE-A"), h.state.gameTimeMillis),
        )
        assertTrue(ask.isEmpty())
        assertNull(h.state.pendingQuestion)

        // New card plays disabled (§4.1).
        val play = h.playCard(h.humanId, CardType.RUSH_HOUR_DELAY)
        assertTrue(play.isEmpty())
        assertEquals(0.0, h.state.bonusMinutes)

        // Already-active effects keep running: Tunnel Vision expires on schedule.
        assertTrue(h.state.activeEffects.any { it.type == CardType.TUNNEL_VISION })
        val expiry = h.tick(curseStart + 8 * 60_000L - h.state.gameTimeMillis)
        assertTrue(expiry.any { it is GameEvent.CurseEnded && it.type == CardType.TUNNEL_VISION })
        assertTrue(h.state.activeEffects.none { it.type == CardType.TUNNEL_VISION })
    }

    @Test
    fun `gps proximity to the hiding zone triggers final approach`() {
        val h = EngineHarness(EngineHarness.gpsConfig(humanRole = Role.SEEKER, hidingPhaseMinutes = 5))
        h.start()
        h.tick(5 * 60_000L) // AI hider parked at start: grace begins
        // Step away from the start node so the parked hider is not captured meanwhile.
        h.gpsFix(h.humanId, GeoMath.destinationPoint(h.stationLatLng("DV-A07"), 0.0, 500.0))
        h.tick(3 * 60_000L) // grace fails: zone = nearest valid station (app-picked)
        val zone = checkNotNull(h.state.hiderZoneStationId)

        // The human seeker walks to 250 m from the zone center: inside the 300 m trigger.
        val nearZone = GeoMath.destinationPoint(h.stationLatLng(zone), 0.0, 250.0)
        h.gpsFix(h.humanId, nearZone)
        h.tick(1_000L)
        assertEquals(GamePhase.FINAL_APPROACH, h.state.phase)
    }
}
