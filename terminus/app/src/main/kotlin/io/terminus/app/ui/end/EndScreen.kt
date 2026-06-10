package io.terminus.app.ui.end

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.terminus.app.R
import io.terminus.app.map.MapOverlays
import io.terminus.app.map.MapOverlays.toGeoPoint
import io.terminus.app.map.OsmMap
import io.terminus.app.ui.common.SectionCard
import io.terminus.app.ui.common.formatClock
import io.terminus.app.ui.common.playerDisplayName
import io.terminus.app.vm.GameViewModel
import io.terminus.core.game.GameCommand
import io.terminus.core.game.GameEvent
import io.terminus.core.game.Player
import io.terminus.core.game.Role
import kotlinx.coroutines.launch

/**
 * The round/match end screen (ARCHITECTURE.md §1.2 `ui.end`; GAME_DESIGN.md §7):
 * round summary with "Caught by …" credit, a capture-point map snapshot, the score
 * table, the AI personality reveal (hidden personalities are revealed here,
 * GAME_DESIGN.md §6.4), and next-round / end-match actions.
 */
@Composable
fun EndScreen(
    gameVm: GameViewModel,
    onNextRound: () -> Unit,
    onEndMatch: () -> Unit,
) {
    val session = gameVm.session
    if (session == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.game_none_active))
            Button(onClick = onEndMatch, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.action_back))
            }
        }
        return
    }

    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val hiderId = state.roles.entries.firstOrNull { it.value == Role.HIDER }?.key
    val captureEvent = remember(state.eventLog) {
        state.eventLog.filterIsInstance<GameEvent.Captured>().lastOrNull()
    }
    val roundEndEvent = remember(state.eventLog) {
        state.eventLog.filterIsInstance<GameEvent.RoundEnded>().lastOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.end_title, state.roundIndex + 1),
            style = MaterialTheme.typography.headlineSmall,
        )

        // Round summary -------------------------------------------------------
        SectionCard(stringResource(R.string.end_summary)) {
            if (hiderId != null) {
                Text(
                    stringResource(
                        R.string.end_hider_was,
                        playerDisplayName(state, hiderId, revealAll = true),
                    ),
                )
            }
            if (state.capturedBy != null) {
                Text(
                    stringResource(
                        R.string.end_caught_by,
                        playerDisplayName(state, state.capturedBy!!, revealAll = true),
                        formatClock(state.captureGameMillis ?: state.gameTimeMillis),
                    ),
                )
            } else {
                Text(stringResource(R.string.end_never_caught))
            }
            roundEndEvent?.let { event ->
                Text(stringResource(R.string.end_round_score, "%.1f".format(event.hiderScore)))
            }
            Text(stringResource(R.string.end_bonus_minutes, "%.1f".format(state.bonusMinutes)))
            Text(stringResource(R.string.end_penalty_minutes, "%.1f".format(state.penaltyMinutes)))
            Text(stringResource(R.string.end_questions_answered, state.questionsAnswered))
            Text(stringResource(R.string.end_cards_played, state.cardsPlayed))
        }

        // Capture point map snapshot ------------------------------------------
        val capturePosition = captureEvent?.position
        if (capturePosition != null) {
            SectionCard(stringResource(R.string.end_capture_point)) {
                OsmMap(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    onCreate = { mapView ->
                        mapView.controller.setZoom(15.0)
                        mapView.controller.setCenter(capturePosition.toGeoPoint())
                        mapView.overlays.add(
                            MapOverlays.tokenMarker(mapView, capturePosition, "✕"),
                        )
                    },
                )
            }
        }

        // Score table ----------------------------------------------------------
        SectionCard(stringResource(R.string.end_scores)) {
            val sorted = state.players.sortedByDescending { state.matchScores[it.id] ?: 0.0 }
            sorted.forEach { player ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(playerDisplayName(state, player.id, revealAll = true))
                    Text("%.1f".format(state.matchScores[player.id] ?: 0.0))
                }
            }
        }

        // Personality reveal (GAME_DESIGN.md §6.4: hidden until the end screen) --
        val aiPlayers = state.players.filterIsInstance<Player.AiPlayer>()
        if (aiPlayers.isNotEmpty() && state.aiPersonalities.isNotEmpty()) {
            SectionCard(stringResource(R.string.end_personalities)) {
                aiPlayers.forEach { player ->
                    val personality = state.aiPersonalities[player.id] ?: return@forEach
                    Text(
                        stringResource(
                            R.string.end_personality_row,
                            player.name,
                            personality.displayName,
                        ),
                    )
                }
            }
        }

        // Actions -----------------------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.roundIndex + 1 < state.config.rounds) {
                Button(
                    onClick = {
                        gameVm.send(GameCommand.StartRound)
                        onNextRound()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.end_next_round))
                }
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        gameVm.endMatchAndRecord()
                        onEndMatch()
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.end_end_match))
            }
        }
    }
}
