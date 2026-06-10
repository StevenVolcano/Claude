/**
 * Game loop package (ARCHITECTURE.md §1.1 `engine`): `GameRunner` — owns the coroutine
 * loop, consumes `GameCommand`s from a channel, ticks at 1 Hz real time, invokes AI
 * brains on their decision ticks, and emits `StateFlow<GameState>` + `Flow<GameEvent>`.
 *
 * Intentionally empty in Phase 0; the entire package is implemented by workstream W6
 * (ARCHITECTURE.md §7). This placeholder file only keeps the directory in version control.
 */
package io.terminus.core.engine
