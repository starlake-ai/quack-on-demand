---
id: install
title: Installation
---

Quack on Demand ships as a Docker image and as a single uber-jar driven by the `qod` CLI (`pip install qod`, or no install at all with `uvx`). The fastest way to evaluate it is [Demo mode](/getting-started/quickstart#demo-mode-self-contained-no-postgres) via `uvx qod demo`. For a durable deployment, two paths are supported: Docker, and the native jar via [`qod start`](#native-jar-qod-start). Both run on **Linux, macOS, and Windows** (Windows support is experimental; see [Run on Windows](#run-on-windows) below). GitHub Releases hosts the raw jar artifacts, but you should rarely need to download one by hand - every path below fetches what it needs.

## Docker

The published image is `starlakeai/quack-on-demand`. Use `scripts/run-docker.sh` to pull and run it against an external Postgres:

```bash
PG_HOST=db.internal PG_PASSWORD=*** ./scripts/run-docker.sh
```

The script requires `PG_HOST` and `PG_PASSWORD`. Key options:

| Variable | Default | Description |
|---|---|---|
| `QOD_VERSION` | `latest` | Image tag to pull |
| `PG_PORT` | `5432` | Postgres port |
| `PG_USER` | `postgres` | Postgres user |
| `MANAGER_PORT` | `20900` | Host port for REST + admin UI |
| `EDGE_PORT` | `31338` | Host port for FlightSQL edge |
| `TLS` | `false` | Enable FlightSQL TLS inside the container |
| `ADMIN_PASSWORD` | `admin` | Admin login password (rotate before production) |
| `API_KEY` | unset | REST `X-API-Key` static key |
| `BUILD` | `0` | Set to `1` to build the image from the local Dockerfile instead of pulling |

To pin a version or use the latest snapshot:

```bash
QOD_VERSION=0.3.2 PG_HOST=... PG_PASSWORD=*** ./scripts/run-docker.sh
QOD_VERSION=latest-snapshot PG_HOST=... PG_PASSWORD=*** ./scripts/run-docker.sh
```

**Note:** do not mix Docker and native-jar runs against the same catalog DB. DuckLake records the absolute data path in Postgres metadata. Inside the container that path is `/app/ducklake/<db>`; natively it is `<host-cwd>/ducklake/<db>`. Use a different `PG_DBNAME` (or `QOD_PG_DBNAME`) per mode, or wipe the data directory between switches.

## Native jar (`qod start`)

`qod start` runs a real manager on the host JVM against your own Postgres, with no repository checkout: it downloads the release jar from GitHub Releases (verified against the published sha256), self-installs the DuckDB CLI + `libduckdb` at the pinned ABI, and starts the JVM with the right flags.

### Prerequisites

- **Postgres 16 or later, reachable.** Installers for all platforms are at [postgresql.org/download](https://www.postgresql.org/download/) (Windows users can also grab the [EDB installer](https://www.enterprisedb.com/downloads/postgres-postgresql-downloads) directly). The control plane stores all state in a dedicated database (default name `qod`) on `localhost:5432`; point `QOD_PG_HOST` / `QOD_PG_PORT` / `QOD_PG_USER` / `QOD_PG_PASSWORD` at yours. When `psql` is on `PATH`, `qod start` creates the control-plane database up front (idempotent); without it the manager creates what it needs on first connection.

  If you do not have a local Postgres instance, the quickest path is:

  ```bash
  docker run -d --name qod-pg \
    -e POSTGRES_PASSWORD=azizam \
    -p 5432:5432 \
    postgres:16-alpine
  ```

- **Java 21+ is found, not required.** `qod start` uses `JAVA_HOME` or `java` on `PATH` when a 21+ JVM is present; otherwise it offers to download a cached Temurin 21 JRE (about 50 MB, one time). `JAVA_BIN` forces a specific binary; `JAVA_OPTS` adds JVM flags (e.g. `-Xmx2g`).
- **DuckDB is self-installed** (CLI + `libduckdb` at the ABI libquackwire links against) into the user cache dir - a system duckdb at the wrong ABI would crash the first node spawn, so it is never used. `DUCKDB_VERSION` / `DUCKDB_CACHE_DIR` override the pin and location; air-gapped operators can pre-populate `$DUCKDB_CACHE_DIR/$VERSION/{bin,lib}` and no network fetch happens.

### Run from Linux/MacOS

```bash
uv tool install qod            # or: pip install qod

qod start                      # this CLI's matching release
QOD_VERSION=latest qod start   # newest release
QOD_VERSION=0.3.8 qod start    # pinned release
qod start --jar path/to/quack-on-demand-assembly-X.Y.Z.jar   # a jar you built
```

On first run against a freshly-provisioned Postgres, Liquibase applies the control-plane schema. The admin UI is at `http://localhost:20900/ui/`; log in as `admin` with password `admin` (change this before any exposure beyond localhost -- see [Configuration model](#configuration-model) below).

The FlightSQL edge listens on `localhost:31338` with TLS on by default. Durable state (the self-signed certs, and the default DuckLake data path unless `QOD_DUCKLAKE_DATA_PATH` is set) lives under the platform user data dir (`~/.local/share/qod` on Linux, `~/Library/Application Support/qod` on macOS).

To stop the manager and its quack nodes (SIGTERM, escalating to SIGKILL after `FORCE_AFTER` seconds):

```bash
qod stop
```

#### Seeding the demo dataset for a quick smoke test

Pass `LOAD_TPCH=1`, `LOAD_TPCDS=1`, and/or `LOAD_SSB=1` to seed each benchmark independently; the loaders run in the background using the self-installed DuckDB - no extra steps.

```bash
LOAD_TPCH=1 qod start                          # TPC-H only (acme, ~10 s)
LOAD_TPCDS=1 qod start                         # TPC-DS only (globex, ~30 s)
LOAD_SSB=1 qod start                           # SSB star schema only (acme, ~15 s)
LOAD_TPCH=1 LOAD_TPCDS=1 qod start             # TPC-H + TPC-DS
LOAD_TPC=1 qod start                           # legacy shortcut for all three
```

Each flag seeds the matching demo dataset: `acme` loaded with TPC-H (8 tables in schema `tpch1`), `globex` loaded with TPC-DS (24 tables in schema `tpcds1`), and/or the SSB star schema (5 tables in schema `ssb1`, derived from TPC-H dbgen and living next to `tpch1` in the `acme_tpch` tenant-db). The bundled manifest `bootstrap-demo.yaml` (imported from the jar's classpath) declares the tenants, roles, groups, and users; see the [Access control model](/operating/rbac-model) for the full ACL matrix.

For a completely fresh start (drops the control-plane DB and wipes local state directories before booting; requires `psql` for the DB drops):

```bash
NUKE=1 LOAD_TPCH=1 LOAD_TPCDS=1 qod start
```

To front a single DuckDB instance instead of the multi-tenant demo, `DEMO=minimal` imports `bootstrap-demo-minimal.yaml`: one tenant (`acme`), one pool (`bi`), one dual node serving both reads and writes, and the analyst RLS/CLS demo. `DEMO=full` is the default and covers the multi-tenant, multi-pool, and federation demos. The profile is only consulted when a `LOAD_*` flag is set and `QOD_BOOTSTRAP_YAML` is unset; bootstrap only imports into a fresh control plane, so switch profiles with `NUKE=1`. `DEMO=minimal` with `LOAD_TPCDS` warns and skips the TPC-DS loader (no `globex` tenant in this profile).

```bash
NUKE=1 DEMO=minimal LOAD_TPCH=1 qod start
```

### Run on Windows

:::warning Experimental
Windows support is **experimental**. The `qod` launcher needs release **0.3.8 or later** (older releases predate the `demo`/`start` subcommand support and are refused).
:::

The manager runs natively on Windows - no WSL, no Docker. `pip install qod` installs the same `qod.exe` entry point, and `qod demo` / `qod start` work as on Linux/macOS: Java 21 is auto-provisioned when missing, DuckDB (CLI + `duckdb.dll`) is self-installed, and Quack nodes are spawned through the bundled PowerShell mirror of the node-spawn script.

```powershell
pip install qod
$env:QOD_PG_HOST        = 'localhost'
$env:QOD_PG_PASSWORD    = 'hunter2'
$env:QOD_ADMIN_PASSWORD = 'change-me'
qod start
```

**Current Windows gaps:** `qod stop` is not implemented (kill the java process with `taskkill /PID <pid> /T`), and the `LOAD_*` benchmark seeders are bash-only through `qod start` (it warns and skips them; run the PowerShell loaders `scripts\load-*-dbgen.ps1` from a checkout instead).

**Client path.** Two ways the manager talks to the Quack nodes:

- **Native wire (default, fastest)** needs `native\windows-x86_64\quackwire.dll` bundled in the assembly. The published cross-platform jar carries it; a jar you build yourself only carries it when built with `QOD_WITH_WINDOWS_NATIVE=true` (see below).
- **Embedded DuckDB (JDBC), no native build** - set `$env:QOD_NATIVE_CLIENT = 'false'`. The DuckDB JDBC driver ships its own Windows native, so this works with any assembly jar out of the box (slower; serialized). Good for a first run.

**Building the Windows native (`quackwire.dll`), optional.** Requires MSVC (Visual Studio Build Tools) + CMake + `sbt`. Assemble a `DUCKDB_HOME` with `duckdb.lib` plus the full `duckdb/` include tree (see `.github/workflows/quackwire.yml`, step "Install libduckdb v1.5.4 (Windows)"), then:

```powershell
$env:DUCKDB_HOME = 'C:\path\to\duckdb-home'
cmake -S native\quackwire -B native\quackwire\build -DCMAKE_BUILD_TYPE=Release -DDUCKDB_HOME="$env:DUCKDB_HOME"
cmake --build native\quackwire\build --config Release
New-Item -ItemType Directory -Force libquackwire\binaries\windows-x86_64 | Out-Null
Copy-Item native\quackwire\build\Release\quackwire.dll libquackwire\binaries\windows-x86_64\
$env:QOD_WITH_WINDOWS_NATIVE = 'true'
sbt libquackwire/publishLocal
sbt assembly            # bundles native/windows-x86_64/quackwire.dll
qod start --jar distrib\quack-on-demand-assembly-<version>.jar
```

### Build from source

```bash
sbt assembly
qod start --jar distrib/quack-on-demand-assembly-*.jar
```

To run the jar manually instead:

```bash
java -jar distrib/quack-on-demand-assembly-*.jar
```

The assembly output lands in `distrib/quack-on-demand-assembly-<version>.jar`. The jar carries the `Add-Opens` manifest attribute covering `java.base/java.nio` and `java.base/sun.nio.ch`, which Arrow Flight's unsafe allocator needs on Java 17+. No extra `--add-opens` flags are needed on the command line.

Note: `sbt compile` and `sbt assembly` also run `npm ci` and `npm run build` inside the `ui/` directory as a resource generator, so the React admin UI is bundled automatically. There is no separate UI build step.

## The CLI beyond launching

The same `qod` command drives the whole REST surface and runs SQL against the
FlightSQL edge:

```bash
qod login --url http://localhost:20900 --username admin
qod tenant list
```

See the [CLI section](/cli/) for a tour.

## Configuration model

Every scalar in `application.conf` accepts a matching `QOD_*` environment variable override (FlightSQL edge keys use the `PROXY_*` prefix). The bundled `application.conf` is baked into the jar at build time, so you should use environment variables rather than editing it directly.

**Defaults you must change before production:**

| Setting | Env var | Insecure default |
|---|---|---|
| Admin password | `QOD_ADMIN_PASSWORD` | `admin` |
| Postgres password | `QOD_PG_PASSWORD` | `azizam` |
| REST API key | `QOD_API_KEY` | unset (open API) |

Setting `QOD_API_KEY` to a secret string requires that value in the `X-API-Key` header on every REST call. Leaving it unset means the REST API is accessible without authentication (acceptable only on localhost or behind a trusted network boundary).

Other commonly used variables:

| Setting | Env var | Default |
|---|---|---|
| Manager REST port | `QOD_ON_DEMAND_PORT` | `20900` |
| FlightSQL edge port | `PROXY_PORT` | `31338` |
| FlightSQL TLS on/off | `PROXY_TLS_ENABLED` | `true` |
| State backend | `QOD_STATE_STORAGE` | `postgres` |
| Postgres host | `QOD_PG_HOST` | `localhost` |
| Postgres user | `QOD_PG_USER` | `postgres` |
| Control-plane database | `QOD_PG_DBNAME` | `qod` |
| Admin usernames | `QOD_ADMIN_USERNAME` | `admin@localhost.local,admin` |
| Enable per-statement RBAC | `QOD_ACL_ENABLED` | `false` |

For the full list of configuration keys, see the [Configuration reference](/reference/configuration).