#!/usr/bin/env bash
#
# Start a Quack server backed by a DuckLake catalog.
#
#   - Metadata: PostgreSQL (defaults to postgres@localhost:5432, db `postgres`)
#   - Data:     local files under DATA_PATH
#   - Wire:     Quack HTTP listener on QOD_NODE_URI
#
# Overrides via env vars:
#   PG_HOST   PG_PORT   PG_USER   PG_PASS
#   DATA_PATH
#   QOD_NODE_URI         (e.g. quack:0.0.0.0:9494)
#   DB_NAME           (Postgres database AND DuckDB catalog name -
#                      the script creates the Postgres DB if it doesn't exist)
#   SCHEMA_NAME       (DuckLake schema under $DB_NAME; defaults to `main`.
#                      MUST differ from DB_NAME or DuckDB will reject 2-part
#                      identifiers like "db1"."customer" as ambiguous -
#                      that's a real problem for JDBC clients (DBeaver etc.)
#                      that don't include the catalog prefix.)
#   PG_ADMIN_DB       (DB used to bootstrap CREATE DATABASE; default `postgres`)
#
# Quick start:
#   ./scripts/start-quack-ducklake.sh
#
# With a custom schema name:
#   SCHEMA_NAME=warehouse ./scripts/start-quack-ducklake.sh
#
# The script stays foreground inside the duckdb REPL. Ctrl-D (or `.exit`)
# shuts down Quack. The auth token Quack generates at startup is printed
# on stdout - clients need it via `TOKEN := '...'`.

set -euo pipefail

# ---- Config (override via env) ----
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_PASS="${PG_PASS:-azizam}"

DB_NAME="${DB_NAME:-tpch}"
SCHEMA_NAME="${SCHEMA_NAME:-tpch1}"
PG_ADMIN_DB="${PG_ADMIN_DB:-postgres}"

DATA_PATH="${DATA_PATH:-/Users/hayssams/git/public/quack-on-demand/ducklake/$DB_NAME}"
QOD_NODE_URI="${QOD_NODE_URI:-quack:0.0.0.0:9494}"

# ---- Sanity ----
command -v duckdb >/dev/null 2>&1 || {
  echo "ERROR: duckdb not found on PATH." >&2
  echo "Install via 'brew install duckdb' or https://duckdb.org/docs/installation/" >&2
  exit 1
}

if [[ "$SCHEMA_NAME" == "$DB_NAME" ]]; then
  echo "ERROR: SCHEMA_NAME ($SCHEMA_NAME) must differ from DB_NAME ($DB_NAME)." >&2
  echo "       DuckDB rejects 2-part identifiers like \"$DB_NAME\".<table> as ambiguous" >&2
  echo "       when a catalog and a schema share a name - JDBC clients hit this." >&2
  exit 1
fi

DUCKDB_VERSION="$(duckdb --version 2>/dev/null | head -1 | awk '{print $1}')"
echo "duckdb: $DUCKDB_VERSION"

mkdir -p "$DATA_PATH"
echo "data path:   $DATA_PATH"
echo "catalog:     $DB_NAME"
echo "schema:      $SCHEMA_NAME"

# Bootstrap: ensure the Postgres database $DB_NAME exists.
command -v psql >/dev/null 2>&1 || {
  echo "ERROR: psql not found on PATH. Install Postgres client tools." >&2
  exit 1
}

PSQL_ADMIN=(psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_ADMIN_DB" -tAc)

if ! PGPASSWORD="$PG_PASS" "${PSQL_ADMIN[@]}" "SELECT 1" >/dev/null 2>&1; then
  echo "ERROR: cannot connect to Postgres $PG_USER@$PG_HOST:$PG_PORT/$PG_ADMIN_DB" >&2
  exit 1
fi
echo "postgres:    OK ($PG_USER@$PG_HOST:$PG_PORT/$PG_ADMIN_DB)"

EXISTS=$(PGPASSWORD="$PG_PASS" "${PSQL_ADMIN[@]}" \
  "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" 2>/dev/null || true)
if [[ "$EXISTS" == "1" ]]; then
  echo "database:    $DB_NAME (exists)"
else
  echo "database:    $DB_NAME (creating)"
  PGPASSWORD="$PG_PASS" "${PSQL_ADMIN[@]}" \
    "CREATE DATABASE \"$DB_NAME\"" >/dev/null
fi

# ---- Generate init SQL ----
INIT_SQL="$(mktemp -t quack-init.XXXXXX.sql)"
trap 'rm -f "$INIT_SQL"' EXIT

# If SCHEMA_NAME is the DuckLake default ('main'), we don't need CREATE SCHEMA -
# it's already there. Otherwise we create it idempotently.
SCHEMA_CREATE=""
if [[ "$SCHEMA_NAME" != "main" ]]; then
  SCHEMA_CREATE="USE $DB_NAME;
CREATE SCHEMA IF NOT EXISTS $SCHEMA_NAME;"
fi

cat > "$INIT_SQL" <<SQL
-- Extensions
INSTALL ducklake;
LOAD ducklake;
INSTALL postgres;
LOAD postgres;
INSTALL quack;
LOAD quack;

-- Attach the DuckLake catalog (Postgres for metadata, local files for data).
-- Postgres database name equals the DuckDB catalog name ($DB_NAME).
ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');

$SCHEMA_CREATE
USE $DB_NAME.$SCHEMA_NAME;

.print ''
.print '== DuckLake catalog attached =='
.print 'Default catalog.schema: $DB_NAME.$SCHEMA_NAME'
.print 'Existing tables in this schema:'
SHOW TABLES;

.print ''
.print '== Starting Quack server on $QOD_NODE_URI =='
.print 'The row below carries the auth token. Save it for client ATTACH.'
.print ''
CALL quack_serve('$QOD_NODE_URI', allow_other_hostname => true);

.print ''
.print 'Quack is running. Clients connect with:'
.print '    ATTACH ''$QOD_NODE_URI'' AS remote (TYPE quack, TOKEN ''<token-above>'');'
.print ''
.print 'Ctrl-D or .exit to shut down.'
SQL

# ---- Run ----
exec duckdb -init "$INIT_SQL"