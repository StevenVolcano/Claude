package io.terminus.app.ui.game

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.terminus.app.R
import io.terminus.app.ui.common.NameResolver
import io.terminus.app.ui.common.eventText
import io.terminus.app.ui.common.formatClock
import io.terminus.core.game.GameState

/**
 * The timestamped round log (ARCHITECTURE.md §1.2 `ui.game` ActivityLog): every
 * [io.terminus.core.game.GameEvent] rendered human-readably via
 * [eventText], newest entry first. AI players appear under their invented display
 * names (with personalities shown per the setup option).
 */
@Composable
fun ActivityLog(
    state: GameState,
    names: NameResolver,
    modifier: Modifier = Modifier,
) {
    val events = state.eventLog
    if (events.isEmpty()) {
        Text(
            text = stringResource(R.string.log_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(12.dp),
        )
        return
    }
    LazyColumn(modifier = modifier) {
        items(events.asReversed()) { event ->
            Row(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = formatClock(event.gameTimeMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = eventText(event, state, names),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
