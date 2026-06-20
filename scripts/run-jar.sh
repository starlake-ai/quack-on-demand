#!/usr/bin/env bash
#
# Run the quack-on-demand manager from the assembly uber-jar.
#
# Two modes, picked via BUILD:
#   BUILD=0 (default) - download the jar from Maven Central
#                       (ai.starlake:quack-on-demand_3:<QOD_VERSION>),
#                       cache it under $JAR_CACHE_DIR, run `java -jar`.
#   BUILD=1           - rebuild libquackwire for the host platform (cmake +
#                       sbt libquackwire/publishLocal), then run `sbt assembly`
#                       from this checkout. Non-host platforms come from
#                       Central snapshots so the assembly carries all four
#                       libs. Uses the freshly-built jar in distrib/.
#
# Boot extras: Postgres reachability probe, idempotent CREATE DATABASE
# of the catalog DB, optional TPC-H seed via load-tpch-dbgen.sh before
# the JVM starts.
#
# All quack-on-demand settings come from QOD_* / PROXY_* env vars
# (see guides/RUNNING.md Path 1 for the full list). Sensible defaults:
#   - REST + UI on :20900, FlightSQL edge on :31338 (TLS on)
#   - Postgres expected at $QOD_PG_HOST:5432 (default localhost)
#
# Env vars:
#   BUILD=1                       run `sbt assembly` first instead of downloading
#   QOD_VERSION                 artifact version to download (default = latest
#                                 release from Maven Central; `latest-snapshot`
#                                 fetches from Central snapshots; ignored when
#                                 BUILD=1)
#   JAR_CACHE_DIR                 download cache (default ~/.cache/quack-on-demand)
#   JAVA_HOME                     uses `java` on PATH if unset
#   JAVA_OPTS                     extra JVM flags (e.g. -Xmx2g)
#
#   LOAD_TPCH=N                   Seeds the demo and loads TPC-H into
#                                 acme/acme_tpch at scale factor N. Positive
#                                 integer; unset = skip.                (default unset)
#   LOAD_TPCDS=N                  Seeds the demo and loads TPC-DS into
#                                 globex/globex_tpcds at scale factor N. Positive
#                                 integer; unset = skip.                (default unset)
#   LOAD_TPC=N                    Legacy shortcut: equivalent to setting both
#                                 LOAD_TPCH=N and LOAD_TPCDS=N. Either explicit
#                                 var wins over this one. Either of these (or
#                                 the legacy LOAD_TPC) also sets QOD_BOOTSTRAP_YAML
#                                 so the JVM imports the bundled demo manifest on
#                                 first boot. Loaders run in background before
#                                 exec java.                            (default unset)
#
#   NUKE=1                        wipe local state (Postgres DB, ducklake/,
#                                 state/, certs/) before booting. Irreversible.
#                                 Mirrors run-docker-compose.sh's NUKE flag for
#                                 the native-jar path.                  (default 0)
#
#   DUCKDB_VERSION                pin a specific DuckDB release (e.g. 1.5.3).
#                                 Default = derived from build.sbt's
#                                 libquackwireVersion so the CLI and libquackwire
#                                 stay ABI-compatible. The script always installs
#                                 duckdb + libduckdb (no skip flag) -- an ABI
#                                 mismatch with the operator's system duckdb
#                                 crashes node spawn with a confusing dlopen
#                                 error.                                (default auto)
#   DUCKDB_CACHE_DIR              where to cache duckdb across runs. Air-gapped
#                                 / CI: pre-populate $DUCKDB_CACHE_DIR/$DUCKDB_VERSION/
#                                 {bin,lib} and the fast path skips the network
#                                 fetch.                                (default $REPO_DIR/.duckdb)
#
# Usage:
#   ./scripts/run-jar.sh                                   # latest release
#   QOD_VERSION=0.1.0 ./scripts/run-jar.sh               # pinned release
#   QOD_VERSION=latest-snapshot ./scripts/run-jar.sh     # latest snapshot
#   BUILD=1 ./scripts/run-jar.sh                           # local source build
#   LOAD_TPCH=1 ./scripts/run-jar.sh                       # + TPC-H demo seed SF=1
#   LOAD_TPCDS=10 ./scripts/run-jar.sh                     # + TPC-DS demo seed SF=10
#   LOAD_TPC=1 ./scripts/run-jar.sh                        # + both demos SF=1 (legacy shortcut)
#   NUKE=1 LOAD_TPC=1 ./scripts/run-jar.sh                 # wipe + fresh boot + both SF=1

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISTRIB_DIR="$REPO_DIR/distrib"

# Anchor CWD at the repo root so the JVM's child processes (spawn-quack-node.sh
# invoked via `./scripts/spawn-quack-node.sh`) resolve correctly no matter where
# the user called this script from.
cd "$REPO_DIR"

