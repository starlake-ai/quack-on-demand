---
id: resilience
title: Resilience and recovery
---

This page describes what the code does today. Aspirational items are noted as gaps. Where a known gap maps to a tracked issue, the issue number is included.

## Topology

quack-on-demand runs as a single-instance manager process. It is designed to be safely restartable, not active-active. Running two managers against the same Postgres control-plane database is not safe today: both would attempt to reconcile the same node registry and race on database creation (tracked: [#11](https://github.com/starlake-ai/quack-on-demand/issues/11) for v0.4).

Worker pools scale horizontally. A pool can contain any number of Quack nodes; the router distributes statements across all healthy nodes in the pool. Adding nodes increases query throughput without touching the manager.

## Cold start and reconciliation

When the manager JVM exits and a supervisor restarts it (systemd, Kubernetes, or a manual rerun of `qod start`), the following sequence runs:

1. **State restored from Postgres.** `PoolSupervisor.restore()` reads the normalized `qodstate_tenant`, `qodstate_tenant_db`, `qodstate_pool`, and `qodstate_node` tables (managed by Liquibase) and re-hydrates the registry into in-memory `TrieMap`s. The RBAC graph (`qodstate_role`, `qodstate_role_permission`, `qodstate_group`, `qodstate_user_role`, `qodstate_user_group`, `qodstate_group_role`, `qodstate_pool_permission`) is rebuilt into the per-session `EffectiveSet` on each connection.

2. **Existing Kubernetes pods adopted.** `KubernetesQuackBackend.discoverExisting()` selects pods by the manager's label (`managed-by=quack-on-demand`) and re-binds them to the in-memory registry. A manager restart does not tear down running pods. **Local mode does not adopt survivors** (`LocalQuackBackend.discoverExisting()` returns `List.empty`), so on a local-mode restart the restored pool state references processes that no longer exist; the reconcile pass below respawns them.

3. **Reconciliation.** `PoolSupervisor.reconcile()` compares the restored desired state against what the runtime backend reports as alive (PID + socket check for local, pod `Ready` condition for Kubernetes) and respawns any nodes that should be present but are not. The method is idempotent. Drained pools (zero distribution) are left alone. Beyond this boot pass, `reconcile()` then runs on a background fiber every `reconcileIntervalSec` (default 30 s, env `QOD_RECONCILE_INTERVAL_SEC`; set 0 to disable the loop and keep the boot-only behavior), so a node that dies while the manager is up is respawned on the next tick rather than staying dead until the next restart.

4. **Bootstrap re-seed.** `Main.scala` re-runs the bootstrap sequence on every start. Each step is idempotent: the named tenant/tenant-db/pool are skipped when they already exist, the admin user upsert re-hashes the password, and the built-in `admin` role with its wildcard permission is a no-op on re-entry.

Typical cold-boot time on a development machine: roughly 5 s JVM start plus 1 s Liquibase schema diff plus 1 s reconcile plus about 3 s per respawned node. A 3-node pool is back in service in approximately 15 s. A first-ever boot adds another 1-2 s per tenant-db for `CREATE DATABASE` and DuckLake metadata table initialization.

## Health checks

`HealthProbe` runs a background fiber that pings each node's `/ping` endpoint on a fixed interval. The default interval is 5 seconds (`healthCheckIntervalSec = 5` in `application.conf`, overridable via `QOD_HEALTH_CHECK_INTERVAL_SEC`). Each tick updates the `NodeLoadTracker`'s `healthy` flag for the node. The router's `pick()` method excludes nodes where `healthy = false`.

When the ping function throws, the probe catches the exception and marks the node unhealthy rather than terminating the loop.

The first successful probe per node also runs `CREATE SCHEMA IF NOT EXISTS <db>.<schema>` so the pool's default schema exists before `FlightSqlRouter.wrapWithDefaultSchema` ever prepends a `USE` statement to client queries. Subsequent probes revert to plain `SELECT 1`.

**Cold-boot quarantine gap.** A newly spawned or restored node starts with `healthy = true` (the default in `NodeLoad.empty`). The node can therefore receive traffic before its first probe confirms it is reachable. The ~5 s window between spawn and the first successful probe is a known gap: a node that is slow to start can receive statements it cannot yet handle and return transient errors. A future improvement would initialize new nodes with `healthy = false` and flip to `true` only after a confirmed probe.

## In-transaction node death

FlightSQL sessions that have issued `BEGIN` are pinned to a specific node for the duration of the transaction. If the pinned node disappears or returns a transient failure before `COMMIT` or `ROLLBACK`:

- `FlightSqlRouter` detects either a `RoutingDecision.PinnedNodeGone` result (the node is no longer in the snapshot) or a `QuackResponse.Failed(QuackError.Transient, ...)` response while `txOpen = true`.
- In both cases the router calls `SessionRegistry.invalidatePin`, which clears the pinned node and resets `txOpen = false`.
- The current statement returns an error to the client (`"pinned node disappeared; transaction lost"` or `"transient failure inside transaction: <detail>"`).
- The client must reconnect and replay the transaction from `BEGIN`.

There is no transparent replay. quack-on-demand does not buffer or re-execute the in-flight transaction. Clients should handle `pin-lost` and `no-node` error strings via standard retry logic. Each occurrence is recorded in `statements_total` with the appropriate status label so it is visible on a metrics panel.

**Outside a transaction.** A transient failure on a statement that is not inside a `BEGIN` block triggers a single automatic retry on a different node (`retryOnce` in `FlightSqlRouter`). The excluded node is filtered from the snapshot for that retry pick. If the retry also fails, the error is returned to the client.

## What survives a restart and what does not

**Durable (survives restart):**
- All control-plane state: tenants, tenant-databases, pools, nodes, users, roles, groups, permissions. These live in Postgres `qodstate_*` tables and are restored by `PoolSupervisor.restore()` on every boot.
- Pool node topology. Kubernetes pods are adopted; local processes are respawned to match the stored desired state.

**Lost on every restart:**

| State | Location | Impact |
|---|---|---|
| Statement history | `StatementHistoryStore` - 256-entry in-memory ring buffer | The admin UI "Recent statements" panel resets. No post-mortem trail from before the crash. |
| Per-node EWMA latency and total served | `NodeLoadTracker` | Routing load data resets to zero. Traffic distributes evenly until the EWMA converges over the next few seconds of live traffic. |
| Per-node latency histogram (p50/p95/p99) | `NodeLoadTracker` latency ring (256-sample window) | UI latency widgets reset to zero. |
| FlightSQL sessions and session pins | `SessionRegistry` | Every client must reconnect. Any open transaction is implicitly rolled back at the Quack node level. |
| Admin UI session tokens | `SessionTokenStore` | Admin UI users must log in again. The static `QOD_API_KEY` continues to work. |

All of these recover through re-population from live traffic. None cause incorrect behavior; they only create gaps in operator-visible signal during and immediately after a restart.

## Failure and recovery matrix

| Failure | Detection | Manager behavior | Impact | Tracked gap |
|---|---|---|---|---|
| Quack node JVM crash | `HealthProbe` `/ping` tick (5 s default) plus PID check (local only) | Local: respawn via `spawn-quack-node.sh`. Kubernetes: kubelet restart, manager waits for pod `Ready`. | New traffic routes to other healthy nodes. Sessions pinned to the dead node are invalidated on next statement. | - |
| Manager JVM crash (OOM, panic) | Process supervisor (systemd, kubelet, manual rerun) | Cold restart: restore from Postgres, reconcile. | All FlightSQL sessions drop. Approximately 15 s to fully reconcile a 3-node pool. | Graceful shutdown ([#2](https://github.com/starlake-ai/quack-on-demand/issues/2)) |
| Postgres brief outage | Hikari throws on connection acquire | First state-changing request gets a 500. No automatic retry wrapper. | Read-only requests served from in-memory state (including cached `EffectiveSet`s on live FlightSQL sessions) continue to work. Writes, new tenant-db creation, and new-session handshakes all fail. | Need retry wrapper (no issue yet) |
| Postgres down for minutes | Same as above | Manager enters degraded state: established FlightSQL sessions keep flowing but `createPool`, `createTenantDb`, and RBAC CRUD all fail. New connections cannot rebuild the `EffectiveSet` and bounce at handshake. | Existing FlightSQL traffic continues. | Same |
| Manager host loss (Kubernetes node evict) | kubelet | New pod scheduled; cold restart sequence runs on a different host. | Same as JVM crash. Set `terminationGracePeriodSeconds` to at least 30 s. | - |
| Network partition between manager and a node | `HealthProbe` flips `healthy = false` after one tick | Node excluded from `Router.pick()`. | Sessions pinned to that node are invalidated on next statement. Outside-transaction statements retry once on a different node. | - |
| Network partition between manager and all nodes | All nodes flip `healthy = false` | Every routing decision returns `Unavailable("no node compatible")`. FlightSQL responses become errors. | Total query outage until partition heals. The manager process itself does not crash. | - |
| FlightSQL edge crash (`FlightProducerImpl` exception) | The wrapping `IO` returns `Left(throwable)` | `Main.scala` logs the error and parks on `IO.never`. The JVM stays up but FlightSQL is dead. | Admin UI and `/metrics` continue working. FlightSQL is silently down. | Should exit non-zero so the supervisor restarts (no issue yet) |
| Disk full on manager host | Logback `RollingFileAppender` drops writes; JVM may OOM | Manager eventually crashes. | Same as manager JVM crash. | - |
| Disk full on a Quack node (Parquet write fails) | Node returns 5xx from `/quack`; adapter classifies as `transient` | Router tries a different node (retry-once outside tx; pin invalidation inside tx). | Reads continue. Writes fail until disk is cleared. | - |
| TLS cert expiry | First TLS handshake fails | Manager refuses new FlightSQL connections. | The auto-generated cert in `certs/` has a 10-year validity. Only a concern for production deployments using externally issued certificates. | Rotate via cert-manager in Kubernetes. |
| Two managers against the same Postgres | Both restore the same state and both try to reconcile | Both attempt to spawn pods with the same node IDs (Kubernetes API returns 409 for the second create; local mode races on port allocation). `DbAdmin.createDatabase` for new tenant-dbs races: one wins, the other sees "database already exists". **Not safe today.** | Avoid. | Multi-manager HA ([#11](https://github.com/starlake-ai/quack-on-demand/issues/11)) |

## Operational guidance

If you are running this in production today (single-manager plus Postgres):

- **Run under a process supervisor** that restarts the JVM on exit: systemd with `Restart=always`, a Kubernetes `Deployment` with `restartPolicy: Always` (the default), or Docker with `restart: unless-stopped`.

- **Add Kubernetes readiness and liveness probes** before exposing the manager to traffic:

  ```yaml
  readinessProbe:
    httpGet: { path: /health, port: 20900 }
    initialDelaySeconds: 5
    periodSeconds: 5
    failureThreshold: 3
  livenessProbe:
    httpGet: { path: /health, port: 20900 }
    initialDelaySeconds: 30
    periodSeconds: 10
    failureThreshold: 5
  ```

  The `/health` endpoint returns OK after `PoolSupervisor.restore()` and `reconcile()` complete their first pass.

- **Back up Postgres with point-in-time recovery.** Everything that survives a manager restart lives there.

- **Set `terminationGracePeriodSeconds: 60`** so in-flight FlightSQL queries have time to complete before the JVM is killed (useful even without a graceful shutdown handler).

- **Monitor `statements_total{status!="ok"}` rate.** A spike in `transient`, `no_node`, or `pin_lost` is the leading indicator for node trouble. A spike in `permanent` typically means client-side SQL errors.

- **Set up an external `/health` watcher** independent of the JVM (for example, a Prometheus `probe_success` check). Routine reachability is the first thing to know when investigating an outage.

- **Design FlightSQL clients with retry logic.** ADBC includes it; JDBC pools usually do; raw gRPC code needs explicit handling. A manager restart is the most common interruption clients will encounter.
