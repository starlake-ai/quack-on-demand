---
id: cli
title: Manager jar
---

The manager is one uber-jar. Run with no arguments it boots the manager; it also has the self-contained `demo` subcommand and two `manifest` subcommands for scripting. The `qod` CLI (`pip install qod`) covers the common boot and teardown flows without a checkout.

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

## Launcher commands

### `qod start`

Boots the manager from the release uber-jar (downloaded from GitHub Releases and sha256-verified, or `--jar <path>`) with TLS cert auto-generation and the DuckDB CLI + `libduckdb` self-installed at the pinned ABI. A Java 21+ is picked up from `JAVA_HOME`/`PATH` or auto-provisioned (Temurin 21, cached). Postgres is assumed reachable at `QOD_PG_HOST`/`QOD_PG_PORT`.

| Env var | Default | Effect |
|---|---|---|
| `QOD_VERSION` | the CLI's own release | Which release jar to run: a version (`0.3.8`, must be 0.3.8+) or `latest`. `--jar <path>` runs a local jar instead. |
| `JAVA_BIN` / `JAVA_OPTS` | unset | Force a specific java binary / add JVM flags (e.g. `-Xmx2g`). |
| `JAR_CACHE_DIR` | user cache dir | Where downloaded jars are cached. |
| `LOAD_TPCH` | unset | Positive integer scale factor: seeds TPC-H sf=N into `acme/acme_tpch`. |
| `LOAD_TPCDS` | unset | Positive integer scale factor: seeds TPC-DS sf=N into `globex/globex_tpcds`. |
| `LOAD_SSB` | unset | Positive integer scale factor: derives the SSB star schema (`lineorder`, `customer`, `supplier`, `part`, `dwdate`) from TPC-H dbgen at sf=N into `acme/acme_tpch` schema `ssb1`. |
| `LOAD_TPC` | unset | Legacy shortcut: equivalent to setting `LOAD_TPCH=N`, `LOAD_TPCDS=N`, and `LOAD_SSB=N`. Explicit per-bench vars override it. Setting any of the four exports `QOD_BOOTSTRAP_YAML` so the JVM imports the bundled demo manifest. |
| `DEMO` | `full` | Demo bootstrap profile: `full` imports `bootstrap-demo.yaml` (tenants `acme` + `globex`: multi-tenancy, multiple pools, federation); `minimal` imports `bootstrap-demo-minimal.yaml`, the single-DuckDB shape (one tenant `acme`, one pool `bi`, one dual node serving both reads and writes, analyst RLS/CLS demo). Only consulted when a `LOAD_*` flag is set and `QOD_BOOTSTRAP_YAML` is unset. `DEMO=minimal` with `LOAD_TPCDS` warns and skips the TPC-DS loader. Bootstrap only imports into a fresh control plane; switch profiles with `NUKE=1`. |
| `DUCKDB_VERSION` | pinned per release | Pin a specific DuckDB release for the self-install (the CLI + `libduckdb` are always provisioned). Pinning a version that disagrees with libquackwire's ABI can crash node spawn with an `UnsatisfiedLinkError`. |
| `DUCKDB_CACHE_DIR` | user cache dir | Relocate the DuckDB cache root. Each pinned version lives under `$DUCKDB_CACHE_DIR/$DUCKDB_VERSION/{bin,lib}`. Pre-populating these for air-gapped / CI runs lets the fast-path skip the network fetch. |
| `NUKE` | `0` | `1` drops the control-plane + demo tenant DBs (needs `psql`) and wipes the local state dirs first. Does NOT wipe the DuckDB cache. Irreversible. |

```bash
qod start
QOD_VERSION=latest qod start
qod start --jar distrib/quack-on-demand-assembly-<version>.jar   # a jar you built
NUKE=1 LOAD_TPCH=1 qod start                       # fresh boot + TPC-H only
NUKE=1 LOAD_TPCH=1 LOAD_TPCDS=10 qod start         # both, independent SFs
NUKE=1 DEMO=minimal LOAD_TPCH=1 qod start          # single-DuckDB profile: acme, 1 dual node
qod stop                     # SIGTERM, wait, then SIGKILL (FORCE_AFTER seconds)
```

### `run-docker-compose.sh`

Brings up the full stack (manager + Postgres, plus optional profiles) via Docker Compose. Same `QOD_VERSION` / `LOAD_TPCH` / `LOAD_TPCDS` / `LOAD_SSB` / `LOAD_TPC` / `DEMO` / `NUKE` flags as above (here `QOD_VERSION` picks the image tag; `QOD_VERSION=BUILD` builds the repo Dockerfile and runs the `:local` tag, `QOD_VERSION=LOCAL` reuses it without rebuilding), plus `PROFILES` (comma-separated, e.g. `observability,seaweedfs`). See [Docker deployment](/operating/deploy-docker).

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
- `load-ssb-dbgen.sh` - derive the SSB star schema from TPC-H dbgen into schema `ssb1` of the `acme_tpch` tenant-db (invoked by the boot scripts via `LOAD_SSB` or the legacy `LOAD_TPC`, or run directly inside a running container).
- `spawn-quack-node.sh` - spawns one Quack node. Invoked by `LocalQuackBackend` with the right port, token, and metastore contract; do not run it directly.
- `tpch-load-test/tpch-load-test.py` - the bundled ADBC `tpch-load-test`, usable as a one-shot client. Cycles a curated TPC-H or TPC-DS mix; switch via `--workload tpch` (default) / `--workload tpcds`. See [Connecting clients](/connecting/clients).
