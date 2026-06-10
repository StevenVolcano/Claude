package io.terminus.core.ai

import io.terminus.core.game.AiPersonality
import kotlinx.serialization.Serializable

/**
 * Hider spot-scoring weights (T, R, A, rand) overriding the defaults of GAME_DESIGN.md §6.1
 * (0.40, 0.25, 0.20, 0.15). Used by [PersonalityProfile.spotWeights] (GAME_DESIGN.md §6.4).
 */
@Serializable
data class SpotWeights(
    val travelTime: Double,
    val remoteness: Double,
    val ambiguity: Double,
    val random: Double,
)

/**
 * Style modifiers applied on top of a [DifficultyProfile] — the exact table from
 * GAME_DESIGN.md §6.4 (ARCHITECTURE.md §1.1 `ai`). Difficulty sets competence;
 * personality sets style. Application of these modifiers is W7's.
 *
 * Modifiers the §6.4 table leaves unspecified for a personality use the documented
 * neutral defaults below (Phase 0 decision): spotWeights null (= §6.1 defaults),
 * obscurityBias 0.0, cardAggression 0.5, decoyPropensity 0.5, vetoDelta 0.0,
 * commitment 0.5, no movement threshold.
 *
 * @property personality which named style this profile encodes.
 * @property temperature randomness when choosing among scored options
 *   (0 = always best; high = near-random).
 * @property spotWeights overrides the §6.1 hider spot-scoring weights; null = use defaults.
 * @property obscurityBias extra weight for low-degree, non-interchange, non-terminus stations
 *   (hider) and for checking low-weight candidates (seeker); 0 = none, 1 = strong.
 * @property rejectsSalientSpots Ghost only: as hider, rejects interchanges and termini outright.
 * @property cardAggression in [0,1], how early/freely curses and bonuses are played vs hoarded;
 *   null = re-rolled from the seeded RNG each decision tick (the Rat).
 * @property decoyPropensity in [0,1], probability of spending Ghost Echo when eligible.
 * @property vetoDelta bits added to the difficulty's veto threshold (negative = vetoes more).
 * @property questionRate in [0,1], probability of asking as soon as cooldowns allow vs
 *   traveling first (seeker).
 * @property commitment in [0,1], how sticky the seeker's current target is before re-planning.
 * @property moveOnlyAboveTopWeight Bookkeeper only: the seeker moves toward a target only when
 *   the top candidate weight exceeds this; null = no such gate.
 */
@Serializable
data class PersonalityProfile(
    val personality: AiPersonality,
    val temperature: Double,
    val spotWeights: SpotWeights? = null,
    val obscurityBias: Double = 0.0,
    val rejectsSalientSpots: Boolean = false,
    val cardAggression: Double? = 0.5,
    val decoyPropensity: Double = 0.5,
    val vetoDelta: Double = 0.0,
    val questionRate: Double,
    val commitment: Double = 0.5,
    val moveOnlyAboveTopWeight: Double? = null,
) {
    companion object {
        /** The Rat (GAME_DESIGN.md §6.4): chaotic and unreadable. */
        val RAT = PersonalityProfile(
            personality = AiPersonality.RAT,
            temperature = 2.0,
            spotWeights = SpotWeights(0.0, 0.0, 0.0, 1.0),
            cardAggression = null, // random per decision tick
            questionRate = 0.5,
            commitment = 0.2,
        )

        /** The Ghost (GAME_DESIGN.md §6.4): obscure-stop exploiter. */
        val GHOST = PersonalityProfile(
            personality = AiPersonality.GHOST,
            temperature = 0.3,
            spotWeights = SpotWeights(0.20, 0.45, 0.25, 0.10),
            obscurityBias = 1.0,
            rejectsSalientSpots = true,
            vetoDelta = -0.3,
            questionRate = 0.6,
        )

        /** The Bookkeeper (GAME_DESIGN.md §6.4): information maximizer. */
        val BOOKKEEPER = PersonalityProfile(
            personality = AiPersonality.BOOKKEEPER,
            temperature = 0.1,
            questionRate = 1.0,
            moveOnlyAboveTopWeight = 0.4,
            vetoDelta = -0.4,
            cardAggression = 0.3,
        )

        /** The Bloodhound (GAME_DESIGN.md §6.4): movement-first. */
        val BLOODHOUND = PersonalityProfile(
            personality = AiPersonality.BLOODHOUND,
            temperature = 0.2,
            spotWeights = SpotWeights(0.60, 0.20, 0.05, 0.15),
            questionRate = 0.3,
            commitment = 0.9,
            vetoDelta = 0.5,
            cardAggression = 0.7,
        )

        /** The Showman (GAME_DESIGN.md §6.4): card-aggressive gambler. */
        val SHOWMAN = PersonalityProfile(
            personality = AiPersonality.SHOWMAN,
            temperature = 0.6,
            cardAggression = 0.9,
            decoyPropensity = 0.9,
            questionRate = 0.8,
            commitment = 0.3,
        )

        /** The profile for an [AiPersonality]. */
        fun of(personality: AiPersonality): PersonalityProfile = when (personality) {
            AiPersonality.RAT -> RAT
            AiPersonality.GHOST -> GHOST
            AiPersonality.BOOKKEEPER -> BOOKKEEPER
            AiPersonality.BLOODHOUND -> BLOODHOUND
            AiPersonality.SHOWMAN -> SHOWMAN
        }
    }
}
