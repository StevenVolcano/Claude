package io.terminus.core.engine

import io.terminus.core.cityfile.CityFile
import io.terminus.core.clock.TimeSource
import io.terminus.core.game.Player
import io.terminus.core.persistence.ReplayLog
import kotlinx.coroutines.CoroutineScope

/**
 * Sim-mode resume via command-log replay (ARCHITECTURE.md §5 `autosave.json`;
 * W6 caveat: the engine runtime is not serialized, so it is reconstructed by
 * re-applying the recorded command stream).
 */
object Replay {

    /**
     * Builds a [GameRunner] for [log]'s config, registers the standard AI brains
     * ([aiBrainsFor]), and replays the recorded commands so state, engine runtime,
     * and brain RNG streams all match the saved game exactly. The returned runner
     * has **not** been started: call `start()` to continue playing. The new
     * [timeSource] may start at 0 — the runner only consumes time *deltas*.
     */
    fun rebuildRunner(
        cityFile: CityFile,
        log: ReplayLog,
        timeSource: TimeSource,
        scope: CoroutineScope,
        ticker: Ticker = RealTicker(),
        players: List<Player>? = null,
    ): GameRunner {
        require(cityFile.cityId == log.cityId) {
            "replay log was recorded on '${log.cityId}', not '${cityFile.cityId}'"
        }
        val runner = GameRunner(cityFile, log.config, timeSource, scope, ticker, players)
        for (brain in aiBrainsFor(log.config, cityFile, players)) runner.registerBrain(brain)
        runner.replay(log.commands)
        return runner
    }
}
