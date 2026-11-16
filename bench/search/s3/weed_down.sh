#!/usr/bin/env bash
# Stops the Task 15a SeaweedFS store with a hard kill, as the demo does (it can take many seconds
# to honor SIGTERM), then removes <p2>/weed and <p2>/secrets/weed.json.
# Usage: weed_down.sh <p2>
# Kills the pid in <p2>/weed/weed.pid, then any leftover listener on 127.0.0.1:27460 to 27468
# whose command lsof names weed. Never touches another process.
set -uo pipefail
P2="$1"
BASE="$P2/weed"
if [ -f "$BASE/weed.pid" ]; then
  pid="$(cat "$BASE/weed.pid")"
  if kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid" && echo "killed weed $pid"
  else
    echo "weed $pid not running"
  fi
fi
sleep 1
for port in 27460 27461 27462 27463 27464 27465 27466 27467 27468; do
  for pid in $(lsof -nP -iTCP:"$port" -sTCP:LISTEN -Fpc 2>/dev/null | awk '/^p/{p=substr($0,2)} /^c/{if (substr($0,2) ~ /^weed/) print p}' | sort -u); do
    kill -9 "$pid" && echo "killed leftover weed $pid on $port"
  done
done
sleep 1
rm -rf "$BASE"
rm -f "$P2/secrets/weed.json"
echo "removed <p2>/weed and <p2>/secrets/weed.json"
