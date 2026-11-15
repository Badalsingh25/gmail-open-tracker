# Free deployment (no card, no money)

Stack: **Render** (free web service, runs the Docker image) + **TiDB Cloud Starter** (free MySQL-compatible database) +
**UptimeRobot** (free pinger that keeps Render awake). One service runs everything (`TRACKER_MODE=all`).

## Know the limits first
- Render's own docs say free services are not for production and may restart at any time. Opens that arrive while it
  restarts (about a minute) are lost. Fine for a personal tool, not for anything you can't afford to miss.
- A free Render service sleeps after 15 idle minutes and takes about a minute to wake. A sleeping tracker loses opens,
  so the pinger in step 5 is not optional.
- 512 MB RAM is tight for Spring Boot. The settings below keep the JVM small.
- Free plans change. Check each site's current terms.

## 1. Database: TiDB Cloud Starter
1. Sign up at tidbcloud.com (Google or GitHub login). No card is needed to start.
2. Create a **Starter** cluster in the region closest to you.
3. Open **Connect** and copy the host, port (4000), username (looks like `abc123.root`) and generate a password.
4. Open the **SQL Editor** and run: `CREATE DATABASE email_tracker;`
5. Your JDBC URL is:
   `jdbc:mysql://<HOST>:4000/email_tracker?sslMode=VERIFY_IDENTITY&serverTimezone=UTC`
   If the connection fails with an SSL error, try `sslMode=REQUIRED` instead.

## 2. Secrets
Run twice and keep both results (never commit them):
`node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"`
- first result = `TRACKER_API_KEY` (what you type into the extension)
- second result = `TRACKER_SIGNING_SECRETS` (signs the tracking links; never change it, or old emails stop working)

## 3. Push the code
In the project folder: `git add -A`, `git commit -m "Deploy"`, `git push`.
Check `git status` first: nothing like `client_secret_*.json` or `.env` may be committed.

## 4. Web service: Render
1. Sign in at render.com with GitHub, then **New → Web Service**, pick the repository (private is fine).
2. Settings: Language **Docker**, **Root Directory** `backend`, Instance type **Free**, a region near you.
   Choose the name carefully: the address will be `https://<name>.onrender.com` and must never change.
3. Health check path: `/api/health`
4. Environment variables:

| Name | Value |
|---|---|
| `TRACKER_MODE` | `all` |
| `DB_URL` | the JDBC URL from step 1 |
| `DB_USERNAME` / `DB_PASSWORD` | from TiDB |
| `DDL_AUTO` | `update` |
| `TRACKER_PUBLIC_BASE_URL` | `https://<name>.onrender.com` |
| `TRACKER_API_KEY` | first secret |
| `TRACKER_SIGNING_SECRETS` | second secret |
| `TRACKER_CLIENT_IP_HEADER` | `X-Forwarded-For:first` |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -Duser.timezone=UTC` |

5. Create the service and wait for the build (several minutes). Open `https://<name>.onrender.com/api/health`;
   the first load can take a minute.

## 5. Keep it awake: UptimeRobot
Sign up at uptimerobot.com (free), add an **HTTP(s)** monitor for `https://<name>.onrender.com/api/health`
with a 5-minute interval.

## 6. Extension
Settings → API URL `https://<name>.onrender.com`, API key = first secret → Save (Chrome asks permission) →
Test connection. Reload Gmail.

## 7. Check it works (important)
Send one tracked mail to another account and open it there. In the popup open the history: the evidence must say
"Gmail image proxy fetch". If it says "the address is not Google's", the client IP is being read wrongly. Change
`TRACKER_CLIENT_IP_HEADER` (try `X-Forwarded-For`, then `remote`) and tell me what the evidence says.

## Backups
TiDB's free tier is not a backup. Occasionally export your data from the SQL Editor if it matters to you.
