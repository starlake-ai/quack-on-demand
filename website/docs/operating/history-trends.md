---
id: history-trends
title: Statement history and trends
---

The manager records every FlightSQL statement in a two-tier store. When `QOD_TELEMETRY_STORE=postgres` (the default), raw statement rows land in `qodstate_stmt_history` for recent search, and a background rollup job aggregates them into `qodstate_stmt_rollup` for trend charts. When `QOD_TELEMETRY_STORE=none`, nothing is recorded and the History page is hidden from the navigation.

Statement history captures **every** statement, including reads. This is different from the [audit log](/administration/audit-log), which records only mutations, auth events, denials, and data-plane writes, and never captures `SELECT`.

## What is recorded

Every statement that completes the FlightSQL execute path is recorded:

| Field | Notes |
|---|---|
| `ts` | Statement completion time (UTC) |
| `tenant` | Tenant display name |
| `pool` | Pool key |
| `username` | The authenticated user |
| `status` | `ok`, `denied`, `transient`, `permanent`, `no-node`, `no-pool`, `pin-lost` |
| `sql` | SQL text, capped at 500 characters |
| `durationMs` | Wall-clock duration from receive to completion |
| `nodeId` | The node that executed the statement |

**What is excluded:** `PREPARE` probe statements (the `LIMIT-0` schema-probe emitted by the ADBC prepare path) do not create a separate row. The matching `EXECUTE` carries a `prepareMs` field instead.

## Two-tier storage model

Raw rows are kept for `QOD_STMT_HISTORY_RETENTION_DAYS` (default `7`) days and support exact search. The rollup job runs every `QOD_ROLLUP_INTERVAL_SEC` (default `300`) seconds and writes two sets of aggregates into `qodstate_stmt_rollup`:

**Hourly rollups** -- one row per `(tenant, pool, UTC hour)`, retained for `QOD_HOURLY_ROLLUP_RETENTION_DAYS` (default `90`) days. Each row holds the total statement count, error count, and p50/p95/p99 latency percentiles computed via `percentile_cont` over the raw rows in that bucket.

**Daily rollups** -- one row per `(tenant, pool, username, UTC day)`. Daily rollups are the backing store for the [usage and accounting](/administration/usage-accounting) feature. They are retained for `QOD_USAGE_RETENTION_DAYS` (default 400) and purged by the same hourly duty that purges raw rows and hourly rollups.

## Watermark semantics

The rollup job uses a per-table watermark stored in Postgres to track the last processed timestamp. On each tick it recomputes all hourly and daily buckets that are newer than the watermark minus a 60-second skew buffer, then advances the watermark to `now() - 60s`. Recomputing overlapping buckets is always safe because the rollup replaces, rather than appends to, each bucket row.

Practical consequences:

- **Short outage (shorter than the raw retention window):** when the manager restarts, the rollup job sees the full backlog in `qodstate_stmt_history` and recomputes the missing buckets on the first tick. The trend chart catches up automatically.
- **Long outage (longer than the raw retention window):** rows older than `QOD_STMT_HISTORY_RETENTION_DAYS` have been purged before the rollup job runs. Those buckets are permanently undercounted in the hourly aggregates. The chart will show a gap or low values for the affected range.
- **Raw retention floor:** do not set `QOD_STMT_HISTORY_RETENTION_DAYS` below `2`. The daily recompute rebuilds the watermark's whole day, which may reach back up to 24 hours. A raw retention of fewer than 2 days can cause the daily bucket to be partially recomputed from an already-purged range. The manager enforces this: `QOD_STMT_HISTORY_RETENTION_DAYS` must be `0` (keep forever) or at least `2`; any other positive value is refused at startup.

The rollup job runs only on the singleton leader in HA mode (the replica holding the `HaCoordinator` Postgres advisory lock). Failover hands the duty to the next leader on its next tick; no manual intervention is needed.

## UTC bucketing

All timestamps and bucket boundaries are in UTC. The API returns `from` / `to` bounds and bucket timestamps as ISO-8601 UTC instants. Pass `from` and `to` in ISO-8601 format (e.g. `2026-07-01T00:00:00Z`); the server does not interpret client-local time zones.

## Who sees what

Access to both history endpoints follows the same scope rules as the audit log:

- **Superusers** see all rows and all tenants.
- **Tenant admins** see only their own tenant's rows. Specifying a different `tenant` in the query is silently overridden to their own tenant; no cross-tenant existence is leaked via differential error codes.
- **Static-key callers** are treated as superusers.

## Retention

The manager runs an hourly background purge:

