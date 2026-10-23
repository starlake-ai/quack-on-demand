#!/usr/bin/env bash
#
# Refresh the vendored qod-node binaries under native/qod-node/binaries/.
#
# qod-node (native/qod-node) is the process every local node runs. The manager jar bundles one per
# Linux and macOS platform (build.sbt) and installs the running platform's at boot
# (QodNodeBinary.scala). Windows bundles none: its static qod-node.exe is a release asset the
# launchers download.
#
# Builds the HOST platform via cmake against the pinned libduckdb ($DUCKDB_CACHE_DIR/<abi>/,
# default .duckdb/<abi>/) with the relative rpath a vendored binary needs
# (QOD_NODE_RELATIVE_RPATH: libduckdb only in ../lib next to it), downloads the other Linux and
# macOS platforms from a green 'qod-node' CI run of the commit being refreshed (FETCH_CI=0
# vendors the host platform alone), checks every binary's build marker against the pin (node
# protocol, DuckDB release, and one DuckDB source id for the whole set), regenerates the .sha256
# companions and the VERSION stamp, and STOPS. Nothing under native/qod-node/binaries/ changes
# unless every binary passes. Review the diff and commit like any other change.
#
# The CI run defaults to the latest successful qod-node.yml run whose head commit is HEAD (push it
# and let the workflow run first, or start it with workflow_dispatch); QOD_NODE_CI_RUN=<run id>
# picks another run.
#
# Usage: ./scripts/release/refresh-qod-node-binaries.sh
set -euo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_DIR"
# shellcheck source=SCRIPTDIR/qod-node-build-info.sh
source "$REPO_DIR/scripts/release/qod-node-build-info.sh"

FETCH_CI="${FETCH_CI:-1}"
VENDORED=native/qod-node/binaries

protocol="$(qod_node_pin_protocol)"
[[ -n "$protocol" ]] || { echo "cannot read QOD_NODE_PROTOCOL from native/qod-node/CMakeLists.txt" >&2; exit 1; }
duckdb="$(qod_node_pin_duckdb)"
[[ -n "$duckdb" ]] || { echo "cannot read Versions.duckdb from project/Versions.scala" >&2; exit 1; }
pin="${duckdb#v}"

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

# --- host platform: cmake build with the relative rpath ---
case "$(uname -s)" in
  Darwin) host_os=osx ;;
  Linux)  host_os=linux ;;
  *) echo "unsupported OS for a vendored qod-node: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64)  host_arch=x86_64 ;;
  aarch64|arm64) host_arch=aarch64 ;;
  *) echo "unsupported arch for a vendored qod-node: $(uname -m)" >&2; exit 1 ;;
esac
host_platform="$host_os-$host_arch"

duckdb_home="${DUCKDB_CACHE_DIR:-$REPO_DIR/.duckdb}/$pin"
[[ -f "$duckdb_home/include/duckdb/planner/planner_extension.hpp" && -e "$duckdb_home/lib" ]] \
  || { echo "pinned libduckdb cache missing at $duckdb_home (need lib/ and include/ with the internal header tree). Run 'QOD_VERSION=BUILD ./scripts/run-jar.sh' once to populate it, then re-run." >&2; exit 1; }
echo "cmake build [$host_platform] (DUCKDB_HOME=$duckdb_home)..."
rm -rf native/qod-node/build-release
cmake -S native/qod-node -B native/qod-node/build-release -DCMAKE_BUILD_TYPE=Release \
  -DQOD_NODE_RELATIVE_RPATH=ON -DDUCKDB_HOME="$duckdb_home"
cmake --build native/qod-node/build-release --config Release --target qod-node
host_built=native/qod-node/build-release/qod-node
source_id="$(qod_node_check_marker "$host_built")" \
  || { echo "the host build does not match the pin; nothing was changed." >&2; exit 1; }

staged=("$host_platform:$host_built")

# --- the other platforms from CI ---
if [[ "$FETCH_CI" == "1" ]]; then
  head_sha="$(git rev-parse HEAD)"
  if [[ -n "${QOD_NODE_CI_RUN:-}" ]]; then
    ci_run_id="$QOD_NODE_CI_RUN"
  else
    echo "looking up the latest successful qod-node run of $head_sha..."
    ci_run_id=$(gh run list --workflow=qod-node.yml --commit="$head_sha" --status=success \
                --limit=1 --json databaseId --jq '.[0].databaseId')
  fi
  [[ -n "$ci_run_id" && "$ci_run_id" != "null" ]] \
    || { echo "no successful qod-node run of $head_sha. Push it and let qod-node.yml run (or start it with workflow_dispatch), set QOD_NODE_CI_RUN=<run id>, or FETCH_CI=0 for the host platform alone." >&2; exit 1; }
  echo "downloading platform artifacts from CI run $ci_run_id..."
  tmp_artifacts=$(mktemp -d)
  trap 'rm -rf "$tmp_artifacts"' EXIT
  gh run download "$ci_run_id" --dir "$tmp_artifacts" --pattern 'qod-node-*'
  for plat in "${QOD_NODE_UNIX_PLATFORMS[@]}"; do
    [[ "$plat" == "$host_platform" ]] && continue
    f="$tmp_artifacts/qod-node-$plat/qod-node"
    [[ -f "$f" ]] || { echo "CI run $ci_run_id has no qod-node-$plat artifact; nothing was changed." >&2; exit 1; }
    id="$(qod_node_check_marker "$f")" \
      || { echo "CI run $ci_run_id: the $plat artifact does not match the pin; nothing was changed." >&2; exit 1; }
    [[ "$id" == "$source_id" ]] \
      || { echo "CI run $ci_run_id: $plat is built against DuckDB source $id, the host against $source_id; nothing was changed." >&2; exit 1; }
    staged+=("$plat:$f")
  done
fi

# --- vendor ---
for entry in "${staged[@]}"; do
  plat="${entry%%:*}"
  mkdir -p "$VENDORED/$plat"
  cp "${entry#*:}" "$VENDORED/$plat/qod-node"
  chmod 755 "$VENDORED/$plat/qod-node"
  sha256_of "$VENDORED/$plat/qod-node" > "$VENDORED/$plat/qod-node.sha256"
done
printf 'protocol=%s;duckdb=%s;duckdb_source=%s\n' "$protocol" "$duckdb" "$source_id" > "$VENDORED/VERSION"
echo "vendored: ${staged[*]%%:*}"
echo "refreshed. Review with 'git status $VENDORED' and commit (git update-index --chmod=+x on a new binary: core.fileMode may be off)."
