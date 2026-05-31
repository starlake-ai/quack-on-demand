#!/usr/bin/env bash
# K8s entrypoint for the Quack node image. The manager (KubernetesQuackBackend)
# passes the listen port + token via QUACK_PORT / QUACK_TOKEN env vars; every
# other env var (pgHost, pgPort, pgUser, pgPassword, dbName, schemaName,
# dataPath, plus the optional SL_QUACK_S3_* / SL_QUACK_AZURE_* knobs) is the
# merged defaultMetastore + spec.metastore that LocalQuackBackend would read
# in local mode. spawn-quack-node.sh takes the port + token as positional
# arguments, so we just translate.

set -euo pipefail

: "${QUACK_TOKEN:?QUACK_TOKEN is required}"
PORT="${QUACK_PORT:-8080}"

exec /app/scripts/spawn-quack-node.sh "$PORT" "$QUACK_TOKEN"