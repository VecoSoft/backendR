#!/usr/bin/env bash
# Puts the images from before the last build back into service (no rebuild, a few seconds).
# Database migrations are NOT undone: Flyway migrations are forward-only, and an older API
# ignores migrations newer than itself, so this is safe for additive schema changes.
#
#   ./rollback.sh            # backend and ml
#   ./rollback.sh backend
. "$(dirname "$0")/lib.sh"

services=("$@")
(( ${#services[@]} )) || services=(backend ml)

for s in "${services[@]}"; do
  image="jachai/$s"
  if ! docker image inspect "$image:previous" >/dev/null 2>&1; then
    log "no $image:previous to roll back to; skipping $s"
    continue
  fi
  docker tag "$image:latest" "$image:rolled-back" 2>/dev/null || true
  docker tag "$image:previous" "$image:latest"
  log "$s -> previous image (the replaced one is kept as $image:rolled-back)"
done

compose up -d --no-build
wait_healthy backend 300
[[ -f .last-deploy ]] && { echo "Code that was running before the last update:"; cat .last-deploy; }
echo "To stay on the old code for future builds:  git -C .. checkout <sha>  (and rpML likewise), then ./build.sh"
