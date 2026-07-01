# Java FlightSQL example

A minimal Java client that runs SQL against the Quack-on-Demand FlightSQL edge
and prints the result.

Java has the Arrow [Flight SQL JDBC driver](https://arrow.apache.org/docs/java/flight_sql_jdbc_driver.html),
so this is a thin wrapper over `java.sql`: no hand-rolled gRPC or Arrow IPC
decoding (contrast the TypeScript example, which does that because Node has no
first-party driver).

## What it does

1. Builds a `jdbc:arrow-flight-sql://host:port?...` URL from the `QOD_*` env
   vars. `user` / `password` are the Basic credential; the driver forwards the
   `tenant` / `pool` / `superuser` parameters to the edge as gRPC call headers.
2. Runs the statement over a standard JDBC `Connection` / `Statement` and reads
   the `ResultSet`.

The edge authenticates every RPC from those call headers, so there is no
separate handshake step.

## Prerequisites

- **JDK 17+** (`java -version`).
- **Maven 3.8+** (`mvn -version`).

The Arrow allocator reflects into `java.nio` internals on Java 17+, so the JVM
needs `--add-opens=java.base/java.nio=ALL-UNNAMED`. This project ships that in
[`.mvn/jvm.config`](.mvn/jvm.config), so `exec:java` (which runs in Maven's JVM)
picks it up automatically. No extra flags needed.

## Run

Always set `QOD_HOST` to point at your edge (`127.0.0.1` for a local one, or the
remote host/IP otherwise).

```bash
QOD_HOST=127.0.0.1 mvn -q compile exec:java
```

The defaults connect as the superuser `admin` against the `acme` tenant's `bi`
pool and run a self-contained query that needs no loaded data. Pass your own SQL
via `-Dexec.args` (quote it as one argument):

```bash
QOD_HOST=127.0.0.1 mvn -q compile exec:java \
  -Dexec.args="SELECT count(*) FROM tpch1.customer"
```

### TPC-H benchmark

Switch the main class to `Tpch` to run the 22 standard TPC-H queries (with the
spec's validation substitution parameters) against the `tpch1` schema, reporting
the row count, latency, and a first-row preview for each:

```bash
# all 22 queries
QOD_HOST=127.0.0.1 mvn -q compile exec:java \
  -Dqod.mainClass=ai.starlake.quack.example.Tpch

# just queries 1, 6 and 14 (by id)
QOD_HOST=127.0.0.1 mvn -q compile exec:java \
  -Dqod.mainClass=ai.starlake.quack.example.Tpch -Dexec.args="1 6 14"
```

Set `QOD_TPCH_SCHEMA` to target a different schema. The queries live in
[`TpchQueries.java`](src/main/java/ai/starlake/quack/example/TpchQueries.java).

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
| `QOD_SQL`         | demo query  | SQL to run (the `-Dexec.args` value takes precedence)  |
| `QOD_TPCH_SCHEMA` | `tpch1`     | Schema the TPC-H runner targets                        |

By default `QOD_TLS_VERIFY=false` maps to the driver's
`disableCertificateVerification=true`, so the edge's auto-generated self-signed
certificate is accepted. Set `QOD_TLS_VERIFY=true` once a CA-signed certificate
is installed on the edge.

## Connecting as a non-superuser

Drop the superuser flag and authenticate as a tenant user (the per-statement ACL
gate then applies, so the user must be granted access to the tables):

```bash
QOD_HOST=127.0.0.1 QOD_SUPERUSER=false QOD_USER=alice QOD_PASSWORD=demo-alice \
  mvn -q compile exec:java -Dexec.args="SELECT count(*) FROM tpch1.customer"
```

## Windows

The example itself is cross-platform; only the way you set environment variables
differs. In **PowerShell** set each with `$env:NAME = "value"` before the
command:

```powershell
$env:QOD_HOST = "127.0.0.1"; mvn -q compile exec:java
$env:QOD_HOST = "127.0.0.1"; mvn -q compile exec:java -Dqod.mainClass=ai.starlake.quack.example.Tpch -Dexec.args="1 6 14"
```

In **Command Prompt** use `set NAME=value` (no spaces around `=`) joined with
`&&`:

```bat
set QOD_HOST=127.0.0.1 && mvn -q compile exec:java
```