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
# (`kill-quack-nodes.sh --all` stops those, with no manager running).
#
# The manager is sent SIGTERM first, alone: it refuses new statements, lets
# running ones finish for up to QOD_DRAIN_TIMEOUT_SEC, then stops its own
# nodes. The script waits for it to exit (that drain plus a margin), then
# sends SIGTERM to whatever wrappers of that manager are left, and escalates
# to SIGKILL after FORCE_AFTER seconds if anything is still up. Signalling the
# nodes together with the manager killed the statements its drain waits for
# (#221). With no manager running, the wrappers are stopped at once.
#
# Overrides via env vars:
#   QOD_ON_DEMAND_PORT    (default 20900 - manager REST + UI)
#   PROXY_PORT                 (default 31338 - FlightSQL edge)
#   QOD_DRAIN_TIMEOUT_SEC      (default 60 - the manager's drain, as it reads it)
#   FORCE_AFTER                (default 10 - seconds before SIGKILL)
#
# Usage:
#   ./scripts/stop-jar.sh

set -euo pipefail

MGR_PORT="${QOD_ON_DEMAND_PORT:-20900}"
EDGE_PORT="${PROXY_PORT:-31338}"
FORCE_AFTER="${FORCE_AFTER:-10}"
DRAIN_TIMEOUT="${QOD_DRAIN_TIMEOUT_SEC:-60}"
[[ "$DRAIN_TIMEOUT" =~ ^[0-9]+$ ]] || DRAIN_TIMEOUT=60
# How much longer than its drain the manager may take to exit: its JVM shutdown hook waits the
# drain plus 15 s for the graceful chain before it forces its own teardown, which stops the nodes.
DRAIN_MARGIN=20

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

mgr_pids=$(echo "$mgr_pid $edge_pid" | tr ' ' '\n' | sort -u | tr '\n' ' ')

# True while a manager pid still runs. A zombie counts as gone: under run-jar.sh the JVM is the
# child of the script that is running this one, and stays a zombie until that script reaps it.
manager_alive() {
  local pid
  for pid in $mgr_pids; do
    [[ -z "$pid" ]] && continue
    if kill -0 "$pid" 2>/dev/null && [[ "$(ps -p "$pid" -o stat= 2>/dev/null | tr -d ' ')" != Z* ]]; then
      return 0
    fi
  done
  return 1
}

# ---- Graceful: the manager first, alone ----
# SIGTERM runs the manager's graceful shutdown (ShutdownCoordinator): it stops the FlightSQL edge
# and the front door, waits for in-flight statements, then stops its own nodes.
waited=0
if [[ -n "${mgr_pids// /}" ]]; then
  budget=$((DRAIN_TIMEOUT + DRAIN_MARGIN))
  echo "stopping the manager first: it lets running statements finish for up to ${DRAIN_TIMEOUT}s" \
    "(QOD_DRAIN_TIMEOUT_SEC), then stops its nodes; waiting up to ${budget}s for it to exit."
  for pid in $mgr_pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done
  while (( waited < budget )) && manager_alive; do
    sleep 1
    waited=$((waited + 1))
  done
  if manager_alive; then
    echo "the manager is still running after ${budget}s; stopping its nodes."
  fi
fi

# ---- Then the nodes it left ----
# Spawned quack scripts trap TERM and tear down their qod-node child.
left_spawn=$(pgrep -f "$SPAWN_RE" 2>/dev/null || true)
for pid in $left_spawn; do
  kill -TERM "$pid" 2>/dev/null || true
done
all_pids=$(echo "$mgr_pids $spawn_pids $left_spawn" | tr ' ' '\n' | sort -u | tr '\n' ' ')

# ---- Wait for ports to clear ----
for ((i=0; i<=FORCE_AFTER; i++)); do
  if ! manager_alive \
     && ! lsof -nP -iTCP:"$MGR_PORT","$EDGE_PORT" -sTCP:LISTEN -t 2>/dev/null > /dev/null \
     && ! pgrep -f "$SPAWN_RE" > /dev/null 2>&1; then
    echo "stopped cleanly after $((waited + i))s."
    exit 0
  fi
  if (( i < FORCE_AFTER )); then
    sleep 1
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