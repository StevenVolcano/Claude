package io.terminus.core.persistence

import io.terminus.core.game.AiPersonality
import io.terminus.core.game.GameConfig
import io.terminus.core.game.GameEvent
import io.terminus.core.game.Player
import io.terminus.core.game.PlayerId
import io.terminus.core.game.Role
import io.terminus.core.geo.LatLng
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A named, saved set of setup options (GAME_DESIGN.md §8 item 11; ARCHITECTURE.md §5
 * `presets.json` stores a list of these).
 */
@Serializable
data class OptionsPreset(
    val schemaVersion: Int = 1,
    val name: String,
    val config: GameConfig,
)

/**
 * The persisted outcome of one round (ARCHITECTURE.md §5 `MatchRecord` contents,
 * §6 item 8; GAME_DESIGN.md §7).
 *
 * @property roundIndex 0-based index within the match.
 * @property seed the round's resolved RNG seed (manual entry enables replays, §8 item 10).
 * @property hiderId who hid this round.
 * @property roles role per player.
 * @property aiPersonalities personality per AI player this round; hidden personalities are
 *   revealed on the end screen from this record (GAME_DESIGN.md §6.4, §8 item 8).
 * @property hidingStationId the hider's (final) hiding-zone station, null if never hidden.
 * @property captured true when the hider was found before clock expiry.
 * @property capturedBy the capturing seeker credited on the round summary ("Caught by …"), if any.
 * @property captureGameMillis game time of capture, if captured.
 * @property capturePosition where the capture happened (for the EndScreen map snapshot), if captured.
 * @property hiderScore the hider's round score: survival minutes + bonus + penalties
 *   + 10.0 never-captured bonus (GAME_DESIGN.md §7).
 * @property survivalMinutes survival game-minutes from end of hiding phase, rounded to 0.1.
 * @property bonusMinutes time-bonus card minutes.
 * @property penaltyMinutes curse penalty minutes earned.
 * @property questionsAnswered tiebreak stat (§7).
 * @property cardsPlayed tiebreak stat (§7).
 * @property hidingPhaseMillis actual hiding-phase duration, game millis.
 * @property durationMillis total round duration, game millis.
 * @property events the round's full event log (questions, answers, cards, penalties).
 */
@Serializable
data class RoundRecord(
    val roundIndex: Int,
    val seed: Long,
    val hiderId: PlayerId,
    val roles: Map<PlayerId, Role>,
    val aiPersonalities: Map<PlayerId, AiPersonality> = emptyMap(),
    val hidingStationId: String? = null,
    val captured: Boolean,
    val capturedBy: PlayerId? = null,
    val captureGameMillis: Long? = null,
    val capturePosition: LatLng? = null,
    val hiderScore: Double,
    val survivalMinutes: Double,
    val bonusMinutes: Double,
    val penaltyMinutes: Double,
    val questionsAnswered: Int,
    val cardsPlayed: Int,
    val hidingPhaseMillis: Long,
    val durationMillis: Long,
    val events: List<GameEvent> = emptyList(),
)

/**
 * A persisted match (ARCHITECTURE.md §5: `history/match-<epochSec>.json`).
 * A match is 1/3/5 rounds with the hider role rotating; each player's match score is
 * the sum of their hider-round scores (GAME_DESIGN.md §7).
 *
 * @property createdEpochSec wall-clock creation time, also the file-name stem.
 * @property config the setup options the match was played with.
 * @property players all participants in rotation order.
 * @property rounds per-round records, in play order.
 * @property totalScores final match score per player.
 * @property winnerId highest total after tiebreaks, null for an abandoned match.
 */
@Serializable
data class MatchRecord(
    val schemaVersion: Int = 1,
    val createdEpochSec: Long,
    val config: GameConfig,
    val players: List<Player>,
    val rounds: List<RoundRecord>,
    val totalScores: Map<PlayerId, Double>,
    val winnerId: PlayerId? = null,
)

/**
 * The single shared JSON configuration for all persisted files
 * (ARCHITECTURE.md §1.1 `persistence`, §5): tolerant of unknown keys so older app
 * versions can read newer files, and explicit about defaults so schema evolution
 * stays visible on disk.
 */
object TerminusJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
