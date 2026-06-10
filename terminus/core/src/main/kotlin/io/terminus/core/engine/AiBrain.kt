package io.terminus.core.engine

import io.terminus.core.game.GameCommand
import io.terminus.core.game.GameState
import io.terminus.core.game.PlayerId

/**
 * A decision source for one AI player (ARCHITECTURE.md §1.1 `engine`, §2 step 3;
 * GAME_DESIGN.md §6: "AI runs on decision ticks of the game clock").
 *
 * The [GameRunner] invokes [decide] on tick boundaries — whenever at least
 * [decisionTickMillis] of game time has passed since the brain's previous invocation
 * — and feeds the returned commands back through its command channel in order.
 *
 * This interface is structurally identical to W7's `TerminusAiBrain`
 * (`io.terminus.core.ai`), so real brains can be adapted trivially (or used directly
 * behind a one-line wrapper). Implementations must be deterministic given the state
 * and their seeded RNG (GAME_DESIGN.md §6 intro).
 */
interface AiBrain {
    /** The AI player this brain controls. */
    val playerId: PlayerId

    /** Decision tick interval in game-time milliseconds (GAME_DESIGN.md §6.5). */
    val decisionTickMillis: Long

    /** Returns the commands this AI wants to issue at [nowGameMillis]. */
    fun decide(state: GameState, nowGameMillis: Long): List<GameCommand>
}
