# WIP quack-on-demand Helm chart

Deploys the [quack-on-demand](https://github.com/starlake-ai/quack-on-demand) manager onto Kubernetes. The manager serves the admin REST/UI on `:20900` and the FlightSQL edge on `:31338`, and supervises Quack node pods in the same namespace via its built-in `KubernetesQuackBackend`.

## Prerequisites

- Kubernetes 1.25+
- Helm 3.12+
- An **external Postgres** reachable from the cluster. The manager and the spawned Quack nodes use it for the DuckLake catalog + the `qodstate_*` control-plane tables.

The chart does **not** bundle Postgres. Production deploys should point at a managed Postgres (RDS, Cloud SQL, AlloyDB, Azure Database for PostgreSQL). For local kind testing, see [`local-stack-k8s/`](local-stack-k8s/).

## Quick install

The chart is **not yet published to any registry** - install it from a checkout.

### Local kind cluster (recommended for first-run)

The bundled `run-local-stack-k8s.sh` script handles everything: creates a kind cluster, applies an in-cluster Postgres + SeaweedFS, then `helm install`s the chart wired against them. Requires `kind`, `kubectl`, `helm`, and `docker`.

```bash
git clone https://github.com/starlake-ai/quack-on-demand
cd quack-on-demand

./charts/quack-on-demand/local-stack-k8s/run-local-stack-k8s.sh
# NUKE=1            wipe the namespace before reinstalling
# LOAD_TPCH=1       seed TPC-H into acme/acme_tpch at SF=1
# LOAD_TPCDS=1      seed TPC-DS into globex/globex_tpcds at SF=1
# LOAD_TPC=1        legacy shortcut for both (LOAD_TPCH=1 LOAD_TPCDS=1)
# BUILD=1           rebuild the manager + Quack-node images from the tree
```

When the script returns, your `kubectl` context is `kind-qod-test`, the manager is reachable on the cluster IP, and `/health` returns OK. See [`local-stack-k8s/README.md`](local-stack-k8s/README.md) for the full env-var matrix.

### Existing cluster

Point your `kubectl` context at the cluster (and make sure `postgres.host` resolves from inside it), then install from this checkout:

```bash
helm install qod charts/quack-on-demand \
  --namespace qod --create-namespace \
  --set postgres.host=postgres.example.svc \
  --set postgres.existingSecret=qod-pg \
  --set admin.existingSecret=qod-admin
```

Or package it locally and host on your own OCI registry:

```bash
helm package charts/quack-on-demand -d /tmp
helm push /tmp/quack-on-demand-*.tgz oci://your-registry.example.com/charts
```

OCI publication to a public registry is a planned follow-up.

## What the chart creates

| Object | Purpose |
|---|---|
| `Deployment` | The manager pod. Default `replicas: 1` (see the Resilience guide at https://starlake-ai.github.io/quack-on-demand/operating/resilience and #11). |
| `Service` (REST) | ClusterIP on `:20900` for `/api`, `/ui`, `/metrics`. |
| `Service` (FlightSQL) | ClusterIP on `:31338` for the Arrow Flight gRPC edge. |
| `ServiceAccount` | Bound to the `Role` below. |
| `Role` + `RoleBinding` | Pods + services CRUD in the manager's own namespace. **Not a `ClusterRole`** - the manager only ever talks to its own namespace. |
| `ConfigMap` | `QOD_*` / `PROXY_*` env-var overrides - everything in `application.conf` that isn't a secret. |
| `Secret` | Chart-managed only when `postgres.password` / `admin.password` / `apiKey.value` are inline. Production deploys should use `existingSecret` references instead. |
| `Ingress` | Optional. HTTP for REST/UI. FlightSQL is gRPC and not handled here - front it with a Gateway / Envoy / Istio. |
| `ServiceMonitor` | Optional, for the Prometheus Operator. |
| `PodDisruptionBudget` | Optional, only created when `replicaCount > 1`. |

## Key values

See [`values.yaml`](values.yaml) for the full list. The most-used:

| Key | Default | Notes |
|---|---|---|
| `image.repository` | `starlakeai/quack-on-demand` | |
| `image.tag` | `""` (uses `Chart.AppVersion`) | |
| `replicaCount` | `1` | Keep at 1 until multi-manager HA lands (#11). |
| `postgres.host` | `""` (REQUIRED) | Set to the cluster-reachable Postgres host. |
| `postgres.existingSecret` | `""` | Recommended for prod. Inline `postgres.password` is dev-only. |
| `admin.existingSecret` | `""` | Recommended for prod. Inline `admin.password` is dev-only. |
| `apiKey.value` | `""` | Static `X-API-Key` for `/api/*`. Optional - UI login still works without it. |
| `flightsql.tls.enabled` | `true` | Manager auto-generates a self-signed cert at boot when no Secret is mounted. |
| `service.flightsql.type` | `ClusterIP` | Override to `LoadBalancer` / `NodePort` to expose externally. |
| `ingress.enabled` | `false` | REST/UI only. |
| `serviceMonitor.enabled` | `false` | Set true when you run Prometheus Operator. |
| `metrics.sink` | `prometheus` | One of `prometheus` \| `aws` \| `azure` \| `gcp` \| `none`. |
| `loadTpc.enabled` | `false` | Inject `QOD_BOOTSTRAP_YAML=classpath:bootstrap-demo.yaml` to seed the bundled acme + globex demo manifest on boot. |

## Quack node image

The chart's `quackNode.image` value points at the DuckDB Quack server image - a **different artifact** from the manager image, on a **separate release lifecycle**. The manager spawns one pod per Quack node and references this image in the pod spec.

The default is `starlakeai/quack-on-demand-node:latest-snapshot`, the moving alias the `quack-node-snapshot` GitHub Action publishes on every push to `main` that touches `docker/quack-node/**`. There is no pinned release yet (no `:0.x.y` tag); pin to a specific digest if you need reproducibility:

```bash
helm upgrade qod charts/quack-on-demand \
  --set quackNode.image=starlakeai/quack-on-demand-node@sha256:<digest>
```

Override only when you need to point at a private registry or a custom build:

```bash
helm upgrade qod charts/quack-on-demand \
  --set quackNode.image=registry.example.com/quack-on-demand-node:<tag>
```

## Manager ↔ Quack node TLS

The chart's default is **plain HTTP** on the manager↔node wire (`quackNode.tls.enabled: false`, which sets `QOD_NODE_DISABLE_SSL=true`). The auth token Quack ships on every request is still required and enforced; only the transport is unencrypted.

Why HTTP by default:
- Intra-namespace traffic. Anyone with pod-network access to your namespace has bigger problems than sniffing this.
- The FlightSQL edge between clients and the manager *does* carry TLS by default (`flightsql.tls.enabled: true`) - that's the surface that crosses trust boundaries.
- The spawn-quack-node.sh script doesn't yet plumb TLS certs into `quack_serve()`, so flipping the manager to TLS without first adding cert mounting would just break every health probe.

When you should turn it on (`--set quackNode.tls.enabled=true`):
- You run a service mesh (Istio / Linkerd) that injects mTLS between pods - the spawned nodes will be encrypted transparently. The manager already passes `disable_ssl=false` correctly in that case.
- You need the wire encrypted for compliance (SOC2 / HIPAA / PCI) and accept the follow-up work of cert plumbing into the spawn script.

Without one of those two, leave it off.

## Operational notes

- **Replicas** - keep at 1 until `roadmap:v0.4 #11` (Postgres advisory-lock leader election) lands. Two managers against the same Postgres will race on pod create/delete.
- **K8s API scope** - manager calls Pod + Service APIs in its own namespace only. Bound by a `Role`. If you need the manager to spawn nodes in a different namespace, override `QOD_K8S_NAMESPACE` and grant the equivalent `Role` there.
- **TLS** - leaving `flightsql.tls.existingSecret` empty makes the manager auto-generate a self-signed cert at boot. Fine for kind. For prod, mount a Secret containing your CA-signed cert chain + key (under any keys; reference them in `flightsql.tls.certKey` / `keyKey`).
- **Probes** - both liveness and readiness target `/health`. Readiness gates traffic until `PoolSupervisor.restore() + reconcile()` finish, so a rolling restart doesn't briefly 503.
- **terminationGracePeriodSeconds** - default 60 s. Gives in-flight FlightSQL statements a chance to finish before the JVM is killed.

## Uninstall

```bash
helm uninstall qod --namespace qod
# The Quack node pods the manager spawned outlive the manager. Clean up:
kubectl --namespace qod delete pods,services -l managed-by=quack-on-demand
```