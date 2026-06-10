package io.terminus.core.game

import io.terminus.core.cards.CardType
import io.terminus.core.cards.EffectParams
import io.terminus.core.geo.LatLng
import io.terminus.core.questions.Answer
import io.terminus.core.questions.QuestionSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything notable that happens during a round (ARCHITECTURE.md §1.1 `game`, §2).
 * Events feed the ActivityLog UI, the EndScreen, and `RoundRecord` persistence;
 * the smoke test requires byte-identical event logs across seeded runs
 * (ARCHITECTURE.md §6 item 8). All timestamps are game-time milliseconds.
 *
 * Visibility filtering (e.g. not showing a Ghost Echo's decoy point to a human seeker)
 * is a UI concern; the log itself is complete and truthful.
 */
@Serializable
sealed interface GameEvent {
    /** Game time at which the event occurred. */
    val gameTimeMillis: Long

    /** A phase transition (GAME_DESIGN.md §2.1). */
    @Serializable
    @SerialName("phaseChanged")
    data class PhaseChanged(
        val from: GamePhase,
        val to: GamePhase,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /** A seeker asked a question; the 20 s response window opened (GAME_DESIGN.md §3). */
    @Serializable
    @SerialName("questionAsked")
    data class QuestionAsked(
        val seekerId: PlayerId,
        val spec: QuestionSpec,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /**
     * An answer was delivered to the seekers (GAME_DESIGN.md §3). Delivery may be later
     * than computation under Scrambled Signal (C7).
     *
     * @property wasDecoy true when computed from a Ghost Echo decoy point; a separate
     *   [DecoyRevealed] is emitted immediately after delivery (GAME_DESIGN.md §4.2 C12).
     */
    @Serializable
    @SerialName("answerDelivered")
    data class AnswerDelivered(
        val spec: QuestionSpec,
        val answer: Answer,
        val wasDecoy: Boolean = false,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /** The hider played Conductor's Override: no answer, no compensation; cooldowns stand (C11). */
    @Serializable
    @SerialName("answerVetoed")
    data class AnswerVetoed(
        val spec: QuestionSpec,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /** Seekers are told "that answer was a decoy" (but not the true answer) (C12). */
    @Serializable
    @SerialName("decoyRevealed")
    data class DecoyRevealed(
        val spec: QuestionSpec,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /** A card was played; plays are announced to seekers (GAME_DESIGN.md §4.2). */
    @Serializable
    @SerialName("cardPlayed")
    data class CardPlayed(
        val playerId: PlayerId,
        val type: CardType,
        val params: EffectParams? = null,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /**
     * The hider drew cards (compensation draw-D-keep-K, or a utility card's draw)
     * (GAME_DESIGN.md §3, §4.2).
     */
    @Serializable
    @SerialName("cardsDrawn")
    data class CardsDrawn(
        val drawn: Int,
        val kept: Int,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /** A curse came into force; a countdown is shown to seekers (GAME_DESIGN.md §4.2). */
    @Serializable
    @SerialName("curseStarted")
    data class CurseStarted(
        val type: CardType,
        val expiryGameMillis: Long? = null,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /** A curse expired or self-cleared (GAME_DESIGN.md §4.2). */
    @Serializable
    @SerialName("curseEnded")
    data class CurseEnded(
        val type: CardType,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /**
     * A rule violation by the human player was detected by automatic GPS/position checks
     * (GAME_DESIGN.md §1.3, §4.1). Usually followed by a [PenaltyApplied].
     */
    @Serializable
    @SerialName("violationDetected")
    data class ViolationDetected(
        val playerId: PlayerId,
        val effectType: CardType? = null,
        val description: String,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /** A penalty was applied: [minutes] added to the hider's score (GAME_DESIGN.md §1.3, §4.2, §7). */
    @Serializable
    @SerialName("penaltyApplied")
    data class PenaltyApplied(
        val playerId: PlayerId,
        val minutes: Double,
        val reason: String,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /** Seekers are notified "the hider is relocating" — not where (Transfer Slip C13). */
    @Serializable
    @SerialName("hiderRelocating")
    data class HiderRelocating(
        override val gameTimeMillis: Long,
    ) : GameEvent

    /** Score accrual paused/resumed due to a hiding-zone excursion (GAME_DESIGN.md §5.2). */
    @Serializable
    @SerialName("scoreAccrualChanged")
    data class ScoreAccrualChanged(
        val paused: Boolean,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /** The hider was found (GAME_DESIGN.md §5.3); the clock stops. */
    @Serializable
    @SerialName("captured")
    data class Captured(
        val seekerId: PlayerId,
        val hiderId: PlayerId,
        val position: LatLng? = null,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /** The round ended by capture or clock expiry; scores recorded (GAME_DESIGN.md §2.1, §7). */
    @Serializable
    @SerialName("roundEnded")
    data class RoundEnded(
        val roundIndex: Int,
        val hiderId: PlayerId,
        val captured: Boolean,
        val capturedBy: PlayerId? = null,
        val hiderScore: Double,
        override val gameTimeMillis: Long,
    ) : GameEvent

    /** The sim-mode clock was paused or resumed (GAME_DESIGN.md §2.2). */
    @Serializable
    @SerialName("pauseToggled")
    data class PauseToggled(
        val paused: Boolean,
        override val gameTimeMillis: Long,
    ) : GameEvent
}
