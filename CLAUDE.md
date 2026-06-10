# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test commands

```bash
sbt run                 # run the manager (forks JVM - see "JVM forking" below)
sbt test                # run the full Scala test suite (~714 tests)
sbt assembly            # build distrib/quack-on-demand-assembly-*.jar (UI is bundled in)
sbt "testOnly ai.starlake.quack.route.RouterSpec"        # one test class
sbt "testOnly *RouterSpec -- -z 'picks the least-loaded'" # one test by name fragment
sbt scalafmtAll         # format (scalafmt 3.10, scala3 dialect, maxColumn 100)
```

Boot the manager from the uber-jar (with TLS cert auto-gen + Postgres reachability probe):

```bash
./scripts/run-jar.sh           # foreground
BUILD=1 ./scripts/run-jar.sh   # sbt assembly first
./scripts/stop-jar.sh            # SIGTERM → wait → SIGKILL
```

UI dev loop (proxies `/api/*` to `localhost:20900`):

```bash
cd ui && npm install && npm run dev
```

The UI is built into `src/main/resources/ui` by [project/UiBuild.scala](project/UiBuild.scala) as a resourceGenerator, so `sbt compile`/`sbt assembly` will run `npm ci` + `npm run build` automatically. There is no manual UI build step for a regular Scala compile.

## JVM forking (don't disable it)

[build.sbt](build.sbt) sets `Compile / run / fork := true` and `Test / fork := true` and adds `--add-opens=java.base/java.nio=ALL-UNNAMED` + `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`. Arrow Flight's `arrow-memory-unsafe` allocator reflects into `java.nio` internals; without forked JVM + the opens, Arrow crashes at class init under Java 17+. The assembly jar embeds an `Add-Opens` manifest attribute (JEP 261) so `java -jar` works without extra flags. The startup script also passes `-Darrow.allocation.manager.type=Unsafe` - without it Arrow auto-picks the netty allocator, which crashes with `NoSuchFieldError: chunkSize` because the assembly bundles a newer Netty than `arrow-memory-netty 14.0.1` reflects against.

## Architecture - the bits that span multiple files

The process is a single uber-jar that exposes **three** sockets:

1. **Manager REST + React UI** on `:20900` ([ManagerServer.scala](src/main/scala/ai/starlake/quack/ondemand/ManagerServer.scala), Tapir + HTTP4s Ember). Endpoints live under `ondemand/api/*Handlers.scala`. The React SPA at `/ui/*` is served from `src/main/resources/ui` (built by the resourceGenerator above).
2. **Arrow FlightSQL edge** on `:31338` ([FlightEdgeServer.scala](src/main/scala/ai/starlake/quack/edge/FlightEdgeServer.scala) → [FlightProducerImpl.scala](src/main/scala/ai/starlake/quack/edge/FlightProducerImpl.scala) → [FlightSqlRouter.scala](src/main/scala/ai/starlake/quack/edge/FlightSqlRouter.scala)). TLS is on by default, cert auto-generated at `certs/server-{cert,key}.pem` if missing.
3. **Quack nodes** on `:21900–22500` - child processes spawned by [scripts/spawn-quack-node.sh](scripts/spawn-quack-node.sh) (local mode) or pods ([KubernetesQuackBackend.scala](src/main/scala/ai/starlake/quack/ondemand/runtime/KubernetesQuackBackend.scala)). Each node runs DuckDB Quack with a metastore env-var contract: `pgHost / pgPort / pgUser / pgPassword / dbName / schemaName / dataPath`. **`spawn-quack-node.sh` is invoked by [LocalQuackBackend.scala](src/main/scala/ai/starlake/quack/ondemand/runtime/LocalQuackBackend.scala) - don't run it directly.**

A FlightSQL request flows: `client → FlightProducerImpl → AuthenticationService → FlightSqlRouter.execute → StatementValidator (ACL) → StatementClassifier (READ/WRITE/DDL) → Router.pick(snapshot, kind) → QuackHttpAdapter → child node's /quack HTTP endpoint → Arrow stream back through Flight`. Tests for the routing core ([RouterSpec.scala](src/test/scala/ai/starlake/quack/route/RouterSpec.scala), [StatementClassifierSpec.scala](src/test/scala/ai/starlake/quack/route/StatementClassifierSpec.scala), [FlightSqlRouterSpec.scala](src/test/scala/ai/starlake/quack/edge/FlightSqlRouterSpec.scala)) exercise this without the Flight wire.

### State storage - file vs. Postgres

[Main.scala](src/main/scala/ai/starlake/quack/Main.scala) picks the control-plane store from `quack-on-demand.stateStorage` (default `postgres`):

