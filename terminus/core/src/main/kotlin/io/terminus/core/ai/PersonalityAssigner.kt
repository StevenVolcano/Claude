package io.terminus.core.ai

import io.terminus.core.game.AiPersonality
import io.terminus.core.game.AiPersonalityMode
import io.terminus.core.game.GameConfig
import io.terminus.core.game.PlayerId
import kotlin.random.Random

/**
 * Per-round AI personality assignment (GAME_DESIGN.md §6.4): personalities are drawn
 * per AI per round from the round seed **without replacement** — two AIs in the same
 * round never share one — so the draw is deterministic for a given seed. MANUAL mode
 * takes the personalities chosen in setup (`GameConfig.manualPersonalities`).
 */
object PersonalityAssigner {

    /**
     * @param roundSeed the round's RNG seed (the engine derives one per round).
     * @param aiPlayerIds the round's AI players, in `GameState.players` order.
     * @param mode HIDDEN and REVEALED both draw from the seed (visibility is a UI
     *   concern); MANUAL uses [manualPersonalities].
     * @param manualPersonalities parallel to [aiPlayerIds] in MANUAL mode.
     */
    fun assign(
        roundSeed: Long,
        aiPlayerIds: List<PlayerId>,
        mode: AiPersonalityMode,
        manualPersonalities: List<AiPersonality>? = null,
    ): Map<PlayerId, AiPersonality> {
        require(aiPlayerIds.size <= AiPersonality.entries.size) {
            "Cannot assign distinct personalities to ${aiPlayerIds.size} AIs: " +
                "only ${AiPersonality.entries.size} personalities exist"
        }
        if (mode == AiPersonalityMode.MANUAL) {
            val manual = requireNotNull(manualPersonalities) {
                "AiPersonalityMode.MANUAL requires manualPersonalities"
            }
            require(manual.size >= aiPlayerIds.size) {
                "manualPersonalities has ${manual.size} entries for ${aiPlayerIds.size} AI players"
            }
            require(manual.distinct().size == manual.size) {
                "Two AIs may never share a personality (GAME_DESIGN.md §6.4)"
            }
            return aiPlayerIds.withIndex().associate { (i, id) -> id to manual[i] }
        }
        // Deterministic draw without replacement from the round seed.
        val pool = AiPersonality.entries.toMutableList()
        val rng = Random(roundSeed)
        return aiPlayerIds.associateWith { pool.removeAt(rng.nextInt(pool.size)) }
    }

    /** Convenience overload reading mode and manual choices from [config]. */
    fun assign(
        config: GameConfig,
        roundSeed: Long,
        aiPlayerIds: List<PlayerId>,
    ): Map<PlayerId, AiPersonality> =
        assign(roundSeed, aiPlayerIds, config.aiPersonalityMode, config.manualPersonalities)
}
