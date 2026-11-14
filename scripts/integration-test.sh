#!/usr/bin/env bash
# The integration tests against a real PostgreSQL 16 with the schema of amaranta-shop-sales-db. Needs only Docker
# and a checkout of the sales-db repository next to this one (or SALES_DB_DIR pointing at it):
#
#   bash scripts/integration-test.sh
#
# It starts a throwaway database, creates sales_app the way amaranta-shop-infra-postgres does, migrates the schema
# with the Flyway of sales-db, and runs the tests as sales_app, the user the service really connects with. The
# integration tests of the build are skipped, not failed, when TEST_DATABASE_URL is not set (Annex C).
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # Git Bash on Windows must not rewrite the container paths below

# pwd -W gives the Windows path under Git Bash, which is what Docker Desktop mounts; elsewhere plain pwd.
SALES_DB_DIR="$(cd "${SALES_DB_DIR:-../amaranta-shop-sales-db}" 2> /dev/null && { pwd -W 2> /dev/null || pwd; })" \
  || { echo "sales-db not found: clone it next to this repository or set SALES_DB_DIR" >&2; exit 1; }
[ -f "$SALES_DB_DIR/flyway.toml" ] || { echo "$SALES_DB_DIR is not the sales-db repository" >&2; exit 1; }

NAME="sales-api-it-$$"
NET="sales-api-net-$$"
ADMIN=amaranta_admin
PW="$(openssl rand -hex 16)"      # a throwaway instance with throwaway passwords: nothing to version
APP_PW="$(openssl rand -hex 16)"

docker network create "$NET" > /dev/null
docker run -d --rm --name "$NAME" --network "$NET" --network-alias postgres \
  -e POSTGRES_DB=amaranta -e POSTGRES_USER="$ADMIN" -e POSTGRES_PASSWORD="$PW" postgres:16-alpine > /dev/null
trap 'docker rm -f "$NAME" > /dev/null 2>&1 || true; docker network rm "$NET" > /dev/null 2>&1 || true' EXIT
# -h 127.0.0.1: during its first start the image runs a temporary server that only listens on the socket.
until docker exec "$NAME" pg_isready -h 127.0.0.1 -U "$ADMIN" -d amaranta > /dev/null 2>&1; do sleep 1; done

# What amaranta-shop-infra-postgres does at the start of the instance: the login user of the domain.
docker exec -i "$NAME" psql -v ON_ERROR_STOP=1 -q -U "$ADMIN" -d amaranta \
  -c "CREATE ROLE sales_app LOGIN PASSWORD '$APP_PW'" -c "ALTER ROLE sales_app SET search_path = sales"

# What sales-db does: build the schema, the roles and the grants.
docker run --rm --network "container:$NAME" -v "$SALES_DB_DIR:/workspace:ro" \
  -e FLYWAY_URL=jdbc:postgresql://localhost:5432/amaranta -e FLYWAY_USER="$ADMIN" -e FLYWAY_PASSWORD="$PW" \
  flyway/flyway:11.20 -workingDirectory=/workspace -configFiles=flyway.toml migrate | grep -E "Successfully|ERROR"

MVN_DOCKER_ARGS="--network $NET \
  -e TEST_DATABASE_URL=jdbc:postgresql://postgres:5432/amaranta \
  -e TEST_DATABASE_USER=sales_app -e TEST_DATABASE_PASSWORD=$APP_PW \
  -e TEST_DATABASE_ADMIN_USER=$ADMIN -e TEST_DATABASE_ADMIN_PASSWORD=$PW" \
  bash scripts/mvn.sh -pl sales-adapters -am verify "$@"
