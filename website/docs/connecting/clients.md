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

- **JDBC**: URL query parameters, e.g. `?tenant=tpch&pool=sales`.
- **ADBC**: per-RPC call headers via the driver's call-header option.
- **Raw Flight**: gRPC headers `tenant` and `pool`.

Because the edge ships with an auto-generated self-signed certificate, the recipes below skip certificate verification. To remove that, install a CA-signed cert as described on the [TLS](/operating/tls) page.

## DBeaver and JDBC

Install the Apache Arrow Flight SQL JDBC driver (`org.apache.arrow:flight-sql-jdbc-driver`, on Maven Central) and set the driver class to `org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`. The entire connection is expressed in the URL:

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&user=admin&password=admin&tenant=tpch&pool=sales
```

- `useEncryption=true` matches edge TLS being on; `disableCertificateVerification=true` accepts the self-signed cert.
- `user` / `password` are the edge credential.
- `tenant` / `pool` route the connection.

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
        "username": "admin",
        "password": "admin",
        DatabaseOptions.TLS_SKIP_VERIFY.value: "true",  # self-signed cert
        hdr + "tenant": "tpch",
        hdr + "pool": "sales",
    },
)
cur = conn.cursor()
cur.execute("SELECT count(*) FROM tpch1.customer")
print(cur.fetchall())
```

The `tenant` and `pool` are passed as per-RPC call headers through `RPC_CALL_HEADER_PREFIX`. The bundled `scripts/loadtest/loadtest.py` is a ready-made ADBC client built exactly this way.

## ODBC

The project ships no ODBC driver, but any Arrow Flight SQL ODBC driver connects with the same parameters: the `grpc+tls://host:31338` endpoint, a username/password, the skip-verify option for the self-signed cert, and the `tenant` / `pool` call headers. Configure them through the driver's DSN settings.

## Next

- [Authenticating](/connecting/authenticating) - Basic vs JWT, and TLS.
- [Supported SQL](/connecting/sql) - the dialect, transactions, and querying federated catalogs.
