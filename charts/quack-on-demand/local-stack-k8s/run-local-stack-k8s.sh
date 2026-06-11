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
#   3. Apply the in-cluster Postgres + SeaweedFS + Prometheus + Grafana.
#      The dashboard ConfigMap is rebuilt from observability/grafana-dashboard.json
#      so the repo file stays the single source of truth.
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
#   LOAD_TPC         scale factor (positive integer) for TPC demo seed.
#                    Unset = skip. Seeds both acme_tpch (TPC-H) and
#                    globex_tpcds (TPC-DS) inside the manager pod; no
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
LOAD_TPC="${LOAD_TPC:-}"
MGR_HUB_IMAGE="${MGR_HUB_IMAGE:-starlakeai/quack-on-demand:latest-snapshot}"
NODE_HUB_IMAGE="${NODE_HUB_IMAGE:-starlakeai/quack-on-demand-node:latest-snapshot}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHART_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$CHART_DIR/../.." && pwd)"

# ---- 1. kind cluster ------------------------------------------------------
#
# kind-config.yaml maps host :20900 -> control-plane container :80 so
# the Traefik ingress installed in step 3 becomes the single host-visible
# port for every HTTP service in the rig. A cluster created before this
# file existed won't have the mapping; detect that by inspecting docker
# port bindings on the control-plane container and ask the user to
# recreate.
if kind get clusters 2>/dev/null | grep -q "^${KIND_CLUSTER}$"; then
  control_plane_node="${KIND_CLUSTER}-control-plane"
  if docker port "$control_plane_node" 80/tcp 2>/dev/null | grep -q ":20900$"; then
    echo "[1/5] reusing kind cluster '${KIND_CLUSTER}' (host :20900 mapping present)"
  else
    cat <<EOM >&2
[1/5] ERROR: kind cluster '${KIND_CLUSTER}' exists but is missing the
      host :20900 -> container :80 port mapping needed by the Traefik
      ingress. Recreate it:

        kind delete cluster --name ${KIND_CLUSTER}
        $0

      (Postgres + SeaweedFS data is ephemeral emptyDir, so this only
      costs you the previous TPC-H seed.)
EOM
    exit 1
  fi
else
  echo "[1/5] creating kind cluster '${KIND_CLUSTER}' with kind-config.yaml..."
  kind create cluster --name "${KIND_CLUSTER}" --config "$SCRIPT_DIR/kind-config.yaml"
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
echo "[3/5] applying Postgres + SeaweedFS + Prometheus + Grafana + Keycloak in '$NAMESPACE'..."
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/local-postgres.yaml"
kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/seaweedfs.yaml"
kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/prometheus.yaml"
kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/grafana.yaml"

# Realm import ConfigMap is built from the on-disk JSON so the realm
# stays editable in-repo as a normal file. Upsert via the standard
# `create --dry-run=client | apply` trick.
KEYCLOAK_REALM_JSON="$SCRIPT_DIR/keycloak-realm-qod.json"
if [[ -f "$KEYCLOAK_REALM_JSON" ]]; then
  kubectl -n "$NAMESPACE" create configmap keycloak-realm-import \
    --from-file="qod-realm.json=$KEYCLOAK_REALM_JSON" \
    --dry-run=client -o yaml \
    | kubectl -n "$NAMESPACE" apply -f -
else
  echo "  WARN: '$KEYCLOAK_REALM_JSON' missing; Keycloak will boot without the qod realm." >&2
fi
kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/keycloak.yaml"

# Static landing page at `/` -- the ingress in step 4b routes the root
# to this nginx pod, so opening http://localhost:20900/ shows links to
# every UI in the rig instead of the manager's bare /ui redirect.
kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/landing.yaml"

