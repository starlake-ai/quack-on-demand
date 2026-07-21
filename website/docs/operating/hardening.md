---
id: hardening
title: Security hardening
---

Quack on Demand runs tenant-supplied SQL on shared infrastructure. The defaults
are tuned for first-run smoke tests, not for exposing the gateway to untrusted
tenants. This page collects the isolation controls you turn on before that: node
lockdown, pod security, network policy, and catalog-reader bounding. All of them
are opt-in or defaulted-safe, and none change behavior for a single-tenant local
deployment.

Read this alongside [TLS](tls.md) (encrypt the FlightSQL edge),
[Authentication](authentication.md) (require credentials), and
[RBAC](rbac-model.md) (scope table access). Hardening assumes those are already
in place: it protects the node process and the network, not the front door.

## Threat model

The controls here address a tenant who authenticates legitimately and then sends
hostile SQL: reading node-local files, attaching arbitrary databases, installing
extensions, exhausting the control-plane database, or reaching the network from a
node pod. They do not replace a runtime sandbox. If you run genuinely untrusted
code and need a hard boundary, put each tenant in its own Kubernetes namespace
and consider a sandboxed runtime (gVisor, Kata); the controls below are
defense-in-depth layered under that, not a substitute for it.

## Node lockdown

Node lockdown stops tenant SQL from touching the machine the node runs on. It has
two layers behind one switch.

Enable it with the environment variable:

```bash
QOD_NODE_LOCKDOWN=true
```

(config key `quack-flightsql.nodeLockdown.enabled`, default `false`). One flag
drives both layers; the manager logs `node lockdown: enabled` at boot.

**Edge layer (statement screen).** Before a statement is routed, the edge denies
these for non-superuser sessions and audits them as denials:

- `ATTACH` / `DETACH` (no attaching arbitrary databases).
- `INSTALL` / `LOAD` (no pulling extensions at query time).
- `SET` / `RESET` / `PRAGMA` against protected settings (`disabled_filesystems`,
  the `allow_*_extensions` and `autoinstall/autoload` knobs,
  `enable_external_access`, `lock_configuration`, `temp_directory`,
  `extension_directory`, `secret_directory`, and the rest of the lockdown set).
  Benign session settings such as `SET memory_limit` and `SET threads` stay
  allowed.
- Local-file reads: `read_text`, `read_blob`, `read_csv`, `read_parquet`,
  `read_json` and friends, `glob`, `getenv`, plus `COPY ... FROM/TO '<path>'` and
  bare-path `FROM '<file>'` replacement scans. Object-store URL literals
  (`s3://`, `gs://`, `az://`, `r2://`, `http://`, `https://`) stay allowed, so
  reading remote data through `httpfs` still works; reading node-local paths does
  not.

Denied statements return a `lockdown: ...` error and appear in the
[audit log](../administration/audit-log.md). Superuser sessions bypass the edge
screen (operators use the REST surface, not raw node SQL).

**Engine layer (settings).** Before a node starts serving, Quack on Demand runs
a lockdown block that disables the settings a tenant could otherwise use to pull
in untrusted code: `SET autoinstall_known_extensions = false` (no fetching
extensions over the network), `SET allow_community_extensions = false`, and
`SET allow_unsigned_extensions = false`. When the tenant-db data path is an
object store, it also sets `SET disabled_filesystems = 'LocalFileSystem'`, so the
engine itself refuses every local-file read. These are the enforcement layer
underneath the edge screen: even a statement the edge parser does not recognize
cannot install or load untrusted code, and on object-store deployments cannot
read the local filesystem.

The block deliberately does not use `SET lock_configuration = true` and does not
disable `autoload_known_extensions`: the node's query server needs to autoload
its signed built-in extensions to handle connections, and freezing configuration
outright makes nodes unable to serve. It is not a gap: the edge screen already
denies every protected-setting `SET`/`RESET`/`PRAGMA` for tenant sessions, so a
tenant cannot re-enable any of the disabled values in the first place.

**Local vs object-store data paths.** With an **object-store** data path both
layers hold: the engine `disabled_filesystems` restriction blocks local-file
access outright. With a **local** data path the engine must keep the filesystem
enabled (the DuckLake data lives there), so the edge screen is the guard, and it
is best-effort: it covers the common
`read_*` / `COPY` / bare-path `FROM` forms, but exotic quoting (dollar-quoted or
identifier-quoted paths) can still reach a local file. **For any multi-tenant or
untrusted deployment, use an object-store data path** so the engine layer is the
hard guard.

