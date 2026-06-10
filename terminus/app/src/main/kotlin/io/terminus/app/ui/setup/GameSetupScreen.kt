package io.terminus.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.terminus.app.R
import io.terminus.app.map.MapOverlays
import io.terminus.app.map.OsmMap
import io.terminus.app.ui.common.ChoiceRow
import io.terminus.app.ui.common.SectionCard
import io.terminus.app.ui.common.StationPickerField
import io.terminus.app.ui.common.transitModeName
import io.terminus.app.vm.BoundaryChoice
import io.terminus.app.vm.CityViewModel
import io.terminus.app.vm.SetupViewModel
import io.terminus.core.cityfile.CityFile
import io.terminus.core.game.AiPersonality
import io.terminus.core.game.AiPersonalityMode
import io.terminus.core.game.BoundarySpec
import io.terminus.core.game.CooldownMultiplier
import io.terminus.core.game.Difficulty
import io.terminus.core.game.PlayMode
import io.terminus.core.game.Role
import io.terminus.core.geo.LatLng
import io.terminus.core.transit.TransitMode

/** Sim-mode time scale options (GAME_DESIGN.md §2.2, §8 item 5). */
private val TIME_SCALES = listOf(1, 2, 5, 10, 30)

/** Match length options (GAME_DESIGN.md §7, §8 item 9). */
private val ROUND_OPTIONS = listOf(1, 3, 5)

/**
 * The Game Setup screen, exactly the eleven options of GAME_DESIGN.md §8
 * (ARCHITECTURE.md §1.2 `ui.setup`): city, boundary (polygon/circle editor on an
 * osmdroid map), per-mode and per-route transit toggles, start station, play mode +
 * sim time scale, durations, cooldown multiplier, AI opponents (difficulty +
 * personality Hidden/Revealed/Manual + human role), rounds, RNG seed, and presets.
 */
