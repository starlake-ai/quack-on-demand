---
id: pools-cohorts
title: Pools and cohorts
---

A pool is a set of DuckDB Quack nodes bound to one database (tenant-db). It is the object clients connect to, and the unit the gateway routes statements across. This page covers creating, sizing, scaling, and stopping pools, the node role distribution that drives routing, and cohort-based node placement on Kubernetes.

Provision the tenant and database first (see "Tenants and databases"). For how pool access governs database access, see the [Access control model](/operating/rbac-model). REST calls authenticate with `X-API-Key` (a static `QOD_API_KEY` or an admin session token, as on the tenants page).

## Node roles and the role distribution

Every node in a pool has one of three roles. The router picks a node for each statement by the statement's kind:

| Role | Serves | Falls back to |
|---|---|---|
| `readonly` | SELECT and other read-class statements | `dual` |
| `writeonly` | INSERT / UPDATE / DELETE / DDL / transaction control (BEGIN, COMMIT, ROLLBACK) | `dual` |
| `dual` | Both reads and writes | none |

When a pool is created you declare how its nodes split across the three roles via `roleDistribution`. The three counts must sum to `size`. A pool of all-`dual` nodes is the simplest choice; splitting `readonly` from `writeonly` lets read and write traffic scale and fail independently.

Within the acceptable roles for a statement, the router picks the least-loaded node (in-flight count plus an EWMA of completed-statement latency).

## Create a pool

```bash
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/create \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"sales","pool":"bi","size":3,
       "roleDistribution":{"writeonly":1,"readonly":1,"dual":1}}'
```

| Field | Default | Meaning |
|---|---|---|
| `tenant` | required | Owning tenant. |
| `tenantDb` | required | The database this pool binds to (the `name` suffix used at database creation, e.g. `sales`). |
| `pool` | required | Pool name, unique within the database. |
| `size` | required | Total node count. Must equal the sum of `roleDistribution`. |
| `roleDistribution` | required | `{writeonly, readonly, dual}` node counts. |
| `idleTimeoutSec` | `-1` | Per-pool idle-timeout setting, persisted on the pool. `-1` means unset. It is recorded on `qodstate_pool` and surfaced back on reads; no automatic idle-shutdown loop acts on it today. |
| `maxConcurrentPerNode` | `0` | Per-node concurrency cap used for capacity-aware routing. `0` means unbounded. |
| `cohorts` | `[]` | Optional Kubernetes placement plan (see below). Empty schedules every node with no constraint. |
| `disabled` | `false` | When true the pool is spawned warm but the edge rejects fresh handshakes until it is enabled. |

The pool inherits its metastore (Postgres connection and data path) from the tenant-db; you do not pass storage config on `pool/create`.

## Inspect, scale, and stop

```bash
# List all pools with their live node tables
curl -sS -H "X-API-Key: $TOKEN" http://localhost:20900/api/pool/list

# Status of one pool (live per-node metrics: in-flight, served, latency)
curl -sS -H "X-API-Key: $TOKEN" \
  http://localhost:20900/api/pool/acme/sales/bi/status

# Scale to a new size and role split (force skips graceful drain when shrinking)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/scale \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"sales","pool":"bi","targetSize":6,
       "roleDistribution":{"writeonly":1,"readonly":3,"dual":2}}'

# Disable a pool without removing it (edge rejects fresh handshakes)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/setDisabled \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"sales","pool":"bi","disabled":true}'

# Stop and remove the pool (force=true skips the graceful drain)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/stop \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"sales","pool":"bi","force":false}'
```

A graceful stop (`force=false`) drains in-flight statements before terminating nodes. `force=true` terminates immediately. The same `force` flag applies when `scale` shrinks a pool.

## Node pod sizing (Kubernetes)

On the Kubernetes backend a pool carries optional `cpu` and `memory` quantities. Each is applied as both the request and the limit on the `quack` container of every node pod, so setting both yields Guaranteed QoS. The local backend ignores them.

```bash
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/setResources \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"sales","pool":"bi","cpu":"2","memory":"8Gi"}'
```

- Values take effect on the next node spawn (idle-timeout replacement, scale-up, manual restart). Restart the pool's nodes to apply immediately. An empty string clears a value.
- The API accepts any valid Kubernetes quantity. The admin UI exposes the same setting as sliders on the pool create form and the pool detail page, covering 0.5 to 128 CPU cores and 1 to 1024 Gi of memory; values outside the slider range set through the API are shown as raw text.
- Size the DuckDB engine below the pod: set `SET memory_limit = '...'` (about 80% of pod memory) in the database or pool `initSql` so the engine spills to disk before the kernel OOM-kills the pod.
- The Helm chart's `resources` value sizes the manager container, not node pods; node pods are sized per pool with this endpoint.

When requests-and-limits are not enough (sidecars, volumes, affinity), a pool can carry a full Pod-manifest template via `POST /api/pool/setPodTemplate` (API-only, superuser-only, gated behind `QOD_POD_TEMPLATE_ENABLED`, default off). The template must contain a container named `quack`; the manager overlays the pod name, its identity labels, and the quack container's env contract and resources.

## Cohorts and Kubernetes placement

By default the supervisor schedules every node in a pool with no placement constraint. On the Kubernetes backend you can instead split a pool into cohorts, each pinned to a class of nodes with a `nodeSelector` and tolerations. This is how you keep, for example, the write nodes on memory-optimized hardware and the read nodes on cheaper general-purpose nodes.

A cohort carries a `placement` (Kubernetes `nodeSelector` plus `tolerations`) and its own `distribution`. Supply the cohorts in `pool/create`:

```bash
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/create \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","tenantDb":"sales","pool":"bi","size":4,
       "roleDistribution":{"writeonly":1,"readonly":2,"dual":1},
       "cohorts":[
         {"placement":{"nodeSelector":{"workload":"write"},
                       "tolerations":[{"key":"dedicated","operator":"Equal",
                                       "value":"write","effect":"NoSchedule"}]},
          "distribution":{"writeonly":1,"readonly":0,"dual":1}},
         {"placement":{"nodeSelector":{"workload":"read"}},
          "distribution":{"writeonly":0,"readonly":2,"dual":0}}
       ]}'
```

Two validation rules apply when `cohorts` is non-empty:

1. The per-cohort `distribution` values must sum, role by role, to the pool's top-level `roleDistribution`.
2. The total node count across all cohorts must equal `size`.

Each toleration is `{key, operator, value, effect}`, matching the Kubernetes pod toleration fields.

Cohorts are ignored on the local (non-Kubernetes) backend, where all nodes are child processes of the manager and there is nothing to place them on. The admin UI hides the per-pool placement controls unless the runtime backend reports that placement is supported. For the Kubernetes backend itself, see [Kubernetes deployment](/operating/deploy-kubernetes).
