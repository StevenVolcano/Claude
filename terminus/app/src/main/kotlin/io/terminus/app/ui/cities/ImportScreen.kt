package io.terminus.app.ui.cities

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import io.terminus.app.map.MapOverlays.toGeoPoint
import io.terminus.app.map.OsmMap
import io.terminus.app.vm.CityViewModel
import io.terminus.app.vm.ImportState
import io.terminus.core.geo.LatLng

/**
 * GTFS import flow (ARCHITECTURE.md §1.2 `ui.cities`): SAF zip picker → streamed
 * parse with progress → boundary polygon draw on an osmdroid map → network build →
 * report + saved `.city.json.gz`.
 */
@Composable
fun ImportScreen(
    cityVm: CityViewModel,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val state by cityVm.importState.collectAsStateWithLifecycle()
    var cityName by rememberSaveable { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) cityVm.startImport(uri, cityName)
    }

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
                text = stringResource(R.string.import_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(
                onClick = {
                    cityVm.resetImport()
                    onBack()
                },
            ) {
                Text(stringResource(R.string.action_back))
            }
        }

        when (val s = state) {
            ImportState.Idle -> {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.import_intro))
                    OutlinedTextField(
                        value = cityName,
                        onValueChange = { cityName = it },
                        label = { Text(stringResource(R.string.import_city_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            picker.launch(
                                arrayOf("application/zip", "application/octet-stream", "*/*"),
                            )
                        },
                        enabled = cityName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.import_pick_zip))
                    }
                }
            }

            ImportState.Parsing -> ProgressStep(stringResource(R.string.import_parsing))

            is ImportState.AwaitingBoundary -> BoundaryStep(
                state = s,
                onConfirm = { ring -> cityVm.confirmBoundary(ring) },
            )

            ImportState.Building -> ProgressStep(stringResource(R.string.import_building))

            is ImportState.Done -> {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.import_done_title, s.city.displayName),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            R.string.import_report,
                            s.report.stopsRead,
                            s.report.stopsMerged,
                            s.report.routes,
                            s.report.edges,
                            s.report.transferEdges,
                            s.report.droppedOutsideBoundary,
                        ),
                    )
                    s.report.warnings.forEach { warning ->
                        Text("• $warning", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            cityVm.resetImport()
                            onDone()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_done))
                    }
                }
            }

            is ImportState.Failed -> {
                Text(
                    stringResource(R.string.import_failed, s.message),
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = { cityVm.resetImport() }) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

@Composable
private fun ProgressStep(label: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(label)
    }
}

@Composable
private fun BoundaryStep(
    state: ImportState.AwaitingBoundary,
    onConfirm: (List<LatLng>) -> Unit,
) {
    var vertices by remember { mutableStateOf<List<LatLng>>(emptyList()) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.import_boundary_hint))
        OsmMap(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onTap = { point -> vertices = vertices + point },
            onCreate = { mapView ->
                mapView.controller.setZoom(11.0)
                mapView.controller.setCenter(state.mapCenter.toGeoPoint())
            },
            update = { mapView ->
                mapView.overlays.removeAll { it is org.osmdroid.views.overlay.Polygon }
                if (state.defaultHull.size >= 3) {
                    val hull = MapOverlays.boundaryOverlay(state.defaultHull)
                    hull.outlinePaint.color = android.graphics.Color.argb(90, 120, 120, 120)
                    hull.fillPaint.color = android.graphics.Color.TRANSPARENT
                    mapView.overlays.add(hull)
                }
                if (vertices.isNotEmpty()) {
                    mapView.overlays.add(MapOverlays.boundaryOverlay(vertices))
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vertices = vertices.dropLast(1) },
                enabled = vertices.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_undo))
            }
            OutlinedButton(
                onClick = { vertices = emptyList() },
                enabled = vertices.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_clear))
            }
            OutlinedButton(onClick = { vertices = state.defaultHull }) {
                Text(stringResource(R.string.import_use_full_extent))
            }
        }
        Button(
            onClick = { onConfirm(vertices) },
            enabled = vertices.size in 3..30,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.import_confirm_boundary, vertices.size))
        }
    }
}