@Composable
fun GameSetupScreen(
    setupVm: SetupViewModel,
    cityVm: CityViewModel,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val ui by setupVm.ui.collectAsStateWithLifecycle()
    val cities by cityVm.cities.collectAsStateWithLifecycle()
    val presets by setupVm.presets.collectAsStateWithLifecycle()
    val city = cities.firstOrNull { it.cityId == ui.cityId }
    val errors = setupVm.validate(city)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }

        // 1. City -----------------------------------------------------------
        SectionCard(stringResource(R.string.setup_city)) {
            if (cities.isEmpty()) {
                Text(stringResource(R.string.setup_no_cities))
            }
            cities.forEach { candidate ->
                val label = if (candidate.isSynthetic) {
                    stringResource(R.string.cities_demo_suffix, candidate.displayName)
                } else {
                    candidate.displayName
                }
                if (candidate.cityId == ui.cityId) {
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = {
                            // City changed: drop city-specific selections.
                            setupVm.update {
                                it.copy(
                                    cityId = candidate.cityId,
                                    startStationId = null,
                                    disabledModes = emptySet(),
                                    disabledRouteIds = emptySet(),
                                    polygonVertices = emptyList(),
                                    circleCenter = null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label)
                    }
                }
            }
        }

        // 2. Boundary -------------------------------------------------------
        SectionCard(stringResource(R.string.setup_boundary)) {
            ChoiceRow(
                options = listOf(
                    stringResource(R.string.setup_boundary_full),
                    stringResource(R.string.setup_boundary_polygon),
                    stringResource(R.string.setup_boundary_circle),
                ),
                selectedIndex = ui.boundaryChoice.ordinal,
                onSelect = { index ->
                    setupVm.update { it.copy(boundaryChoice = BoundaryChoice.entries[index]) }
                },
            )
            if (city != null && ui.boundaryChoice != BoundaryChoice.FULL_EXTENT) {
                BoundaryEditor(setupVm = setupVm, city = city)
            }
        }

        // 3. Allowed transit --------------------------------------------------
        if (city != null) {
            SectionCard(stringResource(R.string.setup_transit)) {
                TransitToggles(setupVm = setupVm, city = city)
            }
        }

        // 4. Start station ----------------------------------------------------
        if (city != null) {
            SectionCard(stringResource(R.string.setup_start_station)) {
                Text(
                    stringResource(R.string.setup_start_station_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                StationPickerField(
                    label = stringResource(R.string.setup_start_station),
                    stations = city.stations,
                    selectedStationId = ui.startStationId,
                    onSelect = { station ->
                        setupVm.update { it.copy(startStationId = station.id) }
                    },
                )
            }
        }

        // 5. Mode -------------------------------------------------------------
        SectionCard(stringResource(R.string.setup_mode)) {
            ChoiceRow(
                options = listOf(
                    stringResource(R.string.setup_mode_gps),
                    stringResource(R.string.setup_mode_sim),
                ),
                selectedIndex = if (ui.playMode == PlayMode.GPS) 0 else 1,
                onSelect = { index ->
                    setupVm.setPlayMode(if (index == 0) PlayMode.GPS else PlayMode.SIM)
                },
            )
            if (ui.playMode == PlayMode.SIM) {
                Text(stringResource(R.string.setup_time_scale))
                ChoiceRow(
                    options = TIME_SCALES.map { "${it}×" },
                    selectedIndex = TIME_SCALES.indexOf(ui.timeScale).coerceAtLeast(0),
                    onSelect = { index ->
                        setupVm.update { it.copy(timeScale = TIME_SCALES[index]) }
                    },
                )
            }
        }

        // 6. Durations ----------------------------------------------------------
        SectionCard(stringResource(R.string.setup_durations)) {
            Text(stringResource(R.string.setup_game_duration, ui.gameDurationMinutes))
            Slider(
                value = ui.gameDurationMinutes.toFloat(),
                onValueChange = { value ->
                    setupVm.update { it.copy(gameDurationMinutes = value.toInt()) }
                },
                valueRange = 20f..180f,
            )
            Text(stringResource(R.string.setup_hiding_duration, ui.hidingPhaseMinutes))
            Slider(
                value = ui.hidingPhaseMinutes.toFloat(),
                onValueChange = { value ->
                    setupVm.update { it.copy(hidingPhaseMinutes = value.toInt()) }
                },
                valueRange = 5f..30f,
            )
        }

        // 7. Question cooldown multiplier ----------------------------------------
        SectionCard(stringResource(R.string.setup_cooldowns)) {
            ChoiceRow(
                options = listOf("×0.5", "×1", "×2"),
                selectedIndex = ui.cooldownMultiplier.ordinal,
                onSelect = { index ->
                    setupVm.update { it.copy(cooldownMultiplier = CooldownMultiplier.entries[index]) }
                },
            )
        }

        // 8. Opponents -------------------------------------------------------------
        SectionCard(stringResource(R.string.setup_opponents)) {
            OpponentOptions(setupVm = setupVm)
        }

        // 9. Rounds ------------------------------------------------------------------
        SectionCard(stringResource(R.string.setup_rounds)) {
            ChoiceRow(
                options = ROUND_OPTIONS.map { it.toString() },
                selectedIndex = ROUND_OPTIONS.indexOf(ui.rounds).coerceAtLeast(0),
                onSelect = { index ->
                    setupVm.update { it.copy(rounds = ROUND_OPTIONS[index]) }
                },
            )
        }

        // 10. RNG seed -----------------------------------------------------------------
        SectionCard(stringResource(R.string.setup_seed)) {
            OutlinedTextField(
                value = ui.seedText,
                onValueChange = { text -> setupVm.update { it.copy(seedText = text) } },
                label = { Text(stringResource(R.string.setup_seed_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 11. Presets ---------------------------------------------------------------------
        SectionCard(stringResource(R.string.setup_presets)) {
            PresetSection(setupVm = setupVm, cities = cities, city = city, presets = presets)
        }

        // Validation + start -----------------------------------------------------------
        errors.forEach { error ->
            Text(
                text = error.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = {
                if (city != null) {
                    setupVm.startGame(city)
                    onStart()
                }
            },
            enabled = city != null && errors.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setup_start_game))
        }
    }
}

/**
 * The boundary editor map (GAME_DESIGN.md §8 item 2): polygon — tap to add a vertex
 * (3–30), with undo/clear; circle — tap to place the center plus a 1–30 km radius
 * slider. The current ring is previewed as a red overlay.
 */
@Composable
private fun BoundaryEditor(setupVm: SetupViewModel, city: CityFile) {
    val ui by setupVm.ui.collectAsStateWithLifecycle()

    Text(
        text = if (ui.boundaryChoice == BoundaryChoice.POLYGON) {
            stringResource(R.string.setup_boundary_polygon_hint, ui.polygonVertices.size)
        } else {
            stringResource(R.string.setup_boundary_circle_hint)
        },
        style = MaterialTheme.typography.bodySmall,
    )
    OsmMap(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
        onTap = { point ->
            when (ui.boundaryChoice) {
                BoundaryChoice.POLYGON -> {
                    if (ui.polygonVertices.size < 30) {
                        setupVm.update { it.copy(polygonVertices = it.polygonVertices + point) }
                    }
                }
                BoundaryChoice.CIRCLE -> setupVm.update { it.copy(circleCenter = point) }
                BoundaryChoice.FULL_EXTENT -> Unit
            }
        },
        onCreate = { mapView ->
            mapView.zoomToBoundingBox(MapOverlays.cityBoundingBox(city), false)
            MapOverlays.routeOverlays(city).forEach { mapView.overlays.add(it) }
        },
        update = { mapView ->
            mapView.overlays.removeAll { it is org.osmdroid.views.overlay.Polygon }
            val ring: List<LatLng> = when (ui.boundaryChoice) {
                BoundaryChoice.POLYGON -> ui.polygonVertices
                BoundaryChoice.CIRCLE -> ui.circleCenter?.let { center ->
                    MapOverlays.boundaryRing(
                        BoundarySpec.CircleBoundary(center, ui.circleRadiusKm.toDouble() * 1000.0),
                        city,
                    )
                }.orEmpty()
                BoundaryChoice.FULL_EXTENT -> emptyList()
            }
            if (ring.size >= 3) {
                mapView.overlays.add(MapOverlays.boundaryOverlay(ring))
            }
        },
    )
    if (ui.boundaryChoice == BoundaryChoice.POLYGON) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { setupVm.update { it.copy(polygonVertices = it.polygonVertices.dropLast(1)) } },
                enabled = ui.polygonVertices.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_undo))
            }
            OutlinedButton(
                onClick = { setupVm.update { it.copy(polygonVertices = emptyList()) } },
                enabled = ui.polygonVertices.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_clear))
            }
        }
    } else {
        Text(stringResource(R.string.setup_circle_radius, ui.circleRadiusKm.toInt()))
        Slider(
            value = ui.circleRadiusKm,
            onValueChange = { value -> setupVm.update { it.copy(circleRadiusKm = value) } },
            valueRange = 1f..30f,
        )
    }
}

