package io.terminus.core.ai

import io.terminus.core.game.PlayMode
import io.terminus.core.geo.GeoMath
import io.terminus.core.questions.Answer
import io.terminus.core.questions.CompassAxis
import io.terminus.core.questions.CompassDirectionValue
import io.terminus.core.questions.DossierAttribute
import io.terminus.core.questions.PingRadius
import io.terminus.core.questions.QuestionSpec
import io.terminus.core.questions.RailRangeHops
import io.terminus.core.transit.TransitNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CandidateSet updates per answer type (ARCHITECTURE.md §6 item 7), including the
 * GPS 300 m hiding-disc consistency rule of GAME_DESIGN.md §6.3 and decoy handling.
 *
 * Stations are placed at exact haversine distances east of a fixed center.
 */
class CandidateSetTest {

    private val center = AiTestNetworks.BASE

    // Distances east of center: chosen around the 1 km ping boundary ± the 300 m disc.
    private val s500 = AiTestNetworks.stationAt("S500", 500.0)
    private val s900 = AiTestNetworks.stationAt("S900", 900.0)
    private val s1200 = AiTestNetworks.stationAt("S1200", 1_200.0)
    private val s1400 = AiTestNetworks.stationAt("S1400", 1_400.0)
    private val s5000 = AiTestNetworks.stationAt("S5000", 5_000.0)
    private val network: TransitNetwork =
        AiTestNetworks.chain(listOf(s500, s900, s1200, s1400, s5000))

    private val uniform = CandidateSet.uniform(network.stations.map { it.id })
    private val ping1km = QuestionSpec.RadiusPing(center = center, radius = PingRadius.KM1)
    private fun yes(v: Boolean) = Answer.YesNo(v)

    @Test
    fun uniformInitializationAndEntropy() {
        assertEquals(5, uniform.weights.size)
        uniform.weights.values.forEach { assertEquals(0.2, it, 1e-12) }
        assertEquals(2.3219, uniform.entropy(), 1e-3) // log2(5)
    }

    @Test
    fun radiusPingYesGpsUsesTheHidingDisc() {
        // Hard (eps 0), GPS: a station is consistent if any point of its 300 m disc is.
        val updated = uniform.updated(ping1km, yes(true), false, DifficultyProfile.HARD, network, PlayMode.GPS)
        // 1200 - 300 = 900 <= 1000: disc reaches inside -> still consistent.
        assertTrue(updated.weightOf("S1200") > 0.0, "disc straddling the radius must stay consistent")
        // 1400 - 300 = 1100 > 1000: the whole disc is outside -> eliminated.
        assertEquals(0.0, updated.weightOf("S1400"))
        assertEquals(0.0, updated.weightOf("S5000"))
        assertTrue(updated.weightOf("S500") > 0.0)
        assertEquals(1.0, updated.weights.values.sum(), 1e-12)
    }

    @Test
    fun radiusPingYesSimTestsTheNodeExactly() {
        val updated = uniform.updated(ping1km, yes(true), false, DifficultyProfile.HARD, network, PlayMode.SIM)
        // Sim mode: the node itself must be within 1 km.
        assertEquals(0.0, updated.weightOf("S1200"))
        assertTrue(updated.weightOf("S900") > 0.0)
        assertTrue(updated.weightOf("S500") > 0.0)
    }

    @Test
    fun radiusPingNoGpsKeepsDiscStraddlers() {
        val updated = uniform.updated(ping1km, yes(false), false, DifficultyProfile.HARD, network, PlayMode.GPS)
        // 900 + 300 = 1200 > 1000: part of the disc is outside -> consistent with "No".
        assertTrue(updated.weightOf("S900") > 0.0)
        // 500 + 300 = 800 <= 1000: the whole disc is inside -> inconsistent with "No".
        assertEquals(0.0, updated.weightOf("S500"))
        assertTrue(updated.weightOf("S5000") > 0.0)
    }

    @Test
    fun mediumEpsilonDownWeightsInsteadOfEliminating() {
        val updated = uniform.updated(ping1km, yes(true), false, DifficultyProfile.MEDIUM, network, PlayMode.SIM)
        // Inconsistent stations keep epsilon = 0.05 of their weight before renormalization.
        val consistentWeight = updated.weightOf("S500")
        val inconsistentWeight = updated.weightOf("S5000")
        assertTrue(inconsistentWeight > 0.0)
        assertEquals(0.05, inconsistentWeight / consistentWeight, 1e-9)
    }

    @Test
    fun decoyAnswersAreDiscardedByMediumAndHardButHalfAppliedByEasy() {
        val medium = uniform.updated(ping1km, yes(true), true, DifficultyProfile.MEDIUM, network, PlayMode.SIM)
        val hard = uniform.updated(ping1km, yes(true), true, DifficultyProfile.HARD, network, PlayMode.SIM)
        assertEquals(uniform.weights, medium.weights, "Medium must discard decoy-flagged answers")
        assertEquals(uniform.weights, hard.weights, "Hard must discard decoy-flagged answers")

        val easy = uniform.updated(ping1km, yes(true), true, DifficultyProfile.EASY, network, PlayMode.SIM)
        // Easy applies decoys at epsilon 0.5.
        assertEquals(0.5, easy.weightOf("S5000") / easy.weightOf("S500"), 1e-9)
    }

