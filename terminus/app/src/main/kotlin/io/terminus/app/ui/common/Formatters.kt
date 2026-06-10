package io.terminus.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.terminus.app.R
import io.terminus.core.cards.CardType
import io.terminus.core.cityfile.CityFile
import io.terminus.core.game.AiPersonalityMode
import io.terminus.core.game.GameEvent
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameState
import io.terminus.core.game.PlayerId
import io.terminus.core.questions.Answer
import io.terminus.core.questions.CompassAxis
import io.terminus.core.questions.CompassDirectionValue
import io.terminus.core.questions.DossierAttribute
import io.terminus.core.questions.QuestionCategory
import io.terminus.core.questions.QuestionSpec
import io.terminus.core.transit.TransitMode

/** "12:34" / "1:02:34" from game-time millis. */
fun formatClock(gameTimeMillis: Long): String {
    val totalSeconds = gameTimeMillis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "2 km" / "500 m" from meters. */
fun formatDistance(meters: Int): String =
    if (meters >= 1000) "${meters / 1000} km" else "$meters m"

/** Resolves station/route ids to display names against the active city. */
class NameResolver(city: CityFile?) {
    private val stations = city?.stations?.associateBy { it.id }.orEmpty()
    private val routes = city?.routes?.associateBy { it.id }.orEmpty()

    fun station(id: String?): String = id?.let { stations[it]?.name } ?: id.orEmpty()
    fun route(id: String?): String = id?.let { routes[it]?.shortName } ?: id.orEmpty()
}

/**
 * Display name with the personality flavor required by the setup option
 * (GAME_DESIGN.md §6.4): hidden personalities show as "Vera (?)", revealed/manual as
 * "Vera (The Ghost)". [revealAll] forces the reveal (end screen).
 */
fun playerDisplayName(state: GameState, playerId: PlayerId, revealAll: Boolean = false): String {
    val player = state.players.firstOrNull { it.id == playerId } ?: return playerId.value
    if (player is io.terminus.core.game.Player.HumanPlayer) return player.name
    val personality = state.aiPersonalities[playerId]
    val reveal = revealAll || state.config.aiPersonalityMode != AiPersonalityMode.HIDDEN
    return when {
        personality != null && reveal -> "${player.name} (${personality.displayName})"
        else -> "${player.name} (?)"
    }
}

@Composable
fun phaseName(phase: GamePhase): String = when (phase) {
    GamePhase.SETUP -> stringResource(R.string.phase_setup)
    GamePhase.HIDING -> stringResource(R.string.phase_hiding)
    GamePhase.SEEKING -> stringResource(R.string.phase_seeking)
    GamePhase.FINAL_APPROACH -> stringResource(R.string.phase_final_approach)
    GamePhase.ROUND_END -> stringResource(R.string.phase_round_end)
}

@Composable
fun transitModeName(mode: TransitMode): String = when (mode) {
    TransitMode.METRO -> stringResource(R.string.mode_metro)
    TransitMode.TRAM -> stringResource(R.string.mode_tram)
    TransitMode.BUS -> stringResource(R.string.mode_bus)
    TransitMode.RAIL -> stringResource(R.string.mode_rail)
    TransitMode.FERRY -> stringResource(R.string.mode_ferry)
}

@Composable
fun questionCategoryName(category: QuestionCategory): String = when (category) {
    QuestionCategory.RADIUS_PING -> stringResource(R.string.q_cat_radius_ping)
    QuestionCategory.COMPASS_CALL -> stringResource(R.string.q_cat_compass_call)
    QuestionCategory.THERMOMETER -> stringResource(R.string.q_cat_thermometer)
    QuestionCategory.LINE_CHECK -> stringResource(R.string.q_cat_line_check)
    QuestionCategory.STATION_DOSSIER -> stringResource(R.string.q_cat_station_dossier)
    QuestionCategory.LINEUP -> stringResource(R.string.q_cat_lineup)
    QuestionCategory.RAIL_RANGE -> stringResource(R.string.q_cat_rail_range)
}

/** Player-facing phrasing of a question, e.g. "Are you within 2 km of Central Cross?". */
@Composable
fun questionText(spec: QuestionSpec, names: NameResolver): String = when (spec) {
    is QuestionSpec.RadiusPing -> stringResource(
        R.string.q_text_radius_ping,
        formatDistance(spec.radius.meters),
        spec.centerStationId?.let { names.station(it) }
            ?: stringResource(R.string.q_center_seeker_position),
    )
    is QuestionSpec.CompassCall -> when (spec.axis) {
        CompassAxis.NORTH_SOUTH ->
            stringResource(R.string.q_text_compass_ns, names.station(spec.referenceStationId))
        CompassAxis.EAST_WEST ->
            stringResource(R.string.q_text_compass_ew, names.station(spec.referenceStationId))
    }
    is QuestionSpec.Thermometer -> stringResource(R.string.q_text_thermometer)
    is QuestionSpec.LineCheck -> stringResource(R.string.q_text_line_check, names.route(spec.routeId))
    is QuestionSpec.StationDossier -> when (val attr = spec.attribute) {
        DossierAttribute.Interchange -> stringResource(R.string.q_text_dossier_interchange)
        DossierAttribute.Terminus -> stringResource(R.string.q_text_dossier_terminus)
        is DossierAttribute.Mode -> stringResource(R.string.q_text_dossier_mode, transitModeName(attr.mode))
        is DossierAttribute.Zone -> stringResource(R.string.q_text_dossier_zone, attr.zoneId)
    }
    is QuestionSpec.Lineup -> stringResource(
        R.string.q_text_lineup,
        spec.stationIds.joinToString(", ") { names.station(it) },
    )
    is QuestionSpec.RailRange -> stringResource(
        R.string.q_text_rail_range,
        spec.hops.hops,
        names.station(spec.referenceStationId),
    )
}

@Composable
fun answerText(answer: Answer): String = when (answer) {
    is Answer.YesNo ->
        if (answer.value) stringResource(R.string.answer_yes) else stringResource(R.string.answer_no)
    is Answer.CompassDirection -> when (answer.direction) {
        CompassDirectionValue.NORTH -> stringResource(R.string.answer_north)
        CompassDirectionValue.SOUTH -> stringResource(R.string.answer_south)
        CompassDirectionValue.EAST -> stringResource(R.string.answer_east)
        CompassDirectionValue.WEST -> stringResource(R.string.answer_west)
    }
    is Answer.WarmerColder ->
        if (answer.warmer) stringResource(R.string.answer_warmer) else stringResource(R.string.answer_colder)
}

/** Effect text for a card, from strings keyed by the [CardType] metadata. */
@Composable
fun cardEffectText(type: CardType): String = when (type) {
    CardType.RUSH_HOUR_DELAY -> stringResource(R.string.card_effect_rush_hour_delay)
    CardType.EXPRESS_SKIP -> stringResource(R.string.card_effect_express_skip)
    CardType.NIGHT_OWL_SERVICE -> stringResource(R.string.card_effect_night_owl_service)
    CardType.STALLED_TRAIN -> stringResource(R.string.card_effect_stalled_train)
    CardType.LOCAL_SERVICE -> stringResource(R.string.card_effect_local_service)
    CardType.TUNNEL_VISION -> stringResource(R.string.card_effect_tunnel_vision)
    CardType.SCRAMBLED_SIGNAL -> stringResource(R.string.card_effect_scrambled_signal)
    CardType.U_TURN -> stringResource(R.string.card_effect_u_turn)
    CardType.TICKET_INSPECTION -> stringResource(R.string.card_effect_ticket_inspection)
    CardType.DETOUR -> stringResource(R.string.card_effect_detour)
    CardType.CONDUCTORS_OVERRIDE -> stringResource(R.string.card_effect_conductors_override)
    CardType.GHOST_ECHO -> stringResource(R.string.card_effect_ghost_echo)
    CardType.TRANSFER_SLIP -> stringResource(R.string.card_effect_transfer_slip)
    CardType.LOST_AND_FOUND -> stringResource(R.string.card_effect_lost_and_found)
    CardType.FOUND_WALLET -> stringResource(R.string.card_effect_found_wallet)
    CardType.OFF_PEAK_PASS -> stringResource(R.string.card_effect_off_peak_pass)
    CardType.BIGGER_BAG -> stringResource(R.string.card_effect_bigger_bag)
    CardType.DEAD_ZONE -> stringResource(R.string.card_effect_dead_zone)
    CardType.SERVICE_CHANGE -> stringResource(R.string.card_effect_service_change)
    CardType.GOLDEN_TICKET -> stringResource(R.string.card_effect_golden_ticket)
}

/** Human-readable one-liner for the ActivityLog. */
@Composable
fun eventText(event: GameEvent, state: GameState, names: NameResolver): String = when (event) {
    is GameEvent.PhaseChanged ->
        stringResource(R.string.event_phase_changed, phaseName(event.to))
    is GameEvent.QuestionAsked -> stringResource(
        R.string.event_question_asked,
        playerDisplayName(state, event.seekerId),
        questionText(event.spec, names),
    )
    is GameEvent.AnswerDelivered ->
        stringResource(R.string.event_answer_delivered, answerText(event.answer))
    is GameEvent.AnswerVetoed -> stringResource(R.string.event_answer_vetoed)
    is GameEvent.DecoyRevealed -> stringResource(R.string.event_decoy_revealed)
    is GameEvent.CardPlayed -> stringResource(
        R.string.event_card_played,
        playerDisplayName(state, event.playerId),
        event.type.displayName,
    )
    is GameEvent.CardsDrawn ->
        stringResource(R.string.event_cards_drawn, event.drawn, event.kept)
    is GameEvent.CurseStarted ->
        stringResource(R.string.event_curse_started, event.type.displayName)
    is GameEvent.CurseEnded ->
        stringResource(R.string.event_curse_ended, event.type.displayName)
    is GameEvent.ViolationDetected -> stringResource(
        R.string.event_violation,
        playerDisplayName(state, event.playerId),
        event.description,
    )
    is GameEvent.PenaltyApplied -> stringResource(
        R.string.event_penalty,
        "%.1f".format(event.minutes),
        event.reason,
    )
    is GameEvent.HiderRelocating -> stringResource(R.string.event_hider_relocating)
    is GameEvent.ScoreAccrualChanged ->
        if (event.paused) {
            stringResource(R.string.event_score_paused)
        } else {
            stringResource(R.string.event_score_resumed)
        }
    is GameEvent.Captured -> stringResource(
        R.string.event_captured,
        playerDisplayName(state, event.seekerId),
        playerDisplayName(state, event.hiderId),
    )
    is GameEvent.RoundEnded -> stringResource(
        R.string.event_round_ended,
        event.roundIndex + 1,
        playerDisplayName(state, event.hiderId),
        "%.1f".format(event.hiderScore),
    )
    is GameEvent.PauseToggled ->
        if (event.paused) {
            stringResource(R.string.event_paused)
        } else {
            stringResource(R.string.event_resumed)
        }
}
