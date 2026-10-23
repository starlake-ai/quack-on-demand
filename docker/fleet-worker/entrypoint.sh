#!/usr/bin/env bash
# Fleet server entrypoint. A container's defaults are wrong for both identity and address: the
# hostname is the container id (a new server on every recreate) and the first IPv4 is the bridge
# address (unreachable from the manager), so both must be set explicitly.
set -euo pipefail

: "${QOD_FLEET_NAME:?QOD_FLEET_NAME is required: a stable server name (the container hostname changes on every recreate)}"
: "${QOD_FLEET_ADVERTISE_HOST:?QOD_FLEET_ADVERTISE_HOST is required: the host address the manager dials (not the container bridge IP)}"
: "${QOD_MANAGER_URL:?QOD_MANAGER_URL is required, e.g. http://mgr.internal:20900}"
: "${QOD_FLEET_JOIN_TOKEN:?QOD_FLEET_JOIN_TOKEN is required}"

args=(fleet join --bind-host 0.0.0.0 --state-dir "$HOME/.qod-fleet")
if [[ "${QOD_FLEET_INSECURE:-false}" == "true" ]]; then
  args+=(--insecure)
fi
exec qod "${args[@]}" "$@"
