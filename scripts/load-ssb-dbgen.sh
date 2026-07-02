#!/usr/bin/env bash
#
# Seed the DuckLake metastore with an SSB (Star Schema Benchmark) dataset.
# DuckDB has no ssb extension, so we generate TPC-H in-memory with the
# built-in `dbgen()` and derive the 5 SSB tables from it using the O'Neil
# SSB spec's TPC-H mapping - no external CSV/JSON files required, no
# datasets/ directory to ship. Works for any of the three deployment
# paths (native, Docker single container, Docker compose).
#
# The star schema lands NEXT TO the TPC-H demo data: same tenant-db
# (acme_tpch by default) in its own schema (ssb1 by default), so the
# existing acme pools serve it with no extra bootstrap entities. Query it
# as `ssb1.lineorder` etc. from any acme session (the demo tenant_admin's
# `*.*.* ALL` grant covers it).
#
# Tables produced:
#   lineorder  fact:      lineitem x orders x partsupp   (~SF*6M rows)
#   customer   dimension: customer x nation x region
#   supplier   dimension: supplier x nation x region
#   part       dimension: part with SSB's mfgr/category/brand1 rollup
#   dwdate     dimension: 2,557 calendar days 1992-01-01..1998-12-31
#              (named dwdate, not the spec's `date`, so the 13 canonical
#              SSB queries run without quoting a reserved word - same
#              rename the Redshift SSB tutorial uses)
#
# Derivation notes: c_city/s_city take the nation prefix + key mod 10,
# p_brand1 spreads p_partkey mod 40 under the TPC-H brand's category, and
# lo_supplycost joins partsupp. d_sellingseason and the d_*fl flags are
# approximated - none of the 13 canonical SSB queries read them. Supplier
# keeps TPC-H's SF*10k rows (spec says SF*2k); lineorder joins are
# unaffected.
#
# *** PATH-MATCHING WARNING ***
# DuckLake stores `DATA_PATH` as an ABSOLUTE path inside the Postgres
# catalog; loader and manager must see the same string. See the header of
# load-tpch-dbgen.sh for the full explanation - the same rules apply here.
#
# Overrides via env vars (with defaults):
#   PG_HOST       Postgres host                       (default localhost)
#   PG_PORT       Postgres port                       (default 5432)
#   PG_USER       Postgres user                       (default postgres)
#   PG_PASS       Postgres password                   (default azizam)
#   DB_NAME       Postgres DB + DuckLake catalog name (default acme_tpch)
#   SCHEMA_NAME   DuckLake schema (must differ from DB_NAME)  (default ssb1)
#   DATA_PATH     DuckLake data dir                   (default ducklake/$DB_NAME)
#   SF            scale factor - controls row counts  (default 1)
#                 SF=1  -> ~6M lineorder rows
#                 SF=10 -> ~60M lineorder rows (much heavier)
#
# Usage:
#   ./scripts/load-ssb-dbgen.sh                        # SF=1 into acme_tpch.ssb1
#   SF=10 ./scripts/load-ssb-dbgen.sh                  # larger workload
#   PG_HOST=db.internal SCHEMA_NAME=ssb10 SF=10 ./scripts/load-ssb-dbgen.sh

set -euo pipefail

# ---- Config ----
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_PASS="${PG_PASS:-azizam}"

DB_NAME="${DB_NAME:-acme_tpch}"
SCHEMA_NAME="${SCHEMA_NAME:-ssb1}"
SF="${SF:-1}"

# DuckDB's default temp_directory is the cwd-relative `./.tmp/`. At SF>=10
# dbgen() exceeds the default memory budget and DuckDB spills to disk; if
# `.tmp/` doesn't exist the entire load aborts. Anchor the temp directory
# to an absolute path we control (see load-tpch-dbgen.sh for the war
# story). Override via TEMP_DIR when running under a read-only / quota'd
# filesystem.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMP_DIR="${TEMP_DIR:-$REPO_DIR/.tmp/duckdb-ssb-load}"
mkdir -p "$TEMP_DIR"

