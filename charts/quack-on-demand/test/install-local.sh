#!/usr/bin/env bash
#
# End-to-end smoke for the chart on a local kind cluster.
#
# Steps:
#   1. Create a kind cluster (named `qod-test` by default; reused if exists).
#   2. Build the manager image from the current source tree.
#   3. Load the image into the kind cluster.
#   4. Apply a minimal in-cluster Postgres (Deployment + Service + Secret).
#   5. helm install the chart pointing at that Postgres + the local image.
#   6. Wait for the manager pod to be Ready and /health to return OK.
#   7. Verify the manager spawned the bootstrap Quack node pods.
#
# Env:
#   KIND_CLUSTER   kind cluster name              (default qod-test)
#   IMAGE          local image ref                (default quack-on-demand:local)
#   NAMESPACE      install namespace              (default qod)
#   RELEASE        helm release name              (default qod)
#   BUILD          "1" to docker build the manager image, "0" to reuse a
#                  pre-loaded image in the kind cluster. Same convention as
#                  scripts/run-jar.sh and run-docker-compose.sh.   (default 1)
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

# 2. build the manager image
NODE_IMAGE="${NODE_IMAGE:-starlakeai/quack-on-demand-node:local}"

if [[ "$BUILD" == "0" ]]; then
  echo "[2/7] BUILD=0, assuming '$IMAGE' and '$NODE_IMAGE' are already loaded in the kind cluster"
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
echo "[4/7] applying minimal in-cluster Postgres..."
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/local-postgres.yaml"
kubectl -n "$NAMESPACE" rollout status deploy/postgres --timeout=120s

# 4b. optional TPC-H seed (mirrors SF in scripts/run-jar.sh). Connects
# to the in-cluster Postgres via a short-lived port-forward and shells
# out to scripts/load-tpch-dbgen.sh on the host.
if [[ -n "$SF" ]]; then
  if ! command -v duckdb >/dev/null 2>&1; then
    echo "WARN: SF=$SF set but duckdb CLI not on PATH; skipping seed." >&2
  else
    echo "[4b/7] seeding TPC-H SF=$SF into in-cluster Postgres..."
    # Pick a free local port so this composes with other port-forwards
    # the user might have on :5432.
    SEED_PORT=15432
    pkill -f "port-forward.*postgres.*$SEED_PORT:5432" 2>/dev/null || true
    kubectl -n "$NAMESPACE" port-forward svc/postgres "$SEED_PORT:5432" \
      > /tmp/qod-seed-pf.log 2>&1 &
    SEED_PF_PID=$!
    trap 'kill $SEED_PF_PID 2>/dev/null; pkill -P $SEED_PF_PID 2>/dev/null' EXIT
    sleep 3
    DATA_PATH="${DATA_PATH:-$REPO_DIR/ducklake/qod-test}" \
    PG_HOST=localhost PG_PORT="$SEED_PORT" PG_USER=postgres PG_PASS=azizam \
    DB_NAME=tpch SCHEMA_NAME="${TPCH_SCHEMA:-tpch1}" SF="$SF" \
      "$REPO_DIR/scripts/load-tpch-dbgen.sh"
    kill $SEED_PF_PID 2>/dev/null || true
    trap - EXIT
    echo "TPC-H SF=$SF seed complete."
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
  --set flightsql.tls.enabled=false \
  --set "quackNode.image=$NODE_IMAGE" \
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