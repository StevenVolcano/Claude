# TERMINUS — Transit Hide & Seek

A single-phone tribute to transit hide-and-seek games. One hider vanishes into a city's transit network; seekers narrow them down with structured, data-computable questions. The hider is paid in cards for every answer and spends them on curses, decoys, and time bonuses. Played on real transit with GPS, or from the couch against a simulated clock — always against real-time AI opponents.

---

## 1. Premise, Roles, and Modes

### 1.1 Premise
The game is played inside a pre-configured geographic **boundary polygon** over a transit network (imported from GTFS or a bundled demo city). One player is the **Hider**; one to three players are **Seekers**. Exactly one participant is human; all others are AI. The hider hides near a station, answers seekers' questions truthfully (the app computes all answers), and earns cards. Seekers travel the network and try to physically reach the hider before the game clock expires.

### 1.2 The two play modes
- **GPS mode**: the human physically rides real transit. The app uses device GPS for the human's position. All AI players are virtual: they move along the transit graph in real time at realistic speeds. The app always knows every AI position and the human's GPS position, so every question, curse, and capture is automatically verifiable.
- **Couch / simulation mode**: the human moves a token along the transit graph on the map. A scaled game clock drives all movement (human token and AI tokens move at network edge speeds). Identical rules; positions are exact graph positions.

### 1.3 Single-phone constraint (design consequence)
Because there is one device, the human is either the hider or one seeker; the app simulates everyone else. Every rule below is enforced one of two ways:
- **On AI players**: enforced perfectly by the simulation (an AI under a freeze curse simply does not move).
- **On the human**: enforced by automatic GPS/position checks. Violating a constraint is detected by the app and triggers an automatic **penalty** (defined per rule) rather than relying on honor.

---

## 2. Round Structure and Timing

### 2.1 Phases
1. **Setup** (untimed): boundary, modes, durations, AI players, role selection. All players start at one configured **start station**.
2. **Hiding Phase** (default 15:00 GPS / 10:00 sim-minutes): the hider travels freely within the boundary; seekers are locked at the start station (AI seekers idle; a human seeker's app disables questions and shows a countdown — leaving 150 m of the start station during this phase incurs a penalty of −5:00 from total seek time, i.e., +5:00 to hider score). At phase end the hider must be inside a valid **hiding zone** (Section 5.1). If not, the hider's app picks the nearest valid station and the hider receives a 3:00 grace period to enter its zone; failure = hider score capped at hiding-phase length.
3. **Seeking Phase** (remainder of game duration, default total game 60:00 GPS / 45:00 sim): questions, cards, movement, capture all active.
4. **Final Approach** (sub-phase): triggers when any seeker enters the hider's hiding zone radius (300 m GPS / arrives at hider's node-adjacent edge in sim). Questions and new card plays are disabled (already-active effects keep running). Seekers sweep for the capture condition (Section 5.3). Final Approach ends only by capture or game-clock expiry.
5. **Round End**: capture or clock expiry. Scores recorded (Section 7); roles rotate for the next round.

### 2.2 Game clock
- A single `GameClock` drives everything. All durations in this document are **game time**.
- **GPS mode**: game time = wall time, 1:1. Tick resolution 1 second.
- **Sim mode**: game time = wall time × **time scale**, selectable {1×, 2×, 5×, 10×, 30×}, default 10×. The simulation advances in 1 sim-second steps; UI updates at 1 Hz real time. The clock is pausable in sim mode only (pausing freezes all timers, cooldowns, and AI). GPS mode is never pausable.
- All cooldowns, curse durations, and AI decision ticks are expressed in game time and therefore scale automatically.

---

## 3. Question Catalog

