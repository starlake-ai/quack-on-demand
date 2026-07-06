---
id: audit-log
title: Audit log
---

The manager records a durable, tenant-scoped audit trail of administrative and data-plane activity. When `QOD_TELEMETRY_STORE=postgres` (the default), every event is written to the `qodstate_audit` table in the control-plane Postgres database. When `QOD_TELEMETRY_STORE=none`, nothing is recorded and the Audit page is hidden from the navigation.

## What is captured

Events fall into four families:

| Family | Meaning |
|---|---|
| `control-plane` | Mutating REST operations: tenant, database, pool, user, role, group, membership, node, and federation changes |
| `auth` | Login successes, login failures, logouts, session revocations, and rejected API keys |
| `data-denial` | SQL statements blocked by the ACL validator at the FlightSQL edge |
| `data-write` | DDL and DML statements that completed successfully at the FlightSQL edge |

Read statements (`SELECT`, `SHOW`, `EXPLAIN` without DML) are not audited. They belong to statement history, which is a separate subsystem.

### Action taxonomy

The `action` field is a dotted string. The full set of values emitted by the current implementation is:

**Auth**

| Action | Description |
|---|---|
| `auth.login` | Successful login |
| `auth.login.failure` | Failed login (bad credentials, expired, or unknown user) |
| `auth.logout` | Session logout |
| `auth.revoke` | Explicit session revocation |
| `auth.api-key.failure` | Rejected `X-API-Key` header; rate-limited to one event per source IP per minute |

**Control-plane: tenants and databases**

| Action | Description |
|---|---|
| `tenant.create` | Tenant created |
| `tenant.delete` | Tenant deleted |
| `tenant.auth.update` | Tenant auth provider updated |
| `tenant.setDisabled` | Tenant enabled or disabled |
| `database.create` | Tenant-db created |
| `database.update` | Tenant-db updated |
| `database.delete` | Tenant-db deleted |

**Control-plane: pools**

| Action | Description |
|---|---|
| `pool.create` | Pool created |
| `pool.scale` | Pool scaled up or down |
| `pool.stop` | Pool drained to zero nodes (pool record kept) |
| `pool.delete` | Pool deleted |
| `pool.setDisabled` | Pool enabled or disabled (create-disabled toggle) |
| `pool.setResources` | Pool CPU or memory limits updated |
| `pool.setPodTemplate` | Pool pod template updated (superuser only) |
| `pool.permission.grant` | Pool permission granted to a role |
| `pool.permission.revoke` | Pool permission revoked from a role |

**Control-plane: users, roles, groups, and memberships**

| Action | Description |
|---|---|
| `user.create` | User created |
| `user.update` | User updated |
| `user.delete` | User deleted |
| `role.create` | Role created |
| `role.delete` | Role deleted |
| `role.permission.grant` | Table permission granted on a role |
| `role.permission.revoke` | Table permission revoked from a role |
| `role.rowPolicy.set` | Row-level policy set on a role |
| `role.rowPolicy.delete` | Row-level policy deleted from a role |
| `role.columnPolicy.set` | Column-level policy set on a role |
| `role.columnPolicy.delete` | Column-level policy deleted from a role |
| `group.create` | Group created |
| `group.delete` | Group deleted |
| `membership.user-role.add` | User assigned to role |
| `membership.user-role.remove` | User removed from role |
| `membership.user-group.add` | User added to group |
| `membership.user-group.remove` | User removed from group |
| `membership.group-role.add` | Group assigned to role |
| `membership.group-role.remove` | Group removed from role |

**Control-plane: nodes, statements, federation, and manifest**

| Action | Description |
|---|---|
| `node.quarantine` | Node quarantined (excluded from routing) |
| `node.unquarantine` | Node returned to rotation |
| `node.restart` | Node restarted via the reconcile path |
| `statement.kill` | Best-effort statement kill requested |
| `federation.source.upsert` | Federated source created or updated |
| `federation.source.delete` | Federated source deleted |
| `federation.secret.upsert` | Federation secret created or updated |
| `federation.secret.delete` | Federation secret deleted |
| `manifest.import` | Manifest imported (full control-plane apply) |

**Data-plane**

| Action | Description |
|---|---|
| `sql.denied` | Statement blocked by the ACL validator; detail includes the denied table and missing verb |
| `sql.write` | DML statement completed; detail includes touched tables and duration |
| `sql.ddl` | DDL statement completed; detail includes touched tables and duration |

### Each event row

Every row in `qodstate_audit` carries:

