#!/usr/bin/env bash
# K8s entrypoint for the Quack node image. The manager (KubernetesQuackBackend)
# passes the listen port + token via QOD_NODE_PORT / QOD_NODE_TOKEN env vars; every
# other env var (pgHost, pgPort, pgUser, pgPassword, dbName, schemaName,
# dataPath, plus the optional QOD_S3_* / QOD_AZURE_* knobs) is the
# merged defaultMetastore + spec.metastore that LocalQuackBackend would read
# in local mode. spawn-quack-node.sh takes the port + token as positional
# arguments, so we just translate.

set -euo pipefail

: "${QOD_NODE_TOKEN:?QOD_NODE_TOKEN is required}"
PORT="${QOD_NODE_PORT:-8080}"

exec /app/scripts/spawn-quack-node.sh "$PORT" "$QOD_NODE_TOKEN"