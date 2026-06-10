# Configuration & Operations Guide

This document describes how to configure the Quack on Demand manager and contains operational notes for running the service in production.

## Configuration Reference

Every scalar configuration key in `src/main/resources/application.conf` can be overridden by a matching environment variable starting with `SL_QUACK_*` (or `PROXY_*` for specific FlightSQL edge settings).

### Most Common Overrides

| Setting | Environment Variable | Default | Description |
|---|---|---|---|
| Manager REST port | `SL_QUACK_ON_DEMAND_PORT` | `20900` | Port for the React UI and HTTP control plane API |
| FlightSQL edge port | `PROXY_PORT` | `31338` | Port for Arrow Flight SQL client connections |
| FlightSQL TLS | `PROXY_TLS_ENABLED` | `true` | Toggle TLS. Automatically generates a self-signed cert if missing |
| State backend | `SL_QUACK_STATE_STORAGE` | `postgres` | Set to `postgres` or `file` |
| Metastore host | `SL_QUACK_PG_HOST` | `localhost` | Postgres metastore host for catalog and control plane state |
| Metastore database | `SL_QUACK_PG_DBNAME` | `tpch` | Database name on the Postgres instance |
| Static admin key | `SL_QUACK_API_KEY` | *unset* | Global API Key for REST endpoints. If unset, API is open! |
| Admin usernames | `SL_QUACK_ADMIN_USERNAME` | `admin@localhost.local,admin` | Comma-separated admin usernames |
| Admin password | `SL_QUACK_ADMIN_PASSWORD` | `admin` | Password for the default admin user |
| Enable DB auth | `SL_QUACK_AUTH_DB_ENABLED` | `false` | Enable password-based database authentication |
| Enable ACL | `SL_QUACK_ACL_ENABLED` | `false` | Enable statement-level ACL validation |

For the full configuration surface, including configuring pluggable identity providers (OIDC/Keycloak/Google), session timeouts, and Kubernetes backend settings, please consult the inline documentation in `src/main/resources/application.conf`.

---

## Operational Notes & Production Gotchas

Before running Quack on Demand in production, ensure you understand the following defaults and architectural behaviors:

### 1. Default Administrator Credentials
> [!WARNING]
> The FlightSQL edge and Admin UI seed a default administrator user (`admin@localhost.local` with password `admin`).
> **You must rotate this password using `SL_QUACK_ADMIN_PASSWORD` before exposing the manager and edge to the public network.**

### 2. REST API Default Security
> [!CAUTION]
> By default, the control plane REST API (`/api/...`) does not require authentication if `SL_QUACK_API_KEY` is not set. A loud warning will be logged at startup if you run in this mode.
> Ensure you configure `SL_QUACK_API_KEY` or restrict access to `localhost` in production.

### 3. Coarse-Grained DML Grants in ACL Mode
> [!NOTE]
> Currently, table-level permissions only support fine-grained checks for read operations (`SELECT`).
> Write operations (DML like `INSERT`/`UPDATE`/`DELETE` and DDL like `CREATE`/`DROP`) are blocked by default unless the principal holds a wildcard `ALL` grant.

### 4. Kubernetes Reconciliation
> [!NOTE]
> Under local execution, dead child processes are automatically detected (via port and PID probes) and respawned.
> In Kubernetes environments, the runtime delegates container health to the API server's liveness probes. Implementing deeper pod-status reconciliation requires wiring `KubernetesQuackBackend.discoverExisting()` directly to the apiserver.

### 5. Edge Session Caching Lag
> [!IMPORTANT]
> To maintain low-latency query routing, authentication validation is cached and only re-validated at the TTL boundary (`sessionTtlSec`, which defaults to `1h`).
> If a token is revoked in your identity provider (OIDC/JWT), it may continue to be authorized for up to one TTL window. Shrink the TTL configuration or restart the manager for immediate revocation.
