---
id: demo
title: Demo bootstrap (LOAD_TPCH / LOAD_TPCDS)
---

`LOAD_TPCH=N`, `LOAD_TPCDS=N`, and `LOAD_SSB=N` turn a fresh install into a self-contained, fully populated multi-tenant demo. One command, the tenants you ask for, real datasets, a complete RBAC graph, and a cross-tenant federated catalog, all reproducible and all live against a real Postgres + DuckDB stack.

```bash
# native, both TPC benchmarks at SF=1
NUKE=1 LOAD_TPCH=1 LOAD_TPCDS=1 ./scripts/run-jar.sh

# native, TPC-H only (fast path, ~10 s)
NUKE=1 LOAD_TPCH=1 ./scripts/run-jar.sh

# native, TPC-H + the SSB star schema derived from it
NUKE=1 LOAD_TPCH=1 LOAD_SSB=1 ./scripts/run-jar.sh

# docker compose, TPC-DS only at SF=10
QOD_VERSION=BUILD NUKE=1 LOAD_TPCDS=10 ./scripts/run-docker-compose.sh

# kubernetes (local stack), both TPC benchmarks at independent scale factors
NUKE=1 LOAD_TPCH=1 LOAD_TPCDS=10 ./charts/quack-on-demand/local-stack-k8s/run-local-stack-k8s.sh
```

Each `N` is that benchmark's scale factor. SF=1 is the fast path (~10 s of TPC-H + ~30 s of TPC-DS); SF=10 is ~5-10 minutes; SF=100+ takes hours and spills to disk. The legacy `LOAD_TPC=N` env var still works as a shortcut for setting `LOAD_TPCH=N`, `LOAD_TPCDS=N`, and `LOAD_SSB=N` at the same SF; explicit per-benchmark vars override it.

## Profiles: full and minimal

`DEMO` selects which bundled manifest a seed boot imports. `DEMO=full` (the default) is the two-tenant demo documented on the rest of this page: it exercises multi-tenancy, multiple pools, and the cross-tenant federated catalog. `DEMO=minimal` imports `bootstrap-demo-minimal.yaml` instead - the shape for running the gateway in front of a **single DuckDB/DuckLake database**: one tenant (`acme`), one pool (`bi`), and one dual node serving both reads and writes, plus the analyst RLS/CLS demo (roles `analyst` and `tenant_admin`, group `analysts`, users `root`, `admin`, `alice`, and `acme-admin`; the `analyst` role masks `customer.c_phone` and restricts `customer` rows to `c_mktsegment = 'BUILDING'`).

The profile is only consulted when a `LOAD_*` flag is set and `QOD_BOOTSTRAP_YAML` is unset. Bootstrap only imports into a fresh control plane, so switch profiles with `NUKE=1`:

```bash
NUKE=1 DEMO=minimal LOAD_TPCH=1 ./scripts/run-jar.sh
```

`DEMO=minimal` plus `LOAD_TPCDS` warns and skips the TPC-DS loader (this profile has no `globex` tenant).

## What gets created

| Component | Where it comes from |
|---|---|
| Bundled manifest at `src/main/resources/bootstrap-demo.yaml` | Loaded into the JVM classpath; imported on boot via `DemoBootstrapHook` when `QOD_BOOTSTRAP_YAML=classpath:bootstrap-demo.yaml` is set (the launcher script sets this automatically whenever any seed flag is non-empty). |
| `acme_tpch` Postgres database, seeded with TPC-H | `scripts/load-tpch-dbgen.sh` forked by the launcher; runs DuckDB's `dbgen(sf=N)` and copies the 8 TPC-H tables into the DuckLake catalog. |
| `globex_tpcds` Postgres database, seeded with TPC-DS | `scripts/load-tpcds-dbgen.sh` forked by the launcher; runs DuckDB's `dsdgen(sf=N)` and copies the 24 TPC-DS tables. |
| Schema `ssb1` in `acme_tpch`, seeded with the SSB star schema (`LOAD_SSB=N`) | `scripts/load-ssb-dbgen.sh` forked by the launcher; runs DuckDB's `dbgen(sf=N)` and derives the 5 SSB tables (`lineorder`, `customer`, `supplier`, `part`, `dwdate`) per the SSB spec's TPC-H mapping. No extra tenant or pool: the acme pools serve it, and the demo `tenant_admin` grant (`*.*.* ALL`) covers it. |
| Two tenants, three pools, six quack nodes, an RBAC graph, a federated catalog | The bootstrap manifest is applied to the control plane, then `PoolSupervisor.reconcile` spawns nodes from each pool's role distribution. |

