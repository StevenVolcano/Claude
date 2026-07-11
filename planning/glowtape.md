# Glow Tape — Planning Document

*A free, community-owned production and rehearsal platform for Grays Harbor theater.*

Status: **planning complete, ready for execution planning** · Decided 2026-07-11

---

## 1. Vision

One app — Android, iOS, and web — that carries a play or musical from casting through
closing night, and connects the Grays Harbor theater community between shows.
It replaces the current patchwork of Band, Discord, group texts, email chains, and
multi-tab Google Sheets with a single place that a 15-year-old in 7th Street Kids and
a 70-year-old props volunteer can both use without help.

- **Free forever.** Offered to Grays Harbor organizations (Driftwood Players, Bishop
  Center for the Performing Arts, Stage West Community Theater, Plank Island, schools,
  7th Street Kids, and others). No plans to sell. Setup is white-glove: if a director
  wants it, we help them set it up.
- **Self-hosted and community-owned.** Runs on a small VPS (~$5–15/month total).
  If a hosted commercial tool dies, its users' data dies with it; Glow Tape's doesn't.
- **No AI in the product.** The community is AI-averse. Every feature below is
  deterministic; the "scene partner" tools use recordings and text mechanics proven in
  pre-AI apps. (Optional plain OS text-to-speech is the only borderline item and is
  strictly opt-in.)

### Name

**Glow Tape** (glowtape.net) — the tape that glows in the blackout so everyone can
find their way backstage: the thing that quietly shows people where to go in the
dark. Theater-authentic, self-explaining after one sentence, and a clean namespace
(the only "glow tape" on the web is physical tape vendors). Local identity lives in
the tagline: *"Glow Tape — Grays Harbor's theater community."*

(History: the original pick was Ghostlight, dropped 2026-07-11 for namespace
crowding — Ghostlight Records, the Ghostlight ETC app, Ghostlight Live, and the
2024 film. Other runners-up: Spike Tape; The Callboard — most literal but crowded
[callboards.app, VirtualCallboard]; Q2Q — taken by sound-cueing software; Prompt
Book — reads AI-ish now; Places!; Green Room; Harborlight — too many Harbor
Lights.)

---

## 2. Decisions made (2026-07-11)

| Decision | Choice |
|---|---|
| Name | **Glow Tape** (glowtape.net) |
| Platform | **PWA-first** — installable web app; no App Store / Play Store at launch |
| MVP scope | **Core only** — schedule + conflicts + calls + acknowledgments, channels/announcements, contacts |
| Business model | Free, no monetization, Grays Harbor community only |
| AI in product | None (deterministic features only) |
| Script licensing stance | Rehearse-what-you-have-rights-to; coexist with licensor tools (ProductionPro etc.), don't work around them |

### Open decisions (defer to execution kickoff)

- **Backend**: PocketBase (single Go binary: auth, files, realtime, SQLite — lowest ops)
  vs. a conventional monolith (e.g. one Next.js/Remix or Rails/Django app + Postgres).
  Recommendation leans PocketBase + thin React/Svelte PWA for a solo maintainer, but
  decide when build starts.
- **Hosting provider**: any $5–12/mo VPS (Hetzner CX22 ≈ €4.5, DigitalOcean $6) behind
  Caddy; object storage (Backblaze B2 / Cloudflare R2) for PDFs if local disk gets tight.
- **Repository**: Glow Tape should live in its own dedicated repo; this document lives
  here only because planning happened in this workspace.

---

## 3. Product structure: three layers

### 3.1 The Hub (all Grays Harbor users)

- Community calendar: auditions, performances, events across all member organizations.
- Open community channels (general chat, classifieds, ride shares).
- **Shared resource pool** — a cross-theater lending inventory (props, costumes,
  set pieces, equipment: "Stage West needs a chaise; Driftwood has one in storage").
  No existing tool at any price serves this.
- Community directory: people who do lights, sound, sewing, set building, and are
  willing to be asked.
- Audition postings visible to the whole community.

### 3.2 The Organization (each theater)

- Own space, own admins, own seasons.
- **Persistent assets across shows** — costume stock, prop inventory, people (with
  measurements, skills, roles history). Commercial tools purge this (Callboards.app
  deletes operational data 30 days after closing); season continuity at $0 is a
  first-of-kind feature.

### 3.3 The Production (daily life of a show)

Profiles/roles per production: director, assistant director, stage manager, performers,
crew (props, costumes, set, lighting, sound, front-of-house…). People see only what
they're part of.

#### Schedule & calls — the killer feature

- Cast/crew enter conflicts once; the SM builds rehearsals against a live view of who's
  available (pattern proven by Cast98 and Planning Center Services).
- Every event lists exactly **who is called** and at what granularity ("Act II only,"
  "dancers at 6, full cast at 7").
- One-tap **"got it" acknowledgment** on schedule changes and announcements; the SM
  sees who hasn't seen the update. Directly kills "I never saw the email."
