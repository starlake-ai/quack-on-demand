#!/usr/bin/env bash
# Runs a load file (default qod_load.sql) on the part 2 QoD stack, one statement per qod sql
# call, as the superuser, printing each statement's wall time. @P2@ in the file is replaced by
# the scratch path with sed.
# Usage: qod_load.sh <p2> [file.sql]    (the stack must be up: qod_up.sh)
# Limits: each statement must fit on one line (the file is split by line; lines starting with
# -- are comments), and <p2> must hold no '#' or '&' (the sed replacement). A failure stops the
# load; the EXIT trap then sets threads back to 16 on the node (SET threads is global there).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
P2="$1"
SQL="${2:-$HERE/qod_load.sql}"
OUT="$P2/$(basename "$SQL")"
reset_threads() {
  "$HERE/qod_sql.sh" "$P2" --csv "SET threads = 16" < /dev/null > /dev/null 2>&1 || echo "WARNING: could not set threads back to 16" >&2
  echo "threads on the node: $("$HERE/qod_sql.sh" "$P2" --csv "SELECT current_setting('threads')" < /dev/null 2>/dev/null | tail -1 | tr -d '\r')"
}
trap reset_threads EXIT
sed "s#@P2@#$P2#g" "$SQL" > "$OUT"
grep -v '^--' "$OUT" | grep -v '^[[:space:]]*$' | while IFS= read -r stmt; do
  echo "== $(date -u +%H:%M:%S) ${stmt:0:110}"
  start=$(python3 -I -c 'import time; print(time.time())')
  "$HERE/qod_sql.sh" "$P2" "$stmt" < /dev/null
  python3 -I -c 'import sys, time; print(f"   wall {time.time() - float(sys.argv[1]):.1f} s")' "$start"
done
