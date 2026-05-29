#!/usr/bin/env bash
#
# Spawn one Quack node - invoked by LocalQuackBackend.
#
# Usage:
#   spawn-quack-node.sh <port> <token>
#
# The metastore connection details come from environment variables that
# LocalQuackBackend.start() sets from CreatePoolRequest.metastore. The
# env-var keys match the UI form field names verbatim:
#
#   pgHost pgPort pgUser pgPassword   (Postgres for DuckLake catalog)
#   dbName                             (Postgres DB + DuckDB catalog)
#   schemaName                         (DuckLake schema under $dbName; default `main`.
#                                       MUST differ from $dbName or 2-part identifiers
#                                       like "$dbName"."customer" resolve as ambiguous.)
#   dataPath                           (DuckLake data files directory)
#
# DuckDB is kept alive after `quack_serve` returns via a FIFO held open
# by this shell, so the background quack thread keeps serving until the
# process is terminated (SIGTERM from `LocalQuackBackend.stop`).

set -euo pipefail

PORT="${1:?port required}"
TOKEN="${2:?token required}"

pgHost="${pgHost:-localhost}"
pgPort="${pgPort:-5432}"
pgUser="${pgUser:-postgres}"
pgPassword="${pgPassword:-azizam}"
dbName="${dbName:-db1}"
schemaName="${schemaName:-main}"
dataPath="${dataPath:-/Users/hayssams/git/public/quack-on-demand/ducklake/$dbName}"

if [[ "$schemaName" == "$dbName" ]]; then
  echo "ERROR: schemaName ($schemaName) must differ from dbName ($dbName)." >&2
  echo "       DuckDB rejects 2-part identifiers like \"$dbName\".<table> as" >&2
  echo "       ambiguous when a catalog and a schema share a name." >&2
  exit 1
fi

mkdir -p "$dataPath"

command -v duckdb >/dev/null 2>&1 || {
  echo "ERROR: duckdb not on PATH" >&2
  exit 1
}

# Ensure the Postgres database $dbName exists. Connects to PG_ADMIN_DB (default
# `postgres`) as admin and runs CREATE DATABASE if missing. Skipped when psql
# isn't available - the DuckLake ATTACH below will fail loudly in that case.
if command -v psql >/dev/null 2>&1; then
  ADMIN_DB="${PG_ADMIN_DB:-postgres}"
  EXISTS=$(PGPASSWORD="$pgPassword" psql -h "$pgHost" -p "$pgPort" -U "$pgUser" \
    -d "$ADMIN_DB" -tAc "SELECT 1 FROM pg_database WHERE datname = '$dbName'" 2>/dev/null || true)
  if [[ "$EXISTS" != "1" ]]; then
    echo "spawn: creating Postgres database $dbName"
    PGPASSWORD="$pgPassword" psql -h "$pgHost" -p "$pgPort" -U "$pgUser" \
      -d "$ADMIN_DB" -tAc "CREATE DATABASE \"$dbName\"" >/dev/null || {
        echo "WARN: CREATE DATABASE $dbName failed; ATTACH below may fail" >&2
      }
  fi
fi

# A named pipe keeps duckdb's stdin live without blocking after we feed the
# init SQL. fd 9 holds the writer side open until this shell exits.
FIFO_DIR="$(mktemp -d -t quack-fifo.XXXXXX)"
FIFO="$FIFO_DIR/in"
mkfifo "$FIFO"

# Start duckdb first - open() on FIFO blocks until the writer side appears.
duckdb < "$FIFO" &
DUCK_PID=$!

# Open the writer end; this unblocks duckdb's open().
exec 9> "$FIFO"

cleanup() {
  kill -TERM "$DUCK_PID" 2>/dev/null || true
  wait "$DUCK_PID" 2>/dev/null || true
  exec 9>&- 2>/dev/null || true
  rm -rf "$FIFO_DIR"
}
trap cleanup TERM INT EXIT

# DuckDB does NOT honour the HTTP_PROXY / HTTPS_PROXY env vars for
# `INSTALL <extension>` downloads - those have to be set via the SQL
# `SET http_proxy=...` setting (duckdb forwards to libcurl through that
# setting only). Without this, behind a corporate proxy the very first
# INSTALL hangs and the node never reaches `quack_serve`. We emit the
# SET lines only when a proxy env var is present, so non-proxied
# environments are unaffected.
PROXY_SQL=""
PROXY_URL="${HTTP_PROXY:-${http_proxy:-${HTTPS_PROXY:-${https_proxy:-}}}}"
if [[ -n "$PROXY_URL" ]]; then
  # DuckDB wants "host:port" without the scheme prefix.
  HOSTPORT="${PROXY_URL#http://}"
  HOSTPORT="${HOSTPORT#https://}"
  HOSTPORT="${HOSTPORT%/}"
  PROXY_SQL="SET http_proxy = '$HOSTPORT';"
fi

# Feed init SQL
cat >&9 <<SQL
$PROXY_SQL
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
INSTALL quack;    LOAD quack;

ATTACH 'ducklake:postgres:host=$pgHost port=$pgPort dbname=$dbName user=$pgUser password=$pgPassword' AS $dbName
  (DATA_PATH '$dataPath');

USE $dbName;
CREATE SCHEMA IF NOT EXISTS $schemaName;
USE $dbName.$schemaName;

CALL quack_serve('quack:0.0.0.0:$PORT', token := '$TOKEN', allow_other_hostname := true);
SQL

# Block until duckdb is killed (typically SIGTERM from the manager).
wait "$DUCK_PID"
