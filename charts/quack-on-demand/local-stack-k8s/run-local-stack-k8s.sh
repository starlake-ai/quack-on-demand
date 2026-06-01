#!/usr/bin/env bash
#
# End-to-end smoke for the chart on a local kind cluster.
#
# Steps:
#   1. Create a kind cluster (named `qod-test` by default; reused if exists).
#   2. Build the manager + Quack node images from the current source tree.
#   3. Load both images into the kind cluster.
#   4. Apply a minimal in-cluster Postgres + SeaweedFS (one pod each).
#   5. helm install the chart pointing at Postgres + SeaweedFS + the local image.
#   6. Wait for the manager pod to be Ready and /health to return OK.
#   7. Verify the manager spawned the bootstrap Quack node pods.
#
# DuckLake DATA_PATH is an `s3://` URL backed by SeaweedFS. The manager-side
# TPC-H loader (SF=N) writes parquet there via a port-forward; the Quack
# node pods read at the same s3:// URL through the in-cluster Service. Same
# SL_QUACK_S3_* env contract as docker-compose.
#
# Env:
#   KIND_CLUSTER   kind cluster name              (default qod-test)
#   IMAGE          local image ref                (default quack-on-demand:local)
#   NAMESPACE      install namespace              (default qod)
#   RELEASE        helm release name              (default qod)
#   BUILD          "1" to docker build the manager + node images from this
#                  tree. "0" to reuse the local `:local`-tagged images; if
#                  they're missing, the script pulls `:latest-snapshot` from
#                  Docker Hub (starlakeai/quack-on-demand{,-node}) and retags
#                  them locally so the rest of the flow is unchanged.
#                  Same convention as scripts/run-jar.sh.          (default 1)
#   NUKE           "1" to delete the namespace before reinstalling (wipes
#                  Postgres ephemeral data, the helm release, and any
#                  orphan quack node pods).                       (default 0)
#   SF             TPC-H scale factor to seed into the in-cluster Postgres
#                  before the manager boots (mirrors SF in run-jar.sh).
#                  Requires `duckdb` CLI on the host. SF=1 ≈ 6M lineitem
#                  rows. Unset to skip the seed.                  (default unset)
#
# Requires: kind, kubectl, helm, docker. SF requires duckdb on the host.

set -euo pipefail

KIND_CLUSTER="${KIND_CLUSTER:-qod-test}"
IMAGE="${IMAGE:-quack-on-demand:local}"
NAMESPACE="${NAMESPACE:-qod}"
RELEASE="${RELEASE:-qod}"
BUILD="${BUILD:-1}"
NUKE="${NUKE:-0}"
SF="${SF:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHART_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$CHART_DIR/../.." && pwd)"

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 not on PATH" >&2; exit 1; }; }
need kind
need kubectl
need helm
[[ "$BUILD" == "1" ]] && need docker

# 1. kind cluster
if kind get clusters 2>/dev/null | grep -q "^${KIND_CLUSTER}$"; then
  echo "[1/7] kind cluster '$KIND_CLUSTER' already exists, reusing"
else
  echo "[1/7] creating kind cluster '$KIND_CLUSTER'..."
  kind create cluster --name "$KIND_CLUSTER"
fi
kubectl config use-context "kind-${KIND_CLUSTER}" >/dev/null

# 2. build (or fetch) the manager + node images.
NODE_IMAGE="${NODE_IMAGE:-starlakeai/quack-on-demand-node:local}"
# Docker Hub fallbacks used by BUILD=0 when no local image is present.
MGR_HUB_IMAGE="${MGR_HUB_IMAGE:-starlakeai/quack-on-demand:latest-snapshot}"
NODE_HUB_IMAGE="${NODE_HUB_IMAGE:-starlakeai/quack-on-demand-node:latest-snapshot}"

