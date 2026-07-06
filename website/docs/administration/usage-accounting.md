---
id: usage-accounting
title: Usage and accounting
---

The manager maintains a durable per-tenant / per-pool / per-user metering ledger built on the daily rollup store. When `QOD_TELEMETRY_STORE=postgres` (the default), each rollup tick writes one row per `(tenant, pool, username, UTC day)` into `qodstate_stmt_rollup`; the usage API reads and aggregates those rows. When `QOD_TELEMETRY_STORE=none`, nothing is metered, `GET /api/usage` returns empty, and the Usage page is hidden from the navigation.

The [Prometheus counters](/reference/metrics) (`statements_total` and `statement_duration_seconds`, both tagged by `tenant`, `pool`, and `status`) provide the real-time scrape view of the same quantities. The usage API and admin UI page are the durable ledger view, suited for monthly billing exports and capacity planning over longer windows.

## The metering unit and its caveats

`engineMs` is the summed statement execution time as measured by the manager: wall-clock duration from receiving the FlightSQL execute call to the last byte streamed back to the client. It is not node CPU time; it includes routing latency and network round-trips to the node.

A few caveats apply before using these numbers for billing:

- **Current-day lag.** The current day's totals are complete only up to the last rollup tick. With the default rollup interval (`QOD_ROLLUP_INTERVAL_SEC=300`), the current day may lag by up to 5 minutes.
- **Journal overflow undercounts.** The event journal that feeds statement recording is a bounded in-process queue. Under extreme burst load, offers that cannot be enqueued are dropped and counted in `qod_journal_dropped_total{table="stmt_history"}`. Dropped rows never reach the rollup, so the ledger can undercount during sustained overload. This counter is the integrity signal: if it is non-zero, the affected period undercounts by that amount.
- **Best-effort, not transactional.** The ledger is best-effort measurement. It is not a transactional accounting system. A manager restart during rollup computation, or a Postgres write failure, can cause a bucket to be partially computed or missing; the watermark-based rollup will recompute it on the next tick if the raw rows are still available.
- **Denied and error statements.** Statements that never reached a node (admission denials, `no-node`, `no-pool`) are counted in `denied` or `errors` with near-zero `engineMs`. They appear in totals but do not contribute meaningfully to engine time.

## API

`GET /api/usage` returns aggregated daily rollup data over a requested period. The endpoint requires authentication (either the `X-API-Key` header or a `qod_session` cookie from the admin UI).

```bash
# Month-to-date usage per tenant (defaults: current calendar month UTC, groupBy=tenant)
curl -sS -H "X-API-Key: $TOKEN" 'http://localhost:20900/api/usage' | python3 -m json.tool

# June 2026, per pool, one tenant
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/usage?from=2026-06-01T00:00:00Z&to=2026-07-01T00:00:00Z&groupBy=pool&tenant=acme' \
  | python3 -m json.tool

# Per-user accounting for a billing export
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/usage?from=2026-06-01T00:00:00Z&to=2026-07-01T00:00:00Z&groupBy=user' \
  | python3 -m json.tool
```

### Query parameters

| Parameter | Description |
|---|---|
| `from` | ISO-8601 instant (e.g. `2026-06-01T00:00:00Z`). Start of the period, inclusive. Defaults to the first instant of the current calendar month (UTC). |
| `to` | ISO-8601 instant. End of the period, exclusive (half-open `[from, to)` interval). Defaults to the first instant of the following calendar month (UTC). |
| `groupBy` | Grouping dimension: `tenant` (default), `pool`, or `user`. Returns `400 invalid_group_by` for any other value. |
| `tenant` | Filter by tenant display name. Superusers can specify any tenant; tenant admins are silently pinned to their own tenant regardless of this value. |
| `pool` | Filter by pool key. Combined with `tenant` to narrow further. |

Invalid ISO-8601 values for `from` or `to` return `400 invalid_time`.

### Response fields

```json
{
  "from": "2026-06-01T00:00:00Z",
  "to": "2026-07-01T00:00:00Z",
  "groupBy": "pool",
  "dataStart": "2026-04-17T00:00:00Z",
  "groups": [
    {
      "tenant": "acme",
      "pool": "bi",
      "username": null,
      "statements": 18432,
      "errors": 12,
      "denied": 5,
      "engineMs": 924000,
      "days": [
        { "day": "2026-06-01T00:00:00Z", "statements": 830, "errors": 0, "engineMs": 41000 }
      ]
    }
  ]
}
```

