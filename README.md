# amaranta-shop-sales-api

> sales bounded context: service API

Part of the **Amaranta Shop** distributed system — team `amaranta-shop`, Grupo 2.
Governance and documentation live in [`amaranta-shop-docs`](https://github.com/code-corhuila/amaranta-shop-docs).

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `amaranta-shop-docs`.

## Role in the system

The service of the **sales** domain: place an order (online, with or without an account, and in store), mark
an order as paid and list my orders. It owns the *data* of the `sales` schema and never the structure: the
schema comes from `amaranta-shop-sales-db`, and this service never migrates it (norm 5.2.1). It is the
first service extracted from the monolith ([spec](https://github.com/code-corhuila/amaranta-shop-docs/blob/main/specs/checkout.md)).

## Depends on

- `amaranta-shop-sales-db` on `amaranta-shop-infra-postgres`: the schema, and `sales_app`, the only user it
  connects with (Annex J, J.4).
- `amaranta-shop-catalog-api`: the stock of a product is reserved and released there, over two **internal**
  HTTP endpoints that the gateway never publishes. Sales never reads or writes Catalog's database.
- `amaranta-shop-security`: only its **public** key, to verify tokens. No service holds the private one.

## Stack

Java 21 and Spring Boot 3.5, in three Maven modules, from the teacher's `api-java` template
([ADR-012](https://github.com/code-corhuila/amaranta-shop-docs/blob/main/05-architecture/decisions/records/ADR-012-stack-of-extracted-services.md)).
Built with **TDD**: every rule of the spec (`RN-01` to `RN-15`) has a named test, written before the code that
makes it pass. Built on **DDD**: the domain is the Order aggregate, in the language of the business. Specified
first (**SDD**): the spec says what must be true, this repository proves it.

## Layout and the rule that matters

```
sales-core/       the domain and the use cases. NO framework: a @Service, an @Entity or a servlet type does not compile
sales-adapters/   everything that touches the outside: HTTP in; PostgreSQL and Catalog out
sales-app/        the composition root: the only module that knows every concrete type and every limit
deploy/           Dockerfile and compose.yml of this service only
scripts/mvn.sh    Maven in Docker, so nothing is installed and the build is the same everywhere
```

The dependency arrows point **towards the domain**: `sales-app` -> `sales-adapters` -> `sales-core`. The core
declares no framework in its `pom.xml` and CI fails if it imports one. A port is an interface in the core; an
adapter implements it outside. The domain throws typed errors and does not know what a `422` is: the status is
decided in one place in the HTTP adapter.

## The contract

Public paths go through the gateway as `/api/v1/orders*`, before and after the move out of the monolith. Every error is
`{error, message, details?, traceId}` and the status is decided in one place (`ErrorHandler`).

| Route | Who | Answers |
|---|---|---|
| `POST /api/v1/orders` + `Idempotency-Key` | a visitor **with no token**, or any customer | `201` + `Location`; `200` and the same order on a retry; `400` one entry per invalid field; `401` if a token is present but broken; `422` a rule of the business; `503` Catalog is down |
| `POST /api/v1/orders/in-store` | `RECEPTIONIST` | the same, attributed to the receptionist; `403` for any other role, before any stock is touched |
| `POST /api/v1/orders/{id}/mark-paid` | `RECEPTIONIST` | `200`; `422 INVALID_STATUS_TRANSITION` the second time; `404`; `400` for a malformed id |
| `GET /api/v1/orders?page=&limit=` | any token | `{data, meta}`, my orders only, newest first, `limit` 1-100 (20 by default) |
| `GET /health` | nobody | `200`, no token |

Each service validates the token itself, with RS256 only. The gateway only checks that one exists.

## How to build, test and run

You need only Docker. Nothing is installed: Maven, Java, PostgreSQL and Flyway all run in containers.

```bash
bash scripts/mvn.sh verify              # compile and test every module (149 tests; 7 need the database)
bash scripts/mvn.sh -pl sales-core test # the core alone: no server, no database, a few seconds
bash scripts/integration-test.sh        # the repository against a real PostgreSQL, as sales_app
bash scripts/smoke-test.sh              # the container, a real database and a Catalog that is switched off
bash scripts/e2e-with-catalog.sh        # THE RELEASE DEMO: sales-api and catalog-api together, with both real databases
docker build -f deploy/Dockerfile -t sales-api .    # the container image
```

The last two need the `amaranta-shop-sales-db` repository next to this one (or `SALES_DB_DIR`): they build the real
schema with its Flyway. The smoke test is the demo of the release in one command: a visitor buys, the order is in the
database, a retry gives the same order, a receptionist marks it as paid, and **with Catalog down the checkout answers
`503` and saves nothing**. In CI those two run when the repository variable `SALES_DB_CHECKOUT` is `true`.

`e2e-with-catalog.sh` runs this service and `amaranta-shop-catalog-api` (Python, MongoDB) side by side, each with its own real
database, and proves: the stock falls in MongoDB while the order, with the price of the purchase, lands in PostgreSQL; changing
the product afterwards does not change the order; a retry takes the stock once; a rule of Catalog reaches the buyer; and the
two failures that matter. **With the order's save made to fail after the stock was taken, the stock comes back (RN-09)**, and
with Catalog down the checkout answers `503` and saves nothing. It needs `sales-db`, `catalog-db` and `catalog-api` next to this repository.

## Configuration

Every variable has a placeholder in `.env.example`; the real values never enter the repository.

| Variable | What it is |
|---|---|
| `PORT` | HTTP port, 8080 |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | the PostgreSQL instance, as `sales_app`. **Empty: orders are kept in memory** (local runs) |
| `JWT_PUBLIC_KEY` or `JWT_PUBLIC_KEY_FILE` | public key of `amaranta-shop-security`. Without one the service refuses to start |
| `CATALOG_API_URL` | the internal stock endpoints of Catalog |
| `DB_POOL_MAX`, `DB_STATEMENT_TIMEOUT_MS` | pool size and time per statement |
| `CLOCK_FIXED_AT` | **demos and smoke tests only**: the service believes it is that instant, and warns. Never in production |

The limits are written with their value in `SalesConfiguration` and `application.yml` (norm 5.3.10): connection timeout 5 s,
keep-alive 60 s, graceful shutdown 20 s, pool 10, a wait of 5 s for a connection, 5 s per statement, 2 s to connect to
Catalog and 3 s per call.

## The rules and the tests that prove them

Each rule of the [spec](https://github.com/code-corhuila/amaranta-shop-docs/blob/main/specs/checkout.md) (section 4) is a
named test, written before the code (TDD). A rule without a test is an intention, not a rule.

| Rule | What it says | Test |
|---|---|---|
| RN-01 | the total is the sum of the lines | `OrderTest.totalEqualsTheSumOfTheLines` |
| RN-02 | at least one line (`INV-ORDER-004`) | `OrderTest.rejectsAnOrderWithoutLines` |
| RN-03 | a product once per order (`INV-ORDER-003`) | `OrderTest.rejectsTheSameProductTwice` |
| RN-04 | a quantity from 1 to 50 (`INV-ORDER-ITEM-001`) | `OrderTest.rejectsAQuantityOutsideOneToFifty`, `OrderLineTest`, `PlaceOrderHttpTest.everyInvalidFieldIsNamed` |
| RN-05 | contact name and phone, not blank (`INV-ORDER-006`) | `OrderTest.rejectsBlankContactData` |
| RN-06 | a delivery needs an address, a pickup stores none (`INV-ORDER-007`) | `OrderTest.requiresAnAddressForDelivery`, `dropsTheAddressOnPickup` |
| RN-07 | no order while the shop is closed, Bogota time (`INV-ORDER-008`) | `OrderTest.rejectsOrdersWhileTheShopIsClosed`, `ShopHoursTest` |
| RN-08 | stock is reserved and released only through Catalog | `CatalogStockHttpAdapterTest` (a real HTTP server) |
| RN-09 | a rejected order gives its reservation back, once, never retried | `PlaceOrderServiceTest.releasesTheReservationWhenSavingFails`, `neverRetriesARelease_andTheOriginalErrorSurvives`, `CatalogStockHttpAdapterTest.aReleaseThatFailsIsNeverRetried` |
| RN-10 | nothing changes after the order, except the payment | `OrderPaymentTest.hasNoMutatorExceptPay`, `theLinesOfAnOrderCannotBeChangedFromOutside` |
| RN-11 | only a `PENDING` order can be paid (`INV-ORDER-005`) | `OrderPaymentTest.onlyAPendingOrderCanBePaid`, `PayAndListHttpTest` |
| RN-12 | a line keeps the name and price of the purchase | `OrderLineTest.keepsTheNameAndThePriceOfThePurchase`, the repository contract |
| RN-13 | other domains by id only; `sales_app` writes only in `sales` | the integration test connects as `sales_app`; the privilege tests live in `sales-db` |
| RN-14 | guest on `POST /orders`; receptionist on in-store and mark-paid; token on the rest | `AuthFilterTest`, `PlaceOrderHttpTest`, `PayAndListHttpTest`, `SalesHttpTest` |
| RN-15 | one order per `Idempotency-Key`; the stock is reserved once | `PlaceOrderServiceTest.replaysTheSameOrder...`, `JdbcOrderRepositoryIntegrationTest.twoRequestsWithTheSameKeyAtTheSameTimeCreateExactlyOneOrder` |
| CA-C1.4 | a present but broken token is a `401`, never a silent guest purchase | `AuthFilterTest.aBrokenTokenOnTheGuestRouteIs401NotAGuestPurchase` |
| CA-C3.3 | a rule that needs no Catalog data never calls Catalog | `PlaceOrderServiceTest.doesNotReserveWhenTheShopIsClosed`, `PlaceOrderHttpTest.aClosedShopIs422WithItsRule_andCatalogIsNeverCalled` |
| CA-C3.5 | only the event failing keeps the stock: the order exists | `PlaceOrderServiceTest.keepsTheReservationWhenOnlyPublishingTheEventFails` |
| CA-C5, CA-C6 | pay and list | `OrderQueriesTest`, `PayAndListHttpTest` |

## Decisions and residues, declared

- **Whole pesos and plural table names** (`orders`, `order_items`), as in the monolith: OD-1 and OD-4 of the spec.
- **The lines of an order come back sorted by name**, because the schema keeps no line number. A later migration can add one.
- **Reserving can leave units reserved** if this service dies between Catalog's answer and the save: the declared residue
  of DP-8. A reservation id with an idempotent release is the upgrade path.
- **`InMemoryOrderRepository` ships in the jar** so the service runs without a database; it honours the same contract as the
  PostgreSQL one and logs a warning on start.
- **Not in this cut**: `GET /api/v1/orders/{id}` (the `Location` header names it), cancelling and fulfilling an order,
  payment processing and a message broker (spec section 6).

## Status

Complete for the checkout: domain, use cases, HTTP, PostgreSQL, Catalog, the composition root and the container, with the
end-to-end demo. Next in the system: `amaranta-shop-catalog-api` must serve the two internal endpoints this service calls,
and `amaranta-shop-api-gateway` must route `/api/v1/orders*` here; then the contract suite of the spec (section 8.5, step 6)
runs against this service.
