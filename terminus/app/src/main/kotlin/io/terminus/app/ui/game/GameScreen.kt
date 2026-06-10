package io.terminus.app.ui.game

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.terminus.app.R
import io.terminus.app.map.MapOverlays
import io.terminus.app.map.OsmMap
import io.terminus.app.service.GameForegroundService
import io.terminus.app.ui.common.NameResolver
import io.terminus.app.ui.common.formatClock
import io.terminus.app.ui.common.phaseName
import io.terminus.app.vm.GameViewModel
import io.terminus.core.cityfile.CityFile
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameRules
import io.terminus.core.game.GameState
import io.terminus.core.game.PlayMode
import io.terminus.core.game.PlayerId
import io.terminus.core.game.Role
import io.terminus.core.geo.GeoMath
import io.terminus.core.sim.SimulationEngine
import io.terminus.core.transit.TransitNetwork
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** The bottom-sheet panels of the game screen (ARCHITECTURE.md §1.2 `ui.game`). */
private enum class GamePanel { QUESTIONS, CARDS, LOG }

/** Maximum tap-to-station snap distance for sim-mode movement, meters. */
private const val MOVE_TAP_SNAP_METERS = 1_000.0

/**
 * The in-game screen (ARCHITECTURE.md §1.2 `ui.game`): a full-screen osmdroid map
 * (boundary, colored route polylines, station markers, seeker tokens, the hider zone
 * when own/known, the human position), a top bar with phase + game clock + sim-mode
 * pause, and the three bottom sheets ([QuestionPanel], [HandPanel], [ActivityLog]).
 * In sim mode, tapping near a station sends a [GameCommand.MoveToken].
 */
