---
id: tenants-databases
title: Tenants and databases
---

This page covers provisioning the two structural objects every deployment starts with: tenants and their databases (tenant-dbs). Pools are layered on top of a database; see the "Pools and cohorts" page. For how these objects relate to access control, see the [Access control model](/operating/rbac-model).

## The object hierarchy

```
tenant  ──owns──▶  tenant-db (a database)  ──hosts──▶  pool  ──contains──▶  nodes
```

- A **tenant** is an isolation boundary. It owns one or more databases and selects the auth provider for its users.
- A **tenant-db** is a database. In the default `ducklake` kind it is a Postgres database on the shared server, holding a DuckLake catalog with a catalog name and a default schema.
- A **pool** is bound to exactly one tenant-db and is what clients connect to.

Provision them in that order: tenant first, then a database under it, then a pool under the database.

## Authenticating with the CLI

Every `/api/*` call needs a credential. The examples below use the [qod CLI](/cli/); they assume `qod login` has stored a session, or `QOD_API_KEY` is set for CI scripts. These endpoints are mounted only in `postgres` state-storage mode (the default).

## Tenants

### Create a tenant

```bash
qod tenant create acme
```

`--auth-provider` defaults to `db` (database-backed bcrypt users). To point a tenant's users at an OIDC provider instead, set `--auth-provider` to one of `keycloak` / `google` / `azure` / `aws` and supply the provider config with repeatable `--auth-config KEY=VALUE`:

```bash
qod tenant create acme --auth-provider keycloak \
  --auth-config issuer=https://kc.example.com/realms/acme --auth-config realm=acme
```

`authConfig` is empty for `db`. For OIDC providers it expects `issuer` (full URL) plus one of `realm` (Keycloak), `hd` (Google Workspace domain), `tenantId` (Azure), or `userPoolId` (Cognito).

For Google, two optional extra keys give the tenant its own OAuth client instead of sharing the manager-wide one from `quack-flightsql.auth.google.*`:

- `clientId` - the tenant's Google OAuth client ID.
- `clientSecretRef` - a reference to the secret (e.g. `env:GOOGLE_CS_ACME`), never the literal value. Today only `env:NAME` is supported.

Both must be set together; leaving either blank falls back to the global client. See [Authentication providers](/operating/auth-providers) for the per-provider details.

### List, disable, change auth, delete

```bash
# List every tenant (with its pools and auth provider)
qod tenant list

# Disable a tenant: the edge rejects fresh handshakes for all its pools
qod tenant set-disabled acme --disabled

# Switch the auth provider after creation
qod tenant set-auth acme --auth-provider db

# Delete a tenant (remove its pools first, else 409 has_pools)
qod tenant delete acme
```

## Databases (tenant-dbs)

A database is created under an existing tenant. The `name` you pass is a suffix; the manager composes the real Postgres database name as `${tenant}_${suffix}` and provisions that database on the shared server. For example, `tenant=acme`, `name=sales` yields the database `acme_sales`.

### Create a database

```bash
qod database create --tenant acme --name sales --kind ducklake \
  --data-path /app/ducklake/acme_sales --default-schema mart
```

| Field | Meaning |
|---|---|
| `tenant` | Owning tenant (must exist). |
| `name` | Database suffix; the full name becomes `${tenant}_${name}`. |
| `kind` | `ducklake` (DuckLake catalog backed by Postgres, the default), `duckdb-file` (a standalone `.duckdb` file), or `memory` (no persistent catalog; useful for federation-only databases). |
| `dataPath` | Where DuckLake writes Parquet. A filesystem path, or an `s3://` / `az://` / `abfss://` URI for object storage. Defaults are derived from the global metastore when omitted. |
| `metastore` | Optional per-database overrides of the Postgres connection (`pgHost`, `pgPort`, `pgUser`, `pgPassword`, `dbName`, `schemaName`). Empty inherits the global defaults. |
| `objectStore` | Optional per-database object-store credentials when `dataPath` is an object-store URI. Takes effect at node spawn as a path-scoped DuckDB secret; see below. |
| `defaultDatabase` / `defaultSchema` | The catalog and schema unqualified table names resolve against for sessions on this database. `defaultSchema` must differ from the database name to avoid ambiguous two-part identifiers in DuckDB. |

### Per-database object-store credentials

`objectStore` authenticates this database's own bucket, separately from the manager-wide `QOD_S3_*` / `QOD_AZURE_*` / `QOD_GCS_*` defaults. Setting it (via `qod database create --object-store KEY=VALUE` / `--object-store` on update, or the object-store fields in the admin UI's database edit form) authors a `CREATE SECRET` scoped to that database's `dataPath`, so this database's bucket authenticates with its own keys while every other database keeps using the global default secret (DuckDB resolves the most specific scope per path). Leaving `objectStore` empty falls back to the global env credentials, unchanged - this is the back-compat path for every deployment that only ever used one set of keys.

Provider-specific keys, matched by the `dataPath` scheme:

| Provider | `dataPath` scheme | Keys |
|---|---|---|
| S3 / R2 | `s3://`, `s3a://`, `r2://` | `s3_region`, `s3_access_key_id`, `s3_secret_access_key`, `s3_endpoint`, `s3_url_style` |
| GCS (HMAC) | `gs://` | `gcs_hmac_key_id`, `gcs_hmac_secret` |
| Azure | `az://`, `azure://`, `abfss://` | `azure_account`, `azure_account_key` |

The credentials never appear inlined in a pod spec: on Kubernetes the manager writes them into a per-node Secret (`qod-store-${nodeId}`) and injects it via `env.valueFrom.secretKeyRef`, mirroring the per-pod token and federation secrets; `kubectl describe pod` shows the ref, never the values. On the local backend they ride the node's process environment, same as the other spawn-time SQL blocks. GET/list responses redact the secret-valued keys (`s3_secret_access_key`, `azure_account_key`, `gcs_hmac_secret`, alongside `pgPassword`).

This per-db secret is authored only for `kind=ducklake` databases (both spawn scripts install it right before the DuckLake `ATTACH`). A `duckdb-file` database does not get a per-db object-store secret even when its `dataPath` is a remote URI - that spawn arm does not install `httpfs`/`azure` at all today, so a remote `duckdb-file` dataPath is not a supported combination yet, independent of this feature.

Editing `objectStore` (create or update) restarts the database's nodes so the new secret takes effect immediately; edits do not rotate a secret on an already-running node without a respawn. For object storage on S3-compatible backends and the manager-wide `QOD_S3_*` keys, see the Docker deployment page and the [Configuration reference](/reference/configuration).

### List and delete

```bash
# List the databases under a tenant
qod database list --tenant acme

# Delete a database (remove its pools first, else 409 has_pools)
qod database delete --tenant acme --name sales
```

Deleting a database removes the `qodstate_tenant_db` row. The underlying Postgres database and any object-store Parquet are not erased by the API; reclaim them separately if you no longer need the data.

## Next step

With a tenant and a database in place, create a pool of nodes against that database so clients can connect and query. See the "Pools and cohorts" page.
