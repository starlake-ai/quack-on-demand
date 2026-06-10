---
id: architecture-map
title: Architecture map
---

This page orients a contributor in the codebase: the process model, the request flow, and where each concern lives. For the runtime concepts behind it, see [Architecture](/concepts/architecture) and the rest of the Concepts section.

## One process, three sockets

The manager is a single uber-jar that exposes three sockets:

| Socket | Default | What it is |
|---|---|---|
| Manager REST + React UI | `:20900` | Tapir + HTTP4s Ember. Endpoints under `ondemand/api/*Handlers.scala`; the SPA at `/ui/*` is served from `src/main/resources/ui`. |
| Arrow FlightSQL edge | `:31338` | The query surface. TLS on by default, cert auto-generated under `certs/`. |
| Quack nodes | `:21900-22500` | Child DuckDB Quack processes (local mode) or pods (Kubernetes), each serving a `/quack` HTTP endpoint. |

## The FlightSQL request flow

A query traverses these components in order:

```
client
  → FlightProducerImpl        (Arrow Flight wire surface)
  → AuthenticationService     (Basic / JWT / OIDC)
  → FlightSqlRouter.execute   (the routing core)
  → StatementValidator        (per-statement ACL gate)
  → StatementClassifier        (READ / WRITE / DDL bucket)
  → Router.pick               (least-loaded node for the kind)
  → QuackHttpAdapter          (HTTP call to the chosen node's /quack)
  → child node                (DuckDB over DuckLake)
  → Arrow stream back through Flight
```

The routing core (`FlightSqlRouter`, `Router`, `StatementClassifier`, `RoleMatcher`) is deliberately separable from the Flight wire, so `RouterSpec`, `StatementClassifierSpec`, and `FlightSqlRouterSpec` exercise it without a live Flight connection.

## Package layout

| Package | Responsibility |
|---|---|
| `ai.starlake.quack` (`Main.scala`) | Wiring: load config, pick the state store, build the auth service and ACL validator, select the runtime backend, mount endpoints. |
| `ai.starlake.quack.edge` | The FlightSQL edge: `FlightEdgeServer`, `FlightProducerImpl`, `FlightSqlRouter`, sessions, the Quack HTTP adapter. |
| `ai.starlake.quack.edge.auth` | Authentication providers (database, JWT, OIDC, OAuth) and role extraction. |
| `ai.starlake.quack.edge.config` / `edge.catalog` | Auth/ACL/session config types and the DuckLake catalog resolver. |
| `ai.starlake.quack.route` | The routing core: `Router`, `RoleMatcher`, `StatementClassifier`. |
| `ai.starlake.quack.ondemand` | The control plane: `ManagerServer`, `PoolSupervisor`, the REST handlers, state stores, RBAC, manifest, runtime backends. |
| `ai.starlake.quack.ondemand.runtime` | The `QuackBackend` strategy and its local / Kubernetes implementations. |
| `ai.starlake.acl` | The SQL parser used by the ACL validator to extract table accesses. |
| `ai.starlake.quack.observability` | Micrometer metrics and the sink selection. |

## State and storage

`Main.scala` picks the control-plane store from `quack-on-demand.stateStorage` (default `postgres`): the normalized `qodstate_*` tables managed by Liquibase, or the legacy single-blob `file` store. See [State storage](/concepts/state-storage). Each managed tenant-db is a separate Postgres database holding its DuckLake catalog; see [DuckLake catalogs](/concepts/catalogs).

## Extending

The two seams a contributor is most likely to extend, the runtime backend and the auth providers, have their own guide: [Extending the manager](/contributing/extending).
