package io.terminus.core.ai

import io.terminus.core.cards.CardType
import io.terminus.core.cityfile.CityCodec
import io.terminus.core.game.AiPersonality
import io.terminus.core.game.Difficulty
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GameConfig
import io.terminus.core.game.GameEvent
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameState
import io.terminus.core.game.PendingKeep
import io.terminus.core.game.PendingQuestion
import io.terminus.core.game.PlayMode
import io.terminus.core.game.Player
import io.terminus.core.game.PlayerId
import io.terminus.core.game.PlayerPosition
import io.terminus.core.game.Role
import io.terminus.core.geo.LatLng
import io.terminus.core.questions.PingRadius
import io.terminus.core.questions.QuestionEngine
import io.terminus.core.questions.QuestionSpec
import io.terminus.core.transit.TransitNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral differentiation and determinism (ARCHITECTURE.md §6 item 7): on the same
 * scripted timeline a BOOKKEEPER seeker asks strictly more questions than a
 * BLOODHOUND; RAT runs are reproducible per seed; the hider obeys §6.2 heuristics.
 */
class BrainBehaviorTest {

    private val seekerId = PlayerId("ai-seeker")
    private val hiderId = PlayerId("ai-hider")

    private fun network(): TransitNetwork = AiTestNetworks.twoClusters()

    private fun baseState(
        network: TransitNetwork,
        seekerStation: String = "A01",
        hiderStation: String = "B03",
        phase: GamePhase = GamePhase.SEEKING,
    ): GameState = GameState(
        config = GameConfig(
            cityId = "test",
            startStationId = "A01",
            playMode = PlayMode.SIM,
            gameDurationMinutes = 60,
            hidingPhaseMinutes = 10,
            seed = 99L,
        ),
        roundIndex = 0,
        phase = phase,
        gameTimeMillis = 0L,
        players = listOf(
            Player.AiPlayer(seekerId, "Seeker", Difficulty.MEDIUM),
            Player.AiPlayer(hiderId, "Hider", Difficulty.MEDIUM),
        ),
        roles = mapOf(seekerId to Role.SEEKER, hiderId to Role.HIDER),
        positions = mapOf(
            seekerId to PlayerPosition.NodePosition(seekerStation),
            hiderId to PlayerPosition.NodePosition(hiderStation),
        ),
        hiderZoneStationId = hiderStation,
    )

    /**
     * Scripted seek: ticks the brain over [totalMinutes]; every AskQuestion is answered
     * truthfully and immediately (cooldowns applied as the engine would), every
     * MoveToken teleports the seeker (movement detail is irrelevant here).
     */
    private fun runScriptedSeek(
        brain: TerminusAiBrain,
        network: TransitNetwork,
        totalMinutes: Int,
    ): Pair<Int, List<GameCommand>> {
        val hiderLatLng = network.stationsById.getValue("B03").latLng
        var state = baseState(network)
        var questions = 0
        val commands = ArrayList<GameCommand>()
        var t = 0L
        while (t < totalMinutes * 60_000L) {
            t += brain.decisionTickMillis
            state = state.copy(gameTimeMillis = t)
            for (cmd in brain.decide(state, t)) {
                commands.add(cmd)
                when (cmd) {
                    is GameCommand.AskQuestion -> {
                        questions++
                        val spec = resolveForAnswer(cmd.spec, network)
                        val answer = QuestionEngine.answer(spec, hiderLatLng, network)
                        state = state.copy(
                            globalCooldownUntilMillis = t + 120_000L,
                            categoryCooldownUntilMillis = state.categoryCooldownUntilMillis +
                                (spec.category to t + spec.category.baseCooldownMillis),
                            eventLog = state.eventLog + listOf(
                                GameEvent.QuestionAsked(brain.playerId, cmd.spec, gameTimeMillis = t),
                                GameEvent.AnswerDelivered(spec, answer, gameTimeMillis = t),
                            ),
                            questionsAnswered = state.questionsAnswered + 1,
                        )
                    }
                    is GameCommand.MoveToken -> {
                        state = state.copy(
                            positions = state.positions +
                                (brain.playerId to PlayerPosition.NodePosition(cmd.targetStationId)),
                        )
                    }
                    else -> {}
                }
            }
        }
        return questions to commands
    }

