#!/usr/bin/env bash
# Stops the part 2 ParadeDB stack and removes its container and data volume.
# Usage: paradedb_down.sh <p2>
set -uo pipefail
docker rm -f qod-p2-paradedb > /dev/null 2>&1
docker volume rm qod-p2-paradedb-data > /dev/null 2>&1
if lsof -nP -iTCP:27432 -sTCP:LISTEN > /dev/null 2>&1; then
  echo "something still listens on 127.0.0.1:27432" >&2; exit 1
fi
if docker volume ls --format '{{.Name}}' | grep -qx qod-p2-paradedb-data; then
  echo "volume qod-p2-paradedb-data still exists" >&2; exit 1
fi
echo "ParadeDB p2 stack down"
