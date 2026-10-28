#!/usr/bin/env bash
# Regenerates the in-engine authorization classification from what qod-node resolves:
#   native/qod-node/functions.json, native/qod-node/settings.json   (qod-node --list-functions /
#     --list-settings, every extension a node may load installed and loaded first);
#   native/qod-node/macros.json   (the built-in macros and table macros, from duckdb_functions() of
#     the release DuckDB CLI ($DUCKDB_CLI, the node's own DuckDB release) with the same extensions
#     loaded: each definition parsed by json_serialize_sql, reduced to its calls); then through
#     scripts/dev/classify-qod-node.py:
#   src/main/resources/authz/function-classes.tsv, src/main/resources/authz/setting-classes.tsv,
#   native/qod-node/file-functions.txt, native/qod-node/admitted-table-functions.txt;
#   and through scripts/dev/gen-qod-node-function-classes.py, native/qod-node/src/function_classes.inc.
#
# Run it at every DuckDB pin bump and whenever qod-node registers a function (DUCKDB_CLI set to the
# release CLI of the node's DuckDB); then rebuild qod-node
# (CMake embeds the two .txt lists, the parse screen includes the .inc) and run FunctionClassesSpec,
# FunctionClassesHeaderSpec and SettingClassesSpec. Local: it runs
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

# The macros: duckdb_functions() lists them, with their definitions, only through SQL; qod-node has
# no listing of them, so the release CLI of the node's DuckDB reads them (Task 19).
cli="${DUCKDB_CLI:-}"
if [[ -z "$cli" || ! -x "$cli" ]]; then
  echo "ERROR: set DUCKDB_CLI to the release duckdb CLI of the node's DuckDB" >&2
  exit 1
fi
node_duckdb="$("$node" --build-info | sed -n 's/.*;duckdb=\([^;]*\);duckdb_source=\([^;]*\).*/\1 \2/p')"
cli_duckdb="$("$cli" -no-agent -version | awk '{print $1, $NF}')"
if [[ -z "$node_duckdb" || "$node_duckdb" != "$cli_duckdb" ]]; then
  echo "ERROR: DUCKDB_CLI is DuckDB '$cli_duckdb', qod-node runs '$node_duckdb'" >&2
  exit 1
fi
# The extensions first, one statement per line (a failing line skips the rest of it), then the dump.
cp "$work/init.sql" "$work/macros.sql"
cat >> "$work/macros.sql" <<'SQL'
COPY (
  SELECT schema_name AS schema, function_name AS name, function_type AS type,
         json_serialize_sql(CASE WHEN function_type = 'macro' THEN 'SELECT ' || macro_definition
                                 ELSE macro_definition END) AS tree
  FROM duckdb_functions()
  WHERE database_name = 'system' AND internal AND function_type IN ('macro', 'table_macro')
  ORDER BY schema, name, type, tree::VARCHAR
) TO '/dev/stdout' (FORMAT json, ARRAY true);
SQL
if ! "$cli" -no-agent -batch -bail -f "$work/macros.sql" > "$work/macros-dump.json" \
    2> "$work/macros.err"; then
  cat "$work/macros.err" >&2
  exit 1
fi
python3 "$root/scripts/dev/classify-qod-node.py" --macros-dump "$work/macros-dump.json"
python3 "$root/scripts/dev/gen-qod-node-function-classes.py"
