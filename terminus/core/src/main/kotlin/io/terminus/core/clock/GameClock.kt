package io.terminus.core.clock

/**
 * Minimal time-bookkeeping helper over a [TimeSource] (ARCHITECTURE.md §1.1 `clock`).
 *
 * Provides phase-deadline tracking, remaining-time queries, and simple
 * scheduled-moment checks. It does **not** implement phase logic — the game engine
 * reducer (`game`/`engine`, W6) decides phase transitions; `GameClock` only answers
 * "what time is it / has moment X passed / how long until the deadline".
 *
 * Not thread-safe; owned by the game loop.
 */
class GameClock(
    private val timeSource: TimeSource,
) {
    /** Current game time in milliseconds (delegates to the [TimeSource]). */
    fun nowGameMillis(): Long = timeSource.nowGameMillis()

    /** The current phase deadline in game millis, or null when no deadline is set. */
    var phaseDeadlineGameMillis: Long? = null
        private set

    /** Sets the phase deadline to an absolute game time. */
    fun setPhaseDeadlineAt(deadlineGameMillis: Long) {
        phaseDeadlineGameMillis = deadlineGameMillis
    }

    /** Sets the phase deadline to now + [durationGameMillis]. */
    fun setPhaseDeadlineIn(durationGameMillis: Long) {
        require(durationGameMillis >= 0) { "duration must be >= 0, was $durationGameMillis" }
        phaseDeadlineGameMillis = nowGameMillis() + durationGameMillis
    }

    /** Clears the phase deadline. */
    fun clearPhaseDeadline() {
        phaseDeadlineGameMillis = null
    }

    /**
     * Game millis remaining until the phase deadline, floored at 0; null when no
     * deadline is set.
     */
    fun remainingMillis(): Long? =
        phaseDeadlineGameMillis?.let { (it - nowGameMillis()).coerceAtLeast(0L) }

    /** True when a phase deadline is set and game time has reached or passed it. */
    fun isPhaseExpired(): Boolean =
        phaseDeadlineGameMillis?.let { nowGameMillis() >= it } ?: false

    /** True when game time has reached or passed the absolute moment [momentGameMillis]. */
    fun hasReached(momentGameMillis: Long): Boolean = nowGameMillis() >= momentGameMillis

    /**
     * True when at least [durationGameMillis] of game time has passed since
     * [sinceGameMillis] — e.g. cooldown and curse-expiry checks.
     */
    fun hasElapsed(sinceGameMillis: Long, durationGameMillis: Long): Boolean =
        nowGameMillis() - sinceGameMillis >= durationGameMillis
}
