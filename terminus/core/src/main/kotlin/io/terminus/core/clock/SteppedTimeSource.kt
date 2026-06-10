package io.terminus.core.clock

/**
 * Manually advanced [TimeSource] for tests and the deterministic smoke test
 * (ARCHITECTURE.md §2 "Determinism", test plan item 8). Time only moves when the
 * caller advances it, making every run with the same advance script reproducible.
 *
 * @param startGameMillis initial game time, defaults to 0.
 */
class SteppedTimeSource(
    startGameMillis: Long = 0L,
) : TimeSource {

    private var nowGameMillis: Long = startGameMillis

    init {
        require(startGameMillis >= 0) { "startGameMillis must be >= 0, was $startGameMillis" }
    }

    /** Advances game time by [millis] (must be >= 0). */
    fun advanceMillis(millis: Long) {
        require(millis >= 0) { "cannot advance time backwards (millis=$millis)" }
        nowGameMillis += millis
    }

    /** Advances game time by [seconds] (must be >= 0). */
    fun advanceSeconds(seconds: Long) = advanceMillis(seconds * 1000L)

    override fun nowGameMillis(): Long = nowGameMillis
}
