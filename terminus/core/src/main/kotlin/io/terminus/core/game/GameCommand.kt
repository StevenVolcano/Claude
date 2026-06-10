package io.terminus.core.game

import io.terminus.core.cards.CardType
import io.terminus.core.cards.EffectParams
import io.terminus.core.geo.LatLng
import io.terminus.core.questions.QuestionSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Inputs to the pure reducer `(GameState, GameCommand) -> (GameState, List<GameEvent>)`
 * (ARCHITECTURE.md §1.1 `game`, §2). The UI, the GPS foreground service, the 1 Hz ticker,
 * and the AI brains all feed commands into the same channel (`GameRunner`, W6).
 *
 * Times are game-time milliseconds since round start.
 */
@Serializable
sealed interface GameCommand {
    /** Begins the round: deals nothing, enters HIDING, places everyone at the start station (§2.1). */
    @Serializable
    @SerialName("startRound")
    data object StartRound : GameCommand

    /**
     * Clock tick from the 1 Hz real-time ticker (ARCHITECTURE.md §2):
     * advances the game clock by [gameMillisDelta] (= 1000 × timeScale in sim mode).
     */
    @Serializable
    @SerialName("tick")
    data class Tick(
        val gameMillisDelta: Long,
    ) : GameCommand

    /** A GPS fix for the human player, forwarded by the foreground service (ARCHITECTURE.md §1.2). */
    @Serializable
    @SerialName("gpsFix")
    data class GpsFix(
        val playerId: PlayerId,
        val position: LatLng,
        val accuracyMeters: Double? = null,
        val gameTimeMillis: Long,
    ) : GameCommand

    /** A seeker asks a question (GAME_DESIGN.md §3); opens the 20 s response window. */
    @Serializable
    @SerialName("askQuestion")
    data class AskQuestion(
        val seekerId: PlayerId,
        val spec: QuestionSpec,
        val gameTimeMillis: Long,
    ) : GameCommand

    /**
     * The 20 s response window of the pending question has elapsed without a veto/decoy;
     * the app now answers automatically and truthfully (GAME_DESIGN.md §3).
     */
    @Serializable
    @SerialName("answerWindowElapsed")
    data class AnswerWindowElapsed(
        val gameTimeMillis: Long,
    ) : GameCommand

    /** The hider plays a card (GAME_DESIGN.md §4); [params] only for cards needing a choice. */
    @Serializable
    @SerialName("playCard")
    data class PlayCard(
        val playerId: PlayerId,
        val type: CardType,
        val params: EffectParams? = null,
        val gameTimeMillis: Long,
    ) : GameCommand

    /**
     * Sim mode: move a token toward [targetStationId] along the planned path
     * (GAME_DESIGN.md §1.2; planned by W5's `PathPlanner`).
     */
    @Serializable
    @SerialName("moveToken")
    data class MoveToken(
        val playerId: PlayerId,
        val targetStationId: String,
        val gameTimeMillis: Long,
    ) : GameCommand

    /**
     * Resolves a pending draw-D-keep-K compensation: [kept] is the subset of
     * `GameState.pendingKeep.drawn` retained; the rest are discarded (GAME_DESIGN.md §3).
     */
    @Serializable
    @SerialName("keepCards")
    data class KeepCards(
        val playerId: PlayerId,
        val kept: List<CardType>,
        val gameTimeMillis: Long,
    ) : GameCommand

    /** Discards cards (e.g. discarding down to the hand limit, GAME_DESIGN.md §4.1). */
    @Serializable
    @SerialName("discardCards")
    data class DiscardCards(
        val playerId: PlayerId,
        val discards: List<CardType>,
        val gameTimeMillis: Long,
    ) : GameCommand

    /** Toggles the sim-mode pause; rejected in GPS mode (GAME_DESIGN.md §2.2). */
    @Serializable
    @SerialName("pauseToggle")
    data object PauseToggle : GameCommand
}
