#!/usr/bin/env bash
# Spike driver: qod-node serves a DuckLake table, a secure view, a table macro and a plain table
# over quack; the release DuckDB CLI queries them as a quack client; the node log shows what the
# post_bind / pre_optimize hooks and prepare finalization saw for every statement.
#
#   DUCKDB_CLI=<release duckdb CLI> [QOD_NODE_BIN=<qod-node>] run-spike.sh <run dir> [port]
#
# The run dir is wiped and recreated; it holds the DuckLake files, the SQL and node.log.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
node_bin="${QOD_NODE_BIN:-$here/../build/qod-node}"
duckdb_cli="${DUCKDB_CLI:?set DUCKDB_CLI to the release duckdb CLI}"
run_dir="${1:?usage: run-spike.sh <run dir> [port]}"
port="${2:-23456}"

rm -rf "$run_dir"
mkdir -p "$run_dir/lake/data"
# Paths written into SQL reach DuckDB verbatim: on Windows (Git Bash) they must be Windows paths.
if command -v cygpath > /dev/null 2>&1; then
  sql_dir="$(cygpath -m "$run_dir")"
else
  sql_dir="$run_dir"
fi

cat > "$run_dir/serve.sql" <<SQL
INSTALL quack;
LOAD quack;
INSTALL ducklake;
LOAD ducklake;
ATTACH 'ducklake:$sql_dir/lake/meta.ducklake' AS lake (DATA_PATH '$sql_dir/lake/data/');
CREATE TABLE lake.main.orders AS SELECT range AS id, range * 10 AS amount, 'c' || (range % 3) AS customer FROM range(10);
CREATE TABLE memory.main.notes AS SELECT 1 AS id, 'n1' AS note;
CREATE SECURE VIEW memory.main.order_totals AS SELECT customer, sum(amount) AS total FROM lake.main.orders GROUP BY customer;
CREATE MACRO memory.main.big_orders() AS TABLE SELECT * FROM lake.main.orders WHERE amount > 50;
SET GLOBAL quack_authorization_function = 'qod_screen';
SELECT listen_url FROM quack_serve('quack:127.0.0.1:$port', token := 'spike', disable_ssl := true);
SQL

cat > "$run_dir/client.sql" <<SQL
INSTALL quack;
LOAD quack;
ATTACH 'quack:127.0.0.1:$port' AS q (TYPE quack, TOKEN 'spike');
SELECT count(*) AS orders FROM q.lake.main.orders;
FROM quack_query_by_name('q', 'SELECT customer, total FROM memory.main.order_totals ORDER BY customer');
FROM quack_query_by_name('q', 'SELECT count(*) AS big FROM memory.main.big_orders()');
FROM quack_query_by_name('q', 'SELECT id FROM lake.main.orders WHERE id IN (SELECT id FROM memory.main.notes)');
FROM quack_query_by_name('q', 'SELECT * FROM query(''SELECT note FROM memory.main.notes'')');
FROM quack_query_by_name('q', 'SELECT current_setting(''threads'') AS threads');
FROM quack_query_by_name('q', 'SELECT 1 AS qod_spike_refuse');
SQL

"$node_bin" --init "$run_dir/serve.sql" > "$run_dir/node.log" 2>&1 &
node_pid=$!
trap 'kill "$node_pid" 2>/dev/null || true' EXIT

for _ in $(seq 1 300); do
  if grep -q '\[qod-node\] ready' "$run_dir/node.log"; then
    break
  fi
  sleep 0.2
done
if ! grep -q '\[qod-node\] ready' "$run_dir/node.log"; then
  echo "qod-node did not become ready"
  cat "$run_dir/node.log"
  exit 1
fi

echo "== client output"
"$duckdb_cli" < "$run_dir/client.sql" 2>&1 || true

kill "$node_pid" 2>/dev/null || true
wait "$node_pid" 2>/dev/null || true
trap - EXIT

echo "== node log"
cat "$run_dir/node.log"
