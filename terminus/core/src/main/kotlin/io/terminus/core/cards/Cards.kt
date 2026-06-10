package io.terminus.core.cards

import io.terminus.core.geo.LatLng
import io.terminus.core.questions.QuestionCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Card kind (GAME_DESIGN.md §4.2 section headings). */
@Serializable
enum class CardKind {
    TIME_BONUS,
    CURSE,
    UTILITY,
}

/** When a card may be played (GAME_DESIGN.md §4.1). */
@Serializable
enum class PlayWindow {
    /** Any time during the Seeking Phase (not during Final Approach or another card's resolution). */
    SEEKING_PHASE,

    /** Only inside a question's 20-second response window (C11 Conductor's Override, C12 Ghost Echo). */
    RESPONSE_WINDOW,
}

/**
 * The 20 card designs C1–C20 with deck metadata (GAME_DESIGN.md §4.2;
 * ARCHITECTURE.md §1.1 `cards`). [count]s sum to the single 50-card deck:
 * 13 time-bonus + 16 curse + 21 utility. Deck, hand, and effect logic are W4's.
 */
@Serializable
enum class CardType(
    val displayName: String,
    val count: Int,
    val kind: CardKind,
    val playWindow: PlayWindow,
) {
    /** C1: +3:00 to hider's final score. */
    RUSH_HOUR_DELAY("Rush Hour Delay", 7, CardKind.TIME_BONUS, PlayWindow.SEEKING_PHASE),

    /** C2: +5:00 to hider's final score. */
    EXPRESS_SKIP("Express Skip", 4, CardKind.TIME_BONUS, PlayWindow.SEEKING_PHASE),

    /** C3: +10:00 to hider's final score. */
    NIGHT_OWL_SERVICE("Night Owl Service", 2, CardKind.TIME_BONUS, PlayWindow.SEEKING_PHASE),

    /** C4: for 4:00, seekers may not move. */
    STALLED_TRAIN("Curse of the Stalled Train", 3, CardKind.CURSE, PlayWindow.SEEKING_PHASE),

    /** C5: for 10:00, seekers must dwell at every station they pass. */
    LOCAL_SERVICE("Curse of the Local Service", 2, CardKind.CURSE, PlayWindow.SEEKING_PHASE),

    /** C6: Radius Ping (Q1) disabled for 8:00. */
    TUNNEL_VISION("Curse of Tunnel Vision", 2, CardKind.CURSE, PlayWindow.SEEKING_PHASE),

    /** C7: the next answered question's answer is withheld for 5:00 after computation. */
    SCRAMBLED_SIGNAL("Curse of the Scrambled Signal", 2, CardKind.CURSE, PlayWindow.SEEKING_PHASE),

    /** C8: each seeker must return to their previous station before the team may ask again; untimed. */
    U_TURN("Curse of the U-Turn", 2, CardKind.CURSE, PlayWindow.SEEKING_PHASE),

    /** C9: for 3:00, each seeker must stay within 100 m of their current nearest station. */
    TICKET_INSPECTION("Curse of the Ticket Inspection", 3, CardKind.CURSE, PlayWindow.SEEKING_PHASE),

    /** C10: hider picks one route line; seekers may not use it for 12:00. */
    DETOUR("Curse of the Detour", 2, CardKind.CURSE, PlayWindow.SEEKING_PHASE),

    /** C11: veto — the question is cancelled; cooldowns still apply. */
    CONDUCTORS_OVERRIDE("Conductor's Override", 3, CardKind.UTILITY, PlayWindow.RESPONSE_WINDOW),

    /** C12: decoy — answer a Q1/Q2/Q3 from a point within 1.5 km of the true position. */
    GHOST_ECHO("Ghost Echo", 2, CardKind.UTILITY, PlayWindow.RESPONSE_WINDOW),

    /** C13: the hider may leave the hiding zone and re-hide. */
    TRANSFER_SLIP("Transfer Slip", 2, CardKind.UTILITY, PlayWindow.SEEKING_PHASE),

    /** C14: discard up to 3 cards, draw the same number. */
    LOST_AND_FOUND("Lost & Found", 3, CardKind.UTILITY, PlayWindow.SEEKING_PHASE),

    /** C15: draw 2, keep 2. */
    FOUND_WALLET("Found Wallet", 3, CardKind.UTILITY, PlayWindow.SEEKING_PHASE),

    /** C16: the next answered question grants double compensation. */
    OFF_PEAK_PASS("Off-Peak Pass", 2, CardKind.UTILITY, PlayWindow.SEEKING_PHASE),

    /** C17: hand limit becomes 8 for the rest of the round. */
    BIGGER_BAG("Bigger Bag", 1, CardKind.UTILITY, PlayWindow.SEEKING_PHASE),

    /** C18: no questions may be asked for 6:00. */
    DEAD_ZONE("Dead Zone", 2, CardKind.UTILITY, PlayWindow.SEEKING_PHASE),

    /** C19: one chosen category's cooldown +5:00 for the rest of the round; stacks. */
    SERVICE_CHANGE("Service Change", 2, CardKind.UTILITY, PlayWindow.SEEKING_PHASE),

    /** C20: draw 3, keep 3. */
    GOLDEN_TICKET("Golden Ticket", 1, CardKind.UTILITY, PlayWindow.SEEKING_PHASE),
}

