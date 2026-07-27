---
id: routing
title: Routing and statement classification
---

Every statement that arrives on the FlightSQL edge is classified, authorized, and then routed to one Quack node in the target pool. This page covers the classification and routing half (the authorization half is the [Access control model](/operating/rbac-model)). It sits in the request flow described on the [Architecture](/concepts/architecture) page, between the ACL gate and the node's `/quack` endpoint.

## Statement classification

The router classifies a statement with a cheap, keyword-based pass, not the full SQL parser. It strips comments, takes the first non-blank token (skipping a leading `(`), uppercases it, and matches it against six keyword buckets; anything unmatched is `Other`:

| Kind | Default first-token keywords |
|---|---|
| `Select` | `SELECT`, `WITH`, `VALUES`, `SHOW`, `DESCRIBE`, `EXPLAIN`, `FROM` |
| `Dml` | `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `UPSERT`, `REPLACE`, `COPY` |
| `Ddl` | `CREATE`, `DROP`, `ALTER`, `TRUNCATE`, `ATTACH`, `DETACH`, `COMMENT`, `GRANT`, `REVOKE` |
| `Begin` | `BEGIN`, `START` |
| `Commit` | `COMMIT`, `END` |
| `Rollback` | `ROLLBACK`, `ABORT` |
| `Other` | anything else (routed like a read) |

This is deliberately separate from the authorization parser: routing wants a fast three-way read/write/DDL answer, while the ACL layer runs its own full parse to extract per-table accesses. The `FROM` keyword covers DuckDB's FROM-first shorthand; `EXPLAIN` is treated as read-side by convention.

The keyword lists are operator-tunable under `quack-on-demand.statementClassifier.*` (the `QOD_CLASSIFIER_*` env vars). A configured list **replaces** the built-in default rather than extending it, so to add one keyword you copy the default list and append. An empty or whitespace-only value collapses that bucket to "never matches", which fails closed (the statement falls to `Other` and routes like a read) rather than fails open. See the [Configuration reference](/reference/configuration).

## Node roles and role matching

Each node has a role: `readonly`, `writeonly`, or `dual`. The classified kind maps to an ordered list of acceptable roles (most-preferred first), and the router restricts that list to the roles actually present in the pool:

| Statement kind | Preferred roles |
|---|---|
| `Select`, `Other` | `readonly`, then `dual` |
| `Dml`, `Ddl`, `Begin`, `Commit`, `Rollback` | `writeonly`, then `dual` |

If no node carries an acceptable role the statement is rejected as unavailable. How you split a pool across these roles is covered on the [Pools and cohorts](/operating/pools-cohorts) page.

## Least-loaded selection

With the acceptable roles known, `Router.pick` selects a node:

1. Keep only **routable** nodes (healthy, not draining).
2. Keep only nodes whose role is acceptable for this statement kind.
3. Drop nodes already at capacity: a node with `maxConcurrentPerNode > 0` is excluded once its in-flight count reaches that cap (`0` means unbounded).
4. From the survivors, pick the node with the smallest `(inFlight, ewmaMs)` tuple: fewest in-flight statements first, then the lowest EWMA of completed-statement latency.

The in-flight count and the latency EWMA are maintained per node by the load tracker (the same numbers surfaced on the Nodes screen and in the `node_in_flight` / `node_ewma_latency_seconds` metrics). If every compatible node is at capacity, the statement is rejected with "all compatible nodes at capacity" rather than being queued.

A statement that is pinned to a node by an open transaction short-circuits all of the above; see [Sessions and transactions](/concepts/sessions-transactions).

## Cache-aware placement

Least-loaded routing spreads statements evenly but ignores which node already has a table's files warm in cache. On object-store pools that is a real cost: a cold read pays object-store latency and egress. Cache-aware placement keeps least-loaded as the base story and adds a locality qualifier on top of it, sending a statement to a node that has recently served its tables when doing so does not overload that node.

It applies to a statement only when all of these hold:

1. `routing.cacheAware` is true (the default; env `QOD_ROUTING_CACHE_AWARE`).
2. The pool's DuckLake `dataPath` is an object store (`s3://`, `gs://`, `az://`, and the like). Local-filesystem and `file://` pools keep pure least-loaded routing, since their cold reads ride the OS page cache.
3. The pool has more than one routable node (a single-node pool, including `--demo`, has nothing to choose between).

