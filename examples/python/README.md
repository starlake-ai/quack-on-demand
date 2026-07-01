# Python FlightSQL example

A minimal Python client that runs SQL against the Quack-on-Demand FlightSQL edge
and prints the Arrow result.

Python has the Arrow [ADBC](https://arrow.apache.org/adbc/) Flight SQL driver, so
this is a thin wrapper over `adbc_driver_flightsql`: no hand-rolled gRPC or Arrow
IPC decoding (contrast the TypeScript example, which does that because Node has
no first-party driver).

## What it does

1. Connects with `adbc_driver_flightsql.dbapi.connect`, carrying the Basic
   credential as `username` / `password` and `tenant` / `pool` / `superuser` as
   per-RPC call headers under `RPC_CALL_HEADER_PREFIX`.
2. `cursor.execute(sql)` then `cursor.fetch_arrow_table()` returns a
   `pyarrow.Table`.

The edge authenticates every RPC from those call headers, so there is no
separate handshake step.

## Run

Requires Python 3.9+. Always set `QOD_HOST` to point at your edge (`127.0.0.1`
for a local one, or the remote host/IP otherwise).

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
QOD_HOST=127.0.0.1 python query.py
```

The defaults connect as the superuser `admin` against the `acme` tenant's `bi`
pool and run a self-contained query that needs no loaded data. Pass your own SQL
as an argument:

```bash
QOD_HOST=127.0.0.1 python query.py "SELECT count(*) FROM tpch1.customer"
```

### TPC-H benchmark

`python tpch.py` runs the 22 standard TPC-H queries (with the spec's validation
substitution parameters) against the `tpch1` schema and reports the row count,
latency, and a first-row preview for each:

```bash
QOD_HOST=127.0.0.1 python tpch.py            # all 22 queries
QOD_HOST=127.0.0.1 python tpch.py 1 6 14     # just queries 1, 6 and 14 (by id)
```

Set `QOD_TPCH_SCHEMA` to target a different schema. The queries live in
`tpch_queries.py`.

## Configuration

Every setting has an environment-variable override:

| Variable          | Default     | Purpose                                                |
| ----------------- | ----------- | ------------------------------------------------------ |
| `QOD_HOST`        | `127.0.0.1` | Edge host                                              |
| `QOD_PORT`        | `31338`     | Edge FlightSQL port                                    |
| `QOD_USER`        | `admin`     | Basic-auth username                                    |
| `QOD_PASSWORD`    | `admin`     | Basic-auth password                                    |
| `QOD_TENANT`      | `acme`      | Tenant (routing)                                       |
| `QOD_POOL`        | `bi`        | Pool within the tenant (routing)                       |
| `QOD_SUPERUSER`   | `true`      | Send `superuser: true` (system realm, ACL bypass)      |
| `QOD_TLS`         | `true`      | `false` connects in plaintext (`grpc://`)              |
| `QOD_TLS_VERIFY`  | `false`     | `true` validates the cert chain against system trust   |
| `QOD_SQL`         | demo query  | SQL to run (the CLI argument takes precedence)         |
| `QOD_TPCH_SCHEMA` | `tpch1`     | Schema the TPC-H runner targets                        |

By default `QOD_TLS_VERIFY=false`: the driver skips the certificate check so the
edge's auto-generated self-signed certificate is accepted. Set
`QOD_TLS_VERIFY=true` once a CA-signed certificate is installed on the edge.

## Connecting as a non-superuser

Drop the superuser flag and authenticate as a tenant user (the per-statement ACL
gate then applies, so the user must be granted access to the tables):

```bash
QOD_HOST=127.0.0.1 QOD_SUPERUSER=false QOD_USER=alice QOD_PASSWORD=demo-alice \
  python query.py "SELECT count(*) FROM tpch1.customer"
```

## Windows

The example itself is cross-platform; only the way you set environment variables
differs. In **PowerShell** set each with `$env:NAME = "value"` before the
command:

```powershell
python -m venv .venv; .venv\Scripts\Activate.ps1
pip install -r requirements.txt
$env:QOD_HOST = "127.0.0.1"; python query.py
$env:QOD_HOST = "127.0.0.1"; python tpch.py 1 6 14
```

In **Command Prompt** use `set NAME=value` (no spaces around `=`) joined with
`&&`:

```bat
set QOD_HOST=127.0.0.1 && python query.py
set QOD_HOST=127.0.0.1 && python tpch.py 1 6 14
```