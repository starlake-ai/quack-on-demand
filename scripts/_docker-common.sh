#!/usr/bin/env bash
#
# Shared helpers for run-docker.sh / run-docker-compose.sh. Sourced; defines
# functions only, no side effects. The two launchers keep their own proxy
# env-propagation loops (docker-run rewrites vars in-process and passes
# --add-host, compose exports them for the compose process to inherit) but
# share the loopback URL rewrite and the socat bridge machinery below.

# Rewrite a host-loopback proxy URL (http://127.0.0.1:3128 / http://localhost:...)
# to host.docker.internal so a container can reach the host's proxy. Inside the
# container 127.0.0.1 is the container's own loopback, not the host. Non-loopback
# URLs pass through unchanged; empty input echoes empty.
rewrite_loopback_proxy() {
  local raw="${1:-}"
  [[ -z "$raw" ]] && { echo ""; return; }
  if [[ "$raw" =~ ^([a-zA-Z]+://)(127\.0\.0\.1|localhost)(.*)$ ]]; then
    echo "${BASH_REMATCH[1]}host.docker.internal${BASH_REMATCH[3]}"
  else
    echo "$raw"
  fi
}

probe_tcp() { (echo > "/dev/tcp/$1/$2") >/dev/null 2>&1; }

# Auto-bridge loopback-only proxies onto the docker bridge. cntlm/squid often
# bind 127.0.0.1 only; the URL rewrite above makes the container LOOK UP the
# right name but the proxy still refuses connections from the docker bridge
# IP. For each port passed as an argument, spawn a socat passthrough that
# listens on the bridge IP and forwards to host loopback, but only when the
# proxy is reachable from loopback AND not yet from the bridge. Skipped
# entirely on non-proxied or already-routable setups. stop-docker.sh tears
# down these `quack-proxy-bridge-*` containers.
start_loopback_proxy_bridges() {
  (( $# > 0 )) || return 0
  local bridge_ip="172.17.0.1" gateway port name
  gateway="$(docker network inspect bridge \
    -f '{{range .IPAM.Config}}{{.Gateway}}{{end}}' 2>/dev/null || true)"
  [[ -n "$gateway" ]] && bridge_ip="$gateway"
  # Dedupe ports (HTTP+HTTPS commonly share one).
  local uniq_ports
  IFS=$'\n' read -r -d '' -a uniq_ports < <(printf '%s\n' "$@" | sort -u && printf '\0')
  for port in "${uniq_ports[@]}"; do
    name="quack-proxy-bridge-$port"
    if [[ -n "$(docker ps -q -f "name=^${name}$" 2>/dev/null)" ]]; then
      echo "proxy bridge already running: $name ($bridge_ip:$port -> 127.0.0.1:$port)"
      continue
    fi
    if probe_tcp "$bridge_ip" "$port"; then
      continue # someone else is already listening on the bridge IP
    fi
    if ! probe_tcp "127.0.0.1" "$port"; then
      echo "WARN: no proxy reachable on 127.0.0.1:$port; container will likely fail to reach it" >&2
      continue
    fi
    docker rm -f "$name" >/dev/null 2>&1 || true
    echo "starting proxy bridge: $bridge_ip:$port -> 127.0.0.1:$port (container=$name)"
    docker run -d --rm --name "$name" --network host alpine/socat \
      "TCP-LISTEN:$port,bind=$bridge_ip,fork,reuseaddr" "TCP:127.0.0.1:$port" >/dev/null
  done
}
