---
id: metrics
title: Metrics
---

The manager registers these series through Micrometer. They are emitted to whichever sink is active (`QOD_METRICS_SINK`); under the default Prometheus sink they appear at `GET :20900/metrics`. For how to scrape, push to a cloud monitor, or import the Grafana dashboard, see [Observability](/operating/observability).

## Application metrics

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `statements_total` | counter | `tenant`, `pool`, `status` | Statements executed, partitioned by outcome status. Drives QPS and error-rate panels. |
| `statement_duration_seconds` | histogram | `tenant`, `pool` | Statement execution latency; the source of the p50/p95/p99 percentiles. |
| `flightsql_sessions_active` | gauge | (none) | Currently open FlightSQL sessions. |
| `pool_nodes` | gauge | `tenant`, `pool`, `role` | Node count per pool, broken down by node role. |
| `node_healthy` | gauge | `tenant`, `pool`, `node_id`, `role` | 1 when the node is healthy, 0 otherwise. |
| `node_draining` | gauge | `tenant`, `pool`, `node_id`, `role` | 1 when the node is draining in-flight work before shutdown. |
| `node_in_flight` | gauge | `tenant`, `pool`, `node_id`, `role` | Statements currently executing on the node. |
| `node_ewma_latency_seconds` | gauge | `tenant`, `pool`, `node_id`, `role` | EWMA of completed-statement latency, the signal the router uses to pick the least-loaded node. |

## DuckDB engine metrics

Scraped from each node's DuckDB engine (`duckdb_memory()`, `duckdb_temporary_files()`) by the background health probe, one extra round-trip per node per `QOD_HEALTH_CHECK_INTERVAL_SEC` tick. A node that has never been scraped successfully publishes no row (rather than a misleading zero); a failed scrape keeps the previous sample until the next tick.

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `node_duckdb_memory_used_bytes` | gauge | `tenant`, `pool`, `node_id`, `role` | Buffer-manager memory in use, summed across all consumers (base tables, hash tables, parquet readers, ...). Compare against the node's `memory_limit` to spot memory pressure before latency degrades. |
| `node_duckdb_temp_storage_bytes` | gauge | `tenant`, `pool`, `node_id`, `role` | Bytes the buffer manager has moved to temporary storage. |
| `node_duckdb_spill_files` | gauge | `tenant`, `pool`, `node_id`, `role` | Live spill-to-disk files. Non-zero means queries are exceeding the memory budget and spilling. |
| `node_duckdb_spill_bytes` | gauge | `tenant`, `pool`, `node_id`, `role` | Total size of live spill files. |

## JVM and process metrics

Registered by the Micrometer JVM and process binders:

| Metric | Type | Meaning |
|---|---|---|
| `jvm_memory_used_bytes` | gauge | Heap and non-heap memory in use. |
| `jvm_gc_pause_seconds_sum` | counter | Cumulative GC pause time. |
| `jvm_threads_live_threads` | gauge | Live thread count. |
| `process_uptime_seconds` | gauge | Process uptime since manager start. |

## Common labels

Every series can carry static deployment labels when these are set, useful for separating environments in a shared Grafana:

| Variable | Label | Example |
|---|---|---|
| `QOD_METRICS_DEPLOYMENT` | `deployment` | `prod-eu` |
| `QOD_METRICS_REGION` | `region` | `eu-west-1` |
