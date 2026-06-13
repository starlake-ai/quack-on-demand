---
id: clients
title: Connecting clients
---

Clients talk to the FlightSQL edge (default `:31338`), not the REST API. Any Arrow Flight SQL driver works: the JDBC driver (DBeaver, Spark, any JDBC tool), ADBC (Python, Go, and others), or a third-party Flight SQL ODBC driver. This page covers the connection target and per-client recipes; see [Authenticating](/connecting/authenticating) for credentials and TLS, and [Supported SQL](/connecting/sql) for what you can run.

## The connection target

A connection needs four things, because the edge applies **no defaults**: every client must fully address its target.

| What | Value |
|---|---|
| Endpoint | `host:31338` (the FlightSQL edge port) |
| Transport | `grpc+tls://` when edge TLS is on (the default), `grpc://` when off; JDBC uses `jdbc:arrow-flight-sql://` |
| `tenant` + `pool` | Required on every connection. The owning database (tenant-db) is resolved server-side. |
| User credential | A username/password (Basic) or a bearer JWT. |

How `tenant` and `pool` are carried depends on the driver, but the names are always plain `tenant` and `pool` (not `X-`-prefixed):

- **JDBC**: URL query parameters, e.g. `?tenant=acme&pool=bi`.
- **ADBC**: per-RPC call headers via the driver's call-header option.
- **Raw Flight**: gRPC headers `tenant` and `pool`.

### Superusers

Connections authenticated against the system realm (a row in `qodstate_user` with `tenant IS NULL`) add `superuser=true` **alongside** the existing `tenant` and `pool` parameters. The `tenant` and `pool` parameters still drive query routing; the `superuser=true` flag only picks which realm validates the credential and whether the per-statement ACL gate fires.

- **JDBC**: `?tenant=acme&pool=bi&superuser=true`.
- **ADBC**: send the call header `superuser` with value `true` alongside the existing `tenant` and `pool` call headers.
- **Raw Flight**: gRPC header `superuser: true` plus the regular `tenant` and `pool` headers.

A superuser can target any tenant's pool for routing while authenticating against the manager's global auth providers; the per-statement ACL gate is bypassed and the session can reach any catalog. See [Authentication](/operating/authentication) for the realm split.

Because the edge ships with an auto-generated self-signed certificate, the recipes below skip certificate verification. To remove that, install a CA-signed cert as described on the [TLS](/operating/tls) page.

## DBeaver and JDBC

Install the Apache Arrow Flight SQL JDBC driver (`org.apache.arrow:flight-sql-jdbc-driver`, on Maven Central) and set the driver class to `org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`. The entire connection is expressed in the URL:

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&user=alice&password=demo-alice&tenant=acme&pool=bi
```

- `useEncryption=true` matches edge TLS being on; `disableCertificateVerification=true` accepts the self-signed cert.
- `user` / `password` are the edge credential.
- `tenant` / `pool` route the connection.

The URL parameters QoD reads:

| Parameter | Required | Values | Default | Purpose |
|---|---|---|---|---|
| `user` | Yes (Basic) | string | none | Username for Basic auth. Omit when using a bearer JWT via the driver's token option. |
| `password` | Yes (Basic) | string | none | Password for Basic auth. |
| `tenant` | Yes | display name or surrogate id (`t-<8 hex>`) | none | Selects the tenant. Surrogate ids are normalized to the display name server-side. |
| `pool` | Yes | string | none | Pool name within the tenant. Pool names are unique per tenant; the owning tenant-db is resolved server-side. |
| `superuser` | No | `true` (case-insensitive); anything else is false | `false` | Picks the system realm instead of the tenant realm. `tenant` and `pool` still drive query routing. |
| `useEncryption` | No | `true` / `false` | `false` | JDBC driver flag. Set `true` when edge TLS is on (the default for QoD), `false` when off. |
| `disableCertificateVerification` | No | `true` / `false` | `false` | JDBC driver flag. `true` accepts the auto-generated self-signed cert; remove once a CA-signed cert is installed. |

Any other parameter is a driver-level option (see the Arrow Flight SQL JDBC driver docs) and is not interpreted by the edge.

A system-realm superuser adds `superuser=true` to the same URL (the `tenant` and `pool` params stay in place because they still drive query routing):

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&user=root&password=demo-root&tenant=acme&pool=bi&superuser=true
```

