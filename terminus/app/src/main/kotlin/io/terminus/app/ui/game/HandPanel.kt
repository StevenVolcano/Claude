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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.terminus.app.R
import io.terminus.app.ui.common.NameResolver
import io.terminus.app.ui.common.cardEffectText
import io.terminus.app.ui.common.formatClock
import io.terminus.app.ui.common.questionCategoryName
import io.terminus.app.ui.common.questionText
import io.terminus.core.cards.CardKind
import io.terminus.core.cards.CardType
import io.terminus.core.cards.EffectParams
import io.terminus.core.cards.PlayWindow
import io.terminus.core.cityfile.CityFile
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GamePhase
import io.terminus.core.game.GameRules
import io.terminus.core.game.GameState
import io.terminus.core.game.PlayMode
import io.terminus.core.game.PlayerId
import io.terminus.core.game.Role
import io.terminus.core.geo.GeoMath
import io.terminus.core.questions.QuestionCategory
import io.terminus.core.sim.SimulationEngine
import io.terminus.core.transit.TransitNetwork

/** Question categories Ghost Echo may decoy (GAME_DESIGN.md §4.2 C12: Q1/Q2/Q3 only). */
private val DECOY_CATEGORIES = setOf(
    QuestionCategory.RADIUS_PING,
    QuestionCategory.COMPASS_CALL,
    QuestionCategory.THERMOMETER,
)

/**
 * The hider's card sheet (ARCHITECTURE.md §1.2 `ui.game` HandPanel;
 * GAME_DESIGN.md §4): active-effect countdowns, the response-window veto/decoy
 * prompt, the pending draw-keep chooser, and the hand with play buttons (with
 * parameter dialogs for Detour, Service Change, Transfer Slip, and Lost & Found).
 * Seekers see the active curse countdowns only — cards are hider-only (§4.1).
 */
@Composable
fun HandPanel(
    state: GameState,
    city: CityFile,
    network: TransitNetwork,
    names: NameResolver,
    humanPlayerId: PlayerId,
    send: (GameCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = state.gameTimeMillis
    val isHider = state.roles[humanPlayerId] == Role.HIDER

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActiveEffectsSection(state = state, now = now)

        if (!isHider) {
            Text(stringResource(R.string.hand_hider_only))
            return@Column
        }

        ResponseWindowSection(
            state = state,
            network = network,
            names = names,
            humanPlayerId = humanPlayerId,
            send = send,
        )
        PendingKeepSection(state = state, humanPlayerId = humanPlayerId, send = send)

        Text(
            stringResource(
                R.string.hand_counts,
                state.hand.size,
                state.handLimit,
                state.deckCount,
                state.discardCount,
            ),
            style = MaterialTheme.typography.bodySmall,
        )

        HandSection(
            state = state,
            city = city,
            network = network,
            humanPlayerId = humanPlayerId,
            send = send,
        )
    }
}

