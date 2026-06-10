package io.terminus.core.game

/**
 * Fixed rule constants from GAME_DESIGN.md, gathered in one place so every workstream
 * uses the same numbers. All durations are game time (GAME_DESIGN.md §2.2).
 */
object GameRules {
    /** Valid hiding zone radius around a station, meters (GAME_DESIGN.md §5.1). */
    const val HIDING_ZONE_RADIUS_METERS: Double = 300.0

    /** Stations within this many graph hops of the start station cannot be hiding zones (§5.1). */
    const val HIDING_EXCLUSION_HOPS: Int = 2

    /** Grace period to enter the nearest valid zone after a failed hiding phase, millis (§2.1). */
    const val HIDING_GRACE_MILLIS: Long = 3 * 60_000L

    /** GPS capture distance, meters (§5.3). */
    const val CAPTURE_RADIUS_METERS: Double = 75.0

    /** GPS capture must be sustained for this many consecutive seconds (§5.3). */
    const val CAPTURE_SUSTAIN_SECONDS: Int = 10

    /** Final Approach triggers when a seeker is within this distance of the hiding zone, GPS (§2.1). */
    const val FINAL_APPROACH_TRIGGER_METERS: Double = 300.0

    /** Final Approach AI sweep ring radius, meters (§5.3). */
    const val SWEEP_RING_RADIUS_METERS: Double = 150.0

    /** Final Approach AI sweep waypoint spacing on the ring, degrees (§5.3). */
    const val SWEEP_WAYPOINT_STEP_DEGREES: Int = 60

    /** Walking speed for off-graph movement, m/s (§5.3; ARCHITECTURE.md §1.1 `sim`). */
    const val WALKING_SPEED_MPS: Double = 1.4

    /** Interval between automatic GPS rule checks, game seconds (§1.3, §4.1, §5.2). */
    const val GPS_CHECK_INTERVAL_SECONDS: Int = 5

    /** Seekers must stay within this radius of the start station during the hiding phase, meters (§2.1). */
    const val START_LOCK_RADIUS_METERS: Double = 150.0

    /** Penalty for a seeker leaving the start station during the hiding phase, game millis (§2.1). */
    const val START_LOCK_PENALTY_MILLIS: Long = 5 * 60_000L

    /** Cumulative seconds outside the hiding zone before score accrual pauses (§5.2). */
    const val EXCURSION_GRACE_SECONDS: Int = 60

    /** Default hand limit (§4.1). */
    const val HAND_LIMIT: Int = 6

    /** Hand limit after Bigger Bag (C17) (§4.1, §4.2). */
    const val BIGGER_BAG_HAND_LIMIT: Int = 8

    /** Maximum simultaneously active curses (§4.1). */
    const val MAX_ACTIVE_CURSES: Int = 2

    /** Maximum curse timer restarts per curse after a human violation (§4.1). */
    const val MAX_CURSE_RESTARTS: Int = 1

    /** Decoy point (C12) must be within this distance of the true position, meters (§4.2). */
    const val DECOY_MAX_DISTANCE_METERS: Double = 1500.0

    /** Transfer Slip (C13) GPS relocation window, game millis (§4.2). */
    const val RELOCATE_WINDOW_MILLIS: Long = 10 * 60_000L

    /** Transfer Slip (C13) sim-mode maximum edges moved (§4.2). */
    const val RELOCATE_MAX_EDGES: Int = 5

    /** Flat hider score bonus when never captured, minutes (§7). */
    const val NEVER_CAPTURED_BONUS_MINUTES: Double = 10.0
}
