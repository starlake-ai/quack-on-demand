#!/usr/bin/env bash
#
# End-to-end smoke for the quack-on-demand Helm chart on a local kind
# cluster. Steps:
#
#   1. Create / reuse the kind cluster.
#   2. Acquire images (BUILD=0 pulls + retags from Docker Hub; BUILD=1
#      docker-builds from the source tree). Load both into the cluster
#      via `docker save | ctr import` (single-platform, no kind /
#      multi-arch quirks).
#   3. Apply the in-cluster Postgres + SeaweedFS.
#   4. Helm-install the chart from `values-local-stack.yaml`.
#   5. Optional TPC-H seed via `kubectl exec` into the manager pod
#      (uses the bundled `/app/scripts/load-tpch-dbgen.sh`; no host
#      duckdb dependency, no port-forward dance).
#   6. Print port-forward commands + tear-down hint.
#
# Env:
#   KIND_CLUSTER     kind cluster name              (default qod-test)
#   IMAGE            manager image ref              (default quack-on-demand:local)
#   NODE_IMAGE       Quack node image ref           (default starlakeai/quack-on-demand-node:local)
#   NAMESPACE        install namespace              (default qod)
#   RELEASE          helm release name              (default qod)
#   BUILD            "1" rebuilds the manager + node images from
#                    $REPO_DIR before loading. "0" reuses local
#                    `:local`-tagged images, pulling `:latest-snapshot`
#                    from Docker Hub and retagging when missing.
#                                                   (default 0)
#   NUKE             "1" deletes the namespace before reinstalling.
#                                                   (default 0)
#   LOAD_TPCH        scale factor (positive integer) for TPC-H seed.
#                    Unset = skip. Runs inside the manager pod, no
#                    host duckdb required.
#
# Requires: kind, kubectl, helm, docker.

set -euo pipefail

KIND_CLUSTER="${KIND_CLUSTER:-qod-test}"
IMAGE="${IMAGE:-quack-on-demand:local}"
NODE_IMAGE="${NODE_IMAGE:-starlakeai/quack-on-demand-node:local}"
NAMESPACE="${NAMESPACE:-qod}"
RELEASE="${RELEASE:-qod}"
BUILD="${BUILD:-0}"
NUKE="${NUKE:-0}"
LOAD_TPCH="${LOAD_TPCH:-}"
MGR_HUB_IMAGE="${MGR_HUB_IMAGE:-starlakeai/quack-on-demand:latest-snapshot}"
NODE_HUB_IMAGE="${NODE_HUB_IMAGE:-starlakeai/quack-on-demand-node:latest-snapshot}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHART_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$CHART_DIR/../.." && pwd)"

# ---- 1. kind cluster ------------------------------------------------------
if kind get clusters 2>/dev/null | grep -q "^${KIND_CLUSTER}$"; then
  echo "[1/5] reusing kind cluster '${KIND_CLUSTER}'"
else
  echo "[1/5] creating kind cluster '${KIND_CLUSTER}'..."
  kind create cluster --name "${KIND_CLUSTER}"
fi
kubectl config use-context "kind-${KIND_CLUSTER}" >/dev/null

# ---- 2. acquire + load images --------------------------------------------
#
# `kind load docker-image` calls `ctr import --all-platforms` inside the
# node, which fails on multi-arch manifest lists when only the host
# platform's layers were actually pulled (the Apple Silicon case). Pipe
# `docker save` straight into `ctr import` (no `--all-platforms`) so the
# import only sees the platform that's actually in the host's docker.
ensure_local_image() {
  local local_ref="$1" hub_ref="$2"
  if docker image inspect "$local_ref" >/dev/null 2>&1; then
    echo "  found '$local_ref'"
    return 0
  fi
  echo "  pulling '$hub_ref' and tagging as '$local_ref'..."
  docker pull "$hub_ref"
  docker tag  "$hub_ref" "$local_ref"
}

if [[ "$BUILD" == "1" ]]; then
  echo "[2/5] BUILD=1: docker build manager + Quack node from $REPO_DIR..."
  ( cd "$REPO_DIR" && docker build -t "$IMAGE"      . )
  ( cd "$REPO_DIR" && docker build -t "$NODE_IMAGE" -f docker/quack-node/Dockerfile . )
else
  echo "[2/5] BUILD=0: ensuring local images (pull from Docker Hub if missing)..."
  ensure_local_image "$IMAGE"      "$MGR_HUB_IMAGE"
  ensure_local_image "$NODE_IMAGE" "$NODE_HUB_IMAGE"
fi

