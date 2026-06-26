# TPC-H 22-query benchmark

Times each of the 22 canonical TPC-H queries (Q1..Q22, standard spec
parameter values) against the quack-on-demand FlightSQL edge and writes a
**CSV** plus an **HTML** report. The HTML chart plots query number on the
X axis and median query duration (ms) on the Y axis.

Each query is run once as a warmup, then `--runs` timed executions; the
report shows the per-query median / p95 / min / max latency.

## Concurrency

`--workers N` runs every query with `N` workers **simultaneously**. Each
worker opens its own ADBC connection, and a barrier keeps all workers on the
same query at the same time before they advance to the next one. So a query
gets `workers x runs` latency samples, and the report adds a `p95` column and
a concurrent `QPS` throughput per query.

- `--workers 1` (default): serial, one execution at a time.
- `--workers 2`: each query is executed by 2 workers at once.
- `--workers 16`: 16 simultaneous executions per query (load test).

The worker count is encoded in the output filename
(`tpch-bench-w<N>.{csv,html}`) so runs at different concurrency levels do not
overwrite each other.

## Prerequisites

- The manager is running with the FlightSQL edge on `:31338` (see the repo
  root: `./scripts/run-jar.sh`).
- The TPC-H demo data is loaded into the target tenant/schema
  (`./scripts/load-tpch-dbgen.sh` seeds `acme` / `bi` / `tpch1`).

## Run

```bash
scripts/load-22/run.sh                          # acme / bi / tpch1, 1 worker, 5 runs
scripts/load-22/run.sh --workers 8              # 8 workers run each query at once
scripts/load-22/run.sh --workers 16 --runs 10 --warmup 2
scripts/load-22/run.sh --tenant globex --pool bi --schema tpch1
scripts/load-22/run.sh --out-dir /tmp/tpch-bench
```

`run.sh` provisions a one-time Python venv with the ADBC FlightSQL driver
under `${QOD_ADBC_VENV:-$HOME/.cache/qod-adbc/venv}` and reuses it. Behind a
proxy set `PIP_PROXY=http://host:port` for the first install. All flags pass
through to `benchmark.py`.

## Output

- `out/tpch-bench-w<N>.csv` - columns: `query, median_ms, p95_ms, min_ms, max_ms, rows, samples, qps, status, error`
- `out/tpch-bench-w<N>.html` - Chart.js bar chart (query number vs median + p95 ms) + a results table

(`<N>` is the worker count. `out/` is the default; override with `--out-dir`.)

## Flags

| Flag | Default | Meaning |
| --- | --- | --- |
| `--url` | `grpc+tls://localhost:31338` | FlightSQL edge URI |
| `--user` / `--password` | `admin` / `admin` | Basic auth |
| `--tenant` / `--pool` | `acme` / `bi` | gRPC routing headers |
| `--schema` | `tpch1` | schema the queries are prefixed with |
| `--workers` / `-w` | `1` | workers that run each query simultaneously (one connection each) |
| `--runs` | `5` | timed executions per query **per worker** (median over workers x runs) |
| `--warmup` | `1` | throwaway executions per worker before timing |
| `--superuser` / `--no-superuser` | superuser on | system-realm login (bootstrap admin) |
| `--insecure` | on | skip TLS verification (dev self-signed cert) |
| `--out-dir` | `scripts/load-22/out` | where the CSV + HTML land |

Every flag also has an `LT_*` env-var equivalent (`LT_TENANT`, `LT_WORKERS`,
`LT_RUNS`, `LT_SCHEMA`, ...), matching `scripts/tpch-load-test/tpch-load-test.py`.