# Anchor the DuckLake data path to the repo unless the caller already set
# it. Exporting an absolute path makes startup logs unambiguous AND
# survives any later JVM cwd changes (e.g. tests that fork). The LOAD_TPC
# block below derives per-tenant-db paths as `dirname($QOD_DUCKLAKE_DATA_PATH)
# /<tenant-db>`, so the leaf segment is just an anchor -- the demo loaders
# place their data alongside it (e.g. ducklake/acme_tpch, ducklake/globex_tpcds).
# Override by exporting QOD_DUCKLAKE_DATA_PATH before invoking this script.
export QOD_DUCKLAKE_DATA_PATH="${QOD_DUCKLAKE_DATA_PATH:-$REPO_DIR/ducklake/data}"

BUILD="${BUILD:-0}"
NUKE="${NUKE:-0}"
GROUP_PATH="ai/starlake"
ARTIFACT="quack-on-demand_3"
JAR_CACHE_DIR="${JAR_CACHE_DIR:-$HOME/.cache/quack-on-demand}"

# ---- DuckDB CLI + libduckdb self-install ---------------------------------
# The bare-jar path needs two DuckDB artifacts on the host:
#   1. `duckdb` CLI on PATH:
#        - spawn-quack-node.sh exec's it for every Quack node the manager
#          spawns via LocalQuackBackend
#        - scripts/load-{tpch,tpcds}-dbgen.sh shell out to it for demo seeds
#   2. libduckdb.{so,dylib} on the JVM's library path:
#        - the JNI native client (QOD_NATIVE_CLIENT=true, default) dlopens
#          libquackwire which itself links against libduckdb at the ABI
#          pinned in build.sbt's libquackwireVersion
#
# Mandatory by design: even when the operator has a system duckdb on PATH,
# an ABI mismatch against libquackwire's libduckdb crashes at first node
# spawn with a confusing dlopen / NoSuchMethodError. Pinning both binaries
# to the libquackwire-ABI version eliminates that footgun.
#
# Air-gapped / CI: pre-populate $DUCKDB_CACHE_DIR/$DUCKDB_VERSION/{bin,lib}
# with the right binaries; the fast-path check will see them and skip the
# network fetch. Override the pinned version with DUCKDB_VERSION (defaults
# to libquackwireVersion's first 3 segments).
DUCKDB_CACHE_DIR="${DUCKDB_CACHE_DIR:-$REPO_DIR/.duckdb}"

ensure_duckdb() {
  local version="${DUCKDB_VERSION:-}"
  if [[ -z "$version" && -f "$REPO_DIR/build.sbt" ]]; then
    version="$(grep -E '^val libquackwireVersion' "$REPO_DIR/build.sbt" \
      | sed -E 's/.*"([0-9]+\.[0-9]+\.[0-9]+).*/\1/')"
  fi
  version="${version:-1.5.3}"

  local cache="$DUCKDB_CACHE_DIR/$version"
  local bin_dir="$cache/bin"
  local lib_dir="$cache/lib"

  # Always expose the cache to PATH + dynamic-loader so the spawn script
  # and the JVM resolve duckdb / libduckdb from the cache instead of the
  # operator's system install (which may be ABI-incompatible).
  case ":$PATH:" in *":$bin_dir:"*) ;; *) export PATH="$bin_dir:$PATH" ;; esac
  case "$(uname -s)" in
    Darwin) export DYLD_LIBRARY_PATH="$lib_dir:${DYLD_LIBRARY_PATH:-}" ;;
    Linux)  export LD_LIBRARY_PATH="$lib_dir:${LD_LIBRARY_PATH:-}"     ;;
  esac

  # Fast path: cached binary matches the pinned version (covers air-gapped
  # operators who pre-populated the cache dir).
  if [[ -x "$bin_dir/duckdb" ]] && "$bin_dir/duckdb" -version 2>/dev/null \
      | head -1 | grep -q "v$version"; then
    return 0
  fi

  # Detect platform; map to DuckDB's release asset names.
  local plat
  case "$(uname -s)" in
    Darwin) plat="osx-universal" ;;
    Linux)
      case "$(uname -m)" in
        x86_64|amd64)  plat="linux-amd64" ;;
        aarch64|arm64) plat="linux-arm64" ;;
        *)
          echo "ERROR: unsupported arch $(uname -m) for duckdb self-install." >&2
          echo "       Pre-populate $bin_dir + $lib_dir with a duckdb v$version" >&2
          echo "       built for this arch, then re-run." >&2
          exit 1
          ;;
      esac
      ;;
    *)
      echo "ERROR: unsupported OS $(uname -s) for duckdb self-install." >&2
      echo "       Pre-populate $bin_dir + $lib_dir with a duckdb v$version build, then re-run." >&2
      exit 1
      ;;
  esac

  if ! command -v unzip >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
    echo "ERROR: curl + unzip are required for the duckdb self-install." >&2
    echo "       Install them (e.g. brew install curl unzip / apt install curl unzip), or" >&2
    echo "       pre-populate $bin_dir + $lib_dir with the v$version binaries by hand." >&2
    exit 1
  fi

  echo "duckdb: installing v$version ($plat) into $cache"
  mkdir -p "$bin_dir" "$lib_dir"
  local tmp
  tmp="$(mktemp -d -t qod-duckdb.XXXXXX)"
  # shellcheck disable=SC2064  # expand $tmp now so the trap sees the right dir
  trap "rm -rf '$tmp'" RETURN

  local base="https://github.com/duckdb/duckdb/releases/download/v$version"

  echo "  fetching duckdb CLI from $base/duckdb_cli-$plat.zip"
  if ! curl -fsSL -o "$tmp/cli.zip" "$base/duckdb_cli-$plat.zip"; then
    echo "ERROR: failed to download $base/duckdb_cli-$plat.zip" >&2
    echo "       Air-gapped? Pre-populate $bin_dir/duckdb + $lib_dir/libduckdb.{so,dylib}" >&2
    echo "       with v$version artifacts and re-run." >&2
    exit 1
  fi
  unzip -q -o "$tmp/cli.zip" -d "$bin_dir"
  chmod +x "$bin_dir/duckdb"

  echo "  fetching libduckdb from $base/libduckdb-$plat.zip"
  if ! curl -fsSL -o "$tmp/lib.zip" "$base/libduckdb-$plat.zip"; then
    echo "ERROR: failed to download $base/libduckdb-$plat.zip" >&2
    echo "       The JNI native client requires libduckdb at the same ABI as libquackwire." >&2
    echo "       Pre-populate $lib_dir with libduckdb v$version, or set QOD_NATIVE_CLIENT=false" >&2
    echo "       on the JVM side to fall back to the embedded (slower) path." >&2
    exit 1
  fi
  unzip -q -o "$tmp/lib.zip" -d "$lib_dir"

  echo "duckdb: ready -> $bin_dir/duckdb (v$version)"
}
ensure_duckdb

