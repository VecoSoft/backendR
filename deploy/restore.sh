#!/usr/bin/env bash
# Restores a pg_dump (custom format) into the managed database. DESTRUCTIVE: existing objects in
# $DB_NAME are dropped and recreated from the dump.
#
#   ./restore.sh latest                         # newest backup in s3://$SPACES_BUCKET/backups/
#   ./restore.sh backups/jachai-...dump         # a specific backup in Spaces
#   ./restore.sh /home/deploy/jachai-local.dump # a local file (first-time import from dev)
#   ./restore.sh --list                         # show the backups in Spaces
#
# The backend is stopped while restoring (if it is running) and started again afterwards.
. "$(dirname "$0")/lib.sh"

src="${1:-}"
[[ -n "$src" ]] || { sed -n '2,10p' "$0"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export PG_WORKDIR="$WORK" SPACES_WORKDIR="$WORK"

if [[ "$src" == "--list" ]]; then
  spaces s3 ls "s3://$SPACES_BUCKET/backups/"
  exit 0
fi

if [[ -f "$src" ]]; then
  cp "$src" "$WORK/restore.dump"
else
  key="$src"
  if [[ "$src" == latest ]]; then
    key="$(spaces s3api list-objects-v2 --bucket "$SPACES_BUCKET" --prefix backups/ \
      --query 'sort_by(Contents, &LastModified)[-1].Key' --output text)"
    [[ -n "$key" && "$key" != "None" ]] || { echo "no backups found in s3://$SPACES_BUCKET/backups/" >&2; exit 1; }
  fi
  log "downloading s3://$SPACES_BUCKET/$key"
  spaces s3 cp "s3://$SPACES_BUCKET/$key" /work/restore.dump --only-show-errors
fi
pg pg_restore --list /work/restore.dump > "$WORK/toc.txt"
echo "Dump: $(grep -m1 'Archive created at' "$WORK/toc.txt" || true)"
echo "Target: database '$DB_NAME' on $DB_HOST:$DB_PORT (everything in it will be replaced)"
if [[ "${2:-}" != "--yes" ]]; then
  read -r -p "Type the database name to continue: " answer
  [[ "$answer" == "$DB_NAME" ]] || { echo "aborted"; exit 1; }
fi

backend_was_running=false
if [[ -n "$(compose ps -q --status running backend 2>/dev/null)" ]]; then
  backend_was_running=true
  log "stopping backend for the restore"
  compose stop backend
fi

# Managed Postgres: the restore runs as $DB_USER (not a superuser), so skip ownership/privileges
# and the comments on extensions (only the extension owner may set those).
grep -v -E 'COMMENT - EXTENSION' "$WORK/toc.txt" > "$WORK/toc-filtered.txt"
log "restoring ..."
set +e
pg pg_restore --clean --if-exists --no-owner --no-privileges --jobs=2 \
  --use-list=/work/toc-filtered.txt --dbname="$DB_NAME" /work/restore.dump 2> "$WORK/restore.err"
status=$?
set -e
if (( status != 0 )); then
  echo "pg_restore reported problems (often harmless on managed Postgres, e.g. extension ownership):"
  grep -E '^pg_restore: (error|warning)' "$WORK/restore.err" | sort | uniq -c | head -30
fi
pg psql -v ON_ERROR_STOP=1 -At -c "SELECT 'flyway version ' || max(version) FROM flyway_schema_history WHERE success" || true

if $backend_was_running; then
  log "starting backend"
  compose start backend
  wait_healthy backend 300
fi
log "restore finished (exit $status)"
