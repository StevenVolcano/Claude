package io.terminus.app.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.terminus.app.data.AppStorage
import io.terminus.core.cityfile.CityFile
import io.terminus.core.geo.GeoMath
import io.terminus.core.geo.LatLng
import io.terminus.core.geo.Polygon
import io.terminus.core.gtfs.GtfsFeed
import io.terminus.core.gtfs.GtfsImportReport
import io.terminus.core.gtfs.GtfsParser
import io.terminus.core.gtfs.NetworkBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

/** Progress of the GTFS import flow (ARCHITECTURE.md §1.2 `ui.cities`). */
sealed interface ImportState {
    /** No import running. */
    data object Idle : ImportState

    /** Streaming/parsing the GTFS zip. */
    data object Parsing : ImportState

    /**
     * Parsed; waiting for the user to draw the boundary polygon on the map.
     *
     * @property displayName city name entered by the user.
     * @property feed the parsed feed, held until the boundary is confirmed.
     * @property mapCenter centroid of the feed's stops, for centering the boundary map.
     * @property defaultHull convex hull of the stops — the suggested full-extent boundary.
     */
    data class AwaitingBoundary(
        val displayName: String,
        val feed: GtfsFeed,
        val mapCenter: LatLng,
        val defaultHull: List<LatLng>,
    ) : ImportState

    /** Building the network and writing the city file. */
    data object Building : ImportState

    /** Import finished; the city has been saved. */
    data class Done(val city: CityFile, val report: GtfsImportReport) : ImportState

    /** Import failed. */
    data class Failed(val message: String) : ImportState
}

/**
 * City manager + GTFS import state (ARCHITECTURE.md §1.2 `ui.cities`, `vm`).
 * Copies the bundled demo cities to `filesDir/cities` on first run.
 */
class CityViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = AppStorage(app)

    private val _cities = MutableStateFlow<List<CityFile>>(emptyList())
    val cities: StateFlow<List<CityFile>> = _cities.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            storage.copyBundledCitiesIfNeeded()
            _cities.value = storage.listCities()
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _cities.value = storage.listCities()
        }
    }

    fun deleteCity(cityId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            storage.deleteCity(cityId)
            _cities.value = storage.listCities()
        }
    }

    /** Step 1: stream-parse the SAF-picked GTFS zip off the main thread. */
    fun startImport(uri: Uri, displayName: String) {
        if (_importState.value is ImportState.Parsing || _importState.value is ImportState.Building) return
        _importState.value = ImportState.Parsing
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val feed = resolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input.buffered()).let { GtfsParser.parse(it) }
                } ?: throw IllegalStateException("Could not open the selected file")
                val points = feed.stops.mapNotNull { stop ->
                    val lat = stop.lat ?: return@mapNotNull null
                    val lon = stop.lon ?: return@mapNotNull null
                    LatLng(lat, lon)
                }
                if (points.isEmpty()) {
                    _importState.value = ImportState.Failed("The feed contains no located stops")
                    return@launch
                }
                val center = LatLng(
                    points.sumOf { it.lat } / points.size,
                    points.sumOf { it.lon } / points.size,
                )
                _importState.value = ImportState.AwaitingBoundary(
                    displayName = displayName.ifBlank { "Imported city" },
                    feed = feed,
                    mapCenter = center,
                    defaultHull = GeoMath.convexHull(points),
                )
            } catch (e: Exception) {
                _importState.value = ImportState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Step 2: boundary confirmed — build the network and save the city file. */
    fun confirmBoundary(ring: List<LatLng>) {
        val awaiting = _importState.value as? ImportState.AwaitingBoundary ?: return
        if (ring.size < 3) return
        _importState.value = ImportState.Building
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cityId = slugify(awaiting.displayName) + "-" + (System.currentTimeMillis() / 1000)
                val result = NetworkBuilder.build(
                    feed = awaiting.feed,
                    boundary = Polygon(ring),
                    cityId = cityId,
                    displayName = awaiting.displayName,
                    attribution = "Imported from GTFS data by the feed publisher",
                )
                if (result.cityFile.stations.isEmpty()) {
                    _importState.value = ImportState.Failed("No stations remain inside the boundary")
                    return@launch
                }
                storage.saveCity(result.cityFile)
                _cities.value = storage.listCities()
                _importState.value = ImportState.Done(result.cityFile, result.report)
            } catch (e: Exception) {
                _importState.value = ImportState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun resetImport() {
        _importState.value = ImportState.Idle
    }

    private fun slugify(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "city" }

    /** IO helper for screens needing a single city by id. */
    suspend fun loadCity(cityId: String): CityFile? = withContext(Dispatchers.IO) {
        storage.loadCity(cityId)
    }
}
