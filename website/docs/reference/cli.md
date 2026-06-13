---
id: cli
title: CLI
---

The manager is one uber-jar. Run with no arguments it boots the manager; it also has two `manifest` subcommands for scripting. A set of wrapper scripts under `scripts/` cover the common boot and teardown flows.

## The manager jar

```bash
# Boot the manager (REST + UI + FlightSQL edge). The default with no args.
java -jar quack-on-demand-assembly-<version>.jar

# Export the whole control-plane configuration as YAML to stdout.
java -jar quack-on-demand-assembly-<version>.jar manifest export > manifest.yaml

# Import a configuration manifest from stdin.
java -jar quack-on-demand-assembly-<version>.jar manifest import < manifest.yaml
```

The `manifest export` / `manifest import` subcommands operate on the Postgres control-plane store directly (they do not need a running manager) and mirror the `/api/manifest/*` REST endpoints; see [Manifest backup and restore](/operating/manifest). All behavior is configured through `QOD_*` / `PROXY_*` environment variables; see the [Configuration reference](/reference/configuration).

## Wrapper scripts

### `run-jar.sh`

Boots the manager from the uber-jar with TLS cert auto-generation and a Postgres reachability probe.

| Env var | Default | Effect |
|---|---|---|
| `BUILD` | `0` | `1` runs `sbt assembly` first instead of using the existing jar. |
| `QOD_VERSION` | latest | Release to resolve from Maven Central (`0.3.2`, `latest-snapshot`, ...). |
| `LOAD_TPCH` | unset | Positive integer scale factor: seeds TPC-H sf=N into `acme/acme_tpch`. |
| `LOAD_TPCDS` | unset | Positive integer scale factor: seeds TPC-DS sf=N into `globex/globex_tpcds`. |
| `LOAD_TPC` | unset | Legacy shortcut: equivalent to setting both `LOAD_TPCH=N` and `LOAD_TPCDS=N`. Explicit per-bench vars override it. Setting any of the three exports `QOD_BOOTSTRAP_YAML` so the JVM imports the bundled demo manifest. |
| `DUCKDB_VERSION` | derived from `libquackwireVersion` in `build.sbt` | Pin a specific DuckDB release for the self-install (`run-jar.sh` always provisions the CLI + `libduckdb`). Pinning a version that disagrees with libquackwire's ABI can crash node spawn with an `UnsatisfiedLinkError`. |
| `DUCKDB_CACHE_DIR` | `$REPO_DIR/.duckdb` | Relocate the DuckDB cache root. Each pinned version lives under `$DUCKDB_CACHE_DIR/$DUCKDB_VERSION/{bin,lib}`. Pre-populating these for air-gapped / CI runs lets the fast-path skip the network fetch. |
| `NUKE` | `0` | `1` drops the control-plane DB and wipes local state dirs first. Does NOT wipe the DuckDB cache. Irreversible. |

```bash
./scripts/run-jar.sh
BUILD=1 ./scripts/run-jar.sh
NUKE=1 LOAD_TPCH=1 ./scripts/run-jar.sh                       # fresh boot + TPC-H only
NUKE=1 LOAD_TPCH=1 LOAD_TPCDS=10 ./scripts/run-jar.sh         # both, independent SFs
./scripts/stop-jar.sh        # SIGTERM, wait, then SIGKILL
```

### `run-docker-compose.sh`

Brings up the full stack (manager + Postgres, plus optional profiles) via Docker Compose. Same `BUILD` / `QOD_VERSION` / `LOAD_TPCH` / `LOAD_TPCDS` / `LOAD_TPC` / `NUKE` flags as above, plus `PROFILES` (comma-separated, e.g. `observability,seaweedfs`). See [Docker deployment](/operating/deploy-docker).

```bash
./scripts/run-docker-compose.sh
PROFILES=observability ./scripts/run-docker-compose.sh
./scripts/stop-docker-compose.sh
```

### `run-docker.sh`

Runs a single manager container against an external Postgres (requires `PG_HOST` and `PG_PASSWORD`). See the Docker section of [Installation](/getting-started/install). Stop with `stop-docker.sh`.

### Other scripts

- `load-tpch-dbgen.sh` - seed TPC-H into the `acme_tpch` tenant-db (invoked by the boot scripts via `LOAD_TPCH` or the legacy `LOAD_TPC`, or run directly inside a running container).
- `load-tpcds-dbgen.sh` - seed TPC-DS into the `globex_tpcds` tenant-db (invoked by the boot scripts via `LOAD_TPCDS` or the legacy `LOAD_TPC`, or run directly inside a running container).
- `spawn-quack-node.sh` - spawns one Quack node. Invoked by `LocalQuackBackend` with the right port, token, and metastore contract; do not run it directly.
- `loadtest/loadtest.py` - the bundled ADBC load tester, usable as a one-shot client. Cycles a curated TPC-H or TPC-DS mix; switch via `--workload tpch` (default) / `--workload tpcds`. See [Connecting clients](/connecting/clients).
