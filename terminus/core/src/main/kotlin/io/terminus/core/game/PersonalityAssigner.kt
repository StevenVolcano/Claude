package io.terminus.core.game

import kotlin.random.Random

/**
 * Deterministic per-round AI personality assignment (GAME_DESIGN.md §6.4, §8 item 8).
 *
 * - [AiPersonalityMode.HIDDEN] / [AiPersonalityMode.REVEALED]: personalities are drawn
 *   **without replacement** from `Random(roundSeed)`, one per AI player in player-list
 *   order — the same seed always yields the same assignment and two AIs in the same
 *   round never share a personality. (Hidden vs revealed is purely a UI concern.)
 * - [AiPersonalityMode.MANUAL]: taken from `GameConfig.manualPersonalities`, parallel
 *   to `GameConfig.aiOpponents` (and therefore to the AI players in order).
 */
object PersonalityAssigner {

    /** Personality per AI player for the round started with [roundSeed]. */
    fun assign(
        players: List<Player>,
        config: GameConfig,
        roundSeed: Long,
    ): Map<PlayerId, AiPersonality> {
        val ais = players.filterIsInstance<Player.AiPlayer>()
        if (ais.isEmpty()) return emptyMap()
        require(ais.size <= AiPersonality.entries.size) {
            "more AI players (${ais.size}) than personalities (${AiPersonality.entries.size})"
        }
        if (config.aiPersonalityMode == AiPersonalityMode.MANUAL) {
            val manual = requireNotNull(config.manualPersonalities) {
                "aiPersonalityMode is MANUAL but manualPersonalities is null"
            }
            require(manual.size == ais.size) {
                "manualPersonalities size ${manual.size} != AI player count ${ais.size}"
            }
            require(manual.distinct().size == manual.size) {
                "two AIs may not share a personality (GAME_DESIGN.md §6.4)"
            }
            return ais.mapIndexed { index, ai -> ai.id to manual[index] }.toMap()
        }
        val rng = Random(roundSeed)
        val pool = AiPersonality.entries.toMutableList()
        return ais.associate { ai -> ai.id to pool.removeAt(rng.nextInt(pool.size)) }
    }
}
