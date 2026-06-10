package io.terminus.core.cards

import io.terminus.core.game.GameEvent
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameRules
import io.terminus.core.game.GameState
import io.terminus.core.game.PlayMode
import io.terminus.core.game.PlayerId
import io.terminus.core.game.PlayerPosition
import io.terminus.core.game.Role
import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.questions.QuestionCategory
import io.terminus.core.questions.QuestionRules
import io.terminus.core.questions.QuestionSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * C8 U-Turn per-seeker return targets, snapshotted from `GameState.visitHistory` at
 * play time (GAME_DESIGN.md §4.2 C8). A seeker is removed from the map when they
 * return (within 100 m GPS / at the node in sim); the effect self-clears when the
 * map empties ([CardEngine.markUTurnReturned]).
 *
 * Declared here (same package + module as the sealed [EffectParams]) as a strictly
 * additive Phase 1 member: Phase 0 froze no carrier for the snapshotted targets.
 * Keys are `PlayerId.value` strings so the params type stays serializable as a plain
 * string-keyed map.
 */
@Serializable
@SerialName("uTurn")
data class UTurnParams(
    val returnStationIdBySeeker: Map<String, String>,
) : EffectParams()

/** Why a card play was rejected by [CardEngine.validatePlay] (GAME_DESIGN.md §4.1). */
enum class PlayRejection {
    /** Cards are hider-only; the player is not this round's hider. */
    NOT_HIDER,

    /** The card is not in the hider's hand. */
    CARD_NOT_IN_HAND,

    /** Another card/draw is still resolving (an unresolved draw-D-keep-K). */
    RESOLUTION_IN_PROGRESS,

    /** No new card plays during Final Approach (active effects persist). */
    FINAL_APPROACH,

    /** Cards are playable only during the Seeking Phase. */
    WRONG_PHASE,

    /** C11/C12 are playable only inside a question's 20 s response window. */
    NO_RESPONSE_WINDOW,

    /** C12 Ghost Echo applies only to Q1/Q2/Q3 (GAME_DESIGN.md §4.2 C12). */
    DECOY_CATEGORY,

    /** At most 2 curses may be active simultaneously. */
    CURSE_CAP,

    /** The supplied [EffectParams] is missing, of the wrong type, or invalid for the card. */
    PARAMS_MISMATCH,

    /** C12 decoy point is farther than 1.5 km from the hider's true position. */
    DECOY_TOO_FAR,

    /** C14 discard choice not satisfiable from the hand (or more than 3 cards). */
    INVALID_DISCARDS,
}

/** Result of [CardEngine.validatePlay]. */
sealed class ValidationResult {
    /** The play is legal. */
    data object Valid : ValidationResult()

    /** The play is illegal for [rejection]; [detail] is a human-readable explanation. */
    data class Invalid(
        val rejection: PlayRejection,
        val detail: String,
    ) : ValidationResult()
}

/**
 * Result of [CardEngine.applyPlay]: the updated immutable state, the updated deck,
 * and the events the play produced.
 *
 * The engine (W6) appends [events] to `GameState.eventLog` itself — [state]'s
 * event log is returned unchanged so events are never double-logged. [state]'s
 * `deckCount`/`discardCount` are already synced to [deck]. After draws
 * ([CardType.FOUND_WALLET], [CardType.GOLDEN_TICKET]) the hand may exceed the
 * limit; the engine must then collect a discard-down choice
 * (`Hand.discardDownRequired`).
 */
data class ApplyResult(
    val state: GameState,
    val deck: Deck,
    val events: List<GameEvent>,
)

/** Result of [CardEngine.expireEffects]. */
data class ExpiryResult(
    val state: GameState,
    val events: List<GameEvent>,
)

/**
 * Result of [CardEngine.onAnswerComputed]: Scrambled Signal (C7) consumption.
 *
 * @property delayMillis 0 when no delay; 5:00 when C7 was active (the engine builds
 *   the `DelayedAnswer` with `deliverAtGameMillis = now + delayMillis`; cooldowns
 *   start at delivery, GAME_DESIGN.md §4.2 C7).
 */
data class AnswerComputation(
    val state: GameState,
    val delayMillis: Long,
    val events: List<GameEvent>,
)

/**
 * Result of [CardEngine.onAnswerDelivered]: consumption of answer-linked effects.
 *
 * @property doubleCompensation true when Off-Peak Pass (C16) applied to this answer —
 *   the engine doubles the draw-D-keep-K for it (GAME_DESIGN.md §4.2 C16).
 */
