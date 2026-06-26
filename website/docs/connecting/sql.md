---
id: sql
title: Supported SQL
---

Each Quack node is a DuckDB engine over a DuckLake catalog, so the dialect a client sees is DuckDB SQL. What you can run is whatever DuckDB supports, subject to the routing and authorization the gateway applies. This page covers the behaviors that differ from talking to a single DuckDB directly.

## Dialect

Statements are executed by DuckDB on the chosen node, so DuckDB syntax, functions, and types apply, including its `FROM`-first shorthand (`FROM t SELECT ...`). Beyond the default-schema prefix described below, the gateway leaves your SQL untouched - unless [column- or row-level security](/operating/rbac-model) is enabled, in which case a matching `SELECT` is rewritten to mask columns or filter rows before it reaches the node.

## Unqualified names and the default schema

A connection is scoped to a pool, and the pool is bound to one database with a default catalog and schema. The gateway prepends `USE <catalog>.<schema>;` to each statement, so an unqualified `SELECT * FROM customer` resolves against the pool's database, and `tpch1.customer` (schema-qualified) works too. The prefix is skipped when your statement itself starts with `USE`, `SET`, `BEGIN`, `COMMIT`, `ROLLBACK`, `ATTACH`, or `DETACH`, so you can drive the session explicitly when needed. The mechanics are in [Routing and statement classification](/concepts/routing).

## Transactions

`BEGIN` / `COMMIT` / `ROLLBACK` are supported. A transaction pins to the node its `BEGIN` ran on, and every statement until `COMMIT` / `ROLLBACK` routes to that node. If that node is lost mid-transaction, the transaction fails explicitly and must be restarted, there is no silent re-execution on another node. See [Sessions and transactions](/concepts/sessions-transactions) for the full model.

## Prepared statements

Prepared statements are supported with two caveats in this version:

- They are effectively single-use: the statement is executed when it is prepared (so the result schema is known to JDBC/ADBC) and the result is replayed once.
- Parameter bindings are not supported; a prepared statement is a fixed SQL string.

For repeated parameterized queries, send each as its own statement rather than re-binding a prepared handle.

## Authorization

When per-statement ACL is enabled, every statement is checked against the connecting principal's effective permissions before it reaches a node; a statement that touches a table the principal cannot access is rejected with an access-denied error and never runs. The verb and table model is described in the [Access control model](/operating/rbac-model).

## Querying federated catalogs

If the database has federated sources attached, their catalogs appear under their aliases, and you query them like any other table with a three-part `alias.schema.table` reference (for example `SELECT * FROM fedpg.public.orders`). Access is governed by the same ACL: you need a `SELECT` grant on the federated alias. Federated writes are denied by default. Setting up federated sources is covered in [Federation](/operating/federation).
