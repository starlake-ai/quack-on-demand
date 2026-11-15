# Part 2 QoD stack (isolated). Source it: . bench/search/stack/qod_env.sh <p2>
# Exports no secret. QOD_HOST / QOD_PORT / QOD_TLS pin every qod CLI command (qod sql included)
# to this stack's Flight SQL edge: without them a profile with no edge settings falls back to
# localhost:31338, the owner's manager.
P2="$1"
# Drop inherited settings that could point the stack at the owner's state first: platformdirs
# honors XDG_* over HOME on macOS (the embedded Postgres data dir, the cache), and any QOD_*,
# PROXY_* or SL_* variable (a data dir, a Postgres host, keys, secrets) would reach the manager.
# Names only; no value is read or printed.
unset XDG_DATA_HOME XDG_CACHE_HOME XDG_CONFIG_HOME XDG_STATE_HOME
for _qod_v in $(compgen -e | grep -E '^(QOD_|PROXY_|SL_)'); do unset "$_qod_v"; done
unset _qod_v
export HOME="$P2/home" QOD_CONFIG_FILE="$P2/home/config.toml" QOD_MANAGER_URL=http://127.0.0.1:27900
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
export JAVA_BIN="$JAVA_HOME/bin/java"
export DUCKDB_CACHE_DIR="$P2/duckdb-cache" QOD_NODE_BIN="$P2/node/bin/qod-node"
export QOD_ON_DEMAND_HOST=127.0.0.1 QOD_ON_DEMAND_PORT=27900 QOD_INTERNAL_PORT=27901
export PROXY_HOST=127.0.0.1 PROXY_PORT=27338 QOD_QUACK_HOST=127.0.0.1 QOD_QUACK_PORT=27494
export QOD_PG_EMBEDDED_PORT=27499 QOD_MIN_PORT=27600 QOD_MAX_PORT=27650 QOD_NO_UPDATE_CHECK=1
export QOD_HOST=127.0.0.1 QOD_PORT=27338 QOD_TLS=true
# Nodes inherit the manager's environment; the spawn script binds 0.0.0.0 unless told otherwise.
export QOD_NODE_BIND=127.0.0.1
unset VIRTUAL_ENV AI_AGENT