data class AnswerDeliveryEffects(
    val state: GameState,
    val events: List<GameEvent>,
    val doubleCompensation: Boolean,
)

/**
 * Pure validation and application of card plays C1–C20 (GAME_DESIGN.md §4;
 * ARCHITECTURE.md §1.1 `cards`). Never mutates [GameState]; every function returns
 * updated copies plus the [GameEvent]s to log. The game engine (W6) is the caller.
 */
object CardEngine {

    /** Time-bonus minutes per card (GAME_DESIGN.md §4.2 C1–C3); null for non-bonus cards. */
    fun bonusMinutes(type: CardType): Double? = when (type) {
        CardType.RUSH_HOUR_DELAY -> 3.0
        CardType.EXPRESS_SKIP -> 5.0
        CardType.NIGHT_OWL_SERVICE -> 10.0
        else -> null
    }

    /**
     * Timed-effect duration in game millis (GAME_DESIGN.md §4.2), or null for
     * instant cards and untimed effects (C7, C8, C16 clear on question events;
     * C17, C19 persist for the round).
     */
    fun effectDurationMillis(type: CardType): Long? = when (type) {
        CardType.STALLED_TRAIN -> 4 * 60_000L
        CardType.LOCAL_SERVICE -> 10 * 60_000L
        CardType.TUNNEL_VISION -> 8 * 60_000L
        CardType.TICKET_INSPECTION -> 3 * 60_000L
        CardType.DETOUR -> 12 * 60_000L
        CardType.DEAD_ZONE -> 6 * 60_000L
        else -> null
    }

    /** Number of curses currently in force (cap = [GameRules.MAX_ACTIVE_CURSES]). */
    fun activeCurseCount(state: GameState): Int =
        state.activeEffects.count { it.type.kind == CardKind.CURSE }

    /** The active Ghost Echo decoy point for answer substitution (W3 reads this), or null. */
    fun activeDecoyPoint(state: GameState): LatLng? =
        state.activeEffects.firstOrNull { it.type == CardType.GHOST_ECHO }
            ?.let { (it.params as? EffectParams.DecoyParams)?.point }

    /** Remaining C8 U-Turn return targets per seeker; empty when no U-Turn is active. */
    fun uTurnReturnTargets(state: GameState): Map<PlayerId, String> =
        state.activeEffects.firstOrNull { it.type == CardType.U_TURN }
            ?.let { (it.params as? UTurnParams)?.returnStationIdBySeeker }
            ?.mapKeys { (key, _) -> PlayerId(key) }
            ?: emptyMap()

    // ---------------------------------------------------------------------------------
    // validatePlay
    // ---------------------------------------------------------------------------------

    /**
     * Checks the legality of playing [card] with [params] in [state]
     * (GAME_DESIGN.md §4.1). "Now" is `state.gameTimeMillis` (response-window checks).
     *
     * The C12 decoy-distance rule is checked here only when the hider's true position
     * is a raw [PlayerPosition.GpsPosition]; node/edge positions require the transit
     * network to resolve coordinates, so the engine (which holds the network) must
     * additionally verify the 1.5 km rule — and the sim-mode C13 5-edge rule — itself.
     */
    fun validatePlay(
        state: GameState,
        playerId: PlayerId,
        card: CardType,
        params: EffectParams?,
    ): ValidationResult {
        if (state.roles[playerId] != Role.HIDER) {
            return invalid(PlayRejection.NOT_HIDER, "cards are hider-only; $playerId is not the hider")
        }
        if (card !in state.hand) {
            return invalid(PlayRejection.CARD_NOT_IN_HAND, "${card.displayName} is not in hand")
        }
        if (state.pendingKeep != null) {
            return invalid(PlayRejection.RESOLUTION_IN_PROGRESS, "a draw-keep is still resolving")
        }
        if (state.phase == GamePhase.FINAL_APPROACH) {
            return invalid(PlayRejection.FINAL_APPROACH, "no new card plays during Final Approach")
        }
        if (state.phase != GamePhase.SEEKING) {
            return invalid(PlayRejection.WRONG_PHASE, "cards are playable only in the Seeking Phase")
        }
        if (card.playWindow == PlayWindow.RESPONSE_WINDOW) {
            val pending = state.pendingQuestion
            if (pending == null || state.gameTimeMillis >= pending.responseWindowEndsGameMillis) {
                return invalid(
                    PlayRejection.NO_RESPONSE_WINDOW,
                    "${card.displayName} is playable only inside a question's response window",
                )
            }
            if (card == CardType.GHOST_ECHO && pending.spec.category !in DECOY_CATEGORIES) {
                return invalid(
                    PlayRejection.DECOY_CATEGORY,
                    "Ghost Echo applies only to Q1/Q2/Q3, not ${pending.spec.category}",
                )
            }
        }
        if (card.kind == CardKind.CURSE && activeCurseCount(state) >= GameRules.MAX_ACTIVE_CURSES) {
            return invalid(PlayRejection.CURSE_CAP, "at most ${GameRules.MAX_ACTIVE_CURSES} curses may be active")
        }
        return validateParams(state, playerId, card, params)
    }

