package io.terminus.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.terminus.app.data.AppStorage
import io.terminus.app.di.ActiveSessionHolder
import io.terminus.app.di.GameSession
import io.terminus.core.cityfile.CityCodec
import io.terminus.core.cityfile.CityFile
import io.terminus.core.game.GameCommand
import io.terminus.core.game.PlayerId
import io.terminus.core.transit.TransitNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds the game/end screens to the active [GameSession]
 * (ARCHITECTURE.md §1.2 `vm`): exposes the session, the city, the in-memory transit
 * graph, and runs the 30 s sim-mode autosave (ARCHITECTURE.md §5 `autosave.json`).
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = AppStorage(app)

    /** The active session, or null when no game is running. */
    val session: GameSession? = ActiveSessionHolder.session

    /** The city the active game is played on. */
    val cityFile: CityFile? = ActiveSessionHolder.cityFile

    /** The in-memory graph used for token coordinates and pickers. */
    val network: TransitNetwork? = cityFile?.let { CityCodec.toTransitNetwork(it) }

    /** The human participant's id. */
    val humanPlayerId: PlayerId = ActiveSessionHolder.humanPlayerId

    init {
        // Sim-mode autosave every 30 s real time (ARCHITECTURE.md §5): the session's
        // replay log; GPS sessions return null and are never autosaved.
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000L)
                val log = session?.replayLog() ?: continue
                storage.saveAutosave(log)
            }
        }
    }

    fun send(cmd: GameCommand) {
        session?.send(cmd)
    }

    /**
     * Records the match from the runner-produced per-round `RoundRecord`s (§7
     * totals and tiebroken winner included), clears the autosave, and closes the
     * session.
     */
    suspend fun endMatchAndRecord() {
        val active = session
        if (active != null) {
            val record = active.buildMatchRecord(System.currentTimeMillis() / 1000)
            withContext(Dispatchers.IO) {
                storage.saveMatch(record)
                storage.clearAutosave()
            }
        }
        ActiveSessionHolder.stop()
    }

    /** Abandons the game without recording it. */
    fun abandonGame() {
        viewModelScope.launch(Dispatchers.IO) { storage.clearAutosave() }
        ActiveSessionHolder.stop()
    }
}
