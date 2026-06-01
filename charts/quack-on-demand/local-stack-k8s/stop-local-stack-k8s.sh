#!/usr/bin/env bash
#
# Tear down the local kind stack started by run-local-stack-k8s.sh.
#
# Default: delete the whole kind cluster. The kind cluster's filesystem is
# ephemeral - dropping the cluster takes Postgres data, the manager pod,
# every spawned Quack node pod, the helm release secret, and the loaded
# images with it. Fastest and most thorough.
#
# Softer modes via env vars:
#
#   KEEP_CLUSTER=1   leave the kind cluster running; just `helm uninstall`
#                    the release and delete the namespace. Use this when
#                    iterating on the chart and you want to redeploy onto
#                    the same cluster without paying the cluster-create
#                    cost again.
#
#   KEEP_NAMESPACE=1 (only meaningful with KEEP_CLUSTER=1) just `helm
#                    uninstall` the release; leave the namespace + the
#                    in-cluster Postgres pod alone. Useful when you want
#                    to reuse Postgres across reinstalls.
#
# Env:
#   KIND_CLUSTER     kind cluster name      (default qod-test)
#   NAMESPACE        install namespace      (default qod)
#   RELEASE          helm release name      (default qod)
#   KEEP_CLUSTER     "1" to keep the kind cluster running     (default 0)
#   KEEP_NAMESPACE   "1" to keep the namespace (impl. KEEP_CLUSTER=1)
#                                                              (default 0)
#
# Requires: kind, kubectl, helm.
#
# Usage:
#   ./charts/quack-on-demand/local-stack-k8s/stop-local-stack-k8s.sh                    # delete cluster
#   KEEP_CLUSTER=1 ./charts/quack-on-demand/local-stack-k8s/stop-local-stack-k8s.sh     # helm uninstall + delete ns
#   KEEP_CLUSTER=1 KEEP_NAMESPACE=1 ./charts/quack-on-demand/local-stack-k8s/stop-local-stack-k8s.sh  # helm uninstall only

set -euo pipefail

KIND_CLUSTER="${KIND_CLUSTER:-qod-test}"
NAMESPACE="${NAMESPACE:-qod}"
RELEASE="${RELEASE:-qod}"
KEEP_CLUSTER="${KEEP_CLUSTER:-0}"
KEEP_NAMESPACE="${KEEP_NAMESPACE:-0}"

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 not on PATH" >&2; exit 1; }; }
need kubectl
need helm
[[ "$KEEP_CLUSTER" == "1" ]] || need kind

# Best-effort: drop any port-forwards we (or run-local-stack-k8s.sh) left
# bound to the standard ports so the next start cycle isn't blocked.
pkill -f 'kubectl.*port-forward.*(20900|31338|5432|qod)' 2>/dev/null || true

CTX="kind-${KIND_CLUSTER}"

if [[ "$KEEP_CLUSTER" == "0" ]]; then
  # Fast path: bin the cluster. Nothing else needed.
  if kind get clusters 2>/dev/null | grep -q "^${KIND_CLUSTER}$"; then
    echo "deleting kind cluster '$KIND_CLUSTER'..."
    kind delete cluster --name "$KIND_CLUSTER"
  else
    echo "kind cluster '$KIND_CLUSTER' not present; nothing to do."
  fi
  exit 0
fi

# Soft path: keep the cluster, just unwind what the chart installed.

if ! kubectl config get-contexts -o name 2>/dev/null | grep -qx "$CTX"; then
  echo "kubectl context '$CTX' not found - the kind cluster may already be gone."
  exit 0
fi

# helm uninstall (idempotent: missing release returns non-zero but is fine).
if helm --kube-context "$CTX" -n "$NAMESPACE" status "$RELEASE" >/dev/null 2>&1; then
  echo "helm uninstall $RELEASE..."
  helm --kube-context "$CTX" -n "$NAMESPACE" uninstall "$RELEASE" 2>&1 | tail -3
else
  echo "helm release '$RELEASE' not found in namespace '$NAMESPACE'; skipping."
fi

# Force-delete any quack node pods + services the manager spawned. Without
# this, a subsequent re-install would 409 on the leftover pod names.
echo "cleaning orphan quack node pods + services..."
kubectl --context "$CTX" -n "$NAMESPACE" delete pods,services \
  -l managed-by=quack-on-demand --grace-period=0 --force --ignore-not-found 2>&1 | tail -5 || true

if [[ "$KEEP_NAMESPACE" == "1" ]]; then
  echo "KEEP_NAMESPACE=1: leaving namespace '$NAMESPACE' + Postgres pod in place."
  echo "  cluster:      kind-$KIND_CLUSTER"
  echo "  next run:     BUILD=0 ./charts/quack-on-demand/local-stack-k8s/run-local-stack-k8s.sh"
  exit 0
fi

echo "deleting namespace '$NAMESPACE'..."
kubectl --context "$CTX" delete namespace "$NAMESPACE" --ignore-not-found --wait=true --timeout=120s
echo
echo "kept kind cluster '$KIND_CLUSTER'. Next run:"
echo "  BUILD=0 ./charts/quack-on-demand/local-stack-k8s/run-local-stack-k8s.sh"