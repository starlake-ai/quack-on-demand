#!/usr/bin/env bash
# K8s entrypoint for the Quack node image. The manager (KubernetesQuackBackend)
# passes the listen port via QOD_NODE_PORT and the node's id via QOD_NODE_ID; the
# other plain env vars are the metastore keys the spawn script reads (TenantDb.NodeEnvKeys:
# pgHost, pgPort, pgUser, dbName, catalogAlias, schemaName, dataPath, encrypted; no other
# metastore key reaches a pod) and what the manager sets itself (kind, dbInitSql,
# lockdownSql, the three QOD_NODE_*_EXTENSIONS policy variables, the optional
# non-secret QOD_S3_* knobs). The sensitive values (token, node
# key, pgPassword, encryptionKey, federation and object-store SQL, object-store
# credentials) are NOT env vars: they are files of the read-only Secret volume named
# by QOD_SECRETS_DIR, which spawn-quack-node.sh reads itself (qod-node reads the
# token and the node key). Only the port is passed on the command line.

set -euo pipefail

: "${QOD_SECRETS_DIR:?QOD_SECRETS_DIR is required (the node secrets volume)}"
PORT="${QOD_NODE_PORT:-8080}"

exec /app/scripts/spawn-quack-node.sh "$PORT"