# Demo seed controls. LOAD_TPC=N is the legacy shortcut that seeds BOTH
# benchmarks at the same scale factor; LOAD_TPCH / LOAD_TPCDS let you opt
# in to one (and at independent scale factors). Any var unset = skip that
# benchmark. Explicit per-bench vars win over LOAD_TPC.
#
#   LOAD_TPCH=1                       -> TPC-H only, SF=1
#   LOAD_TPCDS=10                     -> TPC-DS only, SF=10
#   LOAD_TPCH=1 LOAD_TPCDS=10         -> both, independent SFs
#   LOAD_TPC=1                        -> shortcut for LOAD_TPCH=1 LOAD_TPCDS=1
#   LOAD_TPC=10 LOAD_TPCDS=100        -> TPC-H from LOAD_TPC (SF=10),
#                                        TPC-DS from LOAD_TPCDS (SF=100)
LOAD_TPCH="${LOAD_TPCH:-${LOAD_TPC:-}}"
LOAD_TPCDS="${LOAD_TPCDS:-${LOAD_TPC:-}}"
for _v in LOAD_TPCH LOAD_TPCDS; do
  _val="${!_v}"
  if [[ -n "$_val" ]] && { ! [[ "$_val" =~ ^[0-9]+$ ]] || [[ "$_val" -lt 1 ]]; }; then
    echo "ERROR: $_v must be a positive integer scale factor (got: '$_val')." >&2
    exit 1
  fi
done
unset _v _val

# ---- Resolve jar ----
# BUILD=1 always builds locally. BUILD=0 (default) tries Maven Central
# first and falls back to `sbt assembly` if the artifact hasn't been
# published yet (pre-release / dev), so a fresh clone of the source
# tree works with the documented invocation regardless of release state.

# Ensure `sbt` is callable. If it isn't on PATH, fetch the official
# distribution into `.sbt-bootstrap/` under the repo root so a fresh
# clone can build with zero prior setup. The actual sbt version used by
# the project comes from `project/build.properties`; we just need any
# modern launcher.
SBT_BOOTSTRAP_VERSION="${SBT_BOOTSTRAP_VERSION:-1.12.11}"
SBT_BOOTSTRAP_ROOT="$REPO_DIR/.sbt-bootstrap"
SBT_CMD="sbt"

ensure_sbt() {
  if command -v sbt >/dev/null 2>&1; then
    SBT_CMD="sbt"
    return
  fi
  local sbt_dir="$SBT_BOOTSTRAP_ROOT/sbt-$SBT_BOOTSTRAP_VERSION"
  SBT_CMD="$sbt_dir/bin/sbt"
  if [[ -x "$SBT_CMD" ]]; then
    echo "using bootstrapped sbt: $SBT_CMD"
    return
  fi
  echo "sbt not on PATH; downloading sbt-$SBT_BOOTSTRAP_VERSION into $SBT_BOOTSTRAP_ROOT/ ..."
  mkdir -p "$SBT_BOOTSTRAP_ROOT"
  local tarball="$SBT_BOOTSTRAP_ROOT/sbt-$SBT_BOOTSTRAP_VERSION.tgz"
  if ! curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_BOOTSTRAP_VERSION}/sbt-${SBT_BOOTSTRAP_VERSION}.tgz" -o "$tarball"; then
    echo "ERROR: failed to download sbt-$SBT_BOOTSTRAP_VERSION from GitHub releases." >&2
    rm -f "$tarball"
    exit 1
  fi
  tar xzf "$tarball" -C "$SBT_BOOTSTRAP_ROOT"
  # The tarball expands to ./sbt/{bin,conf,lib}; pin it by renaming with the version.
  if [[ -d "$SBT_BOOTSTRAP_ROOT/sbt" ]]; then
    mv "$SBT_BOOTSTRAP_ROOT/sbt" "$sbt_dir"
  fi
  rm -f "$tarball"
  [[ -x "$SBT_CMD" ]] || { echo "ERROR: bootstrapped sbt did not produce $SBT_CMD" >&2; exit 1; }
  echo "bootstrapped sbt -> $SBT_CMD"
}

