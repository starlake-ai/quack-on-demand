#!/usr/bin/env bash
#
# Shared helpers for the load-*-dbgen.sh demo seeders - the bash sibling of
# _load-common.ps1 (which factored this preamble first; keep the two in sync).
# Defines functions only, no side effects at source time.
#
# Each loader sets its dataset knobs, sources this file, then calls the
# helpers in order:
#
#   DATASET="tpch"                 # message prefix + temp-dir suffix
#   DATASET_LABEL="TPC-H"          # human label for the Docker heads-up
#   DB_NAME / SCHEMA_NAME / SF     # resolved with per-dataset defaults
#   SENTINEL_TABLE="lineitem"      # table probed for idempotency
#   SENTINEL_COLUMN="l_orderkey"   # column read by the file-probe LIMIT 1
#   SCALE_NOTE="approx ...M rows"  # banner row-count estimate
#   source "$SCRIPT_DIR/_load-common.sh"
#   load_config; load_sanity; load_ensure_pg_database; load_resolve_storage
#   load_print_banner; load_exit_if_already_loaded
#
# and embeds "$(load_preamble_sql "INSTALL tpch; LOAD tpch;")" at the top of
# its generation SQL heredoc.
#
# Env-var contract (all optional, resolved by load_config):
#   PG_HOST / PG_PORT / PG_USER / PG_PASS   Postgres coordinates
#   PG_ADMIN_DB                             admin DB for CREATE DATABASE probe
#   DB_NAME / SCHEMA_NAME / SF              per-dataset defaults set by the loader
#   DATA_PATH                               DuckLake data dir (see the path-
#                                           matching warning in each loader)
#   TEMP_DIR                                DuckDB spill dir
#   MEMORY_LIMIT                            DuckDB memory cap; empty = default
#   QOD_S3_* / QOD_AZURE_CONNECTION_STRING  remote-DATA_PATH credentials

# DuckDB's default memory budget is ~80% of *host* RAM and ignores the cgroup
# limit, so inside a memory-capped container (e.g. a 4Gi pod, where a loader
# also shares the cgroup with the manager JVM) dbgen()/dsdgen() at SF>=10
# overruns the limit and the kernel OOM-kills the process (`command terminated
# with exit code 137`). load_config bounds it instead, so DuckDB spills to
# TEMP_DIR rather than overcommit. Default = 40% of the cgroup limit (leaving
# the rest for the JVM); override with MEMORY_LIMIT (e.g. MEMORY_LIMIT=3GiB),
# or MEMORY_LIMIT= to keep DuckDB's default.
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

# Resolve PG_*, TEMP_DIR (created), MEMORY_LIMIT(+_SQL) and DATA_PATH.
load_config() {
  PG_HOST="${PG_HOST:-localhost}"
  PG_PORT="${PG_PORT:-5432}"
  PG_USER="${PG_USER:-postgres}"
  PG_PASS="${PG_PASS:-azizam}"

  # DuckDB's default temp_directory is the cwd-relative `./.tmp/`. At SF>=10
  # dbgen() exceeds the default memory budget and DuckDB spills to disk; if
  # `.tmp/` doesn't exist the entire load aborts with
  #   IO Error: Cannot open file ".tmp/duckdb_temp_storage_DEFAULT-1.tmp"
  # and every subsequent `CREATE TABLE ... AS SELECT * FROM memory.main.<t>`
  # fails because the source tables were never populated. Anchor the temp
  # directory to an absolute path we control, mkdir it up-front, and let
  # DuckDB spill into it. Override via TEMP_DIR when running under a
  # read-only / quota'd filesystem.
  REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
  TEMP_DIR="${TEMP_DIR:-$REPO_DIR/.tmp/duckdb-$DATASET-load}"
  mkdir -p "$TEMP_DIR"

  MEMORY_LIMIT="${MEMORY_LIMIT-$(default_memory_limit)}"
  MEMORY_LIMIT_SQL=""
  [[ -n "$MEMORY_LIMIT" ]] && MEMORY_LIMIT_SQL="SET memory_limit='$MEMORY_LIMIT';"

  # Default DATA_PATH matches `scripts/run-docker.sh` (CWD-anchored, not
  # repo-anchored) so a native loader and a same-CWD `docker run` agree on
  # the same absolute string. Override DATA_PATH to point at any location
  # that BOTH the loader and the manager will see (see the path-matching
  # warning in the loader header).
  DATA_PATH="${DATA_PATH:-$PWD/ducklake/$DB_NAME}"
}

