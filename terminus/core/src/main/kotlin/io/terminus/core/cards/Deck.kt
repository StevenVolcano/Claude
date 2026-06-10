package io.terminus.core.cards

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * The single 50-card deck plus its discard pile (GAME_DESIGN.md §4.1;
 * ARCHITECTURE.md §1.1 `cards`).
 *
 * ### Determinism design (pre-shuffle + derived seeds)
 * `kotlin.random.Random` is not snapshotable, so [Deck] never holds a live RNG.
 * Instead it is a **pure, fully serializable data class**:
 *
 * - The initial draw pile is the 50 cards from [CardType.count] (in `CardType`
 *   declaration order) shuffled once with `Random(seed)`.
 * - The *k*-th reshuffle-on-empty (k = 1, 2, …) shuffles the discard pile with the
 *   derived seed `Random(seed + k)` and increments [reshuffleCount].
 *
 * Every draw is therefore a deterministic function of `(seed, draws so far,
 * discards so far)`, and a deck serialized at any point resumes identically —
 * no RNG stream state needs to survive serialization.
 *
 * @property seed the round seed driving the initial shuffle and all derived reshuffles.
 * @property drawPile cards remaining, index 0 is the top of the deck.
 * @property discardPile face-up discards, in discard order.
 * @property reshuffleCount how many reshuffle-on-empty events have occurred.
 */
@Serializable
data class Deck(
    val seed: Long,
    val drawPile: List<CardType>,
    val discardPile: List<CardType> = emptyList(),
    val reshuffleCount: Int = 0,
) {
    /** Cards remaining in the draw pile (mirrors `GameState.deckCount`). */
    val drawPileSize: Int get() = drawPile.size

    /** Cards in the discard pile (mirrors `GameState.discardCount`). */
    val discardPileSize: Int get() = discardPile.size

    /**
     * Draws up to [n] cards from the top, reshuffling the discard pile into the draw
     * pile with the derived seed `seed + reshuffleCount` whenever the draw pile
     * empties mid-draw (GAME_DESIGN.md §4.1).
     *
     * If both piles run dry (cannot happen with the full 50-card deck unless more
     * than 50 cards are simultaneously out of the deck), fewer than [n] cards are
     * returned.
     */
    fun draw(n: Int): DrawResult {
        require(n >= 0) { "cannot draw a negative number of cards: $n" }
        var pile = drawPile
        var discards = discardPile
        var reshuffles = reshuffleCount
        val drawn = ArrayList<CardType>(n)
        while (drawn.size < n) {
            if (pile.isEmpty()) {
                if (discards.isEmpty()) break
                reshuffles += 1
                pile = discards.shuffled(Random(seed + reshuffles))
                discards = emptyList()
            }
            val take = minOf(n - drawn.size, pile.size)
            drawn += pile.subList(0, take)
            pile = pile.subList(take, pile.size).toList()
        }
        return DrawResult(drawn, Deck(seed, pile, discards, reshuffles))
    }

    /** Returns the deck with [cards] appended to the discard pile. */
    fun discard(cards: List<CardType>): Deck =
        if (cards.isEmpty()) this else copy(discardPile = discardPile + cards)

    /** Returns the deck with a single [card] appended to the discard pile. */
    fun discard(card: CardType): Deck = discard(listOf(card))

    companion object {
        /** Total cards in a fresh deck: the sum of all [CardType.count]s (= 50). */
        val TOTAL_CARDS: Int = CardType.entries.sumOf { it.count }

        /**
         * Builds the full 50-card deck — [CardType.count] copies of each design, in
         * `CardType` declaration order — and shuffles it with `Random(seed)`
         * (GAME_DESIGN.md §4.1: "shuffled with the round's RNG seed").
         */
        fun shuffled(seed: Long): Deck {
            val cards = CardType.entries.flatMap { type -> List(type.count) { type } }
            return Deck(seed = seed, drawPile = cards.shuffled(Random(seed)))
        }
    }
}

/**
 * Result of [Deck.draw]: the cards drawn (top first) and the deck afterwards.
 */
data class DrawResult(
    val drawn: List<CardType>,
    val deck: Deck,
)
