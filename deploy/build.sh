#!/usr/bin/env bash
# Builds the images on the droplet, ONE AT A TIME (a 4 GB droplet can't build both at once while
# the stack is running). The image being replaced is kept as :previous for rollback.sh.
#
#   ./build.sh              # ml, then backend
#   ./build.sh backend      # just one
. "$(dirname "$0")/lib.sh"

services=("$@")
(( ${#services[@]} )) || services=(ml backend)

for s in "${services[@]}"; do
  case "$s" in
    backend|ml) ;;
    *) echo "unknown service: $s (backend | ml)" >&2; exit 1 ;;
  esac
  image="jachai/$s"
  if docker image inspect "$image:latest" >/dev/null 2>&1; then
    docker tag "$image:latest" "$image:previous"
    log "kept current $image as $image:previous"
  fi
  log "building $s ..."
  compose build --pull "$s"
  log "built $s"
done