    private val DECOY_CATEGORIES = setOf(
        QuestionCategory.RADIUS_PING,
        QuestionCategory.COMPASS_CALL,
        QuestionCategory.THERMOMETER,
    )

    private fun validateParams(
        state: GameState,
        playerId: PlayerId,
        card: CardType,
        params: EffectParams?,
    ): ValidationResult = when (card) {
        CardType.DETOUR ->
            if (params is EffectParams.DetourParams) ValidationResult.Valid
            else paramsMismatch(card, "DetourParams(routeId)")

        CardType.GHOST_ECHO -> when {
            params !is EffectParams.DecoyParams -> paramsMismatch(card, "DecoyParams(point)")
            else -> {
                val truePos = (state.positions[playerId] as? PlayerPosition.GpsPosition)?.latLng
                if (truePos != null &&
                    GeoMath.haversineMeters(truePos, params.point) > GameRules.DECOY_MAX_DISTANCE_METERS
                ) {
                    invalid(
                        PlayRejection.DECOY_TOO_FAR,
                        "decoy point must be within ${GameRules.DECOY_MAX_DISTANCE_METERS.toInt()} m " +
                            "of the true position",
                    )
                } else {
                    ValidationResult.Valid
                }
            }
        }

        CardType.SERVICE_CHANGE ->
            if (params is EffectParams.ServiceChangeParams) ValidationResult.Valid
            else paramsMismatch(card, "ServiceChangeParams(category)")

        CardType.TRANSFER_SLIP ->
            if (state.config.playMode == PlayMode.SIM) {
                if (params is EffectParams.RelocateParams && params.targetStationId != null) {
                    ValidationResult.Valid
                } else {
                    paramsMismatch(card, "RelocateParams(targetStationId) in sim mode")
                }
            } else {
                // GPS mode: the hider physically travels; no target is required.
                if (params == null || params is EffectParams.RelocateParams) ValidationResult.Valid
                else paramsMismatch(card, "null or RelocateParams in GPS mode")
            }

        CardType.LOST_AND_FOUND -> when {
            params !is EffectParams.LostAndFoundParams -> paramsMismatch(card, "LostAndFoundParams(discards)")
            params.discards.size > 3 ->
                invalid(PlayRejection.INVALID_DISCARDS, "Lost & Found discards at most 3 cards")
            else -> {
                val withoutPlayed = Hand.remove(state.hand, card)
                if (withoutPlayed == null || Hand.discard(withoutPlayed, params.discards) == null) {
                    invalid(PlayRejection.INVALID_DISCARDS, "discard choice is not satisfiable from the hand")
                } else {
                    ValidationResult.Valid
                }
            }
        }

        else ->
            if (params == null) ValidationResult.Valid
            else paramsMismatch(card, "null")
    }

    private fun invalid(rejection: PlayRejection, detail: String) =
        ValidationResult.Invalid(rejection, detail)

    private fun paramsMismatch(card: CardType, expected: String) =
        invalid(PlayRejection.PARAMS_MISMATCH, "${card.displayName} requires params: $expected")

    // ---------------------------------------------------------------------------------
    // applyPlay
    // ---------------------------------------------------------------------------------

