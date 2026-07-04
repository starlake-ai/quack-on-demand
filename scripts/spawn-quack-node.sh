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

kind="${kind:?kind env var is required (ducklake | duckdb-file | memory)}"
case "$kind" in
  ducklake|duckdb-file|memory) ;;
  *)
    echo "fatal: unknown kind='$kind' (expected: ducklake | duckdb-file | memory)" >&2
    exit 92
    ;;
esac

if [[ "$schemaName" == "$dbName" ]]; then
  echo "ERROR: schemaName ($schemaName) must differ from dbName ($dbName)." >&2
  echo "       DuckDB rejects 2-part identifiers like \"$dbName\".<table> as" >&2
  echo "       ambiguous when a catalog and a schema share a name." >&2
  exit 1
fi

# Detect whether dataPath points at a remote object store. DuckLake accepts
# s3:// (covers AWS S3, SeaweedFS, MinIO, R2, GCS via the S3-interop endpoint)
# and azure:// / abfss:// when the matching DuckDB extension is loaded. For
# remote schemes we skip the local mkdir (the parent dir doesn't exist on the
# container fs) and emit the SQL needed to install httpfs/azure + a SECRET so
# the ATTACH below can read/write parquet against the bucket.
IS_REMOTE=0
STORAGE_SQL=""
case "$dataPath" in
  s3://*|s3a://*|gs://*|r2://*)
    IS_REMOTE=1
    STORAGE_SQL="INSTALL httpfs; LOAD httpfs;"
    if [[ -n "${QOD_S3_ACCESS_KEY_ID:-}" && -n "${QOD_S3_SECRET_ACCESS_KEY:-}" ]]; then
      # Strip http(s):// from the endpoint - DuckDB wants "host:port".
      ep="${QOD_S3_ENDPOINT:-}"
      ep="${ep#http://}"; ep="${ep#https://}"; ep="${ep%/}"
      STORAGE_SQL="$STORAGE_SQL
CREATE OR REPLACE SECRET quack_s3 (
  TYPE s3,
  KEY_ID '${QOD_S3_ACCESS_KEY_ID}',
  SECRET '${QOD_S3_SECRET_ACCESS_KEY}',
  REGION '${QOD_S3_REGION:-us-east-1}',
  ENDPOINT '${ep}',
  URL_STYLE '${QOD_S3_URL_STYLE:-path}',
  USE_SSL ${QOD_S3_USE_SSL:-true}
);"
    fi
    ;;
  az://*|azure://*|abfss://*)
    IS_REMOTE=1
    STORAGE_SQL="INSTALL azure; LOAD azure;"
    if [[ -n "${QOD_AZURE_CONNECTION_STRING:-}" ]]; then
      STORAGE_SQL="$STORAGE_SQL
CREATE OR REPLACE SECRET quack_azure (
  TYPE azure,
  CONNECTION_STRING '${QOD_AZURE_CONNECTION_STRING}'
);"
    fi
    ;;
esac

if [[ "$kind" != "memory" && "$IS_REMOTE" == "0" ]]; then
  mkdir -p "$dataPath"
fi

command -v duckdb >/dev/null 2>&1 || {
  echo "ERROR: duckdb not on PATH" >&2
  exit 1
}

# Ensure the Postgres database $dbName exists. Connects to PG_ADMIN_DB (default
# `postgres`) as admin and runs CREATE DATABASE if missing. Skipped when psql
# isn't available - the DuckLake ATTACH below will fail loudly in that case.
# Only needed for kind=ducklake.
if [[ "$kind" == "ducklake" ]] && command -v psql >/dev/null 2>&1; then
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

# Feed init SQL. Per-kind branching below:
#   ducklake    - INSTALL/ATTACH the DuckLake catalog via a per-dbname
#                 pg_advisory_lock to serialize first-time
#                 __ducklake_metadata creation across concurrent K8s
#                 pod spawns (see guides/ROADMAP.md, issue #3).
#   duckdb-file - ATTACH the on-disk .duckdb file directly. No
#                 advisory lock; per-node file is independent.
#   memory      - DuckDB's built-in 'memory' catalog is the default,
#                 no ATTACH needed. Federation aliases (in extraSetupSql)
#                 are typically the only catalogs available.

# Build init SQL piecemeal based on $kind so kind=memory and
# kind=duckdb-file skip DuckLake-specific setup entirely.
INIT_SQL=""
INIT_SQL+="$PROXY_SQL"$'\n'
# Per-database init SQL (tenant-db initSql, `dbInitSql` env var). Runs BEFORE
# the quack extension is installed/loaded and before the catalog ATTACH, right
# after the proxy settings, so engine-level defaults (SET memory_limit,
# SET temp_directory, extension INSTALLs) are already in effect when quack
# starts. Pool initSql + federation SQL ride $extraSetupSql further down.
if [[ -n "${dbInitSql:-}" ]]; then
  INIT_SQL+="$dbInitSql"$'\n'
fi
INIT_SQL+=$'INSTALL quack;    LOAD quack;\n'

case "$kind" in
  ducklake)
    INIT_SQL+=$'INSTALL ducklake; LOAD ducklake;\n'
    INIT_SQL+=$'INSTALL postgres; LOAD postgres;\n'
    INIT_SQL+="$STORAGE_SQL"$'\n'
    INIT_SQL+="ATTACH 'host=$pgHost port=$pgPort dbname=$dbName user=$pgUser password=$pgPassword' AS qod_init_pg (TYPE postgres);"$'\n'
    INIT_SQL+="SELECT * FROM postgres_query('qod_init_pg', 'SELECT pg_advisory_lock(hashtext(''qod-ducklake-init:$dbName''))');"$'\n'
    INIT_SQL+="ATTACH 'ducklake:postgres:host=$pgHost port=$pgPort dbname=$dbName user=$pgUser password=$pgPassword' AS \"$dbName\""$'\n'
    INIT_SQL+="  (DATA_PATH '$dataPath');"$'\n'
    INIT_SQL+="SELECT * FROM postgres_query('qod_init_pg', 'SELECT pg_advisory_unlock(hashtext(''qod-ducklake-init:$dbName''))');"$'\n'
    INIT_SQL+=$'DETACH qod_init_pg;\n'
    INIT_SQL+="USE \"$dbName\";"$'\n'
    INIT_SQL+="CREATE SCHEMA IF NOT EXISTS \"$schemaName\";"$'\n'
    INIT_SQL+="USE \"$dbName\".\"$schemaName\";"$'\n'
    ;;
  duckdb-file)
    INIT_SQL+="ATTACH '$dataPath' AS \"$dbName\";"$'\n'
    INIT_SQL+="USE \"$dbName\";"$'\n'
    INIT_SQL+="CREATE SCHEMA IF NOT EXISTS \"$schemaName\";"$'\n'
    INIT_SQL+="USE \"$dbName\".\"$schemaName\";"$'\n'
    ;;
  memory)
    : # nothing; DuckDB's built-in 'memory' catalog is the default
    ;;
esac

if [[ -n "${extraSetupSql:-}" ]]; then
  INIT_SQL+="$extraSetupSql"$'\n'
fi

INIT_SQL+="CALL quack_serve('quack:0.0.0.0:$PORT', token := '$TOKEN', allow_other_hostname := true);"$'\n'

printf '%s' "$INIT_SQL" >&9

# Block until duckdb is killed (typically SIGTERM from the manager).
wait "$DUCK_PID"
