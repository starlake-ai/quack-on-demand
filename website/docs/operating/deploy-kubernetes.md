---
id: deploy-kubernetes
title: Kubernetes deployment
---

## When to use

Use the Kubernetes backend when you need multi-node, production-grade deployments. In this mode the manager process runs as a single pod inside your cluster and spawns Quack node pods on demand via the Kubernetes API. Each pool's nodes are independent pods, so they scale and fail independently of the manager.

For single-machine development and testing, use the local backend (`runtimeType=local`) instead.

## Configure the backend

Set `quack-on-demand.runtimeType=kubernetes` (or `QOD_RUNTIME_TYPE=kubernetes`) to activate the Kubernetes backend. All `k8s.*` keys are overridable via environment variables.

| Key | Env var | Default | Description |
|-----|---------|---------|-------------|
| `k8s.namespace` | `QOD_K8S_NAMESPACE` | `default` | Kubernetes namespace where node pods and services are created. |
| `k8s.image` | `QOD_K8S_IMAGE` | `starlakeai/quack-on-demand-node:latest-snapshot` | Container image used for Quack node pods. |
| `k8s.serviceAccount` | `QOD_K8S_SERVICE_ACCOUNT` | (unset) | Service account name injected into node pods. Omit to use the namespace default. |
| `k8s.serviceType` | `QOD_K8S_SERVICE_TYPE` | `ClusterIP` | Kubernetes Service type for per-node Services. |
| `k8s.quackPort` | `QOD_K8S_QUACK_PORT` | `8080` | Port the Quack HTTP server listens on inside each node pod. |
| `k8s.startupTimeoutSec` | `QOD_K8S_STARTUP_TIMEOUT_SEC` | `120` | Seconds the manager waits for a new node pod to reach Running phase before aborting. |
| `k8s.podLabel` | `QOD_K8S_POD_LABEL` | `managed-by=quack-on-demand` | Label (`key=value`) applied to every manager-owned pod and Service, used for discovery. |

See [/reference/configuration](/reference/configuration) for the full list of configuration keys.

## How nodes run

The manager creates one Pod and one Service per node. The Service selector pins to a single pod by `quack-node-id` so traffic never fans out across multiple pods.

Every pod receives the following labels:

| Label | Purpose |
|-------|---------|
| `managed-by` | Matches the `podLabel` value (default `quack-on-demand`); scopes discovery to manager-owned pods. |
| `quack-tenant` | Tenant identifier for the pool this node belongs to. |
| `quack-tenant-db` | Tenant-database identifier. |
| `quack-pool` | Pool name. |
| `quack-role` | Node role: `WriteOnly`, `ReadOnly`, or `Dual`. |
| `quack-node-id` | Unique node identifier; used as the pod name and the Service name. |
| `quack-max-concurrent` | Maximum concurrent queries this node accepts. |

On manager restart, the `discoverExisting` pass lists all pods matching the `podLabel` and reconstructs the in-memory node registry from these labels. The per-node bearer token survives the restart: the manager writes a Secret `qod-token-${nodeId}` alongside each pod and references it via `env.valueFrom.secretKeyRef`. `discoverExisting` reads the Secret back into the token cache, so adopted pods continue to serve queries without a forced pool respawn. The token Secret is deleted alongside the pod on stop.

## Federation Secret

When a pool's `extraSetupSql` is non-empty (i.e. federated sources are registered against the pool's tenant-db), the manager writes a per-pool Secret `qod-fedsql-${tenant}-${tenantDb}-${pool}` (tenant-db underscores hyphenized for RFC-1123) and injects it into every pod via `env.valueFrom.secretKeyRef` so `spawn-quack-node.sh` reads `$extraSetupSql` from the env. The bearer never appears in `kubectl describe pod` output. The Secret is garbage-collected when the last pod of the pool stops; rotation is "update the Secret, restart the pods" (kubelet does not re-inject env values into a running container).

## Object-store credentials

The manager forwards object-store credentials from its own environment into every spawned node pod. This mirrors what the local backend receives for free through process environment inheritance.

The following variables are forwarded when present on the manager pod:

**S3-compatible (AWS S3, MinIO, SeaweedFS, R2, etc.)**

| Variable | Purpose |
|----------|---------|
| `QOD_S3_ENDPOINT` | S3 endpoint URL. |
| `QOD_S3_ACCESS_KEY_ID` | Access key. |
| `QOD_S3_SECRET_ACCESS_KEY` | Secret key. |
| `QOD_S3_REGION` | Region name. |
| `QOD_S3_URL_STYLE` | `path` or `vhost`. |
| `QOD_S3_USE_SSL` | `true` or `false`. |

**Azure Blob Storage**

| Variable | Purpose |
|----------|---------|
| `QOD_AZURE_CONNECTION_STRING` | Azure Storage connection string. |

**Google Cloud Storage**

| Variable | Purpose |
|----------|---------|
| `QOD_GCS_KEY_ID` | GCS HMAC key ID. |
| `QOD_GCS_SECRET` | GCS HMAC secret. |

Variables that are already set explicitly in `defaultMetastore` (via pool or global configuration) are not overridden by forwarding.

## Helm chart

A Helm chart is provided at `charts/quack-on-demand/`. It deploys the manager pod, the two Services (REST/UI on port 20900 and FlightSQL on port 31338), and the RBAC Role + RoleBinding that lets the manager call the Kubernetes API to create, watch, and delete node pods and services within its own namespace.

Top-level keys in `values.yaml`:

| Key | Description |
|-----|-------------|
| `image` | Manager container image repository, tag, and pull policy. |
| `replicaCount` | Number of manager replicas. Keep at `1` until leader election is supported. |
| `nameOverride` / `fullnameOverride` | Override rendered resource names. |
| `serviceAccount` | Service account creation and annotations for the manager pod. |
| `rbac` | Controls creation of the Role and RoleBinding for Kubernetes API access. |
| `podAnnotations` / `podLabels` | Extra annotations and labels on the manager pod. |
| `podSecurityContext` / `securityContext` | Pod- and container-level security contexts. |
| `resources` | CPU and memory requests/limits for the manager container. Node pods are sized per pool via `pool/setResources`; see [Pools and cohorts](/operating/pools-cohorts). |
| `nodeSelector` / `tolerations` / `affinity` | Manager pod scheduling constraints. |
| `priorityClassName` | Priority class for the manager pod. |
| `terminationGracePeriodSeconds` | Grace period (default 60 s) to allow in-flight FlightSQL statements to finish. |
| `service` | Services for the REST/UI listener (`rest`) and the FlightSQL edge (`flightsql`), each with `type`, `port`, and `annotations`. |
| `ingress` | Ingress for the REST/UI; FlightSQL (gRPC) is not covered here. |
| `probes` | Liveness and readiness probe tuning. |
| `serviceMonitor` | Prometheus Operator ServiceMonitor toggle. |
| `pdb` | PodDisruptionBudget (only useful when `replicaCount > 1`). |
| `postgres` | External Postgres connection details and secret references. |
| `quackNode` | Image for the Quack node pods the manager spawns, and TLS toggle for node-to-manager traffic. |
| `storage` | `dataPath` for the DuckLake catalog parquet files. |
| `s3` | S3-compatible object-store credentials forwarded to node pods, with inline or existing-Secret options. |
| `admin` | Initial admin credentials seeded at startup. |
| `apiKey` | Optional static API key for the `/api/*` admin endpoints. |
| `flightsql` | FlightSQL TLS configuration (auto-generated cert by default). |
| `auth` | OIDC provider configuration (Keycloak off by default). |
| `metrics` | Metrics sink selection (`prometheus`, `aws`, `azure`, `gcp`, or `none`). |
| `bootstrap` | Idempotent startup bootstrap for a starter tenant and pool. |
| `env` | Arbitrary extra environment variables passed to the manager pod. |
| `extraVolumes` / `extraVolumeMounts` | Additional volumes and mounts (for cert secrets, custom config, etc.). |
| `imagePullSecrets` | Image pull secrets for private registries. |

## Node placement

Per-pool node placement using cohorts (nodeSelector and tolerations) is supported on the Kubernetes backend and is documented in the Pools guide.
