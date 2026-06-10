---
id: sessions-transactions
title: Sessions and transactions
---

A FlightSQL connection maps to a session on the edge that tracks which Quack node, if any, the connection's open transaction is bound to. This page explains the session model, how transactions pin to a node, when a pin is invalidated, and how prepared statements behave. It builds on [Routing and statement classification](/concepts/routing).

## The session

A session is opened lazily on the connection's first statement and holds:

- `connectionId` and `user`
- `poolKey` - the `(tenant, tenantDb, pool)` the connection handshook onto
- `pinnedNodeId` - the node an open transaction is bound to, or none
- `txOpen` - whether a transaction is currently open

Sessions are keyed by connection and dropped when the connection closes. The number of live sessions and how many are mid-transaction are surfaced as metrics (`flightsql_sessions_active`, and the in-transaction count).

## Transaction pinning

Routing normally picks the least-loaded node per statement. That is wrong for a transaction: a `BEGIN` on node A followed by a load-routed `COMMIT` could land on node B, where the transaction does not exist. To prevent that, the session pins:

- A `Begin` statement sets `pinnedNodeId` to the node it executed on and marks `txOpen = true`.
- While `txOpen` is true, every subsequent statement short-circuits the load-based selection and routes to the pinned node.
- A `Commit` or `Rollback` clears the pin and sets `txOpen = false`, returning the connection to per-statement load routing.

The router only honors a pin while a transaction is open, so a stale `pinnedNodeId` can never misroute a non-transactional statement.

## Pin invalidation

A pin is dropped (pin and `txOpen` both cleared) when the bound node can no longer serve the transaction:

- **Pinned node disappeared.** If the pinned node is gone from the pool snapshot, the statement fails with "pinned node disappeared; transaction lost" and the pin is invalidated. The transaction cannot be continued; the client must start over.
- **Transient failure mid-transaction.** As covered on the routing page, a transient node failure inside an open transaction does not retry on another node (a partially-applied transaction cannot move). The pin is invalidated and the statement fails.
- **Pool vanished.** If the target pool is no longer found while a transaction was open, the pin is invalidated.

In each case the failure is explicit: there is no silent re-execution of a transaction's statements on a different node.

## Retry and re-pin

The one case where a transactional statement moves nodes is a `BEGIN` that hit a transient failure *before* the transaction was established. Because no work has been committed yet, the router retries the `BEGIN` once on a different node and pins the session to that fallback node, so the rest of the transaction (and its `COMMIT`) follows it. This is the only interaction between retry-once and pinning.

## Prepared statements

The edge supports prepared statements with a behavior shaped by Arrow JDBC and DBeaver, which need the result schema known at prepare time to dispatch a handle as a query rather than an update:

- On `createPreparedStatement` the edge **pre-executes** the SQL through the normal route-and-authorize path, caches the resulting stream by a generated handle, and returns the result's Arrow schema in the prepare response.
- On the following `getStream` the edge **replays the cached stream once** and the caller closes it. The handle is effectively single-use: the result is produced at prepare time, not re-run per execution.
- `closePreparedStatement` is a safety net that frees a handle the client opened but never executed.
- Parameter bindings are not supported in this version; a prepared statement is a fixed SQL string.

Because a prepared statement executes at prepare time, it passes through the same classification, ACL gate, routing, and (if it is a `BEGIN`) pinning as a directly executed statement.
