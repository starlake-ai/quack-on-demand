---
id: maintenance
title: Managed DuckLake maintenance
---

The manager runs a background maintenance service that keeps every DuckLake database healthy and its storage bounded. Without maintenance, DuckLake only ever adds: deletes and compactions write new files while old snapshots keep the previous files referenced, so disk usage grows monotonically. The service runs the full DuckLake maintenance chain per database, on compute isolated from query serving, with retention holds enforced and every run recorded.

Maintenance is **double-opt-in**. The service fibers are on by default (`QOD_MAINT_ENABLED=true`), but every database's policy starts disabled, so nothing destructive happens until you enable a database explicitly, from the tenant page's Maintenance tab or via the REST API.

:::warning First enable expires history
The retention window (default 7 days) is the time-travel horizon. The first time you enable maintenance on a database, the next scheduler tick expires every unpinned snapshot older than the window. Tag the snapshots you want to keep as `protected` first (see [retention holds](#retention-holds)).
:::

## The chain

Each run executes the DuckLake maintenance functions in this order, on an ephemeral maintenance node spawned just for the run (never on the nodes serving queries):

1. **Flush** inlined DML to Parquet (`ducklake_flush_inlined_data`).
2. **Expire** snapshots older than the retention window. Expiry always passes an explicit snapshot list: candidates older than `retentionDays`, minus every snapshot pinned by a protected tag, minus the latest snapshot (always kept).
3. **Merge** adjacent small files (`ducklake_merge_adjacent_files`).
4. **Rewrite** files with many deletes (`ducklake_rewrite_data_files`, table-scoped runs).
5. **Cleanup** files scheduled for deletion, after the grace window (`ducklake_cleanup_old_files`). Physical deletion lags expiry by `cleanupGraceDays` (default 1) so long-running readers are not cut off.
6. **Delete orphans** older than `orphanMinAgeDays` (`ducklake_delete_orphaned_files`).

Compaction alone reclaims nothing; only the full chain reduces on-disk bytes. The integration suite pins this, and also pins that `ducklake_cleanup_old_files` physically deletes files under an external Postgres catalog with an explicit `DATA_PATH` on the pinned DuckDB version. Re-verify that test on every engine upgrade.

## Triggers

- **Threshold**: the scheduler watches per-table small-file counts in each database's Postgres catalog (metadata reads, no node involved). A table with at least `smallFileMinCount` files under the target size gets a table-scoped run (flush + merge + rewrite only; nothing lake-wide is expired from a threshold trigger).
- **Cadence**: a per-database cron (default `0 3 * * *` UTC, staggered per database) runs the full chain, including expiry and cleanup.
- **Manual**: `POST /api/maintenance/run` as an escape hatch for backfills and incident response. Returns 409 `run_active` when a run is already queued or running for the database. The Maintenance tab's "Run maintenance now" form drives the same endpoint: scope is a select (whole database runs the full chain; single table takes schema and table inputs and runs only the table-safe steps), and operations are checkboxes (`flush`, `expire`, `merge`, `rewrite`, `cleanup`, `orphans`) - all checked runs the full chain, unchecking restricts the run to the checked subset. See the [admin UI guide](/operating/admin-ui#maintenance).

Runs are queued in the control plane and drained with bounded concurrency (`maxConcurrent`, default 2), serialized per database. Under HA only the leader schedules and drains.

## Policies

Policies are hierarchical: a `table` scope overrides a `schema` scope overrides the `tenantdb` scope, field by field; unset fields fall through to the built-in defaults.

| Field | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Master switch per scope. The lake-level value gates everything. |
| `retentionDays` | 7 | Expire snapshots older than this. This is the time-travel, undrop, and history horizon. |
| `compactionEnabled` | `true` | Run the merge step. |
| `targetFileSize` | `auto` | Target compacted file size (`auto` or e.g. `128MB`). |
| `smallFileMinCount` | 12 | Threshold trigger: sub-target files per table before a compaction run. |
| `rewriteDeleteThreshold` | 0.2 | Fraction of deleted rows that triggers a file rewrite. |
| `cleanupGraceDays` | 1 | Physical deletion lags expiry by this window. |
| `orphanMinAgeDays` | 1 | Never delete orphan files younger than this. |
| `cron` | `0 3 * * *` | Cadence fallback (5-field subset: `*`, integers, `*/n`; UTC). |

Manage policies from the Maintenance tab (see the [admin UI guide](/operating/admin-ui)) or via REST:

```bash
# Enable maintenance on a database with a 14-day horizon
curl -sS -X POST -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  localhost:20900/api/maintenance/policy/upsert \
  -d '{"tenant":"acme","tenantDb":"acme_tpch","scopeKind":"tenantdb","enabled":true,"retentionDays":14}'

# Inspect rows + the resolved effective policy
curl -sS -H "X-API-Key: $KEY" \
  'localhost:20900/api/maintenance/policy?tenant=acme&tenantDb=acme_tpch'

# Run history (newest first, keyset pagination via before=<id>)
curl -sS -H "X-API-Key: $KEY" \
  'localhost:20900/api/maintenance/runs?tenant=acme&tenantDb=acme_tpch&limit=50'

# Manual run (escape hatch); scope is optional: "tenantdb" or "table:<schema>.<table>"
curl -sS -X POST -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  localhost:20900/api/maintenance/run \
  -d '{"tenant":"acme","tenantDb":"acme_tpch","operations":"flush,merge"}'
```

All endpoints are tenant-scoped: a tenant admin manages only their own tenant's policies and runs. Policy changes and manual runs are audited (`maintenance.policy.upsert`, `maintenance.policy.delete`, `maintenance.run.manual`), and every finished run writes a `maintenance.run` audit entry with its counters.

## Retention holds

Before expiring anything, the runner subtracts the pin-set: every snapshot carrying a `protected` [snapshot tag](/operating/admin-ui#snapshots-and-time-travel) survives expiry, and a fail-safe guard skips the cleanup step entirely (marking the run `partial`) if a pinned file ever appears in the deletion schedule. Protect a snapshot before shrinking the retention window if you need it beyond the horizon.

## Run lifecycle and troubleshooting

A run moves `queued` to `running` (with a heartbeat per chain step) to `succeeded`, `failed`, or `partial`. The run history records per-step counters: snapshots expired and skipped-as-pinned, files merged, rewritten, cleaned, orphans deleted, and bytes reclaimed (measured as the catalog-bytes delta across the run; physical deletion lags by the grace window).

- Each run spawns its ephemeral node and waits up to `nodeReadyTimeoutSec` (default 180, env `QOD_MAINT_NODE_READY_TIMEOUT_SEC`) for it to accept connections before the first chain statement is sent; the wait covers cold-start extension installs. A node that is not reachable in time is stopped and the run fails as a node spawn failure ("did not accept connections within Ns" in the manager log); an exception during the spawn itself logs "node spawn failed" and fails the run the same way.
- A run whose heartbeat goes stale for `runTimeoutMin` (default 60) is swept to `failed`. Size `runTimeoutMin` above your longest single chain step, or a long compaction will be swept while still executing.
- A `partial` run means the pinned-file guard fired or a non-fatal step was skipped; the error column names the cause.
- With `QOD_MAINT_ENABLED=false` the fibers do not run at all: nothing is scheduled, and manually enqueued runs stay `queued` until the service is re-enabled.

Configuration knobs (`QOD_MAINT_*`) are listed in the [configuration reference](/reference/configuration); the `qod_maint_*` series are in the [metrics reference](/reference/metrics).
