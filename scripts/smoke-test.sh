#!/usr/bin/env bash
# The service as it will run: the container image, a real PostgreSQL built by sales-db, and a Catalog that can be
# switched off. It is the demo of the release, end to end, with Docker only:
#
#   bash scripts/smoke-test.sh          # needs the sales-db repository next to this one, or SALES_DB_DIR
#
# It proves that the image boots and answers /health, that a visitor buys without an account and the order is in
# the database written by sales_app, that a retry returns the same order, that a receptionist marks it as paid,
# and the failure that matters: with Catalog down the checkout answers 503 and NOTHING is saved.
set -euo pipefail
cd "$(dirname "$0")/.."
# Git Bash on Windows must not rewrite the container paths given to docker, but openssl (a Windows program)
# does need its own paths converted: so the switch is set for docker only.
docker() { MSYS_NO_PATHCONV=1 command docker "$@"; }

SALES_DB_DIR="$(cd "${SALES_DB_DIR:-../amaranta-shop-sales-db}" 2> /dev/null && { pwd -W 2> /dev/null || pwd; })" \
  || { echo "sales-db not found: clone it next to this repository or set SALES_DB_DIR" >&2; exit 1; }

ID=$$
NET="sales-smoke-net-$ID"
PG="sales-smoke-pg-$ID"
CATALOG="sales-smoke-catalog-$ID"
API="sales-smoke-api-$ID"
ADMIN=amaranta_admin
PW="$(openssl rand -hex 16)"
APP_PW="$(openssl rand -hex 16)"
WORK="$(mktemp -d)"
cleanup() { docker rm -f "$PG" "$CATALOG" "$API" > /dev/null 2>&1 || true; docker network rm "$NET" > /dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; docker logs --tail 30 "$API" >&2 || true; exit 1; }
step() { echo "== $*"; }

# ---- a signing key for the test: only its PUBLIC half goes to the service ------------------------------------
openssl genrsa -out "$WORK/private.pem" 2048 2> /dev/null
PUBLIC="$(openssl rsa -in "$WORK/private.pem" -pubout 2> /dev/null | awk '{printf "%s\\n", $0}')"
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
token() { # token <role>: a valid RS256 token for a user, expiring in 2030
  local head body sig
  head="$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)"
  body="$(printf '{"sub":"33333333-3333-4333-8333-333333333333","role":"%s","exp":1893456000}' "$1" | b64url)"
  sig="$(printf '%s.%s' "$head" "$body" | openssl dgst -sha256 -sign "$WORK/private.pem" | b64url)"
  echo "$head.$body.$sig"
}

# ---- the platform: database, schema, Catalog, service ---------------------------------------------------------
step "database and schema (sales-db)"
docker network create "$NET" > /dev/null
docker run -d --name "$PG" --network "$NET" --network-alias postgres -e POSTGRES_DB=amaranta -e POSTGRES_USER="$ADMIN" \
  -e POSTGRES_PASSWORD="$PW" postgres:16-alpine > /dev/null
until docker exec "$PG" pg_isready -h 127.0.0.1 -U "$ADMIN" -d amaranta > /dev/null 2>&1; do sleep 1; done
docker exec "$PG" psql -q -v ON_ERROR_STOP=1 -U "$ADMIN" -d amaranta \
  -c "CREATE ROLE sales_app LOGIN PASSWORD '$APP_PW'" -c "ALTER ROLE sales_app SET search_path = sales"
docker run --rm --network "container:$PG" -v "$SALES_DB_DIR:/workspace:ro" \
  -e FLYWAY_URL=jdbc:postgresql://localhost:5432/amaranta -e FLYWAY_USER="$ADMIN" -e FLYWAY_PASSWORD="$PW" \
  flyway/flyway:11.20 -workingDirectory=/workspace -configFiles=flyway.toml migrate | grep -E "Successfully applied|ERROR"

step "a Catalog that answers (a stub of its two internal endpoints)"
cat > "$WORK/catalog.py" << 'PYEOF'
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path.endswith("/reserve-stock"):
            lines = [{"productId": i["productId"], "productName": "Dog food 3 kg", "unitPriceAmount": 45000,
                      "quantity": i["quantity"]} for i in body["items"]]
            payload = json.dumps({"lines": lines}).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload))); self.end_headers(); self.wfile.write(payload)
        else:
            self.send_response(204); self.end_headers()
    def log_message(self, *args): pass

