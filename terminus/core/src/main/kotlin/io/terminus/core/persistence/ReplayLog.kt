package io.terminus.core.persistence

import io.terminus.core.game.GameCommand
import io.terminus.core.game.GameConfig
import kotlinx.serialization.Serializable

/**
 * The sim-mode autosave payload (ARCHITECTURE.md §5 `autosave.json`): the engine's
 * round runtime (deck card identities, token actors, capture bookkeeping) is
 * deliberately kept out of the serialized `GameState`, so an in-progress game is
 * persisted as its **command log** instead. Replaying [commands] through a fresh
 * `engine.GameRunner` (see `engine.Replay`) deterministically reconstructs the
 * state, the runtime, and the AI brains' RNG positions (ARCHITECTURE.md §2
 * "Determinism"). Commands carry their game-time stamps; ticks carry deltas.
 *
 * GPS mode is not resumable (ARCHITECTURE.md §5) and never writes one of these.
 */
@Serializable
data class ReplayLog(
    val schemaVersion: Int = 1,
    val cityId: String,
    val config: GameConfig,
    val commands: List<GameCommand> = emptyList(),
)
