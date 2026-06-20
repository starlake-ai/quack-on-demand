# Configuration & Operations Guide

This document describes how to configure the Quack on Demand manager and contains operational notes for running the service in production.

## Configuration Reference

Every scalar configuration key in `src/main/resources/application.conf` can be overridden by a matching `QOD_*` environment variable (or `PROXY_*` for FlightSQL edge keys). The full surface is documented inline in `application.conf` and rendered in the website at `/reference/configuration`.

### Most common overrides

| Setting | Environment variable | Default | Description |
|---|---|---|---|
| Manager REST port | `QOD_ON_DEMAND_PORT` | `20900` | Port for the React UI and HTTP control plane API |
| FlightSQL edge port | `PROXY_PORT` | `31338` | Port for Arrow Flight SQL client connections |
| FlightSQL TLS | `PROXY_TLS_ENABLED` | `true` | Toggle TLS. Automatically generates a self-signed cert if missing |
| Postgres host | `QOD_PG_HOST` | `localhost` | Control-plane + DuckLake catalog Postgres host |
| Postgres database | `QOD_PG_DBNAME` | `qod` | Control-plane database name |
| Static admin key | `QOD_API_KEY` | *(unset)* | Static admin token sent as `X-API-Key`. If unset, the REST namespace is open and Main emits a startup warning. |
| Session JWT secret | `QOD_SESSION_JWT_SECRET` | dev default | HS256 secret used to sign UI session JWTs. **Override in production** - the `application.conf` default is a well-known dev string; Main emits a startup warning if it's not overridden. |
| Session cookie path | `QOD_SESSION_COOKIE_PATH` | `/api` | Path attribute on the `qod_session` cookie. Override behind a path-rewriting reverse proxy. |
| Session cookie Secure | `QOD_SESSION_COOKIE_SECURE` | `auto` | `auto` derives `Secure` from `X-Forwarded-Proto` per request (https → Secure, http or absent → not Secure); `true` / `false` force one value regardless of scheme. |
| Session idle TTL | `QOD_SESSION_IDLE_TTL_SEC` | `28800` (8h) | Absolute JWT lifetime. |
| Admin usernames | `QOD_ADMIN_USERNAME` | `admin@localhost.local,admin` | Comma-separated admin usernames seeded at boot |
| Admin password | `QOD_ADMIN_PASSWORD` | `admin` | Re-hashed on every boot - change + restart rotates |
| Enable ACL | `QOD_ACL_ENABLED` | `false` | Enable per-statement table-level RBAC validation |
| Federation secret store | `QOD_FEDERATION_SECRET_STORE` | `dispatch` | `dispatch` routes per secret by externalRef prefix; `postgres` and `env` are the only single-backend modes that are actually wired (cloud stubs are refused at config load) |

The control-plane store is Postgres-only since 2026-06-12 - the `stateStorage` / `statePath` keys were dropped along with the legacy file-state mode. For the full configuration surface, including pluggable identity providers (OIDC / Keycloak / Google / Azure / AWS), session JWT semantics, and Kubernetes backend settings, see the inline documentation in `src/main/resources/application.conf` or the rendered `website/docs/reference/configuration.md`.

---

## Operational Notes & Production Gotchas

Before running Quack on Demand in production, ensure you understand the following defaults and architectural behaviors:

### 1. Default Administrator Credentials
> [!WARNING]
> The FlightSQL edge and Admin UI seed a default administrator user (`admin@localhost.local` with password `admin`).
> **You must rotate this password using `QOD_ADMIN_PASSWORD` before exposing the manager and edge to the public network.**

### 2. REST API Default Security
> [!CAUTION]
> By default, the control plane REST API (`/api/...`) does not require authentication if `QOD_API_KEY` is not set. A loud warning is logged at startup if you run in this mode.
> Set `QOD_API_KEY` or restrict access to `localhost` in production.

### 3. Session JWT secret
> [!CAUTION]
> The `application.conf` default for `manager.auth.management.sessionJwtSecret` is a well-known dev string. Anyone with the source can forge admin sessions if you don't override it. Set `QOD_SESSION_JWT_SECRET` to a stable random >=32-char value before any non-localhost deployment. Main emits a startup warning when the default is in use.

### 4. Coarse-Grained DML Grants in ACL Mode
> [!NOTE]
> Table-level permissions support `RO | RW | DDL | ALL`. `RO` is fine-grained for `SELECT`; `RW` covers any DML (`INSERT` / `UPDATE` / `DELETE` / `MERGE` / `TRUNCATE`); `DDL` covers `CREATE` / `DROP` / `ALTER`. Use `ALL` for full admin.

### 5. Kubernetes Reconciliation
> [!NOTE]
> Under local execution, dead child processes are automatically detected (via port and PID probes) and respawned.
> In Kubernetes environments, the runtime delegates container health to the API server's liveness probes. `discoverExisting()` reads per-pod Secrets (`qod-token-${nodeId}`) so the manager recovers node bearer tokens on restart without forcing a full pool respawn.

### 6. Edge Session Caching Lag
> [!IMPORTANT]
> To maintain low-latency query routing, FlightSQL authentication validation is cached and only re-validated at the TTL boundary (`sessionTtlSec`, default `1h`).
> If a token is revoked in your identity provider (OIDC/JWT), it may continue to be authorized for up to one TTL window at the edge. Shrink the TTL or restart the manager for immediate revocation. The management-plane JWT cookie has its own absolute exp (`QOD_SESSION_IDLE_TTL_SEC`, default 8h) - rotate `QOD_SESSION_JWT_SECRET` for a hard kill of all UI sessions.
