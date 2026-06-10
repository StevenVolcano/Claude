package io.terminus.core.game

import io.terminus.core.cards.CardEngine
import io.terminus.core.cards.CardType
import io.terminus.core.cards.Deck
import io.terminus.core.cards.EffectEnforcer
import io.terminus.core.cards.EffectParams
import io.terminus.core.cards.EnforcementMemory
import io.terminus.core.cards.GpsSample
import io.terminus.core.cards.Hand
import io.terminus.core.cards.HiderZoneMemory
import io.terminus.core.cards.ValidationResult
import io.terminus.core.cityfile.CityCodec
import io.terminus.core.cityfile.CityFile
import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.geo.Polygon
import io.terminus.core.persistence.RoundRecord
import io.terminus.core.questions.CooldownStatus
import io.terminus.core.questions.CooldownTracker
import io.terminus.core.questions.QuestionCategory
import io.terminus.core.questions.QuestionEngine
import io.terminus.core.questions.QuestionRules
import io.terminus.core.questions.QuestionSpec
import io.terminus.core.sim.ActorState
import io.terminus.core.sim.MovementPlan
import io.terminus.core.sim.PathPlanner
import io.terminus.core.sim.SimulationEngine
import io.terminus.core.sim.SweepPattern
import io.terminus.core.transit.TransitNetwork

/**
 * The game reducer (ARCHITECTURE.md §1.1 `game`, §2): `reduce(GameState, GameCommand)
 * -> (GameState, List<GameEvent>)`.
 *
 * The reducer is pure with respect to [GameState]; the engine instance additionally
 * owns the **deterministic round runtime** that Phase 0 deliberately kept out of the
 * serialized state: the clipped [TransitNetwork], the [Deck]'s card identities (the
 * state only carries counts), token [ActorState]s, GPS tracks, enforcement memories,
 * and capture/grace bookkeeping. All of it is a pure function of the command stream
 * and the round seed, so two engines fed the same commands produce identical states
 * and event logs (ARCHITECTURE.md §2 "Determinism").
 *
 * One engine instance serves a whole match: [GameCommand.StartRound] from SETUP
 * starts round 0; from ROUND_END it rotates roles and starts the next round (§7).
 *
 * Every event returned by [reduce] is already appended to `GameState.eventLog`.
 */
class GameEngine(private val cityFile: CityFile) {

    /** The round's clipped/filtered network; valid from StartRound on. */
    lateinit var network: TransitNetwork
        private set

    // --- Deterministic round runtime (reset by startRound) -------------------------
    private var deck: Deck = Deck.shuffled(0L)
    private var roundSeed: Long = 0L
    private var startStationId: String = ""
    private var eligibleHidingStationIds: Set<String> = emptySet()
    private val actors = LinkedHashMap<PlayerId, ActorState>()
    private val gpsTracks = LinkedHashMap<PlayerId, MutableList<GpsSample>>()
    private var enforcementMemory = EnforcementMemory()
    private var hiderZoneMemory = HiderZoneMemory()
    private var lastEnforcerCheckMillis = 0L
    private val captureSustainSince = LinkedHashMap<PlayerId, Long>()
    private val sweeps = LinkedHashMap<PlayerId, SweepPattern>()
    private var graceDeadlineMillis: Long? = null
    private var graceTargetStationId: String? = null
    private var graceFailed = false
    private var relocationOverrun = false
    private var relocationPreviousZoneId: String? = null
    private var startLockPenalized = false
    private var survivalMillis = 0L
    private var capturePosition: LatLng? = null
    private var lastRoundScore = 0.0
    private var lastSurvivalMinutes = 0.0

    /** The pure reduction step. Unknown/illegal commands leave the state unchanged. */
    fun reduce(state: GameState, command: GameCommand): Pair<GameState, List<GameEvent>> =
        when (command) {
            is GameCommand.StartRound -> startRound(state)
            is GameCommand.Tick -> tick(state, command.gameMillisDelta)
            is GameCommand.GpsFix -> gpsFix(state, command)
            is GameCommand.AskQuestion -> askQuestion(state, command)
            is GameCommand.AnswerWindowElapsed -> answerWindowElapsed(state)
            is GameCommand.PlayCard -> playCard(state, command)
            is GameCommand.MoveToken -> moveToken(state, command)
            is GameCommand.KeepCards -> keepCards(state, command)
            is GameCommand.DiscardCards -> discardCards(state, command)
            is GameCommand.PauseToggle -> pauseToggle(state)
        }

    // ===================================================================================
    // Round setup (GAME_DESIGN.md §2.1 phase 1, §8)
    // ===================================================================================

    private fun startRound(state: GameState): Pair<GameState, List<GameEvent>> {
        var s = when {
            state.phase == GamePhase.SETUP -> state
            state.phase == GamePhase.ROUND_END && state.roundIndex + 1 < state.config.rounds ->
                freshRoundState(state, state.roundIndex + 1)
            else -> return noChange(state)
        }
        val config = s.config
        roundSeed = resolvedSeed(config, s.roundIndex)
        network = buildNetwork(cityFile, config)
        check(network.stations.isNotEmpty()) { "the configured boundary/filters leave an empty network" }
        startStationId = config.startStationId?.takeIf { it in network.stationsById }
            ?: defaultStartStation(network)
        eligibleHidingStationIds = network.stationsById.keys -
            network.bfsHops(startStationId).filterValues { it <= GameRules.HIDING_EXCLUSION_HOPS }.keys

        // Runtime reset.
        deck = Deck.shuffled(roundSeed)
        actors.clear()
        gpsTracks.clear()
        enforcementMemory = EnforcementMemory()
        hiderZoneMemory = HiderZoneMemory()
        lastEnforcerCheckMillis = 0L
        captureSustainSince.clear()
        sweeps.clear()
        graceDeadlineMillis = null
        graceTargetStationId = null
        graceFailed = false
        relocationOverrun = false
        relocationPreviousZoneId = null
        startLockPenalized = false
        survivalMillis = 0L
        capturePosition = null

        val roles = rotatedRoles(s.players, config, s.roundIndex)
        s = s.copy(
            phase = GamePhase.HIDING,
            gameTimeMillis = 0L,
            roles = roles,
            aiPersonalities = PersonalityAssigner.assign(s.players, config, roundSeed),
            positions = s.players.associate { it.id to PlayerPosition.NodePosition(startStationId) },
            visitHistory = s.players.associate { it.id to listOf(startStationId) },
            deckCount = deck.drawPileSize,
            discardCount = deck.discardPileSize,
        )
        return done(s, listOf(GameEvent.PhaseChanged(GamePhase.SETUP, GamePhase.HIDING, 0L)))
    }