load_sanity() {
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
}

# Provision the target Postgres database if it doesn't exist. With per-
# tenant-db storage the loader's target is the bootstrap tenant-db (e.g.
# `tpch_tpch1`) which the manager only creates at boot inside
# PoolSupervisor.createTenantDb. Doing it here too lets a loader run BEFORE
# the manager (the order run-jar.sh uses) and standalone (LOAD_SSB without
# LOAD_TPCH). Mirrors the same idiom in spawn-quack-node.sh. Skipped silently
# when psql is absent -- the ATTACH will then surface the missing-database
# error itself. A concurrent loader may win the CREATE race; the WARN path
# tolerates that.
load_ensure_pg_database() {
  command -v psql >/dev/null 2>&1 || return 0
  PG_ADMIN_DB="${PG_ADMIN_DB:-postgres}"
  local exists
  exists=$(PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" \
    -d "$PG_ADMIN_DB" -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" 2>/dev/null || true)
  if [[ "$exists" != "1" ]]; then
    echo "load-$DATASET: creating Postgres database $DB_NAME"
    PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" \
      -d "$PG_ADMIN_DB" -tAc "CREATE DATABASE \"$DB_NAME\"" >/dev/null || {
        echo "WARN: CREATE DATABASE $DB_NAME failed; ATTACH below may fail" >&2
      }
  fi
}

