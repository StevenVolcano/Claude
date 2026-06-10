package io.terminus.core.game

import io.terminus.core.cards.CardType
import io.terminus.core.cards.EffectParams
import io.terminus.core.questions.Answer
import io.terminus.core.questions.PingRadius
import io.terminus.core.questions.QuestionCategory
import io.terminus.core.questions.QuestionRules
import io.terminus.core.questions.QuestionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The §3 question pipeline: ask → response window → answer → compensation → cooldowns. */
class QuestionFlowTest {

    private fun seekingHarness(): EngineHarness {
        val h = EngineHarness(EngineHarness.simConfig())
        h.start()
        h.hideAtAndStartSeeking("DV-A10")
        return h
    }

    private fun lineCheck(routeId: String = "DV-LINE-A") = QuestionSpec.LineCheck(routeId)

    @Test
    fun `happy path - ask, auto-answer at window end, draw-keep, cooldowns`() {
        val h = seekingHarness()
        val askTime = h.state.gameTimeMillis
        val asked = h.send(GameCommand.AskQuestion(h.ai1, lineCheck(), askTime))
        assertTrue(asked.any { it is GameEvent.QuestionAsked })
        assertNotNull(h.state.pendingQuestion)

        val emitted = h.tick(QuestionRules.RESPONSE_WINDOW_MILLIS)
        val delivered = emitted.filterIsInstance<GameEvent.AnswerDelivered>().single()
        assertEquals(Answer.YesNo(true), delivered.answer) // DV-A10 is on Line A
        assertEquals(false, delivered.wasDecoy)
        assertNull(h.state.pendingQuestion)
        assertEquals(1, h.state.questionsAnswered)

        // Compensation: Line Check = draw 2 keep 1 (§3 table).
        val pending = assertNotNull(h.state.pendingKeep)
        assertEquals(2, pending.drawn.size)
        assertEquals(1, pending.keep)
        val deliveryTime = h.state.gameTimeMillis
        assertEquals(deliveryTime + 2 * 60_000L, h.state.globalCooldownUntilMillis)
        assertEquals(
            deliveryTime + 3 * 60_000L,
            h.state.categoryCooldownUntilMillis[QuestionCategory.LINE_CHECK],
        )

        val kept = h.send(GameCommand.KeepCards(h.humanId, listOf(pending.drawn[0]), deliveryTime))
        assertTrue(kept.any { it is GameEvent.CardsDrawn && it.drawn == 2 && it.kept == 1 })
        assertEquals(listOf(pending.drawn[0]), h.state.hand)
        assertNull(h.state.pendingKeep)
        assertEquals(48, h.state.deckCount)
        assertEquals(1, h.state.discardCount)

        // Cooldowns block an immediate re-ask.
        val reAsk = h.send(GameCommand.AskQuestion(h.ai1, lineCheck(), h.state.gameTimeMillis))
        assertTrue(reAsk.isEmpty())
        assertNull(h.state.pendingQuestion)
    }

    @Test
    fun `veto cancels the answer but keeps both cooldowns`() {
        val h = seekingHarness()
        h.giveHand(CardType.CONDUCTORS_OVERRIDE)
        h.send(GameCommand.AskQuestion(h.ai1, lineCheck(), h.state.gameTimeMillis))
        h.tick(5_000L) // still inside the 20 s window
        val vetoTime = h.state.gameTimeMillis
        val emitted = h.playCard(h.humanId, CardType.CONDUCTORS_OVERRIDE)

        assertTrue(emitted.any { it is GameEvent.AnswerVetoed })
        assertNull(h.state.pendingQuestion)
        assertNull(h.state.pendingKeep) // no compensation (§4.2 C11)
        assertEquals(0, h.state.questionsAnswered)
        assertEquals(vetoTime + 2 * 60_000L, h.state.globalCooldownUntilMillis)
        assertEquals(
            vetoTime + 3 * 60_000L,
            h.state.categoryCooldownUntilMillis[QuestionCategory.LINE_CHECK],
        )
        // Nothing further happens at window end.
        val later = h.tick(QuestionRules.RESPONSE_WINDOW_MILLIS)
        assertTrue(later.none { it is GameEvent.AnswerDelivered })
    }

    @Test
    fun `ghost echo answers from the decoy point and is revealed after delivery`() {
        val h = seekingHarness()
        h.giveHand(CardType.GHOST_ECHO)
        // Truth: hider at DV-A10. Ping centered on DV-A10 at 500 m would be Yes.
        val spec = QuestionSpec.RadiusPing(
            center = h.stationLatLng("DV-A10"),
            centerStationId = "DV-A10",
            radius = PingRadius.M500,
        )
        h.send(GameCommand.AskQuestion(h.ai1, spec, h.state.gameTimeMillis))
        // Decoy at DV-A11 (750 m away, within the 1.5 km limit) flips the answer to No.
        h.playCard(h.humanId, CardType.GHOST_ECHO, EffectParams.DecoyParams(h.stationLatLng("DV-A11")))

        val emitted = h.tick(QuestionRules.RESPONSE_WINDOW_MILLIS)
        val delivered = emitted.filterIsInstance<GameEvent.AnswerDelivered>().single()
        assertEquals(Answer.YesNo(false), delivered.answer)
        assertTrue(delivered.wasDecoy)
        // The reveal follows the delivery (§4.2 C12), and compensation is still earned.
        assertTrue(emitted.any { it is GameEvent.DecoyRevealed })
        val pending = assertNotNull(h.state.pendingKeep)
        assertEquals(4, pending.drawn.size) // 500 m ping: draw 4 keep 2
        assertEquals(2, pending.keep)
    }