| What is purged | Controlled by | Default |
|---|---|---|
| Raw statement rows (`qodstate_stmt_history`) | `QOD_STMT_HISTORY_RETENTION_DAYS` | `7` |
| Hourly rollup rows (`qodstate_stmt_rollup`, hourly) | `QOD_HOURLY_ROLLUP_RETENTION_DAYS` | `90` |
| Daily rollup rows (`qodstate_stmt_rollup`, daily) | `QOD_USAGE_RETENTION_DAYS` | `400` |

To keep raw rows for a longer period:

```bash
QOD_STMT_HISTORY_RETENTION_DAYS=30 ./scripts/run-jar.sh
```

Set `QOD_STMT_HISTORY_RETENTION_DAYS=0` to keep raw rows forever (the purge runs but deletes nothing). Keeping raw rows longer than 90 days will not cause rollup gaps but will increase the `qodstate_stmt_history` table size; make sure your Postgres instance has room.

## The `none` off switch

Set `QOD_TELEMETRY_STORE=none` to disable the history subsystem entirely:

- Every statement recording call is a no-op.
- Every query to the history and trends endpoints returns empty.
- The rollup job, the hourly purge, and the watermark updater are not started.
- The `qod_journal_dropped_total` counter stays at zero (not recording is intentional, not a drop).
- The History entry is hidden from the admin UI navigation. A deep link to the History page shows an empty state with a "telemetry is disabled" message.

The `postgres` and `none` values are the only accepted values for `QOD_TELEMETRY_STORE`. Any other value is refused at startup.

## Querying statements via curl

```bash
# Most recent 50 statements for tenant acme (default limit)
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/history/statements?tenant=acme&limit=50' | python3 -m json.tool

# Page through with the keyset cursor (use nextBefore from the previous response)
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/history/statements?before=<nextBefore-from-previous-page>'

# Find yesterday's slow statements: fetch the full day, then inspect durationMs locally
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/history/statements?tenant=acme&pool=bi&from=2026-07-05T00:00:00Z&to=2026-07-06T00:00:00Z&limit=500' \
  | python3 -c "
import sys, json
rows = json.load(sys.stdin).get('statements', [])
slow = [r for r in rows if r.get('durationMs', 0) > 5000]
for r in sorted(slow, key=lambda x: -x.get('durationMs', 0)):
    print(r.get('durationMs'), r.get('username'), r.get('sql', '')[:80])
"

# Denied statements only
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/history/statements?tenant=acme&status=denied&limit=100'

# Free-text search for a table name
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/history/statements?tenant=acme&q=lineorder'
```

### Statement filter parameters

| Parameter | Description |
|---|---|
| `tenant` | Filter by tenant (superusers can specify any tenant; tenant admins are pinned to their own) |
| `pool` | Filter by pool key |
| `user` | Filter by username (exact match) |
| `status` | Filter by status: `ok`, `denied`, `transient`, `permanent`, `no-node`, `no-pool`, or `pin-lost` |
| `q` | Substring match on the SQL text |
| `from` | ISO-8601 instant; return only statements at or after this time |
| `to` | ISO-8601 instant; return only statements before this time |
| `limit` | Number of rows to return (default 50, max 500) |
| `before` | Opaque keyset cursor from `nextBefore` in a prior response; fetches the next page of older results |

Results are returned newest-first. The response includes a `nextBefore` cursor whenever the page is non-empty. There is no duration filter parameter on the endpoint; fetch a time window and filter `durationMs` client-side.

## Querying trends via curl

```bash
# Hourly trend for the last 7 days, tenant acme, pool bi
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/history/trends?granularity=hour&tenant=acme&pool=bi&from=2026-06-29T00:00:00Z&to=2026-07-06T00:00:00Z' \
  | python3 -m json.tool

# Daily trend for the last 30 days (all pools for a tenant)
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/history/trends?granularity=day&tenant=acme&from=2026-06-06T00:00:00Z&to=2026-07-06T00:00:00Z' \
  | python3 -m json.tool
```

### Trend filter parameters

| Parameter | Description |
|---|---|
| `granularity` | `hour` or `day` (required) |
| `tenant` | Filter by tenant (superusers can specify any tenant; tenant admins are pinned to their own) |
| `pool` | Filter by pool key |
| `from` | ISO-8601 instant; start of the range (inclusive) |
| `to` | ISO-8601 instant; end of the range (exclusive) |

Each bucket in the response carries `bucketStart` (UTC bucket start), `tenant`, `pool`, `username`, `stmtCount`, `errorCount`, `deniedCount`, `engineMsSum`, and `p50Ms`/`p95Ms`/`p99Ms`. Percentile fields are present on hourly buckets only; daily buckets carry `null` for percentiles (daily rollups are per-user and are not yet surfaced as latency charts).

Buckets that had zero statements are not returned; gaps in the response represent idle periods.
