#!/usr/bin/env bash
# The two services together, as they will run: sales-api (Java, PostgreSQL) and catalog-api (Python, MongoDB), with the
# real databases built by sales-db and catalog-db. It is the demo of the release, end to end, with Docker only:
#
#   bash scripts/e2e-with-catalog.sh
#
# It needs the sibling repositories next to this one: amaranta-shop-sales-db, amaranta-shop-catalog-db and
# amaranta-shop-catalog-api (or SALES_DB_DIR, CATALOG_DB_DIR, CATALOG_API_DIR). The claims it proves:
#   - a checkout takes the stock in MongoDB and writes the order, with the name and the price of the purchase, in PostgreSQL;
#   - changing the product afterwards does not change the order (RN-12);
#   - a rule of Catalog travels to the buyer; a retry with the same key reserves once (RN-15);
#   - THE INJECTED FAILURE: the save of the order fails after the stock was taken, and the stock comes back (RN-09);
#   - with Catalog down the checkout is a 503 and nothing is saved.
set -euo pipefail
cd "$(dirname "$0")/.."
# Git Bash on Windows must not rewrite the container paths given to docker, but openssl (a Windows program) does need
# its own paths converted: so the switch is set for docker only.
docker() { MSYS_NO_PATHCONV=1 command docker "$@"; }
winpath() { (cd "$1" 2> /dev/null && { pwd -W 2> /dev/null || pwd; }) || { echo "not found: $1 (clone it next to this repository or set its variable)" >&2; exit 1; }; }
SALES_DB_DIR="$(winpath "${SALES_DB_DIR:-../amaranta-shop-sales-db}")"
CATALOG_DB_DIR="$(winpath "${CATALOG_DB_DIR:-../amaranta-shop-catalog-db}")"
CATALOG_API_DIR="$(winpath "${CATALOG_API_DIR:-../amaranta-shop-catalog-api}")"

ID=$$
NET="e2e-net-$ID"; PG="e2e-pg-$ID"; MONGO="e2e-mongo-$ID"; SALES="e2e-sales-$ID"; CATALOG="e2e-catalog-$ID"
ADMIN=amaranta_admin
PW="$(openssl rand -hex 16)"; SALES_PW="$(openssl rand -hex 16)"; CATALOG_PW="$(openssl rand -hex 16)"
WORK="$(mktemp -d)"
cleanup() { docker rm -f "$PG" "$MONGO" "$SALES" "$CATALOG" > /dev/null 2>&1 || true; docker network rm "$NET" > /dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; docker logs --tail 15 "$SALES" >&2 || true; docker logs --tail 15 "$CATALOG" >&2 || true; exit 1; }
step() { echo "== $*"; }

openssl genrsa -out "$WORK/private.pem" 2048 2> /dev/null
PUBLIC="$(openssl rsa -in "$WORK/private.pem" -pubout 2> /dev/null | awk '{printf "%s\\n", $0}')"
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
token() { # token <role>
  local head body sig
  head="$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)"
  body="$(printf '{"sub":"33333333-3333-4333-8333-333333333333","role":"%s","exp":1893456000}' "$1" | b64url)"
  sig="$(printf '%s.%s' "$head" "$body" | openssl dgst -sha256 -sign "$WORK/private.pem" | b64url)"
  echo "$head.$body.$sig"
}

step "the databases: PostgreSQL (sales-db) and MongoDB (catalog-db)"
docker network create "$NET" > /dev/null
docker run -d --name "$PG" --network "$NET" --network-alias postgres -e POSTGRES_DB=amaranta -e POSTGRES_USER="$ADMIN" \
  -e POSTGRES_PASSWORD="$PW" postgres:16-alpine > /dev/null
docker run -d --name "$MONGO" --network "$NET" --network-alias mongo -e MONGO_INITDB_ROOT_USERNAME="$ADMIN" \
  -e MONGO_INITDB_ROOT_PASSWORD="$PW" mongo:7.0 > /dev/null
