package io.terminus.core.clock

/**
 * Source of game time in milliseconds (ARCHITECTURE.md §1.1 `clock`; GAME_DESIGN.md §2.2).
 *
 * All durations in the design are game time: GPS mode is 1:1 wall time, sim mode is wall
 * time scaled by the configured time scale. Concrete sources (`RealTimeSource`,
 * `ScaledTimeSource`, the stepped test clock) and `GameClock` are W5's.
 */
fun interface TimeSource {
    /** Current game time in milliseconds since the start of the round. */
    fun nowGameMillis(): Long
}