# Pull $2 from Docker Hub and retag to $1 if $1 isn't already present
# locally. Lets BUILD=0 work on a fresh machine that has never built
# the manager — falls back to the published snapshot images. The retag
# keeps the rest of this script (kind load, helm install) using the
# stable `:local` refs.
ensure_local_image() {
  local local_ref="$1" hub_ref="$2"
  if docker image inspect "$local_ref" >/dev/null 2>&1; then
    echo "  found local image '$local_ref'"
    return 0
  fi
  echo "  '$local_ref' missing locally; pulling '$hub_ref' from Docker Hub..."
  docker pull "$hub_ref"
  docker tag  "$hub_ref" "$local_ref"
}

if [[ "$BUILD" == "0" ]]; then
  echo "[2/7] BUILD=0, reusing local images (falling back to Docker Hub if missing)..."
  ensure_local_image "$IMAGE"      "$MGR_HUB_IMAGE"
  ensure_local_image "$NODE_IMAGE" "$NODE_HUB_IMAGE"
else
  echo "[2/7] docker build -t $IMAGE (manager) and -t $NODE_IMAGE (quack node)..."
  ( cd "$REPO_DIR" && docker build -t "$IMAGE" . )
  ( cd "$REPO_DIR" && docker build -t "$NODE_IMAGE" -f docker/quack-node/Dockerfile . )
fi

# 3. load both images into kind.
echo "[3/7] loading images into kind cluster..."
kind load docker-image "$IMAGE" "$NODE_IMAGE" --name "$KIND_CLUSTER"

# 4. namespace + Postgres
# NUKE=1: wipe the whole namespace first so Postgres's emptyDir,
# spawned quack node pods, the helm release secret, and the manager
# Deployment all go together. Idempotent: missing namespace is fine.
if [[ "$NUKE" == "1" ]]; then
  echo "[4/7] NUKE=1: deleting namespace '$NAMESPACE' to wipe all state..."
  kubectl delete namespace "$NAMESPACE" --ignore-not-found --wait=true --timeout=120s
fi
echo "[4/7] applying in-cluster Postgres + SeaweedFS..."
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/local-postgres.yaml"
kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/seaweedfs.yaml"
kubectl -n "$NAMESPACE" rollout status deploy/postgres  --timeout=120s
kubectl -n "$NAMESPACE" rollout status deploy/seaweedfs --timeout=120s

# S3 settings that both the seed (run on the host via port-forward) and the
# chart install (and from there, every spawned node pod) must agree on.
# Match the defaults baked into charts/quack-on-demand/local-stack-k8s/seaweedfs.yaml.
S3_BUCKET="${S3_BUCKET:-qod-ducklake}"
S3_PREFIX="${S3_PREFIX:-qod-test}"
S3_ACCESS_KEY="${S3_ACCESS_KEY:-quack}"
S3_SECRET_KEY="${S3_SECRET_KEY:-quackquack}"
DATA_PATH_S3="s3://${S3_BUCKET}/${S3_PREFIX}"

