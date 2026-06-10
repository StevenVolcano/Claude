package io.terminus.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.terminus.app.data.AppStorage
import io.terminus.app.di.ActiveSessionHolder
import io.terminus.core.cityfile.CityFile
import io.terminus.core.game.AiPersonality
import io.terminus.core.game.AiPersonalityMode
import io.terminus.core.game.BoundarySpec
import io.terminus.core.game.CooldownMultiplier
import io.terminus.core.game.Difficulty
import io.terminus.core.game.GameConfig
import io.terminus.core.game.PlayMode
import io.terminus.core.game.Role
import io.terminus.core.geo.LatLng
import io.terminus.core.geo.Polygon
import io.terminus.core.persistence.OptionsPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How the boundary is being specified on the setup screen (GAME_DESIGN.md §8 item 2). */
enum class BoundaryChoice { FULL_EXTENT, POLYGON, CIRCLE }

/**
 * Every option of GAME_DESIGN.md §8 as editable UI state. Defaults are the GPS-mode
 * defaults; switching to SIM substitutes the sim defaults for the durations.
 */
data class SetupUiState(
    val cityId: String? = null,
    val boundaryChoice: BoundaryChoice = BoundaryChoice.FULL_EXTENT,
    val polygonVertices: List<LatLng> = emptyList(),
    val circleCenter: LatLng? = null,
    val circleRadiusKm: Float = 5f,
    val disabledModes: Set<io.terminus.core.transit.TransitMode> = emptySet(),
    val disabledRouteIds: Set<String> = emptySet(),
    val startStationId: String? = null,
    val playMode: PlayMode = PlayMode.GPS,
    val timeScale: Int = 10,
    val gameDurationMinutes: Int = 60,
    val hidingPhaseMinutes: Int = 15,
    val cooldownMultiplier: CooldownMultiplier = CooldownMultiplier.NORMAL,
    val aiDifficulties: List<Difficulty> = listOf(Difficulty.MEDIUM),
    val personalityMode: AiPersonalityMode = AiPersonalityMode.HIDDEN,
    val manualPersonalities: List<AiPersonality> = listOf(AiPersonality.RAT),
    val humanRole: Role = Role.HIDER,
    val rounds: Int = 1,
    val seedText: String = "",
)

/** A validation problem keyed for display; `message` is already user-readable. */
data class SetupError(val message: String)

