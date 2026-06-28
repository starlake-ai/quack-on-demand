---
id: lifecycle-config
title: Lifecycle and config
---

Attach external catalogs, rotate secrets, back up and restore configuration, and decommission cleanly. Each playbook below follows the same structure: goal, prerequisites, step-by-step UI flow with screenshots, the equivalent REST call, and how to verify success.

## Attach an external catalog

**Goal**: Register an external data source (Postgres, S3, Iceberg, or anything a DuckDB extension can `ATTACH`) as a federated catalog on a tenant database. Clients can then query remote data through the same FlightSQL session and ACL model as native DuckLake tables.

**Prerequisites**:

- The tenant and database already exist. See [Tenants and databases](/operating/tenants-databases).
- The target external system is reachable from the Quack nodes at startup time.
- An admin session token or API key in `$TOKEN`. The manager runs at `http://localhost:20900` (the default dev address; adjust the host and port for other environments).

**Steps (UI)**:

1. Go to **Tenants** and select the target tenant.
2. Open the **Databases** tab and find the target database.
3. Click **Federation** to open the federated-source panel.

   ![Federation panel](/img/ui/federation.png)

4. Click **Add source**. Enter the alias (e.g. `fedpg`), an optional description, and the `setupSql`. Use `{{alias}}` in the `ATTACH` clause as the catalog name, and `{{secret.NAME}}` wherever a credential value should appear. The manager substitutes both placeholders before the SQL reaches a node.
5. Click **Add secret** and enter the secret name (e.g. `PG_PWD`) and its inline value (or an `externalRef` for an externally-managed secret).
6. Save. The source takes effect the next time a node starts in this pool.

**REST equivalent**:

```bash
# Register the federated source
curl -X POST -H "X-API-Key: $TOKEN" -H 'Content-Type: application/json' \
  "http://localhost:20900/api/tenants/acme/tenant-dbs/acme_fed/federated-sources" \
  -d '{
    "alias": "fedpg",
    "description": "Prod warehouse Postgres",
    "setupSql": "INSTALL postgres; LOAD postgres; CREATE OR REPLACE SECRET fedpg_sec (TYPE POSTGRES, HOST '\''pg.prod'\'', PORT 5432, DATABASE '\''warehouse'\'', USER '\''svc_qod'\'', PASSWORD '\''{{secret.PG_PWD}}'\''); ATTACH '\'''\'' AS {{alias}} (TYPE POSTGRES, SECRET fedpg_sec, READ_ONLY);"
  }'

# Add the secret referenced by {{secret.PG_PWD}}
curl -X PUT -H "X-API-Key: $TOKEN" -H 'Content-Type: application/json' \
  "http://localhost:20900/api/tenants/acme/tenant-dbs/acme_fed/federated-sources/fedpg/secrets" \
  -d '{"name": "PG_PWD", "value": "hunter2"}'
```

Placeholders in `setupSql`:
- `{{alias}}` is replaced with the source's `alias` field.
- `{{secret.NAME}}` is replaced with the resolved value of the secret named `NAME`.

**Verify**: Run a query that references the alias (e.g. `SELECT * FROM fedpg.public.orders LIMIT 5`). Open the catalog browser to confirm the alias and its schemas appear.

![Catalog browser](/img/ui/catalog.png)

**Related**: [Federation](/operating/federation), [Catalogs](/concepts/catalogs).

---

## Rotate a federated secret

**Goal**: Replace a stored credential (e.g. a rotated database password) without touching the `setupSql`, then apply the new value to running nodes.

**Prerequisites**:

- The federated source exists and has at least one value-backed secret.
- You understand the lifecycle rule: secret changes take effect on the next node spawn only. Already-running nodes keep the credential they received at startup until they exit. Recycle the pool to apply the change immediately.

**Steps (UI)**:

1. Go to **Tenants** -> **Databases** -> **Federation** for the relevant database.
2. Find the federated source and open its secret list.
3. Click the secret row and enter the new value. The `PUT` is an upsert; only the value field changes, not the `setupSql`.
4. Go to the **Pools** tab and select the pool that uses this database.
5. Click **Drain** to stop accepting new queries, then wait for in-flight statements to complete. Click **Scale** to bring nodes back up (or use **Force** if immediate replacement is acceptable). New nodes start with the updated secret value injected into their `setupSql`.

