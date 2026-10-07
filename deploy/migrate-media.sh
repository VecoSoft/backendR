#!/usr/bin/env bash
# One-off, re-runnable media migration from the old local uploads/ folder into Spaces:
#   1. copies every file (skips ones already in the bucket with the same size),
#   2. generates the WebP variants (200/600/1200 px) that are missing,
#   3. rewrites stored file URLs from the old API base (e.g. http://localhost:8085) to
#      https://$API_DOMAIN in every text/jsonb column (comma-separate several old bases).
# Runs the backend image as a throwaway container (no scheduled jobs; its web port is not published)
# that exits when done.
#
#   ./migrate-media.sh /home/deploy/uploads http://localhost:8085,http://localhost:8095
#   ./migrate-media.sh "" http://localhost:8085      # only rewrite URLs
. "$(dirname "$0")/lib.sh"

src="${1:-}"
old_base="${2:-}"
[[ -n "$src" || -n "$old_base" ]] || { sed -n '2,11p' "$0"; exit 1; }

args=(run --rm --no-deps -e APP_SCHEDULING_ENABLED=false)
if [[ -n "$src" ]]; then
  [[ -d "$src" ]] || { echo "not a directory: $src" >&2; exit 1; }
  args+=(-v "$(cd "$src" && pwd):/import:ro" -e STORAGE_MIGRATE_SOURCE=/import)
  log "migrating $(find "$src" -type f | wc -l) files from $src to s3://$SPACES_BUCKET/${SPACES_KEY_PREFIX:-}"
fi
[[ -n "$old_base" ]] && args+=(-e STORAGE_MIGRATE_REWRITE_FROM="$old_base")

compose "${args[@]}" backend
log "media migration done"