# Re-build libquackwire for the host platform via CMake, then ensure all four
# platforms' binaries are staged under libquackwire/binaries/ so
# `sbt libquackwire/publishLocal` can produce a complete set of classifier
# jars. Non-host platforms are pulled from Sonatype Central snapshots (the
# same libquackwire-SNAPSHOT the manager's libraryDependencies points at)
# and unpacked into the staging tree. After publishLocal lands the set in
# ~/.ivy2/local/, the assembly task resolves all four classifier coords
# from the local cache - the host one carries the freshly-built bits.
rebuild_libquackwire_locally() {
  # Skip if the C++ source tree is absent (e.g. distribution-style
  # checkout without native/quackwire/).
  if [[ ! -f "$REPO_DIR/native/quackwire/CMakeLists.txt" ]]; then
    echo "no native/quackwire/CMakeLists.txt found; skipping libquackwire rebuild" >&2
    return 0
  fi
  command -v cmake >/dev/null 2>&1 || {
    echo "WARN: cmake not on PATH; skipping libquackwire rebuild (assembly will resolve from Central snapshots)." >&2
    return 0
  }
  # 1. Detect host platform - mirrors QuackNativeBridge.NativeLoader.platformDir().
  local host_os host_arch host_platform host_ext
  case "$(uname -s)" in
    Darwin) host_os=osx;     host_ext=dylib ;;
    Linux)  host_os=linux;   host_ext=so    ;;
    *) echo "ERROR: unsupported OS for libquackwire: $(uname -s)" >&2; exit 1 ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64)  host_arch=x86_64  ;;
    aarch64|arm64) host_arch=aarch64 ;;
    *) echo "ERROR: unsupported arch for libquackwire: $(uname -m)" >&2; exit 1 ;;
  esac
  host_platform="$host_os-$host_arch"

  # 2. Read the libquackwire coord pinned in build.sbt - keeps the script and
  # the assembly agreeing on which version to publish/resolve.
  local libq_version
  libq_version="$(grep -E '^val libquackwireVersion' "$REPO_DIR/build.sbt" \
                  | sed -E 's/.*"([^"]+)".*/\1/' | head -n1)"
  [[ -n "$libq_version" ]] || {
    echo "ERROR: could not parse libquackwireVersion from build.sbt" >&2; exit 1; }

  # 3. CMake build for the host platform.
  echo "rebuilding libquackwire for $host_platform via cmake..."
  ( cd "$REPO_DIR/native/quackwire" \
    && cmake -B build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build --config Release )
  local host_built="$REPO_DIR/native/quackwire/build/libquackwire.$host_ext"
  [[ -f "$host_built" ]] || {
    echo "ERROR: cmake did not produce $host_built" >&2; exit 1; }
  mkdir -p "$REPO_DIR/libquackwire/binaries/$host_platform"
  cp "$host_built" "$REPO_DIR/libquackwire/binaries/$host_platform/libquackwire.$host_ext"

  # 4. For the other 3 platforms, pull the matching classifier jar from
  # Central snapshots (or use whatever is already staged from a prior run).
  local snap_url="https://central.sonatype.com/repository/maven-snapshots/ai/starlake/libquackwire/${libq_version}"
  local platforms=(linux-x86_64 linux-aarch64 osx-x86_64 osx-aarch64)
  for plat in "${platforms[@]}"; do
    [[ "$plat" == "$host_platform" ]] && continue
    local ext
    case "$plat" in *linux*) ext=so ;; *) ext=dylib ;; esac
    local out_dir="$REPO_DIR/libquackwire/binaries/$plat"
    local out_lib="$out_dir/libquackwire.$ext"
    if [[ -f "$out_lib" ]]; then
      continue
    fi
    mkdir -p "$out_dir"
    local cls_url="${snap_url}/libquackwire-${libq_version}-${plat}.jar"
    local tmp
    tmp="$(mktemp -d)"
    if curl -fsSL "$cls_url" -o "$tmp/cls.jar" 2>/dev/null; then
      ( cd "$tmp" && unzip -q cls.jar "native/$plat/libquackwire.$ext" \
        && mv "native/$plat/libquackwire.$ext" "$out_lib" )
      echo "fetched libquackwire[$plat] from Central snapshots"
    else
      echo "WARN: could not fetch $cls_url; libquackwire[$plat] will be skipped (assembly may fail at sbt-assembly time)." >&2
    fi
    rm -rf "$tmp"
  done

  # 5. Publish all available classifiers into the local ivy cache so the
  # assembly task resolves them without round-tripping Central for the host.
  echo "sbt libquackwire/publishLocal..."
  "$SBT_CMD" libquackwire/publishLocal
}