# Replace the dashboard stub ConfigMap with the real JSON from the repo.
# `kubectl create --dry-run | kubectl apply` is the only kubectl-native
# way to upsert a CM from a single file without losing the rest of the
# CM if it grows additional keys later.
DASHBOARD_JSON="$REPO_DIR/observability/grafana-dashboard.json"
if [[ -f "$DASHBOARD_JSON" ]]; then
  kubectl -n "$NAMESPACE" create configmap grafana-dashboard-qod \
    --from-file="quack-on-demand.json=$DASHBOARD_JSON" \
    --dry-run=client -o yaml \
    | kubectl -n "$NAMESPACE" apply -f -
  # Bounce Grafana so the new CM is mounted. The file-based provisioner
  # also re-reads every 10s once mounted, but the restart removes the
  # 10-second uncertainty window for the first boot.
  kubectl -n "$NAMESPACE" rollout restart deploy/grafana >/dev/null
else
  echo "  WARN: '$DASHBOARD_JSON' missing; Grafana will boot with the stub dashboard." >&2
fi

kubectl -n "$NAMESPACE" rollout status deploy/postgres   --timeout=120s
kubectl -n "$NAMESPACE" rollout status deploy/seaweedfs  --timeout=120s
kubectl -n "$NAMESPACE" rollout status deploy/prometheus --timeout=120s
kubectl -n "$NAMESPACE" rollout status deploy/grafana    --timeout=120s
# Keycloak cold-starts slowly (--import-realm parses the JSON before the
# realm endpoint goes ready), give it more headroom than the others.
kubectl -n "$NAMESPACE" rollout status deploy/keycloak    --timeout=240s
kubectl -n "$NAMESPACE" rollout status deploy/qod-landing --timeout=60s

# ---- 4. helm install -----------------------------------------------------
echo "[4/5] helm install $RELEASE..."
HELM_EXTRA_ARGS=()
if [[ -n "$LOAD_TPC" ]]; then
  HELM_EXTRA_ARGS+=(--set loadTpc.enabled=true)
fi
helm upgrade --install "$RELEASE" "$CHART_DIR" \
  --namespace "$NAMESPACE" \
  -f "$SCRIPT_DIR/values-local-stack.yaml" \
  "${HELM_EXTRA_ARGS[@]}" \
  --wait --timeout=300s

# ---- 4b. Traefik ingress -------------------------------------------------
#
# Single host port (kind extraPortMappings host :20900 -> control-plane
# container :80) fronts every HTTP service in the rig. Traefik runs as a
# DaemonSet on the ingress-ready=true node (the control-plane), binds the
# container :80 via hostPort, and the Ingress in ingress.yaml fans out
# /grafana, /prometheus, /auth, and / to the right Services.
#
# `helm upgrade --install` is idempotent; the values are inline because
# the rig only needs a handful of overrides and a separate yaml would be
# more boilerplate than payload.
echo "[4/5] installing Traefik v3 + applying ingress.yaml..."
helm upgrade --install traefik traefik \
  --repo https://traefik.github.io/charts \
  --namespace "$NAMESPACE" \
  --version "~33.0.0" \
  --set "providers.kubernetesIngress.enabled=true" \
  --set "providers.kubernetesCRD.enabled=true" \
  --set "ingressClass.enabled=true" \
  --set "ingressClass.isDefaultClass=true" \
  --set "service.enabled=false" \
  --set "deployment.kind=DaemonSet" \
  --set "ports.web.hostPort=80" \
  --set "ports.websecure.hostPort=443" \
  --set-string "nodeSelector.ingress-ready=true" \
  --set-string "tolerations[0].key=node-role.kubernetes.io/control-plane" \
  --set-string "tolerations[0].operator=Exists" \
  --set-string "tolerations[0].effect=NoSchedule" \
  --set-string "tolerations[1].key=node-role.kubernetes.io/master" \
  --set-string "tolerations[1].operator=Exists" \
  --set-string "tolerations[1].effect=NoSchedule" \
  --wait --timeout=180s

kubectl -n "$NAMESPACE" apply -f "$SCRIPT_DIR/ingress.yaml"