| Field | Values | Notes |
|---|---|---|
| `ts` | ISO-8601 timestamp | Event time |
| `family` | `control-plane`, `auth`, `data-denial`, `data-write` | Coarse grouping |
| `actor` | username | `anonymous` for unauthenticated 401s; `static-key` for API-key callers that cannot be resolved to a user |
| `actor_realm` | `system`, `tenant` | Superuser sessions are `system` |
| `tenant` | tenant id or null | Null for tenant-less events (see below) |
| `action` | dotted verb (see taxonomy above) | |
| `target` | resource identifier or null | Pool key, role id, node id, table name, etc. |
| `outcome` | `ok`, `denied`, `error` | `denied` for scope-gate rejections; `error` for unexpected failures |
| `origin` | `rest`, `flightsql` | Where the event originated |
| `detail` | key-value map | Whitelisted context (see Sanitization) |

## Who sees what

Access to `GET /api/audit/list` follows the same scope rules as every other RBAC read:

- **Superusers** see all rows, including rows where `tenant` is null.
- **Tenant admins** see only rows whose `tenant` equals one of their administrable tenants. Rows with `tenant IS NULL` are not visible to tenant admins.
- **Static-key callers** are treated as superusers.

Rows with `tenant IS NULL` represent events with no tenant scope: anonymous authentication failures, node operations that span tenants, and manifest imports. These are exclusively visible to superusers so tenant admins cannot infer deployment topology from them.

The tenant admin scope is enforced server-side. A tenant admin requesting `?tenant=other-tenant` gets back their own tenant's rows, not an error, so no cross-tenant existence is leaked via differential error codes.

## Retention

The manager runs an hourly background purge that deletes rows older than `QOD_AUDIT_RETENTION_DAYS` (default `90`). The purge runs hourly and deletes all rows older than the cutoff in one statement.

To keep audit records for a longer period:

```bash
QOD_AUDIT_RETENTION_DAYS=365 ./scripts/run-jar.sh
```

To disable retention (keep forever), set `QOD_AUDIT_RETENTION_DAYS=0`. The purge runs but deletes nothing.

In HA mode the hourly purge runs only on the singleton leader (the replica holding the `HaCoordinator` Postgres advisory lock). A failover hands the duty to the next leader on its next tick.

## Sanitization

The `detail` field is built exclusively from whitelisted keys per action. The `AuditEvent` constructor rejects any detail map that contains a key matching `password`, `secret`, `token`, `jwt`, or `credential` (case-insensitive substring match). This is a construction-time guarantee, not a filtering step: no code path can store these values by accident because the constructor throws before any write reaches the store.

Specifically, the following never enter the audit trail:

- Login passwords
- Federation secret values
- Session JWTs
- Full metastore connection maps (which may contain passwords)
- Raw pod template bodies (which may contain paths and credentials)

SQL text in data-plane events (`sql.write`, `sql.ddl`, `sql.denied`) is capped at 500 characters.

## The `none` off switch

Set `QOD_TELEMETRY_STORE=none` to disable the audit subsystem entirely:

- Every `appendAudit` call is a no-op.
- Every `listAudit` call returns empty.
- The journal fiber, the hourly purge duty, and the rollup duties are not started.
- The `qod_journal_dropped_total` counter stays at zero (not recording is intentional, not a drop).
- The Audit entry is hidden from the admin UI navigation. A deep link to the Audit page shows an empty state with a "telemetry is disabled" message.

The `postgres` and `none` values are the only accepted values for `QOD_TELEMETRY_STORE`. Any other value is refused at startup.

## Querying the audit log via curl

```bash
# List the 50 most recent control-plane events (default limit)
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/audit/list?family=control-plane&limit=50' | python3 -m json.tool

# Page through with the keyset cursor
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/audit/list?before=<nextBefore-from-previous-page>'

# Failed logins in a window
curl -sS -H "X-API-Key: $TOKEN" \
  'http://localhost:20900/api/audit/list?action=auth.login.failure&from=2026-07-01T00:00:00Z'
```

### Filter parameters

| Parameter | Description |
|---|---|
| `family` | Filter by event family (`control-plane`, `auth`, `data-denial`, `data-write`) |
| `tenant` | Filter by tenant id (superusers can specify any tenant; tenant admins are pinned to their own) |
| `actor` | Filter by actor username (exact match) |
| `action` | Filter by action string (exact match, e.g. `auth.login.failure`) |
| `q` | Substring match on `target` and `action` |
| `from` | ISO-8601 instant; return only events at or after this time |
| `to` | ISO-8601 instant; return only events before this time |
| `limit` | Number of rows to return (default 50, max 500) |
| `before` | Opaque keyset cursor from `nextBefore` in a prior response; used to fetch the next page of older results |

Results are returned newest-first. The response includes a `nextBefore` cursor when there are more rows to fetch; it is absent on the last page.
