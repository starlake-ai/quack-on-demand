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
#   KIND_CLUSTER   kind cluster name        (default qod-test)
#   IMAGE          local image ref          (default quack-on-demand:local)
#   NAMESPACE      install namespace        (default qod)
#   RELEASE        helm release name        (default qod)
#   SKIP_BUILD     "1" to skip docker build (default unset)
#
# Requires: kind, kubectl, helm, docker.

set -euo pipefail

KIND_CLUSTER="${KIND_CLUSTER:-qod-test}"
IMAGE="${IMAGE:-quack-on-demand:local}"
NAMESPACE="${NAMESPACE:-qod}"
RELEASE="${RELEASE:-qod}"
SKIP_BUILD="${SKIP_BUILD:-0}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHART_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$CHART_DIR/../.." && pwd)"

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 not on PATH" >&2; exit 1; }; }
need kind
need kubectl
need helm
[[ "$SKIP_BUILD" == "1" ]] || need docker

# 1. kind cluster
if kind get clusters 2>/dev/null | grep -q "^${KIND_CLUSTER}$"; then
  echo "[1/7] kind cluster '$KIND_CLUSTER' already exists, reusing"
else
  echo "[1/7] creating kind cluster '$KIND_CLUSTER'..."
  kind create cluster --name "$KIND_CLUSTER"
fi
kubectl config use-context "kind-${KIND_CLUSTER}" >/dev/null

# 2. build the manager image
if [[ "$SKIP_BUILD" == "1" ]]; then
  echo "[2/7] SKIP_BUILD=1, assuming '$IMAGE' is already loadable"
else
  echo "[2/7] docker build -t $IMAGE ..."
  ( cd "$REPO_DIR" && docker build -t "$IMAGE" . )
fi

# 3. load the manager image into kind. The DuckDB Quack node image
# (referenced by `quackNode.image` in values.yaml) is operator-supplied
# and not part of this smoke - the spawned node pods will ImagePullBackOff
# until you set `--set quackNode.image=...` to something the cluster can pull.
echo "[3/7] loading manager image into kind cluster..."
kind load docker-image "$IMAGE" --name "$KIND_CLUSTER"

# 4. namespace + Postgres
echo "[4/7] applying minimal in-cluster Postgres..."
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/local-postgres.yaml"
kubectl -n "$NAMESPACE" rollout status deploy/postgres --timeout=120s

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