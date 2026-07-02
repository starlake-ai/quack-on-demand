# Resilience guide

## Single-replica mode (default)

With `replicaCount: 1` the manager uses a `Recreate` rollout strategy. Pod
restarts are sequential; there is a brief gap (~5 s preStop + probe time) during
which no manager is available. Quack node pods keep running and serving reads
cached by the edge; write traffic pauses until the new pod passes the readiness
probe.

This is the correct default for development and low-traffic production sites
that can tolerate a short planned-maintenance window.

## Multi-manager HA mode (opt-in)

Setting `replicaCount` to 2 or more enables active-active HA:

```bash
helm upgrade qod charts/quack-on-demand -n qod \
  --reuse-values \
  --set replicaCount=2 \
  --set sessionJwtSecret=$(openssl rand -hex 32)
```

`sessionJwtSecret` (or `existingSessionJwtSecret`) is required: all replicas must
sign and verify JWTs with the same key so a session minted on replica A is
accepted by replica B.

### What changes under HA

- Deployment strategy switches to `RollingUpdate` (`maxUnavailable: 0`,
  `maxSurge: 1`).
- `QOD_HA_ENABLED=true` is injected. The manager activates `HaCoordinator`
  (Postgres session advisory lock) and `PoolLocker` (per-pool advisory locks).
- One replica at a time holds the leader lock and runs singleton duties:
  pool reconcile, DuckLake init, bootstrap, revoked-jti purge.
- JWT revocations written by any replica are persisted to `qodstate_revoked_jti`
  and propagated via `LISTEN/NOTIFY` on the `qod_revocation` channel so every
  replica enforces them immediately.
- RBAC and topology changes propagate via `qod_rbac` / `qod_topology` channels;
  a periodic snapshot-refresh (every `topologyRefreshSec` seconds, env
  `QOD_TOPOLOGY_REFRESH_SEC`, default 30)
  acts as a fallback.
- A `PodDisruptionBudget` (`minAvailable: 1`) is created automatically.

### Failover behavior

When the leader pod is killed or evicted:

- Connections whose in-flight stream was on the lost pod fail once. Clients
  should retry; the surviving replica accepts the retry immediately.
- The survivor detects lock loss within `leaderRetrySec` (default 3 s, env
  `QOD_LEADER_RETRY_SEC`) and acquires the advisory lock.
- The new leader runs reconcile on its next tick and re-adopts any orphaned
  Quack node pods using the credentials stored in `qodstate_node` rows.
  Secret readback from Kubernetes is a boot-time repair path; it is not
  re-run on leader promotion.
- Long-running FlightSQL streams that survive the failover continue against the
  survivor if the client's Flight connection was routed there. Streams tied to
  the killed pod terminate with a transport error and must be restarted.

### Operational notes

- Keep `reconcileIntervalSec` at its default (30). If set to 0, a newly
  promoted follower will never run reconcile until it is restarted.
- After a failover the new leader adopts existing pods from `qodstate_node`
  rows. The Kubernetes Secret readback (`discoverExisting`) is a boot-time
  repair only; do not rely on it to rehydrate token state mid-run.
- `drainTimeoutSec` (env `QOD_DRAIN_TIMEOUT_SEC`, default 60) gives in-flight
  streams a chance to finish on a graceful shutdown. `terminationGracePeriodSeconds`
  is set to `drainTimeoutSec + 15`. Tune this for your longest expected query.
- The local backend (`QOD_BACKEND=local`) refuses to start with `QOD_HA_ENABLED`.
  HA requires `QOD_BACKEND=kubernetes`.

### Enabling HA on an existing single-replica deployment

Upgrade the manager image first at `replicaCount: 1` (the default `Recreate`
strategy means no pod overlap), then scale to 2 or more in a separate
`helm upgrade`. Bumping the image and `replicaCount` in a single upgrade
briefly overlaps an old binary (without HA advisory-lock guards) with the new
HA pod, which is the exact double-fire hazard the per-pool locks are designed
to prevent.

### FlightSQL load balancing and reconnect behavior

The FlightSQL Service must remain connection-level (L4) load balancing; nothing
may split a single client connection across replicas (no per-RPC L7 gRPC
balancing) because open transactions and pinned quack-node routing state live
for the lifetime of the connection.

If a client reconnects mid-transaction (for example after a failover), the
pinned quack node's open transaction is rolled back the same way as after a
single-manager restart (node-side session cleanup); the client must retry the
entire transactional flow from scratch.

### Spec reference

Full design rationale and edge-case analysis:
`docs/superpowers/specs/2026-07-02-manager-ha-zero-downtime-design.md`
