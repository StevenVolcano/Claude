# Ghostlight backend (PocketBase)

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

## Verification checklist (first run)

This schema and the hooks were written against PocketBase v0.30 without a live
server; on first boot walk this list:

- [ ] Migration applies cleanly (`pocketbase serve` exits 0 and `/_/` shows all 10 collections)
- [ ] `POST /api/ghostlight/signup` creates a user; `requestOTP` + `authWithOTP` sign-in works end to end
- [ ] `POST /api/ghostlight/join` with a production's join code creates a member
- [ ] Creating a production auto-creates 4 channels and a join code
- [ ] Creating an event/announcement sends mirrored email (check SMTP + spam)
- [ ] API rules: a signed-in non-member cannot list another production's events/messages

## Backups

`pb_data/` is everything. Nightly copy offsite, e.g.:

```sh
./pocketbase backup   # or: litestream replicate, or rclone pb_data/ to B2
```
