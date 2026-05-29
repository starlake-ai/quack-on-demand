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
- 🟢 Postgres-relational ACL (`slkstate_acl_grant`) + REST CRUD
- 🟢 Pluggable auth (DB / JWT / OIDC) via `AuthenticationService`
- 🟢 React admin console at `/ui/`
- 🟢 Three deploy paths (native, Docker, Docker Compose) + Kubernetes backend
- 🟢 Snapshot + release CI to Sonatype Central + Docker Hub (multi-arch)
- 🟢 Local release flow (`scripts/release.sh`, sbt-release pattern)

---

## 0.2 - Production essentials

Gaps that will hit any non-trivial deployment.

- ⬜ **DML ACL granularity** - extend `TableExtractor` so per-table grants apply to INSERT / UPDATE / DELETE, not just SELECT.
- ⬜ **Graceful JVM shutdown hook** - drain FlightSQL sessions, SIGTERM child Quack nodes; today the JVM sits idle through SIGTERM grace.
- ⬜ **Node-init race on `ducklake_metadata`** - three nodes booting in parallel race the `CREATE TABLE`; serialize or wrap with `IF NOT EXISTS` + retry.
- ⬜ **Prometheus `/metrics` endpoint** - per-tenant QPS / latency / pool occupancy / node health, so operators can scrape.
- ⬜ **Backup / restore playbook** - document and verify point-in-time snapshot of `pgdata` + `ducklake/*` on real data.

## 0.3 - Ecosystem & adoption

Make adoption frictionless for the obvious downstream tools.

- ⬜ **JDBC driver shim** - thin Maven coordinate wrapping `arrow-flight-sql-jdbc-driver` with the right URL pattern + dev-friendly defaults.
- ⬜ **Spark connector example** - runnable `examples/spark/` that loads TPC-H via FlightSQL and runs a benchmark in CI.
- ⬜ **dbt-flightsql compatibility note** - verify end-to-end, contribute upstream fixes if needed.
- ⬜ **Helm chart** - publishable chart so K8s adopters can install via `helm install`. `KubernetesQuackBackend` already exists in code.
- ⬜ **Grafana dashboard JSON** - ships alongside the metrics endpoint from 0.2.

## 0.4 - Scale-out & HA

- ⬜ **Multi-manager HA** - leader election via Postgres advisory lock; lets you run N managers behind a load balancer with one active reconciler.
- ⬜ **Statement cancellation** - implement Flight `Cancel` and propagate to the spawned Quack node (DuckDB interrupt API).
- ⬜ **Per-tenant resource caps** - max concurrent statements, max memory, max nodes; enforced at the router before the request reaches a node.
- ⬜ **Pool autoscaling on K8s** - scale `KubernetesQuackBackend` based on EWMA latency / queue depth, not just static `RoleDistribution`.

## 1.0 - Stable surface

What we commit to long-term.

- ⬜ **API stability** - REST + FlightSQL + `slkstate_*` schema are SemVer-stable from 1.0.
- ⬜ **Migration tool** - automated `slkstate_*` schema upgrades between versions (a version table + ordered migrations).
- ⬜ **Security audit checklist** - TLS, secrets handling, ACL boundary tests, dependency CVE policy.

## 1.x - Differentiators

What makes `quack-on-demand` distinctive vs. plain DuckDB-as-a-service.

- ⬜ **DuckLake catalog UI** - snapshots / time-travel browser, schema evolution, compaction scheduler.
- ⬜ **Statement-level federation** - push down to external Postgres / S3 / Iceberg via DuckDB extensions, gated by the same ACL.
- ⬜ **Tenant data residency** - per-tenant `DATA_PATH` (bucket per tenant, region-pinned).
- ⬜ **Audit log sink** - every statement + principal + ACL decision streamed to S3 / Kafka / etc.

---

## Cross-cutting (continuous, not phased)

- ⬜ **Security scanning in CI** - Trivy on the image, CodeQL on the source, Dependabot for dep bumps.
- ⬜ **Integration tests on arm64** - now that we have a native arm64 runner.
- ⬜ **Nightly cold-boot + load-test smoke** - catch regressions across the release/build matrix.
- ⬜ **Docs site** - docusaurus or mkdocs-material, hosted at `quack-on-demand.starlake.ai`.
- ⬜ **Community** - `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, issue templates, discussion forum.

---

## How to contribute to a roadmap item

1. Find the issue with the matching `roadmap:vX.Y` label.
2. Comment to claim it (avoids two people doing the same work).
3. Open a PR that closes the issue with `Fixes #N`. Adjust this file in
   the same PR (move the line from ⬜ to 🟡 or 🟢).