package io.terminus.app.ui.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.terminus.app.R
import io.terminus.app.ui.common.ChoiceRow
import io.terminus.app.ui.common.StationPickerField
import io.terminus.app.ui.common.formatClock
import io.terminus.app.ui.common.formatDistance
import io.terminus.app.ui.common.questionCategoryName
import io.terminus.app.ui.common.transitModeName
import io.terminus.core.cards.CardType
import io.terminus.core.cityfile.CityFile
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameState
import io.terminus.core.game.PlayerId
import io.terminus.core.game.Role
import io.terminus.core.questions.CompassAxis
import io.terminus.core.questions.DossierAttribute
import io.terminus.core.questions.PingRadius
import io.terminus.core.questions.QuestionCategory
import io.terminus.core.questions.QuestionSpec
import io.terminus.core.questions.RailRangeHops
import io.terminus.core.sim.SimulationEngine
import io.terminus.core.transit.RouteLine
import io.terminus.core.transit.TransitMode
import io.terminus.core.transit.TransitNetwork

/**
 * The seekers' question sheet (ARCHITECTURE.md §1.2 `ui.game` QuestionPanel;
 * GAME_DESIGN.md §3): a category grid with per-category cooldown countdowns,
 * parameter pickers per category Q1–Q7, and the global-cooldown / curse lockout
 * states. Only the human seeker asks here; AI seekers ask through their brains.
 */
