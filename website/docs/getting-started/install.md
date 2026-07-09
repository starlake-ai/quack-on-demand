---
id: install
title: Installation
---

Quack on Demand ships as a Docker image and as a single uber-jar. Two installation paths are supported: Docker, and the binaries (jar downloaded from Maven Central, or built from source). The jar path runs on **Linux, macOS, and Windows** - Windows support is experimental, requires `0.3.7-SNAPSHOT` or later, and uses PowerShell launchers (`*.ps1`) in place of the bash scripts; see [Run on Windows](#run-on-windows) below.

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

## Binaries

### Prerequisites

- **JDK 21 or later.** Download it from [Adoptium (Eclipse Temurin)](https://adoptium.net/temurin/releases/?version=21) or use your platform's package manager (`brew install temurin@21`, `apt install openjdk-21-jdk`, `winget install EclipseAdoptium.Temurin.21.JDK`). The assembly jar embeds an `Add-Opens` manifest attribute (JEP 261) so no extra `--add-opens` flags are required when running `java -jar`.
- **`curl` + `unzip` on `$PATH`.** `run-jar.sh` uses them on first boot to self-install the DuckDB CLI + `libduckdb` shared library into `$REPO_DIR/.duckdb/<version>/` at the ABI libquackwire links against (overridable via `DUCKDB_VERSION` / `DUCKDB_CACHE_DIR`). The install is mandatory by design - an operator's system duckdb at the wrong ABI crashes the first node spawn with a confusing dlopen error. Air-gapped operators can pre-populate the cache directory; the script skips the network fetch when the cached binaries match the pinned version. On **Windows**, `run-jar.ps1` does the same self-install with built-in `Invoke-WebRequest` / `Expand-Archive` (no `curl`/`unzip` needed) and prepends the cache `bin\` + `lib\` to `PATH`.
- **Postgres 16 or later.** Installers for all platforms are at [postgresql.org/download](https://www.postgresql.org/download/) (Windows users can also grab the [EDB installer](https://www.enterprisedb.com/downloads/postgres-postgresql-downloads) directly). The control plane stores all state in a dedicated database (default name `qod`) on `localhost:5432`. The `run-jar.sh` startup script probes Postgres before the JVM boots and refuses to proceed against a server older than PG 16.

  If you do not have a local Postgres instance, the quickest path is:

  ```bash
  docker run -d --name qod-pg \
    -e POSTGRES_PASSWORD=azizam \
    -p 5432:5432 \
    postgres:16-alpine
  ```

### Run from Linux/MacOS

`scripts/run-jar.sh` handles everything: it resolves the latest release from Maven Central (`ai.starlake:quack-on-demand_3:<version>`), caches the jar under `~/.cache/quack-on-demand/`, probes Postgres, creates the control-plane database if it does not exist, and starts the JVM with the Arrow allocator pinned.

```bash
# Latest release (default)
./scripts/run-jar.sh

# Pinned release
QOD_VERSION=0.3.2 ./scripts/run-jar.sh

# Latest snapshot from Central snapshots
QOD_VERSION=latest-snapshot ./scripts/run-jar.sh
```

On first run against a freshly-provisioned Postgres, the script creates the `qod` database and Liquibase applies the control-plane schema. The admin UI is at `http://localhost:20900/ui/`; log in as `admin` with password `admin` (change this before any exposure beyond localhost -- see [Configuration model](#configuration-model) below).

The FlightSQL edge listens on `localhost:31338` with TLS on by default (self-signed cert auto-generated under `certs/`).

To stop the manager:

```bash
./scripts/stop-jar.sh
```

#### Seeding the demo dataset for a quick smoke test

Pass `LOAD_TPCH=1`, `LOAD_TPCDS=1`, and/or `LOAD_SSB=1` to seed each benchmark independently before the JVM starts. The `run-jar.sh` self-install above already provisions the DuckDB CLI the loaders need - no extra steps.

```bash
LOAD_TPCH=1 ./scripts/run-jar.sh                          # TPC-H only (acme, ~10 s)
LOAD_TPCDS=1 ./scripts/run-jar.sh                         # TPC-DS only (globex, ~30 s)
LOAD_SSB=1 ./scripts/run-jar.sh                           # SSB star schema only (acme, ~15 s)
LOAD_TPCH=1 LOAD_TPCDS=1 ./scripts/run-jar.sh             # TPC-H + TPC-DS
LOAD_TPC=1 ./scripts/run-jar.sh                           # legacy shortcut for all three
```

Each flag seeds the matching demo dataset: `acme` loaded with TPC-H (8 tables in schema `tpch1`), `globex` loaded with TPC-DS (24 tables in schema `tpcds1`), and/or the SSB star schema (5 tables in schema `ssb1`, derived from TPC-H dbgen and living next to `tpch1` in the `acme_tpch` tenant-db). The bundled manifest under `src/main/resources/bootstrap-demo.yaml` declares the tenants, roles, groups, and users; see the [Access control model](/operating/rbac-model) for the full ACL matrix.

For a completely fresh start (drops the control-plane DB and wipes local state directories before booting):

```bash
NUKE=1 LOAD_TPCH=1 LOAD_TPCDS=1 ./scripts/run-jar.sh
```

### Run on Windows

:::warning Experimental
Windows support is **experimental** and requires version **0.3.7-SNAPSHOT** or later - no stable release carries the Windows launchers yet. Set the version env var *before* running the jar:

```powershell
$env:QOD_VERSION = '0.3.7-SNAPSHOT'
.\scripts\run-jar.ps1
```
:::

The manager runs natively on Windows - no WSL, no Docker. The bash launchers have PowerShell twins, and Quack nodes are spawned through a PowerShell mirror of the node-spawn script.

**Prerequisites**

- **JDK 21+** (`java` on `PATH`, or `JAVA_HOME` set).
- **Windows PowerShell 5+** (ships with Windows) - used to spawn Quack nodes.
- **PostgreSQL 16+** reachable from the host (native install, a container, or a network host). `psql` is *optional* - the manager provisions its own control-plane and tenant databases; when `psql` is present `run-jar.ps1` additionally runs a reachability probe, enforces the PG16+ version gate, creates the control-plane database up-front, and enables the `NUKE=1` / `LOAD_*` flows below.
- **DuckDB is self-installed** by `run-jar.ps1` (CLI + `duckdb.dll`, v1.5.4, `windows-amd64`) into `.duckdb\<version>\` and prepended to `PATH`. A system `duckdb` on `PATH` is ignored - an ABI/feature mismatch (for example a `winget` 0.10.x build) would fail node spawn.

**Run** (from a PowerShell prompt at the repo root):

```powershell
$env:QOD_VERSION        = '0.3.7-SNAPSHOT'
$env:QOD_PG_HOST        = 'localhost'
$env:QOD_PG_PASSWORD    = 'hunter2'
$env:QOD_ADMIN_PASSWORD = 'change-me'
.\scripts\run-jar.ps1
```

`run-jar.ps1` is at feature parity with the bash `run-jar.sh`. By default it resolves the jar from Maven Central (`QOD_VERSION`; on Windows pin it to `0.3.7-SNAPSHOT` or later, since older releases do not carry the Windows launchers; `latest-snapshot` picks the newest snapshot), caches it under `%USERPROFILE%\.cache\quack-on-demand\` with a sha1 check, and falls back to the newest `distrib\quack-on-demand-assembly-*.jar` or `sbt assembly` (bootstrapping `sbt` into `.sbt-bootstrap\` if it isn't on `PATH`) when nothing is published. Set `$env:BUILD='1'` to force a local `sbt assembly`. Before starting the JVM it runs a Postgres reachability probe + PG16 gate, creates the control-plane database if missing, and does a port preflight (aborting if the REST/FlightSQL ports are held or a stale Quack node is squatting the node-port range). All the same `QOD_*` / `PROXY_*` environment variables apply. Stop with `.\scripts\stop-jar.ps1`.

**Seeding the demo datasets.** The same seed flags as the bash path work on Windows - `run-jar.ps1` runs the PowerShell demo loaders (`load-{tpch,tpcds,ssb}-dbgen.ps1`) in the background before the JVM starts:

```powershell
$env:LOAD_TPCH = '1'; .\scripts\run-jar.ps1                       # TPC-H SF=1 into acme/acme_tpch
$env:NUKE = '1'; $env:LOAD_TPCH = '1'; .\scripts\run-jar.ps1      # wipe state, then fresh boot + seed
```

`LOAD_TPCDS=N` (globex/globex_tpcds) and `LOAD_SSB=N` (acme, schema `ssb1`) behave the same; `LOAD_TPC=N` is the legacy shortcut for all three. `NUKE=1` drops the control-plane and demo tenant-db databases and wipes `ducklake\`, `state\`, and `certs\` before booting (irreversible; requires `psql`). Each loader can also be run standalone (e.g. `$env:SF=10; .\scripts\load-tpch-dbgen.ps1`).

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
.\scripts\run-jar.ps1   # the default native client now works on Windows
```

### Build from source

`BUILD=1` tells `run-jar.sh` to call `sbt assembly` first instead of downloading:

```bash
BUILD=1 ./scripts/run-jar.sh
```

To build the jar separately and run it manually:

```bash
sbt assembly
java -jar distrib/quack-on-demand-assembly-*.jar
```

The assembly output lands in `distrib/quack-on-demand-assembly-<version>.jar`. The jar carries the `Add-Opens` manifest attribute covering `java.base/java.nio` and `java.base/sun.nio.ch`, which Arrow Flight's unsafe allocator needs on Java 17+. No extra `--add-opens` flags are needed on the command line.

Note: `sbt compile` and `sbt assembly` also run `npm ci` and `npm run build` inside the `ui/` directory as a resource generator, so the React admin UI is bundled automatically. There is no separate UI build step.

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