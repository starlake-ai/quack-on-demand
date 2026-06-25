# TPC-H 22-query benchmark

Times each of the 22 canonical TPC-H queries (Q1..Q22, standard spec
parameter values) against the quack-on-demand FlightSQL edge and writes a
**CSV** plus an **HTML** report. The HTML chart plots query number on the
X axis and median query duration (ms) on the Y axis.

Each query is run once as a warmup, then `--runs` timed executions; the
report shows the per-query median / min / max latency.

## Prerequisites

- The manager is running with the FlightSQL edge on `:31338` (see the repo
  root: `./scripts/run-jar.sh`).
- The TPC-H demo data is loaded into the target tenant/schema
  (`./scripts/load-tpch-dbgen.sh` seeds `acme` / `bi` / `tpch1`).

## Run

```bash
scripts/load-22/run.sh                          # acme / bi / tpch1, 5 runs
scripts/load-22/run.sh --runs 10 --warmup 2
scripts/load-22/run.sh --tenant globex --pool bi --schema tpch1
scripts/load-22/run.sh --out-dir /tmp/tpch-bench
```

`run.sh` provisions a one-time Python venv with the ADBC FlightSQL driver
under `${QOD_ADBC_VENV:-$HOME/.cache/qod-adbc/venv}` and reuses it. Behind a
proxy set `PIP_PROXY=http://host:port` for the first install. All flags pass
through to `benchmark.py`.

## Output

- `out/tpch-bench.csv` - columns: `query, median_ms, min_ms, max_ms, rows, status, error`
- `out/tpch-bench.html` - Chart.js bar chart (query number vs median ms) + a results table

(`out/` is the default; override with `--out-dir`.)

## Flags

| Flag | Default | Meaning |
| --- | --- | --- |
| `--url` | `grpc+tls://localhost:31338` | FlightSQL edge URI |
| `--user` / `--password` | `admin` / `admin` | Basic auth |
| `--tenant` / `--pool` | `acme` / `bi` | gRPC routing headers |
| `--schema` | `tpch1` | schema the queries are prefixed with |
| `--runs` | `5` | timed executions per query (median reported) |
| `--warmup` | `1` | throwaway executions before timing |
| `--superuser` / `--no-superuser` | superuser on | system-realm login (bootstrap admin) |
| `--insecure` | on | skip TLS verification (dev self-signed cert) |
| `--out-dir` | `scripts/load-22/out` | where the CSV + HTML land |

Every flag also has an `LT_*` env-var equivalent (`LT_TENANT`, `LT_RUNS`,
`LT_SCHEMA`, ...), matching `scripts/tpch-load-test/tpch-load-test.py`.