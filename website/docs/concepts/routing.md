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