    /** Hider rotation (§7): round r's hider index = (initial hider + r) mod players. */
    private fun rotatedRoles(players: List<Player>, config: GameConfig, roundIndex: Int): Map<PlayerId, Role> {
        val initialHider = if (config.humanRole == Role.HIDER) {
            players.indexOfFirst { it is Player.HumanPlayer }
        } else {
            players.indexOfFirst { it is Player.AiPlayer }
        }
        val hiderIndex = (initialHider + roundIndex) % players.size
        return players.mapIndexed { index, player ->
            player.id to if (index == hiderIndex) Role.HIDER else Role.SEEKER
        }.toMap()
    }

    // ===================================================================================
    // Tick (ARCHITECTURE.md §2 step 2)
    // ===================================================================================

    private fun tick(state: GameState, deltaMillis: Long): Pair<GameState, List<GameEvent>> {
        if (deltaMillis <= 0L || state.paused) return noChange(state)
        if (state.phase == GamePhase.SETUP || state.phase == GamePhase.ROUND_END) return noChange(state)
        val prev = state.gameTimeMillis
        val now = prev + deltaMillis
        var s = state.copy(gameTimeMillis = now)
        val events = mutableListOf<GameEvent>()

        s = advanceActors(s, deltaMillis, events)
        s = expireEffects(s, now, events)
        if (s.phase == GamePhase.HIDING && now >= hidingEndMillis(s.config)) {
            s = endHidingPhase(s, events)
        }
        accrueSurvival(prev, now, s)
        s = checkGrace(s, now)
        s = checkRelocation(s, now, events)
        s = resolveDueAnswers(s, now, events)
        s = checkThermometerResolution(s, now)
        if (s.config.playMode == PlayMode.GPS && now - lastEnforcerCheckMillis >= GameRules.GPS_CHECK_INTERVAL_SECONDS * 1000L) {
            lastEnforcerCheckMillis = now
            s = runGpsChecks(s, now, events)
        }
        s = updateSweeps(s, now)
        s = checkFinalApproach(s, now, events)
        s = checkCapture(s, now, events)
        if (s.phase != GamePhase.ROUND_END && now >= durationMillis(s.config)) {
            s = endRound(s, now, events)
        }
        return done(s, events)
    }

    /** Advances token actors, applying arrivals to positions/visits/thermometer/U-Turn. */
    private fun advanceActors(state: GameState, deltaMillis: Long, events: MutableList<GameEvent>): GameState {
        if (actors.isEmpty()) return state
        var s = state
        val result = SimulationEngine.tick(actors, deltaMillis / 1000.0)
        actors.clear()
        actors.putAll(result.actors)
        val uTurnTargets = CardEngine.uTurnReturnTargets(s)
        for (arrival in result.arrivals) {
            val history = s.visitHistory[arrival.playerId].orEmpty()
            if (history.lastOrNull() != arrival.stationId) {
                s = s.copy(visitHistory = s.visitHistory + (arrival.playerId to history + arrival.stationId))
            }
            s.armedThermometer?.let { armed ->
                if (armed.seekerId == arrival.playerId) {
                    s = s.copy(armedThermometer = armed.copy(edgesMovedSinceArm = armed.edgesMovedSinceArm + 1))
                }
            }
            if (uTurnTargets[arrival.playerId] == arrival.stationId) {
                val cleared = CardEngine.markUTurnReturned(s, arrival.playerId, s.gameTimeMillis)
                s = cleared.state
                events += cleared.events
            }
        }
        var positions = s.positions
        val completed = mutableListOf<PlayerId>()
        for ((playerId, actor) in actors) {
            positions = positions + (playerId to actor.position)
            if (actor.isComplete) completed += playerId
        }
        completed.forEach { actors.remove(it) }
        return s.copy(positions = positions)
    }

    /** Expires timed effects and lifts the corresponding sim-token constraints. */
    private fun expireEffects(state: GameState, now: Long, events: MutableList<GameEvent>): GameState {
        val expiry = CardEngine.expireEffects(state, now)
        events += expiry.events
        for (event in expiry.events) {
            val type = (event as? GameEvent.CurseEnded)?.type ?: continue
            when (type) {
                CardType.STALLED_TRAIN -> setSeekerTokensFrozen(expiry.state, frozen = false)
                CardType.LOCAL_SERVICE -> stripSeekerDwell(expiry.state)
                else -> Unit
            }
        }
        return expiry.state
    }

    private fun setSeekerTokensFrozen(state: GameState, frozen: Boolean) {
        for ((playerId, actor) in actors) {
            if (state.roles[playerId] == Role.SEEKER) actors[playerId] = actor.copy(frozen = frozen)
        }
    }

