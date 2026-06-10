package io.terminus.core.cards

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeckTest {

    // --- Composition (test plan item 5: deck composition 50 and per-type counts) ------

    @Test
    fun `fresh deck holds exactly 50 cards`() {
        assertEquals(50, Deck.TOTAL_CARDS)
        assertEquals(50, Deck.shuffled(1L).drawPile.size)
        assertEquals(0, Deck.shuffled(1L).discardPile.size)
    }

    @Test
    fun `fresh deck holds the per-type counts from the card catalog`() {
        val byType = Deck.shuffled(99L).drawPile.groupingBy { it }.eachCount()
        for (type in CardType.entries) {
            assertEquals(type.count, byType[type] ?: 0, "count of ${type.displayName}")
        }
        // Spot-check the §4.2 table directly.
        assertEquals(7, byType[CardType.RUSH_HOUR_DELAY])
        assertEquals(4, byType[CardType.EXPRESS_SKIP])
        assertEquals(2, byType[CardType.NIGHT_OWL_SERVICE])
        assertEquals(3, byType[CardType.CONDUCTORS_OVERRIDE])
        assertEquals(1, byType[CardType.BIGGER_BAG])
        assertEquals(1, byType[CardType.GOLDEN_TICKET])
    }

    @Test
    fun `kind subtotals match the design totals`() {
        val cards = Deck.shuffled(5L).drawPile
        assertEquals(13, cards.count { it.kind == CardKind.TIME_BONUS })
        assertEquals(16, cards.count { it.kind == CardKind.CURSE })
        assertEquals(21, cards.count { it.kind == CardKind.UTILITY })
    }

    // --- Seeded determinism ------------------------------------------------------------

    @Test
    fun `same seed produces the same shuffle, different seeds differ`() {
        assertEquals(Deck.shuffled(42L), Deck.shuffled(42L))
        assertNotEquals(Deck.shuffled(42L).drawPile, Deck.shuffled(43L).drawPile)
    }

    @Test
    fun `initial shuffle is Random(seed) over the catalog order`() {
        val expected = CardType.entries.flatMap { type -> List(type.count) { type } }
            .shuffled(Random(42L))
        assertEquals(expected, Deck.shuffled(42L).drawPile)
    }

    @Test
    fun `draw takes from the top and is deterministic`() {
        val deck = Deck.shuffled(7L)
        val result = deck.draw(5)
        assertEquals(deck.drawPile.take(5), result.drawn)
        assertEquals(deck.drawPile.drop(5), result.deck.drawPile)
        assertEquals(result, deck.draw(5)) // pure: same call, same result
    }

    // --- Reshuffle-on-empty determinism ------------------------------------------------

    @Test
    fun `emptied deck reshuffles the discard pile with the derived seed`() {
        val seed = 42L
        var deck = Deck.shuffled(seed)
        val all = deck.draw(50)
        deck = all.deck.discard(all.drawn)
        assertEquals(0, deck.drawPileSize)
        assertEquals(50, deck.discardPileSize)

        val expectedOrder = all.drawn.shuffled(Random(seed + 1))
        val redraw = deck.draw(10)
        assertEquals(expectedOrder.take(10), redraw.drawn)
        assertEquals(1, redraw.deck.reshuffleCount)
        assertEquals(0, redraw.deck.discardPileSize)
        assertEquals(40, redraw.deck.drawPileSize)
    }

    @Test
    fun `draw spanning the reshuffle boundary uses the same derived-seed stream`() {
        val seed = 11L
        var deck = Deck.shuffled(seed)
        val first = deck.draw(48)
        deck = first.deck.discard(first.drawn.take(10))

        val spanning = deck.draw(6) // 2 left in the pile, 4 from the reshuffled discards
        assertEquals(6, spanning.drawn.size)
        assertEquals(first.deck.drawPile, spanning.drawn.take(2))
        assertEquals(
            first.drawn.take(10).shuffled(Random(seed + 1)).take(4),
            spanning.drawn.drop(2),
        )
        assertEquals(1, spanning.deck.reshuffleCount)
    }

    @Test
    fun `second reshuffle derives a different seed than the first`() {
        val seed = 3L
        var deck = Deck.shuffled(seed)
        // First cycle: draw everything, discard everything, trigger reshuffle 1.
        var result = deck.draw(50)
        deck = result.deck.discard(result.drawn)
        result = deck.draw(50)
        assertEquals(1, result.deck.reshuffleCount)
        // Second cycle: trigger reshuffle 2 and check the order matches Random(seed + 2).
        deck = result.deck.discard(result.drawn)
        val second = deck.draw(50)
        assertEquals(2, second.deck.reshuffleCount)
        assertEquals(result.drawn.shuffled(Random(seed + 2)), second.drawn)
    }

    @Test
    fun `two decks with the same seed replay an identical draw and reshuffle history`() {
        fun history(seed: Long): List<CardType> {
            var deck = Deck.shuffled(seed)
            val out = mutableListOf<CardType>()
            repeat(40) {
                val r = deck.draw(3)
                out += r.drawn
                deck = r.deck.discard(r.drawn) // keep the cycle going through reshuffles
            }
            return out
        }
        assertEquals(history(123L), history(123L))
    }

    @Test
    fun `draw returns fewer cards when deck and discard are both exhausted`() {
        val deck = Deck(seed = 1L, drawPile = listOf(CardType.BIGGER_BAG))
        val result = deck.draw(3)
        assertEquals(listOf(CardType.BIGGER_BAG), result.drawn)
        assertTrue(result.deck.drawPile.isEmpty())
    }
}
