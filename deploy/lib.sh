# Shared by the deploy scripts: run from deploy/, load .env, wrap docker compose.
# shellcheck shell=bash
set -euo pipefail
DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DEPLOY_DIR"

if [[ ! -f .env ]]; then
  echo "deploy/.env is missing: cp .env.example .env and fill it in (see README.md)." >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
. ./.env
set +a

ML_DIR="${ML_DIR:-../../rpML}"
compose() { docker compose -f "$DEPLOY_DIR/docker-compose.prod.yml" "$@"; }
log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

# Waits until a service's container reports healthy (or fails after $2 seconds).
wait_healthy() {
  local service="$1" timeout="${2:-240}" id status waited=0
  id="$(compose ps -q "$service")"
  while (( waited < timeout )); do
    status="$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || echo missing)"
    [[ "$status" == healthy ]] && { log "$service is healthy"; return 0; }
    sleep 5; waited=$((waited + 5))
  done
  log "$service did not become healthy within ${timeout}s (last status: $status)"
  compose logs --tail=80 "$service" || true
  return 1
}

# aws-cli in a throwaway container, pointed at Spaces. Checksums only when required: Spaces doesn't
# accept the CRC headers newer aws-cli versions send by default.
AWS_CLI_IMAGE="${AWS_CLI_IMAGE:-amazon/aws-cli:latest}"
spaces() {
  docker run --rm -i --user "$(id -u):$(id -g)" -e HOME=/tmp \
    -e AWS_ACCESS_KEY_ID="$SPACES_KEY" -e AWS_SECRET_ACCESS_KEY="$SPACES_SECRET" \
    -e AWS_DEFAULT_REGION="$SPACES_REGION" \
    -e AWS_REQUEST_CHECKSUM_CALCULATION=when_required -e AWS_RESPONSE_CHECKSUM_VALIDATION=when_required \
    -v "${SPACES_WORKDIR:-/tmp}:/work" -w /work \
    "$AWS_CLI_IMAGE" --endpoint-url "$SPACES_ENDPOINT" "$@"
}

# Postgres 17 client tools in a throwaway container (pg_dump must be >= the server's version).
# Both helpers run as the calling user, so files they write in the work dir stay ours.
PG_IMAGE="${PG_IMAGE:-postgres:17-alpine}"
pg() {
  docker run --rm -i --user "$(id -u):$(id -g)" \
    -e PGHOST="$DB_HOST" -e PGPORT="$DB_PORT" -e PGUSER="$DB_USER" -e PGPASSWORD="$DB_PASSWORD" \
    -e PGDATABASE="$DB_NAME" -e PGSSLMODE="${DB_SSLMODE:-require}" \
    -v "${PG_WORKDIR:-/tmp}:/work" -w /work \
    "$PG_IMAGE" "$@"
}
