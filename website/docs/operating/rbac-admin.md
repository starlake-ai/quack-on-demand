---
id: rbac-admin
title: Administering access
---

This page contains task-oriented recipes for managing users, roles, groups, memberships, and pool grants through the REST API. They require a valid admin credential on every request.

For a description of the underlying data model, the EffectiveSet, pool-access gates, and privilege-escalation rules, see the "Access control model" page.

## Authentication

Every RBAC endpoint admits either of two transports:

- **Static key** — set `QOD_API_KEY` in the manager environment and pass it as `X-API-Key`. Best for CLI scripts and CI.
- **Session token** — `POST /api/auth/login` with `{"username":"...","password":"..."}`. Two ways to use the response:
  - **Cookie** (browser default). The login response sets `qod_session=<jwt>` as an HttpOnly Secure SameSite=Lax cookie; the browser auto-attaches it on subsequent same-origin calls. JavaScript cannot read or send it manually — the `/api/auth/whoami` and `/api/auth/logout` endpoints accept the cookie automatically.
  - **Header** (CLI). The JSON body still contains a `token` field; pass it as `X-API-Key: $TOKEN` for non-browser callers.

## Tenant scope on every endpoint

Every RBAC handler checks the caller's session scope before mutating. A tenant-A admin session calling a tenant-B endpoint (resource id, query, or body field) gets:

```json
{ "error": "tenant_forbidden",
  "message": "session has no admin grant on tenant 't-...'" }
```

with HTTP `403`. Superuser and static-key sessions bypass the gate. Unknown ids return `404` (not `403`) so a probe cannot distinguish "exists in another tenant" from "doesn't exist at all". The pattern covers id-only endpoints (`/role/delete`, `/user/delete`, `/group/delete`, `/role/permission/revoke`, `/pool/permission/revoke`, all 6 membership ops, the per-user `/effective`) — those resolve the owning tenant from the resource before applying the check.

```bash
TOKEN=$(curl -sS -X POST http://localhost:20900/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' \
  | jq -r .token)
```

The examples below use `$TOKEN` as shorthand for whichever value you choose.

---

## 1. Create a role and grant a table permission

A role bundles one or more table permissions. First create the role, then attach grants to it.

**Create a role**

```bash
curl -sS -X POST http://localhost:20900/api/role/create \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","name":"analyst","description":"Read-only analyst role"}'
```

Response includes the generated `id` field. Copy it for the next step.

```json
{"id":"r-7a2b...","tenantId":"...","name":"analyst","description":"Read-only analyst role","createdAt":"..."}
```

**Grant a table permission to the role**

Request body fields: `roleId` (required), `catalog` (default `*`), `schema` (default `*`), `table` (default `*`), `verb` (required; one of `RO`, `RW`, `DDL`, `ALL`). `RO` grants read-only, `RW` grants read + any DML (INSERT/UPDATE/DELETE/MERGE/TRUNCATE), `DDL` grants CREATE/DROP/ALTER, `ALL` grants everything.

```bash
curl -sS -X POST http://localhost:20900/api/role/permission/grant \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "roleId": "<role-id>",
    "catalog": "tpch",
    "schema": "tpch1",
    "table": "customer",
    "verb": "RO"
  }'
```

To grant access to every table in a tenant (wildcard), omit `catalog`, `schema`, and `table` (they default to `*`):

```bash
curl -sS -X POST http://localhost:20900/api/role/permission/grant \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"roleId":"<role-id>","verb":"RO"}'
```

In the admin UI: open the tenant detail page, navigate to the Roles tab, create a role, then use the permission editor to add grants.

---

## 2. Create a group and attach a role

Groups collect multiple users under a shared set of role assignments.

**Create a group**

```bash
curl -sS -X POST http://localhost:20900/api/group/create \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"acme","name":"data-team","description":"Data analysts group"}'
```

**Attach a role to the group**

```bash
curl -sS -X POST http://localhost:20900/api/membership/group-role/add \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"groupId":"<group-id>","roleId":"<role-id>"}'
```

This endpoint is idempotent: calling it multiple times with the same pair produces no error and no duplicate row.

To remove the assignment:

```bash
curl -sS -X POST http://localhost:20900/api/membership/group-role/remove \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"groupId":"<group-id>","roleId":"<role-id>"}'
```

In the admin UI: open the tenant detail page, navigate to the Groups tab, create a group, then use the role assignment controls to link roles.

---

## 3. Create a user

**Tenant-scoped user**