- Reminders before calls; every notification mirrors to email (see §5).

#### Messaging

- Channels per production: all-call, cast, crew, production team; org-level and
  hub-level channels too.
- Discord-shaped but drastically simpler — no servers/threads/reactions maze.
- Pinned, acknowledgment-tracked announcements.

#### Trackers (the Google-Sheet killers)

Structured tables with the right columns built in, replacing the multi-tab sheet:

- Props (item, scene, character, source, status, home)
- Costumes (piece, character, scene, measurements link, status)
- Set pieces
- Contact sheet (printable)
- Lighting master / lighting & sound **cue sheets — paperwork only**; QLab owns cue
  execution and Glow Tape stays out of that lane.
- **CSV import** so an existing production's Google Sheet migrates in minutes; CSV
  export always available (no lock-in).

#### Rehearsal & performance reports

SM fills a structured form → formatted report emails to the distribution list.
(Every SM currently hand-assembles these in Word; VirtualCallboard's report templates
are its most-cited paid feature.)

#### Script room & rehearsal tools

See §4.

---

## 4. Scripts, materials, and rehearsal tools

### Licensing stance

The goal is efficient rehearsal, not routing around licensors.

- Standard MTI / Concord / Dramatic Publishing terms prohibit scanning or reproducing
  rented and purchased materials. Glow Tape does not become a script locker.
- **Materials library is per-production and private**: uploads visible only to that
  production's roster; view-and-annotate in app; no public sharing, no cross-production
  sharing, rights affirmation checkbox at upload. DMCA agent + takedown policy if ever
  needed.
- For MTI musicals the honest path is the **$199 ProductionPro add-on** to the show
  license. ProductionPro has no public API, so "integration" means coexisting: link out
  to the licensed script; Glow Tape handles everything else.
- Where Glow Tape's annotation layer fully shines: original works, owned-rights
  materials, permissive houses (e.g. The Licensing House allows unlimited printing),
  and **public domain** — a built-in library seeded from Project Gutenberg / Folger
  (all of Shakespeare, Wilde, Chekhov, Shaw, Ibsen) makes the script features fully
  usable on day one with zero risk.

### Annotation (free/open-source path is viable)

- PDF.js (Apache-2.0) rendering + our own annotation overlay stored as JSON in the
  database — works on image-only scans (coordinate-anchored), no OCR required.
  Commercial PDF SDKs run $1.5K–$100K+/yr and are out of budget.
- Tools: highlights, margin text notes, freehand ink (stylus), blocking-stamp palette
  (circled character initials, movement arrows, X = cross, entrance/exit), per-page
  facing-page canvas for mini ground plans, user-defined legend.