/** Countdown list of every active card effect (visible to both roles, GAME_DESIGN.md §4.2). */
@Composable
private fun ActiveEffectsSection(state: GameState, now: Long) {
    if (state.activeEffects.isEmpty()) return
    Text(
        stringResource(R.string.hand_active_effects),
        style = MaterialTheme.typography.titleSmall,
    )
    state.activeEffects.forEach { effect ->
        val remaining = effect.expiryGameMillis?.minus(now)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(effect.type.displayName, style = MaterialTheme.typography.bodySmall)
            Text(
                text = if (remaining != null) {
                    formatClock(remaining.coerceAtLeast(0))
                } else {
                    stringResource(R.string.hand_effect_untimed)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The 20-second response window (GAME_DESIGN.md §3 general rules): the pending
 * question with a countdown plus Conductor's Override and (for Q1/Q2/Q3) Ghost Echo.
 */
@Composable
private fun ResponseWindowSection(
    state: GameState,
    network: TransitNetwork,
    names: NameResolver,
    humanPlayerId: PlayerId,
    send: (GameCommand) -> Unit,
) {
    val pending = state.pendingQuestion ?: return
    val now = state.gameTimeMillis
    var showDecoyDialog by remember { mutableStateOf(false) }

    Text(
        stringResource(R.string.hand_response_title, formatClock((pending.responseWindowEndsGameMillis - now).coerceAtLeast(0))),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(questionText(pending.spec, names), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (CardType.CONDUCTORS_OVERRIDE in state.hand) {
            Button(
                onClick = {
                    send(GameCommand.PlayCard(humanPlayerId, CardType.CONDUCTORS_OVERRIDE, null, now))
                },
            ) {
                Text(stringResource(R.string.hand_veto))
            }
        }
        if (CardType.GHOST_ECHO in state.hand && pending.spec.category in DECOY_CATEGORIES) {
            Button(onClick = { showDecoyDialog = true }) {
                Text(stringResource(R.string.hand_decoy))
            }
        }
    }

    if (showDecoyDialog) {
        // Decoy point picker: any station within 1.5 km of the true position (§4.2 C12).
        val myLatLng = state.positions[humanPlayerId]?.let { SimulationEngine.latLngOf(it, network) }
        val candidates = if (myLatLng == null) {
            emptyList()
        } else {
            network.stations.filter {
                GeoMath.haversineMeters(myLatLng, it.latLng) <= GameRules.DECOY_MAX_DISTANCE_METERS
            }
        }
        AlertDialog(
            onDismissRequest = { showDecoyDialog = false },
            title = { Text(stringResource(R.string.hand_decoy_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (candidates.isEmpty()) {
                        Text(stringResource(R.string.hand_decoy_none))
                    }
                    candidates.forEach { station ->
                        Text(
                            text = station.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    send(
                                        GameCommand.PlayCard(
                                            humanPlayerId,
                                            CardType.GHOST_ECHO,
                                            EffectParams.DecoyParams(station.latLng),
                                            state.gameTimeMillis,
                                        ),
                                    )
                                    showDecoyDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDecoyDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** The unresolved draw-D-keep-K chooser (GAME_DESIGN.md §3 compensation). */
@Composable
private fun PendingKeepSection(
    state: GameState,
    humanPlayerId: PlayerId,
    send: (GameCommand) -> Unit,
) {
    val pendingKeep = state.pendingKeep ?: return
    var selected by remember(pendingKeep) { mutableStateOf<Set<Int>>(emptySet()) }

    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(stringResource(R.string.hand_keep_title, pendingKeep.keep, pendingKeep.drawn.size))
        },
        text = {
            Column {
                pendingKeep.drawn.forEachIndexed { index, card ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = index in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + index else selected - index
                            },
                        )
                        Text(card.displayName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    send(
                        GameCommand.KeepCards(
                            playerId = humanPlayerId,
                            kept = selected.sorted().map { pendingKeep.drawn[it] },
                            gameTimeMillis = state.gameTimeMillis,
                        ),
                    )
                },
                enabled = selected.size == minOf(pendingKeep.keep, pendingKeep.drawn.size),
            ) {
                Text(stringResource(R.string.hand_keep_confirm))
            }
        },
    )
}

/** The hand itself: one row per held card with a Play button (GAME_DESIGN.md §4.1–4.2). */
@Composable
private fun HandSection(
    state: GameState,
    city: CityFile,
    network: TransitNetwork,
    humanPlayerId: PlayerId,
    send: (GameCommand) -> Unit,
) {
    val now = state.gameTimeMillis
    var paramCard by remember { mutableStateOf<CardType?>(null) }
    val activeCurses = state.activeEffects.count { it.type.kind == CardKind.CURSE }

    if (state.hand.isEmpty()) {
        Text(stringResource(R.string.hand_empty))
        return
    }

    val grouped = state.hand.groupingBy { it }.eachCount()
    grouped.forEach { (card, count) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (count > 1) "${card.displayName} ×$count" else card.displayName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(cardEffectText(card), style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    when (card) {
                        CardType.DETOUR,
                        CardType.SERVICE_CHANGE,
                        CardType.LOST_AND_FOUND,
                        -> paramCard = card

                        CardType.TRANSFER_SLIP ->
                            if (state.config.playMode == PlayMode.SIM) {
                                paramCard = card
                            } else {
                                send(
                                    GameCommand.PlayCard(
                                        humanPlayerId,
                                        card,
                                        EffectParams.RelocateParams(null),
                                        now,
                                    ),
                                )
                            }

                        else -> send(GameCommand.PlayCard(humanPlayerId, card, null, now))
                    }
                },
                enabled = canPlay(card, state, activeCurses),
            ) {
                Text(stringResource(R.string.hand_play))
            }
        }
    }

    when (paramCard) {
        CardType.DETOUR -> DetourDialog(
            city = city,
            state = state,
            onPick = { routeId ->
                send(
                    GameCommand.PlayCard(
                        humanPlayerId,
                        CardType.DETOUR,
                        EffectParams.DetourParams(routeId),
                        now,
                    ),
                )
                paramCard = null
            },
            onDismiss = { paramCard = null },
        )

        CardType.SERVICE_CHANGE -> ServiceChangeDialog(
            onPick = { category ->
                send(
                    GameCommand.PlayCard(
                        humanPlayerId,
                        CardType.SERVICE_CHANGE,
                        EffectParams.ServiceChangeParams(category),
                        now,
                    ),
                )
                paramCard = null
            },
            onDismiss = { paramCard = null },
        )

        CardType.TRANSFER_SLIP -> RelocateDialog(
            network = network,
            onPick = { stationId ->
                send(
                    GameCommand.PlayCard(
                        humanPlayerId,
                        CardType.TRANSFER_SLIP,
                        EffectParams.RelocateParams(stationId),
                        now,
                    ),
                )
                paramCard = null
            },
            onDismiss = { paramCard = null },
        )

        CardType.LOST_AND_FOUND -> LostAndFoundDialog(
            hand = state.hand,
            onPick = { discards ->
                send(
                    GameCommand.PlayCard(
                        humanPlayerId,
                        CardType.LOST_AND_FOUND,
                        EffectParams.LostAndFoundParams(discards),
                        now,
                    ),
                )
                paramCard = null
            },
            onDismiss = { paramCard = null },
        )

        else -> Unit
    }
}

/**
 * Local playability check (GAME_DESIGN.md §4.1); the engine remains the authority
 * and rejects anything illegal that slips through.
 */
private fun canPlay(card: CardType, state: GameState, activeCurses: Int): Boolean = when {
    state.paused -> false
    card.playWindow == PlayWindow.RESPONSE_WINDOW ->
        // Handled by the response-window section; the list button stays disabled.
        false
    state.phase != GamePhase.SEEKING -> false
    state.pendingQuestion != null -> false
    card.kind == CardKind.CURSE && activeCurses >= GameRules.MAX_ACTIVE_CURSES -> false
    else -> true
}

@Composable
private fun DetourDialog(
    city: CityFile,
    state: GameState,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val allowedRoutes = city.routes.filter { route ->
        route.mode in state.config.allowedModes &&
            (state.config.allowedRouteIds?.contains(route.id) != false)
    }
    PickDialog(
        title = stringResource(R.string.hand_detour_title),
        options = allowedRoutes.map { "${it.shortName} — ${it.longName}" },
        onPick = { index -> onPick(allowedRoutes[index].id) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun ServiceChangeDialog(onPick: (QuestionCategory) -> Unit, onDismiss: () -> Unit) {
    PickDialog(
        title = stringResource(R.string.hand_service_change_title),
        options = QuestionCategory.entries.map { questionCategoryName(it) },
        onPick = { index -> onPick(QuestionCategory.entries[index]) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun RelocateDialog(
    network: TransitNetwork,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    PickDialog(
        title = stringResource(R.string.hand_relocate_title),
        options = network.stations.map { it.name },
        onPick = { index -> onPick(network.stations[index].id) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun LostAndFoundDialog(
    hand: List<CardType>,
    onPick: (List<CardType>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hand_lost_and_found_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                hand.forEachIndexed { index, card ->
                    if (card == CardType.LOST_AND_FOUND && index == hand.indexOf(CardType.LOST_AND_FOUND)) {
                        // The copy being played cannot discard itself.
                        return@forEachIndexed
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = index in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked && selected.size < 3) {
                                    selected + index
                                } else {
                                    selected - index
                                }
                            },
                        )
                        Text(card.displayName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(selected.sorted().map { hand[it] }) },
                enabled = selected.isNotEmpty(),
            ) {
                Text(stringResource(R.string.hand_play))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** A simple single-pick list dialog. */
@Composable
private fun PickDialog(
    title: String,
    options: List<String>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEachIndexed { index, label ->
                    Text(
                        text = label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(index) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
