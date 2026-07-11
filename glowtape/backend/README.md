# Glow Tape backend (PocketBase)

The backend is a single PocketBase binary plus the files in this directory:

- `pb_migrations/` — schema, applied automatically on startup
- `pb_hooks/` — signup/join routes, default channels, email mirroring
- `pb_data/` — created at runtime; the database and uploaded files. **Back this up.** Not committed.

## Run locally

```sh
npm run setup-backend   # downloads the pocketbase binary (once)
npm run backend         # serves on http://127.0.0.1:8090
npm run dev             # in another terminal; Vite proxies /api to :8090
```

On first start, PocketBase prints a link to create the superuser account.
Then in the dashboard (`/_/`):

1. **Settings → Mail settings**: configure SMTP (Amazon SES, Resend, Brevo…).
   Until this is set, OTP codes only appear in the server logs.
2. Create an `orgs` record for each theater, and a `productions` record for a
   show (org, title, status). A join code and default channels are created
   automatically.
3. Set the production's `managers` to the director/SM user ids once those
   people have signed in (they appear in `users` after first sign-in). The
   in-app admin tab handles this for subsequent members.

## SMS (optional, dormant by default)

Text reminders and sign-in-by-text stay inactive until these environment
variables are set for the `pocketbase serve` process:

```sh
GLOWTAPE_SMS_PROVIDER=twilio        # or: telnyx
TWILIO_ACCOUNT_SID=ACxxxxxxxx
TWILIO_AUTH_TOKEN=xxxxxxxx
TWILIO_FROM=+18335550123              # your toll-free or 10DLC number
# telnyx instead: TELNYX_API_KEY=..., TELNYX_FROM=+1...
```

Prerequisite: US carrier registration on the provider (toll-free number
verification is the lightest path; A2P 10DLC sole-proprietor works too).
In the provider console, restrict SMS geo-permissions to US only.

What turns on: phone verification + opt-in on the Home screen, "Text me a
code" sign-in (verified phones only), and a 10-minute cron that texts
called members ~10h and ~2h before events (deduped in `reminders_sent`).
Codes are hashed, expire in 10 minutes, and are rate-limited per phone/IP.

## Verification checklist (first run)

This schema and the hooks were written against PocketBase v0.30 without a live
server; on first boot walk this list:

- [ ] Migration applies cleanly (`pocketbase serve` exits 0 and `/_/` shows all 10 collections)
- [ ] `POST /api/glowtape/signup` creates a user; `requestOTP` + `authWithOTP` sign-in works end to end
- [ ] `POST /api/glowtape/join` with a production's join code creates a member
- [ ] Creating a production auto-creates 4 channels and a join code
- [ ] Creating an event/announcement sends mirrored email (check SMTP + spam)
- [ ] API rules: a signed-in non-member cannot list another production's events/messages
- [ ] Contact sheet shows castmates' names/emails (users view rule + emailVisibility)
- [ ] With SMS configured: phone verify flow, sign-in by text, and a test event
      ~1h out produces exactly one "2h" reminder text
- [ ] Without SMS configured: phone/sign-in-by-text endpoints fail gracefully and
      the cron does nothing (codes logged, not sent)
- [ ] Calendar: "Get my calendar link" returns a URL (requires Settings → Application
      → Application URL to be set), the .ics validates, and subscribing in Google
      Calendar / Apple Calendar shows called events

## Backups

`pb_data/` is everything. Nightly copy offsite, e.g.:

```sh
./pocketbase backup   # or: litestream replicate, or rclone pb_data/ to B2
```