# Detect remote DATA_PATH (s3:// / SeaweedFS / MinIO / R2, gs://, azure://) and
# set STORAGE_SQL to the matching DuckDB extension + SECRET so the ATTACH can
# read/write parquet against the bucket. Local paths are mkdir'd and
# canonicalized (DuckLake persists the exact string in the catalog).
load_resolve_storage() {
  IS_REMOTE=0
  STORAGE_SQL=""
  case "$DATA_PATH" in
    s3://*|s3a://*|gs://*|r2://*)
      IS_REMOTE=1
      STORAGE_SQL="INSTALL httpfs; LOAD httpfs;"
      if [[ -n "${QOD_S3_ACCESS_KEY_ID:-}" && -n "${QOD_S3_SECRET_ACCESS_KEY:-}" ]]; then
        local ep="${QOD_S3_ENDPOINT:-}"
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

    # Detect "I'm probably running on a host that will later run Docker" and
    # emit a heads-up. Heuristic: DATA_PATH doesn't start with /app/, but the
    # manager's Dockerfile defaults QOD_DUCKLAKE_DATA_PATH=/app/ducklake.
    if [[ "$DATA_PATH" != /app/ducklake/* ]] && [[ -e /.dockerenv || "${IN_DOCKER:-}" == "1" ]]; then
      : # we're inside Docker but DATA_PATH isn't /app/* - caller probably knows
    elif [[ "$DATA_PATH" != /app/ducklake/* ]]; then
      echo "Heads up: DATA_PATH='$DATA_PATH'" >&2
      echo "If you plan to run the manager in Docker, the container will look for the" >&2
      echo "data files at /app/ducklake/$DB_NAME (its bind-mount target), NOT at" >&2
      echo "'$DATA_PATH'. Use 'docker compose exec quack /app/scripts/load-$DATASET-dbgen.sh'" >&2
      echo "to load $DATASET_LABEL from inside the container (paths match by construction)." >&2
      echo "" >&2
    fi
  fi
}

load_print_banner() {
  echo "postgres:    $PG_USER@$PG_HOST:$PG_PORT/$DB_NAME"
  echo "catalog:     $DB_NAME.$SCHEMA_NAME"
  echo "data path:   $DATA_PATH"
  echo "scale:       SF=$SF ($SCALE_NOTE)"
  echo "memory:      ${MEMORY_LIMIT:-DuckDB default} (spill dir: $TEMP_DIR)"
  echo ""
}

# Common SQL header for every duckdb invocation: catalog extensions, any
# dataset-specific INSTALLs passed as $1, storage extension + secret, and the
# DuckLake ATTACH.
load_preamble_sql() {
  cat <<SQL
-- The seed loaders run in the BACKGROUND while run-jar.sh's manager owns the
-- terminal; duckdb's carriage-return progress bar (on by default when stdout
-- is a tty) overprints the manager's log lines and leaves the display
-- misaligned. Plain line output interleaves cleanly.
SET enable_progress_bar = false;
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
${1:-}
$STORAGE_SQL
ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');
SQL
}

# ---- Idempotency probe ----
# Two-step check:
#   1. SELECT count(*) - hits DuckLake's metadata, decides if the schema
#      claims any rows. Cheap; uses the `__ducklake_*` tables in Postgres
#      and never touches parquet.
#   2. SELECT LIMIT 1 - if step 1 says "yes, rows exist", this verifies
#      the parquet files those rows reference are actually on disk.
#      DuckLake stores absolute paths in its catalog; if `./ducklake/`
#      (or the S3 prefix) was wiped while pgdata was kept, count(*) lies
#      and every client query fails with "Cannot open file ...". Catching
#      that here turns a silent post-boot failure into an actionable
#      pre-boot one.
#
# stderr is silenced for the count (expected "table does not exist" path
# on first boot is quiet) and surfaced for the file-probe (we want the
# Cannot-open-file diagnostic visible).
#
# Exits 0 when the sentinel table already has rows and its files are
# readable; exits 1 on the catalog/file mismatch; returns normally when the
# dataset still needs loading.
load_exit_if_already_loaded() {
  local probe_sql existing_rows file_probe_sql file_probe_err
  probe_sql="$(mktemp -t "load-$DATASET-probe.XXXXXX.sql")"
  {
    load_preamble_sql
    echo ".mode csv"
    echo ".headers off"
    echo "SELECT count(*) FROM $DB_NAME.$SCHEMA_NAME.$SENTINEL_TABLE;"
  } > "$probe_sql"
  existing_rows="$(duckdb < "$probe_sql" 2>/dev/null | tr -d '\r\n ' || true)"
  rm -f "$probe_sql"
  if [[ "$existing_rows" =~ ^[0-9]+$ ]] && (( existing_rows > 0 )); then
    # Catalog says there's data. Verify the parquet files are reachable -
    # LIMIT 1 forces an actual file open, which the metadata short-circuit
    # in count(*) bypasses.
    file_probe_sql="$(mktemp -t "load-$DATASET-file-probe.XXXXXX.sql")"
    {
      load_preamble_sql
      echo ".mode csv"
      echo ".headers off"
      echo "SELECT $SENTINEL_COLUMN FROM $DB_NAME.$SCHEMA_NAME.$SENTINEL_TABLE LIMIT 1;"
    } > "$file_probe_sql"
    file_probe_err="$(duckdb < "$file_probe_sql" 2>&1 1>/dev/null || true)"
    rm -f "$file_probe_sql"
    if [[ -n "$file_probe_err" && "$file_probe_err" == *"Cannot open file"* ]]; then
      echo "ERROR: catalog claims $existing_rows rows in $DB_NAME.$SCHEMA_NAME.$SENTINEL_TABLE" >&2
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
    echo "already loaded: $DB_NAME.$SCHEMA_NAME.$SENTINEL_TABLE has $existing_rows rows; skipping."
    echo "(delete the schema or override SCHEMA_NAME to force a reload)"
    exit 0
  fi
}
