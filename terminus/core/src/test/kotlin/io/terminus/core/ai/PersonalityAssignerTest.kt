package io.terminus.core.ai

import io.terminus.core.game.AiPersonality
import io.terminus.core.game.AiPersonalityMode
import io.terminus.core.game.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Personality assignment (GAME_DESIGN.md §6.4; ARCHITECTURE.md §6 item 7):
 * deterministic per round seed, drawn without replacement, MANUAL respected.
 */
class PersonalityAssignerTest {

    private val ids = listOf(PlayerId("ai-1"), PlayerId("ai-2"), PlayerId("ai-3"))

    @Test
    fun deterministicPerSeed() {
        val a = PersonalityAssigner.assign(42L, ids, AiPersonalityMode.HIDDEN)
        val b = PersonalityAssigner.assign(42L, ids, AiPersonalityMode.HIDDEN)
        assertEquals(a, b)
        // REVEALED only changes UI visibility, not the draw.
        assertEquals(a, PersonalityAssigner.assign(42L, ids, AiPersonalityMode.REVEALED))
    }

    @Test
    fun differentSeedsEventuallyDiffer() {
        val draws = (0L until 10L).map { PersonalityAssigner.assign(it, ids, AiPersonalityMode.HIDDEN) }
        assertTrue(draws.toSet().size > 1, "10 seeds should not all draw identically")
    }

    @Test
    fun noDuplicatesWithinARound() {
        for (seed in 0L until 50L) {
            val assigned = PersonalityAssigner.assign(seed, ids, AiPersonalityMode.HIDDEN)
            assertEquals(ids.size, assigned.values.toSet().size, "seed $seed produced duplicates")
        }
    }

    @Test
    fun manualModeUsesTheConfiguredPersonalities() {
        val manual = listOf(AiPersonality.GHOST, AiPersonality.RAT, AiPersonality.SHOWMAN)
        val assigned = PersonalityAssigner.assign(7L, ids, AiPersonalityMode.MANUAL, manual)
        assertEquals(AiPersonality.GHOST, assigned[ids[0]])
        assertEquals(AiPersonality.RAT, assigned[ids[1]])
        assertEquals(AiPersonality.SHOWMAN, assigned[ids[2]])
    }

    @Test
    fun manualModeRejectsDuplicatesAndMissingChoices() {
        assertFailsWith<IllegalArgumentException> {
            PersonalityAssigner.assign(7L, ids, AiPersonalityMode.MANUAL, null)
        }
        assertFailsWith<IllegalArgumentException> {
            PersonalityAssigner.assign(
                7L, ids, AiPersonalityMode.MANUAL,
                listOf(AiPersonality.RAT, AiPersonality.RAT, AiPersonality.GHOST),
            )
        }
    }

    @Test
    fun rejectsMoreAisThanPersonalities() {
        val tooMany = (1..6).map { PlayerId("ai-$it") }
        assertFailsWith<IllegalArgumentException> {
            PersonalityAssigner.assign(1L, tooMany, AiPersonalityMode.HIDDEN)
        }
    }
}