Any JDBC-based tool uses the same driver and URL. For **Spark**, point the JDBC data source at this URL with the same driver class; each Spark task opens its own Flight SQL connection.

## Python (ADBC / PyArrow)

```bash
pip install adbc_driver_flightsql adbc_driver_manager
```

```python
import adbc_driver_flightsql.dbapi as flight_sql
from adbc_driver_flightsql import DatabaseOptions

hdr = DatabaseOptions.RPC_CALL_HEADER_PREFIX.value
conn = flight_sql.connect(
    uri="grpc+tls://localhost:31338",
    db_kwargs={
        "username": "alice",
        "password": "demo-alice",
        DatabaseOptions.TLS_SKIP_VERIFY.value: "true",  # self-signed cert
        hdr + "tenant": "acme",
        hdr + "pool": "bi",
    },
)
cur = conn.cursor()
cur.execute("SELECT count(*) FROM tpch1.customer")
print(cur.fetchall())
```

The `tenant` and `pool` are passed as per-RPC call headers through `RPC_CALL_HEADER_PREFIX`. A superuser keeps both headers in place and adds `hdr + "superuser": "true"`. The bundled `scripts/loadtest/loadtest.py` is a ready-made ADBC client built exactly this way; pass `--superuser` (or `LT_SUPERUSER=true`) when running it as the bootstrap `admin` user so the same gRPC header is forwarded. The runner cycles a curated TPC-H mix by default (`--workload tpch`, schema `tpch1`); pass `--workload tpcds` (schema `tpcds1`) to drive the TPC-DS benchmark against the `globex_tpcds` tenant-db seeded by `scripts/load-tpcds-dbgen.sh`.

## ODBC

The project ships no ODBC driver, but any Arrow Flight SQL ODBC driver connects with the same parameters: the `grpc+tls://host:31338` endpoint, a username/password, the skip-verify option for the self-signed cert, and the `tenant` / `pool` call headers (plus `superuser=true` alongside `tenant` + `pool` for a system-realm login).

### Sample DSN

Below is a sample `odbc.ini` entry for the Dremio / Apache Arrow Flight SQL ODBC driver. Adjust the `Driver=` path and exact key names to match your driver's documentation; the *values* (host, port, encryption, call headers) are what QoD cares about.

```ini
[QoD]
Description       = Quack-on-Demand FlightSQL edge
Driver            = /opt/dremio/lib/libarrow-odbc.so   ; Windows: ArrowFlightSQLODBCDriver
HOST              = localhost
PORT              = 31338
useEncryption     = true                               ; edge TLS is on by default
disableCertificateVerification = true                  ; accepts the auto-generated self-signed cert
UID               = alice
PWD               = demo-alice
; tenant / pool / superuser are forwarded to the edge as gRPC call headers.
; Most Flight SQL ODBC drivers expose this as either a "RPCCallHeaders" /
; "CallHeaders" semicolon-separated list or as individual "header.<name>"
; keys. Pick whichever your driver supports:
RPCCallHeaders    = tenant=acme;pool=bi
; header.tenant   = acme
; header.pool     = bi
; header.superuser = true                              ; only for a system-realm login
```

And as a DSN-less connection string (same fields, one line):

```
Driver={Arrow Flight SQL ODBC Driver};HOST=localhost;PORT=31338;useEncryption=true;disableCertificateVerification=true;UID=alice;PWD=demo-alice;RPCCallHeaders=tenant=acme;pool=bi
```

Once a CA-signed cert is installed, drop `disableCertificateVerification=true`. For a bearer JWT instead of Basic, drop `UID`/`PWD` and use the driver's bearer-token option (the key varies; commonly `Token` or `BearerToken`).

## Next

- [Authenticating](/connecting/authenticating) - Basic vs JWT, and TLS.
- [Supported SQL](/connecting/sql) - the dialect, transactions, and querying federated catalogs.
