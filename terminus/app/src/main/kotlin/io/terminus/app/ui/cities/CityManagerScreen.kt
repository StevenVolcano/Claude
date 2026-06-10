package io.terminus.app.ui.cities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.terminus.app.R
import io.terminus.app.vm.CityViewModel
import io.terminus.core.cityfile.CityFile

/**
 * City manager (ARCHITECTURE.md §1.2 `ui.cities`): bundled/imported cities with
 * station/route counts, delete, and the entry point to the GTFS import flow.
 */
@Composable
fun CityManagerScreen(
    cityVm: CityViewModel,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    val cities by cityVm.cities.collectAsStateWithLifecycle()
    var deleteCandidate by rememberSaveable { mutableStateOf<String?>(null) }

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
                text = stringResource(R.string.cities_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cities_import_gtfs))
        }
        if (cities.isEmpty()) {
            Text(stringResource(R.string.cities_empty))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cities, key = { it.cityId }) { city ->
                CityCard(
                    city = city,
                    onDelete = { deleteCandidate = city.cityId },
                )
            }
        }
    }

    val candidate = deleteCandidate
    if (candidate != null) {
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.cities_delete_title)) },
            text = { Text(stringResource(R.string.cities_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        cityVm.deleteCity(candidate)
                        deleteCandidate = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun CityCard(city: CityFile, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (city.isSynthetic) {
                        stringResource(R.string.cities_demo_suffix, city.displayName)
                    } else {
                        city.displayName
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            }
            Text(
                text = stringResource(
                    R.string.cities_counts,
                    city.stations.size,
                    city.routes.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = city.attribution,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
