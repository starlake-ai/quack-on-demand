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
(~6M lineitem rows, ~10 s on a modern laptop), and waits until `/health`
returns OK. Expected tail:

```
manager /health: {"status":"ok","poolsCount":1,"nodesCount":3}
TPC-H SF=1 seed complete
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
Queries OK:       1
Queries failed:   0
Throughput:       ~25 qps
Latency  p50:     ~40 ms
```

One TPC-H query (default mix) went: ADBC FlightSQL client → TLS-off gateway on
`:31338` → authenticated as `admin/admin` against Postgres-backed users →
routed to a `DUAL` Quack node → DuckLake-resolved against `tpch.tpch1.*`
parquet → Arrow result streamed back. Every layer the gateway adds was
exercised in that one round-trip.

## 3. Browse the admin UI (optional) — 10 seconds

Open <http://localhost:20900/ui/> in a browser. Log in as `admin` /
`admin`. You can see the bootstrap pool (`acme/sales`), its three Quack
nodes (`WO`, `RO`, `DUAL`), per-node load and latency, and the statement
history of the query you just ran.

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
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&user=admin&password=admin
```

Driver class: `org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`. Works
out of the box in DBeaver (paste the URL in the connection wizard's "JDBC
URL" field, set the Driver to "Apache Arrow Flight SQL").

To run against the plaintext profile (`TLS=false` in `.env`):

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=false&user=admin&password=admin
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
UID=admin;PWD=admin
```

Smoke test with `isql`:

```bash
echo "SELECT count(*) FROM tpch1.lineitem" | isql -v "Driver=Apache Arrow Flight SQL ODBC Driver;HOST=localhost;PORT=31338;UseEncryption=true;DisableCertificateVerification=true;UID=admin;PWD=admin"
```

### ADBC (Python)

Already wired by the loadtest above. For your own scripts:

```python
import adbc_driver_flightsql.dbapi as flight_sql
from adbc_driver_flightsql import DatabaseOptions

conn = flight_sql.connect(
    uri="grpc+tls://localhost:31338",
    db_kwargs={
        "username": "admin",
        "password": "admin",
        DatabaseOptions.TLS_SKIP_VERIFY.value: "true",
    },
)
cur = conn.cursor()
cur.execute("SELECT count(*) FROM tpch1.lineitem")
print(cur.fetchone())   # -> (6001215,)
```

## 5. Stop the stack — 10 seconds

```bash
docker compose down
```

To wipe all state (Postgres data, parquet, certs) for a clean restart next
time, add the `NUKE=1` flag to a fresh `run-docker-compose.sh` invocation.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Connection refused` to `:20900` | Manager pod still booting (TPC-H seed takes ~10 s) | Wait and retry |
| `[FlightSQL] no node with role READONLY or DUAL` | Quack nodes still spawning | Wait 5–10 s and retry |
| `Cannot open file ".../lineitem/ducklake-...parquet"` | Stale catalog (Postgres has metadata, parquet dir was wiped separately) | `NUKE=1 LOAD_TPCH=1 ./scripts/run-docker-compose.sh` for a clean slate |
| `Authentication failed` | Wrong `admin` password — `.env` differs from default | Check `ADMIN_PASSWORD` in `.env` |

## Next steps

- **[RUNNING.md](RUNNING.md)** — every deployment path (native jar, Docker
  against an external Postgres, S3-backed storage, the kind/Helm chart, the
  loadtest parameter table)
- **[README.md](README.md)** — architecture overview, feature list
- **[skills/quack-on-demand/SKILL.md](skills/quack-on-demand/SKILL.md)** —
  operator runbook: REST API recipes, pool/tenant/ACL CRUD, failure modes