    /**
     * Applies a **validated** play of [card] (GAME_DESIGN.md §4.2 C1–C20), returning
     * the updated state, the updated deck, and the events to log. Throws
     * [IllegalArgumentException] if [validatePlay] would reject the play — the
     * engine must validate first.
     *
     * Every play removes the card from the hand, sends it to the discard pile,
     * increments `cardsPlayed`, and emits [GameEvent.CardPlayed] (plays are
     * announced to seekers, §4.2). Curses (and the timed Dead Zone) additionally
     * emit [GameEvent.CurseStarted] with their countdown expiry.
     */
    fun applyPlay(
        state: GameState,
        playerId: PlayerId,
        card: CardType,
        params: EffectParams?,
        deck: Deck,
        nowGameMillis: Long,
    ): ApplyResult {
        val validation = validatePlay(state, playerId, card, params)
        require(validation is ValidationResult.Valid) {
            "illegal play of ${card.displayName}: $validation"
        }

        val events = mutableListOf<GameEvent>(
            GameEvent.CardPlayed(playerId, card, params, nowGameMillis),
        )
        var hand = checkNotNull(Hand.remove(state.hand, card))
        var newDeck = deck.discard(card)
        var s = state.copy(cardsPlayed = state.cardsPlayed + 1)

        fun addEffect(expiryGameMillis: Long?, effectParams: EffectParams? = params) {
            s = s.copy(
                activeEffects = s.activeEffects + ActiveEffect(
                    type = card,
                    startGameMillis = nowGameMillis,
                    expiryGameMillis = expiryGameMillis,
                    params = effectParams,
                ),
            )
            if (card.kind == CardKind.CURSE || expiryGameMillis != null) {
                events += GameEvent.CurseStarted(card, expiryGameMillis, nowGameMillis)
            }
        }

        when (card) {
            // --- Time bonuses (C1–C3): minutes added immediately; CardPlayed is the announcement.
            CardType.RUSH_HOUR_DELAY, CardType.EXPRESS_SKIP, CardType.NIGHT_OWL_SERVICE ->
                s = s.copy(bonusMinutes = s.bonusMinutes + checkNotNull(bonusMinutes(card)))

            // --- C4/C5/C9: timed movement curses; GPS compliance is EffectEnforcer's.
            CardType.STALLED_TRAIN, CardType.LOCAL_SERVICE, CardType.TICKET_INSPECTION ->
                addEffect(nowGameMillis + checkNotNull(effectDurationMillis(card)))

            // --- C6: Q1 disabled for 8:00 — expressed as a Radius Ping cooldown floor so
            //     W3's CooldownTracker reads it from the same field.
            CardType.TUNNEL_VISION -> {
                val until = nowGameMillis + checkNotNull(effectDurationMillis(card))
                addEffect(until)
                s = s.copy(
                    categoryCooldownUntilMillis = s.categoryCooldownUntilMillis +
                        (
                            QuestionCategory.RADIUS_PING to maxOf(
                                s.categoryCooldownUntilMillis[QuestionCategory.RADIUS_PING] ?: 0L,
                                until,
                            )
                            ),
                )
            }

            // --- C7: untimed marker; consumed by onAnswerComputed on the next answer.
            CardType.SCRAMBLED_SIGNAL -> addEffect(null)

            // --- C8: untimed; per-seeker return targets snapshotted from visitHistory.
            CardType.U_TURN -> addEffect(null, UTurnParams(uTurnTargetsFromHistory(s)))

            // --- C10: banned route for 12:00; corridor compliance is EffectEnforcer's,
            //     sim path planning reads the DetourParams from activeEffects.
            CardType.DETOUR -> addEffect(nowGameMillis + checkNotNull(effectDurationMillis(card)))

            // --- C11: veto — question cancelled, no answer, no compensation; category and
            //     global cooldowns still apply (set here so they survive the cancellation).
            CardType.CONDUCTORS_OVERRIDE -> {
                val pending = checkNotNull(s.pendingQuestion)
                val category = pending.spec.category
                val factor = s.config.cooldownMultiplier.factor
                val globalUntil = maxOf(
                    s.globalCooldownUntilMillis,
                    nowGameMillis + (QuestionRules.GLOBAL_COOLDOWN_MILLIS * factor).toLong(),
                )
                val categoryCooldown = (category.baseCooldownMillis * factor).toLong() +
                    (s.categoryCooldownBonusMillis[category] ?: 0L)
                val categoryUntil = maxOf(
                    s.categoryCooldownUntilMillis[category] ?: 0L,
                    nowGameMillis + categoryCooldown,
                )
                s = s.copy(
                    pendingQuestion = null,
                    globalCooldownUntilMillis = globalUntil,
                    categoryCooldownUntilMillis = s.categoryCooldownUntilMillis + (category to categoryUntil),
                )
                events += GameEvent.AnswerVetoed(pending.spec, nowGameMillis)
            }

            // --- C12: decoy — the pending question stays pending; W3 substitutes the
            //     answer position via activeDecoyPoint; onAnswerDelivered emits the reveal.
            CardType.GHOST_ECHO -> addEffect(null)

            // --- C13: relocation. GPS: 10:00 travel window opens, zone cleared. Sim: zone
            //     moves to the chosen node (≤5 edges, engine-verified); token movement is W5/W6's.
            CardType.TRANSFER_SLIP -> {
                s = if (s.config.playMode == PlayMode.GPS) {
                    s.copy(
                        hiderZoneStationId = null,
                        hiderRelocationDeadlineMillis = nowGameMillis + GameRules.RELOCATE_WINDOW_MILLIS,
                    )
                } else {
                    s.copy(hiderZoneStationId = (params as EffectParams.RelocateParams).targetStationId)
                }
                events += GameEvent.HiderRelocating(nowGameMillis)
            }

            // --- C14: discard up to 3, draw the same number (discards hit the pile before
            //     the draw so a reshuffle can include them).
            CardType.LOST_AND_FOUND -> {
                val discards = (params as EffectParams.LostAndFoundParams).discards
                hand = checkNotNull(Hand.discard(hand, discards))
                newDeck = newDeck.discard(discards)
                val draw = newDeck.draw(discards.size)
                newDeck = draw.deck
                hand = hand + draw.drawn
                events += GameEvent.CardsDrawn(draw.drawn.size, draw.drawn.size, nowGameMillis)
            }

            // --- C15/C20: draw straight into the hand (keep all). The hand may exceed the
            //     limit; the engine collects the discard-down choice (Hand.discardDownRequired).
            CardType.FOUND_WALLET, CardType.GOLDEN_TICKET -> {
                val n = if (card == CardType.FOUND_WALLET) 2 else 3
                val draw = newDeck.draw(n)
                newDeck = draw.deck
                hand = hand + draw.drawn
                events += GameEvent.CardsDrawn(draw.drawn.size, draw.drawn.size, nowGameMillis)
            }

            // --- C16: next answered question grants double compensation.
            CardType.OFF_PEAK_PASS -> {
                addEffect(null)
                s = s.copy(doubleCompensationPending = true)
            }

            // --- C17: hand limit 8 for the rest of the round.
            CardType.BIGGER_BAG -> {
                addEffect(null)
                s = s.copy(handLimit = GameRules.BIGGER_BAG_HAND_LIMIT)
            }

            // --- C18: question lockout for 6:00 — expressed as a global-cooldown floor so
            //     W3's CooldownTracker reads it from the same field.
            CardType.DEAD_ZONE -> {
                val until = nowGameMillis + checkNotNull(effectDurationMillis(card))
                addEffect(until)
                s = s.copy(globalCooldownUntilMillis = maxOf(s.globalCooldownUntilMillis, until))
            }

            // --- C19: permanent +5:00 category cooldown; stacks across copies.
            CardType.SERVICE_CHANGE -> {
                val category = (params as EffectParams.ServiceChangeParams).category
                addEffect(null)
                s = s.copy(
                    categoryCooldownBonusMillis = s.categoryCooldownBonusMillis +
                        (category to (s.categoryCooldownBonusMillis[category] ?: 0L) + SERVICE_CHANGE_BONUS_MILLIS),
                )
            }
        }

        s = s.copy(
            hand = hand,
            deckCount = newDeck.drawPileSize,
            discardCount = newDeck.discardPileSize,
        )
        return ApplyResult(s, newDeck, events)
    }