@Composable
fun QuestionPanel(
    state: GameState,
    city: CityFile,
    network: TransitNetwork,
    humanPlayerId: PlayerId,
    send: (GameCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = state.gameTimeMillis

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.roles[humanPlayerId] != Role.SEEKER) {
            Text(stringResource(R.string.questions_seekers_only))
            return@Column
        }
        if (state.phase == GamePhase.HIDING) {
            Text(stringResource(R.string.questions_locked_hiding))
            return@Column
        }
        if (state.phase == GamePhase.FINAL_APPROACH || state.phase == GamePhase.ROUND_END) {
            Text(stringResource(R.string.questions_locked_final))
            return@Column
        }

        // Lockouts and shared cooldowns (GAME_DESIGN.md §3 general rules, §4.2 C6/C18).
        val deadZone = state.activeEffects.firstOrNull { it.type == CardType.DEAD_ZONE }
        val tunnelVision = state.activeEffects.any { it.type == CardType.TUNNEL_VISION }
        val globalRemaining = state.globalCooldownUntilMillis - now
        if (deadZone != null) {
            val remaining = deadZone.expiryGameMillis?.minus(now) ?: 0L
            Text(
                stringResource(R.string.questions_dead_zone, formatClock(remaining.coerceAtLeast(0))),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.pendingQuestion != null) {
            Text(stringResource(R.string.questions_pending))
        }
        if (globalRemaining > 0) {
            Text(stringResource(R.string.questions_global_cooldown, formatClock(globalRemaining)))
        }
        if (state.armedThermometer != null) {
            Text(stringResource(R.string.questions_thermometer_armed))
        }

        var selected by rememberSaveable { mutableStateOf<QuestionCategory?>(null) }

        QuestionCategory.entries.forEach { category ->
            val categoryRemaining = (state.categoryCooldownUntilMillis[category] ?: 0L) - now
            val disabledByCurse = category == QuestionCategory.RADIUS_PING && tunnelVision
            val enabled = categoryRemaining <= 0 && !disabledByCurse && deadZone == null &&
                globalRemaining <= 0 && state.pendingQuestion == null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        selected = if (selected == category) null else category
                    }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = questionCategoryName(category),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
                Text(
                    text = when {
                        disabledByCurse -> stringResource(R.string.questions_tunnel_vision)
                        categoryRemaining > 0 ->
                            stringResource(R.string.questions_ready_in, formatClock(categoryRemaining))
                        else -> stringResource(R.string.questions_ready)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (selected == category && enabled) {
                QuestionParameters(
                    category = category,
                    state = state,
                    city = city,
                    network = network,
                    humanPlayerId = humanPlayerId,
                    onAsk = { spec ->
                        send(GameCommand.AskQuestion(humanPlayerId, spec, state.gameTimeMillis))
                        selected = null
                    },
                )
            }
        }
    }
}

/** Parameter pickers and the Ask button for one selected category (GAME_DESIGN.md §3 table). */
@Composable
private fun QuestionParameters(
    category: QuestionCategory,
    state: GameState,
    city: CityFile,
    network: TransitNetwork,
    humanPlayerId: PlayerId,
    onAsk: (QuestionSpec) -> Unit,
) {
    val myPosition = state.positions[humanPlayerId]?.let { SimulationEngine.latLngOf(it, network) }
    val allowedRoutes = remember(city, state.config) {
        city.routes.filter { route ->
            route.mode in state.config.allowedModes &&
                (state.config.allowedRouteIds?.contains(route.id) != false)
        }
    }

    Column(
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (category) {
            QuestionCategory.RADIUS_PING -> {
                var radiusIndex by rememberSaveable { mutableStateOf(2) }
                var centerStationId by rememberSaveable { mutableStateOf<String?>(null) }
                ChoiceRow(
                    options = PingRadius.entries.map { formatDistance(it.meters) },
                    selectedIndex = radiusIndex,
                    onSelect = { radiusIndex = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (centerStationId == null) {
                        Button(onClick = {}) { Text(stringResource(R.string.q_center_seeker_position)) }
                    } else {
                        OutlinedButton(onClick = { centerStationId = null }) {
                            Text(stringResource(R.string.q_center_seeker_position))
                        }
                    }
                    StationPickerField(
                        label = stringResource(R.string.questions_center_station),
                        stations = city.stations,
                        selectedStationId = centerStationId,
                        onSelect = { centerStationId = it.id },
                    )
                }
                val center = centerStationId?.let { id ->
                    network.stationsById[id]?.latLng
                } ?: myPosition
                AskButton(enabled = center != null) {
                    onAsk(
                        QuestionSpec.RadiusPing(
                            center = center!!,
                            centerStationId = centerStationId,
                            radius = PingRadius.entries[radiusIndex],
                        ),
                    )
                }
            }

            QuestionCategory.COMPASS_CALL -> {
                var stationId by rememberSaveable { mutableStateOf<String?>(null) }
                var axisIndex by rememberSaveable { mutableStateOf(0) }
                StationPickerField(
                    label = stringResource(R.string.questions_reference_station),
                    stations = city.stations,
                    selectedStationId = stationId,
                    onSelect = { stationId = it.id },
                )
                ChoiceRow(
                    options = listOf(
                        stringResource(R.string.questions_axis_ns),
                        stringResource(R.string.questions_axis_ew),
                    ),
                    selectedIndex = axisIndex,
                    onSelect = { axisIndex = it },
                )
                AskButton(enabled = stationId != null) {
                    onAsk(
                        QuestionSpec.CompassCall(
                            referenceStationId = stationId!!,
                            axis = CompassAxis.entries[axisIndex],
                        ),
                    )
                }
            }

            QuestionCategory.THERMOMETER -> {
                Text(
                    stringResource(R.string.questions_thermometer_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                AskButton(enabled = myPosition != null && state.armedThermometer == null) {
                    onAsk(QuestionSpec.Thermometer(armPosition = myPosition!!))
                }
            }

            QuestionCategory.LINE_CHECK -> {
                var routeId by rememberSaveable { mutableStateOf<String?>(null) }
                RoutePickerField(
                    label = stringResource(R.string.questions_route),
                    routes = allowedRoutes,
                    selectedRouteId = routeId,
                    onSelect = { routeId = it.id },
                )
                AskButton(enabled = routeId != null) {
                    onAsk(QuestionSpec.LineCheck(routeId = routeId!!))
                }
            }

            QuestionCategory.STATION_DOSSIER -> {
                val zones = remember(city) {
                    city.stations.mapNotNull { it.zoneId }.distinct().sorted()
                }
                var attrIndex by rememberSaveable { mutableStateOf(0) }
                var modeIndex by rememberSaveable { mutableStateOf(0) }
                var zoneIndex by rememberSaveable { mutableStateOf(0) }
                // The zone option is hidden when the feed provided no zones (GAME_DESIGN.md §3 Q5d).
                val options = buildList {
                    add(stringResource(R.string.questions_dossier_interchange))
                    add(stringResource(R.string.questions_dossier_terminus))
                    add(stringResource(R.string.questions_dossier_mode))
                    if (zones.isNotEmpty()) add(stringResource(R.string.questions_dossier_zone))
                }
                ChoiceRow(
                    options = options,
                    selectedIndex = attrIndex,
                    onSelect = { attrIndex = it },
                )
                if (attrIndex == 2) {
                    ChoiceRow(
                        options = TransitMode.entries.map { transitModeName(it) },
                        selectedIndex = modeIndex,
                        onSelect = { modeIndex = it },
                    )
                }
                if (attrIndex == 3 && zones.isNotEmpty()) {
                    ChoiceRow(
                        options = zones,
                        selectedIndex = zoneIndex.coerceIn(0, zones.size - 1),
                        onSelect = { zoneIndex = it },
                    )
                }
                AskButton(enabled = true) {
                    val attribute = when (attrIndex) {
                        0 -> DossierAttribute.Interchange
                        1 -> DossierAttribute.Terminus
                        2 -> DossierAttribute.Mode(TransitMode.entries[modeIndex])
                        else -> if (zones.isEmpty()) {
                            DossierAttribute.Interchange
                        } else {
                            DossierAttribute.Zone(zones[zoneIndex.coerceIn(0, zones.size - 1)])
                        }
                    }
                    onAsk(QuestionSpec.StationDossier(attribute = attribute))
                }
            }

            QuestionCategory.LINEUP -> {
                var first by rememberSaveable { mutableStateOf<String?>(null) }
                var second by rememberSaveable { mutableStateOf<String?>(null) }
                var third by rememberSaveable { mutableStateOf<String?>(null) }
                StationPickerField(
                    label = stringResource(R.string.questions_lineup_station, 1),
                    stations = city.stations,
                    selectedStationId = first,
                    onSelect = { first = it.id },
                )
                StationPickerField(
                    label = stringResource(R.string.questions_lineup_station, 2),
                    stations = city.stations,
                    selectedStationId = second,
                    onSelect = { second = it.id },
                )
                StationPickerField(
                    label = stringResource(R.string.questions_lineup_station, 3),
                    stations = city.stations,
                    selectedStationId = third,
                    onSelect = { third = it.id },
                )
                val ids = listOfNotNull(first, second, third)
                AskButton(enabled = ids.size == 3 && ids.distinct().size == 3) {
                    onAsk(QuestionSpec.Lineup(stationIds = ids))
                }
            }

            QuestionCategory.RAIL_RANGE -> {
                var stationId by rememberSaveable { mutableStateOf<String?>(null) }
                var hopsIndex by rememberSaveable { mutableStateOf(1) }
                StationPickerField(
                    label = stringResource(R.string.questions_reference_station),
                    stations = city.stations,
                    selectedStationId = stationId,
                    onSelect = { stationId = it.id },
                )
                ChoiceRow(
                    options = RailRangeHops.entries.map {
                        stringResource(R.string.questions_hops, it.hops)
                    },
                    selectedIndex = hopsIndex,
                    onSelect = { hopsIndex = it },
                )
                AskButton(enabled = stationId != null) {
                    onAsk(
                        QuestionSpec.RailRange(
                            referenceStationId = stationId!!,
                            hops = RailRangeHops.entries[hopsIndex],
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AskButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        Text(stringResource(R.string.questions_ask))
    }
}

/** A field that opens a route-line picker dialog, mirroring [StationPickerField]. */
@Composable
fun RoutePickerField(
    label: String,
    routes: List<RouteLine>,
    selectedRouteId: String?,
    onSelect: (RouteLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val selectedName = routes.firstOrNull { it.id == selectedRouteId }?.shortName
        ?: stringResource(R.string.picker_none_selected)
    OutlinedButton(onClick = { showDialog = true }, modifier = modifier) {
        Text("$label: $selectedName")
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    routes.forEach { route ->
                        Text(
                            text = "${route.shortName} — ${route.longName}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(route)
                                    showDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