build_locally() {
  ensure_sbt
  rebuild_libquackwire_locally
  echo "running '$SBT_CMD assembly' (local build)..."
  "$SBT_CMD" assembly
  JAR="$(ls -t "$DISTRIB_DIR"/quack-on-demand-assembly-*.jar 2>/dev/null | head -n1 || true)"
  [[ -n "$JAR" ]] || { echo "ERROR: sbt assembly did not produce a jar in $DISTRIB_DIR" >&2; exit 1; }
}

# Used in the Maven-Central-fallback path only. Reuses an existing assembly
# jar if one is sitting in distrib/, saving ~30-45s of sbt assembly. `BUILD=1`
# bypasses this and always rebuilds.
use_local_jar_or_build() {
  JAR="$(ls -t "$DISTRIB_DIR"/quack-on-demand-assembly-*.jar 2>/dev/null | head -n1 || true)"
  if [[ -n "$JAR" ]]; then
    echo "using existing local jar: $JAR"
    echo "  (BUILD=1 ./scripts/run-jar.sh to force a fresh build)"
  else
    build_locally
  fi
}

if [[ "$BUILD" == "1" ]]; then
  echo "BUILD=1: local build"
  build_locally
else
  mkdir -p "$JAR_CACHE_DIR"

  # Resolve the latest published version. The trailing `|| true` keeps
  # the function exit code at 0 even when curl 404s or grep finds nothing
  # (otherwise `set -e` + `pipefail` would kill the script before the
  # empty-result fallback can fire). `2>/dev/null` silences curl's error
  # messages so the "no published release" path stays clean.
  resolve_latest_release() {
    { curl -fsSL "https://repo1.maven.org/maven2/${GROUP_PATH}/${ARTIFACT}/maven-metadata.xml" 2>/dev/null \
        | grep -oE '<release>[^<]+</release>' | sed 's/<[^>]*>//g' | head -1; } || true
  }
  resolve_latest_snapshot() {
    # First try the standard maven-metadata.xml index. The snapshots repo at
    # central.sonatype.com (Nexus 3) does not always materialise that index
    # even when artifacts are present, so this can legitimately return empty
    # and the caller must have a second strategy (see `local_snapshot_version`).
    { curl -fsSL "https://central.sonatype.com/repository/maven-snapshots/${GROUP_PATH}/${ARTIFACT}/maven-metadata.xml" 2>/dev/null \
        | grep -oE '<latest>[^<]+</latest>' | sed 's/<[^>]*>//g' | head -1; } || true
  }
  # Fall-back: read the SNAPSHOT version pinned in version.sbt. Used when
  # the snapshots-repo metadata index is missing - we still know which
  # snapshot the source tree corresponds to and can probe its jar URL.
  local_snapshot_version() {
    [[ -f "$REPO_DIR/version.sbt" ]] || return 0
    awk -F'"' '/ThisBuild *\/ *version *:=/ { print $2 }' "$REPO_DIR/version.sbt" | head -1
  }

  # Belt-and-suspenders: also wrap each substitution with `|| true`, so an
  # accidental non-zero from the function never aborts the script under
  # `set -e`. The empty-version fall-back branch below picks up the result.
  version="${QOD_VERSION:-latest}"
  case "$version" in
    latest)
      version="$(resolve_latest_release || true)"
      if [[ -n "$version" ]]; then
        echo "resolved latest release: $version"
      else
        # No release on Maven Central; try the project's own SNAPSHOT.
        version="$(local_snapshot_version)"
        if [[ -n "$version" && "$version" == *-SNAPSHOT ]]; then
          echo "no release on Maven Central; trying snapshot $version (from version.sbt)"
        else
          echo "WARN: no release on Maven Central and no snapshot version detected; falling back to local jar / build." >&2
          version=""
        fi
      fi
      ;;
    latest-snapshot)
      version="$(resolve_latest_snapshot || true)"
      if [[ -z "$version" ]]; then
        # snapshot index missing -> fall back to version.sbt.
        version="$(local_snapshot_version)"
      fi
      if [[ -n "$version" ]]; then
        echo "resolved latest snapshot: $version"
      else
        echo "WARN: no snapshot found and no version.sbt detected; falling back to local jar / build." >&2
      fi
      ;;
  esac

  # Decide whether the cached jar at $JAR is already current. Maven
  # publishes a `.sha1` sidecar next to every jar; compare it against
  # `shasum -a 1` of the local file. Snapshots use this every time
  # (same coord can re-publish). Releases use it as belt-and-suspenders
  # (Central is immutable, so a filename match is usually enough - but
  # a partial download from a prior interrupted run would slip past
  # `[[ -f "$JAR" ]]`).
  jar_is_current() {
    local jar="$1" sha_url="$2"
    [[ -f "$jar" ]] || return 1
    local remote_sha1 local_sha1
    remote_sha1=$(curl -fsSL --max-time 10 "$sha_url" 2>/dev/null | tr -d '[:space:]' | head -c40)
    [[ -n "$remote_sha1" ]] || return 1
    local_sha1=$(shasum -a 1 "$jar" 2>/dev/null | awk '{print $1}')
    [[ "$local_sha1" == "$remote_sha1" ]]
  }

  if [[ -z "$version" ]]; then
    # Resolution failed -> reuse local jar if present, else build.
    use_local_jar_or_build
  elif [[ "$version" == *-SNAPSHOT ]]; then
    base_url="https://central.sonatype.com/repository/maven-snapshots/${GROUP_PATH}/${ARTIFACT}/${version}"
    JAR="$JAR_CACHE_DIR/${ARTIFACT}-${version}.jar"
    jar_url="$base_url/${ARTIFACT}-${version}.jar"
    if jar_is_current "$JAR" "${jar_url}.sha1"; then
      echo "snapshot $version cached (sha1 matches Central); skipping download."
    else
      echo "downloading snapshot $version..."
      if ! curl -fsSL "$jar_url" -o "$JAR" 2>/dev/null; then
        echo "WARN: snapshot download failed; falling back to local jar / build." >&2
        use_local_jar_or_build
      fi
    fi
  else
    base_url="https://repo1.maven.org/maven2/${GROUP_PATH}/${ARTIFACT}/${version}"
    JAR="$JAR_CACHE_DIR/${ARTIFACT}-${version}.jar"
    jar_url="$base_url/${ARTIFACT}-${version}.jar"
    if jar_is_current "$JAR" "${jar_url}.sha1"; then
      echo "release $version cached (sha1 matches Central); skipping download."
    else
      echo "downloading $version from Maven Central..."
      if ! curl -fsSL "$jar_url" -o "$JAR" 2>/dev/null; then
        echo "WARN: release download failed; falling back to local jar / build." >&2
        use_local_jar_or_build
      fi
    fi
  fi