    /** Service Change (C19) per-copy category cooldown increase (GAME_DESIGN.md §4.2). */
    const val SERVICE_CHANGE_BONUS_MILLIS: Long = 5 * 60_000L

    /**
     * C8 targets: each seeker must return to the previous station they visited.
     * A seeker standing exactly at their latest visited node must return to the one
     * before it; otherwise the latest visited station is the return target. Seekers
     * with no visit history are unaffected.
     */
    private fun uTurnTargetsFromHistory(state: GameState): Map<String, String> {
        val targets = mutableMapOf<String, String>()
        for ((playerId, role) in state.roles) {
            if (role != Role.SEEKER) continue
            val history = state.visitHistory[playerId].orEmpty()
            if (history.isEmpty()) continue
            val position = state.positions[playerId]
            val atLatest = position is PlayerPosition.NodePosition && position.stationId == history.last()
            val target = if (atLatest && history.size >= 2) history[history.size - 2] else history.last()
            targets[playerId.value] = target
        }
        return targets
    }

    // ---------------------------------------------------------------------------------
    // Effect lifecycle helpers (W6 calls these)
    // ---------------------------------------------------------------------------------

    /**
     * Removes every timed effect whose expiry has passed and emits a
     * [GameEvent.CurseEnded] per removal (also used for the timed Dead Zone — it is
     * the only effect-ended event; UI labels by [CardType]). Untimed effects
     * (null expiry) never expire here.
     */
    fun expireEffects(state: GameState, nowGameMillis: Long): ExpiryResult {
        val (expired, remaining) = state.activeEffects.partition {
            it.expiryGameMillis != null && it.expiryGameMillis!! <= nowGameMillis
        }
        if (expired.isEmpty()) return ExpiryResult(state, emptyList())
        return ExpiryResult(
            state = state.copy(activeEffects = remaining),
            events = expired.map { GameEvent.CurseEnded(it.type, nowGameMillis) },
        )
    }

