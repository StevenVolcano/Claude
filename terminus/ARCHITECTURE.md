# TERMINUS — Technical Architecture

## 1. Module Layout

```
terminus/
  settings.gradle.kts        # always includes :core; includes :app only if an
                             # Android SDK is detected (ANDROID_HOME or local.properties sdk.dir)
  build.gradle.kts
  core/                      # pure Kotlin/JVM. Deps (Maven Central only):
                             # kotlinx-serialization-json, kotlinx-coroutines-core,
                             # JUnit (test). No Android, no osmdroid.
  app/                       # Android (minSdk 26, target 35). Compose, Navigation-Compose,
                             # osmdroid, kotlinx-serialization. Depends on :core.
```

`settings.gradle.kts` performs the SDK check with plain file/env inspection so CI without dl.google.com builds and tests `:core` alone.

### 1.1 `core` package structure (`io.terminus.core.*`)

| Package | Responsibility |
|---|---|
| `geo` | `LatLng`, `Polygon`, `BoundingBox`; `GeoMath`: `haversineMeters`, `bearingDeg`, `destinationPoint`, `pointInPolygon` (ray casting, lon-wrap safe), `distanceToPolylineMeters`, `convexHull` |
| `transit` | `Station` (id, name, latLng, mode, routeIds, isInterchange, isTerminus, zoneId?, underground?), `RouteLine` (id, shortName, longName, mode, colorHex, orderedStationIds), `TransitEdge` (fromId, toId, routeId? null=walk, travelTimeSec, distanceMeters), `TransitNetwork` (stations, routes, adjacency; queries: `nearestStation(LatLng)`, `dijkstraTime(from,to)`, `bfsHops(from)`, `clipTo(Polygon)`, `filterModes/Routes`) |
| `gtfs` | `GtfsParser` (CSV per RFC 4180, streams from a `ZipInputStream`), `NetworkBuilder`, `GtfsImportReport` (counts, warnings) |
| `cityfile` | `CityFile` DTO + versioned JSON schema, `CityCodec` (kotlinx-serialization + GZIP via `java.util.zip`) |
| `clock` | `TimeSource` interface (`nowGameMillis`), `RealTimeSource`, `ScaledTimeSource(scale, pausable)`, `GameClock` (phase timers, scheduled events) |
| `game` | `GameConfig` (incl. `aiPersonalityMode`: HIDDEN/REVEALED/MANUAL + optional manual assignments), `GamePhase` enum, `PlayerId`, `Player` sealed (`HumanPlayer`, `AiPlayer(difficulty)`), `Role`, `GameState` (immutable: phase, clock snapshot, player positions, hider zone, active effects, cooldowns, hand, deck/discard counts, event log, scores), `GameCommand` sealed (AskQuestion, PlayCard, Move, GpsFix, Tick, …), `GameEvent` sealed, `GameEngine` — a pure reducer `(GameState, GameCommand) -> (GameState, List<GameEvent>)`, `ScoreKeeper`, `Penalty` rules |
| `questions` | `QuestionSpec` sealed class per category Q1–Q7 with exact parameters, `Answer`, `QuestionEngine.answer(spec, hiderPos, network)`, `CooldownTracker`, `CompensationRule` (draw/keep table) |
| `cards` | `CardType` enum (C1–C20 with metadata: count, kind, playWindow), `Deck` (seeded shuffle, reshuffle-on-empty), `Hand` (limit), `CardEngine` (validate/apply), `ActiveEffect` (type, expiryGameMillis, params), `EffectEnforcer` (evaluates positions against `MovementConstraint`s every check tick → `Violation` events with penalties), `QuestionConstraint` (category disable, lockout, cooldown bump, delay, double-compensation) |
| `sim` | `TokenPosition` (edge, fraction), `MovementPlan` (path of edges + dwell rules), `SimulationEngine.tick(dtSec)` advancing all non-GPS actors, `PathPlanner` (time Dijkstra honoring banned routes/curses), edge speeds from edge travel times; walking speed 1.4 m/s for off-graph sweep waypoints |
| `ai` | `CandidateSet` (weights, update(answer), entropy), `QuestionMenu` generator, `InfoGain`, `AiSeekerBrain.decide(state): List<GameCommand>`, `AiHiderBrain` (spot scoring, card heuristics), `DifficultyProfile` (the table in GAME_DESIGN 6.5), `AiPersonality` enum (RAT, GHOST, BOOKKEEPER, BLOODHOUND, SHOWMAN) + `PersonalityProfile` (modifiers per GAME_DESIGN 6.4: temperature, spotWeights, obscurityBias, cardAggression, decoyPropensity, vetoDelta, questionRate, commitment) applied on top of difficulty; per-round seeded assignment without replacement, seeded `kotlin.random.Random` injected |
| `persistence` | `OptionsPreset`, `RoundRecord`, `MatchRecord` DTOs; the shared `Json` instance (ignoreUnknownKeys, schemaVersion field) |
| `engine` | `GameRunner`: owns the loop — consumes commands from a channel, ticks at 1 Hz real time (advancing N sim-seconds per tick), invokes AI brains on their decision ticks, emits `StateFlow<GameState>` + `Flow<GameEvent>`. Pure JVM; the app supplies GPS fixes as `GameCommand.GpsFix`. |

