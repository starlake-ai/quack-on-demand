---
id: federation
title: Federation
---

Federation attaches external catalogs (Postgres, MySQL, S3/Iceberg, or anything a DuckDB extension can `ATTACH`) to a database under an alias, so clients query remote data through the same FlightSQL session and the same access-control model as native DuckLake tables. Sources are scoped to a tenant-db: each `qodstate_tenant_db` carries its own set of federated sources.

Provision the tenant and database first (see "Tenants and databases"). REST calls authenticate with `X-API-Key` (a static `QOD_API_KEY` or an admin session token, as on the other operator pages); the examples below assume the token is in `$TOKEN` and the manager base URL in `$MGR` (e.g. `http://localhost:20900`).

:::note Path uses the full database name
The federated-source endpoints are under `/api/tenants/{tenant}/tenant-dbs/{tenantDb}/federated-sources`, where `{tenantDb}` is the **full** database name `${tenant}_${suffix}` (for example `acme_fed`), not the suffix you passed to `database/create` (`fed`).
:::

## Choosing a database kind for federation

Any database kind can carry federated sources, but a common pattern is a `memory` database that serves *only* federated catalogs, with its default catalog pointed at a federated alias:

```bash
curl -sS -H "X-API-Key: $TOKEN" -X POST "$MGR/api/database/create" \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","name":"fed","kind":"memory",
       "defaultDatabase":"fedpg","defaultSchema":"public"}'
```

This creates the database `acme_fed` with no persistent DuckLake catalog; sessions resolve unqualified names against the `fedpg` federated alias. See "Tenants and databases" for the full `kind` list.

## Register a federated source

A source is an alias plus the `setupSql` that DuckDB runs at node startup to install the extension, create any secret, and `ATTACH` the catalog.

```bash
curl -sS -H "X-API-Key: $TOKEN" -X POST \
  "$MGR/api/tenants/acme/tenant-dbs/acme_fed/federated-sources" \
  -H 'Content-Type: application/json' \
  -d '{
    "alias": "fedpg",
    "description": "Prod warehouse Postgres",
    "setupSql": "INSTALL postgres; LOAD postgres; CREATE OR REPLACE SECRET fedpg_sec (TYPE POSTGRES, HOST '\''pg.prod'\'', PORT 5432, DATABASE '\''warehouse'\'', USER '\''svc_qod'\'', PASSWORD '\''{{secret.PG_PWD}}'\''); ATTACH '\'''\'' AS {{alias}} (TYPE POSTGRES, SECRET fedpg_sec, READ_ONLY);"
  }'
```

The request body fields are `alias`, `setupSql`, optional `description`, and `disabled` (default false). Two placeholders are substituted before the SQL runs on a node:

- `{{alias}}` becomes the source's `alias`.
- `{{secret.NAME}}` becomes the resolved value of the secret named `NAME` (see below).

Include `READ_ONLY` in the `ATTACH` for read-only federation; read-only is enforced at attach time by DuckDB, not by the ACL validator.

List, fetch, and delete sources:

```bash
curl -sS -H "X-API-Key: $TOKEN" \
  "$MGR/api/tenants/acme/tenant-dbs/acme_fed/federated-sources"
curl -sS -H "X-API-Key: $TOKEN" \
  "$MGR/api/tenants/acme/tenant-dbs/acme_fed/federated-sources/fedpg"
curl -sS -H "X-API-Key: $TOKEN" -X DELETE \
  "$MGR/api/tenants/acme/tenant-dbs/acme_fed/federated-sources/fedpg"
```

## Secrets

A secret feeds a `{{secret.NAME}}` placeholder. Upsert one with either an inline `value` (stored in Postgres) or an `externalRef` (resolved from an external store at node startup):

```bash
# Value-backed (stored in the control plane)
curl -sS -H "X-API-Key: $TOKEN" -X PUT \
  "$MGR/api/tenants/acme/tenant-dbs/acme_fed/federated-sources/fedpg/secrets" \
  -H 'Content-Type: application/json' \
  -d '{"name":"PG_PWD","value":"hunter2"}'

# External-reference-backed (resolved from a secret store)
curl -sS -H "X-API-Key: $TOKEN" -X PUT \
  "$MGR/api/tenants/acme/tenant-dbs/acme_fed/federated-sources/fedpg/secrets" \
  -H 'Content-Type: application/json' \
  -d '{"name":"PG_PWD","externalRef":"vault:secret/data/qod/fedpg#password"}'
```

Secret values are always redacted on reads: a value-backed secret comes back as `***REDACTED***`, while `externalRef` is returned as-is. Delete a secret with `DELETE .../federated-sources/fedpg/secrets/PG_PWD`.

### Secret resolvers