/**
 * Per-mode toggles and per-route checkboxes grouped by mode, populated from the
 * city file (GAME_DESIGN.md §8 item 3). Disabling a mode disables all its routes.
 */
@Composable
private fun TransitToggles(setupVm: SetupViewModel, city: CityFile) {
    val ui by setupVm.ui.collectAsStateWithLifecycle()
    val routesByMode = remember(city) { city.routes.groupBy { it.mode } }

    TransitMode.entries.forEach { mode ->
        val routes = routesByMode[mode] ?: return@forEach
        val modeEnabled = mode !in ui.disabledModes
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = modeEnabled,
                onCheckedChange = { checked ->
                    setupVm.update {
                        it.copy(
                            disabledModes = if (checked) {
                                it.disabledModes - mode
                            } else {
                                it.disabledModes + mode
                            },
                        )
                    }
                },
            )
            Text(transitModeName(mode), style = MaterialTheme.typography.titleSmall)
        }
        routes.forEach { route ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 24.dp),
            ) {
                Checkbox(
                    checked = modeEnabled && route.id !in ui.disabledRouteIds,
                    enabled = modeEnabled,
                    onCheckedChange = { checked ->
                        setupVm.update {
                            it.copy(
                                disabledRouteIds = if (checked) {
                                    it.disabledRouteIds - route.id
                                } else {
                                    it.disabledRouteIds + route.id
                                },
                            )
                        }
                    },
                )
                Text("${route.shortName} — ${route.longName}")
            }
        }
    }
}

/**
 * AI opponent options (GAME_DESIGN.md §8 item 8): count 1–3, per-AI difficulty,
 * personality mode Hidden/Revealed/Manual (with a per-AI picker when manual),
 * and the human's role.
 */
