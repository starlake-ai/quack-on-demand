#!/usr/bin/env bash
# Starts the part 2 QoD stack (isolated ports of the part 2 plan) with qod serve, detached in a
# session of its own, and waits until it is up. Builds nothing: run sbt assembly first.
# Usage: qod_up.sh <p2> [NAME=value ...]   (extra environment for the manager, e.g. QOD_FOO=1)
# The admin password is read from <p2>/secrets/admin.txt and passed on the first boot only (no
# embedded pgdata yet); never printed.
set -euo pipefail
P2_ARG="$1"; shift
HERE="$(cd "$(dirname "$0")" && pwd)"
WT="$(cd "$HERE/../../.." && pwd)"
MAIN="$(cd "$WT/../.." && pwd)"
. "$HERE/qod_env.sh" "$P2_ARG"
for kv in "$@"; do export "$kv"; done
QOD="$WT/cli/.venv/bin/qod"
[ -x "$QOD" ] || (cd "$WT/cli" && uv sync --extra dev > /dev/null)
JAR="$(ls -t "$WT"/distrib/quack-on-demand-assembly-*.jar | head -1)"
if curl -sf -o /dev/null http://127.0.0.1:27900/ready; then
  echo "a manager already answers on 127.0.0.1:27900; run qod_down.sh first" >&2; exit 2
fi
if [ ! -x "$DUCKDB_CACHE_DIR/2.0.0/bin/duckdb" ]; then
  mkdir -p "$DUCKDB_CACHE_DIR/2.0.0"
  cp -R "$MAIN/.duckdb/2.0.0/bin" "$MAIN/.duckdb/2.0.0/lib" "$DUCKDB_CACHE_DIR/2.0.0/"
fi
mkdir -p "$P2/logs" "$HOME"
LOG="$P2/logs/qod-serve-$(date +%Y%m%d-%H%M%S).log"
echo "jar: $JAR"
echo "log: $LOG"
# The embedded Postgres (zonky) authenticates its superuser postgres by trust: no password
# to generate (see EmbeddedControlPlane.scala). The admin password goes by environment to
# qod serve, never argv; qod serve reads it on the first boot only.
# The data dir qod serve will use must be this stack's (qod_env.sh clears XDG_*; checked here).
DATA_DIR="$("$WT/cli/.venv/bin/python" -I -c 'import platformdirs; print(platformdirs.user_data_dir("qod"))')"
case "$DATA_DIR" in "$P2/home/"*) ;; *) echo "qod data dir is not under $P2/home; refusing" >&2; exit 2;; esac
# The admin password only on the first boot (no embedded pgdata yet), the only boot reading it.
if [ -d "$DATA_DIR/pg/pgdata" ]; then
  unset QOD_ADMIN_PASSWORD
else
  QOD_ADMIN_PASSWORD="$(cat "$P2/secrets/admin.txt")"; export QOD_ADMIN_PASSWORD
fi
python3 -I -c '
import os, subprocess, sys
log = open(sys.argv[1], "ab")
p = subprocess.Popen(sys.argv[2:], stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                     start_new_session=True)
print(f"qod serve pid {p.pid}")
' "$LOG" "$QOD" serve --jar "$JAR" --tenant acme --name lake --pool bi
# Ready, then the launcher's banner (printed once the pool has a routable node) or its
# failed step.
for i in $(seq 1 240); do
  curl -sf -o /dev/null http://127.0.0.1:27900/ready && break
  [ "$i" = 240 ] && { echo "manager not ready after 240 s; see $LOG" >&2; exit 1; }
  sleep 1
done
for i in $(seq 1 240); do
  grep -q 'qod serve: your gateway is ready' "$LOG" && break
  if grep -qE 'qod serve: .*failed|finish by hand' "$LOG"; then
    grep -E 'qod serve: |finish by hand' "$LOG" >&2; exit 1
  fi
  [ "$i" = 240 ] && { echo "no serve banner after 240 s; see $LOG" >&2; exit 1; }
  sleep 1
done
grep -q 'node still warming' "$LOG" && echo "note: the launcher saw no routable node yet"
echo "QoD p2 stack up"
lsof -nP -iTCP -sTCP:LISTEN | grep -E ':27(338|494|499|6[0-5][0-9]|90[01]) ' || true