## Tenants and tenant-dbs

| Tenant | Tenant-db | Default schema | Pools |
|---|---|---|---|
| `acme` | `acme_tpch` (DuckLake on Postgres) | `tpch1` | `bi` (1 read-only + 1 dual), `etl` (1 write-only + 1 dual) |
| `globex` | `globex_tpcds` (DuckLake on Postgres) | `tpcds1` | `bi` (1 read-only + 1 dual) |

`acme` carries the TPC-H tables (`customer`, `lineitem`, `nation`, `orders`, `part`, `partsupp`, `region`, `supplier`), plus, with `LOAD_SSB`, the SSB star schema in the sibling schema `ssb1` (`lineorder`, `customer`, `supplier`, `part`, `dwdate`; query them as `ssb1.<table>` since the pools default to `tpch1`). `globex` carries the TPC-DS tables (`store_sales`, `web_sales`, `catalog_sales`, `customer`, `customer_demographics`, and 19 others).

## Roles and verb-matrix

The four verb classes are exercised explicitly. See [Access control model](/operating/rbac-model) for what `RO` / `RW` / `DDL` / `ALL` cover.

### acme

| Role | Grants | Demonstrates |
|---|---|---|
| `analyst` | `RO` on `customer`, `orders`, `nation`, `region` | Narrow per-table read |
| `etl` | `RW` on `lineitem`, `RO` on `orders` | `RW` includes read so a writer can also `SELECT` |
| `dba` | `DDL` on `tpch1.*` | DDL alone, no data verbs |
| `tenant_admin` | `ALL` on `*.*.*` | Wildcard, tenant-scoped (does NOT reach globex) |

### globex

| Role | Grants | Demonstrates |
|---|---|---|
| `analyst` | `RO` on `store_sales`, `customer`, `customer_demographics` | Same pattern, different schema |
| `etl` | `RW` on `web_sales`, `RO` on `store_sales` | |
| `cross_tenant_analyst` | `RO` on `acme_pg.tpch1.customer`, `RO` on `acme_pg.tpch1.orders` | Cross-tenant read via federation, explicit grants required |
| `tenant_admin` | `ALL` on `*.*.*` | Wildcard, tenant-scoped |

## Groups

| Tenant | Group | Roles |
|---|---|---|
| `acme` | `analysts` | `analyst` |
| `acme` | `data-eng` | `etl`, `dba` (multi-role group) |
| `globex` | `analysts` | `analyst` |

## Users

All passwords are plaintext in the bundled manifest and are bcrypted by `ManifestImporter` at import time. Anyone reading the file in the jar sees the credentials, which is the point of a demo. The file is opt-in: nothing imports it unless `LOAD_TPCH`, `LOAD_TPCDS`, the legacy `LOAD_TPC`, or `QOD_BOOTSTRAP_YAML` is set.

| Username | Tenant | Password | Role-grant path | Pool grant |
|---|---|---|---|---|
| `root` | none (superuser) | `demo-root` | bypass | n/a |
| `alice` | `acme` | `demo-alice` | direct: `analyst` | `bi` |
| `bob` | `acme` | `demo-bob` | group `data-eng` -> `etl`, `dba` | `etl` |
| `dave` | `acme` | `demo-dave` | direct: `dba` | `etl` |
| `acme-admin` | `acme` | `demo-acme-admin` | direct: `tenant_admin` | `bi`, `etl` |
| `carol` | `globex` | `demo-carol` | group `analysts` -> `analyst` AND direct: `cross_tenant_analyst` | `bi` |
| `globex-admin` | `globex` | `demo-globex-admin` | direct: `tenant_admin` | `bi` |

## Federation

`globex_tpcds` carries a federated source named `acme_pg` that ATTACHes the `acme_tpch` Postgres database read-only. The connection secrets pull from the manager's existing `QOD_PG_*` environment variables via `externalRef: env:QOD_PG_HOST` (etc.), so the demo works unchanged in both native (`localhost`) and docker (`postgres` service) modes without a YAML edit.

When a globex user runs `SELECT * FROM acme_pg.tpch1.customer`, the query is routed to a globex node which transparently forwards it to acme's Postgres via DuckDB's `postgres` extension. The result returns through the same FlightSQL session.

ACL is still enforced over federated tables: `cross_tenant_analyst` is the role that opens the door, and it grants `RO` only on `customer` and `orders` under `acme_pg.tpch1`. Other acme tables (`lineitem`, `supplier`, ...) remain unreachable from globex.

