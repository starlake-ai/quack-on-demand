# Running Quack on Demand

This guide walks through the three supported deployment paths. Pick the one
that matches what you already have on hand.

| | Postgres | Quack manager | Use when |
|---|---|---|---|
| **[Path 1 - Native](#path-1--native-run-against-an-external-postgres)** | external | bare JVM (`java -jar`) | you already have Postgres locally or in your network, and want the fastest dev loop |
| **[Path 2 - Docker, external Postgres](#path-2--docker-container-against-an-external-postgres)** | external | container | you want the manager isolated (Docker) but Postgres is somewhere else (RDS, Cloud SQL, hosted, host-installed) |
| **[Path 3 - Docker compose](#path-3--docker-compose-bundled-postgres--host-volumes)** | container | container | you want one command to bring up the whole stack; Postgres + DuckLake files persist on the host |

All three paths use **the same `application.conf`** at runtime - knobs are
flipped via `SL_QUACK_*` env vars. None require the loading of any dataset.
Each path below has a **Seed with TPC-H and Run** subsection if you want benchmark
data - the loader probes DuckLake first and self-skips when `tpch1.lineitem`
is already populated, so it's safe to leave the seeding flag on across reboots.

---

## Path 1 - Native run, against an external Postgres

### Prerequisites
- JDK 17+ (to run the jar)
- `duckdb` CLI on `$PATH` (each Quack node is a duckdb process)
- `psql` on `$PATH` (the start script probes Postgres and `CREATE DATABASE`s the catalog DB if missing)
- `openssl` (auto-generates the FlightSQL self-signed TLS cert on first boot)
- A reachable Postgres - the catalog DB and the slkstate_* control-plane tables both live there
  (the catalog DB itself is auto-created on first boot if your `$SL_QUACK_PG_USER` has `CREATEDB`)
- **`sbt` 1.x and `npm` 18+** - only when `BUILD=1` (assembling the jar from this checkout instead of downloading)

### Run

`run-jar.sh` resolves the jar in one of two ways, then anchors the working
directory at the repo root, probes Postgres, and exec's `java -jar` with
Arrow's allocator pinned:

```bash
# Default: download latest release from Maven Central, cache under
# ~/.cache/quack-on-demand/, run it.
SL_QUACK_PG_HOST=db.internal      \
SL_QUACK_PG_PASSWORD=hunter2      \
SL_QUACK_ADMIN_PASSWORD=change-me \
./scripts/run-jar.sh

# Pin a specific version (release or -SNAPSHOT)
QUACK_VERSION=0.1.0           ./scripts/run-jar.sh
QUACK_VERSION=latest-snapshot ./scripts/run-jar.sh

# Local source build (requires sbt + npm); writes distrib/<...>.jar
BUILD=1 ./scripts/run-jar.sh
```

Default ports: `:20900` (REST + admin UI) and `:31338` (FlightSQL edge,
TLS on). Override with `SL_QUACK_ON_DEMAND_PORT` / `PROXY_PORT`.

To stop: `./scripts/stop-quack-on-demand.sh`.

### Seed with TPC-H and Run

The start script will seed the catalog before the JVM boots when
`SL_QUACK_BOOTSTRAP_LOAD_TPCH=true`. It re-uses your `$SL_QUACK_PG_*` and
`$SL_QUACK_DUCKLAKE_DATA_PATH` so the loader and the manager agree on
paths/credentials by construction. The loader self-skips if `tpch1.lineitem`
is already there.

```bash
# Default SF=1 (~6M lineitem rows, ≈ 10s)
SL_QUACK_BOOTSTRAP_LOAD_TPCH=true \
  ./scripts/run-jar.sh

# Larger scale
SL_QUACK_BOOTSTRAP_LOAD_TPCH=true \
SL_QUACK_BOOTSTRAP_TPCH_SF=10 \
  ./scripts/run-jar.sh
```

Standalone (without booting the manager):

```bash
SF=10 ./scripts/load-tpch-dbgen.sh
```

Result: 8 TPC-H tables (`region, nation, customer, supplier, part, partsupp,
orders, lineitem`) in `tpch.tpch1`.

### Override knobs

Every scalar in `application.conf` has a matching `SL_QUACK_*` /
`PROXY_*` env var. The most-used:

| Setting | Env var | Default |
|---|---|---|
| Postgres host  | `SL_QUACK_PG_HOST`      | `localhost` |
| Postgres user  | `SL_QUACK_PG_USER`      | `postgres` |
| Postgres password | `SL_QUACK_PG_PASSWORD` | `azizam` |
| Catalog DB name | `SL_QUACK_PG_DBNAME`   | `tpch` |
| DuckLake data dir | `SL_QUACK_DUCKLAKE_DATA_PATH` | `<repo>/ducklake/<db>` |
| Edge TLS on/off | `PROXY_TLS_ENABLED`    | `true` |
| FlightSQL edge auth | `SL_QUACK_AUTH_DB_ENABLED` | `true` |
| Admin username | `SL_QUACK_ADMIN_USERNAME` | `admin@localhost.local,admin` |
| Admin password | `SL_QUACK_ADMIN_PASSWORD` | `admin` (rotate!) |
| Static REST key | `SL_QUACK_API_KEY`     | unset (open `/api/*` with startup warning) |
| Seed TPC-H at boot | `SL_QUACK_BOOTSTRAP_LOAD_TPCH` | unset (`true` to seed) |
| TPC-H scale factor | `SL_QUACK_BOOTSTRAP_TPCH_SF` | `1` |
| TPC-H schema name | `SL_QUACK_BOOTSTRAP_TPCH_SCHEMA` | `tpch1` |

---

## Path 2 - Docker container, against an external Postgres

> ⚠️ **Don't share a catalog DB between Docker and native runs.** DuckLake
> bakes the absolute `DATA_PATH` into Postgres metadata. Inside the
> container that path is `/app/ducklake/<db>`; natively it's
> `$PWD/ducklake/<db>` on the host. Once a manager writes a path, every
> future manager reading that catalog must see the same string or every
> query will fail with *"could not open file ..."*. If you've already
> booted natively against `PG_DBNAME=tpch`, point Docker at a different
> DB (e.g. `PG_DBNAME=tpch_docker`) - see the [Path-matching gotcha](#path-matching-gotcha-ducklake)
> below for the why and the recovery steps.

### Prerequisites
- Docker
- A reachable Postgres (any host the container can route to)

### Run

`run-docker.sh` either pulls the published image from Docker Hub (default)
or builds it from this checkout's `Dockerfile` (`BUILD=1`), then `docker
run`s it with port mappings + bind mounts + env vars wired up:

```bash
# Default: pull starlakeai/quack-on-demand:latest and run
PG_HOST=my-rds.amazonaws.com      \
PG_PASSWORD=hunter2               \
AUTH=true                         \
ADMIN_PASSWORD=change-me          \
TLS=true                          \
./scripts/run-docker.sh

# Pin a specific tag (release or snapshot)
QUACK_VERSION=0.1.0           PG_HOST=… PG_PASSWORD=… ./scripts/run-docker.sh
QUACK_VERSION=latest-snapshot PG_HOST=… PG_PASSWORD=… ./scripts/run-docker.sh

# Local Dockerfile build (writes the same image name, tagged $QUACK_VERSION)
BUILD=1 PG_HOST=… PG_PASSWORD=… ./scripts/run-docker.sh
```

What it does internally:
- exposes `:20900` and `:31338` on the host
- mounts `$PWD/ducklake/` → `/app/ducklake` (DuckLake Parquet files persist on the host)
- mounts `$PWD/certs/` → `/app/certs` (auto-generated TLS cert persists)
- maps the lease range `21900-22500` for in-container Quack node ports

To stop: `./scripts/stop-docker.sh` (graceful SIGTERM → SIGKILL after 30s,
matching the `CONTAINER_NAME` from `run-docker.sh`; override the timeout
with `STOP_TIMEOUT=60`).

### Seed with TPC-H and Run

The image ships `/app/scripts/load-tpch-dbgen.sh`. `PG_*` and DuckLake env
vars are already set inside the container, so once the manager is up:

```bash
# Default SF=1
docker exec -it quack-on-demand /app/scripts/load-tpch-dbgen.sh

# Larger scale (override at exec time)
docker exec -it -e SF=10 quack-on-demand /app/scripts/load-tpch-dbgen.sh
```

Paths match by construction because the loader runs inside the container
(it sees `DATA_PATH=/app/ducklake/<db>`, the same path the manager later
spawns Quack nodes for). The loader is idempotent: a second `docker exec`
returns immediately when `tpch1.lineitem` is already populated.

### Override knobs

Env vars at `run-docker.sh` invocation time:

| Env var | Default | Notes |
|---|---|---|
| `PG_HOST` / `PG_PASSWORD` | **required** | remote Postgres |
| `PG_PORT` / `PG_USER` / `PG_DBNAME` / `PG_SCHEMA` | 5432 / postgres / tpch / main |
| `AUTH` | `false` | flip to `true` to enable FlightSQL DB auth |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / `admin` | seeded into `slkstate_user` when `AUTH=true` |
| `TLS` | `false` | flip to `true` to keep TLS on |
| `API_KEY` | unset | gates `/api/*` behind `X-API-Key` |
| `DATA_PATH` | `$PWD/ducklake` | host dir bind-mounted to `/app/ducklake` |
| `MANAGER_PORT` / `EDGE_PORT` | 20900 / 31338 | host port mappings |

> **Linux note:** the script doesn't add `--add-host=host.docker.internal:host-gateway`.
> If `PG_HOST=host.docker.internal` and you're on Linux, either add that flag
> manually or use the host's reachable address / `--network=host`.

---

## Path 3 - Docker compose, bundled Postgres + host volumes

### Prerequisites
- Docker (with the compose plugin - `docker compose`, not `docker-compose`)

### First run

> **Host Postgres already on 5432?** The compose file publishes the
> bundled Postgres at `${PG_PORT:-5432}:5432`. If the host already owns
> 5432, `docker compose up` fails with *"failed to bind host port … address
> already in use"* before the stack starts. The manager talks to Postgres
> over the compose-internal network (hostname `postgres`, container port
> 5432) so it does **not** depend on the host publication - bumping
> `PG_PORT=15432` in `.env` is enough; external `psql` would then connect
> via `localhost:15432`. Alternatives: stop the host Postgres, or delete
> the `postgres` service's `ports:` block if you never need external access.

```bash
cp .env.example .env       # edit if you want; defaults work
./scripts/run-docker-compose.sh                 # default = pull from Docker Hub
BUILD=1 ./scripts/run-docker-compose.sh         # local Dockerfile build instead
```

(Equivalent to `docker compose pull && docker compose up -d` for the
pull path, or `docker compose up -d --build` for the BUILD path. The
script also handles host-port collisions on 5432 and the optional
TPC-H seed - see the next two subsections.)

This brings up two services on the compose-default network:

| Service | Image | Volumes |
|---|---|---|
| `postgres`   | `postgres:16-alpine`     | `./pgdata` → `/var/lib/postgresql/data` |
| `quack`      | `starlakeai/quack-on-demand:latest`    | `./ducklake` → `/app/ducklake`, `./certs` → `/app/certs` |

The manager waits on Postgres's `pg_isready` healthcheck before starting,
so no race conditions. Inside the network it reaches the metastore at the
hostname `postgres` - no `host.docker.internal` workaround needed.

### Where state lives

After the first boot, three directories appear next to `docker-compose.yml`:

- **`pgdata/`** - Postgres data dir. Contains the catalog DB, all
  `slkstate_*` control-plane tables, and the `ducklake_*` metadata tables.
  Persists across `docker compose down`.
- **`ducklake/`** - DuckLake Parquet files written by Quack nodes.
  Same persistence guarantee.
- **`certs/`** - Auto-generated self-signed TLS cert + key, reused on subsequent boots.

### Configuration

Edit `.env` to override (see `.env.example` for the full list):

```ini
AUTH=true
ADMIN_PASSWORD=change-me
TLS=true
API_KEY=my-rest-key
```

### Seed with TPC-H and Run

`scripts/run-docker-compose.sh` will boot the stack and run the seed
in one step when `LOAD_TPCH=true`:

```bash
LOAD_TPCH=true ./scripts/run-docker-compose.sh                # SF=1
LOAD_TPCH=true TPCH_SF=10 ./scripts/run-docker-compose.sh     # SF=10
```

Under the hood it does `docker compose exec quack /app/scripts/load-tpch-dbgen.sh`
- same pattern as Path 2. The loader is baked into the image (so no
bind-mounted scripts dir) and sees the same `/app/ducklake` mount the
manager spawns Quack nodes against, so the absolute `DATA_PATH` the
catalog persists matches what the nodes later resolve. Re-running is
cheap - the loader self-skips when `tpch1.lineitem` is already populated.

To seed against a stack that's already up (no restart needed):

```bash
docker compose exec \
  -e PG_HOST=postgres -e PG_PORT=5432 \
  -e PG_USER=postgres -e PG_PASS=azizam \
  -e DB_NAME=tpch -e SCHEMA_NAME=tpch1 \
  -e DATA_PATH=/app/ducklake/tpch -e SF=1 \
  quack /app/scripts/load-tpch-dbgen.sh
```

### Teardown

```bash
docker compose down                          # stops containers, keeps volumes
NUKE=1 ./scripts/stop-docker-compose.sh      # kill + down + wipe host state
```

The bind-mount contents (`./pgdata`, `./ducklake`, `./certs`) are owned
by container uids (postgres uid 70, root for TLS keys), so a host-side
`rm -rf` from a non-root user fails. `NUKE=1` wipes via an ephemeral
root container.

---

## Behind a corporate proxy

Three layers need configuring; they are independent and each is opt-in.

### 1. Docker daemon (for `docker pull` / `docker compose pull`)

The daemon ignores your shell's `HTTP_PROXY`. Configure it once,
system-wide. Either add to `~/.docker/config.json`:

```json
{
  "proxies": {
    "default": {
      "httpProxy":  "http://proxy.corp.example:3128",
      "httpsProxy": "http://proxy.corp.example:3128",
      "noProxy":    "localhost,127.0.0.1,.corp.example"
    }
  }
}
```

Or drop a systemd unit override at
`/etc/systemd/system/docker.service.d/http-proxy.conf`:

```ini
[Service]
Environment="HTTP_PROXY=http://proxy.corp.example:3128"
Environment="HTTPS_PROXY=http://proxy.corp.example:3128"
Environment="NO_PROXY=localhost,127.0.0.1,.corp.example"
```

then `sudo systemctl daemon-reload && sudo systemctl restart docker`.

### 2. The manager + child Quack nodes

Export the standard vars in your shell, then run the script normally.
`scripts/run-docker-compose.sh` and `scripts/run-docker.sh` both forward
them into the container, and `docker-compose.yml` plumbs them into
BuildKit so `BUILD=1` works behind the proxy too:

```bash
export HTTP_PROXY=http://proxy.corp.example:3128
export HTTPS_PROXY=http://proxy.corp.example:3128
export NO_PROXY=localhost,127.0.0.1,postgres,.corp.example
./scripts/run-docker-compose.sh
```

Inside the container, `QuackHttpClient` translates `HTTP_PROXY` into
DuckDB's `SET http_proxy = '<host:port>'` before `INSTALL quack`, and
`scripts/spawn-quack-node.sh` does the same for each child Quack node.
Without these, DuckDB hangs trying to reach
`extensions.duckdb.org` and the manager crashes at boot.

You can also put the proxy in `.env` instead of exporting it - compose
reads `.env` on each `up`. See `.env.example`.

### 3. Proxy on host loopback (cntlm, squid, …)

When the proxy listens on `127.0.0.1:3128` of the host, the scripts
auto-rewrite the URL to `http://host.docker.internal:3128` (and add
`host.docker.internal:host-gateway` to the container's `extra_hosts`)
so the name resolves to the docker bridge on Linux. But the proxy
**itself** still has to accept connections from the bridge. Pick one:

- **Reconfigure the proxy** to listen on `0.0.0.0:3128` (or on the
  docker bridge IP, typically `172.17.0.1:3128`).
- **Forward via socat** if you can't touch the proxy config:

  ```bash
  docker run -d --rm --name proxy-bridge --network host alpine/socat \
    TCP-LISTEN:3128,bind=172.17.0.1,fork,reuseaddr TCP:127.0.0.1:3128
  ```

  This binds a passthrough on the docker bridge IP that forwards to
  the loopback proxy; nothing on the host loopback is changed.

A quick sanity check before you start the stack:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' --connect-timeout 3 \
  -x http://172.17.0.1:3128 http://archive.ubuntu.com
```

`200` means the docker bridge can reach the proxy; `Connection refused`
means you still need to apply one of the two fixes above.

---

## Path-matching gotcha (DuckLake)

**DuckLake stores absolute paths** in the Postgres catalog
(`__ducklake_data_file.path` rows are full path strings, not relative
references). Every Quack node that later reads the catalog opens parquet
files by that exact string. So **any two managers sharing a catalog DB
must see the same DATA_PATH**, or every query will fail with
`could not open file '/...'`.

### Where this bites

The three seed recipes above keep paths matched by construction
(native: same shell + same `$PWD`; Paths 2 + 3: `docker exec`/`docker
compose exec` runs the loader inside the manager container, against the
same `/app/ducklake` bind-mount the manager itself uses). The
problem case is **mixing deployment modes against one catalog DB**:

| First boot | Second boot | What breaks |
|---|---|---|
| Native (`$PWD/ducklake/tpch`) | Docker (`/app/ducklake/tpch`) | Docker nodes can't find host paths |
| Docker (`/app/ducklake/tpch`) | Native (`$PWD/ducklake/tpch`) | Native nodes can't find `/app/...` |
| Native from `/home/a/…` | Native from `/home/b/…` | Second `$PWD` doesn't match catalog |

The `slkstate_*` control-plane tables (tenants, pools, ACLs, admin
users) don't store paths so they're not the problem - the breakage is
strictly in the DuckLake `__ducklake_*` metadata.

### How to avoid it

Pick one of:

1. **Isolate by `PG_DBNAME`.** Native uses `SL_QUACK_PG_DBNAME=tpch`,
   Docker uses `PG_DBNAME=tpch_docker`. Two catalogs, no overlap, both
   can run any time. Recommended for dev machines that toggle between
   modes.
2. **Pick one mode and stay there.** If you only ever boot natively (or
   only ever in Docker), there's nothing to coordinate.
3. **Symlink to match the container path.** Mount `/app/ducklake` to the
   host's `$PWD/ducklake` *and* symlink `/app/ducklake` on the host so
   the absolute string `/app/ducklake/tpch` is also valid natively. Ugly
   and platform-specific; not recommended.

### Recovery if you already mixed

A "stuck" catalog has rows in `__ducklake_data_file` pinned to a path
that the current manager can't see. Two options:

```bash
# Option A - start fresh: drop the catalog DB + the data dir.
psql -h $PG_HOST -U $PG_USER -d postgres -c 'DROP DATABASE tpch'
rm -rf ducklake/tpch                    # the on-disk parquet files
# Re-boot with SL_QUACK_BOOTSTRAP_LOAD_TPCH=true (Path 1) or
# LOAD_TPCH=true ./scripts/run-docker-compose.sh (Path 3).

# Option B - keep the data, use a fresh catalog DB.
SL_QUACK_PG_DBNAME=tpch_native ./scripts/run-jar.sh
```

---

## Connecting clients

Once the manager is up:

- **Admin console:** `http://localhost:20900/ui/`
- **FlightSQL JDBC:**
  `jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true`
- **REST API:** `http://localhost:20900/api/*` (login first via
  `POST /api/auth/login` to get an `X-API-Key` session token when
  `AUTH=true`)
- **Load tester (Python ADBC):** drives the FlightSQL edge concurrently and
  reports throughput + latency percentiles. Defaults to a TPC-H query mix -
  pair it with [Seed with TPC-H and Run](#seed-with-tpc-h-and-run) above.

  > **URL scheme must match the server's TLS setting.** Native and the
  > shipped compose `.env.example` both default to TLS **on**, so
  > `grpc+tls://localhost:31338` (the loadtest default) works out of the
  > box. The `run-docker.sh` path defaults to TLS **off** - pass
  > `--url grpc://localhost:31338` against it. Mismatch surfaces as
  > *"tls: first record does not look like a TLS handshake"* (client TLS
  > against plaintext server) or *"connection reset"* (the inverse). Same
  > story if you override `TLS=false` in `.env`.

  ```bash
  pip install adbc_driver_flightsql adbc_driver_manager

  # Tiny smoke test (TLS-on default - works for native and shipped compose)
  ./scripts/loadtest/loadtest.py -w 2 -i 5

  # Same against a plaintext server (run-docker.sh default, or TLS=false)
  ./scripts/loadtest/loadtest.py --url grpc://localhost:31338 -w 2 -i 5

  # Slightly heavier (with seeded auth)
  LT_USER=admin LT_PASSWORD=change-me \
    ./scripts/loadtest/loadtest.py -w 4 -i 20

  # Heavy: 32 concurrent sessions x 200 iterations = 6400 queries.
  # Bigger warmup so the pool is steady-state before we start counting.
  LT_USER=admin LT_PASSWORD=change-me \
    ./scripts/loadtest/loadtest.py -w 32 -i 200 --warmup 20

  # Stress: 64 sessions x 500 iterations = 32000 queries against TPC-H SF=10.
  # Expect minutes of runtime; watch the manager UI for pool scaling and
  # per-node QPS while it runs.
  LT_USER=admin LT_PASSWORD=change-me \
    ./scripts/loadtest/loadtest.py -w 64 -i 500 --warmup 50

  # Custom URL, single query
  ./scripts/loadtest/loadtest.py --url grpc+tls://host:31338 -q 'SELECT 1' -w 2 -i 5
  ```

  Parameters (each has a matching `LT_*` env var):

  | Flag | Env var | Default | What it does |
  |---|---|---|---|
  | `--url` | `LT_URL` | `grpc+tls://localhost:31338` | FlightSQL endpoint. Use `grpc+tls://` against a TLS-on server (native + shipped compose default), `grpc://` against plaintext (`run-docker.sh` default, or `TLS=false`). Scheme **must match** what the server is listening for - see callout above. |
  | `-u`, `--user` | `LT_USER` | `admin` | DB auth username (only used when the edge requires auth) |
  | `-p`, `--password` | `LT_PASSWORD` | `admin` | DB auth password |
  | `-w`, `--workers` | `LT_WORKERS` | `8` | concurrent client threads; each opens its own ADBC session |
  | `-i`, `--iterations` | `LT_ITERATIONS` | `100` | queries **per worker** (total calls = workers × iterations) |
  | `--warmup` | `LT_WARMUP` | `5` | throwaway iterations per worker before timing starts |
  | `-q`, `--query` | `LT_QUERY` | unset | single SQL to repeat; unset cycles the TPC-H mix (Q1, Q3, Q5, Q6, Q10, Q12, Q14) |
  | `--schema` | `LT_SCHEMA` | `tpch1` | schema prefix injected into the default mix so it resolves regardless of the session's default schema (ignored with `-q`) |
  | `--insecure` | `LT_INSECURE` | `true` | skip TLS cert verification (the auto-generated cert is self-signed). Only meaningful when `--url` is `grpc+tls://`. |

  Start small (`-w 2 -i 5`) to confirm URL scheme / auth / TLS work, then
  scale `-w` to measure pool capacity and `-i` to amortize warmup over
  more samples.

See the README for credentials, default login, and the full feature tour.