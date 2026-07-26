#!/usr/bin/env bash
#
# Refresh the vendored libquackwire binaries under libquackwire/binaries/.
#
# Builds the HOST platform via cmake (linked against the pinned libduckdb in
# .duckdb/<abi>/), downloads the other platforms from the latest green
# 'quackwire native build' CI run on main, regenerates the .sha256 companions
# and the VERSION stamp, and STOPS. Review the diff and commit like any other
# change. No Maven, no GPG, no Sonatype.
#
# Usage: ./scripts/refresh-quackwire-binaries.sh
set -euo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

version="$(grep -E '^val libquackwireVersion' build.sbt | sed -E 's/.*"(.*)".*/\1/')"
[[ -n "$version" ]] || { echo "cannot read libquackwireVersion from build.sbt" >&2; exit 1; }

# sha256_of <file> - prints the hex digest. macOS ships shasum; Linux usually
# ships sha256sum instead (and may lack shasum entirely). Duplicated from
# release-lib.sh's helper of the same name: this script stays standalone
# (no release-lib.sh sourcing) rather than take on its manager-release-phase
# preflight helpers for a two-line dependency.
sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

# --- host platform detection + cmake build (ported from release-libquackwire.sh steps 3) ---
case "$(uname -s)" in
  Darwin) host_os=osx;   host_ext=dylib ;;
  Linux)  host_os=linux; host_ext=so    ;;
  *) echo "unsupported OS for libquackwire: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64)  host_arch=x86_64  ;;
  aarch64|arm64) host_arch=aarch64 ;;
  *) echo "unsupported arch for libquackwire: $(uname -m)" >&2; exit 1 ;;
esac
host_platform="$host_os-$host_arch"

# Link against the PINNED libduckdb from the .duckdb cache, never the
# operator's system install: a Homebrew/apt libduckdb at another version
# links cleanly and mismatches at runtime (same fix as run-jar.sh's BUILD
# path, which is also the easiest way to populate the cache: one
# `QOD_VERSION=BUILD ./scripts/run-jar.sh` boot stages bin/, lib/, and the
# internal-header include/ tree for the pinned version). The build dir is
# wiped because CMake caches resolved find results.
duckdb_abi="${version%%-*}"
duckdb_home="$REPO_DIR/.duckdb/$duckdb_abi"
[[ -f "$duckdb_home/include/duckdb/common/serializer/binary_serializer.hpp" \
   && -e "$duckdb_home/lib" ]] \
  || { echo "pinned libduckdb cache missing at $duckdb_home (need lib/ + include/ with the internal header tree). Run 'QOD_VERSION=BUILD ./scripts/run-jar.sh' once to populate it, then re-run." >&2; exit 1; }
echo "cmake build [$host_platform] (DUCKDB_HOME=$duckdb_home)..."
( cd native/quackwire \
  && rm -rf build \
  && DUCKDB_HOME="$duckdb_home" cmake -B build -DCMAKE_BUILD_TYPE=Release \
  && cmake --build build --config Release )
mkdir -p "libquackwire/binaries/$host_platform"
cp "native/quackwire/build/libquackwire.$host_ext" \
   "libquackwire/binaries/$host_platform/libquackwire.$host_ext"

# --- fetch the other platforms from CI (ported from step 4) ---
# Download the other platforms from the latest green quackwire CI run on
# main - same matrix CI uses, so bit-identical binaries without a local
# cross-compile toolchain.
echo "looking up the latest 'quackwire native build' run on main..."
ci_run_id=$(gh run list --workflow=quackwire.yml --branch=main --status=success \
            --limit=1 --json databaseId --jq '.[0].databaseId')
[[ -n "$ci_run_id" && "$ci_run_id" != "null" ]] \
  || { echo "no successful quackwire native build run on main yet. Push a libquackwire change and wait for CI." >&2; exit 1; }
echo "downloading platform artifacts from CI run $ci_run_id..."
tmp_artifacts=$(mktemp -d)
gh run download "$ci_run_id" --dir "$tmp_artifacts" --pattern 'quackwire-*'
for d in "$tmp_artifacts"/quackwire-*; do
  plat=$(basename "$d" | sed 's/^quackwire-//')
  [[ "$plat" == "$host_platform" ]] && continue
  mkdir -p "libquackwire/binaries/$plat"
  # Unix artifacts carry libquackwire.{so,dylib}; the Windows artifact carries
  # the un-prefixed quackwire.dll. Copy whichever is present.
  cp "$d"/libquackwire.* "libquackwire/binaries/$plat/" 2>/dev/null || true
  cp "$d"/quackwire.dll  "libquackwire/binaries/$plat/" 2>/dev/null || true
done
rm -rf "$tmp_artifacts"
echo "staged platforms:"; ls libquackwire/binaries/*/

# --- checksums + version stamp ---
for f in libquackwire/binaries/*/libquackwire.* libquackwire/binaries/*/quackwire.dll; do
  [[ -f "$f" && "$f" != *.sha256 ]] || continue
  sha256_of "$f" > "$f.sha256"
done
printf '%s\n' "$version" > libquackwire/binaries/VERSION
echo "refreshed. Review with 'git status libquackwire/binaries/' and commit."
