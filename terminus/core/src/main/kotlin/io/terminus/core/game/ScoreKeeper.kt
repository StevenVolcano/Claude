package io.terminus.core.game

import io.terminus.core.persistence.RoundRecord
import kotlin.math.roundToLong

/**
 * Scoring math from GAME_DESIGN.md §7.
 *
 * Hider score per round = survival game-minutes (end of hiding phase → capture or
 * clock end, rounded to 0.1 min) + time-bonus card minutes + curse/violation penalty
 * minutes + 10.0 flat if never captured. A failed hiding-phase grace caps the round
 * score at the hiding-phase length (§2.1). Match score per player = sum of their
 * hider-round scores; tiebreaks: fewer questions answered during one's hider rounds,
 * then fewer cards played.
 */
object ScoreKeeper {

    /** Rounds [minutes] to one decimal (§7: "rounded to 0.1 min"). */
    fun roundToTenth(minutes: Double): Double = (minutes * 10.0).roundToLong() / 10.0

    /**
     * The hider's round score (§7), with the §2.1 grace-failure cap applied when
     * [graceFailed].
     */
    fun roundScore(
        survivalMinutes: Double,
        bonusMinutes: Double,
        penaltyMinutes: Double,
        captured: Boolean,
        graceFailed: Boolean,
        hidingPhaseMinutes: Int,
    ): Double {
        var score = roundToTenth(survivalMinutes) + bonusMinutes + penaltyMinutes
        if (!captured) score += GameRules.NEVER_CAPTURED_BONUS_MINUTES
        if (graceFailed) score = minOf(score, hidingPhaseMinutes.toDouble())
        return score
    }

    /** Match totals: each player's summed hider-round scores (§7). */
    fun totalScores(players: List<Player>, rounds: List<RoundRecord>): Map<PlayerId, Double> =
        players.associate { player ->
            player.id to rounds.filter { it.hiderId == player.id }.sumOf { it.hiderScore }
        }

    /**
     * The match winner: highest total, tiebroken by fewer questions answered during
     * one's hider rounds, then fewer cards played, then player order (§7).
     * Null when no rounds were played.
     */
    fun winner(players: List<Player>, rounds: List<RoundRecord>): PlayerId? {
        if (rounds.isEmpty()) return null
        val totals = totalScores(players, rounds)
        fun hiderRounds(id: PlayerId) = rounds.filter { it.hiderId == id }
        return players.minWithOrNull(
            compareByDescending<Player> { totals[it.id] ?: 0.0 }
                .thenBy { player -> hiderRounds(player.id).sumOf { it.questionsAnswered } }
                .thenBy { player -> hiderRounds(player.id).sumOf { it.cardsPlayed } },
        )?.id
    }
}
