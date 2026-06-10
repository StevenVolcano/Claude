package io.terminus.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.terminus.app.R
import io.terminus.app.vm.HistoryViewModel
import io.terminus.core.game.PlayMode
import io.terminus.core.persistence.MatchRecord
import io.terminus.core.persistence.RoundRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Past matches from the JSON history records (ARCHITECTURE.md §1.2 `ui.history`,
 * §5): one card per match with date, setup summary, winner, per-player totals, and
 * per-round outcome lines (including the AI personality reveal stored in each
 * [RoundRecord]).
 */
@Composable
fun HistoryScreen(
    historyVm: HistoryViewModel,
    onBack: () -> Unit,
) {
    val matches by historyVm.matches.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.history_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        if (matches.isEmpty()) {
            Text(stringResource(R.string.history_empty))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(matches, key = { it.createdEpochSec }) { record ->
                MatchCard(record)
            }
        }
    }
}

@Composable
private fun MatchCard(record: MatchRecord) {
    val nameById = record.players.associate { it.id to it.name }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = DATE_FORMAT.format(
                    Instant.ofEpochSecond(record.createdEpochSec).atZone(ZoneId.systemDefault()),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.history_setup,
                    record.config.cityId,
                    if (record.config.playMode == PlayMode.GPS) {
                        stringResource(R.string.setup_mode_gps)
                    } else {
                        stringResource(R.string.setup_mode_sim)
                    },
                    record.config.rounds,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            record.winnerId?.let { winnerId ->
                Text(
                    stringResource(R.string.history_winner, nameById[winnerId] ?: winnerId.value),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            record.totalScores.entries
                .sortedByDescending { it.value }
                .forEach { (playerId, score) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(nameById[playerId] ?: playerId.value, style = MaterialTheme.typography.bodySmall)
                        Text("%.1f".format(score), style = MaterialTheme.typography.bodySmall)
                    }
                }
            record.rounds.forEach { round ->
                RoundLine(round, nameById)
            }
        }
    }
}

@Composable
private fun RoundLine(round: RoundRecord, nameById: Map<io.terminus.core.game.PlayerId, String>) {
    val hiderName = displayNameWithPersonality(round, round.hiderId, nameById)
    val outcome = if (round.captured) {
        val seeker = round.capturedBy
        if (seeker != null) {
            stringResource(
                R.string.history_round_caught,
                displayNameWithPersonality(round, seeker, nameById),
            )
        } else {
            stringResource(R.string.history_round_caught_unknown)
        }
    } else {
        stringResource(R.string.history_round_survived)
    }
    Text(
        text = stringResource(
            R.string.history_round_line,
            round.roundIndex + 1,
            hiderName,
            "%.1f".format(round.hiderScore),
            outcome,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Name plus the recorded personality reveal, e.g. "Vera (The Ghost)". */
private fun displayNameWithPersonality(
    round: RoundRecord,
    playerId: io.terminus.core.game.PlayerId,
    nameById: Map<io.terminus.core.game.PlayerId, String>,
): String {
    val name = nameById[playerId] ?: playerId.value
    val personality = round.aiPersonalities[playerId] ?: return name
    return "$name (${personality.displayName})"
}