until docker exec "$PG" pg_isready -h 127.0.0.1 -U "$ADMIN" -d amaranta > /dev/null 2>&1; do sleep 1; done
until docker logs "$MONGO" 2>&1 | grep -q "MongoDB init process complete"; do sleep 1; done
mongo_admin() { docker exec -i "$MONGO" mongosh --quiet -u "$ADMIN" -p "$PW" --authenticationDatabase admin --eval "$1"; }
psql_admin() { docker exec "$PG" psql -tA -q -v ON_ERROR_STOP=1 -U "$ADMIN" -d amaranta -c "$1"; }
until mongo_admin "db.adminCommand('ping').ok" 2> /dev/null | grep -qx 1; do sleep 1; done
psql_admin "CREATE ROLE sales_app LOGIN PASSWORD '$SALES_PW'" > /dev/null
psql_admin "ALTER ROLE sales_app SET search_path = sales" > /dev/null
mongo_admin "db.getSiblingDB('catalog').createUser({ user: 'catalog_app', pwd: '$CATALOG_PW', roles: [{ role: 'readWrite', db: 'catalog' }] })" > /dev/null
docker run --rm --network "container:$PG" -v "$SALES_DB_DIR:/workspace:ro" -e FLYWAY_URL=jdbc:postgresql://localhost:5432/amaranta \
  -e FLYWAY_USER="$ADMIN" -e FLYWAY_PASSWORD="$PW" flyway/flyway:11.20 -workingDirectory=/workspace -configFiles=flyway.toml migrate | grep -E "Successfully applied|ERROR"
docker build -q -t catalog-db-liquibase:e2e -f "$CATALOG_DB_DIR/deploy/liquibase.Dockerfile" "$CATALOG_DB_DIR/deploy" > /dev/null
docker run --rm --network "$NET" -v "$CATALOG_DB_DIR:/workspace:ro" -e LIQUIBASE_COMMAND_USERNAME="$ADMIN" -e LIQUIBASE_COMMAND_PASSWORD="$PW" \
  --entrypoint liquibase catalog-db-liquibase:e2e --search-path=/workspace --changelog-file=changelog/changelog-master.yaml \
  --database-changelog-table-name=databasechangelog_catalog --database-changelog-lock-table-name=databasechangeloglock_catalog \
  --url="mongodb://mongo:27017/catalog?authSource=admin" update 2>&1 | grep -E "^Run:|ERROR|rror:" || true

step "the two services (their container images)"
docker build -q -f deploy/Dockerfile -t sales-api:e2e . > /dev/null
docker build -q -f "$CATALOG_API_DIR/deploy/Dockerfile" -t catalog-api:e2e "$CATALOG_API_DIR" > /dev/null
docker run -d --name "$CATALOG" --network "$NET" --network-alias catalog-api \
  -e "MONGODB_URI=mongodb://catalog_app:$CATALOG_PW@mongo:27017/catalog?authSource=catalog" -e "JWT_PUBLIC_KEY=$PUBLIC" catalog-api:e2e > /dev/null
docker run -d --name "$SALES" --network "$NET" --network-alias sales-api \
  -e DATABASE_URL=jdbc:postgresql://postgres:5432/amaranta -e DATABASE_USER=sales_app -e DATABASE_PASSWORD="$SALES_PW" \
  -e "JWT_PUBLIC_KEY=$PUBLIC" -e CATALOG_API_URL=http://catalog-api:8082 -e CLOCK_FIXED_AT=2026-10-12T15:00:00Z sales-api:e2e > /dev/null

