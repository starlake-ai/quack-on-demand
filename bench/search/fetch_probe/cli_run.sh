#!/usr/bin/env bash
# P5a: runs a SQL file in the DuckDB 2.0.0 CLI on the part 2 stack's DuckLake, attached read-only
# as acme_lake from its Postgres catalog with the node's settings that matter. The DSN and data
# path are read from <p2>/secrets (never printed); the attach preamble is written to a private
# temporary file (mode 600) that is removed on exit. Usage: cli_run.sh <p2> <file.sql>
set -euo pipefail
P2="$1"; SQL="$2"
umask 077
PRE="$(mktemp "$P2/results/fetch_probe/.attach.XXXXXX")"
trap 'rm -f "$PRE"' EXIT
DSN="$(cat "$P2/secrets/ducklake.dsn")"; DP="$(cat "$P2/secrets/data_path.txt")"
{
  echo "SET threads = 16;"
  echo "SET memory_limit = '62GiB';"
  echo "SET enable_external_file_cache = false;"
  echo "SET enable_http_metadata_cache = false;"
  echo "LOAD ducklake;"
  echo "LOAD postgres_scanner;"
  printf "ATTACH '%s' AS acme_lake (DATA_PATH '%s', READ_ONLY);\n" "${DSN//\'/\'\'}" "${DP//\'/\'\'}"
} > "$PRE"
cat "$PRE" "$SQL" | env -u AI_AGENT /Users/hayssams/git/public/quack-on-demand/.duckdb/2.0.0/bin/duckdb -no-agent :memory:
