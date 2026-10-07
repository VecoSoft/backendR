# Deploying Jachai to production

This is the runbook for the production setup:

| Piece | Where | Notes |
|---|---|---|
| Frontend (Jachai repo) | Vercel Pro, region `sin1` | `jachai.com`, `www.jachai.com` |
| API + admin panel (backendR) | Droplet `jachai-prod`, 167.99.67.205 | `api.jachai.com`, Docker Compose, built on the droplet |
| ML service (rpML) | Same droplet, internal only | reached by the API at `http://ml:8081` |
| Redis | Same droplet, internal only | admin sessions, shared caches, rate limits |
| PostgreSQL 17 | DO Managed Database, SGP1 | db `jachai`, port 25060, `sslmode=require` |
| Media + DB backups | DO Spaces `jachai-media` (sgp1, private) | media at the bucket root, backups under `backups/` |
| DNS | Cloudflare | `api` proxied (orange cloud), apex/www DNS-only to Vercel |

Nothing in this folder contains a secret. All secrets go in `deploy/.env` on the droplet, which is
git-ignored.

**Files in `deploy/`**

| File | What it does |
|---|---|
| `docker-compose.prod.yml` | caddy, backend, ml, redis. Only Caddy publishes ports (80/443). |
| `Caddyfile` | HTTPS for `api.jachai.com`, security headers, 10 MB body limit, Cloudflare real-IP |
| `.env.example` | every variable, no values |
| `build.sh` | builds images one at a time, keeps the old ones as `:previous` |
| `update.sh` | `git pull`, build, `up -d`, health check, prune |
| `rollback.sh` | puts the `:previous` images back |
| `backup.sh` / `restore.sh` | nightly `pg_dump -Fc` to Spaces (14 days kept) / restore one |
| `migrate-media.sh` | one-off copy of old local uploads into Spaces + URL rewrite |
| `psql.sh` | psql against the managed DB with the `.env` credentials |

---

## 0. Before you start (one-time, in the DigitalOcean and GitHub dashboards)

1. **Managed database:** under Databases → your cluster → *Settings → Trusted sources*, check that
   `jachai-prod` is listed. Under *Users & Databases*, check that database `jachai` exists.
   From *Connection details* (Public network), note the host, user (`doadmin`) and password.
2. **Spaces key:** API → Spaces Keys → *Generate New Key*. Note the key and secret; the secret is
   shown once. The bucket `jachai-media` stays **private** (File Listing: Restricted).
3. **GitHub access from the droplet:** add a read-only *deploy key* to `backendR` and to `rpML`
   (repo → Settings → Deploy keys). Use one SSH key per repo, generated on the droplet with
   `ssh-keygen -t ed25519 -f ~/.ssh/backendR -N ""` (and the same with `rpML`). Then add both to
   `~/.ssh/config` as host aliases:
   ```
   Host github-backendR
     HostName github.com
     IdentityFile ~/.ssh/backendR
   Host github-rpML
     HostName github.com
     IdentityFile ~/.ssh/rpML
   ```