HTTPServer(("0.0.0.0", 8082), Handler).serve_forever()
PYEOF
docker run -d --name "$CATALOG" --network "$NET" --network-alias catalog-api \
  python:3.12-alpine python -c "$(cat "$WORK/catalog.py")" > /dev/null

step "the service (the container image)"
docker build -q -f deploy/Dockerfile -t sales-api:smoke . > /dev/null
docker run -d --name "$API" --network "$NET" --network-alias sales-api \
  -e DATABASE_URL=jdbc:postgresql://postgres:5432/amaranta -e DATABASE_USER=sales_app -e DATABASE_PASSWORD="$APP_PW" \
  -e JWT_PUBLIC_KEY="$PUBLIC" -e CATALOG_API_URL=http://catalog-api:8082 \
  -e CLOCK_FIXED_AT=2026-10-12T15:00:00Z \
  sales-api:smoke > /dev/null

api() { docker run --rm --network "$NET" curlimages/curl:latest -s -w '\n%{http_code}' "$@"; }
for _ in $(seq 1 60); do api http://sales-api:8080/health 2> /dev/null | tail -1 | grep -qx 200 && break; sleep 1; done
[ "$(api http://sales-api:8080/health | tail -1)" = "200" ] || fail "the service did not come up"

ORDER='{"items":[{"productId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","quantity":2}],"fulfillmentMethod":"PICKUP","paymentMethod":"CASH","contactName":"Ana Torres","contactPhone":"3001234567"}'
checkout() { api -X POST http://sales-api:8080/api/v1/orders -H 'Content-Type: application/json' -H "Idempotency-Key: $1" -d "$ORDER"; }
sql() { docker exec "$PG" psql -tA -U "$ADMIN" -d amaranta -c "$1"; }

step "1. /health answers; a protected route without a token is 401"
[ "$(api http://sales-api:8080/api/v1/orders | tail -1)" = "401" ] || fail "no token must be 401"

step "2. a visitor buys without an account: 201, and the order is in the database"
out="$(checkout smoke-key-0001)"
[ "$(echo "$out" | tail -1)" = "201" ] || fail "checkout answered $(echo "$out" | tail -1): $out"
[ "$(sql "select count(*) from sales.orders where status='PENDING' and total_amount=90000")" = "1" ] || fail "the order is not in the database"

step "3. the same Idempotency-Key again: 200 and still ONE order"
[ "$(checkout smoke-key-0001 | tail -1)" = "200" ] || fail "the retry must be 200"
[ "$(sql "select count(*) from sales.orders")" = "1" ] || fail "the retry created a second order"

step "4. a receptionist marks it as paid; a second time is 422"
ID_ORDER="$(sql "select id from sales.orders")"
pay() { api -X POST "http://sales-api:8080/api/v1/orders/$ID_ORDER/mark-paid" -H "Authorization: Bearer $(token RECEPTIONIST)"; }
[ "$(pay | tail -1)" = "200" ] || fail "mark-paid must be 200"
[ "$(pay | tail -1)" = "422" ] || fail "paying twice must be 422"
[ "$(sql "select status from sales.orders")" = "PAID" ] || fail "the status was not written"

step "5. THE FAILURE: with Catalog down the checkout is 503 and nothing is saved"
docker stop "$CATALOG" > /dev/null
out="$(checkout smoke-key-0002)"
[ "$(echo "$out" | tail -1)" = "503" ] || fail "a Catalog that is down must be 503, got $(echo "$out" | tail -1)"
echo "$out" | grep -q SERVICE_UNAVAILABLE || fail "the 503 must carry the envelope"
[ "$(sql "select count(*) from sales.orders")" = "1" ] || fail "an order was saved while Catalog was down"
[ "$(sql "select count(*) from sales.idempotency_key")" = "1" ] || fail "a key was saved while Catalog was down"

echo "OK: image, database, idempotency, roles and the failure with Catalog down"
