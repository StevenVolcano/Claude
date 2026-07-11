# Ghostlight

*Grays Harbor's theater community.* Free production and rehearsal management for
Driftwood Players, Bishop Center, Stage West, Plank Island, 7th Street Kids, and
friends — schedules, calls, conflicts, messages, and contact sheets in one
installable web app.

The full product plan lives in [`../planning/ghostlight.md`](../planning/ghostlight.md).

## What works in this Phase 1 pilot core

- **Passwordless sign-in** — email a 6-digit code, no accounts to manage
- **Join a production** with a 6-letter code the SM shares at the read-through
- **Schedule** — events with location, notes, and *who is called* (everyone, or
  specific people plus a free-text call note like "dancers at 6, full cast at 7")
- **"Got it" acknowledgments** — managers see how many people have seen each event
- **Conflicts** — cast enter their unavailability; managers see everyone's
- **Channels** — All Call / Cast / Crew / Production Team, live-updating
- **Announcements** — pinned, acknowledgment-tracked
- **Contact sheet** — printable
- **Email mirroring** — new events and announcements are emailed to everyone
  called, so people who never open the app still get the schedule
- **Text reminders & sign-in by text** (dormant until an SMS provider is
  configured — see `backend/README.md`): verified phones get reminder texts
  10 hours and ~2 hours before their calls, and can sign in with a texted
  code instead of email
- **Calendar integration** — add any event to Google/Apple/phone calendars
  with one tap, or subscribe to a personal auto-updating feed of everything
  you're called for
- **PWA** — installable on Android/iOS/desktop, offline app shell

## Architecture

- `backend/` — [PocketBase](https://pocketbase.io) (one binary, SQLite):
  auth with email one-time codes, data, realtime, and the custom
  signup/join/email hooks. See `backend/README.md`.
- `src/` — Vite + React + TypeScript PWA, served by PocketBase in production.

## Develop

```sh
npm install
npm run setup-backend   # downloads the pocketbase binary (once)
npm run backend         # http://127.0.0.1:8090 (create superuser on first run)
npm run dev             # http://127.0.0.1:5173 (proxies /api to the backend)
```

## Deploy (one small VPS)

```sh
npm run build                      # outputs dist/
cp -r dist/* backend/pb_public/    # PocketBase serves the frontend
# run backend/pocketbase serve behind Caddy/nginx with HTTPS
```

Configure SMTP in the PocketBase dashboard so sign-in codes and mirrored
emails deliver. Back up `backend/pb_data/` nightly — it is the entire system.

## Deliberately not here yet

Trackers (props/costumes/cues), script room and line-learning tools, the
community hub, and web push are later phases — see the plan. Web push is the
first candidate once the pilot proves out; email mirroring covers notification
needs until then.