**REST equivalent**:

```bash
# Re-PUT the secret with the new value (same endpoint as the initial add)
curl -X PUT -H "X-API-Key: $TOKEN" -H 'Content-Type: application/json' \
  "http://localhost:20900/api/tenants/acme/tenant-dbs/acme_fed/federated-sources/fedpg/secrets" \
  -d '{"name": "PG_PWD", "value": "hunter2"}'
```

After updating the secret, recycle the pool so new nodes pick up the change. See [Federation - lifecycle](/operating/federation#lifecycle) for the exact spawn-time substitution rules.

**Verify**: After the pool recycles, run a federated query. It should succeed with the new credential. A failure at startup (`unresolved secret` or a DuckDB connect error in the supervisor log) means the new value is wrong or the secret name is mismatched.

**Related**: [Federation](/operating/federation).

---

## Back up and restore

**Goal**: Export the entire control-plane configuration to a YAML manifest for backup, version control, or environment cloning, then re-import an edited file.

Note: the Config/Manifest tab and the manifest endpoints are restricted to superusers. A tenant-scoped session receives `403 superuser_required`.

**Prerequisites**:

- Signed in as a superuser (the **Config** tab in the navigation is only visible to superusers).

**Steps (UI)**:

1. Click **Config** in the top navigation bar.
2. Select the **Manifest** section.
3. Click **Download YAML** to export the current configuration. The file contains all tenants, databases, pools, federated sources, roles, groups, and users. Secret values are written as `***REDACTED***`; user passwords are omitted entirely.
4. Edit the file as needed. Leave `***REDACTED***` in place for secrets you do not intend to change; the importer reuses the existing stored value for those entries.
5. Click **Import** and upload the edited YAML.

![Config page](/img/ui/config.png)

**REST equivalent**:

```bash
# Export - returns application/yaml; superuser only
curl -sS -H "X-API-Key: $TOKEN" "http://localhost:20900/api/manifest/export" > manifest.yaml

# Import after editing
curl -sS -H "X-API-Key: $TOKEN" -X POST "http://localhost:20900/api/manifest/import" \
  -H 'Content-Type: text/plain' --data-binary @manifest.yaml
```

The import response is a JSON count: `{"tenants":2,"tenantDbs":3,"pools":4,...}`. The import validates the whole document before writing anything; on failure it returns `400` and changes nothing.

**Verify**: After import, check **Tenants** and **Users** in the UI to confirm the expected tenants, pools, and users are present. If you cloned to a new environment, run a sample query through the FlightSQL edge to confirm the pools came up correctly.

**Related**: [Manifest backup and restore](/operating/manifest).

---

## Decommission

**Goal**: Cleanly remove a pool, a tenant, or individual RBAC objects (users, roles, groups) from the control plane, freeing resources and preventing stale access.

**Prerequisites**:

- To delete a tenant, all of its pools must already be deleted. Attempting to delete a tenant that still has pools returns an error.
- Deleting a pool with `force: true` stops nodes immediately; outstanding queries fail. Use **Drain** first if you need a graceful stop.

**Steps (UI)**:

1. **Delete a pool**: Go to **Tenants**, select the tenant, open the **Pools** tab, select the pool, and click **Delete**. This stops all nodes and removes the pool from the registry. To stop nodes temporarily without removing the pool, click **Drain** instead.
2. **Delete a tenant**: Once all pools are removed, go to **Tenants**, select the tenant, and click **Delete**.
3. **Remove a user, role, or group**: Go to **Users**, select the tenant scope at the top, find the object on the appropriate tab, and click **Delete**.

**REST equivalent**:

```bash
# Delete a pool: stops nodes AND removes the pool from the registry
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/pool/delete \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","pool":"sales","force":true}'

# Delete a tenant (must have no pools first)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/tenant/delete \
  -H 'Content-Type: application/json' -d '{"name":"acme"}'
```

**Verify**: The pool or tenant no longer appears in the **Tenants** or **Nodes** lists. A user removed from the RBAC graph can no longer authenticate against the tenant or run queries.

**Related**: [Tenants and databases](/operating/tenants-databases), [Administering access](/operating/rbac-admin).
