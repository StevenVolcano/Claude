package io.terminus.core.ai

import io.terminus.core.geo.GeoMath
import io.terminus.core.transit.RouteLine
import io.terminus.core.transit.TransitMode
import io.terminus.core.transit.TransitNetwork
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hider spot scoring (GAME_DESIGN.md §6.1, §6.4; ARCHITECTURE.md §6 item 7):
 * unreachable stations are excluded; the Ghost never picks an interchange or a
 * terminus; Easy uses travel time only.
 */
class SpotScorerTest {

    /**
     * Chain S0–S6 (120 s per edge) plus an isolated station X (no edges).
     * From start S0: S1/S2 are excluded as hiding zones (≤ 2 hops); X is unreachable.
     */
    private fun chainWithIsland(): TransitNetwork {
        val stations = (0..6).map { i ->
            AiTestNetworks.stationAt(
                "S$i",
                meters = i * 800.0,
                interchange = i == 4,
                terminus = i == 0 || i == 6,
            )
        } + AiTestNetworks.station(
            "X",
            GeoMath.destinationPoint(AiTestNetworks.BASE, 0.0, 3_000.0),
            routeIds = emptyList(),
        )
        val chain = AiTestNetworks.chain(stations.take(7))
        return TransitNetwork(stations, chain.routes, chain.edges)
    }

    @Test
    fun unreachableAndTooFarStationsAreExcluded() {
        // Hiding phase 10 min -> cap 510 s: S3 (360 s) and S4 (480 s) qualify;
        // S5 (600 s) and S6 (720 s) cannot be reached in time; X is unreachable.
        val scored = SpotScorer.scoreSpots(
            chainWithIsland(), "S0", hidingPhaseSeconds = 600,
            difficulty = DifficultyProfile.MEDIUM,
            personality = PersonalityProfile.BLOODHOUND,
            rng = Random(7),
        )
        assertEquals(setOf("S3", "S4"), scored.map { it.stationId }.toSet())
    }

    @Test
    fun isolatedStationIsNeverChosenAtAnyDifficultyOrSeed() {
        val network = chainWithIsland()
        for (difficulty in listOf(DifficultyProfile.EASY, DifficultyProfile.MEDIUM, DifficultyProfile.HARD)) {
            for (seed in 0L until 20L) {
                val spot = SpotScorer.chooseSpot(
                    network, "S0", 1_200, difficulty, PersonalityProfile.SHOWMAN, Random(seed),
                )
                assertTrue(spot != null && spot != "X", "$difficulty seed $seed picked $spot")
            }
        }
    }

    @Test
    fun ghostNeverPicksAnInterchangeOrTerminus() {
        // Hiding phase 20 min: S3..S6 all reachable. S4 is an interchange, S6 a terminus.
        val network = chainWithIsland()
        for (seed in 0L until 40L) {
            val spot = SpotScorer.chooseSpot(
                network, "S0", hidingPhaseSeconds = 1_200,
                difficulty = DifficultyProfile.MEDIUM,
                personality = PersonalityProfile.GHOST,
                rng = Random(seed),
            )!!
            val station = network.stationsById.getValue(spot)
            assertTrue(!station.isInterchange && !station.isTerminus, "Ghost picked salient $spot (seed $seed)")
        }
    }

    @Test
    fun hardRejectsStationsNamedAsLineTermini() {
        val network = chainWithIsland()
        // The chain's route ends are S0 and S6; S6 is eligible and reachable in 20 min
        // but Hard must reject it by name salience (GAME_DESIGN.md §6.1).
        for (seed in 0L until 30L) {
            val spot = SpotScorer.chooseSpot(
                network, "S0", 1_200, DifficultyProfile.HARD, PersonalityProfile.SHOWMAN, Random(seed),
            )
            assertTrue(spot != "S6", "Hard picked the line terminus S6 (seed $seed)")
        }
    }

    @Test
    fun easyPicksFromTopHalfByTravelTimeOnly() {
        // 20 min phase: eligible+reachable = S3..S6 with T strictly increasing.
        // Top 50% by T = {S6, S5}.
        val network = chainWithIsland()
        val picks = (0L until 30L).map { seed ->
            SpotScorer.chooseSpot(
                network, "S0", 1_200, DifficultyProfile.EASY, PersonalityProfile.SHOWMAN, Random(seed),
            )!!
        }
        assertTrue(picks.all { it == "S5" || it == "S6" }, "Easy strayed outside the top half: $picks")
        assertTrue(picks.toSet().size == 2, "Easy should randomize within the top half")
    }

    @Test
    fun spotWeightsOverrideChangesTheRanking() {
        // Two-route network where remoteness dominates for one station.
        val near = AiTestNetworks.stationAt("NEAR", 900.0)
        val mid = AiTestNetworks.stationAt("MID", 1_800.0)
        val far = AiTestNetworks.stationAt("FAR", 2_700.0)
        val remote = AiTestNetworks.station(
            "REMOTE",
            GeoMath.destinationPoint(AiTestNetworks.BASE, 0.0, 2_000.0),
        )
        val start = AiTestNetworks.stationAt("START", 0.0)
        val chain = AiTestNetworks.chain(listOf(start, near, mid, far))
        // REMOTE hangs off the end of the chain so it stays > 2 hops from START.
        val edges = chain.edges + AiTestNetworks.bothWays("FAR", "REMOTE", "R2", 240)
        val routes = chain.routes + RouteLine("R2", "R2", "R2", TransitMode.BUS, "#00FF00", listOf("FAR", "REMOTE"))
        val network = TransitNetwork(chain.stations + remote, routes, edges)

        // Pure remoteness weights must rank the geographically isolated REMOTE first.
        val remotenessOnly = PersonalityProfile.GHOST.copy(
            spotWeights = SpotWeights(0.0, 1.0, 0.0, 0.0),
            rejectsSalientSpots = false,
            obscurityBias = 0.0,
        )
        val scored = SpotScorer.scoreSpots(
            network, "START", 3_600, DifficultyProfile.MEDIUM, remotenessOnly, Random(1),
        )
        assertEquals("REMOTE", scored.first().stationId)
    }
}