## Walkthrough: see the ACL in action

The matrix below is also pinned as `BootstrapDemoEffectiveSpec` in the test suite, so the documented decisions and the bundled YAML cannot drift apart silently.

### `alice` (acme analyst, RO on a few TPC-H tables)

| Statement | Decision | Why |
|---|---|---|
| `SELECT * FROM customer` | **Allowed** | `analyst` RO covers `acme_tpch.tpch1.customer` |
| `INSERT INTO customer VALUES (...)` | **Denied** | RO does not cover Write |
| `SELECT * FROM globex_tpcds.tpcds1.store_sales` | **Denied** | `analyst` has no globex grant; `*` wildcard is tenant-scoped |

### `bob` (acme etl + dba via `data-eng` group)

| Statement | Decision | Why |
|---|---|---|
| `INSERT INTO lineitem VALUES (...)` | **Allowed** | `etl` role grants RW on lineitem (via `data-eng` group) |
| `CREATE TABLE x (id INT)` | **Allowed** | `dba` role grants DDL on `tpch1.*` (via `data-eng` group) |

### `dave` (acme dba only)

| Statement | Decision | Why |
|---|---|---|
| `SELECT * FROM customer` | **Denied** | `dba` role grants DDL only, no Read |
| `CREATE TABLE foo (id INT)` | **Allowed** | DDL covers `Ddl` access |

### `acme-admin` (acme tenant admin)

| Statement | Decision | Why |
|---|---|---|
| `SELECT * FROM customer` | **Allowed** | `tenant_admin` `*.*.* ALL` covers everything in acme |
| `SELECT * FROM globex_tpcds.tpcds1.store_sales` | **Denied** | catalog `*` is tenant-scoped; does not reach a sibling tenant's catalog |

### `carol` (globex analyst + cross-tenant federation reader)

| Statement | Decision | Why |
|---|---|---|
| `SELECT * FROM store_sales` | **Allowed** | `analyst` via `analysts` group |
| `SELECT * FROM acme_pg.tpch1.customer` | **Allowed** | `cross_tenant_analyst` direct grant on the federated table |
| `SELECT * FROM acme_pg.tpch1.lineitem` | **Denied** | no grant on lineitem under acme_pg |

### `globex-admin` (globex tenant admin)

| Statement | Decision | Why |
|---|---|---|
| `SELECT * FROM acme_pg.tpch1.customer` | **Denied** | `tenantCatalogs` enumerates tenant-dbs only, not federated aliases. `*.*.* ALL` does NOT reach `acme_pg`; an explicit named grant would be required. |

### `root` (superuser, no tenant)

| Statement | Decision | Why |
|---|---|---|
| `SELECT * FROM globex_tpcds.tpcds1.store_sales` | **Allowed** | superuser bypass; no per-statement check |

## Logging in

### Admin UI (`http://localhost:20900/ui/`)

- Tenant ID blank, username `root`, password `demo-root` for the superuser console.
- Tenant ID `acme` and any acme username/password for a tenant-scoped view of acme's roles, groups, users, and pool permissions.
- Tenant ID `globex` for the globex view (which also surfaces the `acme_pg` federated source under `globex_tpcds`).

### FlightSQL (`grpc+tls://localhost:31338`)

The standard FlightSQL JDBC URL form:

```
jdbc:arrow-flight-sql://localhost:31338
  ?useEncryption=true
  &disableCertificateVerification=true
  &tenant=acme
  &pool=bi
  &username=alice
  &password=demo-alice
```

Replace `tenant`, `pool`, `username`, `password` with the personas from the table above. For the superuser, swap the `tenant=...` parameter for `superuser=true`.

## What to try next

- Walk through the [Access control model](/operating/rbac-model) with concrete tenants in front of you.
- Add a new role to `globex.cross_tenant_analyst` via the [Administering access](/operating/rbac-admin) page and watch carol's effective set widen on the next handshake.
- Read [Federation](/operating/federation) to see how `acme_pg`'s `setupSql` is composed and how secrets are resolved.
- Strip the demo out: run without any `LOAD_TPC*` var, get an empty manager, build your own tenants via REST/UI.

## Cleanup

`NUKE=1` (without any `LOAD_TPC*` var) tears down state and boots empty:

```bash
NUKE=1 ./scripts/run-jar.sh
```

It drops the control-plane database, the `acme_tpch` and `globex_tpcds` tenant-db databases, the certs, and the ducklake data directories. Run-as-you-like operator setups built via the REST API or UI on top of the demo will be wiped along with everything else.