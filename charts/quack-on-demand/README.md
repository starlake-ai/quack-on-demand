# quack-on-demand Helm chart

Deploys the [quack-on-demand](https://github.com/starlake-ai/quack-on-demand) manager onto Kubernetes. The manager serves the admin REST/UI on `:20900` and the FlightSQL edge on `:31338`, and supervises Quack node pods in the same namespace via its built-in `KubernetesQuackBackend`.

## Prerequisites

- Kubernetes 1.25+
- Helm 3.12+
- An **external Postgres** reachable from the cluster. The manager and the spawned Quack nodes use it for the DuckLake catalog + the `qodstate_*` control-plane tables.

The chart does **not** bundle Postgres. Production deploys should point at a managed Postgres (RDS, Cloud SQL, AlloyDB, Azure Database for PostgreSQL). For local kind testing, see [`local-stack-k8s/`](local-stack-k8s/).

## Quick install

The chart is published as an OCI artifact on every release:

```bash
helm install qod oci://ghcr.io/starlake-ai/charts/quack-on-demand \
  --version <release, e.g. 0.8.1> \
  --set postgres.host=<your-postgres-host> \
  --set postgres.user=<a-non-superuser-role> \
  --set postgres.password=<your-postgres-password> \
  --set admin.password=<the-admin-password>
```

(Exact value keys: see [values.yaml](values.yaml); an external Postgres and an
admin password (`admin.password`, or `admin.existingSecret` in production) are
required, and the chart refuses to render without them.) You can also install
from a checkout:

### Local kind cluster (recommended for first-run)

The bundled `run-local-stack-k8s.sh` script handles everything: creates a kind cluster, applies an in-cluster Postgres + SeaweedFS, then `helm install`s the chart wired against them. Requires `kind`, `kubectl`, `helm`, and `docker`.

```bash
git clone https://github.com/starlake-ai/quack-on-demand
cd quack-on-demand

./charts/quack-on-demand/local-stack-k8s/run-local-stack-k8s.sh
# NUKE=1            wipe the namespace before reinstalling
# LOAD_TPCH=1       seed TPC-H into acme/acme_tpch at SF=1
# LOAD_TPCDS=1      seed TPC-DS into globex/globex_tpcds at SF=1
# LOAD_SSB=1        derive the SSB star schema into acme/acme_tpch schema ssb1 at SF=1
# LOAD_TPC=1        legacy shortcut for all three (LOAD_TPCH=1 LOAD_TPCDS=1 LOAD_SSB=1)
# BUILD=1           rebuild the manager + Quack-node images from the tree
```

When the script returns, your `kubectl` context is `kind-qod-test`, the manager is reachable on the cluster IP, and `/health` returns OK. See [`local-stack-k8s/README.md`](local-stack-k8s/README.md) for the full env-var matrix.

### Existing cluster

Point your `kubectl` context at the cluster (and make sure `postgres.host` resolves from inside it), then install from this checkout:

```bash
helm install qod charts/quack-on-demand \
  --namespace qod --create-namespace \
  --set postgres.host=postgres.example.svc \
  --set postgres.user=qod \
  --set postgres.existingSecret=qod-pg \
  --set admin.existingSecret=qod-admin
```

`qod-admin` is a Secret holding the admin password under the key `adminPassword`
(`admin.existingSecretKey`), for example
`kubectl -n qod create secret generic qod-admin --from-file=adminPassword=./admin-password.txt`.

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
| `Secret` | Chart-managed only when `postgres.password` / `admin.password` are inline. Production deploys should use `existingSecret` references instead. |
| `Ingress` | Optional. HTTP for REST/UI. FlightSQL is gRPC and not handled here - front it with a Gateway / Envoy / Istio. |
| `ServiceMonitor` | Optional, for the Prometheus Operator. |
| `PodDisruptionBudget` | Optional, only created when `replicaCount > 1`. |

## Key values

See [`values.yaml`](values.yaml) for the full list. The most-used:

| Key | Default | Notes |
|---|---|---|
| `image.repository` | `starlakeai/quack-on-demand` | |
| `image.tag` | `""` (uses `Chart.AppVersion`) | |
| `replicaCount` | `1` | Set to 2+ to enable HA mode (`QOD_HA_ENABLED`). Replicas share the session secret the control plane stores; `sessionJwtSecret` / `existingSessionJwtSecret` stay optional. See [guides/RESILIENCE.md](../../../guides/RESILIENCE.md). |
| `sessionJwtSecret` / `existingSessionJwtSecret` | `""` | Optional at any replica count (`QOD_SESSION_JWT_SECRET`). Empty: the first manager generates the secret and stores it in the control-plane database, and every restart and replica reads it. Set one only when another service must verify the sessions; a pre-existing Secret MUST carry the value under the key `sessionJwtSecret`. |
| `postgres.host` | `""` (REQUIRED) | Set to the cluster-reachable Postgres host. |
| `postgres.user` | `""` (REQUIRED) | A dedicated non-superuser role with `CREATEDB` that owns the control-plane database. See [Tenant isolation](#tenant-isolation). |
| `security.nodeLockdown` | `true` | `QOD_NODE_LOCKDOWN`. Keeps tenant users away from the node's mounted secrets. See [Tenant isolation](#tenant-isolation). |
| `security.aclEnabled` | `false` | `QOD_ACL_ENABLED`: enforce per-table role grants. |
| `postgres.existingSecret` | `""` | Recommended for prod. Inline `postgres.password` is dev-only. |
| `admin.password` / `admin.existingSecret` | `""` | REQUIRED, one of the two: seeds the superuser(s) on first boot, and nobody can log in without it. Inline `admin.password` is dev-only; in production reference a Secret holding the key `adminPassword`. |
| `flightsql.tls.enabled` | `true` | Manager auto-generates a self-signed cert at boot when no Secret is mounted. |
| `service.flightsql.type` | `ClusterIP` | Override to `LoadBalancer` / `NodePort` to expose externally. |
| `ingress.enabled` | `false` | REST/UI only. |
| `serviceMonitor.enabled` | `false` | Set true when you run Prometheus Operator. |
| `metrics.sink` | `prometheus` | One of `prometheus` \| `aws` \| `azure` \| `gcp` \| `none`. |
| `loadTpc.enabled` | `false` | Inject `QOD_BOOTSTRAP_YAML=classpath:bootstrap-demo.yaml` to seed the bundled acme + globex demo manifest on boot. |

## Choosing an auth provider

The chart configures three independent auth axes. All are optional; the
defaults give you database auth everywhere with the seeded admin user.

**1. Admin credentials and sessions** (always on): `admin.username` /
`admin.password` (or `admin.existingSecret`; one of the two is REQUIRED) seed the superuser on first boot only (an existing admin row is never rewritten, so rotating the Secret does not change the password: use `qod auth change-password`, or `qod admin reset-password` with database access; with the default two admin names (`admin@localhost.local,admin`) both share the seeded password, so rotate each with `qod auth change-password --username <name>`, while `qod admin reset-password` resets every configured admin name by default);
`sessionJwtSecret` signs UI sessions and is optional: left empty, the first manager generates the
secret and stores it in the control-plane database (`qodstate_boot_secret`), so sessions survive
restarts and every replica verifies them. Rotate the stored one with
`DELETE FROM qodstate_boot_secret WHERE name = 'session_jwt'` and a restart of every replica (every
session and password-reset link ends).
The chart sets no static API key and refuses a non-empty `apiKey` (an all-empty
block left in an older values file passes): people log in
(`qod login`, the UI), and automation (CI, MCP clients, SCIM connectors) uses
personal access tokens (`qod auth pat create`). Upgrading from a release that set
`apiKey`: mint those tokens first, point the automation at them, then delete
`apiKey` from your values.

**2. FlightSQL data plane** (what SQL clients authenticate against): database
auth (bcrypt rows in `qodstate_user`) is always in the chain. Layer a
Keycloak/OIDC provider on top with:

```yaml
auth:
  keycloak:
    enabled: true
    baseUrl: "http://keycloak:8080"   # in-cluster URL used for JWKS
    realm: "qod"
    clientId: "qod-flightsql"
    existingSecret: "my-kc-secret"    # key: keycloakClientSecret
    # issuer: ""  # set when the browser-facing issuer differs from baseUrl
```

Both providers authenticate the same session; the manager accepts the first
that validates the presented credentials.

**3. Admin UI login** (the `/ui/` console): `auth.management.identitySource`
selects `db` (the default username/password/tenant form) or `oidc` (pure SSO,
provider-agnostic via OIDC Discovery - works with Keycloak, Entra, Google,
Okta, ...):

```yaml
auth:
  management:
    identitySource: "oidc"
    publicBaseUrl: "https://qod.example.com"   # register <this>/api/auth/oidc/callback on the IdP
    oidc:
      issuerUrl: "https://idp.example.com/realms/qod"
      clientId: "qod-console"
      existingSecret: "my-mgmt-oidc-secret"    # key: mgmtOidcClientSecret
```

**Per-tenant SSO is not a chart value.** A tenant's own provider
(`authProvider: db | keycloak | google | azure | aws` plus its `authConfig`)
is set on the tenant at create/update time through the REST API, CLI, or UI,
and falls back to the manager-wide config above when unset. See
`https://docs.starlake.ai/qod/operating/auth-providers` for the full model.

## Quack node image

The chart's `quackNode.image` value points at the DuckDB Quack server image - a **different artifact** from the manager image. The manager spawns one pod per Quack node and references this image in the pod spec.

The default is `starlakeai/quack-on-demand-node:latest-snapshot`, the moving alias the `quack-node-snapshot` GitHub Action publishes on every push to `main` that touches `docker/quack-node/**`. Since 0.6.1 every release also publishes the node image in lockstep with the manager, so pin the release tag matching your manager version for reproducible deploys:

```bash
helm upgrade qod charts/quack-on-demand \
  --set quackNode.image=starlakeai/quack-on-demand-node:0.6.1
```

Override only when you need to point at a private registry or a custom build:

```bash
helm upgrade qod charts/quack-on-demand \
  --set quackNode.image=registry.example.com/quack-on-demand-node:<tag>
```

## Tenant isolation

Each node pod mounts its secrets read-only at `/var/run/qod-secrets` for its whole life: the DuckLake catalog's Postgres password, the encryption key of an encrypted database, the object-store keys and the federation SQL. A DuckLake database created without its own metastore credentials uses `postgres.user` / `postgres.password`, which are also the control plane's login. So whoever reads that file from a node can connect to the control-plane database (every tenant's password hashes, tokens and catalog passwords) and to every other tenant's catalog, including the per-file encryption keys; if `postgres.user` is a Postgres superuser, they also reach the database server itself (`COPY ... TO PROGRAM`, `pg_read_file`).

Two chart defaults bound that exposure:

- **`security.nodeLockdown: true`.** Non-superuser SQL cannot read local files (`read_text`, `read_csv`, `glob`...), ATTACH, INSTALL, LOAD or change protected settings, so a tenant user cannot read the mounted files. The manager's own default is off; the chart turns it on. A pool can opt out with `POST /api/pool/setLockdown`; its tenant users can then read the files, unless the SQL ACL (`security.aclEnabled: true`) is on, which also refuses file reads to non-superusers. With both off, every tenant user can read the credential.
- **`postgres.user` has no default.** Use a dedicated role that is not a Postgres superuser, for example `CREATE ROLE qod LOGIN CREATEDB PASSWORD '...'; CREATE DATABASE qod OWNER qod;`.

QoD superusers are never screened and can always read the files. Isolating each node to its own catalog needs one Postgres role per catalog database, which is not implemented yet.

## Manager ↔ Quack node TLS

The manager↔node wire is **plain HTTP**: the manager's native Quack client (libquackwire) always speaks `http://<node>/quack`, and the spawn-quack-node.sh script runs `quack_serve()` without TLS. The auth token Quack ships on every request is still required and enforced; only the transport is unencrypted.

Why that is acceptable:
- Intra-namespace traffic. Anyone with pod-network access to your namespace has bigger problems than sniffing this.
- The FlightSQL edge between clients and the manager *does* carry TLS by default (`flightsql.tls.enabled: true`) - that's the surface that crosses trust boundaries.

When you need the wire encrypted (compliance such as SOC2 / HIPAA / PCI), run a service mesh (Istio / Linkerd) that injects mTLS between pods: the spawned nodes are then encrypted transparently, with no chart setting involved. The former `quackNode.tls.enabled` value (which set `QOD_NODE_DISABLE_SSL`) only applied to the embedded fallback client, removed with the DuckDB 2.0 bump.

## Operational notes

- **Replicas** - default 1 (single manager, `Recreate` rollout). Set `replicaCount: 2` or more to enable active-active HA: all replicas serve REST + FlightSQL; one holds the Postgres advisory lock and runs singleton duties (reconcile, bootstrap). Replicas share the session secret stored in the control-plane database; `sessionJwtSecret` or `existingSessionJwtSecret` pins one instead (a pre-existing Secret MUST carry the value under the key `sessionJwtSecret`). See [guides/RESILIENCE.md](../../../guides/RESILIENCE.md). Each replica passes its pod IP (`QOD_POD_IP`, downward API) so its nodes call that replica back for verdicts; leave `env.QOD_INTERNAL_URL` unset (the chart refuses it with `replicaCount > 1`).
- **K8s API scope** - manager calls Pod + Service APIs in its own namespace only. Bound by a `Role`. If you need the manager to spawn nodes in a different namespace, override `QOD_K8S_NAMESPACE` and grant the equivalent `Role` there.
- **TLS** - leaving `flightsql.tls.existingSecret` empty makes the manager auto-generate a self-signed cert at boot. Fine for kind. For prod, mount a Secret containing your CA-signed cert chain + key (under any keys; reference them in `flightsql.tls.certKey` / `keyKey`).
- **Probes** - liveness targets `/health` (always 200 while the JVM is alive); readiness targets `/ready` (503 until Postgres is reachable). Readiness gates traffic until Postgres connectivity is confirmed, so a rolling restart doesn't briefly serve requests against an unavailable control plane.
- **drainTimeoutSec** (`QOD_DRAIN_TIMEOUT_SEC`) - default 60 s. Gives in-flight FlightSQL streams a chance to finish before the JVM is killed. `terminationGracePeriodSeconds` is derived as `drainTimeoutSec + 15` (default 75 s).

## Uninstall

```bash
helm uninstall qod --namespace qod
# The Quack node pods the manager spawned outlive the manager. Clean up:
kubectl --namespace qod delete pods,services -l managed-by=quack-on-demand
```