fi

echo "jar: $JAR"

# ---- Resolve java ----
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "${JAVA_BIN:-java}" >/dev/null 2>&1 || {
  echo "ERROR: java not found. Install JDK 21+ or set JAVA_HOME." >&2
  exit 1
}
echo "java: $("${JAVA_BIN:-java}" -version 2>&1 | head -1)"

# ---- Sanity: Postgres reachable when stateStorage=postgres (the default) ----
state_mode="${QOD_STATE_STORAGE:-postgres}"
pg_host="${QOD_PG_HOST:-localhost}"
pg_port="${QOD_PG_PORT:-5432}"
pg_user="${QOD_PG_USER:-postgres}"
pg_pass="${QOD_PG_PASSWORD:-azizam}"
pg_admin_db="${QOD_PG_ADMIN_DB:-postgres}"
pg_dbname="${QOD_PG_DBNAME:-qod}"

pg_reachable=0
if [[ "$state_mode" == "postgres" ]] && command -v psql >/dev/null 2>&1; then
  if PGPASSWORD="$pg_pass" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_admin_db" \
       -tAc 'SELECT 1' >/dev/null 2>&1; then
    # Version gate: the control plane uses `gen_random_uuid()` (PG13+
    # built-in), `DROP DATABASE ... WITH (FORCE)` (PG13+), and the
    # 0006-rbac changeset's modern partial-index shape. The project's
    # documented minimum is Postgres 16 -- refuse to proceed against
    # an older server so we fail BEFORE the migration / NUKE syntax
    # error appears in the log and confuses the operator.
    pg_major="$(PGPASSWORD="$pg_pass" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_admin_db" \
        -tAc 'SHOW server_version_num' 2>/dev/null | tr -d '[:space:]')"
    if [[ -z "$pg_major" ]] || [[ "$pg_major" -lt 160000 ]]; then
      echo "ERROR: Postgres at $pg_user@$pg_host:$pg_port reports server_version_num=$pg_major (need >= 160000 / PG 16)." >&2
      echo "       This project requires Postgres 16+. Point QOD_PG_HOST / QOD_PG_PORT / QOD_PG_PASSWORD" >&2
      echo "       at a PG16 server (e.g. the postgres:16-alpine container the docker-compose ships)." >&2
      exit 1
    fi
    echo "postgres: OK ($pg_user@$pg_host:$pg_port, server_version_num=$pg_major)  [state storage]"
    pg_reachable=1
  else
    echo "WARN: cannot reach Postgres at $pg_user@$pg_host:$pg_port; manager will fail at startup if it cannot persist state." >&2
    echo "      Either start Postgres, set QOD_STATE_STORAGE=file, or override QOD_PG_*." >&2
  fi
fi

