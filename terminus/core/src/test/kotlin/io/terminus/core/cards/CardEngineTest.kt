package io.terminus.core.cards

import io.terminus.core.game.Difficulty
import io.terminus.core.game.GameConfig
import io.terminus.core.game.GameEvent
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameRules
import io.terminus.core.game.GameState
import io.terminus.core.game.PendingQuestion
import io.terminus.core.game.PlayMode
import io.terminus.core.game.Player
import io.terminus.core.game.PlayerId
import io.terminus.core.game.PlayerPosition
import io.terminus.core.game.Role
import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.questions.PingRadius
import io.terminus.core.questions.QuestionCategory
import io.terminus.core.questions.QuestionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardEngineTest {

    private val hider = PlayerId("hider")
    private val seeker1 = PlayerId("s1")
    private val seeker2 = PlayerId("s2")
    private val now = 20 * 60_000L
    private val hiderPos = LatLng(52.050, 5.080)

    private fun state(
        phase: GamePhase = GamePhase.SEEKING,
        playMode: PlayMode = PlayMode.GPS,
        hand: List<CardType> = emptyList(),
        activeEffects: List<ActiveEffect> = emptyList(),
        pendingQuestion: PendingQuestion? = null,
        visitHistory: Map<PlayerId, List<String>> = emptyMap(),
    ): GameState = GameState(
        config = GameConfig(cityId = "testville", playMode = playMode, seed = 42L),
        roundIndex = 0,
        phase = phase,
        gameTimeMillis = now,
        players = listOf(
            Player.HumanPlayer(hider, "Hider"),
            Player.AiPlayer(seeker1, "Seeker 1", Difficulty.MEDIUM),
            Player.AiPlayer(seeker2, "Seeker 2", Difficulty.MEDIUM),
        ),
        roles = mapOf(hider to Role.HIDER, seeker1 to Role.SEEKER, seeker2 to Role.SEEKER),
        positions = mapOf(
            hider to PlayerPosition.GpsPosition(hiderPos),
            seeker1 to PlayerPosition.NodePosition("st-b"),
            seeker2 to PlayerPosition.NodePosition("st-x"),
        ),
        visitHistory = visitHistory,
        hiderZoneStationId = "st-hide",
        activeEffects = activeEffects,
        pendingQuestion = pendingQuestion,
        hand = hand,
    )

    private fun pendingQ1() = PendingQuestion(
        spec = QuestionSpec.RadiusPing(center = LatLng(52.06, 5.09), radius = PingRadius.KM1),
        askedBy = seeker1,
        askedAtGameMillis = now - 5_000L,
        responseWindowEndsGameMillis = now + 15_000L,
    )

    private fun pendingQ4() = PendingQuestion(
        spec = QuestionSpec.LineCheck(routeId = "R1"),
        askedBy = seeker1,
        askedAtGameMillis = now - 5_000L,
        responseWindowEndsGameMillis = now + 15_000L,
    )

    private fun curse(type: CardType, expiry: Long? = now + 60_000L) =
        ActiveEffect(type, startGameMillis = now - 30_000L, expiryGameMillis = expiry)

    private fun play(
        s: GameState,
        card: CardType,
        params: EffectParams? = null,
        deck: Deck = Deck.shuffled(7L),
    ): ApplyResult = CardEngine.applyPlay(s, hider, card, params, deck, now)

    private fun assertValid(s: GameState, card: CardType, params: EffectParams? = null) {
        assertEquals(ValidationResult.Valid, CardEngine.validatePlay(s, hider, card, params))
    }

    private fun assertRejected(
        s: GameState,
        card: CardType,
        params: EffectParams?,
        expected: PlayRejection,
        playerId: PlayerId = hider,
    ) {
        val result = CardEngine.validatePlay(s, playerId, card, params)
        assertIs<ValidationResult.Invalid>(result, "expected rejection $expected")
        assertEquals(expected, result.rejection)
    }

    // --- Common play bookkeeping -------------------------------------------------------

    @Test
    fun `every play removes the card, discards it, counts it, and announces it`() {
        val deck = Deck.shuffled(7L)
        val s = state(hand = listOf(CardType.RUSH_HOUR_DELAY, CardType.DEAD_ZONE))
        val result = play(s, CardType.RUSH_HOUR_DELAY, deck = deck)

        assertEquals(listOf(CardType.DEAD_ZONE), result.state.hand)
        assertEquals(1, result.state.cardsPlayed)
        assertEquals(listOf(CardType.RUSH_HOUR_DELAY), result.deck.discardPile)
        assertEquals(result.deck.drawPileSize, result.state.deckCount)
        assertEquals(result.deck.discardPileSize, result.state.discardCount)
        val played = result.events.first()
        assertIs<GameEvent.CardPlayed>(played)
        assertEquals(CardType.RUSH_HOUR_DELAY, played.type)
        assertEquals(hider, played.playerId)
        assertEquals(now, played.gameTimeMillis)
        // applyPlay never appends to the event log itself (W6 does).
        assertTrue(result.state.eventLog.isEmpty())
    }

    @Test
    fun `applyPlay throws on an invalid play`() {
        val s = state(hand = emptyList())
        assertFailsWith<IllegalArgumentException> { play(s, CardType.RUSH_HOUR_DELAY) }
    }

    // --- C1-C3 time bonuses -------------------------------------------------------------

    @Test
    fun `C1 C2 C3 add their exact bonus minutes`() {
        val cases = mapOf(
            CardType.RUSH_HOUR_DELAY to 3.0,
            CardType.EXPRESS_SKIP to 5.0,
            CardType.NIGHT_OWL_SERVICE to 10.0,
        )
        for ((card, minutes) in cases) {
            val result = play(state(hand = listOf(card)), card)
            assertEquals(minutes, result.state.bonusMinutes, "bonus of ${card.displayName}")
            assertTrue(result.state.activeEffects.isEmpty())
            assertTrue(result.events.none { it is GameEvent.CurseStarted })
        }
    }

    // --- C4, C5, C9, C10 timed movement curses ------------------------------------------

    @Test
    fun `timed curses create effects with the §4_2 durations and announce countdowns`() {
        val durations = mapOf(
            CardType.STALLED_TRAIN to 4 * 60_000L,
            CardType.LOCAL_SERVICE to 10 * 60_000L,
            CardType.TICKET_INSPECTION to 3 * 60_000L,
        )
        for ((card, duration) in durations) {
            val result = play(state(hand = listOf(card)), card)
            val effect = result.state.activeEffects.single()
            assertEquals(card, effect.type)
            assertEquals(now, effect.startGameMillis)
            assertEquals(now + duration, effect.expiryGameMillis, "duration of ${card.displayName}")
            assertEquals(0, effect.restartsUsed)
            val started = result.events.filterIsInstance<GameEvent.CurseStarted>().single()
            assertEquals(now + duration, started.expiryGameMillis)
        }
    }

    @Test
    fun `C10 detour keeps its route params for 12 minutes`() {
        val params = EffectParams.DetourParams(routeId = "R1")
        val result = play(state(hand = listOf(CardType.DETOUR)), CardType.DETOUR, params)
        val effect = result.state.activeEffects.single()
        assertEquals(now + 12 * 60_000L, effect.expiryGameMillis)
        assertEquals(params, effect.params)
    }

    @Test
    fun `C10 without route params is rejected`() {
        assertRejected(state(hand = listOf(CardType.DETOUR)), CardType.DETOUR, null, PlayRejection.PARAMS_MISMATCH)
    }

    // --- C6 Tunnel Vision ----------------------------------------------------------------

    @Test
    fun `C6 disables Radius Ping for 8 minutes via the category cooldown field`() {
        val result = play(state(hand = listOf(CardType.TUNNEL_VISION)), CardType.TUNNEL_VISION)
        val until = now + 8 * 60_000L
        assertEquals(until, result.state.activeEffects.single().expiryGameMillis)
        assertEquals(until, result.state.categoryCooldownUntilMillis[QuestionCategory.RADIUS_PING])
    }

    @Test
    fun `C6 never lowers an existing longer Radius Ping cooldown`() {
        val longer = now + 20 * 60_000L
        val s = state(hand = listOf(CardType.TUNNEL_VISION)).copy(
            categoryCooldownUntilMillis = mapOf(QuestionCategory.RADIUS_PING to longer),
        )
        val result = CardEngine.applyPlay(s, hider, CardType.TUNNEL_VISION, null, Deck.shuffled(7L), now)
        assertEquals(longer, result.state.categoryCooldownUntilMillis[QuestionCategory.RADIUS_PING])
    }

    // --- C7 Scrambled Signal ---------------------------------------------------------------

    @Test
    fun `C7 marks the delayed-answer state and is consumed at answer computation`() {
        val played = play(state(hand = listOf(CardType.SCRAMBLED_SIGNAL)), CardType.SCRAMBLED_SIGNAL)
        val effect = played.state.activeEffects.single()
        assertEquals(CardType.SCRAMBLED_SIGNAL, effect.type)
        assertNull(effect.expiryGameMillis) // untimed marker: clears on the next answered question

        val computed = CardEngine.onAnswerComputed(played.state, now + 60_000L)
        assertEquals(5 * 60_000L, computed.delayMillis)
        assertTrue(computed.state.activeEffects.isEmpty())
        assertEquals(
            CardType.SCRAMBLED_SIGNAL,
            computed.events.filterIsInstance<GameEvent.CurseEnded>().single().type,
        )
        // A second computation has nothing to consume.
        assertEquals(0L, CardEngine.onAnswerComputed(computed.state, now + 61_000L).delayMillis)
    }

    // --- C8 U-Turn --------------------------------------------------------------------------

    @Test
    fun `C8 snapshots per-seeker return targets from visit history`() {
        val s = state(
            hand = listOf(CardType.U_TURN),
            visitHistory = mapOf(
                seeker1 to listOf("st-a", "st-b"), // at st-b now -> must return to st-a
                seeker2 to listOf("st-c"), // travelling, last visited st-c
            ),
        )
        val result = play(s, CardType.U_TURN)
        val effect = result.state.activeEffects.single()
        assertNull(effect.expiryGameMillis) // untimed, self-clearing
        assertEquals(
            mapOf(seeker1 to "st-a", seeker2 to "st-c"),
            CardEngine.uTurnReturnTargets(result.state),
        )

        // Seekers clear one by one; the effect self-clears with the last.
        val afterFirst = CardEngine.markUTurnReturned(result.state, seeker1, now + 60_000L)
        assertEquals(mapOf(seeker2 to "st-c"), CardEngine.uTurnReturnTargets(afterFirst.state))
        assertTrue(afterFirst.events.isEmpty())
        val afterSecond = CardEngine.markUTurnReturned(afterFirst.state, seeker2, now + 90_000L)
        assertTrue(afterSecond.state.activeEffects.isEmpty())
        assertEquals(
            CardType.U_TURN,
            afterSecond.events.filterIsInstance<GameEvent.CurseEnded>().single().type,
        )
    }

    // --- Curse cap -----------------------------------------------------------------------

    @Test
    fun `a third curse is rejected while two are active`() {
        val s = state(
            hand = listOf(CardType.STALLED_TRAIN),
            activeEffects = listOf(curse(CardType.LOCAL_SERVICE), curse(CardType.DETOUR)),
        )
        assertRejected(s, CardType.STALLED_TRAIN, null, PlayRejection.CURSE_CAP)
    }

    @Test
    fun `non-curse cards and a second curse are allowed under the cap`() {
        val oneCurse = state(
            hand = listOf(CardType.STALLED_TRAIN, CardType.RUSH_HOUR_DELAY, CardType.FOUND_WALLET),
            activeEffects = listOf(curse(CardType.LOCAL_SERVICE)),
        )
        assertValid(oneCurse, CardType.STALLED_TRAIN)
        val twoCurses = oneCurse.copy(activeEffects = oneCurse.activeEffects + curse(CardType.DETOUR))
        assertValid(twoCurses, CardType.RUSH_HOUR_DELAY)
        assertValid(twoCurses, CardType.FOUND_WALLET)
    }

    @Test
    fun `the cap reopens after a curse expires`() {
        val s = state(
            hand = listOf(CardType.STALLED_TRAIN),
            activeEffects = listOf(
                curse(CardType.LOCAL_SERVICE, expiry = now - 1_000L), // already past expiry
                curse(CardType.DETOUR),
            ),
        )
        val expired = CardEngine.expireEffects(s, now)
        assertValid(expired.state, CardType.STALLED_TRAIN)
    }

    // --- C11 Conductor's Override (veto) --------------------------------------------------

    @Test
    fun `C11 cancels the pending question but keeps both cooldowns`() {
        val pending = pendingQ1()
        val s = state(hand = listOf(CardType.CONDUCTORS_OVERRIDE), pendingQuestion = pending)
        val result = play(s, CardType.CONDUCTORS_OVERRIDE)

        assertNull(result.state.pendingQuestion)
        // Global 2:00 and Q1 category 5:00 cooldowns still apply (§4.2 C11).
        assertEquals(now + 2 * 60_000L, result.state.globalCooldownUntilMillis)
        assertEquals(
            now + 5 * 60_000L,
            result.state.categoryCooldownUntilMillis[QuestionCategory.RADIUS_PING],
        )
        val veto = result.events.filterIsInstance<GameEvent.AnswerVetoed>().single()
        assertEquals(pending.spec, veto.spec)
        // No compensation: no draw events.
        assertTrue(result.events.none { it is GameEvent.CardsDrawn })
    }

    @Test
    fun `C11 veto includes Service Change bonus in the retained category cooldown`() {
        val s = state(hand = listOf(CardType.CONDUCTORS_OVERRIDE), pendingQuestion = pendingQ1())
            .copy(categoryCooldownBonusMillis = mapOf(QuestionCategory.RADIUS_PING to 5 * 60_000L))
        val result = CardEngine.applyPlay(s, hider, CardType.CONDUCTORS_OVERRIDE, null, Deck.shuffled(7L), now)
        assertEquals(
            now + 10 * 60_000L,
            result.state.categoryCooldownUntilMillis[QuestionCategory.RADIUS_PING],
        )
    }

    @Test
    fun `C11 outside a response window is rejected`() {
        assertRejected(
            state(hand = listOf(CardType.CONDUCTORS_OVERRIDE)),
            CardType.CONDUCTORS_OVERRIDE, null, PlayRejection.NO_RESPONSE_WINDOW,
        )
        val closedWindow = state(hand = listOf(CardType.CONDUCTORS_OVERRIDE), pendingQuestion = pendingQ1())
            .copy(gameTimeMillis = now + 16_000L) // window ended at now + 15 s
        assertRejected(closedWindow, CardType.CONDUCTORS_OVERRIDE, null, PlayRejection.NO_RESPONSE_WINDOW)
    }

    // --- C12 Ghost Echo (decoy) ------------------------------------------------------------

    @Test
    fun `C12 stores the decoy substitution point and reveals after delivery`() {
        val decoyPoint = GeoMath.destinationPoint(hiderPos, 90.0, 1_000.0)
        val pending = pendingQ1()
        val s = state(hand = listOf(CardType.GHOST_ECHO), pendingQuestion = pending)
        val result = play(s, CardType.GHOST_ECHO, EffectParams.DecoyParams(decoyPoint))

        // The question stays pending; W3 answers from the decoy point.
        assertEquals(pending, result.state.pendingQuestion)
        assertEquals(decoyPoint, CardEngine.activeDecoyPoint(result.state))

        // Immediately after delivery, seekers learn it was a decoy.
        val delivered = CardEngine.onAnswerDelivered(result.state, pending.spec, now + 15_000L)
        val reveal = delivered.events.filterIsInstance<GameEvent.DecoyRevealed>().single()
        assertEquals(pending.spec, reveal.spec)
        assertNull(CardEngine.activeDecoyPoint(delivered.state))
        assertFalse(delivered.doubleCompensation)
    }

    @Test
    fun `C12 is rejected outside Q1 Q2 Q3 response windows`() {
        val decoy = EffectParams.DecoyParams(GeoMath.destinationPoint(hiderPos, 0.0, 500.0))
        assertRejected(
            state(hand = listOf(CardType.GHOST_ECHO), pendingQuestion = pendingQ4()),
            CardType.GHOST_ECHO, decoy, PlayRejection.DECOY_CATEGORY,
        )
        assertRejected(
            state(hand = listOf(CardType.GHOST_ECHO)),
            CardType.GHOST_ECHO, decoy, PlayRejection.NO_RESPONSE_WINDOW,
        )
    }

    @Test
    fun `C12 decoy farther than 1500 m from the true GPS position is rejected`() {
        val tooFar = EffectParams.DecoyParams(GeoMath.destinationPoint(hiderPos, 90.0, 2_000.0))
        assertRejected(
            state(hand = listOf(CardType.GHOST_ECHO), pendingQuestion = pendingQ1()),
            CardType.GHOST_ECHO, tooFar, PlayRejection.DECOY_TOO_FAR,
        )
    }

    // --- C13 Transfer Slip -------------------------------------------------------------------

    @Test
    fun `C13 in GPS mode opens the 10 minute relocation window`() {
        val result = play(state(hand = listOf(CardType.TRANSFER_SLIP)), CardType.TRANSFER_SLIP)
        assertNull(result.state.hiderZoneStationId)
        assertEquals(now + GameRules.RELOCATE_WINDOW_MILLIS, result.state.hiderRelocationDeadlineMillis)
        assertEquals(1, result.events.filterIsInstance<GameEvent.HiderRelocating>().size)
    }

    @Test
    fun `C13 in sim mode moves the zone to the chosen node`() {
        val s = state(playMode = PlayMode.SIM, hand = listOf(CardType.TRANSFER_SLIP))
        val result = play(s, CardType.TRANSFER_SLIP, EffectParams.RelocateParams("st-new"))
        assertEquals("st-new", result.state.hiderZoneStationId)
        assertNull(result.state.hiderRelocationDeadlineMillis)
    }

    @Test
    fun `C13 in sim mode requires a target node`() {
        val s = state(playMode = PlayMode.SIM, hand = listOf(CardType.TRANSFER_SLIP))
        assertRejected(s, CardType.TRANSFER_SLIP, null, PlayRejection.PARAMS_MISMATCH)
        assertRejected(
            s, CardType.TRANSFER_SLIP, EffectParams.RelocateParams(null), PlayRejection.PARAMS_MISMATCH,
        )
    }

    // --- C14 Lost & Found ----------------------------------------------------------------------

    @Test
    fun `C14 discards up to 3 and draws the same number`() {
        val deck = Deck.shuffled(7L)
        val s = state(
            hand = listOf(CardType.LOST_AND_FOUND, CardType.RUSH_HOUR_DELAY, CardType.DETOUR, CardType.DEAD_ZONE),
        )
        val params = EffectParams.LostAndFoundParams(listOf(CardType.RUSH_HOUR_DELAY, CardType.DETOUR))
        val result = play(s, CardType.LOST_AND_FOUND, params, deck)

        assertEquals(listOf(CardType.DEAD_ZONE) + deck.drawPile.take(2), result.state.hand)
        // Played card and discards reach the pile before the draw.
        assertEquals(
            listOf(CardType.LOST_AND_FOUND, CardType.RUSH_HOUR_DELAY, CardType.DETOUR),
            result.deck.discardPile,
        )
        val drawn = result.events.filterIsInstance<GameEvent.CardsDrawn>().single()
        assertEquals(2, drawn.drawn)
        assertEquals(2, drawn.kept)
    }

    @Test
    fun `C14 rejects discards not in hand or more than 3`() {
        val s = state(hand = listOf(CardType.LOST_AND_FOUND, CardType.RUSH_HOUR_DELAY))
        assertRejected(
            s, CardType.LOST_AND_FOUND,
            EffectParams.LostAndFoundParams(listOf(CardType.DETOUR)),
            PlayRejection.INVALID_DISCARDS,
        )
        val big = state(hand = listOf(CardType.LOST_AND_FOUND) + List(4) { CardType.RUSH_HOUR_DELAY })
        assertRejected(
            big, CardType.LOST_AND_FOUND,
            EffectParams.LostAndFoundParams(List(4) { CardType.RUSH_HOUR_DELAY }),
            PlayRejection.INVALID_DISCARDS,
        )
    }

    // --- C15 / C20 draws --------------------------------------------------------------------

    @Test
    fun `C15 draws 2 keep 2 and C20 draws 3 keep 3`() {
        val deck = Deck.shuffled(7L)
        val wallet = play(state(hand = listOf(CardType.FOUND_WALLET)), CardType.FOUND_WALLET, deck = deck)
        assertEquals(deck.drawPile.take(2), wallet.state.hand)
        assertEquals(48, wallet.state.deckCount)

        val golden = play(state(hand = listOf(CardType.GOLDEN_TICKET)), CardType.GOLDEN_TICKET, deck = deck)
        assertEquals(deck.drawPile.take(3), golden.state.hand)
        val drawn = golden.events.filterIsInstance<GameEvent.CardsDrawn>().single()
        assertEquals(3, drawn.drawn)
        assertEquals(3, drawn.kept)
    }

    @Test
    fun `C20 over the hand limit leaves a discard-down obligation for the engine`() {
        val hand = listOf(CardType.GOLDEN_TICKET) + List(5) { CardType.RUSH_HOUR_DELAY }
        val result = play(state(hand = hand), CardType.GOLDEN_TICKET)
        assertEquals(8, result.state.hand.size) // 5 + 3 drawn
        assertEquals(2, Hand.discardDownRequired(result.state.hand, result.state.handLimit))
    }

    // --- C16 Off-Peak Pass ---------------------------------------------------------------------

    @Test
    fun `C16 flags double compensation and is consumed by the next delivered answer`() {
        val played = play(state(hand = listOf(CardType.OFF_PEAK_PASS)), CardType.OFF_PEAK_PASS)
        assertTrue(played.state.doubleCompensationPending)
        assertEquals(CardType.OFF_PEAK_PASS, played.state.activeEffects.single().type)

        val delivered = CardEngine.onAnswerDelivered(played.state, pendingQ1().spec, now + 60_000L)
        assertTrue(delivered.doubleCompensation)
        assertFalse(delivered.state.doubleCompensationPending)
        assertTrue(delivered.state.activeEffects.isEmpty())

        val second = CardEngine.onAnswerDelivered(delivered.state, pendingQ1().spec, now + 120_000L)
        assertFalse(second.doubleCompensation) // applies to one answer only
    }

    // --- C17 Bigger Bag ---------------------------------------------------------------------------

    @Test
    fun `C17 raises the hand limit to 8 for the round`() {
        val result = play(state(hand = listOf(CardType.BIGGER_BAG)), CardType.BIGGER_BAG)
        assertEquals(GameRules.BIGGER_BAG_HAND_LIMIT, result.state.handLimit)
        assertNull(result.state.activeEffects.single().expiryGameMillis) // persists
    }

    // --- C18 Dead Zone -----------------------------------------------------------------------------

    @Test
    fun `C18 locks out questions for 6 minutes via the global cooldown`() {
        val result = play(state(hand = listOf(CardType.DEAD_ZONE)), CardType.DEAD_ZONE)
        val until = now + 6 * 60_000L
        assertEquals(until, result.state.globalCooldownUntilMillis)
        assertEquals(until, result.state.activeEffects.single().expiryGameMillis)
        assertEquals(
            until,
            result.events.filterIsInstance<GameEvent.CurseStarted>().single().expiryGameMillis,
        )
    }

    // --- C19 Service Change ---------------------------------------------------------------------------

    @Test
    fun `C19 adds 5 minutes to the category cooldown and stacks across both copies`() {
        val params = EffectParams.ServiceChangeParams(QuestionCategory.LINEUP)
        val first = play(
            state(hand = listOf(CardType.SERVICE_CHANGE, CardType.SERVICE_CHANGE)),
            CardType.SERVICE_CHANGE, params,
        )
        assertEquals(5 * 60_000L, first.state.categoryCooldownBonusMillis[QuestionCategory.LINEUP])

        val second = CardEngine.applyPlay(
            first.state, hider, CardType.SERVICE_CHANGE, params, first.deck, now + 30_000L,
        )
        assertEquals(10 * 60_000L, second.state.categoryCooldownBonusMillis[QuestionCategory.LINEUP])
        assertEquals(2, second.state.activeEffects.size) // both persist for the round
    }

    // --- Play-window validation ------------------------------------------------------------------------

    @Test
    fun `plays are rejected during Final Approach and outside the Seeking Phase`() {
        assertRejected(
            state(phase = GamePhase.FINAL_APPROACH, hand = listOf(CardType.RUSH_HOUR_DELAY)),
            CardType.RUSH_HOUR_DELAY, null, PlayRejection.FINAL_APPROACH,
        )
        assertRejected(
            state(phase = GamePhase.HIDING, hand = listOf(CardType.RUSH_HOUR_DELAY)),
            CardType.RUSH_HOUR_DELAY, null, PlayRejection.WRONG_PHASE,
        )
    }

    @Test
    fun `plays require the card in hand and the hider role`() {
        assertRejected(state(hand = emptyList()), CardType.RUSH_HOUR_DELAY, null, PlayRejection.CARD_NOT_IN_HAND)
        assertRejected(
            state(hand = listOf(CardType.RUSH_HOUR_DELAY)),
            CardType.RUSH_HOUR_DELAY, null, PlayRejection.NOT_HIDER, playerId = seeker1,
        )
    }

    @Test
    fun `unexpected params are rejected for cards played without choices`() {
        assertRejected(
            state(hand = listOf(CardType.RUSH_HOUR_DELAY)),
            CardType.RUSH_HOUR_DELAY,
            EffectParams.DetourParams("R1"),
            PlayRejection.PARAMS_MISMATCH,
        )
    }

    // --- expireEffects ------------------------------------------------------------------------------------

    @Test
    fun `expireEffects removes elapsed timed effects and emits CurseEnded`() {
        val s = state(
            activeEffects = listOf(
                curse(CardType.STALLED_TRAIN, expiry = now - 1L), // elapsed
                curse(CardType.DETOUR, expiry = now + 60_000L), // still running
                ActiveEffect(CardType.BIGGER_BAG, startGameMillis = 0L, expiryGameMillis = null), // untimed
            ),
        )
        val result = CardEngine.expireEffects(s, now)
        assertEquals(
            listOf(CardType.DETOUR, CardType.BIGGER_BAG),
            result.state.activeEffects.map { it.type },
        )
        val ended = result.events.filterIsInstance<GameEvent.CurseEnded>().single()
        assertEquals(CardType.STALLED_TRAIN, ended.type)
        assertEquals(now, ended.gameTimeMillis)
    }

    @Test
    fun `expiry exactly at now expires the effect`() {
        val s = state(activeEffects = listOf(curse(CardType.STALLED_TRAIN, expiry = now)))
        assertTrue(CardEngine.expireEffects(s, now).state.activeEffects.isEmpty())
        assertNotNull(CardEngine.expireEffects(s, now).events.singleOrNull())
    }
}
