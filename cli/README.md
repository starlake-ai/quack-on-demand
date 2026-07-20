# qod - the quack-on-demand CLI

`qod` is a Python command-line client for quack-on-demand. It wraps the
manager's admin REST surface (tenants, databases, pools, nodes, RBAC, catalog
browsing, federation, audit, usage) and the FlightSQL query plane (`qod sql`,
one-shot or interactive REPL) behind one noun-verb command tree.

## Install

From the repo root, against a local checkout:

```bash
pip install ./cli
```

For development (editable install, plus pytest/respx):

```bash
pip install -e 'cli[dev]'
```

Released versions are published to PyPI as `qod` (`qod-cli`, the original name,
remains as a compatibility alias built from [shim-qod-cli/](shim-qod-cli/)):

```bash
pip install qod
```

Both routes install the `qod` console script. With [uv](https://docs.astral.sh/uv/)
no install step is needed at all:

```bash
uvx qod start --demo
```

## Demo

`qod start --demo` runs the self-contained manager demo (embedded Postgres, seeded
TPC-H sample, RLS/CLS showcase) without any local checkout: it downloads the
manager jar for the latest release from GitHub Releases (falling back to the
release stamped into the CLI build when the lookup fails and a cached jar
exists), verifies it against the published sha256, caches it under the user
cache dir, and launches it with the required JVM flags. If no Java 21+ is
found it downloads a cached Temurin 21 JRE (announced, never prompted).
`--jar <path>` runs a local jar instead; `--version X.Y.Z`
pins a different release; extra arguments are passed through to the jar's
demo subcommand.

## Run a manager

`qod start` boots a real manager against your own Postgres (pointed at by
`QOD_PG_HOST` / `QOD_PG_PORT` / `QOD_PG_USER` / `QOD_PG_PASSWORD`) with the
same provisioning as the demo, plus the libduckdb native client. It honors
the `scripts/run-jar.sh` env-var surface: `QOD_VERSION` (release pin or
`latest`), `JAVA_BIN`, `JAVA_OPTS`, `JAR_CACHE_DIR`, `DUCKDB_VERSION`,
`DUCKDB_CACHE_DIR`, the `LOAD_TPCH` / `LOAD_TPCDS` / `LOAD_SSB` / `LOAD_TPC`
benchmark seeders, `DEMO=full|minimal`, and `NUKE=1`. State (DuckLake data,
TLS certs) anchors under the platform user data dir. `qod stop` finds the
running manager by its ports and tears it and its nodes down (SIGTERM, then
SIGKILL after `FORCE_AFTER` seconds).

## Quick start

```bash
qod login --url http://localhost:20900 --username admin
qod tenant list
qod sql "SELECT 1" --tenant acme --pool bi
```

`qod login` prompts for the password (it is never a command-line flag, so it
never lands in shell history), mints a session, and stores the session token
plus the edge host/port/TLS settings in the active profile - one login
configures both the REST plane and the SQL plane.

## Configuration

Settings resolve in this order for every field, highest priority first:

1. Command-line flag (e.g. `--tenant`, `--url`)
2. `QOD_*` environment variable
3. Value saved in the active profile
4. Built-in default

Profiles are named sections in a TOML file at the platform config
directory: `~/.config/qod/config.toml` on Linux, `~/Library/Application
Support/qod/config.toml` on macOS, `%APPDATA%\qod\config.toml` on Windows.
`QOD_CONFIG_FILE` overrides the full path. The file is written with mode
0600 because it can hold a session token and an opt-in SQL password. Select
a profile with `--profile NAME` or `QOD_PROFILE`; the default profile is
named `default`.

| Setting | Env var | Default | Notes |
|---|---|---|---|
| `manager_url` | `QOD_MANAGER_URL` | `http://localhost:20900` | REST plane base URL. |
| `api_key` | `QOD_API_KEY` | `""` | Static API key; wins over `token` when both are set. |
| `token` | `QOD_TOKEN` | `""` | Session JWT, normally written by `qod login`, not set by hand. |
| `edge_host` | `QOD_HOST` | `localhost` | FlightSQL edge host. |
| `edge_port` | `QOD_PORT` | `31338` | FlightSQL edge port. |
| `edge_tls` | `QOD_TLS` | `true` | TLS to the edge. |
| `edge_tls_verify` | `QOD_TLS_VERIFY` | `false` | Verify the edge TLS cert; off by default since the edge ships a self-signed cert. |
| `tenant` | `QOD_TENANT` | `""` | Default tenant for `qod sql`. |
| `pool` | `QOD_POOL` | `""` | Default pool for `qod sql`. |
| `sql_user` | `QOD_USER` | `""` | FlightSQL username; set by `qod login`. |
| `sql_password` | `QOD_PASSWORD` | `""` | FlightSQL password; opt-in storage, see "SQL plane" below. |
| `superuser` | `QOD_SUPERUSER` | `false` | Send the superuser call header on `qod sql`. |

## Command reference

Output columns below are one-line purposes; run `qod <noun> <verb> --help`
for the full flag list of any command.

### Top level

| Command | Purpose |
|---|---|
| `qod login` | Mint a session, store it and the edge settings in the active profile. |
| `qod logout` | Revoke the current session token. |
| `qod whoami` | Verify the current session. |
| `qod health` | Liveness plus pool/node counts (open endpoint). |
| `qod usage` | Usage accounting rollups. |
| `qod sql` | Run SQL against the FlightSQL edge; one-shot or interactive REPL. |

### auth - authentication mode discovery

| Verb | Purpose |
|---|---|
| `mode` | Show the auth mode (db or oidc) the manager expects. |

### config - server-published configuration

| Verb | Purpose |
|---|---|
| `client` | Edge host/port/TLS for client bootstrapping (open endpoint). |
| `server` | Effective manager configuration. |

### tenant - tenant CRUD

| Verb | Purpose |
|---|---|
| `list` | List tenants. |
| `create` | Create a tenant. |
| `delete` | Delete a tenant (must have no pools). |
| `set-disabled` | Enable or disable a tenant. |
| `set-auth` | Change a tenant's auth provider/config. |

### database - tenant databases (DuckLake catalogs)

| Verb | Purpose |
|---|---|
| `list` | List databases for a tenant. |
| `create` | Create a tenant database. |
| `update` | Update a tenant database's metastore/object-store/init settings. |
| `delete` | Delete a tenant database. |

### pool - pool lifecycle and pool-level access grants

| Verb | Purpose |
|---|---|
| `list` | List pools. |
| `create` | Create a pool. |
| `scale` | Change a pool's target size and role distribution. |
| `stop` | Stop a pool's nodes. |
| `delete` | Delete a pool. |
| `set-disabled` | Enable or disable a pool. |
| `set-resources` | Set CPU/memory requests for a pool's nodes. |
| `set-pod-template` | Set the Kubernetes pod template YAML for a pool. |
| `permission list` | List pool access grants. |
| `permission grant` | Grant pool access to a user or group. |
| `permission revoke` | Revoke a pool access grant. |

### node - node lifecycle and statement inspection

| Verb | Purpose |
|---|---|
| `quarantine` | Take a node out of routing rotation. |
| `unquarantine` | Return a node to routing rotation. |
| `restart` | Restart a node. |
| `set-max-concurrent` | Set a node's max concurrent statement limit. |
| `statements` | Recent statement history, newest first. |
| `active-statements` | Currently running statements. |

### statement - statement control

| Verb | Purpose |
|---|---|
| `kill` | Kill a running statement by id. |

### user - user principals

| Verb | Purpose |
|---|---|
| `list` | List users. |
| `create` | Create a user (tenant-scoped, or `--superuser` for tenant-less). |
| `update` | Update a user's tenant, password, or role. |
| `delete` | Delete a user. |
| `effective` | Closure of roles, groups, table permissions, and pool grants. |

### role - roles, table permissions, and row/column policies

| Verb | Purpose |
|---|---|
| `list` | List roles for a tenant. |
| `create` | Create a role. |
| `delete` | Delete a role. |
| `permission list` | List table permissions attached to a role. |
| `permission grant` | Grant a table permission to a role. |
| `permission revoke` | Revoke a table permission from a role. |
| `row-policy list` | List row-level security predicates on a role. |
| `row-policy create` | Add a row-level security predicate. |
| `row-policy update` | Update a row-level security predicate. |
| `row-policy delete` | Delete a row-level security predicate. |
| `column-policy list` | List column-level security policies on a role. |
| `column-policy create` | Add a column-level security policy. |
| `column-policy update` | Update a column-level security policy. |
| `column-policy delete` | Delete a column-level security policy. |

### group - groups

| Verb | Purpose |
|---|---|
| `list` | List groups for a tenant. |
| `create` | Create a group. |
| `delete` | Delete a group. |

### membership - RBAC membership edges

| Verb | Purpose |
|---|---|
| `user-role add` | Add a user to a role. |
| `user-role remove` | Remove a user from a role. |
| `user-group add` | Add a user to a group. |
| `user-group remove` | Remove a user from a group. |
| `group-role add` | Add a role to a group. |
| `group-role remove` | Remove a role from a group. |
| `group-role list` | List roles held by a group. |

### catalog - DuckLake catalog browsing, time travel, and recovery

| Verb | Purpose |
|---|---|
| `schemas` | List schemas in a tenant database. |
| `tables` | List tables in a schema. |
| `describe` | Describe a table's columns, optionally as of a snapshot/tag/timestamp. |
| `snapshots` | List snapshots for a tenant database, optionally filtered to a table. |
| `history` | Snapshot history for a table (operation, author, time range filters). |
| `preview` | Preview table rows, optionally as of a snapshot/tag/timestamp. |
| `data-diff` | Row-level diff of a table between two snapshot selectors. |
| `schema-diff` | Schema diff of a table between two snapshot selectors. |
| `recoverable` | Dropped tables still recoverable via undrop. |
| `undrop` | Recover a dropped table, optionally under a different name. |

### tag - snapshot tags (create, delete, protect)

| Verb | Purpose |
|---|---|
| `create` | Tag a snapshot, optionally marking it protected. |
| `delete` | Delete a tag. |
| `protect` | Change a tag's protected flag. |

### maintenance - managed maintenance policies and runs

| Verb | Purpose |
|---|---|
| `policy` | Show the maintenance policy for a scope. |
| `policy-upsert` | Create or update a maintenance policy. |
| `policy-delete` | Delete a maintenance policy. |
| `run` | Trigger a maintenance run. |
| `runs` | List past maintenance runs. |

### manifest - topology manifest export/import (YAML)

| Verb | Purpose |
|---|---|
| `export` | Export the whole control-plane topology as YAML. |
| `import` | Import a topology manifest (YAML) into the control plane. |

### federation - federated sources per (tenant, tenant-db)

| Verb | Purpose |
|---|---|
| `list` | List federated sources. |
| `get` | Show one federated source. |
| `create` | Create a federated source. |
| `delete` | Delete a federated source. |
| `secret list` | List secrets referenced by a federated source's setup SQL. |
| `secret set` | Set a secret (inline value or external reference). |
| `secret delete` | Delete a secret. |

### audit - audit log

| Verb | Purpose |
|---|---|
| `list` | List audit log entries with filters. |
| `actions` | Distinct audit action names, for filter values. |

### history - statement history and trends

| Verb | Purpose |
|---|---|
| `statements` | List past statements with filters. |
| `trends` | Aggregate statement trends over time. |

## REST parity gate

Every REST operation published in the generated OpenAPI specification must be
reachable from the CLI with every parameter. A parity test (strict, no exceptions
beyond documented exclusions) runs with the pytest suite.

**How it works:** The test loads `cli/tests/resources/openapi.yaml` (regenerated
from the Scala manager source), extracts every HTTP method/path pair, and
compares it against the CLI's REGISTRY (all commands with their flags). Gaps are
reported as missing commands or missing parameters on existing commands.

**How to add a command:** Stack
`@covers(method, path, {param: "--flag" or "(descriptive value)"})` directly on
the Typer command function, one decorator per REST operation the command
performs (see `login` in `src/qod_cli/commands/auth.py` for a stacked example).
Always include the params dict for parameterized endpoints: a `@covers` without
params registers zero params and fails the gate. Parameters are matched by
name; descriptive value strings (e.g., `"(prompted)"` for the login password,
`"(computed from the dry-run response, gated by --yes)"`) are allowed for
params whose value is not a plain flag - the gate compares **keys only**, so
these annotations serve as documentation without affecting the test.

**How to justify an exclusion:** Some REST endpoints are not CLI-reachable by
design (e.g., OIDC redirect targets). Add them to the `EXCLUSIONS` set in
`cli/tests/test_rest_parity.py` with an inline comment explaining why.

**Keeping the inventory current:** The OpenAPI contract copy lives at
`cli/tests/resources/openapi.yaml`, regenerated via:

```bash
sbt "runMain ai.starlake.quack.docs.GenOpenApi cli/tests/resources/openapi.yaml <version>"
```

A Scala test (`OpenApiFreshnessSpec`) ensures this file stays in sync with the
manager source; commit both the `@covers` markers and the regenerated YAML file
when adding or changing commands.

## Output

Human-readable Rich tables are the default. Pass `--json` (a top-level flag,
before the noun) for the raw JSON response body - the stable interface for
scripting. `qod sql` additionally supports `--csv`.

Exit codes: `0` success, `1` a server/API error (the server's error body is
shown verbatim, e.g. `tenant_forbidden`), `2` a usage error (missing or
invalid arguments, the Typer/Click default).

## SQL plane

`qod sql "SELECT ..."` runs one statement and prints the result; `qod sql`
with no statement argument opens an interactive REPL (statements end with
`;`, `\q` or Ctrl-D exits, errors from one statement do not exit the REPL).

Credentials for the FlightSQL edge are resolved in this order:

1. `QOD_PASSWORD` environment variable
2. `sql_password` in the active profile (opt-in; not written by `qod login`)
3. An interactive prompt, once per invocation

`sql_user` comes from `qod login` (or `QOD_USER`/`--tenant` overrides).
`--superuser` sends the superuser call header instead of a tenant/pool pair.
TLS to the edge defaults to on with certificate verification off, since the
edge ships a self-signed certificate; set `edge_tls_verify = true` or
`QOD_TLS_VERIFY=true` to opt into verification against a real certificate.