General rules:
- Only the seekers ask. Each question targets the hider's **true position** (GPS mode) or **token node** (sim mode). "Hider's nearest station" = nearest network station by haversine distance (sim mode: the node itself).
- Every question has a **per-category cooldown** plus a **global cooldown of 2:00** between any two questions (shared across all seekers — seekers act as one team for questions). Setup can scale all cooldowns by ×0.5 / ×1 / ×2.
- The hider has a **20-second response window** after a question is asked, during which they may play *Conductor's Override* (veto) or *Ghost Echo* (decoy). Otherwise the app answers automatically and truthfully at window end. If *Scrambled Signal* is active, delivery of the answer is delayed (Section 4).
- **Compensation**: after each delivered answer, the hider performs "**draw D, keep K**": draw D cards from the deck, choose K to keep, discard the rest. AI hiders choose keeps heuristically (Section 6.4).

| # | Category | Seeker chooses | Computed answer (data) | Compensation | Category cooldown |
|---|----------|----------------|------------------------|--------------|-------------------|
| Q1 | **Radius Ping** | Center = asking seeker's current position OR any station; radius R ∈ {500 m, 1 km, 2 km, 5 km} | `haversine(hider, center) ≤ R` → Yes/No | 500 m: draw 4 keep 2; 1 km: draw 3 keep 2; 2 km: draw 3 keep 1; 5 km: draw 2 keep 1 | 5:00 |
| Q2 | **Compass Call** | Reference station X; axis ∈ {N/S, E/W} | Compare hider latitude (N/S) or longitude (E/W) against X → "North"/"South"/"East"/"West" (ties resolved as the positive direction) | draw 2 keep 1 | 4:00 |
| Q3 | **Thermometer** | Armed at the seeker's current position; resolves only after that seeker has moved ≥ 750 m straight-line (GPS) or ≥ 2 graph edges (sim) | Compare `haversine(hider, armPos)` vs `haversine(hider, resolvePos)` → "Warmer"/"Colder" (equal → "Colder") | draw 3 keep 1 | 6:00 (starts when resolved) |
| Q4 | **Line Check** | Any route line L from the active network | Does hider's nearest station have L in its `routeIds`? → Yes/No | draw 2 keep 1 | 3:00 |
| Q5 | **Station Dossier** | One attribute from: (a) interchange? (≥2 distinct routes), (b) terminus? (first/last stop of any route pattern), (c) mode of nearest station ∈ {metro, tram, bus, rail, ferry} — "is it mode M?", (d) zone — "is it in fare zone Z?" (only if GTFS provided zones; option hidden otherwise) | Boolean from precomputed station attributes | draw 2 keep 1 | 3:00 |
| Q6 | **Lineup** | Seeker selects exactly 3 stations | Is hider's nearest station one of the 3? → Yes/No (never reveals which) | draw 2 keep 1 | 5:00 |
| Q7 | **Rail Range** | Reference station X; hop count N ∈ {2, 4, 8} | BFS hop distance on the transit graph (transfer edges count as 1 hop) from X to hider's nearest station ≤ N? → Yes/No | N=2: draw 3 keep 2; N=4: draw 3 keep 1; N=8: draw 2 keep 1 | 5:00 |

All seven are computable from: hider coordinates, station coordinates/attributes, route membership, and the graph adjacency — nothing else.

---

## 4. Card Catalog

### 4.1 Deck and hand rules
- **Single 50-card deck**, shuffled with the round's RNG seed. Discards form a discard pile; when the deck empties, the discard pile is reshuffled (same RNG stream).
- **Hand limit 6** (8 after *Bigger Bag*). If a draw would exceed the limit, the hider immediately discards down to the limit.
- Cards are **hider-only**. Play windows: any time during the Seeking Phase, except (a) during another card's resolution, (b) during Final Approach (active effects persist), and (c) *Conductor's Override* and *Ghost Echo* are playable **only** inside a question's 20-second response window.
- **At most 2 curses may be active simultaneously**; playing a third is disallowed by the app until one expires.
- Curse effects apply to **all seekers** unless stated. Enforcement: AI seekers obey perfectly; a human seeker who violates a constraint (detected by GPS checks evaluated every 5 s) triggers the listed **penalty**, and the curse timer restarts once per violation (max 1 restart).