### 1.2 `app` package structure (`io.terminus.app.*`)

| Package | Responsibility |
|---|---|
| `MainActivity`, `nav` | Single-activity Compose; Navigation-Compose routes: `home`, `cities`, `import`, `setup`, `game`, `end`, `history` |
| `ui.home` | HomeScreen: New Game, Cities, History, Resume (if a saved in-progress sim game exists) |
| `ui.cities` | CityManagerScreen: list bundled/imported cities (station/route counts, delete), Import button → SAF document picker for GTFS zip → boundary-draw map step → background import (a `viewModelScope` coroutine with progress UI) → writes `.city.json.gz` |
| `ui.setup` | GameSetupScreen exactly per GAME_DESIGN §8; boundary editor as an osmdroid `MapView` in `AndroidView` with tap-to-add-vertex overlay |
| `ui.game` | GameScreen: full-screen osmdroid map (boundary polygon overlay, route polylines colored, station markers, seeker tokens, hider zone circle when known/own, human GPS dot); bottom bar with three sheets: **QuestionPanel** (category grid, parameter pickers, cooldown timers), **HandPanel** (hider's cards, play buttons, active-effect countdowns), **ActivityLog** (timestamped GameEvents: questions, answers, card announcements, penalties); top bar: phase + game clock + (sim) pause/scale |
| `ui.end` | EndScreen: round summary, capture point map snapshot, score table, next-round / end-match |
| `ui.history` | Past matches from JSON records |
| `service` | `GameForegroundService` (GPS mode only): `android.location.LocationManager` GPS provider, 2 s / 5 m updates, persistent notification with phase + clock; forwards fixes as `GameCommand.GpsFix` to the running `GameRunner`; survives backgrounding |
| `map` | osmdroid setup (tile cache dir, user agent), overlay builders from `GameState` |
| `vm` | `GameViewModel`: instantiates `GameRunner` from `GameConfig` + `CityFile`, exposes `StateFlow<GameState>` collected with `collectAsStateWithLifecycle`; `SetupViewModel`, `CityViewModel` |
| `data` | `AppStorage`: files under `context.filesDir` — `cities/*.city.json.gz`, `presets.json`, `history/match-<epochSec>.json`, `autosave.json` (sim mode autosave every 30 s real) |

The map is osmdroid's `MapView` wrapped in `AndroidView` with `OnlineTileSourceBase` = OpenStreetMap Mapnik, disk tile cache, and offline tolerance (game functions without tiles; overlays still render on a blank grid).

## 2. Core Domain Flow

One coroutine-based loop (`GameRunner`) per game:
1. Real-time ticker (1 Hz) → `GameCommand.Tick(gameMillisDelta)` (delta = 1000 × timeScale).
2. Reducer applies tick: advance `SimulationEngine`, expire `ActiveEffect`s, run `EffectEnforcer` GPS checks (every 5 s game time), check capture condition, check phase transitions.
3. On AI decision-tick boundaries, run `AiSeekerBrain`/`AiHiderBrain` against the current state; their returned commands are enqueued.
4. UI and service push `GpsFix`, `AskQuestion`, `PlayCard`, `MoveToken` commands into the same channel.
5. Every transition emits `GameEvent`s consumed by the ActivityLog and EndScreen.

Determinism: with `ScaledTimeSource` replaced by a test-stepped clock and a fixed seed, the entire AI-vs-AI game is reproducible (this is the smoke test).

## 3. GTFS Import Pipeline

Parsed files: `stops.txt`, `routes.txt`, `trips.txt`, `stop_times.txt`, `calendar.txt` (+ `calendar_dates.txt` if present; if neither exists, all services are assumed active). `shapes.txt` is ignored (route polylines are drawn station-to-station). Streaming, line-by-line; `stop_times.txt` may be hundreds of MB — never fully load into memory; aggregate per trip pattern.

Build steps:
1. **Service selection**: pick the weekday `service_id` set with the most trips (Mon–Fri from `calendar.txt`).
2. **Stop merging**: group stops by `parent_station` when present; otherwise merge stops within **50 m** of each other that share a normalized name (lowercase, punctuation stripped). Merged node coordinate = centroid; node id = stable hash of member stop_ids.
3. **Edges**: for each trip on a selected service, each consecutive `stop_times` pair (A,B) contributes a directed edge sample with travel time = `departure(B) − departure(A)` (fallback arrival). Per merged (A,B,route) the edge travel time = **median** of samples, clamped to [30 s, 30 min]. Mode from `routes.route_type` (0 tram, 1 metro, 2 rail, 3 bus, 4 ferry; others → bus).
4. **Walking transfers**: undirected edges between merged nodes within **250 m** haversine, travel time = distance / 1.2 m/s + 60 s, `routeId = null`.
5. **Attributes**: `isInterchange` = ≥2 distinct routeIds; `isTerminus` = node is first/last of any route's dominant stop pattern (most frequent ordered pattern per route); `zoneId` from `stops.zone_id` (majority among merged members); `underground` only if `stops.location_type`/feed extensions provide it, else null.
6. **Boundary clip**: drop nodes outside the user polygon; drop dangling edges; keep the largest connected component; report dropped counts.
7. Serialize to `CityFile`.

### 3.1 CityFile format
GZIP-compressed JSON (kotlinx-serialization), extension `.city.json.gz`:
`{ schemaVersion: 1, cityId, displayName, attribution, isSynthetic: Boolean, bbox, stations: [...], routes: [...], edges: [...], defaultStartStationId }` with stations/routes/edges exactly mirroring the `transit` domain classes. Expected size for a metro-scale city: 200 KB–2 MB compressed.

## 4. Bundled Demo Cities (decision: synthetic, hand-authored)

Because the build environment cannot download GTFS feeds, the two demo cities are **hand-authored synthetic networks**, generated deterministically by a `core` object (`DemoCities`) and also checked in as `.city.json.gz` under `app/src/main/assets/cities/` and `core/src/test/resources/cities/`. Both are flagged `isSynthetic = true` and labeled "(demo city)" in the UI.

**Demoville Metro** — a generic mid-size grid city, centered at (52.050, 5.080), spanning ~10 × 8 km:
- **Line A (Red, metro)**: 14 stations east–west, spacing ~750 m; stations A01 "Westfield Depot" … A14 "Harbor East". Stations A05–A09 flagged `underground = true`, zone 1; rest zone 2.
- **Line B (Blue, metro)**: 13 stations north–south, B01 "North Heath" … B13 "South Pier"; crosses Line A at the shared interchange "Central Cross" (= A07 = B07).
- **Line C (Green, tram)**: 12 stations on an L-shaped diagonal loop through the southwest, C01 … C12; interchanges with Line A at A04 "Old Market" (= C03) and with Line B at B10 "Garden Bridge" (= C09).
- Totals: **36 distinct stations** (3 shared interchanges), 4 termini, edge travel times 90–150 s (metro) / 120–180 s (tram), 6 walking-transfer edges. Default start: Central Cross.

**Port Saltmarsh** — a small coastal town, centered at (54.300, −1.500), ~7 × 6 km:
- **Line 1 (Orange, tram)**: 12 stations along the coast, S01 "Lighthouse Point" … S12 "Salt Quay".
- **Line 2 (Purple, bus)**: 11 stations inland loop, interchanging at S04 "Town Hall" and S09 "Fish Market".
- **Ferry F (Teal)**: 3 stops crossing the harbour (S12 "Salt Quay" → F01 "Mid Anchorage" → F02 "East Sands"), 6-minute crossings.
- Totals: **26 distinct stations**, zones 1/2, no underground stations, default start: Town Hall.

Exact coordinates are computed by `DemoCities` from base point + offsets (so the checked-in JSON and the generator always agree; a unit test asserts equality).

## 5. Persistence

All plain files, written atomically (temp + rename), schemas owned by `core.persistence`:
- `filesDir/cities/<cityId>.city.json.gz` — imported + (copied-on-first-run) bundled cities.
- `filesDir/presets.json` — list of `OptionsPreset`.
- `filesDir/history/match-<epochSec>.json` — `MatchRecord` (config snapshot, per-round `RoundRecord`: seed, scores, question/card event log, capture info).
- `filesDir/autosave.json` — sim-mode in-progress `GameState` snapshot (GPS mode is not resumable; the foreground service keeps it alive instead).

## 6. Test Plan (`core/src/test`)

1. `geo`: haversine against known city-pair distances (±0.5%); point-in-polygon including concave polygons, points on edges, and longitude-wrap; distance-to-polyline.
2. `gtfs`: a **fixture GTFS zip built in-memory by the test** (8 stops, 2 routes, 6 trips, deliberate parent_station + 40 m duplicate stop, one outlier travel time) → assert merged node count, median edge times, transfer edges, attribute flags, boundary clipping, and import report warnings.
3. `cityfile`: round-trip serialize/deserialize Demoville; generator-vs-checked-in-resource equality.
4. `questions`: for each Q1–Q7, table-driven cases on Demoville with known hider positions asserting exact answers, including hiding-disc consistency edge cases and tie rules (Compass positive-direction, Thermometer equal→Colder).
5. `cards`: deck composition sums to 50; seeded shuffle determinism; hand-limit discards; every C1–C20 effect applied to a synthetic `GameState` and asserted (timers, lockouts, cooldown bumps, double compensation, decoy answer substitution + reveal, veto cooldown retention); curse-cap of 2; `EffectEnforcer` violation/penalty cases from scripted GPS tracks.
6. `clock/sim`: scaled time stepping; token edge interpolation; dwell enforcement under C5; banned-route path planning under C10.
7. `ai`: CandidateSet updates per answer type (exact filtering at ε=0, decoy discard); info-gain ranking picks the bisecting Radius Ping on a contrived 2-cluster layout; hider spot scoring excludes unreachable stations; difficulty profiles differ as specified; personality assignment is deterministic per seed and without replacement; behavioral differentiation (on a scripted scenario, a BOOKKEEPER seeker asks strictly more questions than a BLOODHOUND; a GHOST hider never picks an interchange or terminus; RAT runs are seed-reproducible).
8. **Smoke test**: full AI-hider vs 2 AI-seeker game on Demoville, sim mode, stepped clock, seed 42 — must terminate (capture or expiry) within 45 sim-minutes, produce a valid `RoundRecord`, and produce byte-identical event logs on two runs.

## 7. Implementation Order and Parallel Workstreams

**Phase 0 — Skeleton (single agent, blocking):** all `core` package directories; the **data-only types**: `geo` value classes, `transit` data classes, `game` `GameState`/`GameCommand`/`GameEvent`/`GameConfig`, `questions` `QuestionSpec`, `cards` `CardType` enum, `cityfile` DTOs, `persistence` DTOs. No logic. This freezes every cross-package interface so later streams never edit each other's files.

**Phase 1 — parallel, exclusive directory ownership:**
- **W1 Geo + Transit logic** — owns `core/src/main/kotlin/io/terminus/core/geo/`, `.../transit/` (+ their tests).
- **W2 GTFS + CityFile + DemoCities** — owns `.../gtfs/`, `.../cityfile/`, `core/src/test/resources/cities/`.
- **W3 Questions** — owns `.../questions/`.
- **W4 Cards + Effects** — owns `.../cards/`.
- **W5 Clock + Sim** — owns `.../clock/`, `.../sim/`.

**Phase 2 — parallel:**
- **W6 Game engine** — owns `.../game/` logic + `.../engine/` (integrates W3–W5; starts once Phase 1 merges).
- **W7 AI** — owns `.../ai/`.
- **W8 App scaffolding** — owns `app/` entirely: navigation, theming, CityManager, Setup screen, osmdroid map composables, storage.

**Phase 3 — integration (single agent):** smoke test, GPS foreground service wiring, autosave/resume, asset copy of demo cities.

No two workstreams write to the same directory; Phase 0 is the only shared-file stage.
