# Deploying for real use

Why this and not a tunnel: the pixel URL is written into every email you send, and recipients open mail days later.
A tunnel to your laptop dies when it sleeps, and quick-tunnel URLs change on restart, so opens would silently vanish.
An always-on server with a stable HTTPS address is the reliable option.

## What you need
- A small Linux server (1 GB RAM is enough) with Docker and the Compose plugin.
- A domain, with two DNS **A records** pointing at the server, for example `track.example.com` and `api.example.com`.
- Ports 80 and 443 open (Caddy uses them to get and renew certificates automatically).

## Architecture

```
 recipient's mail client ──https──▶ track.example.com ─▶ ingest  (public, write-only: /t/*.gif and /l/*)
                                                           │
 you / the extension ─────https──▶ api.example.com   ─▶ api     (API key required)
                                                           │
                                                   mysql (no public port)
```

`ingest` and `api` are the same image started with `TRACKER_MODE=ingest` / `api`. Ingest exposes no dashboard or
API routes at all, so an attack on the public endpoint cannot reach your data through it.

## Steps

```bash
git clone <your repo>  &&  cd gmail-open-tracker/deploy
cp .env.example .env
# Fill in .env. Generate every secret with:  openssl rand -hex 32
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps        # wait until api and ingest are "healthy"
```

`TRACKER_STRICT=true` is set for you: the app refuses to start if a secret is missing, short, or still a
development default, or if the public URL is not https.

## Check it works

```bash
curl -sS https://api.example.com/api/health                    # {"status":"ok",...,"mode":"api"}
curl -sSI https://track.example.com/t/not-a-real-token.gif     # 200, image/gif, Cache-Control: no-store...
curl -sS  https://track.example.com/api/stats                  # 404: ingest exposes no API
curl -sS  https://api.example.com/api/stats                    # 401 without the key
```

Then in the extension's **Settings** page set the API URL to `https://api.example.com` and your `TRACKER_API_KEY`
(the extension asks Chrome for permission to contact that host), and use **Test connection**.

## Operating it

- **Backups.** Everything lives in the `mysql_data` volume. Example nightly dump:
  `docker compose -f docker-compose.prod.yml exec -T mysql sh -c 'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" email_tracker' | gzip > backup-$(date +%F).sql.gz`
- **Rotating the signing secret.** Put the new secret first and keep the old one:
  `TRACKER_SIGNING_SECRETS=<new>,<old>`, then `docker compose ... up -d`. Emails already sent keep working.
  Never remove an old secret while emails signed with it can still be opened.
- **Updating.** `git pull && docker compose -f docker-compose.prod.yml up -d --build`.
- **Keep `TRACK_DOMAIN` forever.** Changing it breaks every email already sent.
- **Uptime.** Point any uptime monitor at `https://track.example.com/api/health`. While ingest is down, opens are lost for good.

## Splitting across hosts
Ingest can run on its own cheap host as long as it can reach the same MySQL. Do not expose MySQL to the internet:
use a private network, VPN, or a managed MySQL that restricts connections to your hosts. Run only the `ingest`
service there with `TRACKER_MODE=ingest`.

## Not covered
Monitoring and alerting, automated backups, a data-retention policy (events are kept until you delete the message),
and a proper login system for the dashboard (a single shared API key protects it today).
