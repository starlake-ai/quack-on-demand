---
id: access-control
title: Grant and revoke access
---

Access control is **off by default** (`acl.enabled=false`). Set `QOD_ACL_ENABLED=true` to enforce it. When ACL is on, every FlightSQL statement is matched against the caller's effective permission set before execution. The model - roles, groups, verbs, and wildcards - is described in [Access control model](/operating/rbac-model).

## Grant a team read access

**Goal:** Let a group of users run SELECT statements against one or more tables in a pool.

**Prerequisites:** A tenant and a pool already exist. You are signed in as a superuser or the tenant's admin.

**Steps (UI):**

1. Open the **Users** section (top nav) and select the target tenant from the tenant selector.
2. Go to the **Roles** tab and click **+ New role**. Give the role a name (for example `bi-readers`). Add a table permission with verb `SELECT` and the target catalog, schema, and table (use `*` to wildcard any field). Save.

   ![Roles tab](/img/ui/rbac-roles.png)

3. Go to the **Groups** tab and click **+ New group**. Name it (for example `bi-team`). Add the `bi-readers` role to the group. Save.

   ![Groups tab](/img/ui/rbac-groups.png)

4. Go to the **Users** tab. For each user who needs access, open their record, assign them to the `bi-team` group, and add a pool grant for the target pool. The pool grant is required for the user to connect to that pool.

   ![Users tab](/img/ui/rbac-users.png)

5. Confirm the pool grant: in the **Users** tab, verify the user's row shows the pool grant for the target pool.

**REST equivalent:**

The snippet below is the minimal REST path: create a role permission for `user:alice` directly (skip the role/group layer when a single user needs a quick one-off grant). Adapt `principal` to `group:bi-team` or `role:bi-readers` for the group-based flow.

```bash
# Grant RO (read-only) on tpch.tpch1.customer to user:alice
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/acl/grant/create \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"acme","principal":"user:alice",
       "catalogName":"tpch","schemaName":"tpch1","tableName":"customer",
       "permission":"RO"}'
```

Principal format is `type:name` - `user:alice`, `group:engineers`, `role:admin`. At validation time the authenticated session expands into `user:<username>` + `group:<g>` per group + `role:<r>`; a grant matches any of them.

**Verify:** See [Verify access](#verify-access) below.

**Related:**
- [Access control model](/operating/rbac-model) - verbs, wildcards, effective-set resolution
- [Administering access](/operating/rbac-admin) - full RBAC CRUD reference

---

## Grant DML or DDL

**Goal:** Let a role run INSERT, UPDATE, DELETE, or schema-altering statements.

**Prerequisites:** A role already exists (or follow the steps above to create one).

**Steps (UI):**

1. Open **Users** > **Roles** tab and click the edit icon on the target role.
2. Add one or more table permissions with the appropriate verb:
   - `INSERT` for inserts
   - `UPDATE` / `DELETE` for row mutations
   - `CREATE` / `DROP` / `ALTER` for DDL
   - `ALL` to cover every verb at once
3. Save. The verbs are collapsed to `Read`, `Write`, or `Ddl` at enforcement time; see [Access control model](/operating/rbac-model) for the exact mapping.

**REST equivalent:**

```bash
# Wildcard ALL for the admin role (NULL catalog/schema/table = any)
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/acl/grant/create \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"acme","principal":"role:admin","permission":"ALL"}'
```

Replace `"permission":"ALL"` with `"permission":"INSERT"` (or any other verb) and add `catalogName`/`schemaName`/`tableName` fields to scope the grant to a specific table.

**Verify:** See [Verify access](#verify-access) below.

**Related:**
- [Access control model](/operating/rbac-model) - verb-to-category mapping and enforcement logic

---

## Revoke access

**Goal:** Remove a user's, group's, or role's access to a pool or table.

**Prerequisites:** The grant you want to remove exists and you know its ID (visible in the UI grant list or from the list endpoint).

**Steps (UI):**

1. Open **Users** and select the tenant.
2. To remove a pool grant: open the user's record in the **Users** tab, find the pool grant row, and click **Remove**.
3. To remove a role from a group: open the **Groups** tab, edit the group, and delete the role assignment.
4. To delete a role permission entirely: open the **Roles** tab, edit the role, and delete the permission row.

**Effect on active sessions:** `invalidateEffectiveCache()` is called on every RBAC mutation in `PoolSupervisor`. This drops the entire in-process EffectiveSet cache immediately, so the revoked grant takes effect on the **next handshake** - there is no TTL window to wait for.

**REST equivalent:**

```bash
# List + delete
curl -sS -H "X-API-Key: $TOKEN" 'http://localhost:20900/api/acl/grant/list?tenant=acme'
curl -sS -H "X-API-Key: $TOKEN" -X POST http://localhost:20900/api/acl/grant/delete/7
```

Replace `7` with the grant ID returned by the list call.

**Verify:** Re-run the list endpoint and confirm the deleted grant no longer appears:

```bash
curl -sS -H "X-API-Key: $TOKEN" 'http://localhost:20900/api/acl/grant/list?tenant=acme'
```

**Related:**
- [Administering access](/operating/rbac-admin) - full RBAC CRUD reference

---

## Verify access

**Goal:** Confirm that a user sees exactly the rows and columns the policy intends - no more, no less.

**Prerequisites:** `scripts/adbc.sh` is available (ships in the repo). The tenant, pool, and at least one data table exist.

**Steps (CLI - two-realm diff):**

Run the same query twice: once as the tenant user (policy enforced) and once as the bootstrap superuser (policy bypassed). Diff the outputs to see exactly what the ACL, column policy, or row policy filtered or masked.

```bash
# Query as a tenant user (Basic auth + tenant/pool routing headers).
# --insecure trusts the edge's self-signed dev cert.
scripts/adbc.sh --url grpc+tls://localhost:31338 \
  --user alice --password demo-alice \
  --tenant acme --pool bi --insecure \
  --query "SELECT c_mktsegment, count(*) FROM tpch1.customer GROUP BY 1 ORDER BY 1"

# Same query as the bootstrap superuser (system realm) -- bypasses RLS/CLS,
# so diffing the two outputs shows exactly what a policy filtered or masked.
scripts/adbc.sh --url grpc+tls://localhost:31338 \
  --user root --password demo-root \
  --tenant acme --pool bi --superuser --insecure \
  --query "SELECT c_mktsegment, count(*) FROM tpch1.customer GROUP BY 1 ORDER BY 1"
```

Note: `scripts/adbc.sh` is a CLI tool, not a REST call. It speaks the FlightSQL wire protocol over gRPC. On first run it provisions an ADBC Python venv under `${QOD_ADBC_VENV:-$HOME/.cache/qod-adbc/venv}`; set `PIP_PROXY` if you are behind a proxy.

**Verify:** The diff between the two outputs should match the intended policy - the tenant user's result should contain only the rows and columns the grant allows. If the outputs are identical, check that `QOD_ACL_ENABLED=true` is set; ACL is off by default.

**Related:**
- [Access control model](/operating/rbac-model) - how the effective set is resolved
- [Sessions and transactions](/concepts/sessions-transactions) - session lifecycle and handshake timing
