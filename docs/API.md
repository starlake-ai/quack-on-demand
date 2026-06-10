# REST API & Admin UI Reference

This document describes the control-plane REST API and Admin UI routes for Quack on Demand.

## REST API

All endpoints under `/api/*` require a valid `X-API-Key` (either the static `SL_QUACK_API_KEY` or a UI session token from `/api/auth/login`). `/health` and `/api/config/client` are open; `/ui/*` is open (the React app gates itself).

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/auth/login`           | mint a session token (admin role required) |
| `POST` | `/api/auth/logout`          | revoke the current token |
| `GET`  | `/api/auth/whoami`          | verify session |
| `GET`  | `/api/tenant/list`          | list tenants + effective metastore |
| `POST` | `/api/tenant/create`        | create a tenant |
| `POST` | `/api/tenant/setMetastore`  | patch a tenant's metastore overrides |
| `POST` | `/api/tenant/delete`        | delete a tenant (must have no pools) |
| `GET`  | `/api/pool/list`            | list pools with live node metrics |
| `POST` | `/api/pool/create`          | spin up a pool |
| `POST` | `/api/pool/scale`           | scale up/down with role redistribution |
| `POST` | `/api/pool/stop`            | tear down |
| `POST` | `/api/node/setMaxConcurrent`| per-node concurrency cap |
| `POST` | `/api/node/quarantine`      | mark a node unhealthy |
| `POST` | `/api/node/restart`         | drain + restart a node |
| `GET`  | `/api/acl/grant/list?tenant=…` | list grants |
| `POST` | `/api/acl/grant/create`     | grant `(principal, target, permission)` |
| `POST` | `/api/acl/grant/upload`     | bulk insert |
| `POST` | `/api/acl/grant/delete/:id` | revoke |
| `GET`  | `/api/config/client`        | discovery: edge host/port/TLS (open) |
| `GET`  | `/health`                   | liveness + pool/node counts (open) |

---

## Admin UI Pages

The React admin console is served at `/ui/` and is admin-role gated. It provides the following views:

| Page | What it shows |
|---|---|
| **Login** | Username/password, admin-role gated |
| **/ ** (Tenants) | List + create + delete tenants, effective metastore preview |
| **/tenant/:tenant** | Tenant detail · pools · storage · ACL grants editor |
| **/pool/:tenant/:pool** | Pool nodes, JDBC/ODBC/ADBC connection strings, scale/stop |
| **/nodes** | Live cluster dashboard: per-node `inFlight`, `totalServed`, EWMA latency, role + health badges, per-tenant filter, auto-refresh every 2s |
