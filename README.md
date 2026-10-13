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

The service of the sales domain: checkout (online and in store), mark an order as paid and list my orders. It never runs migrations: the schema comes from `amaranta-shop-sales-db`.

## Depends on

`amaranta-shop-sales-db` (the schema) on `amaranta-shop-infra-postgres` (the instance), `amaranta-shop-catalog-api` (stock reservation) and the public key of `amaranta-shop-security`.

## How to run

Nothing to run yet. Once the code lands: `mvn -B verify` to build and test, `mvn spring-boot:run` to start it with the variables of `.env.example`.

## Stack

Java 21 with Spring Boot, a three-module Maven build ([ADR-012](https://github.com/code-corhuila/amaranta-shop-docs/blob/main/05-architecture/decisions/records/ADR-012-stack-of-extracted-services.md)), from the teacher's `api-java` template. Specified in `specs/checkout.md` of the docs repository.

## Status

**Skeleton.** Only the base of a code repository is here (norm 5.1); nothing in it runs yet, and this README says so
on purpose. Next pull request: `feat/order-domain`: the `Order` aggregate and its invariants, tests first.
