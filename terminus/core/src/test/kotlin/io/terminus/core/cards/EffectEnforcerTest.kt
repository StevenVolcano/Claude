package io.terminus.core.cards

import io.terminus.core.game.PlayerId
import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.transit.RouteLine
import io.terminus.core.transit.Station
import io.terminus.core.transit.TransitMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EffectEnforcerTest {

    private val seeker = PlayerId("human-seeker")
    private val base = LatLng(52.050, 5.080)

    /** A point [meters] east of [base] (or another origin). */
    private fun east(meters: Double, origin: LatLng = base): LatLng =
        GeoMath.destinationPoint(origin, 90.0, meters)

    private fun north(meters: Double, origin: LatLng = base): LatLng =
        GeoMath.destinationPoint(origin, 0.0, meters)

    private fun station(id: String, pos: LatLng, routeIds: List<String> = listOf("R1")) =
        Station(
            id = id, name = id, latLng = pos, mode = TransitMode.METRO,
            routeIds = routeIds, isInterchange = false, isTerminus = false,
        )

    // Stations 1 km apart along a west-east line.
    private val stationA = station("A", base)
    private val stationB = station("B", east(1_000.0))
    private val stationC = station("C", east(2_000.0))
    private val stations = listOf(stationA, stationB, stationC)
    private val routeR1 = RouteLine(
        id = "R1", shortName = "1", longName = "Test Line", mode = TransitMode.METRO,
        colorHex = "#FF0000", orderedStationIds = listOf("A", "B", "C"),
    )
    private val routesById = mapOf("R1" to routeR1)

    private fun effect(type: CardType, start: Long = 0L, params: EffectParams? = null) =
        ActiveEffect(
            type = type,
            startGameMillis = start,
            expiryGameMillis = CardEngine.effectDurationMillis(type)?.let { start + it },
            params = params,
        )

    private fun check(
        effects: List<ActiveEffect>,
        track: List<GpsSample>,
        memory: EnforcementMemory = EnforcementMemory(),
        now: Long = track.last().gameTimeMillis,
    ): SeekerCheckResult =
        EffectEnforcer.checkSeeker(effects, seeker, track, stations, routesById, memory, now)

    // --- C4 Stalled Train -----------------------------------------------------------------

    @Test
    fun `C4 staying within 100 m of the play-time anchor is compliant`() {
        val track = listOf(
            GpsSample(0L, base),
            GpsSample(5_000L, east(50.0)),
            GpsSample(10_000L, east(90.0)),
            GpsSample(15_000L, base),
        )
        val result = check(listOf(effect(CardType.STALLED_TRAIN)), track)
        assertTrue(result.violations.isEmpty())
        assertEquals(0, result.effects.single().restartsUsed)
    }

    @Test
    fun `C4 moving over 100 m penalizes 2 minutes and restarts the timer once`() {
        val stalled = effect(CardType.STALLED_TRAIN)
        val track = listOf(
            GpsSample(0L, base),
            GpsSample(5_000L, east(50.0)),
            GpsSample(10_000L, east(150.0)), // 150 m from anchor -> violation + restart
        )
        val result = check(listOf(stalled), track)
        val violation = result.violations.single()
        assertEquals(CardType.STALLED_TRAIN, violation.effectType)
        assertEquals(2.0, violation.penaltyMinutes)
        assertEquals(seeker, violation.playerId)
        assertEquals(10_000L, violation.gameTimeMillis)
        assertTrue(violation.curseTimerRestarted)
        // Timer restarted: full 4:00 from the violation, restart budget spent.
        val updated = result.effects.single()
        assertEquals(10_000L + 4 * 60_000L, updated.expiryGameMillis)
        assertEquals(1, updated.restartsUsed)
    }

    @Test
    fun `C4 second violation penalizes again but cannot restart twice`() {
        val track = listOf(
            GpsSample(0L, base),
            GpsSample(5_000L, east(150.0)), // violation 1, restart
            GpsSample(10_000L, east(155.0)), // only 5 m from the new anchor: no violation
            GpsSample(15_000L, east(300.0)), // 145 m further: violation 2, no restart left
        )
        val result = check(listOf(effect(CardType.STALLED_TRAIN)), track)
        assertEquals(2, result.violations.size)
        assertTrue(result.violations[0].curseTimerRestarted)
        assertFalse(result.violations[1].curseTimerRestarted)
        assertEquals(1, result.effects.single().restartsUsed)
        assertEquals(4.0, result.violations.sumOf { it.penaltyMinutes })
    }

    @Test
    fun `C4 incremental ticks with carried memory never double-report a violation`() {
        val stalled = effect(CardType.STALLED_TRAIN)
        val track = listOf(
            GpsSample(0L, base),
            GpsSample(5_000L, east(150.0)), // the one violation
            GpsSample(10_000L, east(150.0)),
            GpsSample(15_000L, east(160.0)),
        )
        // Tick 1 sees the first two samples; ticks 2 and 3 see progressively more.
        val tick1 = check(listOf(stalled), track.take(2), EnforcementMemory(), now = 5_000L)
        val tick2 = check(tick1.effects, track.take(3), tick1.memory, now = 10_000L)
        val tick3 = check(tick2.effects, track, tick2.memory, now = 15_000L)
        assertEquals(1, tick1.violations.size)
        assertTrue(tick2.violations.isEmpty())
        assertTrue(tick3.violations.isEmpty())
    }

    @Test
    fun `C4 samples after the curse expiry are ignored`() {
        val track = listOf(
            GpsSample(0L, base),
            GpsSample(4 * 60_000L + 5_000L, east(500.0)), // after the 4:00 expiry
        )
        val result = check(listOf(effect(CardType.STALLED_TRAIN)), track)
        assertTrue(result.violations.isEmpty())
    }

    // --- C5 Local Service ------------------------------------------------------------------

    @Test
    fun `C5 leaving a passed station before 30 s is a missed dwell at 1 minute`() {
        val track = listOf(
            GpsSample(0L, east(500.0)), // between A and B, near neither
            GpsSample(10_000L, east(950.0)), // 50 m from B: dwell starts
            GpsSample(20_000L, east(1_200.0)), // 200 m past B after only 10 s: missed
        )
        val result = check(listOf(effect(CardType.LOCAL_SERVICE)), track)
        val violation = result.violations.single()
        assertEquals(CardType.LOCAL_SERVICE, violation.effectType)
        assertEquals(1.0, violation.penaltyMinutes)
        assertTrue(violation.description.contains("B"))
        assertTrue(violation.curseTimerRestarted)
    }

    @Test
    fun `C5 a completed 30 s dwell is compliant and not re-penalized after leaving`() {
        val track = listOf(
            GpsSample(0L, east(500.0)),
            GpsSample(10_000L, east(950.0)), // inside 75 m of B
            GpsSample(25_000L, east(960.0)), // still inside
            GpsSample(40_000L, east(1_010.0)), // 30 s elapsed inside: satisfied
            GpsSample(50_000L, east(1_500.0)), // leaves: no violation
        )
        val result = check(listOf(effect(CardType.LOCAL_SERVICE)), track)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `C5 each missed station is penalized once`() {
        val track = listOf(
            GpsSample(0L, east(940.0)), // inside B's 75 m
            GpsSample(10_000L, east(1_500.0)), // left B early: miss 1
            GpsSample(20_000L, east(1_950.0)), // inside C's 75 m
            GpsSample(30_000L, east(2_300.0)), // left C early: miss 2
            GpsSample(40_000L, east(1_950.0)), // back near C: already penalized
            GpsSample(50_000L, east(2_300.0)), // leaves again: no third penalty
        )
        val result = check(listOf(effect(CardType.LOCAL_SERVICE)), track)
        assertEquals(2, result.violations.size)
        assertEquals(2.0, result.violations.sumOf { it.penaltyMinutes })
        assertTrue(result.violations[0].curseTimerRestarted)
        assertFalse(result.violations[1].curseTimerRestarted) // max 1 restart per curse
    }

    // --- C9 Ticket Inspection ------------------------------------------------------------------

    @Test
    fun `C9 staying within 100 m of the curse-start nearest station is compliant`() {
        val track = listOf(
            GpsSample(0L, east(20.0)), // nearest station: A
            GpsSample(5_000L, east(80.0)),
            GpsSample(10_000L, north(90.0)),
        )
        val result = check(listOf(effect(CardType.TICKET_INSPECTION)), track)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `C9 straying over 100 m penalizes 1_5 minutes and restarts once`() {
        val track = listOf(
            GpsSample(0L, east(20.0)), // anchored to A
            GpsSample(5_000L, east(150.0)), // 150 m from A -> violation
        )
        val result = check(listOf(effect(CardType.TICKET_INSPECTION)), track)
        val violation = result.violations.single()
        assertEquals(1.5, violation.penaltyMinutes)
        assertTrue(violation.description.contains("A"))
        assertTrue(violation.curseTimerRestarted)
        assertEquals(5_000L + 3 * 60_000L, result.effects.single().expiryGameMillis)
    }

    // --- C10 Detour ------------------------------------------------------------------------------

    private fun detourEffect() = effect(
        CardType.DETOUR,
        params = EffectParams.DetourParams(routeId = "R1"),
    )

    @Test
    fun `C10 advancing over 200 m along the banned corridor penalizes 2 minutes`() {
        val track = listOf(
            GpsSample(0L, east(100.0)), // on the A-B-C corridor
            GpsSample(5_000L, east(250.0)), // +150 m along
            GpsSample(10_000L, east(400.0)), // +300 m total -> violation
        )
        val result = check(listOf(detourEffect()), track)
        val violation = result.violations.single()
        assertEquals(CardType.DETOUR, violation.effectType)
        assertEquals(2.0, violation.penaltyMinutes)
        assertTrue(violation.curseTimerRestarted)
    }

    @Test
    fun `C10 movement off the corridor is compliant`() {
        val track = listOf(
            GpsSample(0L, north(300.0)), // 300 m off the polyline
            GpsSample(5_000L, north(300.0, east(500.0))),
            GpsSample(10_000L, north(300.0, east(1_500.0))), // parallel but outside 150 m
        )
        val result = check(listOf(detourEffect()), track)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `C10 crossing the corridor perpendicularly is compliant`() {
        val crossAt = east(1_000.0) // station B
        val track = listOf(
            GpsSample(0L, north(400.0, crossAt)), // outside the corridor
            GpsSample(5_000L, crossAt), // single fix inside while crossing
            GpsSample(10_000L, north(-400.0, crossAt)), // outside again
        )
        val result = check(listOf(detourEffect()), track)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `C10 progress resets after leaving the corridor`() {
        val track = listOf(
            GpsSample(0L, east(100.0)), // in corridor
            GpsSample(5_000L, east(250.0)), // +150 m (under the limit)
            GpsSample(10_000L, north(400.0, east(250.0))), // leaves: reset
            GpsSample(15_000L, east(250.0)), // re-enters
            GpsSample(20_000L, east(400.0)), // +150 m again: still under the limit
        )
        val result = check(listOf(detourEffect()), track)
        assertTrue(result.violations.isEmpty())
    }

    // --- Multiple simultaneous effects ---------------------------------------------------------------

    @Test
    fun `simultaneous curses are each enforced from the same track`() {
        val track = listOf(
            GpsSample(0L, east(20.0)), // anchors: C4 position, C9 station A
            GpsSample(5_000L, east(180.0)), // >100 m from both anchors, advancing on R1
            GpsSample(10_000L, east(400.0)), // >200 m along the corridor
        )
        val result = check(
            listOf(effect(CardType.STALLED_TRAIN), effect(CardType.TICKET_INSPECTION), detourEffect()),
            track,
        )
        val byType = result.violations.groupBy { it.effectType }
        assertTrue(byType.getValue(CardType.STALLED_TRAIN).isNotEmpty())
        assertTrue(byType.getValue(CardType.TICKET_INSPECTION).isNotEmpty())
        assertTrue(byType.getValue(CardType.DETOUR).isNotEmpty())
    }

    @Test
    fun `violations map to detection and penalty events`() {
        val track = listOf(GpsSample(0L, base), GpsSample(5_000L, east(150.0)))
        val violation = check(listOf(effect(CardType.STALLED_TRAIN)), track).violations.single()
        val events = violation.toEvents()
        assertEquals(2, events.size)
        assertEquals(
            violation.penaltyMinutes,
            (events[1] as io.terminus.core.game.GameEvent.PenaltyApplied).minutes,
        )
    }

    // --- Hider zone compliance (GAME_DESIGN §5.2) ------------------------------------------------------

    @Test
    fun `hider score pauses only after 60 cumulative seconds outside the zone`() {
        // 5 s GPS check ticks; the hider leaves at t=10 s and stays out.
        var memory = HiderZoneMemory()
        var paused = false
        for (t in 0L..80_000L step 5_000L) {
            val pos = if (t < 10_000L) base else east(400.0) // 400 m: beyond the 300 m zone
            val result = EffectEnforcer.checkHiderZone(
                listOf(GpsSample(t, pos)), base, memory, t,
            )
            memory = result.memory
            paused = result.scoreAccrualPaused
            if (t == 65_000L) assertFalse(paused, "55 s outside: still within the 60 s grace")
        }
        assertTrue(paused, "more than 60 s outside must pause accrual")
    }

    @Test
    fun `returning to the zone resumes accrual and resets the accumulator`() {
        val outbound = (0L..75_000L step 5_000L).map { t ->
            GpsSample(t, if (t == 0L) base else east(400.0))
        }
        val out = EffectEnforcer.checkHiderZone(outbound, base, HiderZoneMemory(), 75_000L)
        assertTrue(out.scoreAccrualPaused)

        val back = EffectEnforcer.checkHiderZone(
            outbound + GpsSample(80_000L, east(100.0)), base, out.memory, 80_000L,
        )
        assertFalse(back.scoreAccrualPaused)
        assertEquals(0L, back.memory.excursionMillis)

        // Leaving again starts a fresh 60 s grace.
        val again = EffectEnforcer.checkHiderZone(
            outbound + listOf(GpsSample(80_000L, east(100.0)), GpsSample(85_000L, east(400.0))),
            base, back.memory, 85_000L,
        )
        assertFalse(again.scoreAccrualPaused)
        assertEquals(5_000L, again.memory.excursionMillis)
    }

    @Test
    fun `short excursions under 60 cumulative seconds never pause accrual`() {
        val track = (0L..120_000L step 5_000L).map { t ->
            // 30 s out, 30 s in, repeatedly: each excursion stays under the grace.
            GpsSample(t, if ((t / 30_000L) % 2 == 1L) east(400.0) else base)
        }
        var memory = HiderZoneMemory()
        for (sample in track) {
            val result = EffectEnforcer.checkHiderZone(listOf(sample), base, memory, sample.gameTimeMillis)
            memory = result.memory
            assertFalse(result.scoreAccrualPaused, "at t=${sample.gameTimeMillis}")
        }
    }

    @Test
    fun `hider exactly inside the 300 m radius is compliant`() {
        val result = EffectEnforcer.checkHiderZone(
            listOf(GpsSample(0L, base), GpsSample(120_000L, east(290.0))),
            base, HiderZoneMemory(), 120_000L,
        )
        assertFalse(result.scoreAccrualPaused)
        assertEquals(0L, result.memory.excursionMillis)
    }

    // --- Misc -------------------------------------------------------------------------------------------

    @Test
    fun `non-enforced effect types pass through untouched`() {
        val bag = ActiveEffect(CardType.BIGGER_BAG, startGameMillis = 0L)
        val result = check(listOf(bag), listOf(GpsSample(0L, base), GpsSample(5_000L, east(5_000.0))))
        assertTrue(result.violations.isEmpty())
        assertEquals(listOf(bag), result.effects)
    }

    @Test
    fun `penalty minutes match the §4_2 table`() {
        assertEquals(2.0, EffectEnforcer.penaltyMinutes(CardType.STALLED_TRAIN))
        assertEquals(1.0, EffectEnforcer.penaltyMinutes(CardType.LOCAL_SERVICE))
        assertEquals(1.5, EffectEnforcer.penaltyMinutes(CardType.TICKET_INSPECTION))
        assertEquals(2.0, EffectEnforcer.penaltyMinutes(CardType.DETOUR))
        assertNull(EffectEnforcer.penaltyMinutes(CardType.RUSH_HOUR_DELAY))
    }
}
