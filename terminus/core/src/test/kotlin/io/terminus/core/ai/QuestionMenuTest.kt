package io.terminus.core.ai

import io.terminus.core.questions.PingRadius
import io.terminus.core.questions.QuestionCategory
import io.terminus.core.questions.QuestionSpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Info-gain ranking (ARCHITECTURE.md §6 item 7): on a contrived 2-cluster layout the
 * bisecting Radius Ping wins; Easy picks randomly among the top 3 while Medium takes
 * the argmax (GAME_DESIGN.md §6.3, §6.5).
 */
class QuestionMenuTest {

    private val network = AiTestNetworks.twoClusters()
    private val uniform = CandidateSet.uniform(network.stations.map { it.id })

    private fun fullMenu(): List<MenuEntry> = QuestionMenu.build(
        candidates = uniform,
        seekerPosition = network.stationsById.getValue("A01").latLng,
        seekerStationId = "A01",
        network = network,
        allowedCategories = QuestionCategory.entries.toSet(),
    ) { it.baseCooldownMillis }

    @Test
    fun menuIsAnchoredToTopStationsAndSeeker() {
        val menu = fullMenu()
        assertTrue(menu.size >= 25, "expected a substantial menu, got ${menu.size}")
        assertTrue(menu.any { it.askSpec is QuestionSpec.RadiusPing })
        assertTrue(menu.any { it.askSpec is QuestionSpec.CompassCall })
        assertTrue(menu.any { it.askSpec is QuestionSpec.Thermometer })
        assertTrue(menu.any { it.askSpec is QuestionSpec.LineCheck })
        assertTrue(menu.any { it.askSpec is QuestionSpec.StationDossier })
        assertTrue(menu.any { it.askSpec is QuestionSpec.Lineup })
        assertTrue(menu.any { it.askSpec is QuestionSpec.RailRange })
        // An asked Thermometer is armed: no resolve position on the ask spec.
        val thermo = menu.first { it.askSpec is QuestionSpec.Thermometer }
        assertEquals(null, (thermo.askSpec as QuestionSpec.Thermometer).resolvePosition)
    }

    @Test
    fun blockedCategoriesAreExcluded() {
        val menu = QuestionMenu.build(
            uniform,
            network.stationsById.getValue("A01").latLng,
            "A01",
            network,
            allowedCategories = setOf(QuestionCategory.LINE_CHECK),
        ) { it.baseCooldownMillis }
        assertTrue(menu.all { it.askSpec.category == QuestionCategory.LINE_CHECK })
    }

    @Test
    fun infoGainPicksTheBisectingRadiusPingOnTwoClusters() {
        val scored = InfoGain.score(fullMenu(), uniform, network)
        val picked = QuestionPicker.pick(
            scored, QuestionChoiceStrategy.MAX_INFO_GAIN, temperature = 0.0, rng = Random(1),
        )!!
        // The 8 uniform stations split 4/4 only by a cluster-bisecting Radius Ping:
        // exactly 1 bit of expected entropy reduction, the theoretical max for yes/no.
        assertEquals(1.0, picked.reductionBits, 1e-9)
        assertTrue(picked.entry.askSpec is QuestionSpec.RadiusPing, "expected a Radius Ping, got ${picked.entry.askSpec}")
        val ping = picked.entry.askSpec as QuestionSpec.RadiusPing
        assertTrue(ping.radius == PingRadius.KM2 || ping.radius == PingRadius.KM5)
    }

    @Test
    fun easyPicksRandomOfTop3WhileMediumTakesArgmax() {
        // Contrived menu: scores strictly ordered, identical cooldown cost.
        val specs = listOf("R1", "R2", "R3", "R4", "R5").map { QuestionSpec.LineCheck(it) }
        val scored = specs.mapIndexed { i, spec ->
            ScoredEntry(MenuEntry(spec, spec, 60_000L), reductionBits = 5.0 - i, gainPerCost = 5.0 - i)
        }

        // Medium (MAX_INFO_GAIN) at temperature 0 always takes the best entry.
        repeat(20) { seed ->
            val pick = QuestionPicker.pick(
                scored, QuestionChoiceStrategy.MAX_INFO_GAIN, temperature = 0.0, rng = Random(seed),
            )!!
            assertEquals(scored[0], pick)
        }

        // Easy picks uniformly among the top 3 only — and not always the best.
        val picks = (0 until 60).map { seed ->
            QuestionPicker.pick(
                scored, QuestionChoiceStrategy.RANDOM_OF_TOP_3, temperature = 0.0, rng = Random(seed),
            )!!
        }
        assertTrue(picks.all { it in scored.take(3) }, "Easy must stay within the top 3")
        assertTrue(picks.toSet().size > 1, "Easy must actually randomize among the top 3")
    }

    @Test
    fun highTemperatureSpreadsChoicesAndZeroTemperatureIsDeterministic() {
        val scored = InfoGain.score(fullMenu(), uniform, network)
        val hot = (0 until 40).map { seed ->
            QuestionPicker.pick(scored, QuestionChoiceStrategy.MAX_GAIN_PER_COST, 2.0, Random(seed))!!
        }
        assertTrue(hot.toSet().size > 3, "Rat-grade temperature 2.0 should be near-uniform")
        val cold = (0 until 10).map { seed ->
            QuestionPicker.pick(scored, QuestionChoiceStrategy.MAX_GAIN_PER_COST, 0.0, Random(seed))!!
        }
        assertEquals(1, cold.toSet().size)
    }
}
