package io.terminus.core.cards

/**
 * Pure operations over the hider's hand (GAME_DESIGN.md §4.1). The hand itself is
 * `GameState.hand: List<CardType>` and the limit is `GameState.handLimit` (6, or 8
 * after Bigger Bag); these helpers never touch `GameState` directly.
 *
 * Hands are multisets: duplicates are meaningful (the deck holds up to 7 copies of
 * one design), so removal helpers remove one occurrence per requested card.
 */
object Hand {

    /**
     * Adds [cards] to [hand]. The result may exceed [limit]; [HandAdd.mustDiscard]
     * tells the engine how many cards the hider must immediately discard down
     * (GAME_DESIGN.md §4.1: "If a draw would exceed the limit, the hider immediately
     * discards down to the limit"). Which cards to discard is the player's/AI's
     * choice — apply it with [discard].
     */
    fun add(hand: List<CardType>, cards: List<CardType>, limit: Int): HandAdd {
        val newHand = hand + cards
        return HandAdd(hand = newHand, mustDiscard = discardDownRequired(newHand, limit))
    }

    /** How many cards must be discarded for [hand] to satisfy [limit] (0 when compliant). */
    fun discardDownRequired(hand: List<CardType>, limit: Int): Int =
        maxOf(0, hand.size - limit)

    /**
     * Removes one occurrence of each card in [discards] from [hand] (multiset
     * removal). Returns null if any requested card is not present often enough —
     * the engine must reject the discard choice.
     */
    fun discard(hand: List<CardType>, discards: List<CardType>): List<CardType>? {
        val remaining = hand.toMutableList()
        for (card in discards) {
            if (!remaining.remove(card)) return null
        }
        return remaining
    }

    /** Removes one occurrence of [card], or returns null if it is not in [hand]. */
    fun remove(hand: List<CardType>, card: CardType): List<CardType>? =
        discard(hand, listOf(card))
}

/**
 * Result of [Hand.add].
 *
 * @property hand the hand including the added cards (possibly over the limit).
 * @property mustDiscard how many cards the hider must now discard down (0 = none).
 */
data class HandAdd(
    val hand: List<CardType>,
    val mustDiscard: Int,
)
