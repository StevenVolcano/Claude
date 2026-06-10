package io.terminus.core.clock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeSourcesTest {

    private var wall = 0L

    @Test
    fun realTimeSourceStartsAtZeroAndTracksWallTime() {
        wall = 5_000L
        val source = RealTimeSource { wall }
        assertEquals(0L, source.nowGameMillis())
        wall = 8_500L
        assertEquals(3_500L, source.nowGameMillis())
    }

    @Test
    fun scaledTimeSourceScalesWallTime() {
        wall = 1_000L
        val source = ScaledTimeSource(scale = 10) { wall }
        assertEquals(0L, source.nowGameMillis())
        wall = 6_000L // +5 s wall
        assertEquals(50_000L, source.nowGameMillis())
    }

    @Test
    fun scaledTimeSourceAtScaleOneIsWallTime() {
        wall = 0L
        val source = ScaledTimeSource(scale = 1) { wall }
        wall = 42_000L
        assertEquals(42_000L, source.nowGameMillis())
    }

    @Test
    fun scaledTimeSourceAtScaleThirty() {
        wall = 0L
        val source = ScaledTimeSource(scale = 30) { wall }
        wall = 2_000L
        assertEquals(60_000L, source.nowGameMillis())
    }

    @Test
    fun scaledTimeSourceFreezesWhilePaused() {
        wall = 0L
        val source = ScaledTimeSource(scale = 10) { wall }
        wall = 5_000L
        source.pause()
        assertTrue(source.isPaused)
        assertEquals(50_000L, source.nowGameMillis())
        wall = 100_000L // long pause: no game time accrues
        assertEquals(50_000L, source.nowGameMillis())
    }

    @Test
    fun scaledTimeSourceAccumulatesAcrossPauseResumeCycles() {
        wall = 0L
        val source = ScaledTimeSource(scale = 10) { wall }
        wall = 3_000L // +30 s game
        source.pause()
        wall = 10_000L // paused, nothing accrues
        source.resume()
        wall = 12_000L // +20 s game
        assertEquals(50_000L, source.nowGameMillis())
        source.pause()
        wall = 20_000L
        source.resume()
        wall = 21_000L // +10 s game
        assertEquals(60_000L, source.nowGameMillis())
    }

    @Test
    fun scaledTimeSourcePauseAndResumeAreIdempotent() {
        wall = 0L
        val source = ScaledTimeSource(scale = 5) { wall }
        source.resume() // not paused: no-op
        wall = 1_000L
        source.pause()
        source.pause() // second pause: no-op
        wall = 2_000L
        assertEquals(5_000L, source.nowGameMillis())
        source.resume()
        source.resume()
        wall = 3_000L
        assertEquals(10_000L, source.nowGameMillis())
        assertFalse(source.isPaused)
    }

    @Test
    fun scaledTimeSourceRejectsNonPositiveScale() {
        assertFailsWith<IllegalArgumentException> { ScaledTimeSource(scale = 0) { wall } }
        assertFailsWith<IllegalArgumentException> { ScaledTimeSource(scale = -3) { wall } }
    }

    @Test
    fun steppedTimeSourceAdvancesOnlyManually() {
        val source = SteppedTimeSource()
        assertEquals(0L, source.nowGameMillis())
        source.advanceMillis(1_500L)
        assertEquals(1_500L, source.nowGameMillis())
        source.advanceSeconds(2L)
        assertEquals(3_500L, source.nowGameMillis())
        source.advanceMillis(0L)
        assertEquals(3_500L, source.nowGameMillis())
    }

    @Test
    fun steppedTimeSourceSupportsNonZeroStart() {
        val source = SteppedTimeSource(startGameMillis = 60_000L)
        assertEquals(60_000L, source.nowGameMillis())
    }

    @Test
    fun steppedTimeSourceRejectsBackwardsTime() {
        val source = SteppedTimeSource()
        assertFailsWith<IllegalArgumentException> { source.advanceMillis(-1L) }
        assertFailsWith<IllegalArgumentException> { SteppedTimeSource(startGameMillis = -5L) }
    }
}
