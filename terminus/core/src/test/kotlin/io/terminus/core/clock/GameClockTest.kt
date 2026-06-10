package io.terminus.core.clock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameClockTest {

    private val time = SteppedTimeSource()
    private val clock = GameClock(time)

    @Test
    fun delegatesNowToTimeSource() {
        time.advanceSeconds(7)
        assertEquals(7_000L, clock.nowGameMillis())
    }

    @Test
    fun noDeadlineMeansNoRemainingAndNotExpired() {
        assertNull(clock.phaseDeadlineGameMillis)
        assertNull(clock.remainingMillis())
        assertFalse(clock.isPhaseExpired())
    }

    @Test
    fun deadlineTrackingAndRemaining() {
        time.advanceSeconds(10)
        clock.setPhaseDeadlineIn(60_000L) // deadline at 70 s
        assertEquals(70_000L, clock.phaseDeadlineGameMillis)
        assertEquals(60_000L, clock.remainingMillis())
        assertFalse(clock.isPhaseExpired())

        time.advanceSeconds(25)
        assertEquals(35_000L, clock.remainingMillis())

        time.advanceSeconds(35)
        assertEquals(0L, clock.remainingMillis())
        assertTrue(clock.isPhaseExpired())

        time.advanceSeconds(99)
        assertEquals(0L, clock.remainingMillis()) // floored, never negative
        assertTrue(clock.isPhaseExpired())
    }

    @Test
    fun absoluteDeadlineAndClear() {
        clock.setPhaseDeadlineAt(15_000L)
        assertEquals(15_000L, clock.phaseDeadlineGameMillis)
        time.advanceSeconds(15)
        assertTrue(clock.isPhaseExpired())
        clock.clearPhaseDeadline()
        assertNull(clock.phaseDeadlineGameMillis)
        assertFalse(clock.isPhaseExpired())
        assertNull(clock.remainingMillis())
    }

    @Test
    fun rejectsNegativeDeadlineDuration() {
        assertFailsWith<IllegalArgumentException> { clock.setPhaseDeadlineIn(-1L) }
    }

    @Test
    fun scheduledMomentChecks() {
        assertTrue(clock.hasReached(0L))
        assertFalse(clock.hasReached(1L))
        time.advanceSeconds(30)
        assertTrue(clock.hasReached(30_000L))
        assertTrue(clock.hasReached(29_999L))
        assertFalse(clock.hasReached(30_001L))
    }

    @Test
    fun elapsedDurationChecks() {
        time.advanceSeconds(100)
        // A cooldown of 2:00 that started at t = 10 s.
        assertFalse(clock.hasElapsed(sinceGameMillis = 10_000L, durationGameMillis = 120_000L))
        time.advanceSeconds(30) // now 130 s, exactly 120 s since 10 s
        assertTrue(clock.hasElapsed(sinceGameMillis = 10_000L, durationGameMillis = 120_000L))
    }
}
