# Resilience

Honest assessment of what fails, what recovers, and what does not - written for operators
deciding whether to put this in front of paying users.

This document describes the **current** posture (what the code does today). Aspirational
items live in the [roadmap](docs/ROADMAP.md). Where a known gap maps to a tracked issue, the
issue is linked inline.

---

## TL;DR

| Aspect | Status | Notes |
|---|---|---|
| Single-manager cold restart | **Yes** | Tenant / tenant-db / pool / RBAC state restored from Postgres; nodes reconciled on boot |
| Quack node crash | **Yes** | Detected by `HealthProbe`; respawned (local) / left to kubelet (K8s) |
| In-transaction node death | **Yes** | Session pin invalidated; client sees error and retries from BEGIN |
| Postgres brief outage | **Partial** | No retry wrapper - first transient failure may bubble up to a 500 |
| Statement history across restarts | **No** | Ring buffer is in-memory only (in-flight 256 entries) |
| Graceful shutdown (SIGTERM) | **No** | No JVM-level handler; in-flight queries dropped on `kill` |
| Multi-manager active-active | **No** | Not supported ([#11](https://github.com/starlake-ai/quack-on-demand/issues/11) - v0.4) |
| FlightSQL session continuity across manager restart | **No** | All clients reconnect (Arrow Flight has no resumption) |

**Bottom line:** the manager is designed to be **safely restartable**, not highly available.
A single instance recovers cleanly from crashes; an SLA that forbids ~30 s of FlightSQL
unavailability during deploys / failures needs Tier 3 work below.

---

## What recovers automatically

### Manager process crash → cold start

When the JVM exits and a supervisor restarts it (systemd, Kubernetes, `run-jar.sh` rerun):

1. **State restored from Postgres** -
   [`PoolSupervisor.restore()`](src/main/scala/ai/starlake/quack/ondemand/PoolSupervisor.scala#L59)
   reads the normalized `qodstate_tenant` / `qodstate_tenant_db` / `qodstate_pool` /
   `qodstate_node` tables (Liquibase-managed) and re-hydrates the registry into
   in-memory `TrieMap`s. Users come from `qodstate_user`; the RBAC graph
   (`qodstate_role`, `qodstate_role_permission`, `qodstate_group`,
   `qodstate_user_role`, `qodstate_user_group`, `qodstate_group_role`,
   `qodstate_pool_permission`) is rebuilt into the per-session EffectiveSet
   on each connection. Equivalent path exists for the file-backed `StateStore`
   (legacy `slkstate_pool_state` JSONB blob) when `stateStorage = file`.
2. **Existing K8s pods adopted** -
   [`KubernetesQuackBackend.discoverExisting()`](src/main/scala/ai/starlake/quack/ondemand/runtime/KubernetesQuackBackend.scala#L187)
   selects pods by the manager's label (`managed-by=quack-on-demand`) and re-binds them to
   the in-memory registry, so a manager restart does *not* tear down running pods.
   **Local mode does NOT adopt** - `discoverExisting()` returns `List.empty`
   ([`LocalQuackBackend.scala:100`](src/main/scala/ai/starlake/quack/ondemand/runtime/LocalQuackBackend.scala#L100)) - so on a local-mode restart, the
   restored pool state references child processes that no longer exist; the reconcile pass
   below respawns them.
3. **Reconciliation** -
   [`PoolSupervisor.reconcile()`](src/main/scala/ai/starlake/quack/ondemand/PoolSupervisor.scala#L90)
   compares the restored desired state against what the runtime backend reports as alive,
   and respawns any nodes that should be present but aren't. Idempotent.
4. **Bootstrap tenant / tenant-db / pool / admin user / RBAC seed**
   ([`Main.scala`](src/main/scala/ai/starlake/quack/Main.scala)) re-runs but each
   step is idempotent: the named tenant / tenant-db / pool are skipped when they
   already exist in restored state, `qodstate_user` reseed is upsert-only, and the
   tenant's built-in `admin` role + `*.*.* ALL` permission are no-ops on re-entry.

**Cold boot time on a typical machine:** ~5 s JVM start + ~1 s Liquibase schema
diff against `qod` + ~1 s reconcile + ~3 s per respawned node. A 3-node pool is
back in service in roughly 15 s. First-ever boot adds another ~2 s per tenant-db
the supervisor has to `CREATE DATABASE` + Liquibase-init for DuckLake's
`__ducklake_*` tables.

### Quack node crash → respawn

The [`HealthProbe`](src/main/scala/ai/starlake/quack/edge/adapter/HealthProbe.scala) pings each
node's `/ping` endpoint every `healthCheckIntervalSec` (default 5 s) and updates the
`NodeLoadTracker`'s `healthy` flag. The router refuses to schedule new statements onto
nodes with `healthy=false`.

- **Local mode:** the supervisor detects dead PIDs by polling `ProcessHandle.isAlive` and
  respawns via `spawn-quack-node.sh`. New token + port; the in-memory registry is updated.
- **K8s mode:** the manager does not respawn directly - it trusts the kubelet's liveness
  probe + the Deployment's `restartPolicy: Always` to bring the pod back. The manager
  reconciles when the pod's `Ready` condition flips back to true.

### In-transaction node death

If a session is mid-`BEGIN` on a node and that node dies, the
[`FlightSqlRouter`](src/main/scala/ai/starlake/quack/edge/FlightSqlRouter.scala#L52)
detects the routing failure and calls
[`SessionRegistry.invalidatePin`](src/main/scala/ai/starlake/quack/edge/SessionRegistry.scala#L32),
clearing the pinned node and `txOpen` flag. The next statement on that connection routes
fresh; the client sees an error and must `BEGIN` again.

**There is no transparent retry** - quack-on-demand does not replay the in-flight
transaction. Operators relying on this should design their workload to handle
`pin-lost` / `no-node` errors via standard client retry logic. The `status` label in
`statements_total` records each occurrence so they're visible on a Grafana panel.

---

## What does not survive

### In-memory state lost on every restart

| State | Where it lives | Impact |
|---|---|---|
| **Statement history** | [`StatementHistoryStore`](src/main/scala/ai/starlake/quack/edge/StatementHistoryStore.scala#L23) - 256-entry ring buffer | The Admin UI's "Recent statements" panel resets. No post-mortem trail of what ran before the crash. |
| **Per-node EWMA latency / total served** | [`NodeLoadTracker`](src/main/scala/ai/starlake/quack/edge/adapter/NodeLoadTracker.scala) | The routing algorithm recomputes load from zero. New traffic distributes evenly until the EWMA converges (a few seconds at any real QPS). |
| **FlightSQL sessions + session pins** | [`SessionRegistry`](src/main/scala/ai/starlake/quack/edge/SessionRegistry.scala) | Every client must reconnect. Any open transaction is implicitly rolled back at the Quack node level. |
| **Admin session tokens** | [`SessionTokenStore`](src/main/scala/ai/starlake/quack/ondemand/api/SessionTokenStore.scala) | Admin UI users must log in again. The static `QOD_API_KEY` continues to work. |
| **Rolling per-node histogram (p50/p95/p99)** | Part of the load tracker | UI latency widgets reset to zero. |

All of these recover by re-population from live traffic; none cause incorrect behavior, only
gaps in the operator-visible signal.

### Operator-visible failure modes during restart

- **FlightSQL clients** - connections drop. Clients with auto-reconnect (ADBC, JDBC pool
  with retry) come back within seconds. Clients without retry see a hard error.
- **Admin REST** - 503 until the EmberServerBuilder finishes bind. Typically < 2 s.
- **`/metrics`** - same. Prometheus scrapers log one or two failed scrapes.

---

## Failure modes and current behavior

| Failure | Detection | Manager behavior | Impact | Tracked gap |
|---|---|---|---|---|
| Quack node JVM crash | `HealthProbe` → `/ping` 5 s tick + (local only) PID check | Local: respawn via `spawn-quack-node.sh`. K8s: kubelet restart, manager waits for `Ready`. | New traffic routes to other healthy nodes. Sessions pinned to the dead node get invalidated on next statement. | - |
| Manager JVM crash (OOM, panic) | Process supervisor (systemd / kubelet / `run-jar.sh` rerun) | Cold restart → restore → reconcile. | All FlightSQL sessions drop. ~15 s to fully reconcile. | Graceful shutdown ([#2](https://github.com/starlake-ai/quack-on-demand/issues/2)) |
| Postgres unreachable (network blip) | Hikari throws on connection acquire | First state-changing request gets a 500. No automatic retry. | Read-only requests served from in-memory state (incl. cached EffectiveSets pinned on live FlightSQL sessions) continue to work; writes, new tenant-db creation, and new-session handshakes (which re-resolve the EffectiveSet) all fail. | Need retry wrapper (no issue yet) |
| Postgres down for minutes | Same as above | Manager enters a degraded state where established FlightSQL sessions keep flowing but `createPool` / `createTenantDb` / `setRole` / RBAC CRUD all fail. New connections cannot rebuild the EffectiveSet so they bounce at handshake. | Existing FlightSQL traffic keeps flowing. | Same |
| Manager host loss (K8s node evict) | kubelet | New pod scheduled; cold restart sequence runs on a different host. | Same as JVM crash. K8s `terminationGracePeriodSeconds` should be set ≥ 30 s. | - |
| Network partition between manager and a node | `HealthProbe` flips `healthy=false` after one tick | Node marked unhealthy; router excludes it from `pick()`. | Pinned sessions get invalidated on next statement. | - |
| Network partition between manager and all nodes | All nodes flip unhealthy | Every routing decision returns `Unavailable("no node compatible")`. FlightSQL responses become 500s. | Total query outage until partition heals. The manager itself does not crash. | - |
| FlightSQL edge crash (`FlightProducerImpl` exception) | The wrapping `IO` returns `Left(throwable)` | [`Main.scala`](src/main/scala/ai/starlake/quack/Main.scala) logs the error and parks on `IO.never` - the JVM stays up but FlightSQL is dead. | Admin UI + `/metrics` continue working; FlightSQL is silently down. | No issue yet - should be loud |
| Disk full on the manager host | Logback `RollingFileAppender` (if configured) drops writes; JVM may eventually OOM-kill | Manager eventually crashes. | Same as manager JVM crash. | - |
| Disk full on a Quack node (parquet write fails) | Node returns 5xx from `/quack` | Adapter classifies as `transient`, routes elsewhere. Status appears as `transient` in statement history. | Reads continue. Writes fail until disk is cleared. | - |
| TLS cert expiry | First TLS handshake fails | Manager refuses new FlightSQL connections. | The auto-gen cert in `certs/` has a 10-year validity; only a concern for prod with externally-issued certs. | Rotate certs in K8s via cert-manager. |
| Two managers started against the same Postgres | Both restore the same state; both try to reconcile | Both attempt to spawn pods / processes with the same node IDs (K8s API returns 409 for the second create; local mode races on port allocation), and `DbAdmin.createDatabase` for any new tenant-db races between the two (one wins, the other sees `database "<tenant>_<db>" already exists` and aborts that bootstrap step). **Not safe today.** | Avoid. | Multi-manager HA ([#11](https://github.com/starlake-ai/quack-on-demand/issues/11)) |

---

## Known gaps (ranked by ROI)

### Tier 1 - make crash-restart boring

1. **Graceful shutdown** - JVM SIGTERM handler that drains FlightSQL connections, signals
   the supervisor to persist final state, and exits cleanly. Today a `kill` mid-statement
   leaves the client with a hard error and may briefly delay reconciliation on next boot.
   Tracked: [#2](https://github.com/starlake-ai/quack-on-demand/issues/2).
2. **K8s liveness + readiness probes** in the Helm chart ([#9](https://github.com/starlake-ai/quack-on-demand/issues/9)) - readiness gates traffic until
   `PoolSupervisor.restore()` + `reconcile()` finish, so a restarted manager doesn't
   briefly 503.
3. **Persist statement history to Postgres** - back the ring buffer with a small
   `qodstate_stmt_history` table. Operators get a post-mortem trail surviving crashes.
4. **Hikari transient-retry wrapper** - wrap every state-store write in a
   ≤ 3-attempt retry with backoff, so a 2-second Postgres blip doesn't bubble up as a 500.
   Reconcile is already idempotent so a retried write is safe.
5. **Loud edge-crash failure** - currently `Main.scala` logs the FlightSQL edge failure and
   parks on `IO.never` keeping the JVM alive. Better: exit non-zero so the supervisor
   restarts the whole process.

### Tier 2 - make node failures invisible

6. **Cold-boot node quarantine** - newly-restored nodes start with `healthy=false` for one
   `HealthProbe` tick (~5 s). Avoids routing traffic to a node that hasn't responded since
   restart.
7. **Local-mode pod adoption** - `LocalQuackBackend.discoverExisting()` currently returns
   `List.empty`, so every local-mode restart respawns nodes. Reading the PID file written
   at spawn time would let the manager adopt live children across restart.

### Tier 3 - true multi-manager HA

8. **Postgres advisory-lock leader election** - [#11](https://github.com/starlake-ai/quack-on-demand/issues/11). Two managers run; the leader holds an advisory lock and serves all writes; the
   standby takes over within seconds when the lock expires. The hard part is *not* the
   lock - it's that the standby has to take over FlightSQL session state, which today is
   in-memory only. Either accept session loss on failover (cheap) or persist session
   state (expensive).
9. **Edge horizontal scale** - multiple FlightSQL listeners behind a connection-aware LB
   (Envoy with gRPC stickiness, or similar). Combined with #8.

---

## Operational guidance for today

If you're putting this in production now (single-manager + Postgres):

- **Run under a process supervisor** that restarts the JVM on exit:
  - systemd unit with `Restart=always`
  - K8s `Deployment` with `restartPolicy: Always` (default)
  - Docker `restart: unless-stopped`
- **Pin a real K8s readiness probe** even before the Helm chart lands. A simple one:

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

  The `/health` endpoint returns OK once the supervisor restored and reconcile finished
  the first pass.
- **Back up Postgres** with point-in-time recovery enabled. Everything that survives a
  manager restart lives there.
- **Hold `terminationGracePeriodSeconds: 60`** so in-flight FlightSQL queries have a
  chance to finish before the JVM is killed (helps even without #2 above).
- **Monitor `statements_total{status!="ok"}` rate**. A spike in `transient`, `no_node`, or
  `pin_lost` is the canary for upstream node trouble; a spike in `permanent` usually
  means client-side SQL errors.
- **Set up an external `/health` watcher** independent of the JVM (e.g. Prometheus
  `probe_success`). Routine reachability is the first thing to know.
- **Plan FlightSQL clients with retry logic.** ADBC has it; JDBC pools usually do; raw
  gRPC code needs explicit handling. A manager restart is the most common interruption.

---

## What to read for design context

- [Architecture summary in CLAUDE.md](CLAUDE.md#architecture---the-bits-that-span-multiple-files) -
  the three sockets, state storage, RBAC validator selection.
- [Observability surface](observability/README.md) - Prometheus / Cloud Monitoring sinks
  and the Grafana dashboard you should be watching during failure investigations.
- [Roadmap](docs/ROADMAP.md) - v0.2 / v0.3 / v0.4 / v1.x items that progressively close the
  gaps above.