# ---- Optional nuke: tear down + wipe before starting ----
# Mirrors run-docker-compose.sh's NUKE flag for the native-jar path. Stops a
# running manager (if any), drops the Postgres catalog DB, and wipes local
# state directories (ducklake/, state/, certs/). Idempotent: missing pieces
# are skipped silently.
if [[ "$NUKE" == "1" ]]; then
  echo "NUKE=1: tearing down state..."
  # Stop any running manager via the project's own stop script.
  if curl -sS --max-time 2 "http://${QOD_ON_DEMAND_HOST:-localhost}:${QOD_ON_DEMAND_PORT:-20900}/health" >/dev/null 2>&1; then
    echo "  stopping running manager"
    "$REPO_DIR/scripts/stop-jar.sh" >/dev/null 2>&1 || true
  fi
  # Drop the Postgres catalog DB (will be recreated below). Requires
  # Postgres to be reachable; otherwise warn and continue with the local
  # wipes; they are still useful and the Postgres side is harmless when
  # the DB is later recreated by the bootstrap step.
  #
  # Also drop the demo tenant-db Postgres DBs (acme_tpch + globex_tpcds)
  # so the next boot starts with no leftover DuckLake catalog / tables
  # from a previous run. Runtime-created tenant-dbs (via the REST API,
  # not the bundled demo YAML) are NOT covered; drop them manually if
  # you want them gone.
  if [[ "$state_mode" == "postgres" ]] && [[ "$pg_reachable" == "1" ]] && [[ -n "$pg_dbname" ]]; then
    echo "  dropping Postgres database: $pg_dbname (control plane)"
    PGPASSWORD="$pg_pass" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_admin_db" \
       -tAc "DROP DATABASE IF EXISTS \"$pg_dbname\" WITH (FORCE)" >/dev/null
    for demo_db in acme_tpch globex_tpcds; do
      echo "  dropping Postgres database: $demo_db (demo tenant-db)"
      PGPASSWORD="$pg_pass" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_admin_db" \
         -tAc "DROP DATABASE IF EXISTS \"$demo_db\" WITH (FORCE)" >/dev/null
    done
  elif [[ "$state_mode" == "postgres" ]]; then
    echo "  WARN: Postgres unreachable; skipping DB drop (local wipes still proceed)" >&2
  fi
  # Wipe local state directories that the JVM and child quack nodes write to.
  for d in "$REPO_DIR/ducklake" "$REPO_DIR/state" "$REPO_DIR/certs"; do
    if [[ -d "$d" ]]; then
      echo "  wiping $d"
      rm -rf "$d"
    fi
  done
fi

# ---- Bootstrap catalog DB (idempotent) ----
# Without this a brand-new install fails at the first Hikari connection
# with "database does not exist". Skipped when Postgres isn't reachable or
# psql isn't installed.
if [[ "$state_mode" == "postgres" ]] && [[ "$pg_reachable" == "1" ]] && [[ -n "$pg_dbname" ]]; then
  if PGPASSWORD="$pg_pass" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_admin_db" \
       -tAc "SELECT 1 FROM pg_database WHERE datname='$pg_dbname'" 2>/dev/null | grep -q 1; then
    echo "catalog db: '$pg_dbname' already exists"
  else
    echo "catalog db: creating '$pg_dbname' in $pg_admin_db..."
    PGPASSWORD="$pg_pass" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_admin_db" \
       -tAc "CREATE DATABASE \"$pg_dbname\"" >/dev/null
  fi
fi

# ---- Optional: TPC demo loaders ----
# When either LOAD_TPCH or LOAD_TPCDS is set, the JVM imports the bundled
# demo YAML and the selected loader(s) run in background (TPC-H into
# acme/acme_tpch and/or TPC-DS into globex/globex_tpcds, at their
# independent scale factors).
#
# Backgrounded BEFORE `exec java` because exec replaces this shell.
# Each loader is idempotent (CREATE TABLE IF NOT EXISTS + insert-if-empty).
if [[ -n "$LOAD_TPCH" || -n "$LOAD_TPCDS" ]]; then
  : "${QOD_BOOTSTRAP_YAML:=$REPO_DIR/src/main/resources/bootstrap-demo.yaml}"
  export QOD_BOOTSTRAP_YAML

  echo "load-tpc: TPC-H=${LOAD_TPCH:-skip}, TPC-DS=${LOAD_TPCDS:-skip}, bootstrap=$QOD_BOOTSTRAP_YAML"

  if [[ -n "$LOAD_TPCH" ]]; then
    (
      SF="$LOAD_TPCH" \
      PG_HOST="$pg_host" PG_PORT="$pg_port" PG_USER="$pg_user" PG_PASS="$pg_pass" \
      PG_ADMIN_DB="$pg_admin_db" \
      DB_NAME="acme_tpch" SCHEMA_NAME="tpch1" \
      DATA_PATH="$(dirname "${QOD_DUCKLAKE_DATA_PATH:-$REPO_DIR/ducklake/acme}")/acme_tpch" \
        "$REPO_DIR/scripts/load-tpch-dbgen.sh"
    ) &
  fi

  if [[ -n "$LOAD_TPCDS" ]]; then
    (
      SF="$LOAD_TPCDS" \
      PG_HOST="$pg_host" PG_PORT="$pg_port" PG_USER="$pg_user" PG_PASS="$pg_pass" \
      PG_ADMIN_DB="$pg_admin_db" \
      DB_NAME="globex_tpcds" SCHEMA_NAME="tpcds1" \
      DATA_PATH="$(dirname "${QOD_DUCKLAKE_DATA_PATH:-$REPO_DIR/ducklake/globex}")/globex_tpcds" \
        "$REPO_DIR/scripts/load-tpcds-dbgen.sh"
    ) &
  fi
  # No `wait`: exec java next detaches them.
