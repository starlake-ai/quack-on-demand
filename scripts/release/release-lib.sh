#!/usr/bin/env bash
#
# Shared helpers for the release scripts:
#   release.sh               - cut a release: verify, stamp versions, tag,
#                               push (CI publishes via release.yml)
#   release-docker.sh        - manual FALLBACK for a broken CI Docker channel
#   release.yml (CI)         - sources this file for cli_version,
#                               next_manager_snapshot, set_cli_* and
#                               verify_quackwire_binaries, verify_qod_node_binaries
#
# Source this file; do not execute it. It anchors CWD at the repo root and
# exposes the version-math helpers plus verify_quackwire_binaries (the
# vendored-binaries check - see scripts/release/refresh-quackwire-binaries.sh for how
# the binaries themselves get refreshed). Steps are idempotent: each no-ops
# the work it detects is already done (tag already present, version.sbt
# already bumped), so an interrupted cut is resumed by re-running release.sh.

# Repo root, derived from this file's own location (scripts/release/release-lib.sh).
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_DIR"

# quackwire_check_build_marker, used by verify_quackwire_binaries.
# shellcheck source=SCRIPTDIR/quackwire-build-info.sh
source "$REPO_DIR/scripts/release/quackwire-build-info.sh"
# qod_node_check_marker and the platform list, used by verify_qod_node_binaries.
# shellcheck source=SCRIPTDIR/qod-node-build-info.sh
source "$REPO_DIR/scripts/release/qod-node-build-info.sh"

REGISTRY_IMAGE="${REGISTRY_IMAGE:-starlakeai/quack-on-demand}"
# Larger heap/stack for the signed publish + scaladoc steps.
SBT_OPTS="${SBT_OPTS:--Xss8M -Xmx5g -XX:+UseG1GC}"
export SBT_OPTS

# ---- terminal / scratch hygiene -----------------------------------------
# Metals' Scala-CLI worksheets drop `.metals-scala-cli/` + `.scala-build/`
# scratch trees compiled with Metals' own (newer) Scala; sbt copies their
# `.tasty` into target/classes and scaladoc aborts with "Forward incompatible
# TASTy file". The release skips `clean`, so nuke them up-front.
purge_metals_scratch() {
  echo "purging Metals Scala-CLI scratch dirs (.metals-scala-cli / .scala-build)..."
  find src target -type d \( -name '.metals-scala-cli' -o -name '.scala-build' \) \
    -prune -exec rm -rf {} + 2>/dev/null || true
}

# ---- preflight helpers ---------------------------------------------------
die() { echo "ERROR: $*" >&2; exit 1; }

require_clean_tree() {
  [[ -z "$(git status --porcelain)" ]] || {
    echo "ERROR: working tree is dirty. Commit or stash before releasing." >&2
    git status --short >&2
    exit 1
  }
}

warn_if_not_main() {
  local branch
  branch="$(git rev-parse --abbrev-ref HEAD)"
  if [[ "$branch" != "main" ]]; then
    if [[ "${RELEASE_YES:-0}" == "1" ]]; then
      echo "WARN: releasing from '$branch', not 'main' (RELEASE_YES=1, continuing)." >&2
    else
      echo "WARN: releasing from '$branch', not 'main'. Continue? [y/N]" >&2
      read -r ans
      [[ "$ans" =~ ^[Yy]$ ]] || exit 1
    fi
  fi
}

require_cmd() { command -v "$1" >/dev/null 2>&1 || die "$1 not on PATH${2:+ ($2)}."; }

# confirm <prompt> - honors RELEASE_YES=1 for non-interactive / orchestrated runs.
confirm() {
  [[ "${RELEASE_YES:-0}" == "1" ]] && return 0
  echo "$1 [y/N]"
  read -r ans
  [[ "$ans" =~ ^[Yy]$ ]]
}

