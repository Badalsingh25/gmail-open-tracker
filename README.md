# Gmail Open Tracker (v0.5)

Estimates, **per recipient**, whether an email you chose to track was opened or a link was clicked, and tells you how
sure it is. It is an estimate, never proof: no tracking pixel can see a person.

## How it works

```
Chrome extension (Gmail compose)          ┌────────────── your server ──────────────┐
  Track opens ✓  ─ register ───────────▶  │ api    (TRACKER_MODE=api)    API key     │
  one pixel + rewritten links per person  │ ingest (TRACKER_MODE=ingest) public,     │
                                          │        write-only: /t/<token>.gif        │
recipient's mail client / proxy ───────▶  │                 /l/<token>/<link>        │
                                          │ mysql  (private)                         │
                                          └──────────────────────────────────────────┘
```

1. **Per-recipient tokens.** Each recipient gets their own HMAC-signed pixel and link URLs (`tracker/core/TokenSigner`).
   For several recipients the extension can send separate copies (optional Google sign-in, see `extension/GOOGLE_SETUP.md`).
2. **Ingest** verifies the signature without touching the database, then appends one event (derived facts only: no IP,
   no User-Agent stored). It never breaks what the recipient sees: bad tokens still get the image, links still redirect.
3. **Scoring** (`core/OpenScorer`) turns each request into a 0-100 score from who fetched it, whether the address matches
   Google/Apple's published ranges, how long after sending it arrived, and whether it was a link click. Evidence is
   combined (bursts are one fetch, repeat opens hours apart count for more, scanner noise never adds up). Verdicts:
   `ENGAGED` (clicked), `OPENED`, `POSSIBLY_OPENED`, `AUTOMATED_ONLY`, `NO_ACTIVITY`.
4. **Verdicts are recomputed from raw events on every read**, so tuning the scorer re-scores history.

## Run locally (development)

```bash
docker compose up -d                         # MySQL
cd backend && mvn spring-boot:run            # mode=all on :8080, dev secrets (warns, does not refuse)
cd extension && npm install && npm run build # then Load unpacked -> extension/dist
```

Locally the pixel points at `http://localhost:8080`, which **no recipient can reach**, so you will only see your own
test traffic. For anything real, deploy: see `deploy/DEPLOY.md`.

## Real use
1. `deploy/DEPLOY.md`: always-on HTTPS server (ingest + api + MySQL + Caddy).
2. Extension **Settings** page: API URL + key, send mode, link tracking.
3. Optional: `extension/GOOGLE_SETUP.md` for separate per-recipient copies.
4. `calibration/CALIBRATION.md`: measure accuracy against your own test mail and tune.

## Configuration (environment)

| Variable | Meaning |
|---|---|
| `TRACKER_MODE` | `ingest`, `api` or `all` |
| `TRACKER_PUBLIC_BASE_URL` | Public https address embedded in emails. Keep it stable forever |
| `TRACKER_SIGNING_SECRETS` | Comma-separated, first signs, all verify (rotation) |
| `TRACKER_API_KEY` | Dashboard API key |
| `TRACKER_STRICT` | `true` = refuse to start with weak/default settings |
| `TRACKER_CLIENT_IP_HEADER` | `X-Forwarded-For`, `CF-Connecting-IP` or `remote` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `DDL_AUTO` | Database |
| `tracker.scoring.*` | Scoring thresholds (see `application.yml`) |

## Tests
- Backend: `cd backend && mvn clean test` (unit + a full-flow integration test on H2).
- Extension: `cd extension && npm test`.

## Limits you cannot engineer away
Images blocked by the recipient are invisible. Apple Mail Privacy Protection hides real opens (reported as "not
measurable"; only clicks help). One shared copy cannot say which person opened. Forwarded mail reuses the original
URLs. Scheduled sends look "late" to the timing check. See `REVIEW.md`.

Use only on email where you have a lawful basis, and respect your organisation's policy and recipients' privacy.
