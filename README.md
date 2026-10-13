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

## How to build and test

You need only Docker.

```bash
bash scripts/mvn.sh verify              # compile and test every module
bash scripts/mvn.sh -pl sales-core test # only the core: no server, no database, a few seconds
docker build -f deploy/Dockerfile -t sales-api .    # the container image
```

## Configuration

Every variable has a placeholder in `.env.example`; the real values never enter the repository.

| Variable | What it is |
|---|---|
| `PORT` | HTTP port, 8080 |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | the PostgreSQL instance, as `sales_app` |
| `JWT_PUBLIC_KEY` or `JWT_PUBLIC_KEY_FILE` | public key of `amaranta-shop-security` |
| `CATALOG_API_URL` | the internal stock endpoints of Catalog |
| `DB_POOL_MAX`, `DB_STATEMENT_TIMEOUT_MS` | pool size and time per statement, declared in the composition root |

## Status

Skeleton: the three modules build, the jar is produced and the image builds. Next, each its own pull request and
each test-first: `feat/order-domain` (the `Order` aggregate and its rules), `feat/place-order-use-case` (checkout
with its compensation and its idempotency), `feat/http-error-envelope`, `feat/order-routes`,
`feat/jdbc-order-repository` and `feat/catalog-stock-adapter`.