/**
 * Parameters the hider supplies when playing a card that needs a choice
 * (GAME_DESIGN.md §4.2; ARCHITECTURE.md §1.1 `cards`). Cards without choices
 * are played with null params.
 */
@Serializable
sealed class EffectParams {
    /** C10 Detour: the banned route line. */
    @Serializable
    @SerialName("detour")
    data class DetourParams(
        val routeId: String,
    ) : EffectParams()

    /** C12 Ghost Echo: the decoy point, within 1.5 km of the true position. */
    @Serializable
    @SerialName("decoy")
    data class DecoyParams(
        val point: LatLng,
    ) : EffectParams()

    /** C19 Service Change: the question category whose cooldown is increased by +5:00. */
    @Serializable
    @SerialName("serviceChange")
    data class ServiceChangeParams(
        val category: QuestionCategory,
    ) : EffectParams()

    /**
     * C13 Transfer Slip: relocation target.
     *
     * @property targetStationId sim mode: destination node (≤5 edges away); null in GPS mode,
     *   where the hider physically travels during the 10:00 window.
     */
    @Serializable
    @SerialName("relocate")
    data class RelocateParams(
        val targetStationId: String? = null,
    ) : EffectParams()

    /** C14 Lost & Found: the up-to-3 cards being discarded for redraw. */
    @Serializable
    @SerialName("lostAndFound")
    data class LostAndFoundParams(
        val discards: List<CardType>,
    ) : EffectParams()
}

/**
 * A card effect currently in force (ARCHITECTURE.md §1.1 `cards`; GAME_DESIGN.md §4).
 * Enforcement (`EffectEnforcer`, `CardEngine`) is W4's.
 *
 * @property type the card that created this effect.
 * @property startGameMillis game time when the card was played (curse timers may restart
 *   once after a human violation, GAME_DESIGN.md §4.1).
 * @property expiryGameMillis game time when the effect ends, or null for untimed effects
 *   (C8 U-Turn self-clears; C17 Bigger Bag and C19 Service Change persist for the round;
 *   C7 Scrambled Signal and C16 Off-Peak Pass clear on the next answered question).
 * @property params the parameters the card was played with, if any.
 * @property restartsUsed how many violation-triggered timer restarts have occurred (max 1).
 */
@Serializable
data class ActiveEffect(
    val type: CardType,
    val startGameMillis: Long,
    val expiryGameMillis: Long? = null,
    val params: EffectParams? = null,
    val restartsUsed: Int = 0,
)
