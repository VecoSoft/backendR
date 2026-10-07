#!/usr/bin/env bash
# Nightly database backup: pg_dump (custom format) of the managed database, checked with
# pg_restore --list, uploaded to s3://$SPACES_BUCKET/backups/, and backups older than
# $BACKUP_KEEP_DAYS (default 14) deleted. Uses throwaway postgres:17 and aws-cli containers, so
# nothing needs installing on the droplet.
#
# Cron (deploy user, crontab -e), 02:30 Dhaka = 20:30 UTC:
#   30 20 * * * /opt/jachai/backendR/deploy/backup.sh >> /home/deploy/jachai-backup.log 2>&1
. "$(dirname "$0")/lib.sh"

KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="jachai-${DB_NAME}-${STAMP}.dump"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export PG_WORKDIR="$WORK" SPACES_WORKDIR="$WORK"

log "dumping $DB_NAME@$DB_HOST"
pg pg_dump --format=custom --compress=6 --no-owner --no-privileges --file="/work/$FILE"
# A dump that pg_restore can't list is not a backup.
pg pg_restore --list "/work/$FILE" >/dev/null
log "dump ok: $(du -h "$WORK/$FILE" | cut -f1)"

log "uploading to s3://$SPACES_BUCKET/backups/$FILE"
spaces s3 cp "/work/$FILE" "s3://$SPACES_BUCKET/backups/$FILE" --only-show-errors

CUTOFF="$(date -u -d "-${KEEP_DAYS} days" +%Y-%m-%dT%H:%M:%S)"
old="$(spaces s3api list-objects-v2 --bucket "$SPACES_BUCKET" --prefix backups/ \
  --query "Contents[?LastModified<'${CUTOFF}'].Key" --output text)"
if [[ -n "$old" && "$old" != "None" ]]; then
  for key in $old; do
    spaces s3 rm "s3://$SPACES_BUCKET/$key" --only-show-errors
    log "deleted old backup $key"
  done
fi
log "backup finished: backups/$FILE (keeping ${KEEP_DAYS} days)"
