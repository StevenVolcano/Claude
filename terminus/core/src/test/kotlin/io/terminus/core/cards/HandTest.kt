package io.terminus.core.cards

import io.terminus.core.game.GameRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HandTest {

    @Test
    fun `add within the limit requires no discards`() {
        val result = Hand.add(
            hand = listOf(CardType.RUSH_HOUR_DELAY, CardType.DETOUR),
            cards = listOf(CardType.GHOST_ECHO),
            limit = GameRules.HAND_LIMIT,
        )
        assertEquals(3, result.hand.size)
        assertEquals(0, result.mustDiscard)
    }

    @Test
    fun `add past the limit reports the discard-down count`() {
        val hand = List(5) { CardType.RUSH_HOUR_DELAY }
        val result = Hand.add(hand, listOf(CardType.EXPRESS_SKIP, CardType.DEAD_ZONE, CardType.DETOUR), 6)
        assertEquals(8, result.hand.size) // cards are added; the engine collects the choice
        assertEquals(2, result.mustDiscard)
        assertEquals(2, Hand.discardDownRequired(result.hand, 6))
    }

    @Test
    fun `bigger bag limit of 8 changes the discard-down count`() {
        val hand = List(7) { CardType.RUSH_HOUR_DELAY }
        assertEquals(1, Hand.discardDownRequired(hand, GameRules.HAND_LIMIT))
        assertEquals(0, Hand.discardDownRequired(hand, GameRules.BIGGER_BAG_HAND_LIMIT))
    }

    @Test
    fun `discard removes one occurrence per requested card`() {
        val hand = listOf(CardType.RUSH_HOUR_DELAY, CardType.RUSH_HOUR_DELAY, CardType.DETOUR)
        assertEquals(
            listOf(CardType.RUSH_HOUR_DELAY, CardType.DETOUR),
            Hand.discard(hand, listOf(CardType.RUSH_HOUR_DELAY)),
        )
        assertEquals(
            listOf(CardType.DETOUR),
            Hand.discard(hand, listOf(CardType.RUSH_HOUR_DELAY, CardType.RUSH_HOUR_DELAY)),
        )
    }

    @Test
    fun `discard rejects cards not present often enough`() {
        val hand = listOf(CardType.RUSH_HOUR_DELAY, CardType.DETOUR)
        assertNull(Hand.discard(hand, listOf(CardType.GHOST_ECHO)))
        assertNull(Hand.discard(hand, listOf(CardType.RUSH_HOUR_DELAY, CardType.RUSH_HOUR_DELAY)))
    }

    @Test
    fun `remove takes a single card or rejects`() {
        assertEquals(
            listOf(CardType.DETOUR),
            Hand.remove(listOf(CardType.RUSH_HOUR_DELAY, CardType.DETOUR), CardType.RUSH_HOUR_DELAY),
        )
        assertNull(Hand.remove(emptyList(), CardType.DETOUR))
    }
}