fi

# ---- Effective settings ----
echo "REST + UI:  http://${QOD_ON_DEMAND_HOST:-0.0.0.0}:${QOD_ON_DEMAND_PORT:-20900}/ui/"
echo "FlightSQL:  ${PROXY_HOST:-0.0.0.0}:${PROXY_PORT:-31338}  (TLS=${PROXY_TLS_ENABLED:-true})"
echo "State:      $state_mode"
echo "Runtime:    ${QOD_RUNTIME_TYPE:-local}"
echo ""

# ---- Port preflight ----
# Aborts before the JVM boots if any port the manager will need is held
# by another process. The truly nasty failure mode is an orphan Quack
# node from a prior run still listening inside the [minPort, maxPort]
# range. The supervisor's port allocator does not probe the OS, so it
# will happily hand that port to a fresh spawn; the JNI client then
# connects to the orphan, mismatches tokens, and the node returns
# "Authentication failed" until you kill the orphan by hand. NUKE=1
# wipes Postgres state but not OS processes - so a "fresh" NUKE boot
# is exactly when this trap snaps shut.
#
# Three port classes are checked:
#   1. REST/UI port (manager would crash at bind, but we'd rather say so up-front)
#   2. FlightSQL edge port (same)
#   3. Node port range [minPort, maxPort] (only WARN; the manager can
#      route around in-use entries, but orphans there are the silent-
#      auth-failure source we want to call out)
preflight_ports() {
  local rest_port="${QOD_ON_DEMAND_PORT:-20900}"
  local flight_port="${PROXY_PORT:-31338}"
  local min_port="${QOD_MIN_PORT:-21900}"
  local max_port="${QOD_MAX_PORT:-22500}"
  if ! command -v lsof >/dev/null 2>&1; then
    echo "WARN: lsof not on PATH; skipping port preflight." >&2
    return 0
  fi

  # `set -euo pipefail` is on. `head -N` closes its pipe early, which
  # makes `lsof` / `sort` exit SIGPIPE (non-zero), which `pipefail`
  # propagates as the substitution's exit code, which `set -e` then
  # treats as a fatal script error. The `|| true` tail neutralises that
  # without changing what `busy=` ends up with.
  local busy
  for p in "$rest_port" "$flight_port"; do
    busy=$(lsof -nP -iTCP:"$p" -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print $2 " " $1}' | sort -u | head -3 || true)
    if [[ -n "$busy" ]]; then
      echo "ERROR: port $p already in use:" >&2
      echo "$busy" | sed 's/^/  /' >&2
      echo "  Stop the holder (./scripts/stop-jar.sh, or kill -TERM <pid>) and retry." >&2
      exit 1
    fi
  done

  # Node range: enumerate listeners, intersect with the configured range.
  local in_range
  in_range=$(lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null \
             | awk -v lo="$min_port" -v hi="$max_port" '
                 NR>1 {
                   # column 9 is "addr:port", typically *:21900 or 127.0.0.1:21900
                   n=split($9, parts, ":");
                   port=parts[n];
                   if (port+0>=lo && port+0<=hi) print $2 " " $1 " :" port;
                 }
               ' | sort -u || true)
  if [[ -n "$in_range" ]]; then
    echo "ERROR: orphan listener(s) inside the configured node port range [$min_port, $max_port]:" >&2
    echo "$in_range" | sed 's/^/  /' >&2
    echo "  These are almost certainly stale Quack node processes from a prior run." >&2
    echo "  The manager's port allocator does not probe the OS - re-leasing one of" >&2
    echo "  these ports leads to silent 'Authentication failed' errors at query time." >&2
    echo "  Kill them and retry:    pkill -f 'quack_serve|spawn-quack-node'" >&2
    exit 1
  fi
}
preflight_ports

# ---- Run ----
# The assembly jar carries `Add-Opens` in its manifest (JEP 261) so Arrow's
# unsafe allocator works on Java 17+ without extra flags. The system
# property pins Arrow's allocator - without it Arrow picks netty and
# crashes with NoSuchFieldError: chunkSize.
#
# Terminal hygiene:
#   - </dev/null  : the JVM does not need stdin. Without this the JVM
#                   grabs the terminal's read side, so any keystrokes
#                   are consumed by Java instead of the shell.
#   - stty sane   : if the JVM exits abnormally (Ctrl-C, OOM) it can
#                   leave the WSL pty in raw / no-echo mode. We do NOT
#                   `exec` so the trap still fires after Java returns.
trap 'stty sane 2>/dev/null || true' EXIT INT TERM
"${JAVA_BIN:-java}" \
  -Darrow.allocation.manager.type=Unsafe \
  ${JAVA_OPTS:-} \
  -jar "$JAR" </dev/null
