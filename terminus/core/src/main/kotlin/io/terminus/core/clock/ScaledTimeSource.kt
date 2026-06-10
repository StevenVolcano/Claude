package io.terminus.core.clock

/**
 * Scaled, pausable [TimeSource] for couch/sim mode (GAME_DESIGN.md §2.2: game time =
 * wall time × time scale, scale ∈ {1, 2, 5, 10, 30}; pausable in sim mode only).
 *
 * Game time starts at 0 at construction and accumulates `wallDelta × scale` while
 * running. Pausing freezes game time; resuming continues from the accumulated value.
 * The scale is fixed at construction (changing scale mid-round would retroactively
 * rescale history; the setup screen fixes it per game, GAME_DESIGN.md §8 item 5).
 *
 * Not thread-safe; the game loop owns the clock (ARCHITECTURE.md §2).
 *
 * @param scale game-seconds per wall-second, ≥ 1.
 * @param wallMillis wall-clock supplier in epoch milliseconds; defaults to
 *   [System.currentTimeMillis]. Injectable for tests.
 */
class ScaledTimeSource(
    val scale: Int,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) : TimeSource {

    init {
        require(scale >= 1) { "scale must be >= 1, was $scale" }
    }

    private var accumulatedGameMillis: Long = 0L
    private var segmentStartWallMillis: Long = wallMillis()

    /** Whether the clock is currently paused. */
    var isPaused: Boolean = false
        private set

    /** Freezes game time. No-op when already paused. */
    fun pause() {
        if (isPaused) return
        accumulatedGameMillis += (wallMillis() - segmentStartWallMillis) * scale
        isPaused = true
    }

    /** Resumes game time from the accumulated value. No-op when not paused. */
    fun resume() {
        if (!isPaused) return
        segmentStartWallMillis = wallMillis()
        isPaused = false
    }

    override fun nowGameMillis(): Long =
        if (isPaused) {
            accumulatedGameMillis
        } else {
            accumulatedGameMillis + (wallMillis() - segmentStartWallMillis) * scale
        }
}