    /** An armed Thermometer can only be answered once resolved; resolve it at A04. */
    private fun resolveForAnswer(spec: QuestionSpec, network: TransitNetwork): QuestionSpec =
        if (spec is QuestionSpec.Thermometer && spec.resolvePosition == null) {
            spec.copy(resolvePosition = network.stationsById.getValue("A04").latLng)
        } else {
            spec
        }

    @Test
    fun bookkeeperAsksStrictlyMoreQuestionsThanBloodhound() {
        val net = network()
        val (bookkeeperQuestions, _) = runScriptedSeek(
            createSeekerBrain(seekerId, Difficulty.MEDIUM, AiPersonality.BOOKKEEPER, net, seed = 5L),
            net,
            totalMinutes = 50,
        )
        val (bloodhoundQuestions, _) = runScriptedSeek(
            createSeekerBrain(seekerId, Difficulty.MEDIUM, AiPersonality.BLOODHOUND, net, seed = 5L),
            net,
            totalMinutes = 50,
        )
        assertTrue(
            bookkeeperQuestions > bloodhoundQuestions,
            "BOOKKEEPER ($bookkeeperQuestions) must ask strictly more than BLOODHOUND ($bloodhoundQuestions)",
        )
        // questionRate 1.0 means the Bookkeeper asks at essentially every legal window.
        assertTrue(bookkeeperQuestions >= 15, "BOOKKEEPER asked only $bookkeeperQuestions questions")
    }

    @Test
    fun ratSeekerIsReproduciblePerSeed() {
        val net = network()
        val runA = runScriptedSeek(
            createSeekerBrain(seekerId, Difficulty.MEDIUM, AiPersonality.RAT, net, seed = 11L), net, 30,
        )
        val runB = runScriptedSeek(
            createSeekerBrain(seekerId, Difficulty.MEDIUM, AiPersonality.RAT, net, seed = 11L), net, 30,
        )
        assertEquals(runA.second, runB.second, "identical seeds must replay identically")

        val runC = runScriptedSeek(
            createSeekerBrain(seekerId, Difficulty.MEDIUM, AiPersonality.RAT, net, seed = 12L), net, 30,
        )
        assertTrue(runA.second != runC.second, "different seeds should diverge for the Rat")
    }

    @Test
    fun ratHiderIsReproduciblePerSeed() {
        val net = network()
        fun run(seed: Long): List<GameCommand> {
            val brain = createHiderBrain(hiderId, Difficulty.MEDIUM, AiPersonality.RAT, net, seed)
            val hiding = baseState(net, hiderStation = "A01", phase = GamePhase.HIDING)
            return (1..5).flatMap { tick ->
                brain.decide(hiding.copy(gameTimeMillis = tick * brain.decisionTickMillis), tick * brain.decisionTickMillis)
            }
        }
        assertEquals(run(21L), run(21L))
    }

    @Test
    fun hiderMovesToAnEligibleSpotDuringTheHidingPhase() {
        val net = network()
        val brain = createHiderBrain(hiderId, Difficulty.MEDIUM, AiPersonality.GHOST, net, seed = 3L)
        val state = baseState(net, hiderStation = "A01", phase = GamePhase.HIDING)
        val commands = brain.decide(state, 25_000L)
        val move = commands.filterIsInstance<GameCommand.MoveToken>().single()
        val eligible = HidingEligibility.eligibleHidingStationIds(net, "A01")
        assertTrue(move.targetStationId in eligible, "${move.targetStationId} is not an eligible hiding zone")
        // Repeated ticks do not re-issue the same move command.
        assertTrue(brain.decide(state, 50_000L).isEmpty())
    }

