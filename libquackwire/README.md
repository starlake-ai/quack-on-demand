# libquackwire

JNI shim that speaks DuckDB's Quack wire (`application/vnd.duckdb`)
directly from a JVM, without requiring DuckDB to be embedded in the
JVM process.

## Vendored, not published

The native binaries are vendored in git under `binaries/<platform>/`,
one file per supported platform:

| Platform          | Native binary         |
|-------------------|------------------------|
| `linux-x86_64`    | `libquackwire.so`     |
| `linux-aarch64`   | `libquackwire.so`     |
| `osx-x86_64`      | `libquackwire.dylib`  |
| `osx-aarch64`     | `libquackwire.dylib`  |
| `windows-x86_64`  | `quackwire.dll`       |

Each binary ships with a `.sha256` companion, and `binaries/VERSION`
stamps the version all of them were built at. `build.sbt`'s
resourceGenerator copies these into the assembly at
`native/<platform>/<lib>` on every `sbt compile`/`sbt assembly` - no
Maven coordinate, no classifier jars, no publish step. The first four
platforms are mandatory (the build fails without them); the Windows
dll rides in automatically whenever it is present, no env flag
required.

Refreshed by `scripts/refresh-quackwire-binaries.sh`: it rebuilds the
host platform locally via CMake and downloads the rest from the latest
green run of `.github/workflows/quackwire.yml` on main, then leaves the
diff for a developer to review and commit like any other change.
`scripts/release.sh` (and CI's release.yml before publishing) verifies
the stamped `VERSION` and every `.sha256` match `libquackwireVersion`
in `build.sbt` before a release proceeds.

**Pin bump:** edit `val libquackwireVersion` in `build.sbt`, push (CI
builds the new binaries), run `scripts/refresh-quackwire-binaries.sh`,
review and commit the diff.

## Version scheme

`<duckdb-abi-version>-<duckdb-quack-short-sha>-<rev>`

For example, `1.5.5-7e80f7ffcc98-1` says:

- Built against DuckDB v1.5.5's C++ ABI (link-compatible with
  `libduckdb.so` / `libduckdb.dylib` from
  https://github.com/duckdb/duckdb/releases/tag/v1.5.5).
- Pinned at `duckdb/duckdb-quack` commit `7e80f7ffcc98`.
- `rev` is a monotonic patch number that bumps each time the binaries
  are re-released for the same (abi, sha) pair, without touching the
  duckdb-quack pin.

Bumping either the ABI or the duckdb-quack pin resets `rev` to 1. There
is no truncation - the short SHA is the integrity check.

## Source

Built from <https://github.com/starlake-ai/quack-on-demand>, directory
`native/quackwire/`. The build is reproducible via CMake; see
`.github/workflows/quackwire.yml`.
