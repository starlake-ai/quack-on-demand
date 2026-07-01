# Rust FlightSQL example

A minimal Rust client that runs SQL against the Quack-on-Demand FlightSQL edge
and prints the Arrow result.

Rust has [`arrow-flight`](https://crates.io/crates/arrow-flight)'s
`FlightSqlServiceClient`, so this is a thin wrapper over `tonic`: no hand-rolled
gRPC or Arrow IPC decoding (contrast the TypeScript example, which does that
because Node has no first-party driver).

## What it does

1. Builds a `tonic` channel honoring the `QOD_*` TLS settings, then a
   `FlightSqlServiceClient` and sets the `authorization` (Basic), `tenant`,
   `pool`, and `superuser` call headers with `set_header`.
2. `execute(sql)` returns a `FlightInfo`; `do_get(ticket)` for each endpoint
   streams the Arrow `RecordBatch`es, which are collected into a result.

The edge authenticates every RPC from those call headers, so there is no
separate handshake step.

## Prerequisites

- **Rust 1.85+** (`rustc --version`). The dependency tree uses crates published
  for the 2024 edition, which needs a 1.85-or-newer toolchain; `rustup update
  stable` if yours is older.

## Run

Always set `QOD_HOST` to point at your edge (`127.0.0.1` for a local one, or the
remote host/IP otherwise).

```bash
QOD_HOST=127.0.0.1 cargo run --bin query
```

The defaults connect as the superuser `admin` against the `acme` tenant's `bi`
pool and run a self-contained query that needs no loaded data. Pass your own SQL
as an argument (after `--`):

```bash
QOD_HOST=127.0.0.1 cargo run --bin query -- "SELECT count(*) FROM tpch1.customer"
```

### TPC-H benchmark

The `tpch` binary runs the 22 standard TPC-H queries (with the spec's validation
substitution parameters) against the `tpch1` schema and reports the row count,
latency, and a first-row preview for each:

```bash
QOD_HOST=127.0.0.1 cargo run --bin tpch            # all 22 queries
QOD_HOST=127.0.0.1 cargo run --bin tpch -- 1 6 14  # just queries 1, 6 and 14 (by id)
```

Set `QOD_TPCH_SCHEMA` to target a different schema. The queries live in
[`src/tpch_queries.rs`](src/tpch_queries.rs).

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

By default `QOD_TLS_VERIFY=false`: the client accepts any server certificate
(the TLS certificate verifier is bypassed) so the edge's auto-generated
self-signed certificate is accepted. Set `QOD_TLS_VERIFY=true` once a CA-signed
certificate is installed on the edge; the chain is then validated against the
system trust store.

## Connecting as a non-superuser

Drop the superuser flag and authenticate as a tenant user (the per-statement ACL
gate then applies, so the user must be granted access to the tables):

```bash
QOD_HOST=127.0.0.1 QOD_SUPERUSER=false QOD_USER=alice QOD_PASSWORD=demo-alice \
  cargo run --bin query -- "SELECT count(*) FROM tpch1.customer"
```

## Windows

The example itself is cross-platform; only the way you set environment variables
differs. In **PowerShell** set each with `$env:NAME = "value"` before the
command:

```powershell
$env:QOD_HOST = "127.0.0.1"; cargo run --bin query
$env:QOD_HOST = "127.0.0.1"; cargo run --bin tpch -- 1 6 14
```

In **Command Prompt** use `set NAME=value` (no spaces around `=`) joined with
`&&`:

```bat
set QOD_HOST=127.0.0.1 && cargo run --bin query
set QOD_HOST=127.0.0.1 && cargo run --bin tpch -- 1 6 14
```