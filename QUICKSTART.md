# Quickstart

Zero to first query through the FlightSQL gateway in under 5 minutes. One
command brings up Postgres + the manager + a TPC-H seed; a second runs a real
SQL query against it.

## Prerequisites

- `docker` + `docker compose` v2
- ~3 GB free disk, ~2 GB free RAM
- Ports `20900` (admin REST + UI) and `31338` (FlightSQL edge) free on
  localhost
- `python3` (any 3.9+; macOS / most Linux ship it)

## 1. Boot the stack — 60 seconds

From a clone of this repo:

```bash
cp .env.example .env             # copy the default config (admin/admin, etc.)
LOAD_TPCH=1 ./scripts/run-docker-compose.sh
```

This pulls `starlakeai/quack-on-demand:latest` from Docker Hub, brings up
Postgres + the manager in the background, seeds TPC-H at scale factor 1
into the bootstrap tenant-db `tpch_tpch1` (~6M lineitem rows, ~10 s on a
modern laptop), and waits until the manager REST is reachable. Expected
tail:

```
seeding TPC-H (db=tpch_tpch1, schema=tpch1, SF=1, data_path=/app/ducklake/tpch_tpch1) via docker compose exec quack ...
…
stack is up:
  image:      starlakeai/quack-on-demand:latest
  REST + UI:  http://localhost:20900/ui/
  FlightSQL:  grpc+tls://localhost:31338  (TLS=true)
  Postgres:   localhost:15432 (external)  /  postgres:5432 (internal)
```

## 2. Run your first query — 30 seconds

The repo ships a Python load tester that doubles as a one-shot client:

```bash
pip install --user adbc_driver_flightsql adbc_driver_manager
python3 ./scripts/loadtest/loadtest.py \
  --url grpc+tls://localhost:31338 --insecure \
  --user admin --password admin \
  -w 1 -i 1 --warmup 0
```

(`.env`'s `TLS=true` means the gateway expects TLS; `--insecure` skips
cert verification because the self-signed cert isn't in your trust store.
Drop both flags once you mount a CA-signed cert and set `TLS=false`.)

Expected:

```
Load test: 1 workers x 1 iterations (+0 warmup) against grpc+tls://localhost:31338 as admin -> tpch/sales
Workload:  default TPC-H mix [schema=tpch1]

─── Results ───
Queries OK:       1
Queries failed:   0
Latency  p50:     ~60 ms
```

The `-> tpch/sales` in the banner is the routing scope picked up from
the loadtest's `--tenant` / `--pool` defaults (matching the bootstrap).
Every FlightSQL client must scope by tenant + pool so the router knows
which Quack-node pool to dispatch to — that includes the JDBC / ODBC /
ADBC examples in §4 below, all of which thread the same `tpch` + `sales`
into the connection.

One TPC-H query (default mix) went: ADBC FlightSQL client → TLS gateway on
`:31338` → authenticated as `admin/admin` against `qodstate_user` (the
admin is seeded as a superuser, `tenant IS NULL`) → routed via the
`tenant=tpch, pool=sales` gRPC headers to the bootstrap pool's three
Quack nodes → DuckLake-resolved against `tpch_tpch1.tpch1.*` parquet →
Arrow result streamed back. Every layer the gateway adds was exercised
in that one round-trip.

## 3. Browse the admin UI (optional) — 10 seconds

Open <http://localhost:20900/ui/> in a browser. Log in as `admin` /
`admin`. The Tenants page shows the bootstrap tenant `tpch`; opening it
reveals the **Databases**, **Pools**, and **Auth provider** tabs. The
Databases tab drills into `tpch1` and browses the catalog (per-table
row count, parquet file count, on-disk folder under `./ducklake/tpch_tpch1/`
or the configured `s3://…` URL). The Pools tab shows `sales` with its
three Quack nodes (`WO`, `RO`, `DUAL`). The **Nodes** page (top nav)
hosts the live cluster dashboard plus the recent-statements history of
the query you just ran. The **Users** page is the RBAC console
(users · groups · roles · identities + per-user "Effective permissions"
drilldown).

## 4. Run a custom SQL — 30 seconds

The load tester accepts an inline SQL via `-q`:

```bash
LT_QUERY="SELECT n_name, count(*) AS suppliers
          FROM tpch1.nation JOIN tpch1.supplier ON n_nationkey = s_nationkey
          GROUP BY n_name ORDER BY suppliers DESC LIMIT 5" \
  python3 ./scripts/loadtest/loadtest.py \
    --url grpc+tls://localhost:31338 --insecure \
    --user admin --password admin \
    -w 1 -i 1 --warmup 0
```

Or connect any FlightSQL-aware JDBC/ODBC/ADBC client. The default `.env`
turns TLS **on** with an auto-issued self-signed cert, so every example
below disables cert verification (drop those flags once you mount a real
cert).

### JDBC

Apache Arrow Flight SQL JDBC driver — `org.apache.arrow:flight-sql-jdbc-driver`
on Maven Central. URL:

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&user=admin&password=admin&tenant=tpch&pool=sales
```

The trailing `tenant=…&pool=…` is what the FlightSQL edge uses to route
the connection at handshake time. The JDBC driver forwards every URL
query parameter as a gRPC header, so the same `tenant` / `pool` headers
the loadtest sets via call-headers also reach the server here.

Driver class: `org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`. Works
out of the box in DBeaver (paste the URL in the connection wizard's "JDBC
URL" field, set the Driver to "Apache Arrow Flight SQL").

To run against the plaintext profile (`TLS=false` in `.env`):

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=false&user=admin&password=admin&tenant=tpch&pool=sales
```

### ODBC