The resolver backend is selected by `QOD_FEDERATION_SECRET_STORE`. The default is `dispatch`, which routes per secret by `externalRef` prefix (inline `value` → `postgres`, `env:` → env, `aws-sm:` / `gcp-sm:` / `azure-kv:` / `vault:` → the matching cloud backend). The single-backend modes `postgres` and `env` are also valid. The single-backend modes `aws-sm` / `gcp-sm` / `azure-kv` / `vault` are **refused at config load** because the resolvers are stubs; deployments that use them through `externalRef` should run under `dispatch` instead. The `externalRef` format depends on the backend:

| Backend | `externalRef` format |
|---|---|
| `env` | `env:QOD_SECRET_FOO` |
| `aws-sm` | `aws-sm:arn:aws:secretsmanager:...` or `aws-sm:name#jsonKey` |
| `gcp-sm` | `gcp-sm:projects/<p>/secrets/<name>/versions/latest` |
| `azure-kv` | `azure-kv:<secretName>` (vault URL from config) |
| `vault` | `vault:secret/data/<path>#<key>` |

The four cloud backends (`aws-sm`, `gcp-sm`, `azure-kv`, `vault`) are still stub resolvers in the current version: under `dispatch` mode the dispatcher keeps them wired so deployments that only consume `postgres` + `env` work without changes; the moment a source's secret carries a stub prefix, the manager raises `NotImplementedError` at node spawn with a message pointing at the supported alternatives. The Admin UI marks the stub options as `(not implemented)` and disables the select. The `postgres` and `env` resolvers are live. See the [Configuration reference](/reference/configuration) for the per-backend config keys.

The substitution that splices a resolved value into `setupSql` is SQL-quote-safe: every `'` in the value is doubled before splicing, so a password like `O'Brien` survives a `PASSWORD '{{secret.PG_PWD}}'` template intact instead of breaking the `ATTACH`. See `FederationBlobBuilderSpec` for the contract.

## Granting access to federated tables

Federated tables are governed by the same RBAC graph as native tables. A `qodstate_role_permission` row on the federated alias (for example `SELECT` on `fedpg.public.orders`) grants access exactly as it would for a DuckLake table. Use the grant flow on the "Administering access" page, naming the federated alias as the catalog. The mechanics are described in the [Access control model](/operating/rbac-model).

Two specifics for federation:

- Reads are authorized by a `SELECT` grant on the alias like any other table.
- Federated writes (INSERT / UPDATE / DELETE against a federated alias) are denied by default; an `ATTACH ... READ_ONLY` enforces read-only at the DuckDB layer regardless.

## Lifecycle

- **Edits take effect on the next node spawn.** Creating, editing, or disabling a source changes the catalog blob that every node spawned *after* the change receives: idle-timeout replacements, manual restarts, scale-up additions, and pool recreation. Nodes already running keep their attached catalogs until they exit, so recycle the pool to apply a change immediately.
- **Boot-time failures are fatal.** If a source's `setupSql` errors at node startup (extension missing, bad credentials, DNS failure), the spawn aborts and the supervisor surfaces the last lines of stderr.
- **Disabled sources** are filtered out when the catalog blob is assembled; there is no live `DETACH` of running nodes. A disabled source's alias stays in the ACL ambiguity guard (see [Table name resolution](/administration/access-control#table-name-resolution)) because already-running nodes keep it attached until the pool recycles.
- **Recycle the pool right after deleting a source.** Deleting a source removes its alias from the ACL ambiguity guard (the guard's attached-catalog set is rebuilt from the control plane, cached for about 60 seconds), but running nodes keep the catalog attached until they exit. In that window the ambiguity guard no longer covers the alias while the engine still binds it catalog-first, so recycle the pool to close the gap.

## Backup and restore

Federated sources and their secrets are included in the whole-control-plane config manifest, exported and re-imported through `GET /api/manifest/export` and `POST /api/manifest/import` (not a federation-specific endpoint). Value-backed secrets are written as `***REDACTED***` in the export, while `externalRef` secrets are written verbatim. On import, federation follows replace-by-alias semantics within each database, and a secret left as `***REDACTED***` (with no `externalRef`) reuses the existing stored value, so a round-trip never requires re-typing passwords. See [Manifest backup and restore](/operating/manifest) for the manifest format and the export/import flow.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `unresolved secret 'X' in source '<alias>'` in the supervisor log | `setupSql` references `{{secret.X}}` but no matching secret row exists | Add the secret with `PUT .../federated-sources/<alias>/secrets` |
| `unsubstituted placeholder` at boot | A malformed placeholder such as `{{secret.X}` (missing brace) | Fix `setupSql` (POST upserts the source) and recycle the pool |
| Client sees `catalog 'fedpg' does not exist` | The pool was not recycled after the source changed | Recreate the pool, or wait for the idle-timeout replacement |
| Client sees `missing SELECT grant on fedpg.public.X` | No ACL grant on the federated alias | Grant `SELECT` on the alias (see "Administering access") |