# ---- version math --------------------------------------------------------
manager_version()  { grep -E '^ThisBuild / version' version.sbt | sed -E 's/.*"([^"]+)".*/\1/'; }
libquackwire_version() { grep -E '^val libquackwireVersion' build.sbt | sed -E 's/.*"([^"]+)".*/\1/'; }

strip_snapshot() { echo "${1%-SNAPSHOT}"; }

# sha256_of <file> - prints the hex digest. macOS ships shasum; Linux usually
# ships sha256sum instead (and may lack shasum entirely).
sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

# ---- vendored libquackwire verification ----------------------------------
# Replaces the old Maven-Central-availability gate: binaries are vendored in
# git now (refreshed by scripts/release/refresh-quackwire-binaries.sh), so "is
# libquackwire safe to ship with this manager release" means "does the
# working tree's libquackwire/binaries/ match the pinned version, its own
# checksums and, per binary, its compiled build marker" rather than "is it on
# Central". The VERSION stamp alone vouches for the whole set, so only the
# marker catches a binary left over from an earlier DuckDB. Shared by
# release.sh's phase 1 and by release.yml before any CI channel publishes.
verify_quackwire_binaries() {
  local version stamped
  version="$(grep -E '^val libquackwireVersion' build.sbt | sed -E 's/.*"(.*)".*/\1/')"
  stamped="$(cat libquackwire/binaries/VERSION 2>/dev/null || true)"
  [[ "$stamped" == "$version" ]] \
    || { echo "libquackwire/binaries/VERSION ($stamped) != libquackwireVersion ($version). Run scripts/release/refresh-quackwire-binaries.sh." >&2; return 1; }

  # Mandatory platforms must have their binary present - an unmatched glob in
  # the checksum loop below silently iterates zero times, so a tree missing a
  # whole platform (e.g. a refresh script CI-fetch that came up short) would
  # otherwise pass as long as VERSION happened to already be correct.
  local mandatory=(
    "linux-x86_64:libquackwire.so"
    "linux-aarch64:libquackwire.so"
    "osx-x86_64:libquackwire.dylib"
    "osx-aarch64:libquackwire.dylib"
  )
  local entry plat file missing=()
  for entry in "${mandatory[@]}"; do
    plat="${entry%%:*}"
    file="${entry##*:}"
    [[ -f "libquackwire/binaries/$plat/$file" ]] || missing+=("$plat")
  done
  [[ ${#missing[@]} -eq 0 ]] \
    || { echo "missing vendored libquackwire binaries: ${missing[*]}. Run scripts/release/refresh-quackwire-binaries.sh." >&2; return 1; }

  # windows-{x86_64,aarch64}/quackwire.dll ride along whenever present; when
  # present they are verified below like every other platform's binary.
  local f
  for f in libquackwire/binaries/*/libquackwire.* libquackwire/binaries/*/quackwire.dll; do
    [[ -f "$f" && "$f" != *.sha256 ]] || continue
    [[ -f "$f.sha256" ]] || { echo "missing checksum $f.sha256. Run scripts/release/refresh-quackwire-binaries.sh." >&2; return 1; }
    [[ "$(sha256_of "$f")" == "$(cat "$f.sha256")" ]] \
      || { echo "checksum mismatch for $f. Run scripts/release/refresh-quackwire-binaries.sh." >&2; return 1; }
    quackwire_check_build_marker "$f" "$version" || return 1
  done
  echo "phase 1 OK: vendored libquackwire binaries match $version"
}

# The vendored qod-node binaries (native/qod-node/binaries/, bundled by build.sbt): every Linux and
# macOS platform present, its checksum right, its build marker on the pin, and one DuckDB source id
# for the whole set, which the VERSION stamp names. Shared by release.sh's phase 1 and by
# release.yml / snapshot.yml before any channel publishes.
verify_qod_node_binaries() {
  local root=native/qod-node/binaries plat f id ids=() missing=()
  for plat in "${QOD_NODE_UNIX_PLATFORMS[@]}"; do
    [[ -f "$root/$plat/qod-node" ]] || missing+=("$plat")
  done
  [[ ${#missing[@]} -eq 0 ]] \
    || { echo "missing vendored qod-node binaries: ${missing[*]}. Run scripts/release/refresh-qod-node-binaries.sh." >&2; return 1; }
  for plat in "${QOD_NODE_UNIX_PLATFORMS[@]}"; do
    f="$root/$plat/qod-node"
    [[ -f "$f.sha256" ]] || { echo "missing checksum $f.sha256. Run scripts/release/refresh-qod-node-binaries.sh." >&2; return 1; }
    [[ "$(sha256_of "$f")" == "$(cat "$f.sha256")" ]] \
      || { echo "checksum mismatch for $f. Run scripts/release/refresh-qod-node-binaries.sh." >&2; return 1; }
    id="$(qod_node_check_marker "$f")" || return 1
    ids+=("$id")
  done
  [[ "$(printf '%s\n' "${ids[@]}" | sort -u | wc -l | tr -d ' ')" == "1" ]] \
    || { echo "the vendored qod-node binaries are built against different DuckDB sources (${ids[*]}). Run scripts/release/refresh-qod-node-binaries.sh." >&2; return 1; }
  local expected
  expected="protocol=$(qod_node_pin_protocol);duckdb=$(qod_node_pin_duckdb);duckdb_source=${ids[0]}"
  [[ "$(cat "$root/VERSION" 2>/dev/null || true)" == "$expected" ]] \
    || { echo "$root/VERSION does not read $expected. Run scripts/release/refresh-qod-node-binaries.sh." >&2; return 1; }
  echo "phase 1 OK: vendored qod-node binaries match $expected"
}

# 0.3.5 -> 0.3.6-SNAPSHOT (sbt-release's default patch bump).
next_manager_snapshot() {
  local v; v="$(strip_snapshot "$1")"
  local a b c; IFS=. read -r a b c <<<"$v"
  echo "${a}.${b}.$((c + 1))-SNAPSHOT"
}

# ---- qod CLI (PyPI) --------------------------------------------------------
# The CLI publishes as `qod`; `qod-cli` (the original name) is a shim package
# that pins the matching qod release. Both ship from every release.
CLI_VERSION_FILE="$REPO_DIR/cli/src/qod_cli/__init__.py"
CLI_SHIM_PYPROJECT="$REPO_DIR/cli/shim-qod-cli/pyproject.toml"

cli_version() {
  sed -nE 's|^__version__ = "([^"]+)"$|\1|p' "$CLI_VERSION_FILE"
}

set_cli_version() {
  sed -i.bak -E "s|^__version__ = \".*\"$|__version__ = \"$1\"|" "$CLI_VERSION_FILE"
  rm "${CLI_VERSION_FILE}.bak"
}

# The manager release the CLI pairs with (resolve_jar's offline fallback),
# stamped from version.sbt alongside __version__.
MANAGER_VERSION_FILE="$REPO_DIR/cli/src/qod_cli/_manager_version.py"

cli_manager_version() {
  sed -nE 's|^MANAGER_VERSION = "([^"]+)"$|\1|p' "$MANAGER_VERSION_FILE"
}

set_cli_manager_version() {
  sed -i.bak -E "s|^MANAGER_VERSION = \".*\"$|MANAGER_VERSION = \"$1\"|" "$MANAGER_VERSION_FILE"
  rm "${MANAGER_VERSION_FILE}.bak"
}

set_cli_shim_version() {
  sed -i.bak -E \
    -e "s|^version = \".*\"$|version = \"$1\"|" \
    -e "s|\"qod==[^\"]*\"|\"qod==$1\"|" \
    "$CLI_SHIM_PYPROJECT"
  rm "${CLI_SHIM_PYPROJECT}.bak"
}
# PyPI publication moved to CI (release.yml, 2026-07-26); the local flow
# only stamps versions and pushes the tag, so no PyPI helpers live here.
