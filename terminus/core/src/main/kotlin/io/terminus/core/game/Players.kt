package io.terminus.core.game

import io.terminus.core.geo.LatLng
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable identifier of a participant in a match (ARCHITECTURE.md §1.1 `game`).
 * Serializes as a plain JSON string, so it is usable as a map key.
 */
@Serializable
@JvmInline
value class PlayerId(val value: String)

/**
 * Role of a player within one round (GAME_DESIGN.md §1.1: one hider, one to three seekers).
 * Roles rotate between rounds (GAME_DESIGN.md §7).
 */
@Serializable
enum class Role {
    HIDER,
    SEEKER,
}

/** AI difficulty level — sets *competence* (GAME_DESIGN.md §6.5, §8 item 8). */
@Serializable
enum class Difficulty {
    EASY,
    MEDIUM,
    HARD,
}

/**
 * AI personality — a per-round play *style* orthogonal to difficulty
 * (GAME_DESIGN.md §6.4). Drawn per AI per round from the round seed without
 * replacement; hidden by default. The modifier values live in
 * `io.terminus.core.ai.PersonalityProfile` (W7's package); the enum lives here so
 * `GameConfig`/`GameState` need not depend on `ai`.
 */
@Serializable
enum class AiPersonality(val displayName: String) {
    /** Chaotic and unreadable: near-random spot, question, and target choices. */
    RAT("The Rat"),

    /** Obscure-stop exploiter: remote, low-salience stations; distrusts the obvious. */
    GHOST("The Ghost"),

    /** Information maximizer: asks at every cooldown, moves only on confidence. */
    BOOKKEEPER("The Bookkeeper"),

    /** Movement-first: commits early and travels hard, cheap questions en route. */
    BLOODHOUND("The Bloodhound"),

    /** Card-aggressive gambler: plays curses, decoys, and bonuses the moment they're legal. */
    SHOWMAN("The Showman"),
}

/**
 * A match participant (ARCHITECTURE.md §1.1 `game`). Exactly one participant is human
 * (GAME_DESIGN.md §1.3); all others are [AiPlayer]s.
 */
@Serializable
sealed class Player {
    abstract val id: PlayerId
    abstract val name: String

    /** The single human participant (GAME_DESIGN.md §1.3). */
    @Serializable
    @SerialName("human")
    data class HumanPlayer(
        override val id: PlayerId,
        override val name: String,
    ) : Player()

    /** A simulated participant with a fixed difficulty (GAME_DESIGN.md §6). */
    @Serializable
    @SerialName("ai")
    data class AiPlayer(
        override val id: PlayerId,
        override val name: String,
        val difficulty: Difficulty,
    ) : Player()
}

/**
 * A player's position in either play mode (GAME_DESIGN.md §1.2; ARCHITECTURE.md §1.1).
 *
 * GPS mode uses [GpsPosition] for the human; AI players and sim-mode tokens use
 * [NodePosition] (at a station) or [EdgePosition] (travelling along an edge).
 * `sim`'s token position reuses [EdgePosition] rather than defining a duplicate type.
 */
@Serializable
sealed class PlayerPosition {
    /** A raw GPS fix position (GPS mode human player). */
    @Serializable
    @SerialName("gps")
    data class GpsPosition(
        val latLng: LatLng,
    ) : PlayerPosition()

    /** Exactly at a network node (sim tokens at stations; sim hiding spots, GAME_DESIGN.md §5.1). */
    @Serializable
    @SerialName("node")
    data class NodePosition(
        val stationId: String,
    ) : PlayerPosition()

    /**
     * Part-way along a directed edge (sim tokens in motion; AI players in GPS mode).
     *
     * @property fromStationId edge origin.
     * @property toStationId edge destination.
     * @property routeId route of the edge, null for a walking transfer edge.
     * @property fraction progress along the edge in [0, 1].
     */
    @Serializable
    @SerialName("edge")
    data class EdgePosition(
        val fromStationId: String,
        val toStationId: String,
        val routeId: String? = null,
        val fraction: Double,
    ) : PlayerPosition()
}
