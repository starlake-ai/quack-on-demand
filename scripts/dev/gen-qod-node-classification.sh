#!/usr/bin/env bash
# Regenerates the in-engine authorization classification from what qod-node resolves:
#   native/qod-node/functions.json, native/qod-node/settings.json   (qod-node --list-functions /
#     --list-settings, every extension a node may load installed and loaded first), then through
#     scripts/dev/classify-qod-node.py:
#   src/main/resources/authz/function-classes.tsv, src/main/resources/authz/setting-classes.tsv,
#   native/qod-node/file-functions.txt, native/qod-node/admitted-table-functions.txt.
#
# Run it at every DuckDB pin bump and whenever qod-node registers a function; then rebuild qod-node
# (CMake embeds the two .txt lists) and run FunctionClassesSpec and SettingClassesSpec. Local: it runs
# the host's qod-node build ($QOD_NODE_BIN, else native/qod-node/build/qod-node), which downloads the
# extensions into DuckDB's default extension directory when they are missing there.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
node="${QOD_NODE_BIN:-$root/native/qod-node/build/qod-node}"
if [[ ! -x "$node" ]]; then
  echo "ERROR: no qod-node at $node (build native/qod-node first, or set QOD_NODE_BIN)" >&2
  exit 1
fi

# Every extension a node may INSTALL: the spawn scripts (httpfs, avro, azure, ducklake, postgres), the
# object-store and federation setup SQL (httpfs, azure, iceberg, aws, postgres) and qod-node's strict
# init (quack). mysql is left out: the mysql extension is not in the 2.0 toolchain
# (RdbmsConfig.scala), so a node that loads it gets functions no table classifies, refused to
# non-superusers.
extensions=(httpfs aws azure postgres iceberg avro ducklake quack)

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
for e in "${extensions[@]}"; do
  printf 'INSTALL %s;\nLOAD %s;\n' "$e" "$e" >> "$work/init.sql"
done
statements=$((2 * ${#extensions[@]}))

# Strict: a listing made without one of the extensions would classify a node that has it wrongly.
list() {
  local mode="$1" out="$2"
  if ! "$node" "--$mode" --init "$work/init.sql" > "$work/$mode.json" 2> "$work/$mode.err"; then
    cat "$work/$mode.err" >&2
    exit 1
  fi
  if ! grep -qF "$statements of $statements statement(s) ran" "$work/$mode.err"; then
    echo "ERROR: --$mode did not install and load every extension:" >&2
    cat "$work/$mode.err" >&2
    exit 1
  fi
  mv "$work/$mode.json" "$out"
}
list list-functions "$root/native/qod-node/functions.json"
list list-settings "$root/native/qod-node/settings.json"
python3 "$root/scripts/dev/classify-qod-node.py"
