# Running it for real: operations guide

For the free setup (Render web service + TiDB Cloud Starter + UptimeRobot). Everything here is optional hardening;
nothing in it changes how the tracker behaves.

## 1. What already protects the public endpoints

- **Rate limit per source address** on `/t/<token>.gif` and `/l/<token>/<id>`: a burst of 120 requests, refilling at
  20 per second (`TRACKER_RATE_LIMIT_BURST`, `TRACKER_RATE_LIMIT_PER_SECOND`). A request over the limit still gets
  the image or the redirect, so the recipient sees nothing wrong, but it is not recorded.
- **Signed tokens.** A bad or forged token never touches the database and never breaks the page.
- **API key** on every dashboard endpoint. Use a long random value, never the development default.
- **Client IP is used in memory only** (to classify Google/Apple/other); it is not stored.

Limits are generous because Gmail's and Apple's proxies fetch for many people from shared addresses.

## 2. Database connection settings for TiDB Cloud Starter

TiDB Starter closes idle public connections after about 340 seconds, may end connections open longer than 30 minutes,
and allows 400 connections ([TiDB Cloud limits](https://docs.pingcap.com/tidbcloud/serverless-limitations/)). The
default connection pool keeps connections for 30 minutes, so after a quiet period the first request can hit a dead one.
Add these **environment variables on Render** (no code change) and redeploy:

| Variable | Value | Why |
|---|---|---|
| `SPRING_DATASOURCE_HIKARI_MAX_LIFETIME` | `270000` | retire connections after 4.5 min, before the 5-6 min cut-offs |
| `SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT` | `240000` | drop idle connections sooner than TiDB does |
| `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` | `1` | don't hold ten idle connections on a 512 MB instance |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `5` | plenty for one user, easy on memory |

After the redeploy, check **Render > Logs** for connection errors for a day. If you see none, leave them.

## 3. The free database allowance

TiDB Cloud Starter (MySQL-compatible) includes 5 GiB of row storage and 50 million Request Units per month. When the
monthly units run out, **new connections are refused** until the month resets, which would look like "the dashboard
stopped working". One event row is a few hundred bytes, so storage is not a realistic worry; request units are the
thing to glance at. Once a month open **TiDB Cloud > your cluster > Usage** and check the month-to-date RUs. Normal
personal use, plus a 5-minute health check that does not touch the database, stays far below it.

## 4. Exporting your data regularly

`deploy/backup.mjs` saves everything (mails, recipients, verdicts, raw events) as one JSON file, using the same API as
the extension. It needs only Node 18+:

```bash
node deploy/backup.mjs --api https://YOUR-SERVICE.onrender.com --key YOUR_API_KEY
```

Files go to `~/gmail-tracker-backups` (outside the repository, on purpose: they contain recipient addresses and
subjects). The newest 14 are kept. This is a readable archive for analysis or re-import, not a database restore. For a
full dump use the MySQL client against TiDB (host, user and password from the TiDB **Connect** dialog):

```bash
mysqldump -h HOST -P 4000 -u USER -p --ssl-mode=VERIFY_IDENTITY email_tracker > tracker.sql
```

**Run it daily on Windows** (Task Scheduler). Store the address and key as user environment variables so the key is not
visible in the task, then create the task (open a new terminal afterwards):

```bat
setx TRACKER_API "https://YOUR-SERVICE.onrender.com"
setx TRACKER_KEY "YOUR_API_KEY"
schtasks /Create /SC DAILY /ST 21:00 /TN "Tracker export" /TR "node E:\gmail-open-tracker\deploy\backup.mjs"
```

If the PC is off at 21:00 the run is skipped; in Task Scheduler open the task > Settings and tick **Run task as soon
as possible after a scheduled start is missed**. A free Render service may need up to a minute to wake: the script waits.

## 4a. Why not an automatic cloud backup?

A scheduled GitHub Action could do it, but its output is stored as a workflow artifact, and on a **public** repository
anyone can download those. The data is people's email addresses, so don't do that unless the repository is private.

## 5. Rotating secrets

- **API key:** change `TRACKER_API_KEY` on Render, then the key in the extension Settings.
- **Signing secret:** `TRACKER_SIGNING_SECRETS` is a comma-separated list: the first signs new links, all of them verify.
  To rotate without breaking pixels already sent, put the new secret first and keep the old one after it:
  `NEW,OLD`. Remove `OLD` only when you no longer care about opens on very old mail.
- Never paste either value into an email, chat or the repository. If one leaks, rotate it.

## 6. Monitoring

- **UptimeRobot** pings `/api/health` every 5 minutes, which also keeps the free Render service from sleeping. That
  endpoint checks that the service is alive, not that the database is reachable.
- If opens stop appearing: (1) open `/api/health` in a browser, (2) look at **Render > Logs** for database errors,
  (3) check the TiDB **Usage** page for an exhausted allowance, (4) run `deploy/backup.mjs`: if it fails, the API or
  database is down.
- Free Render services can restart without notice. An open that arrives during a restart is lost. That cannot be
  fixed on a free plan.
