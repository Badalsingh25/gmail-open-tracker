# Review notes (v0.5.0)

## Can it be 100% accurate?
No. A pixel or link only tells the server that *something fetched a URL*. v0.5 aims at a **measured, well-labelled
estimate**: remove the known false positives, expose confidence and evidence, and give you a way to measure error on
your own mail (`calibration/`). Nothing has been measured on real mail yet, so no accuracy figure is claimed.

| Situation | Effect | What v0.5 does |
|---|---|---|
| Images blocked | Missed open | Cannot be fixed; clicks still count |
| Apple Mail Privacy Protection | Prefetch looks like an open | Detected by Apple's address ranges; reported as "not measurable" |
| Security scanners / prefetch at delivery | False open | Timing + User-Agent + HEAD rules; scanner noise never accumulates |
| Your own preview / Sent view | False open | Early-window rule |
| Several recipients, one copy | Can't tell who | Per-recipient copies (optional Google sign-in) |
| Link click | Strongest signal | Tracked links, `ENGAGED` verdict |

## What changed since v0.4.0
1. **Link-click tracking** (`/l/{token}/{linkId}`): destinations are stored server-side, so it is not an open redirect.
2. **One token per recipient**, with idempotent registration.
3. **Scored classification** replaces yes/no. Signals: User-Agent class, Apple relay ranges, Google ranges (UA/IP mismatch is
   penalised), seconds since sending, burst grouping, repeat opens hours apart.
4. **HMAC-signed tokens**, key rotation, constant-time verification, no database hit for forged tokens.
5. **Ingest and API separated** (one jar, `TRACKER_MODE`). Ingest is append-only with no row locks, rate-limited, and exposes no API.
6. **Calibration harness** (`calibration/`).
7. **Deployment**: Dockerfile, production Compose with automatic HTTPS, strict startup validation.
8. Extension: settings page (no source edits), separate-copy sending, Ctrl+Enter fix kept.

## Corrections to what I said earlier
- **Signed tokens do not stop replay.** A signature stops guessing, forging and enumeration, and saves database work. Someone who
  holds a genuine URL (the recipient, or anyone it is forwarded to) can still request it. The scorer treats suspicious patterns
  with lower confidence, but cannot eliminate this.
- **v0.4.0 would have discarded Outlook desktop opens**, because it treated "Microsoft Office" User-Agents as scanners. Fixed:
  they are now classified as an Outlook client. The assumption itself still needs checking with the calibration harness.

## Known gaps (honest list)
- **Not verified in a real Gmail.** The compose-window selectors are best-effort and tested only against a simulated DOM.
- **Backend Spring/JPA layer has not been compiled or run** (Maven Central was blocked where this was built). The pure logic
  is tested; see `VERIFICATION_REPORT.md`. Run `mvn clean test` first.
- Scheduled sends: the timing rule measures from registration, so prefetch after a scheduled send looks "late".
- Forwarded/replayed URLs, and Gmail's behaviour for very old emails, can produce opens from other people or times.
- Separate copies are only for new, plain messages (no attachments, inline images, replies).
- Rewritten links show your tracker's address on hover and can affect spam scoring.
- Dashboard auth is a single shared API key. Fine for one person, not for a team.
- Old v0.3/0.4 data is **not migrated**: v0.5 uses new tables (`tracked_message`, `tracked_recipient`, `tracked_link`, `track_event`).
- The Apple list is fetched daily; if the download fails the previous list (or just `17.0.0.0/8`) is used. Not tested live.
- Scoring numbers are starting heuristics until calibrated.