4. **Cloud firewall (recommended):** Networking → Firewalls, on `jachai-prod`. Inbound: 22 from
   your own IP; 80 and 443 from anywhere (or only from Cloudflare's ranges, see step 9). Use the DO
   firewall rather than `ufw`: Docker-published ports bypass `ufw`.

## 1. Clone into /opt/jachai

```bash
ssh deploy@167.99.67.205
sudo mkdir -p /opt/jachai && sudo chown deploy:deploy /opt/jachai
cd /opt/jachai
git clone git@github-backendR:VecoSoft/backendR.git backendR
git clone git@github-rpML:nsayed3379-ctrl/rpML.git rpML
docker --version && docker compose version     # Docker 29, Compose v5
```

The compose file expects exactly `/opt/jachai/backendR` and `/opt/jachai/rpML` side by side. If you
use other paths, set `ML_DIR` in `.env`.

## 2. Fill in deploy/.env

```bash
cd /opt/jachai/backendR/deploy
cp .env.example .env
chmod 600 .env
nano .env
```

Every variable is explained in `.env.example`. You must fill in:

| Variable | Value |
|---|---|
| `ACME_EMAIL` | your e-mail (Let's Encrypt notices) |
| `DB_HOST`, `DB_PASSWORD` | from the managed DB's connection details (`DB_USER=doadmin`, `DB_PORT=25060`, `DB_NAME=jachai` are prefilled) |
| `JWT_SECRET` | output of `openssl rand -base64 48` |
| `SPACES_KEY`, `SPACES_SECRET` | the Spaces key from step 0 |
| `GEMINI_API_KEY` | Google AI Studio key (AI review summary, captions, fake-review LLM check) |
| `GOOGLE_CLIENT_IDS` | Google Cloud → Credentials → OAuth client id (type Web; authorized JavaScript origins `https://jachai.com`, `https://www.jachai.com`). Add Android/iOS ids comma-separated later |
| `EMAIL_PROVIDER` + `EMAIL_API_KEY` | `resend` and its API key (verify `jachai.com` in Resend: SPF/DKIM DNS records in Cloudflare). Or `smtp` + `SMTP_HOST/PORT/USER/PASS` |
| `GOOGLE_MAPS_API_KEY` | optional: admin map picker; restrict the key to `api.jachai.com` |

These are prefilled and normally stay as they are: `API_DOMAIN`, `CORS_ALLOWED_ORIGINS`,
`DB_SSLMODE`, `DB_POOL_MAX`, `SPACES_*` endpoint/region/bucket, `EMBEDDING_MODEL`, `ML_MEM_LIMIT`,
`ADMIN_BOOTSTRAP_ENABLED=false`.

The API runs with `APP_ENV=production`, so it **refuses to start** and lists the problems if any
required value is missing or still a development default (for example localhost, the default JWT
secret, or local file storage).

## 3. Copy the local database into the managed database (before the first start)

Do this **before** starting the API for the first time. The dump brings the schema, the data and
Flyway's history, so the API starts on the existing schema and applies only newer migrations.

**On your Windows machine** (PostgreSQL 17 tools are in `C:\Program Files\PostgreSQL\17\bin`):

```powershell
& "C:\Program Files\PostgreSQL\17\bin\pg_dump.exe" -Fc --no-owner --no-privileges `
  -h localhost -U postgres -d bd_review -f "$env:USERPROFILE\jachai-local.dump"
scp "$env:USERPROFILE\jachai-local.dump" deploy@167.99.67.205:/home/deploy/
```

**On the droplet:**

```bash
cd /opt/jachai/backendR/deploy
./restore.sh /home/deploy/jachai-local.dump     # asks you to type the database name
./psql.sh -c "SELECT count(*) AS users FROM app_user"
```

On managed Postgres, `pg_restore` usually reports a few harmless errors about extension ownership
or comments; the script prints a summary of them. The last line it prints must show the Flyway
version (69 or later).

Your local admin account comes along with the dump, so leave `ADMIN_BOOTSTRAP_ENABLED=false`.
Change that admin's password after the first login.

> Starting from an empty database instead? Skip this step and set `ADMIN_BOOTSTRAP_ENABLED=true`
> plus `ADMIN_DEFAULT_PHONE` and `ADMIN_DEFAULT_PASSWORD` (12+ characters) for the first start.
> Flyway then creates the schema. Set it back to `false` afterwards.

## 4. Cloudflare DNS for the API (before the first start)

Caddy obtains the certificate on its first start, so `api.jachai.com` must already resolve to the
droplet.

1. Cloudflare → `jachai.com` → DNS → add **A `api` → `167.99.67.205`, Proxied (orange)**.
2. SSL/TLS → Overview → encryption mode **Full (strict)**.
3. SSL/TLS → Edge Certificates → **Always Use HTTPS: Off**. Caddy already redirects HTTP to HTTPS
   for the API, and Vercel does the same for the site. With this setting on, Cloudflare would answer
   Let's Encrypt's HTTP check itself, and the certificate could neither be issued nor renewed.
4. Caching → Cache Rules → *Create rule*: hostname equals `api.jachai.com` → **Bypass cache**. API
   responses are per-user, and file responses are short-lived redirects.

## 5. Build and start

```bash
cd /opt/jachai/backendR/deploy
./build.sh                        # ml first, then backend; ~10-15 min the first time
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps
```

`build.sh` builds one image at a time on purpose: the droplet has 4 GB (+4 GB swap), the Maven build
is capped at 1 GB of heap and the ML image installs PyTorch.

## 6. Check health

```bash
docker compose -f docker-compose.prod.yml ps        # all "healthy"
docker compose -f docker-compose.prod.yml exec backend curl -fsS localhost:8085/actuator/health/readiness
curl -fsS https://api.jachai.com/actuator/health/liveness     # {"status":"UP"} through Cloudflare
docker compose -f docker-compose.prod.yml logs -f backend     # Ctrl-C to stop
```

- **Liveness** (`/actuator/health/liveness`) means the JVM is up; the Docker healthcheck uses it.
  **Readiness** (`/actuator/health/readiness`) also checks Postgres and Redis. Other actuator paths
  answer 404 at Caddy.
- Log in at `https://api.jachai.com/admin` and open **System → Health**. You should see Database,
  Redis, ML service and File storage ("S3 bucket jachai-media reachable") as UP, plus the scheduled
  jobs.
- If Caddy has no certificate yet, check `docker compose -f docker-compose.prod.yml logs caddy`.
  It is almost always step 4.3 (Always Use HTTPS) or DNS not pointing at the droplet yet.

## 7. Move the existing uploads into Spaces

The old uploads live in `business-review-backend/uploads` on your Windows machine (about 75 files,
12 MB). The stored URLs in the database point at `http://localhost:8085` and, from test runs,
`http://localhost:8095`.

**On Windows:**

```powershell
scp -r "C:\Users\USER\Downloads\my-fullstack-project\business-review-backend\uploads" deploy@167.99.67.205:/home/deploy/uploads
```

**On the droplet:**

```bash
cd /opt/jachai/backendR/deploy
./migrate-media.sh /home/deploy/uploads http://localhost:8085,http://localhost:8095
```

The command:

1. copies each file to `s3://jachai-media/<same key>`, skipping files already there with the same size;
2. writes the WebP variants (200/600/1200 px) for images under `_variants/`;
3. rewrites stored URLs `http://localhost:8085/api/v1/storage/...` and
   `http://localhost:8095/api/v1/storage/...` to `https://api.jachai.com/api/v1/storage/...` in every
   text and jsonb column (the audit log is left as written).

It is idempotent, so run it again if it stops halfway. Then open a business page on the site and
check that its photos load. Delete `/home/deploy/uploads` once you are satisfied.

**How media is served:** stored URLs stay `https://api.jachai.com/api/v1/storage/files/<key>`. Each
request passes the moderation check first, so pending, rejected and deleted photos are never public.
An allowed request then gets a 302 to a presigned Spaces URL valid for 5 minutes.
`.../files/<key>?w=600` serves the 600 px WebP variant. Uploads need a signed URL the API issued
(valid 15 minutes).

**media.jachai.com:** not used for uploads. The bucket is private, so files can't be served from a
public CDN hostname without bypassing moderation. Configure it later only if you add public assets.
DO Spaces → Settings → CDN → custom subdomain needs a certificate; with DNS on Cloudflare, upload a
certificate for `media.jachai.com` (DO can't issue one for a domain whose DNS it doesn't host), then
add `CNAME media → jachai-media.sgp1.cdn.digitaloceanspaces.com` (DNS only). `next.config.mjs`
already allows both hostnames.

## 8. Nightly backups (cron)

Try a backup by hand first:

```bash
cd /opt/jachai/backendR/deploy
./backup.sh
./restore.sh --list
```

Then schedule it, plus the weekly vacuum (see `docs/data-retention.md`):

```bash
crontab -e
```

```
# 02:30 Dhaka (20:30 UTC): pg_dump -> s3://jachai-media/backups/, keep 14 days
30 20 * * * /opt/jachai/backendR/deploy/backup.sh >> /home/deploy/jachai-backup.log 2>&1
# Sunday 04:30 Dhaka: VACUUM (ANALYZE) the tables the retention job prunes
30 22 * * 6 /opt/jachai/backendR/deploy/psql.sh -c "VACUUM (ANALYZE) notification, search_log, search_log_daily, user_login_event, scheduled_job_run, audit_log, community_post, community_post_comment;" >> /home/deploy/jachai-vacuum.log 2>&1
```

The droplet clock is UTC (`timedatectl`). The `deploy` user must be in the `docker` group
(`groups deploy`).

**Restore:** `./restore.sh latest` (or `./restore.sh backups/jachai-jachai-<stamp>.dump`). It stops
the backend, replaces the database, and starts the backend again. DO's managed database also keeps
its own daily backups with point-in-time recovery (Databases → Backups); these dumps are a second,
independent copy.

## 9. Vercel (frontend)

1. vercel.com → *Add New → Project* → import the **Jachai** repo. Framework: Next.js (detected),
   root directory `/`, default build command.
2. *Settings → Environment Variables*: `NEXT_PUBLIC_API_URL` = `https://api.jachai.com` for
   Production (and Preview, if you want previews to use the production API). It is inlined at build
   time, so redeploy after changing it. That is the only variable (see `.env.example` in the repo).
3. `vercel.json` pins functions to `sin1` (Singapore, next to the droplet and database).
4. *Settings → Domains*: add `jachai.com` and `www.jachai.com`; choose which one redirects to the
   other (Vercel suggests www → apex).
5. **Cloudflare DNS** for the site, both **DNS only (grey cloud)**, as Vercel recommends:
   - `A @ → 76.76.21.21`
   - `CNAME www → cname.vercel-dns.com`

   Use the exact values Vercel shows on the Domains page if they differ.

Preview deployments (`*.vercel.app`) are not in `CORS_ALLOWED_ORIGINS`, so their browser calls to
the production API are refused. That is intended. To test a preview against the API, add its exact
origin to `CORS_ALLOWED_ORIGINS` and run `docker compose -f docker-compose.prod.yml up -d backend`.

**Optional, stricter origin:** restrict the DO firewall's 80/443 to Cloudflare's IP ranges (listed
in the `Caddyfile`), so the API is only reachable through Cloudflare.

## 10. Updating

```bash
/opt/jachai/backendR/deploy/update.sh
```

This pulls `main` of backendR and rpML, rebuilds one image at a time (the running images are kept
as `:previous`), restarts with `up -d`, waits for both healthchecks, checks readiness, and prunes
dangling images and build cache older than a week. Frontend changes deploy on Vercel by pushing to
the Jachai repo.

Database migrations run automatically when the new API starts (Flyway). The backend takes about a
minute to start. Meanwhile Caddy holds requests for up to 20 s, so expect a short blip on deploys
with a single instance.

## 11. Rolling back

```bash
/opt/jachai/backendR/deploy/rollback.sh          # backend and ml
/opt/jachai/backendR/deploy/rollback.sh backend  # just one
cat /opt/jachai/backendR/deploy/.last-deploy     # commits that ran before the last update
```

This swaps the `:previous` images back in, with no rebuild, in seconds. Flyway migrations are not
undone. An older API ignores migrations newer than itself, which is safe for additive changes (all
of ours so far). To keep building the old code, check out the old commit (`git -C .. checkout <sha>`,
and the same for rpML) before the next `build.sh`, or fix forward with a new commit and `update.sh`.

For a bad data change, restore a backup (step 8) or use DO's point-in-time recovery.

## Operations notes

- **Logs:** `docker compose -f docker-compose.prod.yml logs --tail=200 backend` (also `ml`,
  `caddy`, `redis`). Logs rotate at 5 x 20 MB per container.
- **Restart one service:** `docker compose -f docker-compose.prod.yml restart backend`.
- **Change .env:** `docker compose -f docker-compose.prod.yml up -d`, which recreates the services
  whose settings changed.
- **Memory budget:** backend 1.5 GB, ml 768 MB, redis 300 MB, caddy 128 MB, about 2.7 GB of 4 GB.
  The ML container keeps its embedding model in memory. The dev model
  (`paraphrase-multilingual-mpnet-base-v2`) needs about 1.5 GB and is killed at 768 MB, which is why
  `.env.example` uses `paraphrase-multilingual-MiniLM-L12-v2`. If ML still restarts with OOM
  (`docker compose ... ps` shows restarts; `dmesg | grep -i oom`), raise `ML_MEM_LIMIT` to `1g`.
- **Scaling out:** the API keeps no local state. Media is in Spaces; sessions, caches and rate limits
  are in Redis; scheduled jobs are ShedLock-guarded in Postgres; shutdown is graceful. A second API
  droplet behind a DO load balancer needs the same `.env`, a shared Redis (DO Managed Redis/Valkey,
  `REDIS_HOST`), and `DB_POOL_MAX` lowered so that (instances x pool) + 2 stays under 22.
- **Data retention:** see `../docs/data-retention.md`. Periods are edited on Admin → System health
  → Data retention.
- **SMS and e-mail:** the backend has only the development senders, which log OTPs and e-mails
  instead of sending them. Phone sign-up/login OTPs are therefore **not delivered** in production
  until a real SMS gateway is implemented. Don't set `SPRING_PROFILES_ACTIVE=prod`: no `prod` sender
  exists, so the API would not start.