# 4b. optional TPC-H seed (mirrors SF in scripts/run-jar.sh). The DuckLake
# catalog goes into the in-cluster Postgres; parquet files land in
# SeaweedFS so the Quack node pods can read them at the same s3:// URL
# the manager records. Both Postgres and SeaweedFS are reached via short-
# lived port-forwards from the host.
if [[ -n "$SF" ]]; then
  if ! command -v duckdb >/dev/null 2>&1; then
    echo "WARN: SF=$SF set but duckdb CLI not on PATH; skipping seed." >&2
  else
    echo "[4b/7] seeding TPC-H SF=$SF into Postgres catalog + SeaweedFS parquet..."
    SEED_PG_PORT=15432
    SEED_S3_PORT=18333
    pkill -f "port-forward.*postgres.*$SEED_PG_PORT:5432"   2>/dev/null || true
    pkill -f "port-forward.*seaweedfs.*$SEED_S3_PORT:8333"  2>/dev/null || true
    kubectl -n "$NAMESPACE" port-forward svc/postgres   "$SEED_PG_PORT:5432" \
      > /tmp/qod-seed-pg-pf.log 2>&1 &
    SEED_PG_PID=$!
    kubectl -n "$NAMESPACE" port-forward svc/seaweedfs  "$SEED_S3_PORT:8333" \
      > /tmp/qod-seed-s3-pf.log 2>&1 &
    SEED_S3_PID=$!
    trap '
      kill $SEED_PG_PID $SEED_S3_PID 2>/dev/null;
      pkill -P $SEED_PG_PID 2>/dev/null;
      pkill -P $SEED_S3_PID 2>/dev/null
    ' EXIT
    sleep 3
    DATA_PATH="$DATA_PATH_S3" \
    PG_HOST=localhost PG_PORT="$SEED_PG_PORT" PG_USER=postgres PG_PASS=azizam \
    DB_NAME=tpch SCHEMA_NAME="${TPCH_SCHEMA:-tpch1}" SF="$SF" \
    SL_QUACK_S3_ENDPOINT="http://localhost:$SEED_S3_PORT" \
    SL_QUACK_S3_ACCESS_KEY_ID="$S3_ACCESS_KEY" \
    SL_QUACK_S3_SECRET_ACCESS_KEY="$S3_SECRET_KEY" \
    SL_QUACK_S3_REGION=us-east-1 \
    SL_QUACK_S3_URL_STYLE=path \
    SL_QUACK_S3_USE_SSL=false \
      "$REPO_DIR/scripts/load-tpch-dbgen.sh"
    kill $SEED_PG_PID $SEED_S3_PID 2>/dev/null || true
    trap - EXIT
    echo "TPC-H SF=$SF seed complete (catalog -> Postgres, parquet -> SeaweedFS)."
  fi
fi

# 4c. clean orphan Quack node pods. If a previous bootstrap got
# part-way (e.g. quack node image was bad and ImagePullBackOff prevented
# Ready), the manager rolled back the pool record in Postgres but the
# K8s pod object stuck around. The next bootstrap then fails with 409
# Conflict on pod create. Wipe any orphans before reinstalling. Idempotent.
ORPHANS=$(kubectl -n "$NAMESPACE" get pods -l managed-by=quack-on-demand \
  -o name 2>/dev/null | wc -l | tr -d ' ')
if [[ "$ORPHANS" -gt 0 ]]; then
  echo "[4c/7] cleaning $ORPHANS orphan quack node pod(s) from a prior run..."
  kubectl -n "$NAMESPACE" delete pods,services -l managed-by=quack-on-demand --grace-period=0 --force 2>&1 | tail -5 || true
fi

# 5. helm install
echo "[5/7] helm install $RELEASE..."
helm upgrade --install "$RELEASE" "$CHART_DIR" \
  --namespace "$NAMESPACE" \
  --set "image.repository=$(echo "$IMAGE" | cut -d: -f1)" \
  --set "image.tag=$(echo "$IMAGE" | cut -d: -f2)" \
  --set image.pullPolicy=Never \
  --set postgres.host=postgres \
  --set postgres.existingSecret=postgres \
  --set postgres.existingSecretKey=POSTGRES_PASSWORD \
  --set admin.password=admin \
  --set flightsql.tls.enabled=true \
  --set "quackNode.image=$NODE_IMAGE" \
  --set "storage.dataPath=$DATA_PATH_S3" \
  --set "s3.endpoint=http://seaweedfs:8333" \
  --set "s3.accessKey=$S3_ACCESS_KEY" \
  --set "s3.secretKey=$S3_SECRET_KEY" \
  --set s3.region=us-east-1 \
  --set s3.urlStyle=path \
  --set s3.useSsl=false \
  --wait --timeout=300s

# 6. /health
# Always go via port-forward + local curl. The manager image is JRE-only
# (no curl/wget), so an in-pod probe needs an extra package install which
# isn't worth the complexity. The trap guarantees the port-forward dies
# even if curl below fails under `set -e`.
echo "[6/7] verifying /health..."
kubectl -n "$NAMESPACE" wait --for=condition=Ready \
  pod -l "app.kubernetes.io/instance=$RELEASE,app.kubernetes.io/name=quack-on-demand" \
  --timeout=180s
