#!/usr/bin/env bash
# Tear down the stack started by `run-docker-compose.sh`. The manager
# JVM registers a shutdown hook that drains in-flight FlightSQL
# statements (`quack-on-demand.drainTimeoutSec`, default 60s) and
# SIGTERMs the child Quack node processes before exiting, so a plain
# `docker compose down` is graceful by default. `stop_grace_period`
# on the `quack` service in docker-compose.yml gives the JVM hook
# enough room (75s) before Docker escalates to SIGKILL.
#
# Env vars:
#   NUKE          "1" to wipe ./pgdata, ./ducklake, ./certs after teardown.
#                 Implies REMOVE=1. Irreversible.               (default 0)
#
# Usage:
#   ./scripts/stop-docker-compose.sh                    # graceful drain
#   NUKE=1     ./scripts/stop-docker-compose.sh         # graceful + delete host state

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

NUKE="${NUKE:-0}"

command -v docker >/dev/null 2>&1 || {
  echo "ERROR: docker not found on PATH." >&2
  exit 1
}
docker compose version >/dev/null 2>&1 || {
  echo "ERROR: 'docker compose' plugin not available." >&2
  exit 1
}

echo "removing containers + default network..."
docker compose down

if [[ "$NUKE" == "1" ]]; then
  echo "wiping host state directories (./pgdata, ./ducklake, ./certs)..."
  docker run --rm -v "$REPO_DIR:/work" alpine sh -c \
    'rm -rf /work/pgdata /work/ducklake /work/certs'
  echo "wiped. next run-docker-compose.sh will boot from a clean slate."
fi

echo "done."