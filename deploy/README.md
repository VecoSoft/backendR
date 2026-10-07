# Deploy (single droplet, Docker Compose)

Images are built **on the droplet** (`docker compose build`); nothing is pushed to a registry.

## Layout

```
/opt/jachai/backendR   # this repo; compose runs from backendR/deploy
/opt/jachai/Jachai     # frontend
/opt/jachai/rpML       # ml-service
```

Other locations: set `FRONTEND_DIR` / `ML_DIR` in `.env`.

## First run

```bash
cd /opt/jachai/backendR/deploy
cp .env.example .env && nano .env
# Build one image at a time so a small droplet doesn't run out of memory
docker compose -f docker-compose.prod.yml build backend
docker compose -f docker-compose.prod.yml build frontend
docker compose -f docker-compose.prod.yml build ml
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs -f backend
```

Builds are capped (Maven `-Xmx1g`, Next `--max-old-space-size=1536`). On a droplet with
less than 4 GB of RAM, add a swap file before building.

## Update

```bash
git -C ../ pull && git -C ../../Jachai pull && git -C ../../rpML pull
docker compose -f docker-compose.prod.yml build backend frontend ml
docker compose -f docker-compose.prod.yml up -d
```

## Notes

- **Do not set `SPRING_PROFILES_ACTIVE=prod`.** The SMS and email senders only exist outside the
  `prod` profile today, so the API would fail to start.
- **Storage:** `STORAGE_DRIVER=s3` keeps files in a private Spaces bucket. Browsers only talk to
  the API (`/api/v1/storage/...`), which streams to and from the bucket, so photo moderation and
  admin-only documents keep their access rules. The bucket needs no CORS rules and no public access.
- `NEXT_PUBLIC_API_BASE_URL` is baked into the frontend at build time from `API_DOMAIN`. After
  changing `API_DOMAIN`, rebuild the frontend.
- Postgres data, Caddy certificates, local uploads and the Hugging Face model cache are kept in
  named volumes (`docker volume ls`).