    /**
     * Marks [seekerId] as returned for the active C8 U-Turn (GPS: within 100 m of
     * the target; sim: at the node — the engine decides when). When the last seeker
     * returns, the effect self-clears with a [GameEvent.CurseEnded]. No-op when no
     * U-Turn is active or the seeker has no pending target.
     */
    fun markUTurnReturned(state: GameState, seekerId: PlayerId, nowGameMillis: Long): ExpiryResult {
        val effect = state.activeEffects.firstOrNull { it.type == CardType.U_TURN }
            ?: return ExpiryResult(state, emptyList())
        val targets = (effect.params as? UTurnParams)?.returnStationIdBySeeker ?: emptyMap()
        if (seekerId.value !in targets) return ExpiryResult(state, emptyList())
        val remaining = targets - seekerId.value
        return if (remaining.isEmpty()) {
            ExpiryResult(
                state = state.copy(activeEffects = state.activeEffects - effect),
                events = listOf(GameEvent.CurseEnded(CardType.U_TURN, nowGameMillis)),
            )
        } else {
            val updated = effect.copy(params = UTurnParams(remaining))
            ExpiryResult(
                state = state.copy(
                    activeEffects = state.activeEffects.map { if (it === effect) updated else it },
                ),
                events = emptyList(),
            )
        }
    }

    /**
     * Called by the engine when a question's answer has just been **computed**:
     * consumes an active Scrambled Signal (C7), returning the delivery delay for the
     * `DelayedAnswer` (GAME_DESIGN.md §4.2 C7: withheld 5:00 after computation,
     * cooldowns start at delivery). Emits [GameEvent.CurseEnded] for the consumed
     * curse (its countdown is the answer's, not the effect's).
     */
    fun onAnswerComputed(state: GameState, nowGameMillis: Long): AnswerComputation {
        val effect = state.activeEffects.firstOrNull { it.type == CardType.SCRAMBLED_SIGNAL }
            ?: return AnswerComputation(state, 0L, emptyList())
        return AnswerComputation(
            state = state.copy(activeEffects = state.activeEffects - effect),
            delayMillis = QuestionRules.SCRAMBLED_SIGNAL_DELAY_MILLIS,
            events = listOf(GameEvent.CurseEnded(CardType.SCRAMBLED_SIGNAL, nowGameMillis)),
        )
    }

    /**
     * Called by the engine immediately after an answer is **delivered** to seekers:
     * consumes the answer-linked effects.
     *
     * - Ghost Echo (C12): removed; emits [GameEvent.DecoyRevealed] — seekers are
     *   told "that answer was a decoy" immediately after delivery (§4.2 C12).
     * - Off-Peak Pass (C16): removed; `doubleCompensationPending` cleared;
     *   [AnswerDeliveryEffects.doubleCompensation] is true so the engine doubles
     *   the draw-D-keep-K for this answer (§4.2 C16).
     */
    fun onAnswerDelivered(
        state: GameState,
        spec: QuestionSpec,
        nowGameMillis: Long,
    ): AnswerDeliveryEffects {
        var s = state
        val events = mutableListOf<GameEvent>()
        var doubleCompensation = false

        s.activeEffects.firstOrNull { it.type == CardType.GHOST_ECHO }?.let { decoy ->
            s = s.copy(activeEffects = s.activeEffects - decoy)
            events += GameEvent.DecoyRevealed(spec, nowGameMillis)
        }
        s.activeEffects.firstOrNull { it.type == CardType.OFF_PEAK_PASS }?.let { pass ->
            s = s.copy(activeEffects = s.activeEffects - pass, doubleCompensationPending = false)
            doubleCompensation = true
        }
        return AnswerDeliveryEffects(s, events, doubleCompensation)
    }
}
