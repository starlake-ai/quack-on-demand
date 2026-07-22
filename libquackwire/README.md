# libquackwire

JNI shim that speaks DuckDB's Quack wire (`application/vnd.duckdb`)
directly from a JVM, without requiring DuckDB to be embedded in the
JVM process.

This Maven coordinate publishes four classifier jars - one per
supported platform:

| Classifier      | Native binary             |
|-----------------|---------------------------|
| `linux-x86_64`  | `libquackwire.so`         |
| `linux-aarch64` | `libquackwire.so`         |
| `osx-x86_64`    | `libquackwire.dylib`      |
| `osx-aarch64`   | `libquackwire.dylib`      |

Each classifier jar contains a single file at
`native/<classifier>/libquackwire.<so|dylib>`. The consumer extracts
it to a temp file and `System.load`s it.

## Version scheme

`<duckdb-abi-version>-<duckdb-quack-short-sha>`

For example, `1.5.5-7e80f7ffcc98` says:

- Built against DuckDB v1.5.5's C++ ABI (link-compatible with
  `libduckdb.so` / `libduckdb.dylib` from
  https://github.com/duckdb/duckdb/releases/tag/v1.5.5).
- Pinned at `duckdb/duckdb-quack` commit `7e80f7ffcc98`.

Bumping either pin bumps the version. There is no truncation - the
short SHA is the integrity check.

## Source

Built from <https://github.com/starlake-ai/quack-on-demand>, directory
`native/quackwire/`. The build is reproducible via CMake; see
`.github/workflows/quackwire.yml`.