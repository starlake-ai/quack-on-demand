#!/usr/bin/env bash
#
# Run the FlightSQL load tester (scripts/loadtest/LoadTest.java) against a
# running quack-on-demand manager. Locates the Arrow Flight SQL JDBC driver
# from the env var, the local Downloads/ directory, or the Coursier cache.
#
# Usage:
#   ./scripts/loadtest.sh                    # defaults: 8 workers x 100 iters
#   ./scripts/loadtest.sh --workers 32 --iterations 500
#   LT_QUERY='SELECT 1' ./scripts/loadtest.sh
#
# Env overrides (also accepted as --flag):
#   LT_URL         (jdbc:arrow-flight-sql://...)
#   LT_USER        (default: admin)
#   LT_PASSWORD    (default: admin)
#   LT_WORKERS     (default: 8)
#   LT_ITERATIONS  (default: 100)
#   LT_WARMUP      (default: 5)
#   LT_QUERY       (single SQL; cycles a 3-way TPCH-ish mix when unset)
#
# Driver lookup (highest priority first; latest version wins inside a dir):
#   FLIGHTSQL_JDBC_JAR=/path/to/flight-sql-jdbc-driver-*.jar
#   ~/Downloads/arrow-flight-sql-jdbc/flight-sql-jdbc-driver-*.jar
#   Coursier cache
# If none found, auto-downloads the latest from Maven Central (~/Downloads).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$SCRIPT_DIR/loadtest/LoadTest.java"

# ---- Locate the JDBC driver jar ----
# Returns the highest version found in each candidate directory. If nothing
# turns up, falls back to a one-time download of the latest from Maven Central.

latest_in_dir() {
  # Sort -V groups major.minor.patch correctly, take the last one
  ls "$@" 2>/dev/null | sort -V | tail -n1
}

locate_jar() {
  if [[ -n "${FLIGHTSQL_JDBC_JAR:-}" && -f "$FLIGHTSQL_JDBC_JAR" ]]; then
    echo "$FLIGHTSQL_JDBC_JAR"; return
  fi
  local found
  found=$(latest_in_dir "$HOME/Downloads/arrow-flight-sql-jdbc/flight-sql-jdbc-driver-"*.jar)
  if [[ -n "$found" ]]; then echo "$found"; return; fi
  found=$(latest_in_dir "$HOME/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/arrow/flight-sql-jdbc-driver/"*/flight-sql-jdbc-driver-*.jar)
  if [[ -n "$found" ]]; then echo "$found"; return; fi
  found=$(latest_in_dir "$HOME/.cache/coursier/v1/https/repo1.maven.org/maven2/org/apache/arrow/flight-sql-jdbc-driver/"*/flight-sql-jdbc-driver-*.jar)
  if [[ -n "$found" ]]; then echo "$found"; return; fi

  # ---- One-time auto-download ----
  local latest target dl_dir
  echo "no JDBC driver found locally; fetching the latest from Maven Central..." >&2
  latest=$(curl -fsSL https://repo1.maven.org/maven2/org/apache/arrow/flight-sql-jdbc-driver/maven-metadata.xml 2>/dev/null \
           | sed -n 's:.*<latest>\(.*\)</latest>.*:\1:p')
  if [[ -z "$latest" ]]; then
    echo "ERROR: could not query Maven Central. Set FLIGHTSQL_JDBC_JAR manually." >&2
    exit 1
  fi
  dl_dir="$HOME/Downloads/arrow-flight-sql-jdbc"
  target="$dl_dir/flight-sql-jdbc-driver-$latest.jar"
  mkdir -p "$dl_dir"
  curl -fsSL "https://repo1.maven.org/maven2/org/apache/arrow/flight-sql-jdbc-driver/$latest/flight-sql-jdbc-driver-$latest.jar" \
       -o "$target" >&2
  echo "$target"
}

JAR="$(locate_jar)"
echo "driver: $JAR"

# ---- Java needs --add-opens for Arrow's unsafe allocator on Java 17+ ----
exec java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  -cp "$JAR" \
  "$SRC" \
  "$@"
