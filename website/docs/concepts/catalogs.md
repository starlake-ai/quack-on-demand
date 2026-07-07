---
id: catalogs
title: DuckLake catalogs
---

Each database (tenant-db) is backed by a catalog that the Quack nodes open. The catalog mode is the database's `kind`. This page explains the three kinds, how a DuckLake catalog separates metadata from data, and how the data path is derived. For creating databases, see [Tenants and databases](/operating/tenants-databases); this page is about how they work.

## The three kinds

| Kind | Catalog | Data | Use |
|---|---|---|---|
| `ducklake` (default) | DuckLake catalog metadata (`ducklake_*` tables) stored in the tenant-db's own Postgres database | Parquet files under `dataPath` (filesystem or object store) | Production, multi-node persistence |
| `duckdb-file` | A standalone `.duckdb` file at `dataPath` | In the same file | Single node only (the file must exist on every node) |
| `memory` | None; ephemeral | None persistent | Federation-only databases (point `defaultDatabase` at a federated alias) |

## How a DuckLake catalog is laid out

A `ducklake` database splits metadata from data:

- **Metadata** lives in the tenant-db's Postgres database as `ducklake_*` tables: the schema, table, and file-manifest records that make up the catalog. This database is separate from the control-plane `qod` database, which only ever holds `qodstate_*` tables and never `ducklake_*` ones.
- **Data** is Parquet, written under the database's `dataPath`, which can be a local filesystem path or an `s3://` / `gs://` / `az://` / `abfss://` object-store URI.

Because the catalog metadata lives in shared Postgres and the data lives in shared storage, **every Quack node in a pool attaches the same DuckLake catalog and sees one consistent view**. That is what makes a `ducklake` database safe to serve from many nodes at once, and why `duckdb-file` (a single local file) is effectively single-node.

When a tenant-db is created the supervisor provisions its Postgres database and then initializes the DuckLake catalog once (the `ducklake_*` tables). The catalog browser in the admin UI reads those tables directly, which is why it is available only in `postgres` state-storage mode.

## Catalog and schema names

A DuckLake database is addressed by a catalog name (`dbName`) and a default schema (`schemaName`). These must differ: a same-named catalog and schema is an ambiguous two-part identifier in DuckDB, which JDBC clients hit on identifier resolution. The router prepends `USE <dbName>.<schemaName>;` to each statement so unqualified names resolve against the right catalog and schema; see [Routing and statement classification](/concepts/routing).

## Data path derivation

The global default `dataPath` is a root; each tenant-db gets its own subdirectory under it. The supervisor derives the per-database path by replacing the last component of the global default with the composed `${tenant}_${tenantDb}` name. For example a global default of `/var/ducklake/tpch` yields `/var/ducklake/tpch_tpch1` for the `tpch/tpch1` database. Object-store URIs are handled string-wise (so the `//` after the scheme is preserved), because the path DuckLake records in the catalog must match the operator-supplied URI exactly or the next `ATTACH` is refused.

Switching a database to object storage is therefore a matter of setting its `dataPath` to a bucket URI and supplying the S3 credentials; see [Docker deployment](/operating/deploy-docker) for the `QOD_S3_*` keys and the bundled object-store option.

## Snapshots and time travel

DuckLake versions the catalog: every committed transaction writes a snapshot, and all metadata rows (schemas, tables, columns, data files) carry `begin_snapshot` / `end_snapshot` ranges. Snapshots therefore accumulate with write traffic; an append-heavy database collects thousands in weeks. History is linear (no branches), so ordering by snapshot id is the lineage.

The admin UI's catalog browser exposes this history: a per-database snapshots panel (id, commit time, change summary, rows and files added or removed, affected tables) and an "as of" view on each table that shows its schema and parquet files at any retained snapshot. See the [catalog browser](/operating/admin-ui#catalog-browser). Like the rest of the browser, these are metadata reads against the `ducklake_*` tables; they do not query table data and work even when the pool has no running nodes.

Two details worth knowing:

- Small DML can be inlined. DuckLake buffers small inserts and deletes in catalog tables and materializes parquet on the next flush, backdating the files to the snapshot where the change logically happened. AS OF views follow engine semantics, but the current-state row count (from DuckLake's stats) includes inlined rows that the AS OF computation (parquet rows minus delete rows) does not, so the two can briefly differ on a write-hot table.
- Snapshots are retained until expired. Expiry is a DuckLake maintenance operation (`ducklake_expire_snapshots()` followed by `ducklake_cleanup_old_files()`), not something the manager runs automatically; the browser shows whatever history the catalog retains, and time travel reaches back only to the oldest retained snapshot.
