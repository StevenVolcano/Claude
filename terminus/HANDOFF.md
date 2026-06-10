# TERMINUS — Orchestration Handoff / State Document

> Living document for session continuity. Updated by the orchestrator at every
> phase commit. If a session dies, a fresh session can resume from here.
> Last updated: 2026-06-10 (Phase 3 integration complete).

## What this project is

TERMINUS — Transit Hide & Seek: a self-contained Android tribute to
Jet Lag: The Game Home Edition. One hider vs seekers on a real transit
network inside a configurable boundary; data-computable questions; a
50-card deck; real-time AI opponents with hidden personalities; GPS mode
(ride real transit) and couch/simulation mode. Open data only (OSM tiles,
GTFS imports, bundled synthetic demo cities). No server, no accounts.

- Branch: `claude/jet-lag-android-game-q54kzg` (repo `StevenVolcano/Claude`); never push elsewhere.
- Specs (binding): `terminus/GAME_DESIGN.md`, `terminus/ARCHITECTURE.md`.
- Build: `cd terminus && ./gradlew :core:test` (pure-JVM core; **279 tests green** as of Phase 3).
  The `:app` Android module is included only when an Android SDK is present
  (this cloud container has none and cannot reach dl.google.com). CI workflow
  `.github/workflows/terminus-build.yml` runs core tests + builds a
  sideloadable debug APK artifact on every push to this branch.

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 0 | Frozen data-type contract (all core packages) | DONE, committed |
| 1 W1 | geo math + transit graph queries | DONE (29 tests), committed |
| 1 W2 | GTFS parser/builder, CityCodec, DemoCities + artifacts | DONE (26 tests), committed |
| 1 W3 | question engine + cooldown tracker | DONE (27 tests), committed |
| 1 W4 | deck/hand/card engine + GPS effect enforcer | DONE (74 tests), committed |
| 1 W5 | time sources, path planner, simulation, sweep | DONE (49 tests), committed |
| 2 W6 | game reducer + GameRunner | DONE (34 tests; full suite 274 green). Round seed = (config.seed ?: 0) + roundIndex; RoundEnded has no record payload — use GameRunner.roundRecords; sim resume needs command-log replay (engine runtime not serialized) |
| 2 W7 | AI brains (candidate set, info gain, personalities) | DONE (35 tests), committed. Brains rebuild candidate state by replaying eventLog; W6 must invoke hider brain when a response window opens (see agent caveats in commit history) |
| 2 W8 | Android app module | DONE (36 files; verified with embedded Kotlin compiler against core — only Android-SDK symbols unresolved, expected). Cannot compile in this container; CI builds the APK. Phase 3 wiring points listed in app/README.md |
| 3 | Integration: W7→W6 brain wiring (`engine/BrainAdapter.kt`: `asAiBrain` + per-round `RoundAwareAiBrain` + `aiBrainsFor`), §6-item-8 smoke test + 3-round match test (`core/src/test/.../integration/`), sim autosave/resume via command-log replay (`persistence.ReplayLog` + `GameRunner.commandLog/replay` + `engine.Replay`), real app `GameSessionFactory`/autosave/Resume wiring | DONE (279 tests green). Notes: `TerminusJson` classDiscriminator is now `"kind"` (default `"type"` collided with `CardPlayed.type`/`PlayCard.type` — polymorphic persistence would have crashed); `GameRunner` now invokes the hider brain the moment a response window opens (W7 caveat); seed-42 smoke round ends by expiry (hider DV-A14, score 63.0), 3-round match has a round-2 capture and winner ai-2 |

## Key integration contracts (told to in-flight agents)

- W6 defines `interface AiBrain { val playerId: PlayerId; val decisionTickMillis: Long; fun decide(state: GameState, nowGameMillis: Long): List<GameCommand> }`; W7 exposes structurally identical `TerminusAiBrain` + factories `createSeekerBrain/createHiderBrain(playerId, difficulty, personality, network, seed)`. Phase 3 wires them (trivial adapter if shapes drifted).
- App binds engine/ai ONLY via `app/.../di/GameSessionFactory.kt` (TODO-marked); everything else uses the `GameSession` facade (StateFlow<GameState> / SharedFlow<GameEvent> / send(cmd)).
- Edge convention: ALL TransitEdge entries directed; walk transfers = two directed edges. `dijkstraTime` returns `Int?` seconds.
- Cooldowns: `recordAnswered` fires at thermometer RESOLUTION, Scrambled-Signal DELIVERY, and for VETOED questions. C6/C18 reuse the frozen cooldown fields; C19 stacks `categoryCooldownBonusMillis`.
- Deck determinism: pre-shuffled `Random(seed)`, k-th reshuffle uses `Random(seed + k)`.
- `ActorState` requires non-empty MovementPlan — idle actors stay out of `SimulationEngine.tick`.
- `EffectEnforcer.checkHiderZone` is skipped/reset during a Transfer Slip relocation window; C12's 1.5 km / sim C13's ≤5-edge checks are W6's to verify with the network.
- DemoCities ids: `DV-A01..A14`, `DV-B01..B13` (no B07), `DV-C01..C12` (no C03/C09); start `DV-A07` Central Cross. Saltmarsh `PS-*`, start `PS-S04` Town Hall. Loops have no termini — use `station.isTerminus`.
- Test resources: `core/src/test/resources/cities/*.city.json.gz`; regenerate via `TERMINUS_CITY_ARTIFACTS_ROOT=/home/user/Claude/terminus ./gradlew :core:test --tests 'io.terminus.core.cityfile.*'`.

## Remaining work

1. Final review pass (orchestrator): docs accuracy, README at terminus/ root (how to play/build), push, confirm CI core-tests job green and the APK job builds (the app module compiles only in CI — this container has no Android SDK; Phase 3 app changes were type-checked file-by-file against the core jar with the embedded Kotlin compiler, only Android-SDK symbols unresolved).
2. Report to user: how to download the APK artifact and sideload.

## Session-limit protocol (user instruction, 2026-06-10)

When session usage nears the limit (~80%) or any limit warning appears:
stop launching new agents, update this document, commit and push everything
(including WIP, labeled as such), and notify the user that work is paused.
Background agents cut off by limits lose unwritten work — prefer committing
WIP from the working tree before relaunching.