# DuckDB's default memory budget is ~80% of *host* RAM and ignores the cgroup
# limit, so inside a memory-capped container dbgen() at SF>=10 overruns the
# limit and the kernel OOM-kills the process (exit 137). Bound it instead, so
# DuckDB spills to TEMP_DIR rather than overcommit. Default = 40% of the
# cgroup limit (leaving the rest for the JVM); override with MEMORY_LIMIT
# (e.g. MEMORY_LIMIT=3GiB), or MEMORY_LIMIT= to keep DuckDB's default.
cgroup_memory_bytes() {
  local v=""
  if [[ -r /sys/fs/cgroup/memory.max ]]; then                       # cgroup v2
    v="$(cat /sys/fs/cgroup/memory.max 2>/dev/null)"
  elif [[ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]]; then   # cgroup v1
    v="$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null)"
  fi
  [[ "$v" =~ ^[0-9]+$ ]] || return 0                                # "max"/empty -> unset
  # Skip the v1 "unlimited" sentinel (~2^63); treat only real caps as limits.
  (( v > 0 && v < 9000000000000000000 )) && echo "$v"
}
default_memory_limit() {
  local bytes mib
  bytes="$(cgroup_memory_bytes)"
  [[ -n "$bytes" ]] || return 0
  mib=$(( bytes / 1024 / 1024 * 40 / 100 ))
  (( mib < 1024 )) && mib=1024
  echo "${mib}MiB"
}
MEMORY_LIMIT="${MEMORY_LIMIT-$(default_memory_limit)}"
MEMORY_LIMIT_SQL=""
[[ -n "$MEMORY_LIMIT" ]] && MEMORY_LIMIT_SQL="SET memory_limit='$MEMORY_LIMIT';"

# Default DATA_PATH matches `scripts/run-docker.sh` (CWD-anchored, not
# repo-anchored) so a native loader and a same-CWD `docker run` agree on
# the same absolute string. Override DATA_PATH to point at any location
# that BOTH the loader and the manager will see (see warning above).
DATA_PATH="${DATA_PATH:-$PWD/ducklake/$DB_NAME}"

# ---- Sanity ----
command -v duckdb >/dev/null 2>&1 || {
  echo "ERROR: duckdb CLI not on PATH." >&2
  echo "       Install via 'brew install duckdb' or https://duckdb.org/docs/installation/" >&2
  exit 1
}

if [[ "$SCHEMA_NAME" == "$DB_NAME" ]]; then
  echo "ERROR: SCHEMA_NAME ($SCHEMA_NAME) must differ from DB_NAME ($DB_NAME)." >&2
  echo "       DuckDB rejects 2-part identifiers like \"$DB_NAME\".<table> as ambiguous" >&2
  echo "       when a catalog and a schema share a name." >&2
  exit 1
fi

# Provision the target Postgres database if it doesn't exist, so this
# loader also works standalone (LOAD_SSB without LOAD_TPCH) and before the
# manager boots. Same idiom as load-tpch-dbgen.sh; skipped silently when
# psql is absent -- the ATTACH below will then surface the missing-database
# error itself. A concurrent load-tpch-dbgen.sh may win the CREATE race;
# the WARN path tolerates that.
if command -v psql >/dev/null 2>&1; then
  PG_ADMIN_DB="${PG_ADMIN_DB:-postgres}"
  EXISTS=$(PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" \
    -d "$PG_ADMIN_DB" -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" 2>/dev/null || true)
  if [[ "$EXISTS" != "1" ]]; then
    echo "load-ssb: creating Postgres database $DB_NAME"
    PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" \
      -d "$PG_ADMIN_DB" -tAc "CREATE DATABASE \"$DB_NAME\"" >/dev/null || {
        echo "WARN: CREATE DATABASE $DB_NAME failed; ATTACH below may fail" >&2
      }
  fi
fi

# Detect remote DATA_PATH (s3:// / SeaweedFS / MinIO / R2, gs://, azure://) and
# emit the matching DuckDB extension + SECRET so the ATTACH below can read/write
# parquet against the bucket. Skips mkdir + canonicalize for remote schemes.
IS_REMOTE=0
STORAGE_SQL=""
case "$DATA_PATH" in
  s3://*|s3a://*|gs://*|r2://*)
    IS_REMOTE=1
    STORAGE_SQL="INSTALL httpfs; LOAD httpfs;"
    if [[ -n "${QOD_S3_ACCESS_KEY_ID:-}" && -n "${QOD_S3_SECRET_ACCESS_KEY:-}" ]]; then
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