@Composable
fun GameScreen(
    gameVm: GameViewModel,
    onRoundEnd: () -> Unit,
    onAbandon: () -> Unit,
) {
    val session = gameVm.session
    val city = gameVm.cityFile
    val network = gameVm.network
    if (session == null || city == null || network == null) {
        NoActiveGame(onAbandon)
        return
    }

    val state by session.state.collectAsStateWithLifecycle()
    val names = remember(city) { NameResolver(city) }
    val context = LocalContext.current
    val humanPlayerId = gameVm.humanPlayerId

    // Navigate to the end screen on the transition into ROUND_END (not when
    // re-entering the screen while already ended — that gets a button instead).
    var lastPhase by remember { mutableStateOf<GamePhase?>(null) }
    LaunchedEffect(state.phase) {
        if (state.phase == GamePhase.ROUND_END &&
            lastPhase != null && lastPhase != GamePhase.ROUND_END
        ) {
            onRoundEnd()
        }
        lastPhase = state.phase
    }

    // GPS mode: request location (+ notification) permissions, then run the
    // foreground service that feeds GpsFix commands (ARCHITECTURE.md §1.2 `service`).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            GameForegroundService.start(context)
        }
    }
    LaunchedEffect(Unit) {
        if (state.config.playMode == PlayMode.GPS) {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    var showQuitDialog by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf(GamePanel.LOG) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            state = state,
            send = { session.send(it) },
            onQuit = { showQuitDialog = true },
            onSummary = onRoundEnd,
        )

        GameMap(
            state = state,
            city = city,
            network = network,
            humanPlayerId = humanPlayerId,
            send = { session.send(it) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PanelTab(stringResource(R.string.game_tab_questions), panel == GamePanel.QUESTIONS) {
                panel = GamePanel.QUESTIONS
            }
            PanelTab(stringResource(R.string.game_tab_cards), panel == GamePanel.CARDS) {
                panel = GamePanel.CARDS
            }
            PanelTab(stringResource(R.string.game_tab_log), panel == GamePanel.LOG) {
                panel = GamePanel.LOG
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
        ) {
            when (panel) {
                GamePanel.QUESTIONS -> QuestionPanel(
                    state = state,
                    city = city,
                    network = network,
                    humanPlayerId = humanPlayerId,
                    send = { session.send(it) },
                    modifier = Modifier.fillMaxSize(),
                )

                GamePanel.CARDS -> HandPanel(
                    state = state,
                    city = city,
                    network = network,
                    names = names,
                    humanPlayerId = humanPlayerId,
                    send = { session.send(it) },
                    modifier = Modifier.fillMaxSize(),
                )

                GamePanel.LOG -> ActivityLog(
                    state = state,
                    names = names,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showQuitDialog) {
        AlertDialog(
            onDismissRequest = { showQuitDialog = false },
            title = { Text(stringResource(R.string.game_quit_title)) },
            text = { Text(stringResource(R.string.game_quit_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showQuitDialog = false
                        GameForegroundService.stop(context)
                        gameVm.abandonGame()
                        onAbandon()
                    },
                ) {
                    Text(stringResource(R.string.game_quit_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuitDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun NoActiveGame(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.game_none_active))
        Button(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.action_back))
        }
    }
}

/** Phase + game clock, sim-mode pause/scale, quit, and a summary link once ended. */
@Composable
private fun TopBar(
    state: GameState,
    send: (GameCommand) -> Unit,
    onQuit: () -> Unit,
    onSummary: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(phaseName(state.phase), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    R.string.game_clock,
                    formatClock(state.gameTimeMillis),
                    formatClock(state.config.gameDurationMinutes * 60_000L),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.config.playMode == PlayMode.SIM) {
                Text("${state.config.timeScale}×", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { send(GameCommand.PauseToggle) }) {
                    Text(
                        if (state.paused) {
                            stringResource(R.string.game_resume)
                        } else {
                            stringResource(R.string.game_pause)
                        },
                    )
                }
            }
            if (state.phase == GamePhase.ROUND_END) {
                Button(onClick = onSummary) { Text(stringResource(R.string.game_summary)) }
            }
            TextButton(onClick = onQuit) { Text(stringResource(R.string.game_quit)) }
        }
    }
}

@Composable
private fun PanelTab(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

/**
 * The live map. Static overlays (boundary, routes, stations) are added once on
 * creation; per-state overlays (tokens, hider zone) are swapped on every state
 * emission. Token visibility: seekers are always visible; the hider token only to
 * the hider themselves (or after capture) (ARCHITECTURE.md §1.2 `ui.game`).
 */
@Composable
private fun GameMap(
    state: GameState,
    city: CityFile,
    network: TransitNetwork,
    humanPlayerId: PlayerId,
    send: (GameCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dynamicOverlays = remember { mutableListOf<Overlay>() }
    val humanRole = state.roles[humanPlayerId]
    val canMoveToken = state.config.playMode == PlayMode.SIM && !state.paused &&
        state.phase != GamePhase.ROUND_END && state.phase != GamePhase.SETUP &&
        // A seeker may not leave the start station during the hiding phase (§2.1).
        !(state.phase == GamePhase.HIDING && humanRole == Role.SEEKER)

    Column(modifier = modifier) {
        if (state.config.playMode == PlayMode.SIM) {
            Text(
                text = if (canMoveToken) {
                    stringResource(R.string.game_move_hint)
                } else {
                    stringResource(R.string.game_move_locked)
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        OsmMap(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onTap = { point ->
                if (canMoveToken) {
                    val station = network.nearestStation(point)
                    if (station != null &&
                        GeoMath.haversineMeters(point, station.latLng) <= MOVE_TAP_SNAP_METERS
                    ) {
                        send(GameCommand.MoveToken(humanPlayerId, station.id, state.gameTimeMillis))
                    }
                }
            },
            onCreate = { mapView -> addStaticOverlays(mapView, state, city) },
            update = { mapView ->
                mapView.overlays.removeAll(dynamicOverlays)
                dynamicOverlays.clear()
                addDynamicOverlays(mapView, state, network, humanPlayerId, dynamicOverlays)
            },
        )
    }
}

private fun addStaticOverlays(mapView: MapView, state: GameState, city: CityFile) {
    mapView.zoomToBoundingBox(MapOverlays.cityBoundingBox(city), false)
    mapView.overlays.add(
        MapOverlays.boundaryOverlay(MapOverlays.boundaryRing(state.config.boundary, city)),
    )
    MapOverlays.routeOverlays(city).forEach { mapView.overlays.add(it) }
    MapOverlays.stationMarkers(mapView, city).forEach { mapView.overlays.add(it) }
}

private fun addDynamicOverlays(
    mapView: MapView,
    state: GameState,
    network: TransitNetwork,
    humanPlayerId: PlayerId,
    dynamicOverlays: MutableList<Overlay>,
) {
    val humanRole = state.roles[humanPlayerId]

    // The hider's 300 m zone, when own or revealed (Final Approach / capture).
    val zoneStation = state.hiderZoneStationId?.let { network.stationsById[it] }
    val zoneVisible = humanRole == Role.HIDER ||
        state.phase == GamePhase.FINAL_APPROACH || state.capturedBy != null
    if (zoneStation != null && zoneVisible) {
        val circle = MapOverlays.circleOverlay(
            center = zoneStation.latLng,
            radiusMeters = GameRules.HIDING_ZONE_RADIUS_METERS,
            argbStroke = android.graphics.Color.argb(200, 69, 123, 157),
            argbFill = android.graphics.Color.argb(40, 69, 123, 157),
        )
        dynamicOverlays += circle
        mapView.overlays.add(circle)
    }

    // Player tokens: the hider's token is hidden from a seeking human until capture.
    state.players.forEach { player ->
        val role = state.roles[player.id]
        val visible = role != Role.HIDER || player.id == humanPlayerId || state.capturedBy != null
        if (!visible) return@forEach
        val position = state.positions[player.id] ?: return@forEach
        val latLng = SimulationEngine.latLngOf(position, network) ?: return@forEach
        val label = if (player.id == humanPlayerId) "◎" else player.name
        val marker = MapOverlays.tokenMarker(mapView, latLng, label)
        dynamicOverlays += marker
        mapView.overlays.add(marker)
    }
}
