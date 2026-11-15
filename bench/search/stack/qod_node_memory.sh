#!/usr/bin/env bash
# Caps the part 2 QoD node's DuckDB memory_limit through the supported pool setting: the pool's
# initSql (POST /api/pool/setInitSql, written by the superuser, so trusted), which applies at
# the next node start; then restarts the node (POST /api/node/restart) and reads the setting.
# Usage: qod_node_memory.sh <p2> <limit, e.g. 62GiB>
# Why: spec 11 wants the same memory on both sides; ParadeDB's Docker VM has 62.7 GiB.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WT="$(cd "$HERE/../../.." && pwd)"
. "$HERE/qod_env.sh" "$1"
LIMIT="$2"
case "$LIMIT" in *[!0-9A-Za-z.]*|"") echo "bad limit" >&2; exit 2;; esac
QOD="$WT/cli/.venv/bin/qod"
"$QOD" pool set-init-sql --tenant acme --db acme_lake --pool bi --init-sql "SET memory_limit = '$LIMIT';" > /dev/null
NODE="$("$QOD" --json node list --tenant acme --pool bi | python3 -I -c 'import json,sys; r=json.load(sys.stdin); print(r[0]["node"])')"
"$QOD" node restart --tenant acme --db acme_lake --pool bi --node-id "$NODE" > /dev/null
for i in $(seq 1 120); do
  ok="$("$QOD" --json node list --tenant acme --pool bi | python3 -I -c 'import json,sys; r=json.load(sys.stdin); print(1 if r and r[0]["healthy"] else 0)')"
  [ "$ok" = 1 ] && break
  [ "$i" = 120 ] && { echo "node not healthy after 120 s" >&2; exit 1; }
  sleep 1
done
"$HERE/qod_sql.sh" "$1" --csv "SELECT current_setting('memory_limit') AS memory_limit, current_setting('threads') AS threads" 2>/dev/null
