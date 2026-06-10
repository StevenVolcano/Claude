package io.terminus.core.game

import io.terminus.core.cards.CardType
import io.terminus.core.cityfile.CityCodec
import io.terminus.core.cityfile.CityFile
import io.terminus.core.geo.LatLng

/**
 * Shared reducer test harness: a [GameEngine] over the checked-in Demoville city
 * (36 stations, start DV-A07 "Central Cross") plus a current state and a cumulative
 * event list.
 *
 * Demoville facts used throughout the tests:
 * - hops from DV-A07: DV-A09 is 2 hops (excluded as a hiding zone, §5.1);
 *   DV-A10 is 3 hops (eligible), DV-A12 is 5 hops (eligible).
 * - Line A edge times: A07→A10 = 105+150+120 = 375 s; A10→A12 = 90+135 = 225 s.
 * - Line A stations sit 750 m apart on a constant latitude.
 */
class EngineHarness(config: GameConfig) {
    val cityFile: CityFile = demovilleCity
    val engine = GameEngine(cityFile)
    var state: GameState = GameEngine.initialState(config)
        private set
    val events = mutableListOf<GameEvent>()

    val humanId: PlayerId = GameEngine.HUMAN_PLAYER_ID
    val ai1: PlayerId = PlayerId("ai-1")

    val hiderId: PlayerId get() = state.roles.entries.first { it.value == Role.HIDER }.key
    val seekerIds: List<PlayerId> get() = state.roles.filterValues { it == Role.SEEKER }.keys.toList()

    fun send(command: GameCommand): List<GameEvent> {
        val (next, emitted) = engine.reduce(state, command)
        state = next
        events += emitted
        return emitted
    }

    fun start(): List<GameEvent> = send(GameCommand.StartRound)

    fun tick(gameMillis: Long): List<GameEvent> = send(GameCommand.Tick(gameMillis))

    /** Ticks [seconds] in 1 s steps (capture-sustain granularity). */
    fun tickSecondsStepped(seconds: Int): List<GameEvent> {
        val emitted = mutableListOf<GameEvent>()
        repeat(seconds) { emitted += tick(1_000L) }
        return emitted
    }

    fun stationLatLng(stationId: String): LatLng =
        engine.network.stationsById.getValue(stationId).latLng

    /** Replaces the hider's hand (tests hand-craft hands; the deck only tracks counts). */
    fun giveHand(vararg cards: CardType) {
        state = state.copy(hand = cards.toList())
    }

    fun playCard(playerId: PlayerId, type: CardType, params: io.terminus.core.cards.EffectParams? = null) =
        send(GameCommand.PlayCard(playerId, type, params, state.gameTimeMillis))

    fun moveToken(playerId: PlayerId, target: String) =
        send(GameCommand.MoveToken(playerId, target, state.gameTimeMillis))

    fun gpsFix(playerId: PlayerId, position: LatLng, atGameMillis: Long = state.gameTimeMillis) =
        send(GameCommand.GpsFix(playerId, position, null, atGameMillis))

    companion object {
        val demovilleCity: CityFile by lazy {
            val stream = checkNotNull(
                EngineHarness::class.java.getResourceAsStream("/cities/demoville.city.json.gz"),
            ) { "demoville test resource missing" }
            CityCodec.decode(stream)
        }

        /** Sim-mode base config used by most reducer tests. */
        fun simConfig(
            seed: Long = 42L,
            gameDurationMinutes: Int = 20,
            hidingPhaseMinutes: Int = 10,
            humanRole: Role = Role.HIDER,
            aiOpponents: List<Difficulty> = listOf(Difficulty.MEDIUM),
            rounds: Int = 1,
        ): GameConfig = GameConfig(
            cityId = "demoville",
            startStationId = "DV-A07",
            playMode = PlayMode.SIM,
            gameDurationMinutes = gameDurationMinutes,
            hidingPhaseMinutes = hidingPhaseMinutes,
            aiOpponents = aiOpponents,
            humanRole = humanRole,
            rounds = rounds,
            seed = seed,
        )

        /** GPS-mode base config. */
        fun gpsConfig(
            seed: Long = 42L,
            gameDurationMinutes: Int = 20,
            hidingPhaseMinutes: Int = 5,
            humanRole: Role = Role.HIDER,
            aiOpponents: List<Difficulty> = listOf(Difficulty.MEDIUM),
        ): GameConfig = GameConfig(
            cityId = "demoville",
            startStationId = "DV-A07",
            playMode = PlayMode.GPS,
            gameDurationMinutes = gameDurationMinutes,
            hidingPhaseMinutes = hidingPhaseMinutes,
            aiOpponents = aiOpponents,
            humanRole = humanRole,
            seed = seed,
        )
    }
}

/** Drives a sim-mode human hider to [stationId] and through the hiding-phase end. */
fun EngineHarness.hideAtAndStartSeeking(stationId: String) {
    moveToken(hiderId, stationId)
    tick(state.config.hidingPhaseMinutes * 60_000L)
    check(state.phase == GamePhase.SEEKING) { "expected SEEKING, was ${state.phase}" }
}