Lockdown is a per-deployment switch, not per-tenant. Turn it on wherever tenants
are untrusted; leave it off for a trusted single-tenant install where local-file
reads and `ATTACH` are legitimate.

## Pod security context (Kubernetes)

On the Kubernetes backend, every spawned node pod gets a hardened security
context by default (no flag needed):

- **Pod:** `runAsNonRoot: true`, `runAsUser` / `fsGroup` = `1000`
  (env `QOD_K8S_RUN_AS_USER`), `seccompProfile: RuntimeDefault`.
- **Container:** `allowPrivilegeEscalation: false`, all Linux capabilities
  dropped, `readOnlyRootFilesystem: true`.
- **Writable temp:** `emptyDir` volumes at `/tmp` and `/duckdb-tmp` so the JVM
  and DuckDB still have scratch space despite the read-only root filesystem.

The kubelet refuses to start a pod that would run as root, so this is enforced,
not advisory. If you supply a [pod template](pools-cohorts.md), its own
`securityContext` fields win field-by-field: set only what you need to relax and
the rest of the hardened defaults still apply.

Change the UID fleet-wide with `QOD_K8S_RUN_AS_USER`. Note that all node pods
share this UID; per-tenant UIDs are not a v1 feature (put tenants in separate
namespaces if you need that separation).

The Helm chart applies an equivalent posture to the manager deployment; both are
values-overridable.

## Network policy (Kubernetes, opt-in)

The Helm chart ships two `NetworkPolicy` templates, off by default. Enable them:

```yaml
networkPolicy:
  enabled: true
```

When enabled you get default-deny ingress and egress with only the necessary
holes:

- **Node pods:** ingress only from manager pods on the node port range; egress to
  DNS, the control-plane Postgres, and the object store. A tenant node cannot
  reach the rest of the cluster or the internet beyond those.
- **Manager:** ingress on the REST/UI port (20900) and the FlightSQL edge (31338);
  egress to node pods, Postgres, DNS, and optionally SMTP.

Tune the reachable targets through the values block:

```yaml
networkPolicy:
  enabled: true
  nodePortRange: { from: 21900, to: 22500 }
  postgres: { cidr: "10.0.0.0/8", port: 5432 }   # empty cidr = any host on the port
  objectStore: { cidrs: [] }                     # empty = 443 anywhere; tighten in prod
  ingressFrom: []                                # manager ingress peers; empty = anywhere
  smtp: { enabled: false, cidr: "", port: 587 }
  extraEgress: []                                # extra egress rules for federation targets
```

An empty `cidr`/list means "allow on that port" (the safe default for a policy you
are turning on); set real CIDRs before calling it locked down. Add federation
egress targets through `extraEgress`.

The node-pod selector matches the `managed-by: quack-on-demand` label. If you
override the node pod label (`QOD_K8S_POD_LABEL`), update the policy selector to
match, or the node policy will not select any pods.

## Catalog-reader bounding

The manager caches one DuckLake catalog reader per browsed tenant-db, each holding
a small Postgres connection pool. Without bounds, a deployment with many
self-serve tenant-dbs could accumulate enough idle readers to exhaust the
control-plane database's connections.

Two controls keep that in check, both on by default:

- Each cached reader's pool holds **zero idle connections** (`minimumIdle 0`,
  60-second idle timeout, max 2), so an idle reader releases its Postgres
  connections on its own.
- A background sweep **evicts readers unused for 30 minutes**, closing their
  pools. A later browse simply rebuilds the reader.

Tune the cadence and threshold:

```bash
QOD_CATALOG_READER_SWEEP_MIN=10        # how often the sweep runs (minutes)
QOD_CATALOG_READER_IDLE_EVICT_MIN=30   # idle age before a reader is evicted (minutes)
```

The sweep runs on every replica (the cache is per-process), independently of the
[HA leader](resilience.md). No action is needed; the defaults suit most
deployments.

## Recommended posture for untrusted tenants

For a gateway exposed to tenants you do not fully trust:

- `QOD_NODE_LOCKDOWN=true`, with an **object-store data path** so both lockdown
  layers hold.
- `QOD_ACL_ENABLED=true` ([RBAC](rbac-model.md)) so table access is scoped.
- TLS on the edge ([TLS](tls.md)) and a real `QOD_API_KEY` /
  `QOD_SESSION_JWT_SECRET` ([Authentication](authentication.md)).
- On Kubernetes: `networkPolicy.enabled=true` with real CIDRs, the default pod
  security context left on, and one namespace per tenant if you need hard
  isolation.

Leave all of this off for a trusted single-tenant local install, where the
defaults keep first-run smoke tests frictionless.
