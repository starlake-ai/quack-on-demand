#!/usr/bin/env bash
#
# Stop the quack-on-demand manager and all child Quack nodes.
#
# Locates the manager by its REST port (default 20900) and child quack
# processes by their spawn-quack-node.sh parent script. Sends SIGTERM
# first for graceful shutdown; escalates to SIGKILL after FORCE_AFTER
# seconds if anything is still listening.
#
# Overrides via env vars:
#   SL_QUACK_ON_DEMAND_PORT    (default 20900 - manager REST + UI)
#   PROXY_PORT                 (default 31338 - FlightSQL edge)
#   FORCE_AFTER                (default 10 - seconds before SIGKILL)
#
# Usage:
#   ./scripts/stop-quack-on-demand.sh

set -euo pipefail

MGR_PORT="${SL_QUACK_ON_DEMAND_PORT:-20900}"
EDGE_PORT="${PROXY_PORT:-31338}"
FORCE_AFTER="${FORCE_AFTER:-10}"

# ---- Discover processes ----
# Manager owns BOTH the REST port and the FlightSQL edge port (same JVM).
# Spawned quack nodes listen on their own ports (21900+), but the easiest
# handle is the spawn-quack-node.sh PID - killing the script trips its
# trap which terminates the duckdb child.
mgr_pid=$(lsof -nP -iTCP:"$MGR_PORT" -sTCP:LISTEN -t 2>/dev/null | head -n1 || true)
edge_pid=$(lsof -nP -iTCP:"$EDGE_PORT" -sTCP:LISTEN -t 2>/dev/null | head -n1 || true)
spawn_pids=$(pgrep -f spawn-quack-node 2>/dev/null || true)

if [[ -z "$mgr_pid" && -z "$edge_pid" && -z "$spawn_pids" ]]; then
  echo "nothing running on ports $MGR_PORT / $EDGE_PORT and no spawn-quack-node processes."
  exit 0
fi

[[ -n "$mgr_pid"   ]] && echo "manager:  pid=$mgr_pid (port $MGR_PORT)"
[[ -n "$edge_pid"  ]] && echo "edge:     pid=$edge_pid (port $EDGE_PORT)"
[[ -n "$spawn_pids" ]] && echo "quack:    pids=$(echo "$spawn_pids" | tr '\n' ' ')"

# ---- Graceful: SIGTERM ----
# The manager's IOApp + cats-effect supervises children; SIGTERM hits the
# .guarantee block which stops the edge and cancels the health probe.
# Spawned quack scripts trap TERM and tear down their duckdb child.
all_pids=$(echo "$mgr_pid $edge_pid $spawn_pids" | tr ' ' '\n' | sort -u | tr '\n' ' ')
for pid in $all_pids; do
  [[ -z "$pid" ]] && continue
  kill -TERM "$pid" 2>/dev/null || true
done

# ---- Wait for ports to clear ----
for ((i=0; i<FORCE_AFTER; i++)); do
  sleep 1
  if ! lsof -nP -iTCP:"$MGR_PORT","$EDGE_PORT" -sTCP:LISTEN -t 2>/dev/null > /dev/null \
     && ! pgrep -f spawn-quack-node > /dev/null 2>&1; then
    echo "stopped cleanly after ${i}s."
    exit 0
  fi
done

# ---- Force: SIGKILL ----
echo "still listening after ${FORCE_AFTER}s; sending SIGKILL."
for pid in $all_pids; do
  [[ -z "$pid" ]] && continue
  kill -9 "$pid" 2>/dev/null || true
done

# Re-discover (PIDs may have died but spawned new replacements unlikely)
remaining_spawn=$(pgrep -f spawn-quack-node 2>/dev/null || true)
remaining_duck=$(pgrep -f '^duckdb' 2>/dev/null || true)
for pid in $remaining_spawn $remaining_duck; do
  kill -9 "$pid" 2>/dev/null || true
done

sleep 2
if lsof -nP -iTCP:"$MGR_PORT","$EDGE_PORT" -sTCP:LISTEN -t 2>/dev/null > /dev/null; then
  echo "WARN: a port is still listening. Investigate with: lsof -nP -iTCP:$MGR_PORT,$EDGE_PORT -sTCP:LISTEN" >&2
  exit 1
fi
echo "stopped (forced)."