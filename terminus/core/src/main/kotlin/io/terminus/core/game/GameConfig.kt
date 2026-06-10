package io.terminus.core.game

import io.terminus.core.geo.LatLng
import io.terminus.core.geo.Polygon
import io.terminus.core.transit.TransitMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Play mode (GAME_DESIGN.md §1.2, §8 item 5). */
@Serializable
enum class PlayMode {
    /** Human physically rides real transit; device GPS positions the human. */
    GPS,

    /** Couch/simulation mode: human moves a token; scaled game clock drives movement. */
    SIM,
}

/** Question cooldown multiplier setup option (GAME_DESIGN.md §3 general rules, §8 item 7). */
@Serializable
enum class CooldownMultiplier(val factor: Double) {
    HALF(0.5),
    NORMAL(1.0),
    DOUBLE(2.0),
}

/**
 * The configured game boundary (GAME_DESIGN.md §8 item 2). Stations outside are excluded
 * and the network is clipped (via `TransitNetwork.clipTo`).
 */
@Serializable
sealed class BoundarySpec {
    /** Default: the city's full network extent hull. */
    @Serializable
    @SerialName("fullExtent")
    data object FullExtent : BoundarySpec()

    /** A drawn polygon of 3–30 vertices. */
    @Serializable
    @SerialName("polygon")
    data class PolygonBoundary(
        val polygon: Polygon,
    ) : BoundarySpec()

    /** A circle: tapped center plus radius 1–30 km. */
    @Serializable
    @SerialName("circle")
    data class CircleBoundary(
        val center: LatLng,
        val radiusMeters: Double,
    ) : BoundarySpec()
}

/**
 * Every option from the Game Setup screen (GAME_DESIGN.md §8; ARCHITECTURE.md §1.1 `game`).
 *
 * Defaults are the GPS-mode defaults from the design; the setup screen substitutes the
 * sim defaults (45 min game / 10 min hiding) when [playMode] is [PlayMode.SIM].
 *
 * @property cityId id of the imported/bundled city to play in (§8 item 1).
 * @property boundary boundary polygon/circle, default full network extent hull (§8 item 2).
 * @property allowedModes per-mode toggles; at least one route must remain (§8 item 3).
 * @property allowedRouteIds per-route selection; null means all routes of the allowed modes (§8 item 3).
 * @property startStationId start station; null means the highest-degree interchange inside
 *   the boundary (§8 item 4).
 * @property playMode GPS or couch/sim (§8 item 5).
 * @property timeScale sim-mode clock scale, one of {1, 2, 5, 10, 30}, default 10 (§2.2, §8 item 5).
 *   Ignored in GPS mode.
 * @property gameDurationMinutes total game duration in game-minutes, 20–180,
 *   default 60 GPS / 45 sim (§8 item 6).
 * @property hidingPhaseMinutes hiding-phase length in game-minutes, 5–30,
 *   default 15 GPS / 10 sim (§8 item 6).
 * @property cooldownMultiplier scales all question cooldowns (§8 item 7).
 * @property aiOpponents difficulty per AI player, size 1–3 (§8 item 8).
 * @property humanRole role of the human; if SEEKER, exactly one AI is the hider (§8 item 8).
 * @property rounds rounds per match: 1, 3, or 5 (§7, §8 item 9).
 * @property seed RNG seed; null means auto (timestamp at round start) (§8 item 10).
 */
@Serializable
data class GameConfig(
    val cityId: String,
    val boundary: BoundarySpec = BoundarySpec.FullExtent,
    val allowedModes: Set<TransitMode> = TransitMode.entries.toSet(),
    val allowedRouteIds: Set<String>? = null,
    val startStationId: String? = null,
    val playMode: PlayMode = PlayMode.GPS,
    val timeScale: Int = 10,
    val gameDurationMinutes: Int = 60,
    val hidingPhaseMinutes: Int = 15,
    val cooldownMultiplier: CooldownMultiplier = CooldownMultiplier.NORMAL,
    val aiOpponents: List<Difficulty> = listOf(Difficulty.MEDIUM),
    val humanRole: Role = Role.HIDER,
    val rounds: Int = 1,
    val seed: Long? = null,
)
