package io.terminus.core.clock

/**
 * Wall-clock [TimeSource] for GPS mode (GAME_DESIGN.md §2.2: game time = wall time, 1:1).
 *
 * Game time starts at 0 at construction. GPS mode is never pausable, so there is no
 * pause API. The wall clock is injectable for tests.
 *
 * @param wallMillis wall-clock supplier in epoch milliseconds; defaults to
 *   [System.currentTimeMillis].
 */
class RealTimeSource(
    private val wallMillis: () -> Long = System::currentTimeMillis,
) : TimeSource {

    private val startWallMillis: Long = wallMillis()

    override fun nowGameMillis(): Long = wallMillis() - startWallMillis
}