if [[ "$IS_REMOTE" == "0" ]]; then
  mkdir -p "$DATA_PATH"
  # Canonicalize - DuckLake persists this exact string in the catalog.
  DATA_PATH="$(cd "$DATA_PATH" && pwd)"

  if [[ "$DATA_PATH" != /app/ducklake/* ]] && [[ -e /.dockerenv || "${IN_DOCKER:-}" == "1" ]]; then
    : # we're inside Docker but DATA_PATH isn't /app/* - caller probably knows
  elif [[ "$DATA_PATH" != /app/ducklake/* ]]; then
    echo "Heads up: DATA_PATH='$DATA_PATH'" >&2
    echo "If you plan to run the manager in Docker, the container will look for the" >&2
    echo "data files at /app/ducklake/$DB_NAME (its bind-mount target), NOT at" >&2
    echo "'$DATA_PATH'. Use 'docker compose exec quack /app/scripts/load-ssb-dbgen.sh'" >&2
    echo "to load SSB from inside the container (paths match by construction)." >&2
    echo "" >&2
  fi
fi

echo "postgres:    $PG_USER@$PG_HOST:$PG_PORT/$DB_NAME"
echo "catalog:     $DB_NAME.$SCHEMA_NAME"
echo "data path:   $DATA_PATH"
echo "scale:       SF=$SF (approx $((SF * 6))M lineorder rows)"
echo "memory:      ${MEMORY_LIMIT:-DuckDB default} (spill dir: $TEMP_DIR)"
echo ""

# ---- Idempotency probe ----
# Same two-step check as load-tpch-dbgen.sh: count(*) hits DuckLake
# metadata only; the LIMIT 1 file-probe verifies the parquet files the
# catalog references are actually reachable (catches the wiped-ducklake/
# kept-pgdata desync before boot instead of at first client query).
PROBE_SQL="$(mktemp -t load-ssb-probe.XXXXXX.sql)"
cat > "$PROBE_SQL" <<SQL
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
$STORAGE_SQL
ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');
.mode csv
.headers off
SELECT count(*) FROM $DB_NAME.$SCHEMA_NAME.lineorder;
SQL
existing_rows="$(duckdb < "$PROBE_SQL" 2>/dev/null | tr -d '\r\n ' || true)"
rm -f "$PROBE_SQL"
if [[ "$existing_rows" =~ ^[0-9]+$ ]] && (( existing_rows > 0 )); then
  FILE_PROBE_SQL="$(mktemp -t load-ssb-file-probe.XXXXXX.sql)"
  cat > "$FILE_PROBE_SQL" <<SQL
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
$STORAGE_SQL
ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');
.mode csv
.headers off
SELECT lo_orderkey FROM $DB_NAME.$SCHEMA_NAME.lineorder LIMIT 1;
SQL
  file_probe_err="$(duckdb < "$FILE_PROBE_SQL" 2>&1 1>/dev/null || true)"
  rm -f "$FILE_PROBE_SQL"
  if [[ -n "$file_probe_err" && "$file_probe_err" == *"Cannot open file"* ]]; then
    echo "ERROR: catalog claims $existing_rows rows in $DB_NAME.$SCHEMA_NAME.lineorder" >&2
    echo "       but the parquet files it references are missing on disk:" >&2
    echo "$file_probe_err" | sed -n 's/.*Cannot open file "\([^"]*\)".*/         \1/p' \
      | head -1 >&2
    echo "" >&2
    echo "Catalog metadata in Postgres got out of sync with the data files. Most" >&2
    echo "likely cause: ./ducklake/ (or the S3 prefix) was wiped while ./pgdata/" >&2
    echo "was kept. Either re-create both from scratch (NUKE=1) or manually" >&2
    echo "drop the stale schema:" >&2
    echo "  DROP SCHEMA $DB_NAME.$SCHEMA_NAME CASCADE;" >&2
    exit 1
  fi
  echo "already loaded: $DB_NAME.$SCHEMA_NAME.lineorder has $existing_rows rows; skipping."
  echo "(delete the schema or override SCHEMA_NAME to force a reload)"
  exit 0
fi

# ---- Generate + run ----
INIT_SQL="$(mktemp -t load-ssb-dbgen.XXXXXX.sql)"
trap 'rm -f "$INIT_SQL"' EXIT

cat > "$INIT_SQL" <<SQL
-- Anchor temp_directory FIRST so any subsequent INSTALL / LOAD / CALL that
-- needs to spill (dbgen at SF>=10 will) writes into a directory we know
-- exists, rather than DuckDB's cwd-relative default ./.tmp/.
SET temp_directory='$TEMP_DIR';
-- Bound DuckDB's memory so dbgen() spills to temp_directory instead of
-- overrunning the container cgroup limit and getting OOM-killed (exit 137).
$MEMORY_LIMIT_SQL

INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
INSTALL tpch;     LOAD tpch;
$STORAGE_SQL

ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');

CREATE SCHEMA IF NOT EXISTS $DB_NAME.$SCHEMA_NAME;

.print ''
.print '== Generating TPC-H SF=$SF (in-memory) as the SSB source =='
.print 'SF=1 is fast (~10s); SF=10 takes a few minutes; SF=100+ is slow.'
.print ''
-- dbgen() only writes to native DuckDB catalogs, not DuckLake. Generate
-- into the default in-memory database, then derive each SSB table into
-- the DuckLake schema. We DROP first so a previous partial run is
-- overwritten cleanly.
CALL dbgen(sf = $SF);

.print ''
.print '== Deriving SSB star schema into DuckLake $DB_NAME.$SCHEMA_NAME =='

-- customer: TPC-H customer joined to nation/region; c_city is the SSB
-- 10-char city (9-char nation prefix + digit, key mod 10 as the digit).
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.customer;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.customer AS
SELECT
  c.c_custkey,
  c.c_name,
  c.c_address,
  substr(rpad(n.n_name, 9, ' '), 1, 9) || CAST(c.c_custkey % 10 AS VARCHAR) AS c_city,
  n.n_name AS c_nation,
  r.r_name AS c_region,
  c.c_phone,
  c.c_mktsegment
FROM memory.main.customer c
JOIN memory.main.nation n ON c.c_nationkey = n.n_nationkey
JOIN memory.main.region r ON n.n_regionkey = r.r_regionkey;

-- supplier: same shape as customer.
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.supplier;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.supplier AS
SELECT
  s.s_suppkey,
  s.s_name,
  s.s_address,
  substr(rpad(n.n_name, 9, ' '), 1, 9) || CAST(s.s_suppkey % 10 AS VARCHAR) AS s_city,
  n.n_name AS s_nation,
  r.r_name AS s_region,
  s.s_phone
FROM memory.main.supplier s
JOIN memory.main.nation n ON s.s_nationkey = n.n_nationkey
JOIN memory.main.region r ON n.n_regionkey = r.r_regionkey;

-- part: TPC-H p_brand is 'Brand#XY' with X = manufacturer 1-5, Y = 1-5.
-- SSB rolls up brand1 (MFGR#XYZZ, ZZ in 1-40) -> category (MFGR#XY)
-- -> mfgr (MFGR#X); p_color is the first token of the TPC-H p_name.
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.part;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.part AS
SELECT
  p_partkey,
  p_name,
  'MFGR#' || substr(p_brand, 7, 1) AS p_mfgr,
  'MFGR#' || substr(p_brand, 7, 2) AS p_category,
  'MFGR#' || substr(p_brand, 7, 2) || CAST(p_partkey % 40 + 1 AS VARCHAR) AS p_brand1,
  split_part(p_name, ' ', 1) AS p_color,
  p_type,
  p_size,
  p_container
FROM memory.main.part;

-- dwdate: one row per calendar day over TPC-H's order date range.
-- d_daynuminweek is 1(Sunday)..7(Saturday) per the SSB spec.
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.dwdate;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.dwdate AS
SELECT
  CAST(strftime(d, '%Y%m%d') AS INTEGER)               AS d_datekey,
  strftime(d, '%B %d, %Y')                             AS d_date,
  dayname(d)                                           AS d_dayofweek,
  monthname(d)                                         AS d_month,
  CAST(year(d) AS INTEGER)                             AS d_year,
  CAST(year(d) * 100 + month(d) AS INTEGER)            AS d_yearmonthnum,
  strftime(d, '%b%Y')                                  AS d_yearmonth,
  CAST(dayofweek(d) + 1 AS INTEGER)                    AS d_daynuminweek,
  CAST(day(d) AS INTEGER)                              AS d_daynuminmonth,
  CAST(dayofyear(d) AS INTEGER)                        AS d_daynuminyear,
  CAST(month(d) AS INTEGER)                            AS d_monthnuminyear,
  CAST(weekofyear(d) AS INTEGER)                       AS d_weeknuminyear,
  CASE
    WHEN month(d) = 12 OR (month(d) = 11 AND day(d) >= 15) THEN 'Christmas'
    WHEN month(d) IN (6, 7, 8)                             THEN 'Summer'
    WHEN month(d) IN (3, 4, 5)                             THEN 'Spring'
    WHEN month(d) IN (9, 10, 11)                           THEN 'Fall'
    ELSE 'Winter'
  END                                                  AS d_sellingseason,
  CASE WHEN dayofweek(d) = 6 THEN 1 ELSE 0 END         AS d_lastdayinweekfl,
  CASE WHEN d = last_day(d) THEN 1 ELSE 0 END          AS d_lastdayinmonthfl,
  CASE WHEN (month(d) = 12 AND day(d) = 25)
         OR (month(d) = 1  AND day(d) = 1)
         OR (month(d) = 7  AND day(d) = 4) THEN 1 ELSE 0 END AS d_holidayfl,
  CASE WHEN dayofweek(d) IN (0, 6) THEN 0 ELSE 1 END   AS d_weekdayfl
FROM (
  SELECT CAST(gs.d AS DATE) AS d
  FROM generate_series(DATE '1992-01-01', DATE '1998-12-31', INTERVAL 1 DAY) AS gs(d)
);

-- lineorder: lineitem denormalized with its order header; lo_discount and
-- lo_tax become 0-10 / 0-8 integer percentages (SSB queries filter
-- `lo_discount BETWEEN 1 AND 3`), lo_supplycost comes from partsupp.
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.lineorder;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.lineorder AS
SELECT
  l.l_orderkey                                         AS lo_orderkey,
  l.l_linenumber                                       AS lo_linenumber,
  o.o_custkey                                          AS lo_custkey,
  l.l_partkey                                          AS lo_partkey,
  l.l_suppkey                                          AS lo_suppkey,
  CAST(strftime(o.o_orderdate, '%Y%m%d') AS INTEGER)   AS lo_orderdate,
  o.o_orderpriority                                    AS lo_orderpriority,
  o.o_shippriority                                     AS lo_shippriority,
  CAST(l.l_quantity AS INTEGER)                        AS lo_quantity,
  l.l_extendedprice                                    AS lo_extendedprice,
  o.o_totalprice                                       AS lo_ordtotalprice,
  CAST(round(l.l_discount * 100) AS INTEGER)           AS lo_discount,
  round(l.l_extendedprice * (1 - l.l_discount), 2)     AS lo_revenue,
  ps.ps_supplycost                                     AS lo_supplycost,
  CAST(round(l.l_tax * 100) AS INTEGER)                AS lo_tax,
  CAST(strftime(l.l_commitdate, '%Y%m%d') AS INTEGER)  AS lo_commitdate,
  l.l_shipmode                                         AS lo_shipmode
FROM memory.main.lineitem l
JOIN memory.main.orders o
  ON l.l_orderkey = o.o_orderkey
JOIN memory.main.partsupp ps
  ON l.l_partkey = ps.ps_partkey AND l.l_suppkey = ps.ps_suppkey;

USE $DB_NAME.$SCHEMA_NAME;

.print ''
.print '== Final state =='
SHOW TABLES;
SELECT 'lineorder' AS tbl, count(*) AS rows FROM lineorder
UNION ALL SELECT 'customer', count(*) FROM customer
UNION ALL SELECT 'supplier', count(*) FROM supplier
UNION ALL SELECT 'part',     count(*) FROM part
UNION ALL SELECT 'dwdate',   count(*) FROM dwdate;
SQL

exec duckdb < "$INIT_SQL"