    @Test
    fun compassCallGpsDiscRule() {
        // Station 200 m south of the reference: its disc still reaches 300 m north.
        val ref = AiTestNetworks.stationAt("REF", 3_000.0)
        val south = AiTestNetworks.station(
            "SOUTH",
            GeoMath.destinationPoint(ref.latLng, 180.0, 200.0),
        )
        val net = AiTestNetworks.chain(listOf(ref, south))
        val set = CandidateSet.uniform(listOf("REF", "SOUTH"))
        val spec = QuestionSpec.CompassCall("REF", CompassAxis.NORTH_SOUTH)
        val north = Answer.CompassDirection(CompassDirectionValue.NORTH)

        val gps = set.updated(spec, north, false, DifficultyProfile.HARD, net, PlayMode.GPS)
        assertTrue(gps.weightOf("SOUTH") > 0.0, "disc reaching north of the reference stays consistent")

        val sim = set.updated(spec, north, false, DifficultyProfile.HARD, net, PlayMode.SIM)
        assertEquals(0.0, sim.weightOf("SOUTH"), "sim mode tests the node exactly")
        assertEquals(1.0, sim.weightOf("REF"), 1e-12) // tie resolves North (>=)
    }

    @Test
    fun thermometerGpsDiscRule() {
        // Arm 2000 m and resolve 2400 m from the station: the node says "Colder",
        // but some disc point could still be warmer (2400 - 300 < 2000 + 300).
        val station = AiTestNetworks.stationAt("S", 0.0)
        val far = AiTestNetworks.stationAt("FAR", 8_000.0)
        val net = AiTestNetworks.chain(listOf(station, far))
        val arm = GeoMath.destinationPoint(station.latLng, 90.0, 2_000.0)
        val resolve = GeoMath.destinationPoint(station.latLng, 90.0, 2_400.0)
        val spec = QuestionSpec.Thermometer(arm, resolve)
        val warmer = Answer.WarmerColder(warmer = true)
        val set = CandidateSet.uniform(listOf("S", "FAR"))

        val gps = set.updated(spec, warmer, false, DifficultyProfile.HARD, net, PlayMode.GPS)
        assertTrue(gps.weightOf("S") > 0.0)

        val sim = set.updated(spec, warmer, false, DifficultyProfile.HARD, net, PlayMode.SIM)
        assertEquals(0.0, sim.weightOf("S"))
    }

    @Test
    fun nearestStationQuestionsFilterExactly() {
        val withRoute = AiTestNetworks.stationAt(
            "ONROUTE", 0.0, routeIds = listOf("R1", "R2"), interchange = true,
        )
        val offRoute = AiTestNetworks.stationAt("OFFROUTE", 1_000.0)
        val far = AiTestNetworks.stationAt("FARAWAY", 2_000.0)
        val net = AiTestNetworks.chain(listOf(withRoute, offRoute, far))
        val set = CandidateSet.uniform(listOf("ONROUTE", "OFFROUTE", "FARAWAY"))

        val lineYes = set.updated(
            QuestionSpec.LineCheck("R2"), Answer.YesNo(true), false,
            DifficultyProfile.HARD, net, PlayMode.GPS,
        )
        assertEquals(1.0, lineYes.weightOf("ONROUTE"), 1e-12)

        val lineupNo = set.updated(
            QuestionSpec.Lineup(listOf("ONROUTE", "OFFROUTE", "FARAWAY")), Answer.YesNo(false), false,
            DifficultyProfile.HARD, net, PlayMode.GPS,
        )
        // Nothing is consistent with "not one of all three" -> reset to uniform, not collapse.
        assertEquals(set.weights, lineupNo.weights)

        val dossier = set.updated(
            QuestionSpec.StationDossier(DossierAttribute.Interchange), Answer.YesNo(false), false,
            DifficultyProfile.HARD, net, PlayMode.GPS,
        )
        assertEquals(0.0, dossier.weightOf("ONROUTE")) // the only interchange, answer was "No"
        assertTrue(dossier.weightOf("OFFROUTE") > 0.0)

        val rail = set.updated(
            QuestionSpec.RailRange("ONROUTE", RailRangeHops.TWO), Answer.YesNo(false), false,
            DifficultyProfile.HARD, net, PlayMode.GPS,
        )
        // Everything is within 2 hops on a 3-chain: inconsistent everywhere -> uniform reset.
        assertEquals(set.weights, rail.weights)
    }

    @Test
    fun topWeightedIsDeterministicWithTies() {
        assertEquals(
            listOf("S1200", "S1400", "S500"),
            uniform.topWeighted(3).map { it.first },
        )
    }
}
