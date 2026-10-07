#!/usr/bin/env bash
# psql against the managed database, with the credentials from .env (postgres:17 client container).
#   ./psql.sh                                  # interactive
#   ./psql.sh -c "SELECT count(*) FROM app_user"
. "$(dirname "$0")/lib.sh"
if [[ -t 0 ]]; then
  docker run --rm -it --user "$(id -u):$(id -g)" \
    -e PGHOST="$DB_HOST" -e PGPORT="$DB_PORT" -e PGUSER="$DB_USER" -e PGPASSWORD="$DB_PASSWORD" \
    -e PGDATABASE="$DB_NAME" -e PGSSLMODE="${DB_SSLMODE:-require}" "$PG_IMAGE" psql "$@"
else
  pg psql "$@"
fi
