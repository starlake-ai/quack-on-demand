# REST API & Admin UI Reference

This document describes the control-plane REST API and Admin UI routes for Quack on Demand.

## REST API

Every endpoint under `/api/*` is admitted via either path:

- **`X-API-Key` header** - either the static `QOD_API_KEY` (set in the manager env; typical for CLI / CI) or a session JWT returned in the `LoginResponse.token` field.
- **`qod_session` cookie** - set by `POST /api/auth/login` as `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/api`. The browser auto-attaches it on subsequent same-origin requests.

The open endpoints (no auth required) are `GET /health` and `GET /api/config/client` (used by the UI to discover the FlightSQL host/port before login). The React SPA under `/ui/*` is open at the network layer - every authenticated route inside the SPA checks the session via `/api/auth/whoami` before rendering.

Every RBAC mutation also enforces tenant scope: a tenant-A admin session that targets tenant B (by URL path, query param, or JSON body field) is rejected with `403 tenant_forbidden`. Superuser sessions and the static API key bypass the gate. Unknown ids return `404` so that probing cannot distinguish "exists in another tenant" from "doesn't exist".

### Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/auth/login`            | mint a session JWT (sets `qod_session` cookie; body also carries `token` for CLI) |
| `POST` | `/api/auth/logout`           | revoke (cookie OR header) |
| `GET`  | `/api/auth/whoami`           | verify session (cookie OR header) |
| `GET`  | `/api/tenant/list`           | list tenants |
| `POST` | `/api/tenant/create`         | create a tenant (superuser only) |
| `POST` | `/api/tenant/setAuth`        | update tenant auth provider (per-tenant OIDC) |
| `POST` | `/api/tenant/setDisabled`    | toggle tenant active flag |
| `POST` | `/api/tenant/delete`         | delete a tenant (no pools left; cascades through tenant-dbs) |
| `GET`  | `/api/database/list?tenant=` | list a tenant's databases |
| `POST` | `/api/database/create`       | create a tenant-db |
| `POST` | `/api/database/delete`       | delete a tenant-db (no pools left) |
| `GET`  | `/api/pool/list`             | list pools with live node metrics |
| `POST` | `/api/pool/create`           | spin up a pool |
| `POST` | `/api/pool/scale`            | scale up/down with role redistribution |
| `POST` | `/api/pool/stop`             | tear down |
| `POST` | `/api/pool/setDisabled`      | toggle the pool's accept-handshake flag |
| `POST` | `/api/node/setMaxConcurrent` | per-node concurrency cap |
| `POST` | `/api/node/quarantine`       | mark a node unhealthy |
| `GET`  | `/api/node/statements`       | recent statement-history ring buffer (filtered to manageable tenants) |
| `POST` | `/api/user/create`           | create a user (RBAC) |
| `POST` | `/api/user/update`           | update password / role |
| `POST` | `/api/user/delete`           | delete user |
| `GET`  | `/api/user/list?tenant=`     | list users (clamped to manageable tenants) |
| `GET`  | `/api/user/{id}/effective`   | EffectiveSet view |
| `POST` | `/api/role/create`           | role CRUD |
| `POST` | `/api/role/delete`           | |
| `GET`  | `/api/role/list?tenant=`     | |
| `POST` | `/api/role/permission/grant` | table grant (catalog/schema/table/verb in {RO,RW,DDL,ALL}) |
| `POST` | `/api/role/permission/revoke`| |
| `GET`  | `/api/role/permission/list?roleId=` | |
| `POST` | `/api/group/create`          | group CRUD |
| `POST` | `/api/group/delete`          | |
| `GET`  | `/api/group/list?tenant=`    | |
| `POST` | `/api/membership/user-role/{add,remove}`  | direct user → role edge |
| `POST` | `/api/membership/user-group/{add,remove}` | user → group edge |
| `POST` | `/api/membership/group-role/{add,remove}` | group → role edge |
| `GET`  | `/api/membership/group-role/list?groupId=` | |
| `POST` | `/api/pool/permission/grant` | pool-access grant on (user OR group) |
| `POST` | `/api/pool/permission/revoke`| |
| `GET`  | `/api/pool/permission/list?tenant=&userId=&groupId=` | |
| `*`    | `/api/tenants/{t}/tenant-dbs/{td}/federated-sources*` | federation CRUD + secrets |
| `GET`  | `/api/manifest/export`       | YAML snapshot of the whole control plane (superuser only) |
| `POST` | `/api/manifest/import`       | YAML import (superuser only) |
| `GET`  | `/api/config/client`         | discovery: edge host/port/TLS (open) |
| `GET`  | `/api/config/server`         | resolved server config (superuser only) |
| `GET`  | `/health`                    | liveness + pool/node counts (open) |

`POST /api/node/setRole` and `POST /api/node/restart` were removed in mid-2026 - they were stubs that lied about persisting state. Use `POST /api/pool/scale` to rebalance role distribution; use `POST /api/node/quarantine` to drain a node without killing it.

---

## Admin UI Pages

The React admin console is served at `/ui/` and is admin-role gated. Login sets the `qod_session` cookie; logout clears it. The UI never reads or writes a token in `localStorage`.

| Page | What it shows |
|---|---|
| **Login** | Username + password + optional tenant. Empty tenant = system / superuser realm. |
| **Tenants** | List + create + delete tenants, per-tenant OIDC config |
| **/tenant/:tenant** | Tenant detail · databases · pools · RBAC editor (roles / groups / users / pool grants) |
| **/pool/:tenant/:tenantDb/:pool** | Pool nodes, JDBC/ODBC/ADBC connection strings, scale/stop |
| **/nodes** | Live cluster dashboard: per-node `inFlight`, `totalServed`, EWMA latency, role + health badges, per-tenant filter, auto-refresh every 2s |
| **/users** | Cross-tenant user list (filtered to your manageable tenants) |
| **/catalog/...** | DuckLake catalog browser per tenant-db |
| **/config** | Resolved server config + manifest import/export (superuser only) |