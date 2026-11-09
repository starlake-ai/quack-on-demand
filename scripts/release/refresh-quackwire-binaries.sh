#!/usr/bin/env bash
#
# Refresh the vendored libquackwire binaries under libquackwire/binaries/.
#
# Builds the HOST platform via cmake (linked against the pinned libduckdb in
# $DUCKDB_CACHE_DIR/<abi>/, default .duckdb/<abi>/), downloads the other
# platforms from a green 'quackwire native build' CI run of the commit being
# refreshed (a Linux host takes its own platform from CI too: a binary links the
# glibc it is built against, so only CI's manylinux_2_28 build meets the floor of
# glibc-floor.sh), checks every binary's compiled build marker against the pin
# (Quack protocol, DuckDB release, duckdb-quack commit: see
# quackwire-build-info.sh), regenerates the .sha256 companions and the VERSION
# stamp, and STOPS. Nothing in libquackwire/binaries/ changes unless every
# binary passes. Review the diff and commit like any other change. No Maven, no
# GPG, no Sonatype.
#
# The CI run defaults to the latest successful quackwire.yml run whose head
# commit is HEAD (push it and let the workflow run first, or start it with
# workflow_dispatch); QUACKWIRE_CI_RUN=<run id> picks another run. Never "the
# latest green run on main": that run can predate the pin and its artifacts
# would be vendored under the new stamp. The marker check refuses them anyway.
#
# Usage: ./scripts/release/refresh-quackwire-binaries.sh
set -euo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_DIR"
# shellcheck source=SCRIPTDIR/quackwire-build-info.sh
source "$REPO_DIR/scripts/release/quackwire-build-info.sh"
# shellcheck source=SCRIPTDIR/glibc-floor.sh
source "$REPO_DIR/scripts/release/glibc-floor.sh"

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
build_host=1
[[ "$host_os" != linux ]] || build_host=0
if [[ "$build_host" == "1" ]]; then
  duckdb_abi="${version%%-*}"
  duckdb_home="${DUCKDB_CACHE_DIR:-$REPO_DIR/.duckdb}/$duckdb_abi"
  [[ -f "$duckdb_home/include/duckdb/common/serializer/binary_serializer.hpp" \
     && -e "$duckdb_home/lib" ]] \
    || { echo "pinned libduckdb cache missing at $duckdb_home (need lib/ + include/ with the internal header tree). Run 'QOD_VERSION=BUILD ./scripts/run-jar.sh' once to populate it, then re-run." >&2; exit 1; }
  echo "cmake build [$host_platform] (DUCKDB_HOME=$duckdb_home)..."
  ( cd native/quackwire \
    && rm -rf build \
    && DUCKDB_HOME="$duckdb_home" cmake -B build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build --config Release )
  host_built="native/quackwire/build/libquackwire.$host_ext"
  quackwire_check_build_marker "$host_built" "$version" \
    || { echo "the host build does not match $version; nothing was changed." >&2; exit 1; }
fi

# --- fetch the other platforms from CI (ported from step 4) ---
# Download the other platforms from a green quackwire CI run of this commit -
# same matrix CI uses, so bit-identical binaries without a local cross-compile
# toolchain.
head_sha="$(git rev-parse HEAD)"
if [[ -n "${QUACKWIRE_CI_RUN:-}" ]]; then
  ci_run_id="$QUACKWIRE_CI_RUN"
else
  echo "looking up the latest successful 'quackwire native build' run of $head_sha..."
  ci_run_id=$(gh run list --workflow=quackwire.yml --commit="$head_sha" --status=success \
              --limit=1 --json databaseId --jq '.[0].databaseId')
fi
[[ -n "$ci_run_id" && "$ci_run_id" != "null" ]] \
  || { echo "no successful quackwire native build run of $head_sha. Push it and let quackwire.yml run (or start it with workflow_dispatch), or set QUACKWIRE_CI_RUN=<run id>." >&2; exit 1; }
echo "downloading platform artifacts from CI run $ci_run_id..."
tmp_artifacts=$(mktemp -d)
trap 'rm -rf "$tmp_artifacts"' EXIT
gh run download "$ci_run_id" --dir "$tmp_artifacts" --pattern 'quackwire-*'

# Check every downloaded binary BEFORE touching the vendored tree: an artifact
# built against another DuckDB or duckdb-quack than the pin is refused.
staged=()
for d in "$tmp_artifacts"/quackwire-*; do
  plat=$(basename "$d" | sed 's/^quackwire-//')
  [[ "$plat" == "$host_platform" && "$build_host" == "1" ]] && continue
  # Unix artifacts carry libquackwire.{so,dylib}; the Windows artifact carries
  # the un-prefixed quackwire.dll.
  for f in "$d"/libquackwire.so "$d"/libquackwire.dylib "$d"/quackwire.dll; do
    [[ -f "$f" ]] || continue
    quackwire_check_build_marker "$f" "$version" \
      || { echo "CI run $ci_run_id: the $plat artifact does not match $version; nothing was changed." >&2; exit 1; }
    if [[ "$plat" == linux-* ]]; then
      glibc_floor_check "$f" || { echo "CI run $ci_run_id: nothing was changed." >&2; exit 1; }
    fi
    staged+=("$plat:$f")
  done
done
[[ ${#staged[@]} -gt 0 ]] || { echo "CI run $ci_run_id has no quackwire-* platform artifacts." >&2; exit 1; }

if [[ "$build_host" == "1" ]]; then
  mkdir -p "libquackwire/binaries/$host_platform"
  cp "$host_built" "libquackwire/binaries/$host_platform/libquackwire.$host_ext"
fi
for entry in "${staged[@]}"; do
  plat="${entry%%:*}"
  mkdir -p "libquackwire/binaries/$plat"
  cp "${entry#*:}" "libquackwire/binaries/$plat/"
done
echo "staged platforms:"; ls libquackwire/binaries/*/

# --- checksums + version stamp ---
for f in libquackwire/binaries/*/libquackwire.* libquackwire/binaries/*/quackwire.dll; do
  [[ -f "$f" && "$f" != *.sha256 ]] || continue
  sha256_of "$f" > "$f.sha256"
done
printf '%s\n' "$version" > libquackwire/binaries/VERSION
echo "refreshed. Review with 'git status libquackwire/binaries/' and commit."
