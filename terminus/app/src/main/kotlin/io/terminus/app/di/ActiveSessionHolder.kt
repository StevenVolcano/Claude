package io.terminus.app.di

import io.terminus.core.cityfile.CityFile
import io.terminus.core.game.GameConfig
import io.terminus.core.game.PlayerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Process-wide holder of the (at most one) running [GameSession]
 * (ARCHITECTURE.md §1.2 `service`): it outlives any single Activity or ViewModel so
 * the GPS foreground service can keep feeding fixes while the UI is backgrounded.
 */
object ActiveSessionHolder {

    @Volatile
    var session: GameSession? = null
        private set

    @Volatile
    var cityFile: CityFile? = null
        private set

    private var scope: CoroutineScope? = null

    /** The human participant's id in the active session. */
    val humanPlayerId: PlayerId
        get() = GameSessionFactory.HUMAN_PLAYER_ID

    /** Closes any previous session and starts a new one for [config] on [city]. */
    @Synchronized
    fun start(config: GameConfig, city: CityFile): GameSession {
        stop()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val newSession = GameSessionFactory.create(config, city, newScope)
        scope = newScope
        cityFile = city
        session = newSession
        return newSession
    }

    /** Closes the active session, if any. */
    @Synchronized
    fun stop() {
        session?.close()
        scope?.cancel()
        session = null
        cityFile = null
        scope = null
    }
}