@Composable
private fun OpponentOptions(setupVm: SetupViewModel) {
    val ui by setupVm.ui.collectAsStateWithLifecycle()

    Text(stringResource(R.string.setup_ai_count))
    ChoiceRow(
        options = listOf("1", "2", "3"),
        selectedIndex = ui.aiDifficulties.size - 1,
        onSelect = { index -> setupVm.setOpponentCount(index + 1) },
    )

    ui.aiDifficulties.forEachIndexed { i, difficulty ->
        Text(stringResource(R.string.setup_ai_label, i + 1))
        ChoiceRow(
            options = listOf(
                stringResource(R.string.difficulty_easy),
                stringResource(R.string.difficulty_medium),
                stringResource(R.string.difficulty_hard),
            ),
            selectedIndex = difficulty.ordinal,
            onSelect = { index ->
                setupVm.update {
                    it.copy(
                        aiDifficulties = it.aiDifficulties.toMutableList().apply {
                            this[i] = Difficulty.entries[index]
                        },
                    )
                }
            },
        )
        if (ui.personalityMode == AiPersonalityMode.MANUAL) {
            PersonalityPickerField(
                label = stringResource(R.string.setup_ai_personality, i + 1),
                selected = ui.manualPersonalities.getOrNull(i),
                onSelect = { personality ->
                    setupVm.update {
                        it.copy(
                            manualPersonalities = it.manualPersonalities.toMutableList().apply {
                                while (size <= i) add(AiPersonality.entries[size % AiPersonality.entries.size])
                                this[i] = personality
                            },
                        )
                    }
                },
            )
        }
    }

    Text(stringResource(R.string.setup_personality_mode))
    ChoiceRow(
        options = listOf(
            stringResource(R.string.personality_hidden),
            stringResource(R.string.personality_revealed),
            stringResource(R.string.personality_manual),
        ),
        selectedIndex = ui.personalityMode.ordinal,
        onSelect = { index ->
            setupVm.update { it.copy(personalityMode = AiPersonalityMode.entries[index]) }
        },
    )

    Text(stringResource(R.string.setup_human_role))
    ChoiceRow(
        options = listOf(
            stringResource(R.string.role_hider),
            stringResource(R.string.role_seeker),
        ),
        selectedIndex = if (ui.humanRole == Role.HIDER) 0 else 1,
        onSelect = { index ->
            setupVm.update { it.copy(humanRole = if (index == 0) Role.HIDER else Role.SEEKER) }
        },
    )
}

/** A field opening a dialog listing the five personalities (GAME_DESIGN.md §6.4). */
@Composable
private fun PersonalityPickerField(
    label: String,
    selected: AiPersonality?,
    onSelect: (AiPersonality) -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { showDialog = true }) {
        Text("$label: ${selected?.displayName ?: stringResource(R.string.picker_none_selected)}")
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AiPersonality.entries.forEach { personality ->
                        TextButton(
                            onClick = {
                                onSelect(personality)
                                showDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(personality.displayName)
                        }
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

/** Save/load/delete named presets (GAME_DESIGN.md §8 item 11). */
@Composable
private fun PresetSection(
    setupVm: SetupViewModel,
    cities: List<CityFile>,
    city: CityFile?,
    presets: List<io.terminus.core.persistence.OptionsPreset>,
) {
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("") }

    presets.forEach { preset ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(preset.name, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    setupVm.applyPreset(
                        preset,
                        cities.firstOrNull { it.cityId == preset.config.cityId },
                    )
                },
            ) {
                Text(stringResource(R.string.setup_preset_load))
            }
            TextButton(onClick = { setupVm.deletePreset(preset.name) }) {
                Text(stringResource(R.string.action_delete))
            }
        }
    }
    OutlinedButton(
        onClick = { showSaveDialog = true },
        enabled = city != null && setupVm.validate(city).isEmpty(),
    ) {
        Text(stringResource(R.string.setup_preset_save))
    }

    if (showSaveDialog && city != null) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.setup_preset_save)) },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.setup_preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        setupVm.savePreset(presetName.trim(), city)
                        presetName = ""
                        showSaveDialog = false
                    },
                    enabled = presetName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
