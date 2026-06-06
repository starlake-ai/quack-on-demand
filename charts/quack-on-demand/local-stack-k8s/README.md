# WIP Local test rig (kind + Helm)

End-to-end smoke for the chart on a local [kind](https://kind.sigs.k8s.io/) cluster. The rig is intentionally self-contained — it does not require an external Postgres or a published image.

## Prerequisites

- `kind` 0.20+
- `kubectl`
- `helm` 3.12+
- `docker`
- `duckdb` CLI on `$PATH` (only required when seeding via `LOAD_TPCH`; otherwise skip)
- ~8 GB free RAM (the manager image + Quack node image + Postgres + SeaweedFS + kind nodes)

## One command

```bash
./charts/quack-on-demand/local-stack-k8s/run-local-stack-k8s.sh
```

This:

1. Creates a kind cluster named `qod-test` (reused if it already exists).
2. Resolves the manager + Quack-node images. With the default `BUILD=0` it reuses local `:local`-tagged images, pulling `starlakeai/quack-on-demand{,-node}:latest-snapshot` from Docker Hub and retagging as `:local` if absent. `BUILD=1` runs `docker build` for both from the source tree first.
3. Loads both images into the kind cluster.
4. Applies a minimal in-cluster Postgres ([`local-postgres.yaml`](local-postgres.yaml)), SeaweedFS ([`seaweedfs.yaml`](seaweedfs.yaml)), Prometheus ([`prometheus.yaml`](prometheus.yaml)) and Grafana ([`grafana.yaml`](grafana.yaml)) — one Pod + Service each, ephemeral `emptyDir` storage. The Grafana dashboard ConfigMap is rebuilt from [`observability/grafana-dashboard.json`](../../../observability/grafana-dashboard.json) so the repo file stays authoritative. **Not production-grade** — that's the point. The chart itself expects an external Postgres + S3-compatible store; these manifests exist only to satisfy the smoke.
5. `helm install`s the chart pointing at that Postgres for the DuckLake catalog + control plane, SeaweedFS for the parquet `s3://` data path, and the local image, with FlightSQL TLS on (auto-generated self-signed cert) and an inline admin password.
6. Waits for the manager pod to be `Ready` and `/health` to return OK.
7. Verifies the manager spawned the bootstrap Quack node pods (3 by default — one each of WriteOnly / ReadOnly / Dual).

## Architecture

### At a glance

```mermaid
flowchart LR
    host["Host"]
    manager["Manager"]
    nodes["Quack nodes"]
    pg["Postgres"]
    sw["SeaweedFS"]
    obs["Prometheus<br/>+ Grafana"]

    host    --> manager
    manager --> nodes
    manager --> pg
    nodes   --> pg
    nodes   --> sw
    obs     --> manager
    host    --> obs
```

### In detail

What the script ends up creating, end-to-end. Everything runs in the single `qod` namespace of the `qod-test` kind cluster; the host only opens short-lived port-forwards.

```mermaid
flowchart TB
    subgraph host["Host machine"]
        client["FlightSQL client<br/>(JDBC / ODBC / ADBC)"]
        browser["Browser<br/>(admin UI)"]
        loadtpch["scripts/load-tpch-dbgen.sh<br/>(host duckdb, only when LOAD_TPCH=N)"]
    end

    subgraph kind["kind cluster &quot;qod-test&quot; · namespace qod"]
        direction TB

        subgraph manager_block["Manager"]
            manager["Pod: qod-quack-on-demand<br/>REST :20900 · FlightSQL :31338<br/>(image: starlakeai/quack-on-demand:local)"]
            cfg["ConfigMap<br/>QOD_* / PROXY_* env"]
            sec["Secrets<br/>postgres / admin / [apiKey] / [s3]"]
        end

        nodes["Quack node pods<br/>quack-tpch-tpch1-sales-{1,2,3}<br/>(image: starlakeai/quack-on-demand-node:local)<br/>HTTP :8080 → quack_serve"]

        pg["Pod: postgres<br/>(single replica, emptyDir)<br/>databases: qod (control plane)<br/>· tpch_tpch1 (DuckLake catalog)"]
        sw["Pod: seaweedfs<br/>(single replica, emptyDir)<br/>S3 API :8333 · bucket qod-ducklake"]
        prom["Pod: prometheus<br/>(emptyDir, 7d retention)<br/>scrapes manager :20900/metrics"]
        graf["Pod: grafana<br/>(emptyDir, anonymous Admin)<br/>UI :3000 · provisioned dashboard"]

        kapi["Kubernetes API"]
    end

    %% Client / operator traffic via short-lived port-forwards
    client    -- "kubectl port-forward&nbsp;svc/qod-quack-on-demand-flightsql 31338" --> manager
    browser   -- "kubectl port-forward&nbsp;svc/qod-quack-on-demand 20900"          --> manager
    loadtpch  -- "kubectl port-forward&nbsp;svc/postgres 15432"                     --> pg
    loadtpch  -- "kubectl port-forward&nbsp;svc/seaweedfs 18333"                    --> sw

    %% Config + secret injection
    cfg -. envFrom .-> manager
    sec -. envFrom .-> manager

    %% Manager - K8s control plane
    manager  -- "create / delete Pods + Services (Role-scoped)" --> kapi
    kapi -.-> nodes

    %% Manager - control plane DB
    manager -- "qodstate_* (tenants / pools / users / RBAC)" --> pg

    %% Statement routing
    manager -- "HTTP /quack to selected node" --> nodes

    %% Node - data plane
    nodes -- "DuckLake __ducklake_* (per-tenant-db catalog)" --> pg
    nodes -- "parquet (s3://qod-ducklake/qod-test/...)"      --> sw

    %% Observability
    prom -- "GET /metrics every 5s" --> manager
    graf -- "PromQL" --> prom
```

The pieces, one per row of the diagram:

- **Host port-forwards** are the only way anything outside the cluster talks to the stack. `run-local-stack-k8s.sh` prints the exact commands at the end.
- **Postgres** holds both the manager's `qodstate_*` control plane and each tenant-db's `__ducklake_*` catalog tables. They live in separate Postgres databases (`qod` vs. `${tenant}_${tenantDb}`) so the control plane never collides with DuckLake metadata.
- **SeaweedFS** speaks the S3 API and stores the parquet that DuckLake's catalog references. The chart wires `storage.dataPath=s3://qod-ducklake/qod-test`; both the manager and every Quack node use the same URL.
- **Manager** talks to the K8s API via a `Role` (not a `ClusterRole`) scoped to its own namespace, so it can spawn / delete Pods + Services for Quack nodes without touching anything else in the cluster.
- **Quack node pods** are spawned by the manager (not by Helm). They listen on `:8080`, attach the per-tenant-db DuckLake catalog to their local DuckDB, and serve the manager's per-statement `/quack` HTTP requests.

## Env knobs

| Var | Default | Purpose |
|---|---|---|
| `KIND_CLUSTER` | `qod-test` | kind cluster name |
| `IMAGE` | `quack-on-demand:local` | manager image ref |
| `NAMESPACE` | `qod` | install namespace |
| `RELEASE` | `qod` | helm release name |
| `BUILD` | `0` | `0` reuses local `:local`-tagged images (falling back to `:latest-snapshot` from Docker Hub if absent); `1` runs `docker build` first. Same convention as `scripts/run-jar.sh`. |
| `NUKE` | `0` | `1` deletes the namespace before reinstalling — wipes the Postgres `emptyDir`, the helm release, and every Quack node pod. Mirrors `NUKE` in `scripts/run-jar.sh`. |
| `LOAD_TPCH` | unset | TPC-H seed. Unset = skip; positive integer = scale factor (`LOAD_TPCH=1` ≈ 6M lineitem rows, `LOAD_TPCH=10` ≈ 60M). Seeds TPC-H into the in-cluster Postgres + SeaweedFS before the manager boots — same `scripts/load-tpch-dbgen.sh` flow `run-jar.sh` uses. Requires `duckdb` on the host. |

```bash
# Fresh boot from a clean Postgres + TPC-H SF=1 seeded:
NUKE=1 LOAD_TPCH=1 ./charts/quack-on-demand/local-stack-k8s/run-local-stack-k8s.sh

# Just nuke and reinstall without seeding:
NUKE=1 ./charts/quack-on-demand/local-stack-k8s/run-local-stack-k8s.sh

# Force a fresh docker build (default is BUILD=0 = reuse local / pull from Hub):
BUILD=1 ./charts/quack-on-demand/local-stack-k8s/run-local-stack-k8s.sh
```

The script also auto-cleans orphan `managed-by=quack-on-demand` pods + services from a prior failed bootstrap before installing — so reruns without `NUKE=1` still recover cleanly (the manager's bootstrap would otherwise 409 on the leftover pod name).

## Verify by hand

After the script reports `smoke OK` (the script prints the exact port-forward commands at the end). The full names that Helm renders for the default release/chart combo are:

```bash
# Port-forward the admin UI (REST + UI on :20900)
kubectl -n qod port-forward svc/qod-quack-on-demand 20900:20900
# Open http://localhost:20900/ui/  (login admin / admin)

# Port-forward FlightSQL (gRPC+TLS on :31338, manager-issued self-signed cert)
kubectl -n qod port-forward svc/qod-quack-on-demand-flightsql 31338:31338
# JDBC: jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&user=admin&password=admin&tenant=tpch&pool=sales

# Watch the quack node pods the manager spawns
kubectl -n qod get pods -l managed-by=quack-on-demand -w

# Observability (Grafana opens anonymously as Admin, dashboard pre-loaded)
kubectl -n qod port-forward svc/prometheus 9090:9090
kubectl -n qod port-forward svc/grafana    3000:3000
# Open http://localhost:3000/ -> Dashboards -> "Quack on Demand"
```

Note: Helm prepends the chart name (`quack-on-demand`) to the release name (`qod`) unless the release name already contains it. So services land at `qod-quack-on-demand`, not `qod`. The script's tail message resolves this from the cluster so its copy-paste lines always match what's actually there.

## Connect from a client

Hold a port-forward open in another terminal while you connect:

```bash
kubectl -n qod port-forward svc/qod-quack-on-demand-flightsql 31338:31338
```

Then pick a client.

**JDBC (Arrow Flight SQL driver, e.g. DBeaver / DataGrip / your JVM app):**

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true
  &disableCertificateVerification=true
  &user=admin&password=admin
  &tenant=tpch&pool=sales
```

(All on one line. `disableCertificateVerification=true` is required because the smoke rig uses the manager's self-signed cert — see Known limitations.)

**Python (ADBC FlightSQL driver):**

```python
import adbc_driver_flightsql.dbapi as flight_sql
conn = flight_sql.connect(
    "grpc+tls://localhost:31338",
    db_kwargs={
        "username":                                            "admin",
        "password":                                            "admin",
        "adbc.flight.sql.client_option.tls_skip_verify":       "true",
        "adbc.flight.sql.rpc.call_header.tenant":              "tpch",
        "adbc.flight.sql.rpc.call_header.pool":                "sales",
    },
)
cur = conn.cursor()
cur.execute("SELECT count(*) FROM tpch1.lineitem")
print(cur.fetchall())
```

`pip install adbc_driver_flightsql adbc_driver_manager pyarrow` if you haven't already.

**REST API + admin UI:**

```bash
kubectl -n qod port-forward svc/qod-quack-on-demand 20900:20900
# Open http://localhost:20900/ui/  (login admin / admin)
curl -s -u admin:admin http://localhost:20900/api/tenants
```

The `tenant` / `pool` headers are mandatory on FlightSQL — the edge's tenant selector rejects sessions that don't carry them. Defaults shipped by the chart are `tpch` / `sales`; if you changed `bootstrap.tenant` / `bootstrap.pool`, match those.

## Run a load test

[`scripts/loadtest/loadtest.py`](../../../scripts/loadtest/loadtest.py) is a small ADBC FlightSQL load tester that cycles a TPC-H query mix across N worker threads. It works against the kind rig over the same port-forward you'd use for an interactive client.

```bash
# In one terminal:
kubectl -n qod port-forward svc/qod-quack-on-demand-flightsql 31338:31338

# In another -- defaults (8 workers x 100 iterations, TPC-H mix on schema tpch1):
./scripts/loadtest/loadtest.py

# Heavier mix:
./scripts/loadtest/loadtest.py --workers 24 --iterations 50 --warmup 5

# Single query, no warmup, count-only:
LT_QUERY='SELECT count(*) FROM tpch1.lineitem' \
  ./scripts/loadtest/loadtest.py --iterations 200 --warmup 0
```

Defaults the script picks up via env vars (override on the CLI as shown):

| Var | Default | What it sets |
|---|---|---|
| `LT_URL` | `grpc+tls://localhost:31338` | ADBC FlightSQL URL |
| `LT_USER` / `LT_PASSWORD` | `admin` / `admin` | basic auth |
| `LT_WORKERS` | `8` | concurrent threads |
| `LT_ITERATIONS` | `100` | queries per worker |
| `LT_WARMUP` | `5` | throwaway iterations per worker before stats start |
| `LT_QUERY` | unset | single SQL to repeat instead of the TPC-H mix |
| `LT_SCHEMA` | `tpch1` | schema prefix on the default mix |
| `LT_TENANT` / `LT_POOL` | `tpch` / `sales` | FlightSQL routing headers |
| `LT_INSECURE` | `true` | skip TLS cert verification (required against the kind rig's self-signed cert) |

The script auto-`pip install`s `adbc_driver_flightsql adbc_driver_manager pyarrow` on first run if they're missing. Output is a per-percentile latency table (p50 / p90 / p99 / max) plus throughput and error count; eyeball Grafana's "Quack on Demand" dashboard at `http://localhost:3000/` in parallel to see manager-side counters move.

## Tear down

```bash
./charts/quack-on-demand/local-stack-k8s/stop-local-stack-k8s.sh
```

## Known limitations

- Postgres data is ephemeral (`emptyDir`). Recreating the cluster wipes all state — that's a feature for a smoke rig.
- By default (`BUILD=0`) the script reuses the local `:local`-tagged images, pulling `:latest-snapshot` from Docker Hub if they're absent. Set `BUILD=1` to rebuild from the local Dockerfile (cached layers make reruns fast once JDK + sbt deps are cached).
- FlightSQL TLS is on with a manager-issued self-signed cert. Clients must skip cert verification (`useEncryption=true&disableCertificateVerification=true` for JDBC, `LT_INSECURE=true` for `loadtest.py`). Production deploys should mount a CA-signed cert via the chart's `flightsql.tls.existingSecret` value (which sets the manager's `PROXY_TLS_CERT_CHAIN` / `PROXY_TLS_PRIVATE_KEY` env vars to the mounted chain + key paths).