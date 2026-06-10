# TERMINUS — Transit Hide & Seek

An Android game in tribute to *Jet Lag: The Game — Home Edition*. One player
hides somewhere in a city's transit network; seekers close in by asking
structured questions whose answers the app computes from open map and transit
data. The hider earns cards for every answer and spends them on curses,
decoys, and time bonuses. Play on real transit with GPS, or from the couch
against a simulated clock — always against real-time AI opponents, each with a
hidden personality (the Rat, the Ghost, the Bookkeeper, the Bloodhound, the
Showman).

Completely self-contained: no server, no accounts. OpenStreetMap tiles,
GTFS imports for any city, and two bundled synthetic demo cities
(Demoville Metro, Port Saltmarsh).

- **Game rules:** [GAME_DESIGN.md](GAME_DESIGN.md)
- **Technical architecture:** [ARCHITECTURE.md](ARCHITECTURE.md)
- **App build/run details + manual test script:** [app/README.md](app/README.md)
- **Project state / orchestration handoff:** [HANDOFF.md](HANDOFF.md)

## Highlights

- **7 question categories** (radius pings, compass calls, thermometers, line
  checks, station dossiers, lineups, rail ranges) — every answer computed
  automatically from coordinates, the transit graph, and station attributes.
- **A 50-card deck of 20 designs**: time bonuses, enforceable curses
  (Stalled Train, Local Service, Detour, Ticket Inspection…), vetoes, decoys,
  and relocation.
- **Real-time AI** hiders and seekers: candidate-set reasoning with
  information-gain question selection, difficulty levels, and five
  per-round seeded personalities, all deterministic per seed.
- **Any city**: import a GTFS zip, draw your boundary polygon, pick the
  allowed modes/routes, and play.
- **Two modes**: GPS (ride real transit; rule compliance auto-checked with
  penalties) and couch simulation (scaled game clock, pausable, resumable).

## Building

The repo splits into a pure-JVM `core` (game engine — no Android needed) and
the Android `app`:

```bash
# Core engine + full test suite (279 tests) — works anywhere with a JDK:
./gradlew :core:test

# Android APK — needs an Android SDK (the :app module is auto-included
# when ANDROID_HOME or local.properties is present):
./gradlew :app:assembleDebug
```

Or open this directory in Android Studio and press Run. CI builds a
sideloadable debug APK artifact (`terminus-debug-apk`) on every push.

Map tiles come from OpenStreetMap (© OpenStreetMap contributors); the app
caches tiles and respects the OSM tile usage policy. Transit data comes from
whatever GTFS feed you import; the bundled demo cities are synthetic.
