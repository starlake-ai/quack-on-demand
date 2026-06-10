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

## Authenticating REST calls

Every `/api/*` call needs a credential. Either set a static `QOD_API_KEY` and send it as `X-API-Key`, or mint an admin session token:

```bash
TOKEN=$(curl -sS -X POST http://localhost:20900/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')
```

The examples below send `-H "X-API-Key: $TOKEN"`. These endpoints are mounted only in `postgres` state-storage mode (the default).

## Tenants

### Create a tenant

```bash
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/tenant/create \
  -H 'Content-Type: application/json' \
  -d '{"name":"acme"}'
```

`authProvider` defaults to `db` (database-backed bcrypt users). To point a tenant's users at an OIDC provider instead, set `authProvider` to one of `keycloak` / `google` / `azure` / `aws` and supply the provider config:

```bash
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/tenant/create \
  -H 'Content-Type: application/json' \
  -d '{"name":"acme","authProvider":"keycloak",
       "authConfig":{"issuer":"https://kc.example.com/realms/acme","realm":"acme"}}'
```

`authConfig` is empty for `db`. For OIDC providers it expects `issuer` (full URL) plus one of `realm` (Keycloak), `hd` (Google Workspace domain), `tenantId` (Azure), or `userPoolId` (Cognito). See [Authentication providers](/operating/auth-providers) for the per-provider details.

### List, disable, change auth, delete

```bash
# List every tenant (with its pools and auth provider)
curl -sS -H "X-API-Key: $TOKEN" http://localhost:20900/api/tenant/list

# Disable a tenant: the edge rejects fresh handshakes for all its pools
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/tenant/setDisabled \
  -H 'Content-Type: application/json' -d '{"name":"acme","disabled":true}'

# Switch the auth provider after creation
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/tenant/setAuth \
  -H 'Content-Type: application/json' \
  -d '{"name":"acme","authProvider":"db","authConfig":{}}'

# Delete a tenant (remove its pools first, else 409 has_pools)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/tenant/delete \
  -H 'Content-Type: application/json' -d '{"name":"acme"}'
```

## Databases (tenant-dbs)

A database is created under an existing tenant. The `name` you pass is a suffix; the manager composes the real Postgres database name as `${tenant}_${suffix}` and provisions that database on the shared server. For example, `tenant=acme`, `name=sales` yields the database `acme_sales`.

### Create a database

```bash
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/database/create \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","name":"sales","kind":"ducklake",
       "dataPath":"/app/ducklake/acme_sales",
       "defaultSchema":"mart"}'
```

| Field | Meaning |
|---|---|
| `tenant` | Owning tenant (must exist). |
| `name` | Database suffix; the full name becomes `${tenant}_${name}`. |
| `kind` | `ducklake` (DuckLake catalog backed by Postgres, the default), `duckdb-file` (a standalone `.duckdb` file), or `memory` (no persistent catalog; useful for federation-only databases). |
| `dataPath` | Where DuckLake writes Parquet. A filesystem path, or an `s3://` / `az://` / `abfss://` URI for object storage. Defaults are derived from the global metastore when omitted. |
| `metastore` | Optional per-database overrides of the Postgres connection (`pgHost`, `pgPort`, `pgUser`, `pgPassword`, `dbName`, `schemaName`). Empty inherits the global defaults. |
| `objectStore` | Optional S3 credentials when `dataPath` is an object-store URI (endpoint, key, secret, region). |
| `defaultDatabase` / `defaultSchema` | The catalog and schema unqualified table names resolve against for sessions on this database. `defaultSchema` must differ from the database name to avoid ambiguous two-part identifiers in DuckDB. |

For object storage on S3-compatible backends and the `QOD_S3_*` keys, see the Docker deployment page and the [Configuration reference](/reference/configuration).

### List and delete

```bash
# List the databases under a tenant
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/database/list?tenant=acme'

# Delete a database (remove its pools first, else 409 has_pools)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/database/delete \
  -H 'Content-Type: application/json' -d '{"tenant":"acme","name":"sales"}'
```

Deleting a database removes the `qodstate_tenant_db` row. The underlying Postgres database and any object-store Parquet are not erased by the API; reclaim them separately if you no longer need the data.

## Next step

With a tenant and a database in place, create a pool of nodes against that database so clients can connect and query. See the "Pools and cohorts" page.
