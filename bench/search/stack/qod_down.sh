#!/usr/bin/env bash
# Stops the part 2 QoD stack (manager, its nodes and its embedded Postgres) with qod stop, and
# checks that no listener of the part 2 QoD ports remains. Usage: qod_down.sh <p2>
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WT="$(cd "$HERE/../../.." && pwd)"
. "$HERE/qod_env.sh" "$1"
QOD_ON_DEMAND_PORT=27900 "$WT/cli/.venv/bin/qod" stop
for _ in $(seq 1 30); do
  lsof -nP -iTCP -sTCP:LISTEN | grep -qE ':27(338|494|499|6[0-5][0-9]|90[01]) ' || break
  sleep 1
done
if lsof -nP -iTCP -sTCP:LISTEN | grep -E ':27(338|494|499|6[0-5][0-9]|90[01]) '; then
  echo "listeners of the part 2 QoD stack remain" >&2; exit 1
fi
echo "QoD p2 stack down"
