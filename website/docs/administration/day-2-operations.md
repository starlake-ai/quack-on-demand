---
id: day-2-operations
title: Run the platform
---

The Nodes board is the at-a-glance operational view for a running deployment. From it you can spot trouble, scale compute up or down, drain a pool before maintenance, and drill into recent statements. This page walks through each of those tasks as step-by-step playbooks with the equivalent REST call for scripting and automation.

## Watch the Nodes board

**Goal:** Confirm the fleet is healthy and identify hot pools, draining nodes, or idle nodes at a glance.

**Prerequisites:** Signed in as a superuser or tenant admin. At least one pool is running.

**Steps (UI):**

1. Click **Nodes** in the top navigation bar. The board loads automatically on sign-in.
2. The table lists every Quack node across all tenants, grouped by tenant and pool. Each row shows:
   - **In-flight** - statements currently executing on that node.
   - **Total served** - lifetime count since the manager last started.
   - **Avg latency** - exponentially weighted moving average in milliseconds.
   - **Role** - `ReadOnly`, `WriteOnly`, or `Dual`.
   - **Healthy / draining** flags.
3. To spot a **hot pool**: look for nodes with a consistently high in-flight count or rising average latency. Use [Scale a pool](#scale-a-pool) to add nodes.
4. To spot a **draining node**: the row shows `draining: true`. It is excluded from new routing picks and will disappear once its stop completes.
5. To spot an **idle node**: in-flight is 0 and total served is low relative to siblings. If the entire pool is idle you may want to scale it down to save resources.

![Nodes overview](/img/ui/nodes.png)

**REST equivalent:**

```bash
# Live node table (used by the UI)
curl -sS -H "X-API-Key: $TOKEN" http://localhost:20900/api/pool/list \
  | python3 -c "import sys,json; d=json.load(sys.stdin); \
    [print(f'{n[\"nodeId\"]:28s} role={n[\"role\"]:9s} healthy={n[\"healthy\"]} \
served={n[\"totalServed\"]:5d} p50={n[\"p50Ms\"]:4.0f} p95={n[\"p95Ms\"]:4.0f} p99={n[\"p99Ms\"]:4.0f}') \
     for p in d['pools'] for n in p['nodes']]"
```

Per-node fields surfaced via `/api/pool/list`:
- `inFlight` - currently executing statements
- `totalServed` - lifetime counter since manager start
- `avgDurationMs` - EWMA latency
- `p50Ms`/`p95Ms`/`p99Ms` - rolling 256-sample window
- `healthy` / `draining` - tracker flags

**Verify:** Run a query through the FlightSQL edge and reload the Nodes board. The in-flight counter briefly increments during execution, and total-served increments by one after it completes.

**Related:** [Observability](/operating/observability), [Metrics reference](/reference/metrics).

---

## Scale a pool

**Goal:** Increase or decrease the node count of a pool without deleting and recreating it.

**Prerequisites:** Signed in as a superuser or tenant admin. The target pool exists and has at least one running node.

**Steps (UI):**

1. Click **Tenants** in the top navigation bar and select the target tenant.
2. On the **Pools** tab, click the pool name to open its detail page.
3. Click **Scale**.
4. Set the new **Target size** (total node count) and adjust the **Role distribution** sliders (WriteOnly, ReadOnly, Dual). The distribution must sum to the target size.
5. Click **Apply**. The manager computes the diff: surplus nodes are stopped, and deficit nodes are spawned.

![Pool detail](/img/ui/pool-detail.png)

**REST equivalent:**

```bash
# Scale up
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/scale \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","pool":"bi","targetSize":6,
       "roleDistribution":{"writeonly":1,"readonly":2,"dual":3}}'
```

**Verify:** Return to the Nodes board. The new node count appears as rows grouped under the pool. Newly spawned nodes may show `healthy: false` for up to 5 seconds while the health probe runs its first check.

**Related:** [Pools and cohorts](/operating/pools-cohorts).

---

## Drain vs force-stop

**Goal:** Bring a pool to zero nodes either gracefully (drain) or immediately (force), without deleting the pool registration.

**Prerequisites:** Signed in as a superuser or tenant admin. The target pool is running.

**Steps (UI):**

1. Click **Tenants**, select the tenant, and open the **Pools** tab.
2. Two stop actions appear per pool:
   - **Drain** - graceful stop. Each node is marked as draining in the router so no new statements are routed to it, then the node process is stopped. Use this during planned maintenance when you want to stop routing new queries to the pool before it shuts down. Drain does not wait for in-flight queries to finish, so statements already executing on a node can still be interrupted.
   - **Force** - immediate stop. Nodes are stopped without a draining period. Any statements in flight on those nodes are failed and returned to the client as errors. Use this when a pool is wedged and drain is not making progress.
3. After either action the pool row stays in the registry with zero nodes and a zero distribution. It is not deleted. Reconcile will not respawn nodes because the persisted distribution is zero.
4. To bring the pool back: use **Scale** and set a non-zero target size.

**REST equivalent:**

```bash
# Stop a pool: scales it down to 0 nodes but KEEPS the pool (force=true skips graceful drain)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/stop \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","pool":"bi","force":true}'
```

Pass `"force":false` to drain instead of force-stopping.

**Verify:** On the Nodes board, the pool's rows disappear. The pool itself still appears in the Tenants -> Pools tab with zero nodes, confirming it is registered but stopped.

**Related:** [Resilience and recovery](/operating/resilience) (graceful drain detail, reconcile behavior), [Pools and cohorts](/operating/pools-cohorts).

---

## Read statement history

**Goal:** Inspect recent SQL statements routed through the fleet, newest first, to audit activity or diagnose a slow query.

**Prerequisites:** Signed in as a superuser or tenant admin. At least one statement has been executed since the manager started. Note: the history is an in-memory ring buffer (256 entries) and resets on manager restart.

**Steps (UI):**

1. Navigate to a tenant detail page (click **Tenants**, then select the tenant).
2. The page shows a **Recent statements** panel below the pool list. Statements appear newest-first with the SQL text, the node that handled it, the duration, and the status.
3. The same panel appears on the pool detail page, scoped to statements routed to that pool.

**REST equivalent:**

```bash
# Recent statement history (newest first)
curl -sS -H "X-API-Key: $TOKEN" 'http://localhost:20900/api/node/statements?limit=20' \
  | python3 -m json.tool
```

**Verify:** Run a known query through the FlightSQL edge. Refresh the Recent statements panel. The query appears at the top of the list.

**Related:** [Observability](/operating/observability).

---

## Common failure modes

| Symptom | Likely cause | Where to look |
|---|---|---|
| `/api/*` returns 401 | `QOD_API_KEY` is set but the request header is missing or wrong | Pass `X-API-Key: <key>` or log in via `/api/auth/login`; check env var on manager startup |
| `no node with role READONLY or DUAL` | All nodes flipped unhealthy (port unreachable) | Run `pgrep -fl spawn-quack-node`; if 0 processes, call `pool/stop` then `pool/scale` to respawn |
| `access denied: missing RO grant on ...` | ACL is enabled and the user has no matching role-permission grant | Add the grant via the role-permission API or the Users screen; or set `QOD_ACL_ENABLED=false` for dev |
| `session expired; please reconnect` | Bearer token is not recognized (manager restarted, clearing the in-process denylist and session store) | Re-login or pass Basic credentials; the static `QOD_API_KEY` continues to work after a restart |
| `Could not connect to server` for `http://127.0.0.1:21NNN/quack` | Quack child process died after the manager restarted (local mode does not adopt survivors) | Wait for the next reconcile tick (default 30 s) to respawn; or call `pool/stop` then `pool/scale` immediately |
| Manager hangs at startup after `BaseAllocator` log line, JVM pegged at 100% CPU | `INSTALL quack` is blocked by a corporate proxy; DuckDB silently retries fetching the extension | Pass `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` to the manager process; see the README "Behind a corporate proxy" section |

For the full failure and recovery matrix (Postgres outage, TLS expiry, disk full, multi-manager race) see [Resilience and recovery](/operating/resilience).

**Related:** [Resilience and recovery](/operating/resilience), [Authentication](/operating/authentication).
