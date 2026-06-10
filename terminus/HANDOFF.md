# TERMINUS — Orchestration Handoff / State Document

> Living document for session continuity. Updated by the orchestrator at every
> phase commit. If a session dies, a fresh session can resume from here.
> Last updated: 2026-06-10 ~14:20 UTC.

## What this project is

TERMINUS — Transit Hide & Seek: a self-contained Android tribute to
Jet Lag: The Game Home Edition. One hider vs seekers on a real transit
network inside a configurable boundary; data-computable questions; a
50-card deck; real-time AI opponents with hidden personalities; GPS mode
(ride real transit) and couch/simulation mode. Open data only (OSM tiles,
GTFS imports, bundled synthetic demo cities). No server, no accounts.

- Branch: `claude/jet-lag-android-game-q54kzg` (repo `StevenVolcano/Claude`); never push elsewhere.
- Specs (binding): `terminus/GAME_DESIGN.md`, `terminus/ARCHITECTURE.md`.
- Build: `cd terminus && ./gradlew :core:test` (pure-JVM core; **205 tests green** as of Phase 1).
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
| 2 W6 | game reducer + GameRunner | **IN FLIGHT** (agent relaunched 14:14 UTC after limit cutoff; first attempt wrote nothing) |
| 2 W7 | AI brains (candidate set, info gain, personalities) | DONE (35 tests), committed. Brains rebuild candidate state by replaying eventLog; W6 must invoke hider brain when a response window opens (see agent caveats in commit history) |
| 2 W8 | Android app module | partial committed (26 files: manifest, theme, map, home/cities/import, VMs, storage, GPS service, di facade); completion agent **IN FLIGHT** for MainActivity/nav, setup/game/end/history screens, strings, README |
| 3 | Integration: wire W7 brains into W6 runner + app `di/GameSessionFactory.kt`, AI-vs-AI smoke test (seed 42, byte-identical event logs), full-suite verify | NOT STARTED |

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

## Remaining work after in-flight agents land

1. Commit W6/W7/W8 results (verify `./gradlew :core:test` full-green first; commit per workstream as earlier in history).
2. Phase 3 integration agent: implement `GameSessionFactory` against real `GameRunner` + W7 brains; ARCHITECTURE §6 item 8 smoke test (AI hider vs 2 AI seekers, Demoville, sim, seed 42, terminates ≤45 sim-min, deterministic event logs); autosave/resume wiring check; full suite green.
3. Final review pass (orchestrator): docs accuracy, README at terminus/ root (how to play/build), push, confirm CI core-tests job green and APK job builds.
4. Report to user: how to download the APK artifact and sideload.

## Session-limit protocol (user instruction, 2026-06-10)

When session usage nears the limit (~80%) or any limit warning appears:
stop launching new agents, update this document, commit and push everything
(including WIP, labeled as such), and notify the user that work is paused.
Background agents cut off by limits lose unwritten work — prefer committing
WIP from the working tree before relaunching.