| Field | Type | Notes |
|---|---|---|
| `from` | string | Period start as ISO-8601 UTC instant |
| `to` | string | Period end as ISO-8601 UTC instant |
| `groupBy` | string | The grouping dimension used: `tenant`, `pool`, or `user` |
| `dataStart` | string or null | Oldest daily bucket still in the ledger. Present and earlier than `from` only when the requested range starts before the retention horizon. Use it to detect a truncated edge. |
| `groups` | array | One entry per (tenant, pool, username) combination with non-zero activity in the period. Sorted by `engineMs` descending. |
| `groups[].tenant` | string | Tenant display name |
| `groups[].pool` | string or null | Pool key; null when `groupBy=tenant` |
| `groups[].username` | string or null | Username; null when `groupBy` is not `user` |
| `groups[].statements` | integer | Total statements in the period |
| `groups[].errors` | integer | Statements whose status is not `ok` or `denied` (transient, permanent, no-node, no-pool, pin-lost) |
| `groups[].denied` | integer | Statements whose status is `denied` (blocked by the ACL validator) |
| `groups[].engineMs` | integer | Summed execution time in milliseconds |
| `groups[].days` | array | Per-day breakdown, one entry per UTC day bucket with non-zero statements |
| `groups[].days[].day` | string | Day bucket start as ISO-8601 UTC instant |
| `groups[].days[].statements` | integer | Statement count for that day |
| `groups[].days[].errors` | integer | Error count for that day |
| `groups[].days[].engineMs` | integer | Summed engine time for that day in milliseconds |

Note that per-day entries carry `errors` but not `denied`. The `denied` total is available at the group level only.

## CSV column contract

The admin UI's Download CSV button produces a client-side CSV. There is no server-side CSV endpoint. The column order depends on the active `groupBy`:

| `groupBy` | Columns |
|---|---|
| `tenant` | `tenant,statements,errors,denied,engine_ms` |
| `pool` | `tenant,pool,statements,errors,denied,engine_ms` |
| `user` | `tenant,user,statements,errors,denied,engine_ms` |

The absent dimension columns (`pool`, `user`) are omitted for dimensions that are not the active grouping. The filename is `qod-usage-<from-date>-<to-date>.csv`.

## Retention

The manager runs an hourly background purge that deletes daily rollup rows older than `QOD_USAGE_RETENTION_DAYS`:

| Env var | Default | Effect |
|---|---|---|
| `QOD_USAGE_RETENTION_DAYS` | `400` | Delete daily rollup buckets older than N days; `0` = keep forever |

The default of 400 covers a full billing year (365 days) plus a safety margin. The purge runs on the same hourly tick as the audit and history purges; in HA mode it runs only on the singleton leader.

When the requested `from` date is before the retention horizon, the API returns whatever buckets remain and sets `dataStart` to the oldest bucket present. The UI marks this truncated edge with a note "Data starts YYYY-MM-DD (older buckets purged)."

## Feeding external billing

The JSON API is the integration surface for external billing systems. A typical monthly billing pipeline:

```bash
# Extract a closed billing month (half-open interval, UTC)
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/usage?from=2026-06-01T00:00:00Z&to=2026-07-01T00:00:00Z&groupBy=user' \
  | jq -r '["tenant","user","statements","errors","denied","engine_ms"],
           (.groups[] | [.tenant, (.username // ""), .statements, .errors, .denied, .engineMs])
           | @csv'
```

Use the per-day `days` arrays if you need sub-monthly granularity (for example, to detect spikes or to reconcile a partial month in a multi-system environment).

Rows and bytes transferred are not metered. Metering them would require counting Arrow batches on the hot statement path and is out of scope.

## Scoping rules

The scoping rules mirror the statement history endpoints:

- **Superusers** and static-key callers see all tenants. The `tenant` and `pool` filter parameters work as passed.
- **Tenant admins** are pinned to their manageable tenants. A `?tenant=` parameter is accepted but silently intersected with the admin's own tenants; specifying another tenant's name does not return an error and does not reveal its existence.

## The `none` off switch

Set `QOD_TELEMETRY_STORE=none` to disable all telemetry:

- Every rollup write is a no-op, so no daily buckets are created.
- `GET /api/usage` returns an empty `groups` list.
- The rollup job, hourly purge duties, and journal fiber are not started.
- The Usage entry is hidden from the admin UI navigation. A deep link to the Usage page shows a "telemetry is disabled" message.

The `postgres` and `none` values are the only accepted values for `QOD_TELEMETRY_STORE`. Any other value is refused at startup.