control_plane_node="${KIND_CLUSTER}-control-plane"
echo "  loading into kind (docker save | ctr import)..."
for img in "$IMAGE" "$NODE_IMAGE"; do
  echo "    $img"
  docker image save "$img" \
    | docker exec -i "$control_plane_node" ctr -n k8s.io images import - >/dev/null
done

# ---- 3. in-cluster Postgres + SeaweedFS ----------------------------------
if [[ "$NUKE" == "1" ]]; then
  echo "[3/5] NUKE=1: deleting namespace '$NAMESPACE'..."
  kubectl delete namespace "$NAMESPACE" --ignore-not-found --wait=true --timeout=120s
fi
echo "[3/5] applying Postgres + SeaweedFS in '$NAMESPACE'..."
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/local-postgres.yaml"
kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/seaweedfs.yaml"
kubectl -n "$NAMESPACE" rollout status deploy/postgres  --timeout=120s
kubectl -n "$NAMESPACE" rollout status deploy/seaweedfs --timeout=120s

# ---- 4. helm install -----------------------------------------------------
echo "[4/5] helm install $RELEASE..."
helm upgrade --install "$RELEASE" "$CHART_DIR" \
  --namespace "$NAMESPACE" \
  -f "$SCRIPT_DIR/values-local-stack.yaml" \
  --wait --timeout=300s

# ---- 5. optional TPC-H seed ---------------------------------------------
#
# Runs inside the manager pod via the bundled /app/scripts/load-tpch-dbgen.sh.
# The pod has cluster DNS so it reaches `postgres` + `seaweedfs` directly;
# no host duckdb, no port-forward orchestration.
if [[ -n "$LOAD_TPCH" ]]; then
  if ! [[ "$LOAD_TPCH" =~ ^[0-9]+$ ]] || [[ "$LOAD_TPCH" -lt 1 ]]; then
    echo "[5/5] WARN: LOAD_TPCH='$LOAD_TPCH' is not a positive integer; skipping seed." >&2
  else
    echo "[5/5] seeding TPC-H SF=$LOAD_TPCH inside the manager pod..."
    MGR_DEPLOY=$(kubectl -n "$NAMESPACE" get deploy \
      -l "app.kubernetes.io/instance=$RELEASE,app.kubernetes.io/name=quack-on-demand" \
      -o jsonpath='{.items[0].metadata.name}')
    # DATA_PATH must point at the per-tenant-db prefix the manager
    # registered with DuckLake -- NOT the root `qod-test`. Manager derives
    # it as `parent(rootDataPath)/${tenant}_${tenantDb}`; mirror that here.
    TENANT_DB_NAME="${TPCH_DB:-tpch_tpch1}"
    SEED_DATA_PATH="s3://qod-ducklake/$TENANT_DB_NAME"
    kubectl -n "$NAMESPACE" exec "deploy/$MGR_DEPLOY" -- \
      env PG_HOST=postgres PG_PORT=5432 PG_USER=postgres PG_PASS=azizam \
          DB_NAME="$TENANT_DB_NAME" SCHEMA_NAME="${TPCH_SCHEMA:-tpch1}" \
          SF="$LOAD_TPCH" \
          DATA_PATH="$SEED_DATA_PATH" \
          QOD_S3_ENDPOINT="http://seaweedfs:8333" \
          QOD_S3_ACCESS_KEY_ID=quack QOD_S3_SECRET_ACCESS_KEY=quackquack \
          QOD_S3_REGION=us-east-1 QOD_S3_URL_STYLE=path QOD_S3_USE_SSL=false \
      /app/scripts/load-tpch-dbgen.sh
  fi
fi

# ---- summary -------------------------------------------------------------
# Helm prepends the chart name to the release name unless the release
# name already contains it; resolve from the cluster so the printed
# commands always match what is actually there.
REST_SVC=$(kubectl -n "$NAMESPACE" get svc \
  -l "app.kubernetes.io/instance=$RELEASE,app.kubernetes.io/name=quack-on-demand" \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
FS_SVC=$(kubectl -n "$NAMESPACE" get svc \
  -l "app.kubernetes.io/instance=$RELEASE,app.kubernetes.io/component=flightsql" \
  -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)

cat <<EOM

manager Ready. To connect:
  kubectl -n $NAMESPACE port-forward svc/$REST_SVC 20900:20900
  kubectl -n $NAMESPACE port-forward svc/$FS_SVC   31338:31338

watch Quack node pods:
  kubectl -n $NAMESPACE get pods -l managed-by=quack-on-demand -w

tear down:
  kind delete cluster --name $KIND_CLUSTER
EOM