- **Layers**: private per-user notes vs. shared production notes that the director/SM
  publishes to the cast (Scriptation's model). Layer visibility toggles.

### Scene partner — zero AI, all proven mechanics

Borrowed from Rehearsal Pro, LineLearner, and coldRead (all pre-AI, shipped, loved):

1. **Record-a-part** (the flagship): each cast member records their own lines on their
   phone; Glow Tape assembles the full-cast audio track; you rehearse against your
   actual castmates' voices with an adjustable gap where your line goes, shrinking the
   gap as you get confident. LineLearner proved the mechanic but makes sharing awkward
   — Glow Tape already has the whole cast in one production space.
2. Highlight-my-lines (one tap to claim a character).
3. Blackout/hide-my-lines with press-to-peek.
4. Cue-line flashcards: see the cue, speak, tap to reveal.
5. Prompt button (reveal/play the next line on demand).
6. Scene looping, adjustable playback speed, per-character pitch shift.
7. Karaoke-style fixed-speed teleprompter scroll (no voice tracking).
8. Optional plain OS text-to-speech stand-in voice (offline, not generative; opt-in).

---

## 5. Ease-of-use constitution

Non-negotiable design principles, in priority order:

1. **No passwords.** Email a 6-digit one-time code (codes beat magic links — links die
   in spam filters and get eaten by link-scanning security tools). Directors add people
   by email address or a QR code at the read-through.
2. **Fully functional in a plain browser tab.** Home-screen install is encouraged with
   a guided, illustrated, per-device flow — never required.
3. **Every notification mirrors to email.** Someone who never opens the app still gets
   the schedule and the changes. Nothing is app-only.
4. **You see only what you're in.** A props volunteer sees their production's schedule
   and the props list, not an empty maze of channels.
5. **Everything important prints cleanly.** Contact sheets, schedules, cue sheets,
   reports. Some people want paper; give it to them beautifully.
6. Big tap targets, readable type, no jargon in UI copy ("Who's called" not "Roster
   assignment matrix").

---

## 6. Technical plan

- **PWA-first, no app stores at launch.** iOS web push has worked since 16.4 for
  home-screen-installed PWAs (VAPID, no Apple developer account); iOS 26 opens
  home-screen sites as web apps by default, softening install friction; Android PWAs
  install like native apps (WebAPK). Store wrapping (Capacitor) is a later option only
  if iOS adoption measurably stalls — costs $99/yr Apple (waivable only for a legal
  501(c)(3) — a partner theater could sponsor this), Google's 12-tester/14-day
  closed-test gauntlet for new personal accounts, and Guideline 4.2 wrapper-rejection
  risk.
- **One boring monolith** on one VPS behind Caddy. Leading candidate: PocketBase
  (auth incl. email OTP, file storage, realtime subscriptions, SQLite) + a thin
  React/Svelte PWA. Alternative: conventional monolith + Postgres. 500 users across
  6 orgs is far inside single-server comfort.
- **Realtime chat** via PocketBase realtime or SSE/websockets from the monolith.
- **Files**: local disk or Cloudflare R2/Backblaze B2 (~$0.015/GB-mo, zero egress on
  R2); stream PDFs with range requests for bad theater wifi.
- **Offline tolerance**: service-worker precache of app shell + cached schedule/script
  data in IndexedDB; queue writes client-side and flush on reconnect (iOS has no
  Background Sync — accept it).
- **Email**: free-tier transactional provider (SES/Resend/Brevo) for OTP codes and
  notification mirroring.
- **Backups**: nightly SQLite backup (Litestream) or pg_dump, rclone'd offsite.
- **Cost**: $5–12/mo VPS + ~$0–1/mo storage + ~$0 email = **$5–15/month total, $0
  store fees.**
- **Don't assemble from generic OSS** (Mattermost + Nextcloud + ChurchCRM): it solves
  the admin's problem while recreating the end-user problem — multiple apps, multiple
  logins. Steal feature models instead (Cast98's conflict→schedule sync, Planning
  Center's team→position→person calls with accept/decline, Callboards' read-ack).

---

## 7. Roadmap

1. **Phase 1 — Pilot core** (one willing production): people/roles/invites,
   conflicts → schedule → who-is-called with acknowledgments, channels, announcements,
   contact sheet, email mirroring, OTP login. *Smallest thing that beats Band + Sheets.*
2. **Phase 2 — Sheet-killers**: props/costumes/set/cue trackers with CSV import/export,
   rehearsal & performance reports.
3. **Phase 3 — Script room**: PDF viewer + annotation layers, public-domain library,
   record-a-part and line-learning tools.
4. **Phase 4 — The Hub**: cross-org calendar, lending inventory, community channels,
   directory — once two or more theaters are aboard.

Out of scope, deliberately: cue execution (QLab), ticketing (On The Stage et al.,
fee-funded and adequate), licensed script distribution (MTI/ProductionPro's lane),
auditions/casting management (possible Phase 5; Cast98 does it well today).

---

## 8. Competitive landscape (research summary, 2026-07)

| Tool | Model | Relevance |
|---|---|---|
| VirtualCallboard | $20/mo + $10/production | Incumbent; scheduling/reports/forums; no props/costumes; cost never ends |
| Cast98 | Free for community/nonprofit; $50+/mo large shows | Best conflict→schedule workflow; hosted/closed — data stranded if it dies |
| StageManager.tech | Single plan (price undisclosed) | Closest analog; **ships as PWA explicitly because "cast won't install another app"** |
| Callboards.app | Annual company license | Read-acknowledgment tracking pattern; purges data 30 days post-close |
| Propared / Shoflo | $112–$299/mo / $1,000+/yr | Pro ops tools; only ones with inventory tracking; wildly over budget |
| Planning Center Services | $0 → $239/mo by headcount | Best team→position→person call model + blockout dates + accept/decline |
| ProductionPro (MTI) | $199/production add-on | The legal digital-script lane for MTI shows; coexist, don't compete |
| Stage Write | $60–$100/user/yr | Owns blocking/choreography notation; out of scope |
| BAND / Discord / Sheets | Free | The actual current stack; no conflicts, no calls, no trackers, no reports |

Key market facts: props/costume tracking is absent from every cast-facing tool;
nothing free is self-hostable; **no maintained open-source theater production
management project exists** — Glow Tape would be first of its kind in the niche.

---

## 9. Risks & mitigations

- **iOS install friction** → guided illustrated install flow, full browser-tab
  functionality, email mirroring of everything. Revisit Capacitor only on evidence.
- **Push reliability on iOS PWAs** (subscriptions can go stale) → email fallback is
  first-class, not an afterthought.
- **Uploaded-script liability** → per-production privacy, rights checkbox, no
  re-download/re-share by default, DMCA agent + takedown policy, never market scanning.
- **Bus factor (solo maintainer)** → boring stack, open source the code, nightly
  offsite backups, CSV export everywhere so any org can leave with its data.
- **Adoption** → white-glove onboarding per production (a real person sets it up with
  the director), pilot with one friendly production before widening.