- `postgres` - normalized **`qodstate_*`** tables managed by Liquibase, living in a dedicated control-plane DB (`qod` by default): `qodstate_tenant`, `qodstate_tenant_db`, `qodstate_pool`, `qodstate_node` for the registry; `qodstate_user`, `qodstate_role`, `qodstate_role_permission`, `qodstate_group`, `qodstate_user_role`, `qodstate_user_group`, `qodstate_group_role`, `qodstate_pool_permission` for the RBAC graph. Each managed tenant-db (e.g. `tpch_tpch1`) is a **separate** database next to it, holding the DuckLake `__ducklake_*` catalog. RBAC REST endpoints and admin seeding are **only mounted in postgres mode**.
- `file` - legacy single-JSON-blob store at `statePath` (backed by the `slkstate_pool_state` schema when stored in Postgres). No admin seeding, no RBAC endpoints, no Liquibase. Useful for immutable deploys.

The `qodstate_*` prefix keeps control-plane tables from colliding with DuckLake's `__ducklake_*` namespace inside any shared database.

### Edge config + catalog ([quack.edge.config](src/main/scala/ai/starlake/quack/edge/config/), [quack.edge.catalog](src/main/scala/ai/starlake/quack/edge/catalog/))

The auth/ACL/session config types and the DuckLake catalog resolver live under [quack.edge.config](src/main/scala/ai/starlake/quack/edge/config/) and [quack.edge.catalog](src/main/scala/ai/starlake/quack/edge/catalog/). The pureconfig `ProductHint`s in [Main.scala](src/main/scala/ai/starlake/quack/Main.scala) override the `derives ConfigReader` defaults to use camelCase (matching `application.conf`) instead of pureconfig's kebab-case.

### RBAC validator

[Main.scala](src/main/scala/ai/starlake/quack/Main.scala) wires the `StatementValidator` based on storage:
- `PostgresAclValidator` when `stateStorage=postgres` - resolves the session's `(tenant, user)` into the cached **EffectiveSet** (closure of roles · groups · `qodstate_role_permission` rows reachable through them), then per statement uses the [ACL SQL parser](src/main/scala/ai/starlake/acl/parser/) to extract table refs and matches them against that set. Superusers (`qodstate_user.tenant IS NULL`) bypass the check.
- `StatementValidator.allowAll` when `acl.enabled=false`. The file-based `AclStatementValidator` is parser-only after the RBAC cutover - the YAML grant store is unwired.

**DML / DDL grants are enforced per-table.** `SqlParser` walks INSERT / UPDATE / DELETE / MERGE / CREATE TABLE / CREATE VIEW / DROP / ALTER / TRUNCATE and emits `TableAccess(table, verb)` tuples with `verb` collapsed to `Read | Write | Ddl`. `PostgresAclValidator` matches each access against the principal's role permissions via a `verbCovers` helper that bridges granular column values (`SELECT`/`INSERT`/`UPDATE`/`DELETE`/`CREATE`/`DROP`/`ALTER`/`ALL`) into the collapsed space. A role permission of `verb=ALL` covers anything; `verb=INSERT` covers an INSERT-target `Write` but not a SELECT's `Read`. The DuckDB / BigQuery `FROM t` shorthand (`FromQuery extends Select`) is also walked. Statements with no table refs (COMMIT, ROLLBACK, SET, USE, SHOW) hit the `ControlFlow` arm and are admitted unconditionally.

## Configuration

Every scalar in [src/main/resources/application.conf](src/main/resources/application.conf) accepts a `QOD_*` env-var override (or `PROXY_*` for FlightSQL edge keys). Prefer env vars over editing the conf - the conf is bundled into the jar at build time. Defaults: `:20900` REST, `:31338` FlightSQL (TLS on), Postgres `localhost:5432` user `postgres` / `azizam`, admin `admin@localhost.local` / `admin`. `quack-on-demand.defaultMetastore.dataPath` ships with a developer machine's absolute path - override it before running outside that environment.

## Operator runbook

[skills/quack-on-demand/SKILL.md](skills/quack-on-demand/SKILL.md) is the operator runbook: REST API curl recipes, tenant/pool/ACL CRUD, typical failure modes, load-test invocation. When the user asks operational questions ("how do I create a pool", "why is auth failing"), prefer the patterns there over reinventing them.

## Things to avoid

- **Don't disable JVM forking** in build.sbt - see "JVM forking" above.
- **Don't invoke `scripts/spawn-quack-node.sh` directly** - it's spawned by `LocalQuackBackend` with the right port + token + env contract. Manual invocation will leak ports and confuse the supervisor.
- **Don't edit the bundled `application.conf` for local tweaks** - set the `QOD_*` env var instead, or the change vanishes on the next `sbt assembly`.
