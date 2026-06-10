package io.terminus.core.engine

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * Drives the [GameRunner]'s tick loop (ARCHITECTURE.md §2 step 1). Each completed
 * [awaitTick] makes the runner read its `TimeSource` and emit one `GameCommand.Tick`
 * with the elapsed game time. Injectable so tests can step deterministically.
 */
fun interface Ticker {
    /** Suspends until the next tick should fire. */
    suspend fun awaitTick()
}

/** The production 1 Hz real-time ticker (GAME_DESIGN.md §2.2: "UI updates at 1 Hz"). */
class RealTicker(
    private val intervalRealMillis: Long = 1_000L,
) : Ticker {
    override suspend fun awaitTick() = delay(intervalRealMillis)
}

/**
 * Test ticker: [awaitTick] completes once per [fire] call, letting tests interleave
 * `SteppedTimeSource.advanceMillis` and ticks deterministically.
 */
class ManualTicker : Ticker {
    private val ticks = Channel<Unit>(Channel.UNLIMITED)

    /** Releases one tick. */
    fun fire() {
        ticks.trySend(Unit)
    }

    override suspend fun awaitTick() {
        ticks.receive()
    }
}