A tenant-scoped user can only connect to pools belonging to their tenant. The `role` field controls the admin UI role (`user` or `admin`); it is not an RBAC role in the access-control sense.

```bash
curl -sS -X POST http://localhost:20900/api/user/create \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "tenant": "acme",
    "username": "alice",
    "password": "s3cr3t",
    "role": "user"
  }'
```

**Superuser (tenant null)**

A superuser has `tenant: null`. Superusers bypass the pool-access gate and the per-statement ACL gate entirely. Only an existing superuser may create another superuser; a tenant-scoped admin attempting this call receives a 403.

```bash
curl -sS -X POST http://localhost:20900/api/user/create \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "username": "ops-admin",
    "password": "str0ng!",
    "role": "admin"
  }'
```

Omitting `tenant` is equivalent to passing `"tenant": null`.

In the admin UI: open the Users page, click "Create user", fill in the form. The superuser option appears only when the logged-in user is themselves a superuser.

---

## 4. Add a user to a role and to a group

These endpoints are idempotent: adding an already-existing membership returns 204 with no error.

**User to role**

```bash
curl -sS -X POST http://localhost:20900/api/membership/user-role/add \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"<user-id>","roleId":"<role-id>"}'
```

**User to group**

```bash
curl -sS -X POST http://localhost:20900/api/membership/user-group/add \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"<user-id>","groupId":"<group-id>"}'
```

To remove either membership, call the corresponding `.../remove` path with the same body.

In the admin UI: on the user detail row, use the role and group assignment controls.

---

## 5. Grant pool access

A pool permission allows a user or a group to open FlightSQL connections to a specific pool. Pass exactly one of `userId` or `groupId`. Set `poolId` to a specific pool's `id` (the UUID returned in `PoolResponse.id`) or omit it to grant access to every pool in the tenant.

**Grant a specific pool to a user**

```bash
curl -sS -X POST http://localhost:20900/api/pool/permission/grant \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "tenant": "acme",
    "poolId": "<pool-uuid>",
    "userId": "<user-id>"
  }'
```

**Grant all pools in a tenant to a group**

```bash
curl -sS -X POST http://localhost:20900/api/pool/permission/grant \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "tenant": "acme",
    "groupId": "<group-id>"
  }'
```

Omitting `poolId` (or passing `null`) grants access to every current and future pool in the tenant.

**Revoke a pool grant**

Use the `id` from the grant response:

```bash
curl -sS -X POST http://localhost:20900/api/pool/permission/revoke \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"id":"<grant-id>"}'
```

**List pool grants**

All three query parameters are optional; any combination filters the results:

```bash
curl -sS "http://localhost:20900/api/pool/permission/list?tenant=acme&userId=<user-id>" \
  -H "X-API-Key: $TOKEN"
```

In the admin UI: on the pool detail page, use the "Access" tab to add or remove user and group grants.

---

## 6. Inspect a user's effective permissions

The effective-permissions endpoint returns the full closure of roles, groups, pool grants, and table permissions for a given user without requiring you to trace the graph manually.

```bash
curl -sS "http://localhost:20900/api/user/<user-id>/effective" \
  -H "X-API-Key: $TOKEN"
```

Example response shape:

```json
{
  "user": {"id":"...","tenant":"acme","username":"alice","role":"user",...},
  "roles": [{"id":"...","name":"analyst",...}],
  "groups": [{"id":"...","name":"data-team",...}],
  "pools": [{"id":"...","tenantId":"...","poolId":"<pool-uuid>",...}],
  "tablePerms": [
    {"id":"...","roleId":"...","catalogName":"tpch","schemaName":"tpch1","tableName":"customer","verb":"RO",...}
  ]
}
```

`tablePerms` lists every permission reachable through the user's direct roles and through roles inherited via group membership. This is the set the ACL gate evaluates at statement time.

In the admin UI: click a user in the Users page to open the effective-permissions panel.

---

## Reference: list endpoints

| Resource | Path |
|---|---|
| Users | `GET /api/user/list?tenant=<name>` (omit `tenant` to list all users including superusers) |
| Roles | `GET /api/role/list?tenant=<name>` |
| Role permissions | `GET /api/role/permission/list?roleId=<id>` |
| Groups | `GET /api/group/list?tenant=<name>` |
| Group role memberships | `GET /api/membership/group-role/list?groupId=<id>` |
| Pool permissions | `GET /api/pool/permission/list?tenant=<name>&userId=<id>&groupId=<id>` |