http() { docker run --rm --network "$NET" curlimages/curl:latest -s -w '\n%{http_code}' "$@"; }
code() { tail -1; }
for service in catalog-api:8082 sales-api:8080; do
  for _ in $(seq 1 90); do http "http://$service/health" 2> /dev/null | code | grep -qx 200 && break; sleep 1; done
  [ "$(http "http://$service/health" | code)" = "200" ] || fail "$service did not come up"
done

FOOD=399d2ea8-1ccb-4193-8255-1b51e331984d   # FOOD-001, the product id the monolith issued: 24 units, 189000 pesos
stock() { mongo_admin "db.getSiblingDB('catalog').product.findOne({_id: '$FOOD'}).stockQuantity.toString()"; }
orders() { psql_admin "select count(*) from sales.orders"; }
buy() { # buy <idempotency-key> <quantity> [product]
  http -X POST http://sales-api:8080/api/v1/orders -H 'Content-Type: application/json' -H "Idempotency-Key: $1" \
    -d "{\"items\":[{\"productId\":\"${3:-$FOOD}\",\"quantity\":$2}],\"fulfillmentMethod\":\"PICKUP\",\"paymentMethod\":\"CASH\",\"contactName\":\"Ana Torres\",\"contactPhone\":\"3001234567\"}"
}
[ "$(stock)" = "24" ] || fail "the seed must start with 24 units"

step "1. a visitor buys 2 units: 201, the stock falls in MongoDB, the order is in PostgreSQL with the price of the purchase"
out="$(buy e2e-key-0001 2)"
[ "$(echo "$out" | code)" = "201" ] || fail "checkout must be 201: $out"
echo "$out" | grep -q '"total":378000' || fail "the total must be 2 x 189000: $out"
[ "$(stock)" = "22" ] || fail "the stock in MongoDB must be 22"
[ "$(psql_admin "select unit_price_amount from sales.order_items")" = "189000" ] || fail "the price of the purchase is not in the order"

step "2. the product changes afterwards: the order does not (RN-12)"
http -X PATCH "http://catalog-api:8082/api/v1/admin/products/$FOOD" -H "Authorization: Bearer $(token ADMIN)" \
  -H 'Content-Type: application/json' -d '{"name":"Renamed food","price":250000}' | code | grep -qx 200 || fail "the admin edit must be 200"
[ "$(psql_admin "select unit_price_amount from sales.order_items")" = "189000" ] || fail "the order changed with the product"
[ "$(psql_admin "select product_name from sales.order_items")" != "Renamed food" ] || fail "the order shows the new name"

step "3. the same Idempotency-Key again: 200, the same order, and the stock is taken ONCE"
[ "$(buy e2e-key-0001 2 | code)" = "200" ] || fail "the retry must be 200"
[ "$(orders)" = "1" ] && [ "$(stock)" = "22" ] || fail "the retry created a second order or took the stock again"

step "4. a rule of Catalog reaches the buyer: 30 units when 22 are left is a 422 INV-PRODUCT-002, and nothing changes"
out="$(buy e2e-key-0002 30)"
[ "$(echo "$out" | code)" = "422" ] && echo "$out" | grep -q INV-PRODUCT-002 || fail "must be 422 INV-PRODUCT-002: $out"
[ "$(orders)" = "1" ] && [ "$(stock)" = "22" ] || fail "a rejected checkout changed something"

step "5. THE INJECTED FAILURE: the order cannot be saved AFTER the stock was taken -> the stock comes back (RN-09)"
psql_admin "ALTER TABLE sales.orders ADD CONSTRAINT chk_demo_failure CHECK (total_amount < 0) NOT VALID" > /dev/null
out="$(buy e2e-key-0003 3)"
[ "$(echo "$out" | code)" = "500" ] || fail "a save that fails must be a 500 with the envelope: $out"
[ "$(stock)" = "22" ] || fail "THE STOCK WAS NOT GIVEN BACK: it is $(stock), it must be 22"
[ "$(orders)" = "1" ] || fail "an order was saved despite the failure"
psql_admin "ALTER TABLE sales.orders DROP CONSTRAINT chk_demo_failure" > /dev/null

step "6. with Catalog down the checkout is a 503 and nothing is saved"
docker stop "$CATALOG" > /dev/null
out="$(buy e2e-key-0004 1)"
[ "$(echo "$out" | code)" = "503" ] && echo "$out" | grep -q SERVICE_UNAVAILABLE || fail "must be 503 SERVICE_UNAVAILABLE: $out"
[ "$(orders)" = "1" ] || fail "an order was saved while Catalog was down"

echo "OK: two services, two databases, the price frozen, the retry, a rule that travels, the compensation and the outage"