Apache Arrow Flight SQL ODBC driver (from
[arrow-adbc](https://arrow.apache.org/adbc/main/driver/flight_sql.html#odbc)
or the Dremio Flight SQL connector). Register the driver in
`/etc/odbcinst.ini` (Linux/macOS):

```ini
[Apache Arrow Flight SQL ODBC Driver]
Description = Apache Arrow Flight SQL
Driver      = /usr/local/lib/libarrow-odbc.so
```

DSN-less connection string:

```
Driver={Apache Arrow Flight SQL ODBC Driver};
HOST=localhost;PORT=31338;
UseEncryption=true;DisableCertificateVerification=true;
UID=admin;PWD=admin;
adbc.flight.sql.rpc.call_header.tenant=tpch;
adbc.flight.sql.rpc.call_header.pool=sales
```

The two `adbc.flight.sql.rpc.call_header.*` entries thread the routing
headers through the underlying ADBC driver — same role as the
`tenant=…&pool=…` URL parameters on the JDBC side.

Smoke test with `isql`:

```bash
echo "SELECT count(*) FROM tpch1.lineitem" | isql -v "Driver=Apache Arrow Flight SQL ODBC Driver;HOST=localhost;PORT=31338;UseEncryption=true;DisableCertificateVerification=true;UID=admin;PWD=admin;adbc.flight.sql.rpc.call_header.tenant=tpch;adbc.flight.sql.rpc.call_header.pool=sales"
```

### ADBC (Python)

Already wired by the loadtest above. For your own scripts:

```python
import adbc_driver_flightsql.dbapi as flight_sql
from adbc_driver_flightsql import DatabaseOptions

hdr = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
conn = flight_sql.connect(
    uri="grpc+tls://localhost:31338",
    db_kwargs={
        "username": "admin",
        "password": "admin",
        DatabaseOptions.TLS_SKIP_VERIFY.value: "true",
        hdr + "tenant": "tpch",     # routes to the bootstrap tenant
        hdr + "pool":   "sales",    # ... and its sales pool
    },
)
cur = conn.cursor()
cur.execute("SELECT count(*) FROM tpch1.lineitem")
print(cur.fetchone())   # -> (6001215,)
```

## 5. Opt-in profiles (optional)

The compose file has two profiles you can toggle independently. Activate them
via the `PROFILES` env var on `run-docker-compose.sh`:

- **`observability`** — Prometheus + Grafana with a provisioned
  `quack-on-demand` dashboard.
- **`seaweedfs`** — in-cluster SeaweedFS S3 store + filer UI for an
  object-storage-backed catalog. Activates automatically when `.env`
  sets `QOD_S3_ENDPOINT=seaweedfs:8333` (uncomment lines 81-87 of
  `.env.example` for the full block).

```bash
# Everything on (TPC-H seeded + observability + SeaweedFS-backed storage):
NUKE=1 LOAD_TPCH=1 PROFILES=observability \
  ./scripts/run-docker-compose.sh   # seaweedfs auto-activates if .env wires it
```

### URLs exposed by each profile

The post-boot banner prints these too, but for reference:

| Profile | URL | Purpose |
|---|---|---|
| (always) | <http://localhost:20900/ui/> | Manager admin UI (login `admin` / `admin`) |
| (always) | `grpc+tls://localhost:31338` | FlightSQL edge (use `--insecure` to skip cert verify) |
| (always) | `localhost:15432` | Postgres (host port; container-internal is `postgres:5432`) |
| `observability` | <http://localhost:3000/> | Grafana — anonymous admin, `quack-on-demand` dashboard pre-loaded |
| `observability` | <http://localhost:9090/> | Prometheus — query the `quack-on-demand` scrape target |
| `seaweedfs` | <http://localhost:8888/buckets/> | Filer file browser (drill into `/buckets/ducklake/tpch/tpch1/`) |
| `seaweedfs` | <http://localhost:9333/> | SeaweedFS master / cluster status |
| `seaweedfs` | <http://localhost:8080/ui/> | Volume-server UI |
| `seaweedfs` | `http://localhost:8333/` | S3 API endpoint — works with `aws s3 ls`, `s5cmd`, `mc`. Default creds: `quack` / `quackquack` |

## 6. Stop the stack — 10 seconds

```bash
docker compose --profile seaweedfs --profile observability down
```

(`--profile` flags are needed only if you opted into those profiles. The
post-boot banner's `stop with:` line spells out the right command for your
current set.)

To wipe all state (Postgres data, parquet, certs, SeaweedFS) for a clean
restart, add `NUKE=1` to a fresh `./scripts/run-docker-compose.sh`.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Connection refused` to `:20900` | Manager still booting (TPC-H seed takes ~10 s) | Wait and retry |
| `[FlightSQL] no node with role READONLY or DUAL` | Quack nodes still spawning | Wait 5–10 s and retry |
| `Cannot open file ".../lineitem/ducklake-...parquet"` | Stale catalog (Postgres has metadata, parquet dir was wiped separately) | `NUKE=1 LOAD_TPCH=1 ./scripts/run-docker-compose.sh` for a clean slate. The loader self-detects this and aborts loudly. |
| `Authentication failed` | Wrong `admin` password — `.env` differs from default | Check `ADMIN_PASSWORD` in `.env` |
| `error reading server preface: EOF` from loadtest | Client URL is `grpc://` but `.env` has `TLS=true` | Use `grpc+tls://localhost:31338 --insecure` |
| `[FlightSQL] missing tenant scope for Basic auth: 'tenant' header required` | Client connected without routing headers (custom JDBC/ODBC/ADBC code) | Add `?tenant=tpch&pool=sales` to the JDBC URL, `adbc.flight.sql.rpc.call_header.tenant/pool` to ODBC/ADBC db_kwargs. The loadtest threads these by default. |
| `DATA_PATH parameter "…" does not match existing data path in the catalog "s3://…"` | Previous run used the SeaweedFS profile and recorded an `s3://` path; this run is back to filesystem | `NUKE=1 LOAD_TPCH=1 ./scripts/run-docker-compose.sh` to wipe `./pgdata` + `./ducklake` + `./seaweedfs` and re-seed |
| SeaweedFS filer `/buckets/<bucket>/` is empty after seed | `.env` doesn't wire `QOD_S3_ENDPOINT=seaweedfs:8333`, so the manager wrote parquet to the host FS instead | Uncomment the SeaweedFS block in `.env` (the Option A block in `.env.example`) and `NUKE=1 LOAD_TPCH=1 ./scripts/run-docker-compose.sh` |

## Next steps

- **[RUNNING.md](RUNNING.md)** — every deployment path (native jar, Docker
  against an external Postgres, S3-backed storage, the kind/Helm chart, the
  loadtest parameter table)
- **[README.md](README.md)** — architecture overview, feature list
- **[skills/quack-on-demand/SKILL.md](skills/quack-on-demand/SKILL.md)** —
  operator runbook: REST API recipes, pool/tenant/ACL CRUD, failure modes