Everything else, including every statement whose tables the parser cannot extract, takes the least-loaded path unchanged. Placement is a routing hint, never a gate: it never rejects or fails a statement.

### Table homes and write epochs

The manager keeps a per-pool **placement directory**: a map from each table to up to three **home** nodes, most-recently-used first. Reads stick to a warm home; writes make the other homes stale.

Each table carries a write **epoch**. A home that last served or produced the table at the current epoch is **fresh**; a home left behind by a later write is **stale**. Because the manager routes every write, it bumps the table's epoch itself when it dispatches one, with no per-query catalog lookup. The node that serves the write becomes the fresh home (it produced the new files); the table's other listed homes stay in the set but go stale, since they still hold every Parquet file the write did not touch. DuckLake files are immutable, so a stale home is still warm for most of the table and stays preferred over a cold node.

For each candidate node the router scores the statement's tables: **2** if the node is a fresh home, **1** if it is a stale home, **0** otherwise. It picks the highest-scoring node, breaking ties by the same `(inFlight, ewmaMs)` least-loaded order as before.

### The load cap and overflow

A hot table must not funnel all its traffic onto one home. Each candidate is held under a per-node load cap: its in-flight count may not exceed `c * max(1, average in-flight of the other routable nodes)`, where `c` is `routing.loadCapFactor` (default `2.0`, env `QOD_ROUTING_LOAD_CAP_FACTOR`). The candidate is excluded from its own average, so a busy home cannot inflate its own cap.

When every home of a statement's tables is over the cap, the router falls back to the least-loaded node under the cap and **adds** it as a new home for those tables rather than moving an existing one. If a table already has three homes, its least-recently-used home is dropped to make room. Overflow therefore turns a hot table into a genuinely replicated one, and a load burst cannot ping-pong a table's placement, since the original homes stay listed.

Precedence is unchanged from least-loaded routing: an open transaction's pin wins absolutely, then the Prepare-to-Execute soft pin, then the cache-aware scorer, then plain least-loaded. A pinned statement still updates the directory, so the node it lands on is learned regardless of why it was chosen.

### State, HA, and the revert switch

The directory is per-manager and in-memory only. It is lost on restart and rebuilt from one pass of traffic; there is no recovery path and none is needed. Where more than one manager runs, each keeps its own independent directory with no coordination, by design: the worst case is a table warm on one extra node of a small pool, which is benign over immutable files, and merging cross-manager hints would make each local estimate less trustworthy, not more.

Setting `QOD_ROUTING_CACHE_AWARE=false` instantly reverts routing **decisions** to pure least-loaded everywhere, bit-identical to the pre-feature behavior. The locality metrics and their memoized statement parse keep running regardless, so the [routing metrics](/reference/metrics) stay populated with the flag off.

## Default-schema qualification

Each statement runs in a fresh DuckDB session on the chosen node, so an unqualified `SELECT * FROM customer` would not find its catalog. Before sending, the router prepends `USE <dbName>.<schemaName>;` derived from the pool's metastore, so unqualified names and two-part `"schema"."table"` identifiers resolve to what the node actually exposes. The schema is pre-created once per node by the health probe (`CREATE SCHEMA IF NOT EXISTS`), so the `USE` always resolves by the time client traffic flows.

The prefix is skipped when the statement itself starts with `USE`, `SET`, `BEGIN`, `COMMIT`, `ROLLBACK`, `ATTACH`, or `DETACH`, so an operator can still escape the default and drive the session explicitly.

## Retry-once on transient failure

A node response is either OK, a transient failure, or a permanent failure:

- **OK** streams Arrow batches back to the client and updates the node's load counters.
- **Transient failure, no open transaction:** the router retries the statement exactly once on a *different* node (the failed node is excluded from the second pick). A retried `BEGIN` pins the session to the fallback node so its later `COMMIT` lands there too. If the retry also fails, the error is returned.
- **Transient failure inside an open transaction:** there is no cross-node retry (a half-applied transaction cannot move nodes). The pin is invalidated and the statement fails with "transient failure inside transaction".
- **Permanent failure:** returned immediately, no retry.

Each outcome is recorded in the statement history with a status (`ok`, `denied`, `no-pool`, `no-node`, `pin-lost`, `transient`, `permanent`) visible on the tenant detail screen and folded into the `statements_total` metric by status.
