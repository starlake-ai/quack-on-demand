# TypeScript FlightSQL example

A minimal TypeScript client that runs a SQL query against the Quack-on-Demand
FlightSQL edge and prints the Arrow result.

Node has no first-party Flight SQL driver, so this talks to the edge over raw
gRPC ([@grpc/grpc-js](https://www.npmjs.com/package/@grpc/grpc-js)) using a
minimal slice of the Flight protocol (`src/proto/`), then decodes the Arrow
result stream with [apache-arrow](https://www.npmjs.com/package/apache-arrow).

## What it does

1. `GetFlightInfo` with a `FlightDescriptor` whose `cmd` is an `Any`-wrapped
   `CommandStatementQuery`. The edge returns a `FlightInfo` with one or more
   endpoints, each carrying an opaque ticket.
2. `DoGet(ticket)` for each endpoint, which streams `FlightData` chunks (an
   Arrow IPC message header plus its body).
3. The chunks are reassembled into a standard Arrow IPC stream and decoded into
   a table.

The edge authenticates every RPC from the call headers (`tenant`, `pool`,
`authorization: Basic <base64>`), so there is no separate handshake step.

## Run

Always set `QOD_HOST` to point at your edge (`127.0.0.1` for a local one, or the
remote host/IP otherwise). The commands below use the macOS/Linux shell syntax;
for Windows see [Windows](#windows).

```bash
npm install
QOD_HOST=127.0.0.1 npm run query
```

The defaults connect as the superuser `admin` against the `acme` tenant's `bi`
pool and run a self-contained query that needs no loaded data. Pass your own SQL
as an argument:

```bash
QOD_HOST=127.0.0.1 npm run query -- "SELECT count(*) FROM tpch1.customer"
```

### TPC-H benchmark

`npm run tpch` runs the 22 standard TPC-H queries (with the spec's validation
substitution parameters) against the `tpch1` schema and reports the row count,
latency, and a first-row preview for each:

```bash
QOD_HOST=127.0.0.1 npm run tpch            # all 22 queries
QOD_HOST=127.0.0.1 npm run tpch -- 1 6 14  # just queries 1, 6 and 14 (by id)
```

Set `QOD_TPCH_SCHEMA` to target a different schema. The queries live in
`src/tpch-queries.ts`.

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

By default `QOD_TLS_VERIFY=false`: the example pins the edge's auto-generated
self-signed certificate off the wire and skips the hostname check. Set
`QOD_TLS_VERIFY=true` once a CA-signed certificate is installed on the edge.

## Connecting as a non-superuser

Drop the superuser flag and authenticate as a tenant user (the per-statement
ACL gate then applies, so the user must be granted access to the tables):

```bash
QOD_HOST=127.0.0.1 QOD_SUPERUSER=false QOD_USER=alice QOD_PASSWORD=demo-alice \
  npm run query -- "SELECT count(*) FROM tpch1.customer"
```

## Windows

The example itself is cross-platform; only the way you set environment
variables differs from the macOS/Linux examples above. Node 18+ is required
(install from [nodejs.org](https://nodejs.org/) or `winget install OpenJS.NodeJS`).

### PowerShell

Set each variable with `$env:NAME = "value"` before the `npm` command. The `--`
separator still passes the SQL through to the script:

```powershell
npm install

# Single query (default demo SQL)
$env:QOD_HOST = "127.0.0.1"; npm run query

# Single query with your own SQL
$env:QOD_HOST = "127.0.0.1"; npm run query -- "SELECT count(*) FROM tpch1.customer"

# All 22 TPC-H queries
$env:QOD_HOST = "127.0.0.1"; npm run tpch

# A subset of TPC-H queries by id
$env:QOD_HOST = "127.0.0.1"; npm run tpch -- 1 6 14

# Connect as a non-superuser tenant user
$env:QOD_HOST = "127.0.0.1"; $env:QOD_SUPERUSER = "false"; $env:QOD_USER = "alice"; $env:QOD_PASSWORD = "demo-alice"; npm run query -- "SELECT count(*) FROM tpch1.customer"
```

`$env:` assignments persist for the rest of the PowerShell session, so once set
you can drop them from subsequent commands. To clear one: `Remove-Item Env:\QOD_HOST`.

### Command Prompt (cmd.exe)

Use `set NAME=value` (no spaces around `=`) joined with `&&`:

```bat
npm install

REM Single query (default demo SQL)
set QOD_HOST=127.0.0.1 && npm run query

REM Single query with your own SQL
set QOD_HOST=127.0.0.1 && npm run query -- "SELECT count(*) FROM tpch1.customer"

REM All 22 TPC-H queries
set QOD_HOST=127.0.0.1 && npm run tpch

REM A subset of TPC-H queries by id
set QOD_HOST=127.0.0.1 && npm run tpch -- 1 6 14

REM Connect as a non-superuser tenant user
set QOD_HOST=127.0.0.1 && set QOD_SUPERUSER=false && set QOD_USER=alice && set QOD_PASSWORD=demo-alice && npm run query -- "SELECT count(*) FROM tpch1.customer"
```