    @Test
    fun `decoy farther than 1500 m is rejected`() {
        val h = seekingHarness()
        h.giveHand(CardType.GHOST_ECHO)
        h.send(
            GameCommand.AskQuestion(
                h.ai1,
                QuestionSpec.RadiusPing(h.stationLatLng("DV-A10"), "DV-A10", PingRadius.M500),
                h.state.gameTimeMillis,
            ),
        )
        // DV-A13 is 2250 m from DV-A10.
        val rejected = h.playCard(h.humanId, CardType.GHOST_ECHO, EffectParams.DecoyParams(h.stationLatLng("DV-A13")))
        assertTrue(rejected.isEmpty())
        assertTrue(CardType.GHOST_ECHO in h.state.hand)
    }

    @Test
    fun `scrambled signal delays delivery and cooldowns start at delivery`() {
        val h = seekingHarness()
        h.giveHand(CardType.SCRAMBLED_SIGNAL)
        h.playCard(h.humanId, CardType.SCRAMBLED_SIGNAL)
        h.send(GameCommand.AskQuestion(h.ai1, lineCheck(), h.state.gameTimeMillis))

        val atWindowEnd = h.tick(QuestionRules.RESPONSE_WINDOW_MILLIS)
        assertTrue(atWindowEnd.none { it is GameEvent.AnswerDelivered })
        val delayed = assertNotNull(h.state.delayedAnswer)
        val computedAt = h.state.gameTimeMillis
        assertEquals(computedAt + 5 * 60_000L, delayed.deliverAtGameMillis)
        assertNull(h.state.pendingQuestion)
        assertEquals(0L, h.state.globalCooldownUntilMillis) // not started yet

        val atDelivery = h.tick(5 * 60_000L)
        assertTrue(atDelivery.any { it is GameEvent.AnswerDelivered })
        assertNull(h.state.delayedAnswer)
        val deliveryTime = h.state.gameTimeMillis
        assertEquals(deliveryTime + 2 * 60_000L, h.state.globalCooldownUntilMillis)
        assertNotNull(h.state.pendingKeep)
    }

    @Test
    fun `off-peak pass doubles the compensation of the next answer`() {
        val h = seekingHarness()
        h.giveHand(CardType.OFF_PEAK_PASS)
        h.playCard(h.humanId, CardType.OFF_PEAK_PASS)
        assertTrue(h.state.doubleCompensationPending)

        h.send(GameCommand.AskQuestion(h.ai1, lineCheck(), h.state.gameTimeMillis))
        h.tick(QuestionRules.RESPONSE_WINDOW_MILLIS)

        val pending = assertNotNull(h.state.pendingKeep)
        assertEquals(4, pending.drawn.size) // draw 2D = 4
        assertEquals(2, pending.keep) // keep 2K = 2
        assertEquals(false, h.state.doubleCompensationPending)
    }

    @Test
    fun `keep over the hand limit forces a discard down`() {
        val h = seekingHarness()
        h.giveHand(
            CardType.RUSH_HOUR_DELAY, CardType.RUSH_HOUR_DELAY, CardType.RUSH_HOUR_DELAY,
            CardType.RUSH_HOUR_DELAY, CardType.RUSH_HOUR_DELAY,
        )
        h.send(
            GameCommand.AskQuestion(
                h.ai1,
                QuestionSpec.RadiusPing(h.stationLatLng("DV-A10"), "DV-A10", PingRadius.M500),
                h.state.gameTimeMillis,
            ),
        )
        h.tick(QuestionRules.RESPONSE_WINDOW_MILLIS)
        val pending = assertNotNull(h.state.pendingKeep) // draw 4 keep 2
        h.send(GameCommand.KeepCards(h.humanId, pending.drawn.take(2), h.state.gameTimeMillis))
        assertEquals(7, h.state.hand.size) // over the limit of 6
        assertTrue(h.state.hand.size > h.state.handLimit)

        h.send(GameCommand.DiscardCards(h.humanId, listOf(h.state.hand.first()), h.state.gameTimeMillis))
        assertEquals(6, h.state.hand.size)
    }

    @Test
    fun `thermometer arms, resolves after two edges, and answers warmer`() {
        val h = seekingHarness() // hider at DV-A10
        val asked = h.send(
            GameCommand.AskQuestion(h.ai1, QuestionSpec.Thermometer(h.stationLatLng("DV-A07")), h.state.gameTimeMillis),
        )
        assertTrue(asked.any { it is GameEvent.QuestionAsked })
        val armed = assertNotNull(h.state.armedThermometer)
        assertEquals(h.ai1, armed.seekerId)
        assertNull(h.state.pendingQuestion)

        // Move the seeker two edges toward the hider: A07 → A09 (105 + 150 s).
        h.moveToken(h.ai1, "DV-A09")
        h.tick(255_000L)
        assertNull(h.state.armedThermometer) // resolved into the response window
        val pending = assertNotNull(h.state.pendingQuestion)
        assertNotNull((pending.spec as QuestionSpec.Thermometer).resolvePosition)

        val emitted = h.tick(QuestionRules.RESPONSE_WINDOW_MILLIS)
        val delivered = emitted.filterIsInstance<GameEvent.AnswerDelivered>().single()
        assertEquals(Answer.WarmerColder(warmer = true), delivered.answer)
        // Q3 cooldown starts at resolution/delivery, not at arm time (§3).
        assertEquals(
            h.state.gameTimeMillis + 6 * 60_000L,
            h.state.categoryCooldownUntilMillis[QuestionCategory.THERMOMETER],
        )
    }
}