/**
 * Setup screen state + validation + presets + game start
 * (ARCHITECTURE.md §1.2 `ui.setup`, `vm`; GAME_DESIGN.md §8).
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = AppStorage(app)

    private val _ui = MutableStateFlow(SetupUiState())
    val ui: StateFlow<SetupUiState> = _ui.asStateFlow()

    private val _presets = MutableStateFlow<List<OptionsPreset>>(emptyList())
    val presets: StateFlow<List<OptionsPreset>> = _presets.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _presets.value = storage.loadPresets()
        }
    }

    fun update(transform: (SetupUiState) -> SetupUiState) {
        _ui.value = transform(_ui.value)
    }

    /** Switches play mode, substituting the design's default durations (§8 item 6). */
    fun setPlayMode(mode: PlayMode) {
        _ui.value = _ui.value.copy(
            playMode = mode,
            gameDurationMinutes = if (mode == PlayMode.SIM) 45 else 60,
            hidingPhaseMinutes = if (mode == PlayMode.SIM) 10 else 15,
        )
    }

    /** Adjusts the AI opponent count, keeping difficulties/personalities in step. */
    fun setOpponentCount(count: Int) {
        val n = count.coerceIn(1, 3)
        val s = _ui.value
        val difficulties = List(n) { s.aiDifficulties.getOrElse(it) { Difficulty.MEDIUM } }
        val personalityPool = AiPersonality.entries
        val personalities = List(n) { i ->
            s.manualPersonalities.getOrElse(i) {
                personalityPool.first { p -> p !in s.manualPersonalities.take(i) }
            }
        }
        _ui.value = s.copy(aiDifficulties = difficulties, manualPersonalities = personalities)
    }

    /** All §8 validation rules; an empty list means the config is playable. */
    fun validate(city: CityFile?): List<SetupError> {
        val s = _ui.value
        val errors = ArrayList<SetupError>()
        if (city == null) errors += SetupError("Pick a city")
        if (s.boundaryChoice == BoundaryChoice.POLYGON && s.polygonVertices.size !in 3..30) {
            errors += SetupError("Boundary polygon needs 3–30 vertices (has ${s.polygonVertices.size})")
        }
        if (s.boundaryChoice == BoundaryChoice.CIRCLE && s.circleCenter == null) {
            errors += SetupError("Tap the map to place the boundary circle's center")
        }
        if (city != null) {
            val allowedRoutes = city.routes.filter {
                it.mode !in s.disabledModes && it.id !in s.disabledRouteIds
            }
            if (allowedRoutes.isEmpty()) errors += SetupError("At least one route must stay enabled")
        }
        if (s.hidingPhaseMinutes >= s.gameDurationMinutes) {
            errors += SetupError("The hiding phase must be shorter than the game")
        }
        if (s.personalityMode == AiPersonalityMode.MANUAL &&
            s.manualPersonalities.distinct().size != s.aiDifficulties.size
        ) {
            errors += SetupError("Manual personalities must be distinct (one per AI)")
        }
        if (s.seedText.isNotBlank() && s.seedText.trim().toLongOrNull() == null) {
            errors += SetupError("The seed must be a whole number (or blank for auto)")
        }
        return errors
    }

    /** Builds the [GameConfig] for [city]; call only when [validate] returns empty. */
    fun buildConfig(city: CityFile): GameConfig {
        val s = _ui.value
        val boundary = when (s.boundaryChoice) {
            BoundaryChoice.FULL_EXTENT -> BoundarySpec.FullExtent
            BoundaryChoice.POLYGON -> BoundarySpec.PolygonBoundary(Polygon(s.polygonVertices))
            BoundaryChoice.CIRCLE -> BoundarySpec.CircleBoundary(
                center = s.circleCenter!!,
                radiusMeters = s.circleRadiusKm.toDouble() * 1000.0,
            )
        }
        val allowedModes = io.terminus.core.transit.TransitMode.entries.toSet() - s.disabledModes
        val allowedRouteIds = if (s.disabledRouteIds.isEmpty()) {
            null
        } else {
            city.routes
                .filter { it.mode in allowedModes && it.id !in s.disabledRouteIds }
                .map { it.id }
                .toSet()
        }
        return GameConfig(
            cityId = city.cityId,
            boundary = boundary,
            allowedModes = allowedModes,
            allowedRouteIds = allowedRouteIds,
            startStationId = s.startStationId,
            playMode = s.playMode,
            timeScale = s.timeScale,
            gameDurationMinutes = s.gameDurationMinutes,
            hidingPhaseMinutes = s.hidingPhaseMinutes,
            cooldownMultiplier = s.cooldownMultiplier,
            aiOpponents = s.aiDifficulties,
            aiPersonalityMode = s.personalityMode,
            manualPersonalities = if (s.personalityMode == AiPersonalityMode.MANUAL) {
                s.manualPersonalities.take(s.aiDifficulties.size)
            } else {
                null
            },
            humanRole = s.humanRole,
            rounds = s.rounds,
            seed = s.seedText.trim().toLongOrNull(),
        )
    }

    /** Creates the session in the process-wide holder; the caller then navigates to `game`. */
    fun startGame(city: CityFile) {
        ActiveSessionHolder.start(buildConfig(city), city)
    }

    // ---------------------------------------------------------------- presets

    fun savePreset(name: String, city: CityFile) {
        val preset = OptionsPreset(name = name, config = buildConfig(city))
        viewModelScope.launch(Dispatchers.IO) {
            val updated = _presets.value.filter { it.name != name } + preset
            storage.savePresets(updated)
            _presets.value = updated
        }
    }

    fun deletePreset(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = _presets.value.filter { it.name != name }
            storage.savePresets(updated)
            _presets.value = updated
        }
    }

    /** Loads a preset back into the editable UI state. */
    fun applyPreset(preset: OptionsPreset, city: CityFile?) {
        val c = preset.config
        val boundaryChoice = when (c.boundary) {
            BoundarySpec.FullExtent -> BoundaryChoice.FULL_EXTENT
            is BoundarySpec.PolygonBoundary -> BoundaryChoice.POLYGON
            is BoundarySpec.CircleBoundary -> BoundaryChoice.CIRCLE
        }
        val disabledModes = io.terminus.core.transit.TransitMode.entries.toSet() - c.allowedModes
        val disabledRoutes = if (c.allowedRouteIds == null || city == null) {
            emptySet()
        } else {
            city.routes.map { it.id }.toSet() - c.allowedRouteIds!!
        }
        _ui.value = SetupUiState(
            cityId = c.cityId,
            boundaryChoice = boundaryChoice,
            polygonVertices = (c.boundary as? BoundarySpec.PolygonBoundary)?.polygon?.vertices.orEmpty(),
            circleCenter = (c.boundary as? BoundarySpec.CircleBoundary)?.center,
            circleRadiusKm = ((c.boundary as? BoundarySpec.CircleBoundary)?.radiusMeters?.div(1000.0))?.toFloat()
                ?: 5f,
            disabledModes = disabledModes,
            disabledRouteIds = disabledRoutes,
            startStationId = c.startStationId,
            playMode = c.playMode,
            timeScale = c.timeScale,
            gameDurationMinutes = c.gameDurationMinutes,
            hidingPhaseMinutes = c.hidingPhaseMinutes,
            cooldownMultiplier = c.cooldownMultiplier,
            aiDifficulties = c.aiOpponents,
            personalityMode = c.aiPersonalityMode,
            manualPersonalities = c.manualPersonalities
                ?: AiPersonality.entries.take(c.aiOpponents.size),
            humanRole = c.humanRole,
            rounds = c.rounds,
            seedText = c.seed?.toString().orEmpty(),
        )
    }
}