    private fun stripSeekerDwell(state: GameState) {
        for ((playerId, actor) in actors) {
            if (state.roles[playerId] != Role.SEEKER) continue
            actors[playerId] = actor.copy(
                plan = actor.plan.copy(dwellSecondsByStationId = emptyMap()),
                dwellRemainingSeconds = 0.0,
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Hiding-phase end + grace (GAME_DESIGN.md §2.1 phase 2)
    // ---------------------------------------------------------------------------------

    private fun endHidingPhase(state: GameState, events: MutableList<GameEvent>): GameState {
        val now = state.gameTimeMillis
        val hiderId = hiderOf(state)
        val zone = validZoneOf(state, hiderId)
        var s = state
        if (zone != null) {
            s = s.copy(hiderZoneStationId = zone)
        } else {
            // §2.1: the app picks the nearest valid station; 3:00 grace to enter its zone.
            val hiderPos = latLngOf(s, hiderId)
            graceTargetStationId = eligibleHidingStationIds
                .mapNotNull { network.stationsById[it] }
                .minWithOrNull(compareBy({ GeoMath.haversineMeters(hiderPos, it.latLng) }, { it.id }))
                ?.id
            graceDeadlineMillis = now + GameRules.HIDING_GRACE_MILLIS
        }
        s = s.copy(phase = GamePhase.SEEKING)
        events += GameEvent.PhaseChanged(GamePhase.HIDING, GamePhase.SEEKING, now)
        return s
    }

    /** The hider's valid hiding-zone station right now, or null (§5.1). */
    private fun validZoneOf(state: GameState, hiderId: PlayerId): String? {
        val position = state.positions[hiderId]
        return if (state.config.playMode == PlayMode.SIM) {
            (position as? PlayerPosition.NodePosition)?.stationId?.takeIf { it in eligibleHidingStationIds }
        } else {
            val latLng = latLngOf(state, hiderId)
            eligibleHidingStationIds
                .mapNotNull { network.stationsById[it] }
                .minWithOrNull(compareBy({ GeoMath.haversineMeters(latLng, it.latLng) }, { it.id }))
                ?.takeIf { GeoMath.haversineMeters(latLng, it.latLng) <= GameRules.HIDING_ZONE_RADIUS_METERS }
                ?.id
        }
    }

    private fun checkGrace(state: GameState, now: Long): GameState {
        val deadline = graceDeadlineMillis ?: return state
        val target = graceTargetStationId ?: return state
        val hiderId = hiderOf(state)
        val inZone = if (state.config.playMode == PlayMode.SIM) {
            (state.positions[hiderId] as? PlayerPosition.NodePosition)?.stationId == target
        } else {
            val center = network.stationsById[target]?.latLng
            center != null &&
                GeoMath.haversineMeters(latLngOf(state, hiderId), center) <= GameRules.HIDING_ZONE_RADIUS_METERS
        }
        if (inZone) {
            graceDeadlineMillis = null
            graceTargetStationId = null
            return state.copy(hiderZoneStationId = target)
        }
        if (now >= deadline) {
            // §2.1: failure — score capped at hiding-phase length (applied at scoring).
            graceFailed = true
            graceDeadlineMillis = null
            graceTargetStationId = null
            return state.copy(hiderZoneStationId = target)
        }
        return state
    }

    // ---------------------------------------------------------------------------------
    // Transfer Slip relocation (GAME_DESIGN.md §4.2 C13)
    // ---------------------------------------------------------------------------------

    private fun checkRelocation(state: GameState, now: Long, events: MutableList<GameEvent>): GameState {
        val deadline = state.hiderRelocationDeadlineMillis ?: return state
        var s = state
        val hiderId = hiderOf(s)
        // Re-hide: the hider entered a *new* valid zone during (or after) the window
        // (§4.2 C13: "enter a new valid hiding zone" — the old zone does not count).
        val newZone = validZoneOf(s, hiderId)?.takeIf { it != relocationPreviousZoneId }
        if (newZone != null) {
            s = s.copy(hiderZoneStationId = newZone, hiderRelocationDeadlineMillis = null)
            hiderZoneMemory = HiderZoneMemory()
            relocationPreviousZoneId = null
            if (relocationOverrun) {
                relocationOverrun = false
                s = s.copy(scoreAccrualPaused = false)
                events += GameEvent.ScoreAccrualChanged(paused = false, gameTimeMillis = now)
            }
            return s
        }
        // Overrun: score frozen at the moment the window expired until they re-hide.
        if (now >= deadline && !relocationOverrun) {
            relocationOverrun = true
            s = s.copy(scoreAccrualPaused = true)
            events += GameEvent.ScoreAccrualChanged(paused = true, gameTimeMillis = now)
        }
        return s
    }

    // ---------------------------------------------------------------------------------
    // Survival accrual (GAME_DESIGN.md §7, §5.2)
    // ---------------------------------------------------------------------------------

    private fun accrueSurvival(prevMillis: Long, nowMillis: Long, state: GameState) {
        if (state.phase != GamePhase.SEEKING && state.phase != GamePhase.FINAL_APPROACH) return
        if (state.capturedBy != null || state.scoreAccrualPaused) return
        val from = maxOf(prevMillis, hidingEndMillis(state.config))
        var to = minOf(nowMillis, durationMillis(state.config))
        // C13 overrun freezes the score at the moment the window expired (§4.2 C13).
        state.hiderRelocationDeadlineMillis?.let { to = minOf(to, it) }
        if (to > from) survivalMillis += to - from
    }

    // ---------------------------------------------------------------------------------
    // Question answering pipeline (GAME_DESIGN.md §3, §4.2 C7/C12/C16)
    // ---------------------------------------------------------------------------------

    private fun resolveDueAnswers(state: GameState, now: Long, events: MutableList<GameEvent>): GameState {
        var s = state
        val pending = s.pendingQuestion
        if (pending != null && now >= pending.responseWindowEndsGameMillis) {
            s = computeAnswer(s, now, events)
        }
        val delayed = s.delayedAnswer
        if (delayed != null && now >= delayed.deliverAtGameMillis) {
            s = deliverAnswer(s, delayed.spec, delayed.answer, delayed.wasDecoy, now, events)
        }
        return s
    }

    private fun answerWindowElapsed(state: GameState): Pair<GameState, List<GameEvent>> {
        val pending = state.pendingQuestion ?: return noChange(state)
        if (state.gameTimeMillis < pending.responseWindowEndsGameMillis) return noChange(state)
        val events = mutableListOf<GameEvent>()
        return done(computeAnswer(state, state.gameTimeMillis, events), events)
    }

    /** Window elapsed without veto: compute (decoy-substituted) answer; maybe delay (C7). */
    private fun computeAnswer(state: GameState, now: Long, events: MutableList<GameEvent>): GameState {
        var s = state
        val pending = checkNotNull(s.pendingQuestion)
        val decoyPoint = CardEngine.activeDecoyPoint(s)
        val answerPos = decoyPoint ?: latLngOf(s, hiderOf(s))
        val answer = QuestionEngine.answer(pending.spec, answerPos, network)
        val computation = CardEngine.onAnswerComputed(s, now)
        s = computation.state
        events += computation.events
        return if (computation.delayMillis > 0L) {
            s.copy(
                pendingQuestion = null,
                delayedAnswer = DelayedAnswer(
                    spec = pending.spec,
                    answer = answer,
                    wasDecoy = decoyPoint != null,
                    computedAtGameMillis = now,
                    deliverAtGameMillis = now + computation.delayMillis,
                ),
            )
        } else {
            deliverAnswer(s.copy(pendingQuestion = null), pending.spec, answer, decoyPoint != null, now, events)
        }
    }

    /** Delivery: answer event, decoy reveal, cooldowns (start at delivery), compensation. */
    private fun deliverAnswer(
        state: GameState,
        spec: QuestionSpec,
        answer: io.terminus.core.questions.Answer,
        wasDecoy: Boolean,
        now: Long,
        events: MutableList<GameEvent>,
    ): GameState {
        var s = state
        events += GameEvent.AnswerDelivered(spec, answer, wasDecoy, now)
        val delivery = CardEngine.onAnswerDelivered(s, spec, now)
        s = delivery.state
        events += delivery.events
        s = CooldownTracker.fromGameState(s).recordAnswered(spec.category, now).applyTo(s)
        s = s.copy(
            pendingQuestion = null,
            delayedAnswer = null,
            questionsAnswered = s.questionsAnswered + 1,
        )
        var rule = QuestionEngine.compensationFor(spec)
        if (delivery.doubleCompensation) rule = QuestionEngine.doubled(rule)
        val draw = deck.draw(rule.draw)
        deck = draw.deck
        return s.copy(
            pendingKeep = PendingKeep(draw.drawn, minOf(rule.keep, draw.drawn.size)),
            deckCount = deck.drawPileSize,
            discardCount = deck.discardPileSize,
        )
    }

    /** Q3 resolution: ≥750 m straight-line (GPS) / ≥2 edges (sim) opens the 20 s window. */
    private fun checkThermometerResolution(state: GameState, now: Long): GameState {
        val armed = state.armedThermometer ?: return state
        if (state.pendingQuestion != null || state.delayedAnswer != null) return state
        if (state.phase != GamePhase.SEEKING) return state
        val seekerPos = latLngOf(state, armed.seekerId)
        if (!QuestionEngine.thermometerMayResolve(
                mode = state.config.playMode,
                armPos = armed.armPosition,
                currentPos = seekerPos,
                edgesTraversed = armed.edgesMovedSinceArm,
            )
        ) {
            return state
        }
        val spec = QuestionSpec.Thermometer(armPosition = armed.armPosition, resolvePosition = seekerPos)
        return state.copy(
            armedThermometer = null,
            pendingQuestion = PendingQuestion(
                spec = spec,
                askedBy = armed.seekerId,
                askedAtGameMillis = now,
                responseWindowEndsGameMillis = now + QuestionRules.RESPONSE_WINDOW_MILLIS,
            ),
        )
    }

    // ---------------------------------------------------------------------------------
    // GPS rule checks every 5 s game time (GAME_DESIGN.md §1.3, §4.1, §5.2)
    // ---------------------------------------------------------------------------------

    private fun runGpsChecks(state: GameState, now: Long, events: MutableList<GameEvent>): GameState {
        var s = state
        val human = humanOf(s)
        val track = gpsTracks[human.id].orEmpty()
        if (s.roles[human.id] == Role.SEEKER && track.isNotEmpty()) {
            val result = EffectEnforcer.checkSeeker(
                effects = s.activeEffects,
                seekerId = human.id,
                track = track,
                stations = network.stations,
                routesById = network.routesById,
                memory = enforcementMemory,
                nowGameMillis = now,
            )
            enforcementMemory = result.memory
            s = s.copy(activeEffects = result.effects)
            for (violation in result.violations) {
                events += violation.toEvents()
                s = s.copy(penaltyMinutes = s.penaltyMinutes + violation.penaltyMinutes)
            }
            // C8 U-Turn: the human seeker clears their target within 100 m of it.
            val target = CardEngine.uTurnReturnTargets(s)[human.id]
            val targetLatLng = target?.let { network.stationsById[it]?.latLng }
            val fix = track.lastOrNull()?.position
            if (targetLatLng != null && fix != null &&
                GeoMath.haversineMeters(fix, targetLatLng) <= 100.0
            ) {
                val cleared = CardEngine.markUTurnReturned(s, human.id, now)
                s = cleared.state
                events += cleared.events
            }
        }
        if (s.roles[human.id] == Role.HIDER) {
            val zoneCenter = s.hiderZoneStationId
                ?.takeIf { s.hiderRelocationDeadlineMillis == null && graceDeadlineMillis == null }
                ?.let { network.stationsById[it]?.latLng }
            if (zoneCenter == null) {
                hiderZoneMemory = HiderZoneMemory() // skipped/reset while relocating or pre-zone
            } else if (track.isNotEmpty()) {
                val result = EffectEnforcer.checkHiderZone(track, zoneCenter, hiderZoneMemory, now)
                hiderZoneMemory = result.memory
                if (result.scoreAccrualPaused != s.scoreAccrualPaused && !relocationOverrun) {
                    s = s.copy(scoreAccrualPaused = result.scoreAccrualPaused)
                    events += GameEvent.ScoreAccrualChanged(result.scoreAccrualPaused, now)
                }
            }
        }
        return s
    }

    // ---------------------------------------------------------------------------------
    // Final Approach + capture (GAME_DESIGN.md §2.1 phase 4, §5.3)
    // ---------------------------------------------------------------------------------

    private fun checkFinalApproach(state: GameState, now: Long, events: MutableList<GameEvent>): GameState {
        if (state.phase != GamePhase.SEEKING) return state
        val zone = state.hiderZoneStationId ?: return state
        if (state.hiderRelocationDeadlineMillis != null) return state
        val zoneStation = network.stationsById[zone] ?: return state
        val triggered = state.roles.any { (playerId, role) ->
            if (role != Role.SEEKER) return@any false
            if (state.config.playMode == PlayMode.GPS) {
                GeoMath.haversineMeters(latLngOf(state, playerId), zoneStation.latLng) <=
                    GameRules.FINAL_APPROACH_TRIGGER_METERS
            } else {
                isOnOrAdjacentTo(state.positions[playerId], zone)
            }
        }
        if (!triggered) return state
        events += GameEvent.PhaseChanged(GamePhase.SEEKING, GamePhase.FINAL_APPROACH, now)
        return state.copy(phase = GamePhase.FINAL_APPROACH)
    }

    /** Sim FA trigger: the seeker is at the zone node, an adjacent node, or an incident edge. */
    private fun isOnOrAdjacentTo(position: PlayerPosition?, stationId: String): Boolean = when (position) {
        is PlayerPosition.NodePosition ->
            position.stationId == stationId || network.edges.any {
                (it.fromId == position.stationId && it.toId == stationId) ||
                    (it.fromId == stationId && it.toId == position.stationId)
            }
        is PlayerPosition.EdgePosition ->
            position.fromStationId == stationId || position.toStationId == stationId
        else -> false
    }

    /** GPS-mode Final Approach: AI seekers at the zone station orbit the sweep ring (§5.3). */
    private fun updateSweeps(state: GameState, now: Long): GameState {
        if (state.config.playMode != PlayMode.GPS || state.phase != GamePhase.FINAL_APPROACH) return state
        val zone = state.hiderZoneStationId ?: return state
        var positions = state.positions
        for (player in state.players) {
            if (player !is Player.AiPlayer || state.roles[player.id] != Role.SEEKER) continue
            val existing = sweeps[player.id]
            if (existing == null) {
                val atZone = (state.positions[player.id] as? PlayerPosition.NodePosition)?.stationId == zone
                if (atZone && player.id !in actors) {
                    SweepPattern.around(zone, network, now / 1000.0)?.let { sweeps[player.id] = it }
                }
            }
            sweeps[player.id]?.let { sweep ->
                positions = positions + (player.id to PlayerPosition.GpsPosition(sweep.positionAt(now / 1000.0)))
            }
        }
        return state.copy(positions = positions)
    }

    private fun checkCapture(state: GameState, now: Long, events: MutableList<GameEvent>): GameState {
        if (state.phase != GamePhase.SEEKING && state.phase != GamePhase.FINAL_APPROACH) return state
        val hiderId = hiderOf(state)
        var capturer: PlayerId? = null
        if (state.config.playMode == PlayMode.GPS) {
            val hiderLatLng = latLngOf(state, hiderId)
            for ((playerId, role) in state.roles) {
                if (role != Role.SEEKER) continue
                val distance = GeoMath.haversineMeters(latLngOf(state, playerId), hiderLatLng)
                if (distance <= GameRules.CAPTURE_RADIUS_METERS) {
                    val since = captureSustainSince.getOrPut(playerId) { now }
                    if (now - since >= GameRules.CAPTURE_SUSTAIN_SECONDS * 1000L) {
                        capturer = playerId
                        break
                    }
                } else {
                    captureSustainSince.remove(playerId)
                }
            }
        } else {
            val hiderNode = (state.positions[hiderId] as? PlayerPosition.NodePosition)?.stationId
            if (hiderNode != null) {
                capturer = state.roles.entries.firstOrNull { (playerId, role) ->
                    role == Role.SEEKER &&
                        (state.positions[playerId] as? PlayerPosition.NodePosition)?.stationId == hiderNode
                }?.key
            }
        }
        val seekerId = capturer ?: return state
        capturePosition = latLngOf(state, hiderId)
        var s = state.copy(capturedBy = seekerId, captureGameMillis = now)
        events += GameEvent.Captured(seekerId, hiderId, capturePosition, now)
        s = endRound(s, now, events)
        return s
    }

    // ---------------------------------------------------------------------------------
    // Round end + scoring (GAME_DESIGN.md §2.1 phase 5, §7)
    // ---------------------------------------------------------------------------------

    private fun endRound(state: GameState, now: Long, events: MutableList<GameEvent>): GameState {
        val hiderId = hiderOf(state)
        val captured = state.capturedBy != null
        lastSurvivalMinutes = ScoreKeeper.roundToTenth(survivalMillis / 60_000.0)
        lastRoundScore = ScoreKeeper.roundScore(
            survivalMinutes = survivalMillis / 60_000.0,
            bonusMinutes = state.bonusMinutes,
            penaltyMinutes = state.penaltyMinutes,
            captured = captured,
            graceFailed = graceFailed,
            hidingPhaseMinutes = state.config.hidingPhaseMinutes,
        )
        val previousPhase = state.phase
        val s = state.copy(
            phase = GamePhase.ROUND_END,
            matchScores = state.matchScores +
                (hiderId to (state.matchScores[hiderId] ?: 0.0) + lastRoundScore),
        )
        events += GameEvent.PhaseChanged(previousPhase, GamePhase.ROUND_END, now)
        events += GameEvent.RoundEnded(
            roundIndex = s.roundIndex,
            hiderId = hiderId,
            captured = captured,
            capturedBy = s.capturedBy,
            hiderScore = lastRoundScore,
            gameTimeMillis = now,
        )
        return s
    }

    /**
     * The persisted [RoundRecord] for a finished round (GAME_DESIGN.md §7;
     * ARCHITECTURE.md §5), including the personality reveal for the end screen.
     */
    fun buildRoundRecord(state: GameState): RoundRecord {
        require(state.phase == GamePhase.ROUND_END) { "round is not over" }
        return RoundRecord(
            roundIndex = state.roundIndex,
            seed = roundSeed,
            hiderId = hiderOf(state),
            roles = state.roles,
            aiPersonalities = state.aiPersonalities,
            hidingStationId = state.hiderZoneStationId,
            captured = state.capturedBy != null,
            capturedBy = state.capturedBy,
            captureGameMillis = state.captureGameMillis,
            capturePosition = capturePosition,
            hiderScore = lastRoundScore,
            survivalMinutes = lastSurvivalMinutes,
            bonusMinutes = state.bonusMinutes,
            penaltyMinutes = state.penaltyMinutes,
            questionsAnswered = state.questionsAnswered,
            cardsPlayed = state.cardsPlayed,
            hidingPhaseMillis = hidingEndMillis(state.config),
            durationMillis = state.captureGameMillis ?: minOf(state.gameTimeMillis, durationMillis(state.config)),
            events = state.eventLog,
        )
    }

    // ===================================================================================
    // GPS fixes (ARCHITECTURE.md §1.2 `service`)
    // ===================================================================================

    private fun gpsFix(state: GameState, command: GameCommand.GpsFix): Pair<GameState, List<GameEvent>> {
        if (state.config.playMode != PlayMode.GPS) return noChange(state)
        if (state.players.none { it is Player.HumanPlayer && it.id == command.playerId }) return noChange(state)
        if (state.phase == GamePhase.SETUP || state.phase == GamePhase.ROUND_END) return noChange(state)
        var s = state.copy(positions = state.positions + (command.playerId to PlayerPosition.GpsPosition(command.position)))
        gpsTracks.getOrPut(command.playerId) { mutableListOf() } +=
            GpsSample(command.gameTimeMillis, command.position)
        val events = mutableListOf<GameEvent>()
        // §2.1: a human seeker leaving 150 m of the start station during the hiding phase.
        if (s.phase == GamePhase.HIDING && s.roles[command.playerId] == Role.SEEKER && !startLockPenalized) {
            val start = checkNotNull(network.stationsById[startStationId]).latLng
            if (GeoMath.haversineMeters(command.position, start) > GameRules.START_LOCK_RADIUS_METERS) {
                startLockPenalized = true
                val minutes = GameRules.START_LOCK_PENALTY_MILLIS / 60_000.0
                val reason = "left the start station during the hiding phase"
                events += GameEvent.ViolationDetected(command.playerId, null, reason, s.gameTimeMillis)
                events += GameEvent.PenaltyApplied(command.playerId, minutes, reason, s.gameTimeMillis)
                s = s.copy(penaltyMinutes = s.penaltyMinutes + minutes)
            }
        }
        // Responsive grace-entry and relocation re-hide checks on hider fixes.
        if (s.roles[command.playerId] == Role.HIDER) {
            s = checkGrace(s, s.gameTimeMillis)
            if (s.hiderRelocationDeadlineMillis != null) s = checkRelocation(s, s.gameTimeMillis, events)
        }
        return done(s, events)
    }

    // ===================================================================================
    // Questions (GAME_DESIGN.md §3)
    // ===================================================================================

    private fun askQuestion(state: GameState, command: GameCommand.AskQuestion): Pair<GameState, List<GameEvent>> {
        val now = state.gameTimeMillis
        if (state.phase != GamePhase.SEEKING) return noChange(state) // §2.1: FA disables questions
        if (state.roles[command.seekerId] != Role.SEEKER) return noChange(state)
        if (state.pendingQuestion != null || state.delayedAnswer != null) return noChange(state)
        if (CardEngine.uTurnReturnTargets(state).isNotEmpty()) return noChange(state) // C8 lock
        if (CooldownTracker.fromGameState(state).canAsk(command.spec.category, now) !is CooldownStatus.Allowed) {
            return noChange(state)
        }
        if (!specIsValid(command.spec)) return noChange(state)
        if (command.spec.category == QuestionCategory.THERMOMETER) {
            if (state.armedThermometer != null) return noChange(state)
            val armPos = latLngOf(state, command.seekerId)
            val spec = QuestionSpec.Thermometer(armPosition = armPos)
            return done(
                state.copy(armedThermometer = ArmedThermometer(command.seekerId, armPos, now)),
                listOf(GameEvent.QuestionAsked(command.seekerId, spec, now)),
            )
        }
        return done(
            state.copy(
                pendingQuestion = PendingQuestion(
                    spec = command.spec,
                    askedBy = command.seekerId,
                    askedAtGameMillis = now,
                    responseWindowEndsGameMillis = now + QuestionRules.RESPONSE_WINDOW_MILLIS,
                ),
            ),
            listOf(GameEvent.QuestionAsked(command.seekerId, command.spec, now)),
        )
    }

    private fun specIsValid(spec: QuestionSpec): Boolean = when (spec) {
        is QuestionSpec.CompassCall -> spec.referenceStationId in network.stationsById
        is QuestionSpec.Lineup -> spec.stationIds.size == 3 && spec.stationIds.all { it in network.stationsById }
        is QuestionSpec.RailRange -> spec.referenceStationId in network.stationsById
        is QuestionSpec.LineCheck -> spec.routeId in network.routesById
        is QuestionSpec.Thermometer -> spec.resolvePosition == null
        else -> true
    }

    // ===================================================================================
    // Cards (GAME_DESIGN.md §4)
    // ===================================================================================

    private fun playCard(state: GameState, command: GameCommand.PlayCard): Pair<GameState, List<GameEvent>> {
        val now = state.gameTimeMillis
        if (!engineCardChecks(state, command)) return noChange(state)
        if (CardEngine.validatePlay(state, command.playerId, command.type, command.params)
            !is ValidationResult.Valid
        ) {
            return noChange(state)
        }
        val result = CardEngine.applyPlay(state, command.playerId, command.type, command.params, deck, now)
        deck = result.deck
        var s = result.state
        val events = result.events.toMutableList()
        when (command.type) {
            CardType.STALLED_TRAIN -> setSeekerTokensFrozen(s, frozen = true)
            CardType.TICKET_INSPECTION -> s = snapSeekerTokensToNodes(s)
            CardType.LOCAL_SERVICE -> applySeekerDwell(s)
            CardType.DETOUR -> truncateBannedPlans(s, (command.params as EffectParams.DetourParams).routeId)
            CardType.TRANSFER_SLIP -> s = startRelocation(s, command, previousZoneId = state.hiderZoneStationId)
            else -> Unit
        }
        return done(s, events)
    }

    /** Network-dependent validations CardEngine cannot do itself (its KDoc contract). */
    private fun engineCardChecks(state: GameState, command: GameCommand.PlayCard): Boolean {
        when (command.type) {
            CardType.GHOST_ECHO -> {
                // C12: decoy within 1.5 km of the true position — node/edge positions too.
                val point = (command.params as? EffectParams.DecoyParams)?.point ?: return false
                val truePos = latLngOf(state, hiderOf(state))
                if (GeoMath.haversineMeters(truePos, point) > GameRules.DECOY_MAX_DISTANCE_METERS) return false
            }
            CardType.TRANSFER_SLIP -> if (state.config.playMode == PlayMode.SIM) {
                // C13 sim: target must be a valid hiding node reachable within 5 edges.
                val target = (command.params as? EffectParams.RelocateParams)?.targetStationId ?: return false
                if (target !in eligibleHidingStationIds) return false
                val from = (state.positions[hiderOf(state)] as? PlayerPosition.NodePosition)?.stationId
                    ?: return false
                val path = PathPlanner.plan(network, from, target) ?: return false
                if (path.size > GameRules.RELOCATE_MAX_EDGES) return false
            }
            else -> Unit
        }
        return true
    }

    private fun startRelocation(
        state: GameState,
        command: GameCommand.PlayCard,
        previousZoneId: String?,
    ): GameState {
        hiderZoneMemory = HiderZoneMemory()
        relocationOverrun = false
        relocationPreviousZoneId = previousZoneId
        if (state.config.playMode == PlayMode.SIM) {
            // Move the hider token along the (already validated) ≤5-edge path.
            val hiderId = hiderOf(state)
            val from = (state.positions[hiderId] as? PlayerPosition.NodePosition)?.stationId
            val target = (command.params as EffectParams.RelocateParams).targetStationId
            if (from != null && target != null && from != target) {
                val path = PathPlanner.plan(network, from, target)
                if (!path.isNullOrEmpty()) actors[hiderId] = ActorState(MovementPlan(path))
            }
        }
        return state
    }

    private fun snapSeekerTokensToNodes(state: GameState): GameState {
        var positions = state.positions
        for ((playerId, role) in state.roles) {
            if (role != Role.SEEKER) continue
            val latLng = latLngOf(state, playerId)
            val nearest = network.nearestStation(latLng) ?: continue
            if (playerId in actors || state.positions[playerId] !is PlayerPosition.GpsPosition) {
                actors.remove(playerId)
                positions = positions + (playerId to PlayerPosition.NodePosition(nearest.id))
            }
        }
        return state.copy(positions = positions)
    }

    private fun applySeekerDwell(state: GameState) {
        val dwell = network.stations.associate { it.id to LOCAL_SERVICE_DWELL_SIM_SECONDS }
        for ((playerId, actor) in actors) {
            if (state.roles[playerId] != Role.SEEKER) continue
            actors[playerId] = actor.copy(plan = actor.plan.copy(dwellSecondsByStationId = dwell))
        }
    }

    /** C10: stop in-flight seeker tokens before their first banned-route edge. */
    private fun truncateBannedPlans(state: GameState, bannedRouteId: String) {
        for ((playerId, actor) in actors.entries.toList()) {
            if (state.roles[playerId] != Role.SEEKER) continue
            val edges = actor.plan.edges
            val firstReplannable = if (actor.secondsIntoEdge > 0.0) actor.edgeIndex + 1 else actor.edgeIndex
            val cut = (firstReplannable until edges.size).firstOrNull { edges[it].routeId == bannedRouteId }
                ?: continue
            if (cut <= actor.edgeIndex) {
                actors.remove(playerId)
            } else {
                actors[playerId] = actor.copy(plan = actor.plan.copy(edges = edges.subList(0, cut).toList()))
            }
        }
    }

    private fun keepCards(state: GameState, command: GameCommand.KeepCards): Pair<GameState, List<GameEvent>> {
        if (state.roles[command.playerId] != Role.HIDER) return noChange(state)
        val pending = state.pendingKeep ?: return noChange(state)
        if (command.kept.size != pending.keep) return noChange(state)
        val rest = multisetMinus(pending.drawn, command.kept) ?: return noChange(state)
        deck = deck.discard(rest)
        val added = Hand.add(state.hand, command.kept, state.handLimit)
        // The hand may now exceed the limit; the hider must follow up with DiscardCards.
        val s = state.copy(
            hand = added.hand,
            pendingKeep = null,
            deckCount = deck.drawPileSize,
            discardCount = deck.discardPileSize,
        )
        return done(s, listOf(GameEvent.CardsDrawn(pending.drawn.size, command.kept.size, state.gameTimeMillis)))
    }

    private fun discardCards(state: GameState, command: GameCommand.DiscardCards): Pair<GameState, List<GameEvent>> {
        if (state.roles[command.playerId] != Role.HIDER) return noChange(state)
        if (command.discards.isEmpty()) return noChange(state)
        val remaining = Hand.discard(state.hand, command.discards) ?: return noChange(state)
        deck = deck.discard(command.discards)
        return done(
            state.copy(hand = remaining, deckCount = deck.drawPileSize, discardCount = deck.discardPileSize),
            emptyList(),
        )
    }

    /** Removes [kept] from [drawn] (multiset); null when not a sub-multiset. */
    private fun multisetMinus(drawn: List<CardType>, kept: List<CardType>): List<CardType>? =
        Hand.discard(drawn, kept)

    // ===================================================================================
    // Token movement (GAME_DESIGN.md §1.2, §5.2; ARCHITECTURE.md §1.1 `sim`)
    // ===================================================================================

    private fun moveToken(state: GameState, command: GameCommand.MoveToken): Pair<GameState, List<GameEvent>> {
        if (state.phase == GamePhase.SETUP || state.phase == GamePhase.ROUND_END) return noChange(state)
        if (command.targetStationId !in network.stationsById) return noChange(state)
        val role = state.roles[command.playerId] ?: return noChange(state)
        val player = state.players.firstOrNull { it.id == command.playerId } ?: return noChange(state)
        // GPS-mode humans move physically, never by token.
        if (state.config.playMode == PlayMode.GPS && player is Player.HumanPlayer) return noChange(state)
        when (role) {
            Role.SEEKER -> {
                if (state.phase == GamePhase.HIDING) return noChange(state) // locked at start (§2.1)
                // C4/C9: the simulation enforces movement curses perfectly (§1.3).
                if (state.activeEffects.any {
                        it.type == CardType.STALLED_TRAIN || it.type == CardType.TICKET_INSPECTION
                    }
                ) {
                    return noChange(state)
                }
            }
            Role.HIDER -> {
                // Free during the hiding phase, the §2.1 grace, or a C13 relocation;
                // otherwise locked to the hiding spot (§5.2).
                val mayMove = state.phase == GamePhase.HIDING ||
                    graceDeadlineMillis != null ||
                    state.hiderRelocationDeadlineMillis != null
                if (!mayMove) return noChange(state)
            }
        }
        val bannedRoutes = if (role == Role.SEEKER) {
            state.activeEffects.mapNotNull { (it.params as? EffectParams.DetourParams)?.routeId }.toSet()
        } else {
            emptySet()
        }
        val dwellActive = role == Role.SEEKER &&
            state.activeEffects.any { it.type == CardType.LOCAL_SERVICE }
        val dwell = if (dwellActive) {
            network.stations.associate { it.id to LOCAL_SERVICE_DWELL_SIM_SECONDS }
        } else {
            emptyMap()
        }
        val speed = if (player is Player.AiPlayer && player.difficulty == Difficulty.EASY) 0.85 else 1.0

        val current = actors[command.playerId]
        if (current != null && !current.isComplete && current.secondsIntoEdge > 0.0) {
            // Mid-edge: finish the current edge, then continue along the new path.
            val edge = current.plan.edges[current.edgeIndex]
            val rest = PathPlanner.plan(network, edge.toId, command.targetStationId, bannedRoutes)
                ?: return noChange(state)
            actors[command.playerId] = ActorState(
                plan = MovementPlan(listOf(edge) + rest, dwell),
                edgeIndex = 0,
                secondsIntoEdge = current.secondsIntoEdge,
                dwellRemainingSeconds = 0.0,
                frozen = current.frozen,
                speedMultiplier = speed,
            )
            return done(state, emptyList())
        }
        val fromId = when (val position = state.positions[command.playerId]) {
            is PlayerPosition.NodePosition -> position.stationId
            is PlayerPosition.EdgePosition -> position.fromStationId
            else -> return noChange(state)
        }
        val path = PathPlanner.plan(network, fromId, command.targetStationId, bannedRoutes)
            ?: return noChange(state)
        if (path.isEmpty()) {
            actors.remove(command.playerId)
            return done(
                state.copy(
                    positions = state.positions +
                        (command.playerId to PlayerPosition.NodePosition(command.targetStationId)),
                ),
                emptyList(),
            )
        }
        actors[command.playerId] = ActorState(plan = MovementPlan(path, dwell), speedMultiplier = speed)
        return done(state, emptyList())
    }

    // ===================================================================================
    // Pause (GAME_DESIGN.md §2.2)
    // ===================================================================================

    private fun pauseToggle(state: GameState): Pair<GameState, List<GameEvent>> {
        if (state.config.playMode != PlayMode.SIM) return noChange(state) // GPS is never pausable
        val paused = !state.paused
        return done(
            state.copy(paused = paused),
            listOf(GameEvent.PauseToggled(paused, state.gameTimeMillis)),
        )
    }

    // ===================================================================================
    // Helpers
    // ===================================================================================

    private fun hidingEndMillis(config: GameConfig): Long = config.hidingPhaseMinutes * 60_000L

    private fun durationMillis(config: GameConfig): Long = config.gameDurationMinutes * 60_000L

    private fun hiderOf(state: GameState): PlayerId =
        state.roles.entries.first { it.value == Role.HIDER }.key

    private fun humanOf(state: GameState): Player =
        state.players.first { it is Player.HumanPlayer }

    private fun latLngOf(state: GameState, playerId: PlayerId): LatLng =
        checkNotNull(state.positions[playerId]?.let { SimulationEngine.latLngOf(it, network) }) {
            "no resolvable position for $playerId"
        }

    private fun done(state: GameState, events: List<GameEvent>): Pair<GameState, List<GameEvent>> =
        if (events.isEmpty()) {
            state to emptyList()
        } else {
            state.copy(eventLog = state.eventLog + events) to events
        }

    private fun noChange(state: GameState): Pair<GameState, List<GameEvent>> = state to emptyList()

    companion object {
        /** C5 sim dwell at every node, game seconds (GAME_DESIGN.md §4.2 C5). */
        const val LOCAL_SERVICE_DWELL_SIM_SECONDS: Int = 45

        /** Default player id of the single human participant. */
        val HUMAN_PLAYER_ID: PlayerId = PlayerId("human")

        /**
         * The round seed (GAME_DESIGN.md §8 item 10): the configured seed (or 0 for
         * unset — callers wanting "auto" should set `config.seed` to a timestamp
         * before starting) plus the round index, so every round of a match shuffles
         * and assigns differently but deterministically.
         */
        fun resolvedSeed(config: GameConfig, roundIndex: Int): Long =
            (config.seed ?: 0L) + roundIndex

        /** A fresh pre-round state for [config]: one human plus the configured AIs. */
        fun initialState(config: GameConfig): GameState {
            val players = buildList {
                add(Player.HumanPlayer(HUMAN_PLAYER_ID, "You"))
                config.aiOpponents.forEachIndexed { index, difficulty ->
                    add(Player.AiPlayer(PlayerId("ai-${index + 1}"), "AI ${index + 1}", difficulty))
                }
            }
            return GameState(
                config = config,
                roundIndex = 0,
                phase = GamePhase.SETUP,
                gameTimeMillis = 0L,
                players = players,
                roles = emptyMap(),
                positions = emptyMap(),
            )
        }

        /** Resets every round-scoped field, keeping players, config, and match scores. */
        private fun freshRoundState(state: GameState, roundIndex: Int): GameState = GameState(
            config = state.config,
            roundIndex = roundIndex,
            phase = GamePhase.SETUP,
            gameTimeMillis = 0L,
            players = state.players,
            roles = emptyMap(),
            positions = emptyMap(),
            matchScores = state.matchScores,
        )

        /** §8 item 4 default: the highest-degree interchange (most routes; ties by id). */
        private fun defaultStartStation(network: TransitNetwork): String =
            network.stations
                .sortedBy { it.id }
                .maxByOrNull { it.routeIds.size }!!
                .id

        /** Builds the round's network: clip to the boundary, then apply mode/route filters. */
        fun buildNetwork(cityFile: CityFile, config: GameConfig): TransitNetwork {
            var network = CityCodec.toTransitNetwork(cityFile)
            when (val boundary = config.boundary) {
                is BoundarySpec.FullExtent -> Unit
                is BoundarySpec.PolygonBoundary -> network = network.clipTo(boundary.polygon)
                is BoundarySpec.CircleBoundary ->
                    network = network.clipTo(circlePolygon(boundary.center, boundary.radiusMeters))
            }
            if (config.allowedModes != io.terminus.core.transit.TransitMode.entries.toSet()) {
                network = network.filterModes(config.allowedModes)
            }
            config.allowedRouteIds?.let { network = network.filterRoutes(it) }
            return network
        }

        /** A 64-gon approximation of a circular boundary (GAME_DESIGN.md §8 item 2). */
        private fun circlePolygon(center: LatLng, radiusMeters: Double): Polygon =
            Polygon(
                List(64) { i ->
                    GeoMath.destinationPoint(center, bearingDeg = i * (360.0 / 64), meters = radiusMeters)
                },
            )
    }
}