### 4.2 The cards (20 designs, 50 cards total)

**Time Bonuses** (play any time in Seeking Phase; immediately and visibly added to the hider's score; the played card is announced to seekers):

| # | Card | Count | Effect |
|---|------|-------|--------|
| C1 | Rush Hour Delay | 7 | +3:00 to hider's final score. |
| C2 | Express Skip | 4 | +5:00 to hider's final score. |
| C3 | Night Owl Service | 2 | +10:00 to hider's final score. |

**Curses** (announced to seekers when played; a countdown is shown):

| # | Card | Count | Effect and enforcement |
|---|------|-------|------------------------|
| C4 | Curse of the Stalled Train | 3 | For 4:00, seekers may not move. Sim: tokens frozen mid-edge. GPS: human seeker moving > 100 m from their position at play time → penalty +2:00 hider score. |
| C5 | Curse of the Local Service | 2 | For 10:00, seekers must dwell at every station they pass. Sim: token pauses 45 sim-seconds at each node. GPS: human must remain within 75 m of each station's coordinates for 30 s when their track passes within 75 m of it; each missed dwell → penalty +1:00 hider score. |
| C6 | Curse of Tunnel Vision | 2 | Radius Ping (Q1) is disabled for 8:00. Enforced by the question engine. |
| C7 | Curse of the Scrambled Signal | 2 | The next answered question's answer is withheld for 5:00 after computation, then delivered (cooldowns start at delivery). Enforced by the question engine. |
| C8 | Curse of the U-Turn | 2 | Each seeker must return to the **previous station they visited** (app tracks per-seeker visit history) before the team may ask another question. Sim: enforced movement requirement. GPS: question UI stays locked until the human seeker's GPS is within 100 m of that station. No timer; self-clearing. |
| C9 | Curse of the Ticket Inspection | 3 | For 3:00, each seeker must stay within 100 m of their current nearest station. Sim: token snaps to and holds the node. GPS violation → penalty +1:30 hider score. |
| C10 | Curse of the Detour | 2 | Hider picks one route line; seekers may not use it for 12:00. Sim: edges of that route excluded from path planning. GPS: if the human seeker's track advances > 200 m along that route's stop-sequence corridor (within 150 m of its polyline) → penalty +2:00 hider score. |

**Hider Utility:**

| # | Card | Count | Effect |
|---|------|-------|--------|
| C11 | Conductor's Override (Veto) | 3 | Played in the response window: the question is cancelled — no answer, no compensation. Its category cooldown and the global cooldown still apply. |
| C12 | Ghost Echo (Decoy) | 2 | Played in the response window of a Q1, Q2, or Q3 only. The hider picks any point within 1.5 km of their true position (AI hider: a scored decoy point); the answer is computed from the decoy point. Immediately after delivery, seekers are told "that answer was a decoy" (but not the true answer). Normal compensation is earned. |
| C13 | Transfer Slip (Relocate) | 2 | The hider may leave the hiding zone and re-hide. GPS: 10:00 travel window to enter a new valid hiding zone (capture remains active throughout; overrun → score frozen at the moment the window expired until they re-hide). Sim: move up to 5 edges, then must stop at a node. Seekers are notified "the hider is relocating" at play time, not where. |
| C14 | Lost & Found | 3 | Discard up to 3 cards, draw the same number. |
| C15 | Found Wallet | 3 | Draw 2, keep 2. |
| C16 | Off-Peak Pass | 2 | The next answered question grants double compensation (draw 2D keep 2K). |
| C17 | Bigger Bag | 1 | Hand limit becomes 8 for the rest of the round (persists; play costs the card). |
| C18 | Dead Zone | 2 | No questions may be asked for 6:00. Question engine lockout. |
| C19 | Service Change | 2 | Hider picks one question category; its cooldown is increased by +5:00 for the rest of the round (stacks if both copies are played on the same category). |
| C20 | Golden Ticket | 1 | Draw 3, keep 3. |

Totals: 13 time-bonus + 16 curse + 21 utility = **50 cards**.

---

## 5. Hider Rules

### 5.1 Hiding zone
- A valid hiding zone is the disc of **300 m radius around any station** whose coordinates lie inside the boundary polygon and which is served by at least one **allowed mode/route** (per setup). Sim mode: the hiding spot is **exactly a node**.
- The start station and stations within 2 graph hops of it are **excluded** as hiding zones.

### 5.2 Movement after the hiding phase
- GPS: the hider must remain within their 300 m hiding zone. The app checks every 5 s; each excursion beyond 300 m for more than 60 cumulative seconds pauses score accrual until they return (no honor system — score simply stops). *Transfer Slip* (C13) is the only sanctioned relocation.
- Sim: the hider token is locked to its node except via C13.

### 5.3 Capture
- **GPS mode**: a seeker position (human GPS or AI simulated position) within **75 m** of the hider position, sustained for **10 consecutive seconds**, during Seeking Phase or Final Approach → hider is found. During Final Approach, AI seekers execute a sweep pattern: visit the hiding-zone station node, then orbit waypoints at 60° intervals on a 150 m ring at walking speed (1.4 m/s) until the 75 m condition is met against the human hider's GPS.
- **Sim mode**: a seeker token occupying the **same node** as the hider token → found. During Final Approach, AI seekers path directly to the hider's node.
- On capture: clock stops; survival time recorded.

---

## 6. AI Player Design

All AI decisions use a **seeded RNG** (round seed) and are deterministic given the seed and game history. AI runs on **decision ticks** of the game clock; between ticks, AI tokens move continuously along their planned graph paths at edge speeds.

### 6.1 AI Hider — choosing a hiding spot
At hiding-phase start, score every eligible station `s`:

`score(s) = 0.40·T(s) + 0.25·R(s) + 0.20·A(s) + 0.15·rand(s)`

- `T(s)`: normalized network travel time from the start station (Dijkstra) — farther is better, but capped at what is reachable within 85% of the hiding phase (unreachable stations score 0).
- `R(s)`: remoteness = normalized mean haversine distance to the 5 nearest other stations (sparse areas are harder to bracket with Radius Pings).
- `A(s)`: ambiguity = fraction of all stations sharing `s`'s full Station Dossier attribute tuple (mode, interchange?, terminus?, zone) — bigger cluster is better.
- `rand(s)`: uniform [0,1] from the seed.

Difficulty: **Easy** picks uniformly from the top 50% by `T` only. **Medium** uses the full formula. **Hard** uses the full formula and additionally rejects stations whose name appears as a terminus of any line (high-salience spots), and pre-plans one decoy point for C12 at the position within 1.5 km that maximally flips a Radius Ping answer.

The AI hider then paths to the spot (Dijkstra, time-weighted) and arrives during the hiding phase.

### 6.2 AI Hider — card play heuristics (evaluated each decision tick)
- Play time bonuses immediately when hand > limit − 2, largest first.
- Veto (C11): veto an incoming question if its expected information gain against the AI's *own* modeled candidate set exceeds a threshold (Easy: never veto; Medium: veto Q1@500 m and Q6 only; Hard: veto when modeled gain > 1.2 bits).
- Curses: play a movement curse (C4/C9) when the nearest seeker's travel time to the hiding zone < 8:00; play C10 (Detour) on the line the nearest seeker is currently using; play C18 (Dead Zone) when ≥ 2 questions were answered in the last 6:00.
- Keep rule for draw-keep: priority order C11 > C12 > C3 > C10 > C2 > C4 > others.

### 6.3 AI Seekers — candidate-set reasoning
Each AI seeker team maintains one shared **CandidateSet**: a weight `w(s) ∈ [0,1]` per station, initialized uniform over eligible hiding stations.

- **Answer update**: every delivered answer partitions stations into consistent/inconsistent. Consistent: `w` unchanged; inconsistent: `w(s) ×= ε` where ε = 0 (Hard), 0.05 (Medium), 0.15 (Easy — sloppy filtering). Decoy-flagged answers (C12 reveal) are discarded entirely by Medium/Hard; Easy applies them anyway at ε = 0.5. Renormalize after each update. For Radius/Compass/Thermometer questions, a station is "consistent" if **any point of its 300 m hiding disc** is consistent (GPS mode); sim mode tests the node exactly.
- **Question selection** (when global + category cooldowns allow): enumerate a fixed menu of ~40 candidate questions (each category with parameter choices anchored to the current top-10 weighted stations and the seeker's position). For each, compute expected post-question entropy of the CandidateSet; pick the question maximizing `(entropy reduction) / (1 + cooldownMinutes/10)`. Easy instead picks uniformly among the top 3.
- **Movement**: target = the station maximizing `w(s) / (1 + travelTimeMinutes(seeker → s)/10)`; path via time-weighted Dijkstra; re-plan on every tick where the target changed. With multiple AI seekers, **Hard** assigns seekers to distinct weight clusters (greedy: each seeker claims the best unclaimed cluster among the top-k weighted stations grouped by 4-hop neighborhoods); Easy/Medium all chase the same best target.
- **Endgame**: when a seeker is at the top-weighted station and `w(top) > 0.5`, it commits and triggers Final Approach behavior on arrival in the zone.

### 6.4 Personalities (gameplay styles)

Every AI player is additionally assigned a **personality** — a consistent play style held for the whole round. Personality is orthogonal to difficulty: difficulty sets *competence* (decision tick rate, filtering accuracy, info-gain quality), personality sets *style*. Personalities are drawn per AI per round from the round seed **without replacement** (two AIs in the same round never share one), so they are deterministic for a given seed. By default they are **hidden** — the player experiences them only through behavior — but setup offers Hidden / Revealed / Manual-per-AI.

A personality is a `PersonalityProfile` of modifiers applied on top of the difficulty profile:

| Modifier | Meaning |
|---|---|
| `temperature` | randomness when choosing among scored options (0 = always best; high = near-random) |
| `spotWeights` (T, R, A, rand) | overrides the hider spot-scoring weights of §6.1 |
| `obscurityBias` | extra weight for low-degree, non-interchange, non-terminus stations (hider) and for checking low-weight candidates (seeker) |
| `cardAggression` ∈ [0,1] | how early/freely curses and bonuses are played vs hoarded |
| `decoyPropensity` ∈ [0,1] | probability of spending Ghost Echo when eligible |
| `vetoDelta` (bits) | adjustment to the difficulty's veto threshold (negative = vetoes more) |
| `questionRate` ∈ [0,1] | probability of asking as soon as cooldowns allow vs traveling first (seeker) |
| `commitment` ∈ [0,1] | how sticky the seeker's current target is before re-planning |

The five personalities:

| Personality | Style | Key modifiers |
|---|---|---|
| **The Rat** | Rat mode — chaotic and unreadable. Near-random hiding spot, near-random question/target choice, erratic card play. | temperature 2.0; spotWeights (0, 0, 0, 1); cardAggression random per tick; questionRate 0.5; commitment 0.2 |
| **The Ghost** | Obscure-stop exploiter. Hides at remote, low-salience stations no one thinks of; as a seeker, distrusts the obvious and sweeps unlikely candidates. | temperature 0.3; spotWeights (0.20, 0.45, 0.25, 0.10); obscurityBias strong (rejects interchanges and termini outright as hider); vetoDelta −0.3; questionRate 0.6 |
| **The Bookkeeper** | Information maximizer. As seeker, asks at every cooldown and moves only on confidence; as hider, vetoes high-gain questions and hoards utility cards. | temperature 0.1; questionRate 1.0; commitment: moves only when top weight > 0.4; vetoDelta −0.4; cardAggression 0.3 |
| **The Bloodhound** | Movement-first. As seeker, commits early to the top candidate and travels hard, asking only cheap questions en route; as hider, picks the farthest reachable spot and burns movement curses early. | temperature 0.2; spotWeights (0.60, 0.20, 0.05, 0.15); questionRate 0.3; commitment 0.9; vetoDelta +0.5; cardAggression 0.7 (movement curses first) |
| **The Showman** | Card-aggressive gambler. Plays curses, decoys, and bonuses the moment they're legal; as seeker, chases the latest answer region aggressively and re-plans constantly. | temperature 0.6; cardAggression 0.9; decoyPropensity 0.9; questionRate 0.8; commitment 0.3 |

Determinism rule: all personality-driven randomness (temperature draws, Rat's choices) consumes the same seeded RNG stream as the rest of the AI, so a round replayed with the same seed is identical.

### 6.5 Difficulty summary

| Parameter | Easy | Medium | Hard |
|---|---|---|---|
| Decision tick | 45 s | 25 s | 15 s |
| Answer filtering ε | 0.15 | 0.05 | 0 |
| Question choice | random of top 3 | max info gain | max gain/cost, decoy-aware |
| Multi-seeker coordination | none | none | cluster assignment |
| Hider spot scoring | distance only | full formula | full + salience rejection + planned decoy |
| Hider veto | never | fixed list | gain-threshold |
| AI travel speed multiplier | 0.85× | 1.0× | 1.0× |

---

## 7. Scoring Across Rounds

- **Hider score per round** = survival game-minutes (from end of hiding phase to capture or clock end, rounded to 0.1 min) + time-bonus card minutes + curse penalty minutes earned + **10.0 flat bonus if never captured**.
- A match is **1, 3, or 5 rounds** (setup option); the hider role rotates through all players (human and AI) in fixed order. Each participant's match score is the **sum of their hider-round scores**. Highest total wins. Tiebreak: fewer questions answered during one's hider rounds; then fewer cards played.
- The capturing seeker is credited on the round summary ("Caught by …") but seeking earns no points — pressure to catch fast comes from suppressing the opponent's hider score.

---

## 8. Game Setup Options Screen (exact contents)

1. **City**: pick from imported/bundled cities.
2. **Boundary**: (a) draw polygon on map, 3–30 vertices, tap-to-add/drag/undo; or (b) circle: tap center + radius slider 1–30 km. Default: city's full network extent hull. Stations outside are excluded; the network is clipped.
3. **Allowed transit**: per-mode toggles (metro/tram/bus/rail/ferry) and per-route checkboxes (grouped by mode), populated from the city file. At least one route required.
4. **Start station**: pick on map or list (default: highest-degree interchange inside the boundary).
5. **Mode**: GPS / Couch (sim). Sim only: **time scale** {1,2,5,10,30}×, default 10×.
6. **Durations**: game duration slider 20–180 min (default 60 GPS / 45 sim, in game time); hiding phase slider 5–30 min (default 15 GPS / 10 sim).
7. **Question cooldown multiplier**: ×0.5 / ×1 / ×2.
8. **Opponents**: number of AI players 1–3; per-AI difficulty Easy/Medium/Hard; **personalities**: Hidden (default — drawn from the round seed, revealed only on the end screen) / Revealed / Manual per AI (pick from the five styles of §6.4); **role selection**: human plays Hider or Seeker (if Seeker, exactly one AI is the hider and remaining AI are co-seekers).
9. **Rounds**: 1 / 3 / 5.
10. **RNG seed**: auto (timestamp) or manual entry (for replays).
11. **Save as preset** (named) / load preset.
