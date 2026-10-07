#!/usr/bin/env bash
# Deploys the latest main: pull both repos, build one image at a time, restart, check health,
# prune. Run as the deploy user from anywhere:  /opt/jachai/backendR/deploy/update.sh
. "$(dirname "$0")/lib.sh"

{
  echo "previous deploy: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "backendR $(git -C .. rev-parse --short HEAD)"
  echo "rpML     $(git -C "$ML_DIR" rev-parse --short HEAD)"
} > .last-deploy

log "pulling backendR and rpML"
git -C .. pull --ff-only
git -C "$ML_DIR" pull --ff-only

./build.sh

log "starting the new containers"
compose up -d --remove-orphans
wait_healthy ml 180
wait_healthy backend 300
compose exec -T backend curl -fsS http://localhost:8085/actuator/health/readiness && echo

log "pruning dangling images and old build cache (keeps :latest and :previous)"
docker image prune -f >/dev/null
docker builder prune -f --filter until=168h >/dev/null
log "deployed backendR $(git -C .. rev-parse --short HEAD), rpML $(git -C "$ML_DIR" rev-parse --short HEAD)"
