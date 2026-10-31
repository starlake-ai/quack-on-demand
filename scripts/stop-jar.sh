#!/usr/bin/env bash
#
# Stop the quack-on-demand manager and its child Quack nodes.
#
# Locates the manager by its REST port (default 20900) and ITS child quack
# processes by their spawn-quack-node.sh wrapper, whose argv carries the
# manager's marker `<rest-port>-<boot id>` (LocalQuackBackend.managerMarker).
# Nodes of another manager on this host (a different REST port) are never
# touched, and nor are the orphans of a manager from an earlier release, whose
# wrappers carry the node token where the marker now goes
# (`kill-quack-nodes.sh --all` stops those, with no manager running). Sends SIGTERM
# first for graceful shutdown; escalates to SIGKILL after FORCE_AFTER
# seconds if anything is still listening.
#
# Overrides via env vars:
#   QOD_ON_DEMAND_PORT    (default 20900 - manager REST + UI)
#   PROXY_PORT                 (default 31338 - FlightSQL edge)
#   FORCE_AFTER                (default 10 - seconds before SIGKILL)
#
# Usage:
#   ./scripts/stop-jar.sh

set -euo pipefail

MGR_PORT="${QOD_ON_DEMAND_PORT:-20900}"
EDGE_PORT="${PROXY_PORT:-31338}"
FORCE_AFTER="${FORCE_AFTER:-10}"

# ---- Discover processes ----
# Manager owns BOTH the REST port and the FlightSQL edge port (same JVM).
# Spawned quack nodes listen on their own ports (21900+), but the easiest
# handle is the spawn-quack-node.sh PID - killing the script trips its
# trap which terminates the qod-node child.
mgr_pid=$(lsof -nP -iTCP:"$MGR_PORT" -sTCP:LISTEN -t 2>/dev/null | head -n1 || true)
edge_pid=$(lsof -nP -iTCP:"$EDGE_PORT" -sTCP:LISTEN -t 2>/dev/null | head -n1 || true)
# This manager's wrappers (`spawn-quack-node.sh <node-port> <rest-port>-<boot id>`) and nodes
# (`.../qod-node ... --secret-directory <tmp>/quack-node.<rest-port>-<boot id>.XXXXXX/secrets/...`;
# an earlier release's `.../duckdb -init <tmp>/quack-node.<rest-port>-<boot id>.XXXXXX/init`).
SPAWN_RE="spawn-quack-node\.sh [0-9]+ ${MGR_PORT}-[0-9a-f]+"
NODE_RE="(qod-node .*--secret-directory|duckdb .*-init) .*quack-node\.${MGR_PORT}-[0-9a-f]+\."
spawn_pids=$(pgrep -f "$SPAWN_RE" 2>/dev/null || true)
# pgrep exits 1 when nothing matches, which pipefail + set -e would turn into a silent abort here.
unmarked=$( (pgrep -f 'spawn-quack-node\.sh [0-9]+$' 2>/dev/null || true) | wc -l | tr -d ' ')
if [[ "$unmarked" != "0" ]]; then
  echo "note: leaving $unmarked unmarked spawn-quack-node.sh process(es) alone (an older manager's;" \
    "kill-quack-nodes.sh --unmarked stops them)."
fi

if [[ -z "$mgr_pid" && -z "$edge_pid" && -z "$spawn_pids" ]]; then
  echo "nothing running on ports $MGR_PORT / $EDGE_PORT and no spawn-quack-node processes of that manager."
  exit 0
fi

[[ -n "$mgr_pid"   ]] && echo "manager:  pid=$mgr_pid (port $MGR_PORT)"
[[ -n "$edge_pid"  ]] && echo "edge:     pid=$edge_pid (port $EDGE_PORT)"
[[ -n "$spawn_pids" ]] && echo "quack:    pids=$(echo "$spawn_pids" | tr '\n' ' ')"

# ---- Graceful: SIGTERM ----
# The manager's IOApp + cats-effect supervises children; SIGTERM hits the
# .guarantee block which stops the edge and cancels the health probe.
# Spawned quack scripts trap TERM and tear down their qod-node child.
all_pids=$(echo "$mgr_pid $edge_pid $spawn_pids" | tr ' ' '\n' | sort -u | tr '\n' ' ')
for pid in $all_pids; do
  [[ -z "$pid" ]] && continue
  kill -TERM "$pid" 2>/dev/null || true
done

# ---- Wait for ports to clear ----
for ((i=0; i<FORCE_AFTER; i++)); do
  sleep 1
  if ! lsof -nP -iTCP:"$MGR_PORT","$EDGE_PORT" -sTCP:LISTEN -t 2>/dev/null > /dev/null \
     && ! pgrep -f "$SPAWN_RE" > /dev/null 2>&1; then
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
remaining_spawn=$(pgrep -f "$SPAWN_RE" 2>/dev/null || true)
# Nodes run as `<abs path>/qod-node ... --secret-directory <tmp>/quack-node.<marker>.XXX/secrets/...`;
# match that argv with this manager's marker, not a bare `^qod-node` (which the absolute path never
# matches).
remaining_nodes=$(pgrep -f "$NODE_RE" 2>/dev/null || true)
for pid in $remaining_spawn $remaining_nodes; do
  kill -9 "$pid" 2>/dev/null || true
done

sleep 2
if lsof -nP -iTCP:"$MGR_PORT","$EDGE_PORT" -sTCP:LISTEN -t 2>/dev/null > /dev/null; then
  echo "WARN: a port is still listening. Investigate with: lsof -nP -iTCP:$MGR_PORT,$EDGE_PORT -sTCP:LISTEN" >&2
  exit 1
fi
echo "stopped (forced)."