REST_SVC=$(kubectl -n "$NAMESPACE" get svc \
  -l "app.kubernetes.io/instance=$RELEASE,app.kubernetes.io/name=quack-on-demand" \
  -o jsonpath='{.items[0].metadata.name}')

# Best-effort: kill any prior port-forward holding the port from a
# previous run of this script.
pkill -f "port-forward.*${REST_SVC}.*20900:20900" 2>/dev/null || true
sleep 1

kubectl -n "$NAMESPACE" port-forward "svc/$REST_SVC" 20900:20900 \
  > /tmp/qod-smoke-pf.log 2>&1 &
PF_PID=$!
# Clean up the forwarder on any exit path (success, set -e, ctrl-C).
trap 'kill $PF_PID 2>/dev/null; pkill -P $PF_PID 2>/dev/null; wait $PF_PID 2>/dev/null' EXIT
sleep 4
HEALTH=$(curl -fsS http://localhost:20900/health 2>/dev/null || true)
echo "manager /health: $HEALTH"
echo "$HEALTH" | grep -q '"status":"ok"' || {
  echo "ERROR: /health did not report OK"
  echo "--- manager logs (last 40 lines) ---"
  MANAGER_POD=$(kubectl -n "$NAMESPACE" get pod \
    -l "app.kubernetes.io/instance=$RELEASE,app.kubernetes.io/name=quack-on-demand" \
    -o jsonpath='{.items[0].metadata.name}')
  kubectl -n "$NAMESPACE" logs "$MANAGER_POD" --tail=40
  exit 1
}

# 7. quack node pods — proves the manager's K8s RBAC + the pod-spawn path
# work end-to-end. The pods themselves will only become Ready when the
# Quack node image (`quackNode.image`, default `starlakeai/quack:latest`)
# is loadable by the cluster — out of scope for this smoke since that
# image is operator-supplied.
echo "[7/7] checking the manager spawned its bootstrap quack node pods..."
sleep 5
NODE_COUNT=$(kubectl -n "$NAMESPACE" get pods -l managed-by=quack-on-demand \
  --no-headers 2>/dev/null | wc -l | tr -d ' ')
echo "quack node pods spawned: $NODE_COUNT"
kubectl -n "$NAMESPACE" get pods -l managed-by=quack-on-demand 2>/dev/null || true

if [[ "$NODE_COUNT" -ge 1 ]]; then
  # Helm prepends the chart name to the release name (per qod.fullname helper)
  # unless the release name already contains the chart name. Resolve the
  # real service name from the cluster so these copy-paste commands work.
  REST_SVC=$(kubectl -n "$NAMESPACE" get svc \
    -l "app.kubernetes.io/instance=$RELEASE,app.kubernetes.io/name=quack-on-demand" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
  FS_SVC=$(kubectl -n "$NAMESPACE" get svc \
    -l "app.kubernetes.io/instance=$RELEASE,app.kubernetes.io/component=flightsql" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
  echo
  echo "smoke OK - manager is up, RBAC works, K8s pod-spawn path works."
  echo "  Quack node pods will ImagePullBackOff until you provide a real"
  echo "  Quack image: helm upgrade ... --set quackNode.image=<your-image>"
  echo
  echo "  port-forward UI:        kubectl -n $NAMESPACE port-forward svc/$REST_SVC 20900:20900"
  echo "  port-forward FlightSQL: kubectl -n $NAMESPACE port-forward svc/$FS_SVC 31338:31338"
  echo "  tear down:              kind delete cluster --name $KIND_CLUSTER"
else
  echo
  echo "WARN: no quack node pods detected; bootstrap may still be in flight."
  echo "  kubectl -n $NAMESPACE get pods -l managed-by=quack-on-demand"
fi