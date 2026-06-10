package io.terminus.core.game

import io.terminus.core.cards.ActiveEffect
import io.terminus.core.cards.CardType
import io.terminus.core.geo.LatLng
import io.terminus.core.questions.Answer
import io.terminus.core.questions.QuestionCategory
import io.terminus.core.questions.QuestionSpec
import kotlinx.serialization.Serializable

/** Round phases (GAME_DESIGN.md §2.1). */
@Serializable
enum class GamePhase {
    SETUP,
    HIDING,
    SEEKING,
    FINAL_APPROACH,
    ROUND_END,
}

/**
 * A question that has been asked and is inside its 20-second response window
 * (GAME_DESIGN.md §3 general rules). The hider may play Conductor's Override or
 * Ghost Echo before [responseWindowEndsGameMillis]; otherwise the app answers
 * automatically and truthfully at window end.
 */
@Serializable
data class PendingQuestion(
    val spec: QuestionSpec,
    val askedBy: PlayerId,
    val askedAtGameMillis: Long,
    val responseWindowEndsGameMillis: Long,
)

/**
 * An answer that has been computed but whose delivery is withheld by
 * Curse of the Scrambled Signal (GAME_DESIGN.md §4.2 C7). Cooldowns start at delivery.
 */
@Serializable
data class DelayedAnswer(
    val spec: QuestionSpec,
    val answer: Answer,
    val wasDecoy: Boolean,
    val computedAtGameMillis: Long,
    val deliverAtGameMillis: Long,
)

/**
 * A Thermometer (Q3) that has been armed and not yet resolved
 * (GAME_DESIGN.md §3 Q3): it resolves only after the arming seeker has moved
 * ≥750 m straight-line (GPS) or ≥2 graph edges (sim).
 */
@Serializable
data class ArmedThermometer(
    val seekerId: PlayerId,
    val armPosition: LatLng,
    val armedAtGameMillis: Long,
    /** Graph edges traversed by the seeker since arming (sim-mode resolution criterion). */
    val edgesMovedSinceArm: Int = 0,
)

/**
 * The complete immutable game state — the frozen heart of the Phase 0 contract
 * (ARCHITECTURE.md §1.1 `game`). The reducer `(GameState, GameCommand) ->
 * (GameState, List<GameEvent>)` is W6's; this type is pure data and is what
 * sim-mode autosave serializes (ARCHITECTURE.md §5 `autosave.json`).
 *
 * All times are game time in milliseconds since round start (GAME_DESIGN.md §2.2).
 *
 * @property config the round's full configuration snapshot.
 * @property roundIndex 0-based index within the match (matches run 1/3/5 rounds, §7).
 * @property phase current phase (§2.1).
 * @property gameTimeMillis current game clock reading.
 * @property paused true while the sim-mode clock is paused (§2.2; GPS mode is never pausable).
 * @property players all participants in fixed rotation order (§7).
 * @property roles role per player for this round.
 * @property positions current position per player.
 * @property visitHistory per-seeker ordered station visit history (drives C8 U-Turn, §4.2).
 * @property hiderZoneStationId the station anchoring the hider's hiding zone, null until hidden (§5.1).
 * @property hiderRelocationDeadlineMillis Transfer Slip (C13) GPS travel-window end, null when
 *   not relocating (§4.2).
 * @property scoreAccrualPaused true while the hider is outside their zone past the 60 s grace (§5.2).
 * @property activeEffects card effects currently in force (at most 2 curses, §4.1).
 * @property globalCooldownUntilMillis no question may be asked before this time (§3: 2:00 global).
 * @property categoryCooldownUntilMillis per-category earliest next-ask time (§3 table).
 * @property categoryCooldownBonusMillis permanent per-category additions from Service Change
 *   (C19, stacks; §4.2).
 * @property pendingQuestion the question currently inside its response window, if any.
 * @property delayedAnswer answer withheld by Scrambled Signal (C7), if any.
 * @property armedThermometer an armed, unresolved Q3, if any.
 * @property hand the hider's cards (cards are hider-only, §4.1).
 * @property handLimit 6, or 8 after Bigger Bag (§4.1).
 * @property deckCount cards remaining in the deck.
 * @property discardCount cards in the discard pile (reshuffled into the deck when it empties, §4.1).
 * @property pendingKeep an unresolved draw-D-keep-K compensation awaiting a KeepCards command, if any.
 * @property doubleCompensationPending true while Off-Peak Pass (C16) is waiting for the next
 *   answered question (§4.2).
 * @property questionsAnswered answered-question count this round (tiebreak stat, §7).
 * @property cardsPlayed cards played by the hider this round (tiebreak stat, §7).
 * @property bonusMinutes time-bonus card minutes earned this round (§7).
 * @property penaltyMinutes curse/violation penalty minutes earned this round (§7).
 * @property capturedBy the capturing seeker, null until capture (§5.3).
 * @property captureGameMillis game time of capture, null until capture.
 * @property matchScores cumulative hider-round scores per player across the match (§7).
 * @property eventLog every emitted event this round, in order (feeds the ActivityLog and RoundRecord).
 */
@Serializable
data class GameState(
    val config: GameConfig,
    val roundIndex: Int,
    val phase: GamePhase,
    val gameTimeMillis: Long,
    val paused: Boolean = false,
    val players: List<Player>,
    val roles: Map<PlayerId, Role>,
    val positions: Map<PlayerId, PlayerPosition>,
    val visitHistory: Map<PlayerId, List<String>> = emptyMap(),
    val hiderZoneStationId: String? = null,
    val hiderRelocationDeadlineMillis: Long? = null,
    val scoreAccrualPaused: Boolean = false,
    val activeEffects: List<ActiveEffect> = emptyList(),
    val globalCooldownUntilMillis: Long = 0L,
    val categoryCooldownUntilMillis: Map<QuestionCategory, Long> = emptyMap(),
    val categoryCooldownBonusMillis: Map<QuestionCategory, Long> = emptyMap(),
    val pendingQuestion: PendingQuestion? = null,
    val delayedAnswer: DelayedAnswer? = null,
    val armedThermometer: ArmedThermometer? = null,
    val hand: List<CardType> = emptyList(),
    val handLimit: Int = GameRules.HAND_LIMIT,
    val deckCount: Int = 0,
    val discardCount: Int = 0,
    val pendingKeep: PendingKeep? = null,
    val doubleCompensationPending: Boolean = false,
    val questionsAnswered: Int = 0,
    val cardsPlayed: Int = 0,
    val bonusMinutes: Double = 0.0,
    val penaltyMinutes: Double = 0.0,
    val capturedBy: PlayerId? = null,
    val captureGameMillis: Long? = null,
    val matchScores: Map<PlayerId, Double> = emptyMap(),
    val eventLog: List<GameEvent> = emptyList(),
)

/**
 * An unresolved "draw D, keep K" compensation (GAME_DESIGN.md §3 general rules):
 * the hider has drawn [drawn] and must choose [keep] of them to keep.
 */
@Serializable
data class PendingKeep(
    val drawn: List<CardType>,
    val keep: Int,
)
