# Observability

## 1. Overview

This directory documents the observability surface of the Quack-on-Demand manager process. Metrics are collected via Micrometer and routed to exactly one sink per process. The active sink is selected by the `quack-on-demand.metrics.sink` key in `application.conf`, overridable at runtime with `SL_QUACK_METRICS_SINK`. Supported values are `prometheus`, `aws`, `azure`, `gcp`, and `none`.

## 2. Prometheus pull (`sink = "prometheus"`)

Prometheus is the default sink. When active, the manager exposes a standard scrape endpoint with no authentication (same policy as `/health`):

```
GET http://<host>:20900/metrics
```

Sample Prometheus scrape configuration:

```yaml
scrape_configs:
  - job_name: quack-on-demand
    static_configs:
      - targets: ['quack-manager.svc:20900']
    metrics_path: /metrics
```

### 2.1 Local stack (Prometheus + Grafana via docker compose)

For testing on a laptop, this directory ships a tiny compose stack that
scrapes the manager running on the host and serves a Grafana with the
dashboard auto-loaded.

```bash
# 1. Start the manager on the host (Prometheus sink is the default).
./scripts/run-jar.sh                       # or NUKE=1 LOAD_TPCH=1 ./scripts/run-jar.sh

# 2. Bring up Prometheus + Grafana.
docker compose -f observability/docker-compose.yml up -d

# 3. Open the UIs.
# Prometheus:    http://localhost:9090           (try query: up)
# Grafana:       http://localhost:3000           (anonymous admin; no login)
#                Dashboard: "Quack-on-Demand — Operator Overview"
```

Both containers reach the host's `:20900` via `host.docker.internal`,
which resolves automatically on Docker Desktop and is mapped through
`extra_hosts` for Linux. Grafana is preprovisioned with the Prometheus
datasource (UID `prometheus-local`) so the bundled dashboard renders
without manual selection.

```bash
# Stop the stack (keeps data volumes; metrics history is preserved).
docker compose -f observability/docker-compose.yml down

# Wipe data volumes too (fresh start).
docker compose -f observability/docker-compose.yml down -v
```

## 3. Cloud push (`sink = "aws" | "azure" | "gcp"`)

When a cloud sink is selected the manager pushes metrics on a fixed cadence (default 60 s) using the respective cloud SDK. The `/metrics` Prometheus endpoint is **not** exposed in cloud-push mode.

| Sink | Set | Required config | Credential source |
|---|---|---|---|
| `aws` | `SL_QUACK_METRICS_SINK=aws` | `SL_QUACK_METRICS_AWS_NAMESPACE` (default `quack-on-demand`) | `DefaultCredentialsProvider` chain — IAM role, env, profile |
| `azure` | `SL_QUACK_METRICS_SINK=azure` | `SL_QUACK_METRICS_AZURE_KEY` (Application Insights instrumentation key — **REQUIRED**) | `DefaultAzureCredential` — managed identity, env, CLI |
| `gcp` | `SL_QUACK_METRICS_SINK=gcp` | `SL_QUACK_METRICS_GCP_PROJECT_ID` (**REQUIRED**) | ADC — `GOOGLE_APPLICATION_CREDENTIALS`, GCE metadata, gcloud |

Push cadence defaults to 60 s per backend. Override with:

- `SL_QUACK_METRICS_AWS_STEP_SEC`
- `SL_QUACK_METRICS_AZURE_STEP_SEC`
- `SL_QUACK_METRICS_GCP_STEP_SEC`

**Only ONE sink runs per process.** If `metrics.sink = "aws"`, the `/metrics` Prometheus endpoint is **not** exposed and no other cloud sink is active. There are no per-backend `enabled` flags; the single `sink` field is the sole selector.

## 4. Disabling metrics

Set `metrics.sink = "none"` (or `SL_QUACK_METRICS_SINK=none`). No `/metrics` endpoint is mounted; no cloud push occurs; all counters, timers, and gauges registered against the manager become no-ops.

## 5. CloudWatch cost note

CloudWatch charges approximately $0.30 per custom metric per month. A worst-case deployment with 50 tenants × 5 pools × ~5 nodes × 3 roles can produce 5 000–10 000 active series, costing roughly **$2 000/month**. Before enabling `sink = "aws"` in production:

- Drop high-cardinality labels such as `node_id` from non-essential panels.
- Aggregate at the pool or tenant level rather than per-node where possible.
- Consider keeping Prometheus as the sink and scraping into a managed Prometheus (Amazon Managed Service for Prometheus, Google Managed Prometheus) to avoid per-series CloudWatch costs.

## 6. Common labels

Set the following environment variables to attach static labels to every emitted series. These are useful for distinguishing environments in a shared Grafana instance (e.g. `prod-eu`, `prod-us`, `staging`):

| Variable | HOCON key | Purpose |
|---|---|---|
| `SL_QUACK_METRICS_DEPLOYMENT` | `metrics.commonTags.deployment` | Deployment name, e.g. `prod-eu` |
| `SL_QUACK_METRICS_REGION` | `metrics.commonTags.region` | Cloud region, e.g. `eu-west-1` |

Example:

```bash
export SL_QUACK_METRICS_DEPLOYMENT=prod-eu
export SL_QUACK_METRICS_REGION=eu-west-1
```

## 7. Grafana dashboard

`grafana-dashboard.json` is a single-screen operator overview covering all key signals. It was validated with `python3 -m json.tool` and is ready to import.

**Import procedure:**

1. In Grafana 10.x, navigate to **Dashboards → New → Import**.
2. Click **Upload JSON file** and select `docs/observability/grafana-dashboard.json`.
3. At the datasource prompt, select your Prometheus datasource. The `${datasource}` variable in the JSON resolves to its UID automatically.
4. Click **Import**.

**Dashboard layout:**

| Row | Panels |
|---|---|
| Overview | Total QPS, Error Rate, Active Sessions, Total Nodes |
| Latency | p50 / p95 / p99 statement duration percentiles |
| By Tenant | Stacked QPS per tenant, Outcomes by status |
| Pool Occupancy | Node count bar chart by tenant / pool / role |
| Node Health | Table: healthy, draining, in-flight, EWMA latency per node |
| JVM | Heap used, GC pause rate, live threads, process uptime |

**Expected metric names** (all registered by the manager):

- `statements_total` — counter, labels: `tenant`, `pool`, `status`
- `statement_duration_seconds` — histogram, labels: `tenant`, `pool`
- `node_healthy` — gauge, labels: `tenant`, `pool`, `node_id`, `role`
- `node_draining` — gauge, labels: `tenant`, `pool`, `node_id`, `role`
- `node_in_flight` — gauge, labels: `tenant`, `pool`, `node_id`, `role`
- `node_ewma_latency_seconds` — gauge, labels: `tenant`, `pool`, `node_id`, `role`
- `pool_nodes` — gauge, labels: `tenant`, `pool`, `role`
- `flightsql_sessions_active` — gauge
- `jvm_memory_used_bytes` — gauge (Micrometer JVM binder)
- `jvm_gc_pause_seconds_sum` — counter (Micrometer JVM binder)
- `jvm_threads_live_threads` — gauge (Micrometer JVM binder)
- `process_uptime_seconds` — gauge (Micrometer process binder)