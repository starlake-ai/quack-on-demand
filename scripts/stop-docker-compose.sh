#!/usr/bin/env bash
#
# Stop the quack-on-demand docker-compose stack started by
# scripts/run-docker-compose.sh.
#
# Default: `docker compose kill -s SIGKILL` - terminates immediately.
# The manager JVM has no graceful shutdown hook today, so the SIGTERM
# grace period is dead time; Docker Desktop on macOS also adds ~30s of
# its own latency per container during graceful stop. Fast kill is the
# right tradeoff for dev. Bind-mounted state (./pgdata, ./ducklake,
# ./certs) is on the host and untouched by SIGKILL.
#
# Env vars:
#   GRACEFUL      "1" to use `docker compose stop -t $STOP_TIMEOUT` instead
#                 of `kill`. Use when you actually have in-flight FlightSQL
#                 sessions that need to drain.                  (default 0)
#   STOP_TIMEOUT  SIGTERM grace period before SIGKILL when GRACEFUL=1.
#                                                               (default 5s)
#   REMOVE        "1" to also `docker compose down` (removes containers +
#                 network). Volumes/bind mounts are kept.       (default 0)
#   NUKE          "1" to wipe ./pgdata, ./ducklake, ./certs after teardown.
#                 Implies REMOVE=1. Irreversible.               (default 0)
#
# Usage:
#   ./scripts/stop-docker-compose.sh                    # fast SIGKILL
#   GRACEFUL=1 ./scripts/stop-docker-compose.sh         # SIGTERM, then SIGKILL
#   REMOVE=1   ./scripts/stop-docker-compose.sh         # kill + compose down
#   NUKE=1     ./scripts/stop-docker-compose.sh         # kill + down + delete host state

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

GRACEFUL="${GRACEFUL:-0}"
STOP_TIMEOUT="${STOP_TIMEOUT:-5}"
REMOVE="${REMOVE:-0}"
NUKE="${NUKE:-0}"
[[ "$NUKE" == "1" ]] && REMOVE=1

command -v docker >/dev/null 2>&1 || {
  echo "ERROR: docker not found on PATH." >&2
  exit 1
}
docker compose version >/dev/null 2>&1 || {
  echo "ERROR: 'docker compose' plugin not available." >&2
  exit 1
}

# Idempotent: both `kill` and `stop` no-op when the stack is already down.
if [[ "$GRACEFUL" == "1" ]]; then
  echo "stopping compose stack gracefully (SIGTERM, then SIGKILL after ${STOP_TIMEOUT}s)..."
  docker compose stop -t "$STOP_TIMEOUT"
else
  echo "killing compose stack (SIGKILL)..."
  docker compose kill -s SIGKILL
fi

if [[ "$REMOVE" == "1" ]]; then
  echo "removing containers + default network..."
  docker compose down
fi

# Tear down any proxy bridge containers spawned by run-docker-compose.sh.
# Match by the `quack-proxy-bridge-` prefix so we don't touch unrelated
# socat sidecars the user may be running.
bridges="$(docker ps -q -f "name=^quack-proxy-bridge-" 2>/dev/null || true)"
if [[ -n "$bridges" ]]; then
  echo "stopping proxy bridge container(s)..."
  docker stop $bridges >/dev/null
fi

if [[ "$NUKE" == "1" ]]; then
  echo "wiping host state directories (./pgdata, ./ducklake, ./certs)..."
  # Container uids (postgres uid 70, root for TLS keys) own the bind-mount
  # contents, so a host-side `rm -rf` from a non-root user gets EACCES.
  # Wipe via an ephemeral root container that has write access.
  docker run --rm -v "$REPO_DIR:/work" alpine sh -c \
    'rm -rf /work/pgdata /work/ducklake /work/certs'
  echo "wiped. next run-docker-compose.sh will boot from a clean slate."
fi

echo "done."