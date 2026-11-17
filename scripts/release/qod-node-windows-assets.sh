#!/usr/bin/env bash
#
# The Windows qod-node release assets, from the qod-node workflow's artifacts (qod-node-windows-<arch>/: the
# qod-node.exe, the build-info.txt its --build-info printed and, for a build linking qod_search_core, its
# third-party notices). Writes into the output directory (made if missing; nothing goes anywhere else), for each
# arch, qod-node-windows-<arch>.exe and its .sha256 (naming the bare file name) and, for a search build,
# qod-node-windows-<arch>-THIRD-PARTY-NOTICES-qod-search.txt beside them (search spec 13), and prints the path of
# each file it wrote, <output dir>/<asset>, one per line.
#
# The second argument is the search setting the publishing workflow ships (QOD_NODE_SEARCH, OFF while search
# Phase 1 is unmerged: OWNER RULING 2026-11-16, D2): an exe built otherwise, or a search build without its
# notices, is refused and nothing is printed (a refusal can leave the first arch's files in the output directory).
#
# Called by release.yml (github-release) and qod-node.yml (snapshot-assets).
#
# Usage: scripts/release/qod-node-windows-assets.sh <artifacts dir> <ON|OFF> <output dir>
set -euo pipefail
# shellcheck source=SCRIPTDIR/qod-node-build-info.sh
source "$(dirname "${BASH_SOURCE[0]}")/qod-node-build-info.sh"

dir="${1:?artifacts dir}"
expected="${2:?ON or OFF}"
out="${3:?output dir}"
[[ "$expected" == ON || "$expected" == OFF ]] || { echo "the search setting must be ON or OFF, not $expected" >&2; exit 2; }
mkdir -p "$out"

assets=()
for arch in x86_64 aarch64; do
  src="$dir/qod-node-windows-$arch"
  [[ -f "$src/qod-node.exe" && -f "$src/build-info.txt" ]] \
    || { echo "$src lacks qod-node.exe or build-info.txt" >&2; exit 1; }
  case "$(head -n1 "$src/build-info.txt" | tr -d '\r')" in
    *" search=on") flag=ON ;;
    *" search=off") flag=OFF ;;
    *) echo "$src/build-info.txt names no search setting" >&2; exit 1 ;;
  esac
  bytes="$(qod_node_search_flag "$src/qod-node.exe")"
  [[ "$bytes" == "$(echo "$flag" | tr '[:upper:]' '[:lower:]')" ]] \
    || { echo "$src/qod-node.exe reads search=$bytes, its build-info.txt search=$flag" >&2; exit 1; }
  [[ "$flag" == "$expected" ]] \
    || { echo "qod-node-windows-$arch.exe is built with QOD_NODE_SEARCH=$flag; this release ships $expected" >&2; exit 1; }
  exe="qod-node-windows-$arch.exe"
  cp "$src/qod-node.exe" "$out/$exe"
  ( cd "$out" && shasum -a 256 "$exe" > "$exe.sha256" )
  assets+=("$out/$exe" "$out/$exe.sha256")
  if [[ "$flag" == ON ]]; then
    [[ -f "$src/$QOD_SEARCH_NOTICES_NAME" ]] \
      || { echo "qod-node-windows-$arch.exe links qod_search_core and its artifact carries no $QOD_SEARCH_NOTICES_NAME" >&2; exit 1; }
    cp "$src/$QOD_SEARCH_NOTICES_NAME" "$out/qod-node-windows-$arch-$QOD_SEARCH_NOTICES_NAME"
    assets+=("$out/qod-node-windows-$arch-$QOD_SEARCH_NOTICES_NAME")
  fi
done
printf '%s\n' "${assets[@]}"
