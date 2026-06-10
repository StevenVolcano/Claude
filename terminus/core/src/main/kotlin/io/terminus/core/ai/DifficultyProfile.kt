package io.terminus.core.ai

import io.terminus.core.game.Difficulty
import kotlinx.serialization.Serializable

/** How an AI seeker team chooses its next question (GAME_DESIGN.md §6.3, §6.4). */
@Serializable
enum class QuestionChoiceStrategy {
    /** Easy: uniform pick among the top 3 by expected entropy reduction. */
    RANDOM_OF_TOP_3,

    /** Medium: maximize expected information gain. */
    MAX_INFO_GAIN,

    /** Hard: maximize gain / (1 + cooldownMinutes/10), decoy-aware. */
    MAX_GAIN_PER_COST,
}

/** How an AI hider scores candidate hiding spots (GAME_DESIGN.md §6.1, §6.4). */
@Serializable
enum class SpotScoringStrategy {
    /** Easy: uniform pick from the top 50% by travel-time term T only. */
    DISTANCE_ONLY,

    /** Medium: the full 0.40·T + 0.25·R + 0.20·A + 0.15·rand formula. */
    FULL_FORMULA,

    /** Hard: full formula + rejects line-terminus names + pre-plans a C12 decoy point. */
    FULL_WITH_SALIENCE_REJECTION,
}

/** When an AI hider plays Conductor's Override (GAME_DESIGN.md §6.2, §6.4). */
@Serializable
enum class VetoPolicy {
    /** Easy: never veto. */
    NEVER,

    /** Medium: veto Q1@500 m and Q6 only. */
    FIXED_LIST,

    /** Hard: veto when modeled information gain > 1.2 bits. */
    GAIN_THRESHOLD,
}

/**
 * Per-difficulty AI tuning — the exact table from GAME_DESIGN.md §6.4
 * (ARCHITECTURE.md §1.1 `ai`). Brains and `CandidateSet` are W7's.
 *
 * @property decisionTickSeconds AI decision-tick interval in game seconds.
 * @property answerFilterEpsilon CandidateSet weight multiplier for answer-inconsistent
 *   stations (0 = exact filtering) (GAME_DESIGN.md §6.3).
 * @property questionChoice question-selection strategy.
 * @property coordinatesSeekers true when multiple AI seekers split across weight clusters
 *   (Hard only); otherwise all chase the same best target.
 * @property spotScoring hider hiding-spot scoring strategy.
 * @property vetoPolicy hider veto policy.
 * @property travelSpeedMultiplier multiplier on AI travel speed along edges.
 * @property decoyAnswerEpsilon weight multiplier applied to decoy-flagged answers, or null
 *   when decoy answers are discarded entirely (Medium/Hard; Easy applies them at 0.5)
 *   (GAME_DESIGN.md §6.3).
 */
@Serializable
data class DifficultyProfile(
    val decisionTickSeconds: Int,
    val answerFilterEpsilon: Double,
    val questionChoice: QuestionChoiceStrategy,
    val coordinatesSeekers: Boolean,
    val spotScoring: SpotScoringStrategy,
    val vetoPolicy: VetoPolicy,
    val travelSpeedMultiplier: Double,
    val decoyAnswerEpsilon: Double? = null,
) {
    companion object {
        /** Easy column of the GAME_DESIGN.md §6.4 table. */
        val EASY = DifficultyProfile(
            decisionTickSeconds = 45,
            answerFilterEpsilon = 0.15,
            questionChoice = QuestionChoiceStrategy.RANDOM_OF_TOP_3,
            coordinatesSeekers = false,
            spotScoring = SpotScoringStrategy.DISTANCE_ONLY,
            vetoPolicy = VetoPolicy.NEVER,
            travelSpeedMultiplier = 0.85,
            decoyAnswerEpsilon = 0.5,
        )

        /** Medium column of the GAME_DESIGN.md §6.4 table. */
        val MEDIUM = DifficultyProfile(
            decisionTickSeconds = 25,
            answerFilterEpsilon = 0.05,
            questionChoice = QuestionChoiceStrategy.MAX_INFO_GAIN,
            coordinatesSeekers = false,
            spotScoring = SpotScoringStrategy.FULL_FORMULA,
            vetoPolicy = VetoPolicy.FIXED_LIST,
            travelSpeedMultiplier = 1.0,
            decoyAnswerEpsilon = null,
        )

        /** Hard column of the GAME_DESIGN.md §6.4 table. */
        val HARD = DifficultyProfile(
            decisionTickSeconds = 15,
            answerFilterEpsilon = 0.0,
            questionChoice = QuestionChoiceStrategy.MAX_GAIN_PER_COST,
            coordinatesSeekers = true,
            spotScoring = SpotScoringStrategy.FULL_WITH_SALIENCE_REJECTION,
            vetoPolicy = VetoPolicy.GAIN_THRESHOLD,
            travelSpeedMultiplier = 1.0,
            decoyAnswerEpsilon = null,
        )

        /** The profile for a [Difficulty] level. */
        fun of(difficulty: Difficulty): DifficultyProfile = when (difficulty) {
            Difficulty.EASY -> EASY
            Difficulty.MEDIUM -> MEDIUM
            Difficulty.HARD -> HARD
        }
    }
}
