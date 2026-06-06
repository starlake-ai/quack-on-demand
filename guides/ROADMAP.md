# Roadmap

Public, living plan for `quack-on-demand`. Items are grouped by phase,
biased toward what unblocks real adopters next rather than nice-to-haves.
Each line corresponds to a tracked GitHub issue under the matching
`roadmap:vX.Y` label - claim one if you'd like to contribute.

This document is intentionally short. Concrete designs live in the
individual issues; ADRs (if any) go under `docs/adr/`.

## Status legend

- 🟢 done / merged
- 🟡 in progress
- ⬜ open / unstarted

---

## 0.1 - Foundation (current)

The initial public release. See [README](../README.md) for what ships today.

- 🟢 Multi-tenant pool + role-aware FlightSQL routing
- 🟢 Postgres-relational RBAC graph (`qodstate_user` / `qodstate_role` / `qodstate_role_permission` / `qodstate_group` + memberships + `qodstate_pool_permission`) + REST CRUD
- 🟢 Pluggable auth (DB / JWT / OIDC) via `AuthenticationService`
- 🟢 React admin console at `/ui/` (tenants, pools, nodes, users, catalog browser, Config viewer, Manifest import / export)
- 🟢 Three deploy paths (native, Docker, Docker Compose) + Kubernetes backend
- 🟢 Snapshot + release CI to Sonatype Central + Docker Hub (multi-arch)
- 🟢 Local release flow (`scripts/release.sh`, sbt-release pattern)

---

## 0.2 - Production essentials

Gaps that will hit any non-trivial deployment.

- ⬜ **DML ACL granularity** - extend `TableExtractor` so per-table grants apply to INSERT / UPDATE / DELETE, not just SELECT.
- ⬜ **Graceful JVM shutdown hook** - drain FlightSQL sessions, SIGTERM child Quack nodes; today the JVM sits idle through SIGTERM grace.
- 🟢 **Node-init race on `ducklake_metadata`** - wrapped DuckLake ATTACH in a per-dbname `pg_advisory_lock` so concurrent first-attach calls serialize. Applied in `spawn-quack-node.sh` (K8s pod path) and `DuckLakeInitializer.scala` (manager-side pre-init); regression in `DuckLakeInitializerRaceSpec`.
- 🟢 **Prometheus `/metrics` endpoint** - per-tenant QPS / latency / pool occupancy / node health, scrape endpoint on `:20900/metrics`; also supports `aws` / `azure` / `gcp` push sinks. See `observability/README.md`.
- 🟢 **Backup / restore (control plane)** - YAML manifest at `/api/manifest/{export,import}` (REST + UI button + CLI subcommand) captures every tenant, tenant-db, pool, user, role, group, permission, and pool grant; passwords are preserved across a re-import via a pre-truncate hash snapshot. Data-plane parquet relies on the object store's own versioning (S3 / GCS / Azure Blob), so it is the operator's responsibility.

## 0.3 - Ecosystem & adoption

Make adoption frictionless for the obvious downstream tools.

- ⬜ **JDBC driver shim** - thin Maven coordinate wrapping `arrow-flight-sql-jdbc-driver` with the right URL pattern + dev-friendly defaults.
- ⬜ **Spark connector example** - runnable `examples/spark/` that loads TPC-H via FlightSQL and runs a benchmark in CI.
- ⬜ **dbt-flightsql compatibility note** - verify end-to-end, contribute upstream fixes if needed.
- 🟢 **Helm chart** - `charts/quack-on-demand/`, lints clean, ships a kind-based smoke rig under `charts/quack-on-demand/local-stack-k8s/`. OCI publication is a follow-up.
- 🟢 **Grafana dashboard JSON** - `observability/grafana-dashboard.json`, auto-loaded by the `observability` compose profile (`PROFILES=observability ./scripts/run-docker-compose.sh`).

## 0.4 - Scale-out & HA

- ⬜ **Multi-manager HA** - leader election via Postgres advisory lock; lets you run N managers behind a load balancer with one active reconciler.
- ⬜ **Statement cancellation** - implement Flight `Cancel` and propagate to the spawned Quack node (DuckDB interrupt API).
- ⬜ **Per-tenant resource caps** - max concurrent statements, max memory, max nodes; enforced at the router before the request reaches a node.
- ⬜ **Pool autoscaling on K8s** - scale `KubernetesQuackBackend` based on EWMA latency / queue depth, not just static `RoleDistribution`.

## 1.0 - Stable surface

What we commit to long-term.

- ⬜ **API stability** - REST + FlightSQL + `qodstate_*` schema are SemVer-stable from 1.0.
- 🟢 **Migration tool** - Liquibase changelogs under `src/main/resources/db/changelog/` own forward + idempotent `qodstate_*` schema upgrades; applied automatically at manager boot via `LiquibaseRunner`.
- ⬜ **Security audit checklist** - TLS, secrets handling, ACL boundary tests, dependency CVE policy.

## 1.x - Differentiators

What makes `quack-on-demand` distinctive vs. plain DuckDB-as-a-service.

- 🟢 **DuckLake catalog browse** - catalog browser + click-to-expand table-schema cards. Snapshots / time-travel / schema-evolution diff / compaction scheduler tracked separately as #29.
- ⬜ **Statement-level federation** - push down to external Postgres / S3 / Iceberg via DuckDB extensions, gated by the same ACL.
- ⬜ **Tenant data residency** - per-tenant `DATA_PATH` (bucket per tenant, region-pinned).
- ⬜ **Audit log sink** - every statement + principal + ACL decision streamed to S3 / Kafka / etc.

---

## Cross-cutting (continuous, not phased)

- ⬜ **Security scanning in CI** - Trivy on the image, CodeQL on the source, Dependabot for dep bumps.
- ⬜ **Integration tests on arm64** - now that we have a native arm64 runner.
- ⬜ **Nightly cold-boot + load-test smoke** - catch regressions across the release/build matrix.
- ⬜ **Docs site** - docusaurus or mkdocs-material, hosted at `quack-on-demand.starlake.ai`.
- 🟢 **Community** - `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, issue templates under `.github/ISSUE_TEMPLATE/`, Discord linked from the README.

---

## How to contribute to a roadmap item

1. Find the issue with the matching `roadmap:vX.Y` label.
2. Comment to claim it (avoids two people doing the same work).
3. Open a PR that closes the issue with `Fixes #N`. Adjust this file in
   the same PR (move the line from ⬜ to 🟡 or 🟢).