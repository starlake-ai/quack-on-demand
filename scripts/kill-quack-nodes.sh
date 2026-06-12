#!/usr/bin/env bash
#
# Kill every running `spawn-quack-node.sh` (and the duckdb child it traps).
# Unlike `stop-jar.sh`, this leaves the manager JVM alone -- use it when
# you want to nuke stuck child nodes without bouncing the manager (e.g.
# after a crashed reconcile leaves orphaned spawn-quack-node.sh PIDs).
#
# SIGTERM first so the script's `trap` tears down the duckdb child
# cleanly; SIGKILL after FORCE_AFTER seconds for anything still alive.
#
# Overrides via env vars:
#   FORCE_AFTER   (default 5  - seconds before SIGKILL)
#
# Usage:
#   ./scripts/kill-quack-nodes.sh

set -euo pipefail

FORCE_AFTER="${FORCE_AFTER:-5}"

# Match the literal script name so we don't grab unrelated `quack-node`
# matches (e.g. log lines piped through `tee`).
pids=$(pgrep -f 'spawn-quack-node\.sh' 2>/dev/null || true)

if [[ -z "$pids" ]]; then
  echo "no spawn-quack-node.sh processes running."
  exit 0
fi

echo "killing spawn-quack-node.sh pids: $(echo "$pids" | tr '\n' ' ')"

# ---- Graceful ----
for pid in $pids; do
  kill -TERM "$pid" 2>/dev/null || true
done

# ---- Wait for them to exit ----
for ((i=0; i<FORCE_AFTER; i++)); do
  sleep 1
  if ! pgrep -f 'spawn-quack-node\.sh' > /dev/null 2>&1; then
    echo "stopped cleanly after ${i}s."
    exit 0
  fi
done

# ---- Force ----
echo "still alive after ${FORCE_AFTER}s; sending SIGKILL."
remaining=$(pgrep -f 'spawn-quack-node\.sh' 2>/dev/null || true)
for pid in $remaining; do
  kill -9 "$pid" 2>/dev/null || true
done

# Sweep any orphaned duckdb children whose parent script died before its
# trap could fire.
orphan_duck=$(pgrep -f '^duckdb' 2>/dev/null || true)
for pid in $orphan_duck; do
  kill -9 "$pid" 2>/dev/null || true
done

sleep 1
if pgrep -f 'spawn-quack-node\.sh' > /dev/null 2>&1; then
  echo "WARN: some spawn-quack-node.sh PIDs survived SIGKILL. Investigate with: pgrep -alf spawn-quack-node" >&2
  exit 1
fi
echo "stopped (forced)."