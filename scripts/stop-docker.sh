#!/usr/bin/env bash
#
# Stop the quack-on-demand Docker container started by scripts/run-docker.sh.
#
# Idempotent: exits 0 cleanly when the container is already stopped or
# never started. Because run-docker.sh uses `docker run --rm`, the
# container is auto-deleted once it exits; this script only needs to
# send the stop signal.
#
# Env vars (with defaults):
#   CONTAINER_NAME   docker --name (default quack-on-demand - match run-docker.sh)
#   STOP_TIMEOUT     SIGTERM grace period before SIGKILL (default 30s)
#                    The JVM uses this to drain in-flight FlightSQL
#                    sessions and terminate child Quack node processes,
#                    so keep it >= 15s if you have live load.
#
# Usage:
#   ./scripts/stop-docker.sh
#   CONTAINER_NAME=qod-prod STOP_TIMEOUT=60 ./scripts/stop-docker.sh

set -euo pipefail

CONTAINER_NAME="${CONTAINER_NAME:-quack-on-demand}"
STOP_TIMEOUT="${STOP_TIMEOUT:-30}"

# Probe container state in a single inspect call. `-f` returns empty when
# the container doesn't exist; treat that as "already gone".
state="$(docker inspect -f '{{.State.Status}}' "$CONTAINER_NAME" 2>/dev/null || true)"

case "$state" in
  running|paused|restarting)
    echo "stopping container '$CONTAINER_NAME' (timeout=${STOP_TIMEOUT}s)..."
    docker stop --time "$STOP_TIMEOUT" "$CONTAINER_NAME" >/dev/null
    echo "stopped."
    ;;
  exited|created|dead)
    echo "container '$CONTAINER_NAME' is already $state; nothing to do."
    ;;
  "")
    echo "container '$CONTAINER_NAME' not found; nothing to do."
    ;;
  *)
    echo "container '$CONTAINER_NAME' in unexpected state '$state'; trying docker stop anyway..."
    docker stop --time "$STOP_TIMEOUT" "$CONTAINER_NAME" >/dev/null
    ;;
esac