    @Test
    fun hiderKeepsCardsByPriorityOrder() {
        val net = network()
        val brain = createHiderBrain(hiderId, Difficulty.MEDIUM, AiPersonality.BOOKKEEPER, net, seed = 3L)
        val state = baseState(net, hiderStation = "B03").copy(
            pendingKeep = PendingKeep(
                drawn = listOf(CardType.RUSH_HOUR_DELAY, CardType.CONDUCTORS_OVERRIDE, CardType.LOST_AND_FOUND),
                keep = 1,
            ),
        )
        val keep = brain.decide(state, 25_000L).filterIsInstance<GameCommand.KeepCards>().single()
        // §6.2 keep priority: C11 Conductor's Override beats everything else drawn here.
        assertEquals(listOf(CardType.CONDUCTORS_OVERRIDE), keep.kept)
    }

    @Test
    fun mediumHiderVetoesAFiveHundredMeterPingFromTheFixedList() {
        val net = network()
        val brain = createHiderBrain(hiderId, Difficulty.MEDIUM, AiPersonality.BOOKKEEPER, net, seed = 3L)
        val spec = QuestionSpec.RadiusPing(
            center = net.stationsById.getValue("A01").latLng,
            radius = PingRadius.M500,
        )
        val state = baseState(net, hiderStation = "B03").copy(
            hand = listOf(CardType.CONDUCTORS_OVERRIDE),
            pendingQuestion = PendingQuestion(
                spec = spec,
                askedBy = seekerId,
                askedAtGameMillis = 24_000L,
                responseWindowEndsGameMillis = 44_000L,
            ),
            gameTimeMillis = 25_000L,
        )
        val play = brain.decide(state, 25_000L).filterIsInstance<GameCommand.PlayCard>().single()
        assertEquals(CardType.CONDUCTORS_OVERRIDE, play.type)
    }

    @Test
    fun seekerHonorsUTurnComplianceBeforeAnythingElse() {
        val net = network()
        val brain = createSeekerBrain(seekerId, Difficulty.MEDIUM, AiPersonality.BOOKKEEPER, net, seed = 4L)
        val state = baseState(net).copy(
            activeEffects = listOf(
                io.terminus.core.cards.ActiveEffect(
                    type = CardType.U_TURN,
                    startGameMillis = 0L,
                    params = io.terminus.core.cards.UTurnParams(mapOf(seekerId.value to "A03")),
                ),
            ),
        )
        val commands = brain.decide(state, 25_000L)
        // No questions while under U-Turn; the only command is the return move.
        assertTrue(commands.none { it is GameCommand.AskQuestion })
        assertEquals("A03", (commands.single() as GameCommand.MoveToken).targetStationId)
    }

    @Test
    fun brainsAreDeterministicOnADemoCity() {
        val city = CityCodec.decode(
            requireNotNull(javaClass.getResourceAsStream("/cities/demoville.city.json.gz")) {
                "missing demoville test resource"
            },
        )
        val net = CityCodec.toTransitNetwork(city)
        val start = city.defaultStartStationId
        fun run(): List<GameCommand> {
            val brain = createHiderBrain(hiderId, Difficulty.HARD, AiPersonality.GHOST, net, seed = 42L)
            val state = baseState(net, hiderStation = start, phase = GamePhase.HIDING).copy(
                config = GameConfig(cityId = city.cityId, startStationId = start, playMode = PlayMode.SIM, seed = 42L),
                positions = mapOf(
                    seekerId to PlayerPosition.NodePosition(start),
                    hiderId to PlayerPosition.NodePosition(start),
                ),
                hiderZoneStationId = null,
            )
            return brain.decide(state, 15_000L)
        }
        assertEquals(run(), run())
        assertTrue(run().filterIsInstance<GameCommand.MoveToken>().isNotEmpty())
    }
}
