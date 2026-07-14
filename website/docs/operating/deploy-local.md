---
id: deploy-local
title: Local deployment
---

## When to use

`runtimeType=local` is the default backend. It is the right choice for:

- Developer workstations and single-host setups where all DuckDB Quack nodes run as child processes of the manager JVM.
- Smoke-testing and integration work where Kubernetes is not available or not needed.
- Small deployments where the total node count fits comfortably on one machine.

For multi-host or container-orchestrated deployments, see the Kubernetes backend instead.

## How nodes are spawned

`LocalQuackBackend` spawns each DuckDB Quack node by forking a child process via `scripts/spawn-quack-node.sh`. The manager controls the full lifecycle: it allocates a port from the configured range, generates a random authentication token, sets the metastore environment variables, and hands those to the script. When the manager stops (SIGTERM or `./scripts/stop-jar.sh`), it sends SIGTERM to every tracked child and waits up to 5 seconds per process before issuing SIGKILL.

**Port allocation.** The manager maintains a `PortAllocator` bounded by `minPort` and `maxPort` (defaults: 21900 to 22500). Ports are leased on spawn and released on stop. At most `maxNodesTotal` (default: 50) child nodes may be alive at the same time across all pools.

**Do not run `scripts/spawn-quack-node.sh` directly.** The supervisor owns port allocation, token generation, and the metastore environment contract. Manual invocations bypass all of that, leak ports into the allocator's range, and produce orphan processes that cause silent "Authentication failed" errors at query time.

**Orphan detection.** `scripts/run-jar.sh` runs a port preflight check at startup. If any listener is found inside the node port range it aborts with an actionable error message. To clear orphans:

```bash
pkill -f 'quack_serve|spawn-quack-node'
```

The spawn script path can be overridden via `QOD_SPAWN_SCRIPT` when the script is installed outside the repository (for example under `/opt/...` in a container image).

## Node environment contract

`LocalQuackBackend.start()` populates each child process's environment from two sources in order: the `defaultMetastore` block in `application.conf` (or its `QOD_PG_*` env-var overrides), then any per-pool `metastore` entries supplied in the `CreatePoolRequest`. Pool-level keys override the defaults.

The spawn script reads the following variables:

| Variable | Meaning |
|---|---|
| `pgHost` | Hostname of the Postgres server used by DuckLake as its catalog store. |
| `pgPort` | Port of the Postgres server (default `5432`). |
| `pgUser` | Postgres login user (default `postgres`). |
| `pgPassword` | Postgres password for `pgUser`. |
| `dbName` | Postgres database that holds the DuckLake catalog metadata. Also used as the DuckDB catalog name. |
| `schemaName` | DuckLake schema inside `dbName` where tables are registered. Must differ from `dbName` to avoid ambiguous two-part identifiers in DuckDB. |
| `dataPath` | Local or object-store path where DuckLake writes Parquet data files. Accepts `s3://`, `az://`, and `abfss://` URIs for remote storage. |

In addition the supervisor injects two internal variables that the spawn script receives as positional arguments: the TCP port the node must listen on, and a 256-bit URL-safe base64 bearer token used to authenticate the manager's HTTP requests to that node. Neither appears in `application.conf`; they are generated fresh for each spawn.

The `kind` variable is also required. It selects the catalog mode: `ducklake` (DuckLake catalog backed by Postgres), `duckdb-file` (standalone `.duckdb` file), or `memory` (no persistent catalog; useful for federation-only pools).

## Operate locally

**Configuration knobs.** All relevant settings accept `QOD_*` environment variable overrides. See [/reference/configuration](/reference/configuration) for the full list. The most commonly adjusted ones for a local deployment are:

| Env var | Default | Purpose |
|---|---|---|
| `QOD_RUNTIME_TYPE` | `local` | Backend type. Leave as `local` for single-host deployments. |
| `QOD_MIN_PORT` | `21900` | Lower bound of the child node port range. |
| `QOD_MAX_PORT` | `22500` | Upper bound of the child node port range. |
| `QOD_MAX_NODES_TOTAL` | `50` | Hard cap on simultaneous child node processes. |
| `QOD_SPAWN_SCRIPT` | `./scripts/spawn-quack-node.sh` | Path to the node spawn script. |
| `QOD_PG_HOST` | `localhost` | Postgres host for the control plane and default metastore. |
| `QOD_PG_PASSWORD` | `azizam` | Postgres password. Rotate before any non-local exposure. |
| `QOD_DUCKLAKE_DATA_PATH` | `./ducklake/tpch` | Default data path propagated to spawned nodes. |

**Wiping local state.** To discard all state and start fresh, pass `NUKE=1` to `run-jar.sh`:

```bash
NUKE=1 ./scripts/run-jar.sh
```

This stops any running manager, drops the Postgres control-plane database and the bootstrap tenant database, and removes the `ducklake/`, `state/`, and `certs/` directories. This action is irreversible.

To boot freshly seeded instead of empty, combine `NUKE=1` with the demo seed flags. `DEMO=minimal` selects the single-DuckDB profile - one tenant (`acme`), one pool (`bi`), one dual node serving both reads and writes - the shape for fronting a single DuckDB/DuckLake database:

```bash
NUKE=1 DEMO=minimal LOAD_TPCH=1 ./scripts/run-jar.sh
```

See [Demo bootstrap](/getting-started/demo) for the full flag reference and what each profile creates.

**Respawn and recovery behavior.** When the manager restarts after a crash or SIGTERM, it reads the persisted node registry and calls `adopt()` on each node that was alive at shutdown. Adopted nodes have their port re-claimed in the allocator and are tracked via `ProcessHandle` so a subsequent `stop()` still delivers SIGTERM. Nodes that died while the manager was down are detected by the health-check loop and replaced automatically. For full details on how the manager handles node failures, drains, and replacements, refer to the Resilience guide.