# ---- 5. optional TPC demo seed ------------------------------------------
#
# Runs inside the manager pod via the bundled loader scripts.
# The pod has cluster DNS so it reaches `postgres` + `seaweedfs` directly;
# no host duckdb, no port-forward orchestration.
# Two databases are seeded: acme_tpch (TPC-H) and globex_tpcds (TPC-DS).
if [[ -n "$LOAD_TPC" ]]; then
  if ! [[ "$LOAD_TPC" =~ ^[0-9]+$ ]] || [[ "$LOAD_TPC" -lt 1 ]]; then
    echo "[5/5] WARN: LOAD_TPC='$LOAD_TPC' is not a positive integer; skipping seed." >&2
  else
    echo "[5/5] seeding TPC demo SF=$LOAD_TPC inside the manager pod..."

    MGR_DEPLOY=$(kubectl -n "$NAMESPACE" get deploy \
      -l "app.kubernetes.io/instance=$RELEASE,app.kubernetes.io/name=quack-on-demand" \
      -o jsonpath='{.items[0].metadata.name}')

    # Locate the in-cluster Postgres pod so we can pre-create the catalog DBs.
    PG_POD=$(kubectl -n "$NAMESPACE" get pod \
      -l "app=postgres" \
      -o jsonpath='{.items[0].metadata.name}')

    # Pre-create both tenant databases (idempotent).
    echo "[5/5] pre-creating acme_tpch and globex_tpcds databases in Postgres..."
    kubectl -n "$NAMESPACE" exec "$PG_POD" -- \
      psql -U postgres -c \
        "SELECT 'CREATE DATABASE acme_tpch' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='acme_tpch')\gexec"
    kubectl -n "$NAMESPACE" exec "$PG_POD" -- \
      psql -U postgres -c \
        "SELECT 'CREATE DATABASE globex_tpcds' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='globex_tpcds')\gexec"

    # --- TPC-H loader: acme_tpch.tpch1 ---
    echo "[5/5] running TPC-H loader (acme_tpch.tpch1, SF=$LOAD_TPC)..."
    kubectl -n "$NAMESPACE" exec "deploy/$MGR_DEPLOY" -- \
      env PG_HOST=postgres PG_PORT=5432 PG_USER=postgres PG_PASS=azizam \
          DB_NAME=acme_tpch SCHEMA_NAME=tpch1 \
          SF="$LOAD_TPC" \
          DATA_PATH="s3://qod-ducklake/acme_tpch" \
          QOD_S3_ENDPOINT="http://seaweedfs:8333" \
          QOD_S3_ACCESS_KEY_ID=quack QOD_S3_SECRET_ACCESS_KEY=quackquack \
          QOD_S3_REGION=us-east-1 QOD_S3_URL_STYLE=path QOD_S3_USE_SSL=false \
      /app/scripts/load-tpch-dbgen.sh

    # --- TPC-DS loader: globex_tpcds.tpcds1 ---
    echo "[5/5] running TPC-DS loader (globex_tpcds.tpcds1, SF=$LOAD_TPC)..."
    kubectl -n "$NAMESPACE" exec "deploy/$MGR_DEPLOY" -- \
      env PG_HOST=postgres PG_PORT=5432 PG_USER=postgres PG_PASS=azizam \
          DB_NAME=globex_tpcds SCHEMA_NAME=tpcds1 \
          SF="$LOAD_TPC" \
          DATA_PATH="s3://qod-ducklake/globex_tpcds" \
          QOD_S3_ENDPOINT="http://seaweedfs:8333" \
          QOD_S3_ACCESS_KEY_ID=quack QOD_S3_SECRET_ACCESS_KEY=quackquack \
          QOD_S3_REGION=us-east-1 QOD_S3_URL_STYLE=path QOD_S3_USE_SSL=false \
      /app/scripts/load-tpcds-dbgen.sh
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

manager Ready. Open the landing page for links to every UI:

  http://localhost:20900/

Direct links (all behind the same Traefik ingress):
  admin UI / REST       http://localhost:20900/ui/        (admin / admin)
  Grafana               http://localhost:20900/grafana/   (anonymous Admin)
  Prometheus            http://localhost:20900/prometheus/
  Keycloak admin        http://localhost:20900/auth/      (admin / admin · realm 'qod')

FlightSQL is gRPC+TLS and stays on a dedicated port-forward:
  kubectl -n $NAMESPACE port-forward svc/$FS_SVC 31338:31338

watch Quack node pods:
  kubectl -n $NAMESPACE get pods -l managed-by=quack-on-demand -w

tear down:
  kind delete cluster --name $KIND_CLUSTER
EOM