# qod-node spike

Throwaway prototype for the in-engine authorization redesign. It answers the feasibility questions
before the real `qod-node` is built; none of this code is the product.

`qod-node` here is a small host executable linked against the official, unmodified DuckDB release
libduckdb. It registers DuckDB's `post_bind` and `pre_optimize` hooks before any SQL runs, executes
init SQL statement by statement, and serves over quack. The hooks only print what each bound plan
resolves to (tables and columns read or written, functions, secure-view boundaries) and which kind
of connection runs it; nothing is decided or enforced. `screen_probe` registers a function to install
as quack's per-PREPARE authorization hook and records what it receives.

## Run it locally

`DUCKDB_HOME` must hold the release `lib/libduckdb` and the full `src/include` tree of the same tag;
the `.duckdb/<version>/` cache that the qod CLI and `run-jar.sh` provision has both.

```bash
cmake -S native/qod-node -B native/qod-node/build -DDUCKDB_HOME=<.duckdb/2.0.0 cache>
cmake --build native/qod-node/build
native/qod-node/spike/check-smoke.sh "$PWD/native/qod-node/build/qod-node" /tmp/qod-node-smoke
DUCKDB_CLI=<release duckdb CLI> native/qod-node/spike/run-spike.sh /tmp/qod-node-run 23456
```

`check-smoke.sh` needs no quack client; `run-spike.sh` serves a DuckLake table, a secure view, a table
macro and a plain table, queries them through quack with the release CLI, and prints the node log.
CI runs both on the six platforms: `.github/workflows/qod-node-spike.yml`.

## Results so far (macOS arm64, DuckDB v2.0.0)

- Links and runs against the release libduckdb; it exports the C++ symbols the hooks use.
- quack, ducklake, postgres, httpfs and iceberg install and load.
- `post_bind` fires for every statement on a quack session, and the session is tagged
  (`quack_session` registered state) from its first statement. DuckLake runs its metadata queries on
  its own connection, tagged `ducklake_internal_connection`. quack's token check runs on a separate,
  untagged connection.
- Plans resolve to exact tables and columns through secure views, table macros, `IN` subqueries and
  `query()`; functions resolve with catalog and schema (`"system".main.current_setting`).
- `CREATE SECURE VIEW` works in `memory` and `duckdb-file` catalogs (it survives detach and
  re-attach); at read time the plan carries a boundary with the view's qualified name.
- Setting scope is recoverable at runtime: 140 of 192 core settings carry a scope target, the other
  52 have exactly one setter (37 global, 15 local); every extension setting has a default scope.
  Scope alone does not classify: `s3_secret_access_key` and `profiling_output` are session-scoped.
- quack's authorization hook receives the connection id and the full SQL of every PREPARE, and its
  refusal message reaches the client. Connection ids are 128 random bits from DuckDB's cryptographic
  generator.
- `OnFinalizePrepare` only runs when a registered state with `CanRequestRebind()` already exists
  before the statement: the real node must create it when the connection's handle is installed.
- The quack client sends each query twice (each statement is bound twice) and retries a refused
  statement several times; a verdict cache absorbs both.

## Results across platforms (CI)

- Linux x86_64/arm64 and macOS x86_64/arm64 against the shared release libduckdb: smoke and quack
  round trip pass.
- Windows: the release `duckdb.dll` does not export the internal C++ symbols the hooks use (link
  fails with 10 unresolved externals, x64 and ARM64). The fallback is to link the official release
  static libraries (`duckdb-static-libs-<platform>.tar.gz`, `-DQOD_NODE_DUCKDB_STATIC=ON`), the same
  ones the official `duckdb.exe` is built from. A static host must register what those libraries
  link like extensions before opening a database (`duckdb_register_static_extension` with the
  `loadable_extensions` and `httplib` capabilities and the built-in extensions; see
  `src/static_extensions.cpp`): without the capability, INSTALL and LOAD are refused. Verified on
  macOS arm64 (official extensions install and load, round trip passes); CI checks Windows.

Still open: Iceberg and Postgres plans, and latency.
