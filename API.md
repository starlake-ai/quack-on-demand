# REST API

The Quack on Demand manager exposes a control-plane HTTP API at
`http://<manager>:20900/api/*`. The matching admin UI at `/ui/`
calls these endpoints; you can drive everything from `curl` /
your favorite HTTP client too.

## Authentication

All endpoints under `/api/*` require a valid `X-API-Key` header. Two
ways to mint one:

- **Static key** - set `QOD_API_KEY` in the manager's env. Send the
  same value as `X-API-Key` on every request. Good for CI / scripts.
- **Session token** - `POST /api/auth/login` with
  `{"username":"...","password":"..."}` returns `{token, role, ...}`.
  Send the token as `X-API-Key` on subsequent calls.
  `POST /api/auth/logout` revokes it.

Open endpoints (no key required):

- `GET /health` - liveness + pool/node counts
- `GET /api/config/client` - edge host / port / TLS for client bootstrapping
- `GET /metrics` - Prometheus scrape (disabled when `metrics.sink` pushes
  to CloudWatch / Azure Monitor / GCP Cloud Monitoring instead)
- `/ui/*` - the React SPA gates itself

```bash
TOK=$(curl -sS -X POST -H 'Content-Type: application/json' \
   -d '{"username":"admin","password":"admin"}' \
   http://localhost:20900/api/auth/login | jq -r .token)

curl -sS -H "X-API-Key: $TOK" http://localhost:20900/api/tenant/list
```

## Endpoints

### Auth

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/auth/login`           | mint a session token (admin role required) |
| `POST` | `/api/auth/logout`          | revoke the current token |
| `GET`  | `/api/auth/whoami`          | verify session |

### Tenants + pools + nodes

| Method | Path | Purpose |
|---|---|---|
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

### Users + RBAC graph

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/api/user/list?tenant=...`   | list users (omit `tenant` for every user incl. superusers) |
| `POST` | `/api/user/create`          | create a `(tenant?, username, password, role)` principal; `tenant=null` = superuser |
| `POST` | `/api/user/update`          | rotate password / role |
| `POST` | `/api/user/delete`          | delete a user (cascades memberships + pool grants) |
| `GET`  | `/api/user/:id/effective`   | closure of roles / groups / table permissions / pool grants for one user |
| `GET`  | `/api/role/list?tenant=...`   | list roles in a tenant |
| `POST` | `/api/role/create`          | create a role |
| `POST` | `/api/role/delete`          | delete a role |
| `GET`  | `/api/role/permission/list?roleId=...` | list table permissions attached to a role |
| `POST` | `/api/role/permission/grant`| attach `(catalog, schema, table, verb)` to a role (`*` wildcard, `verb in SELECT/INSERT/UPDATE/DELETE/ALL`) |
| `POST` | `/api/role/permission/revoke` | detach a single permission row |
| `GET`  | `/api/group/list?tenant=...`  | list groups |
| `POST` | `/api/group/create`         | create a group |
| `POST` | `/api/group/delete`         | delete a group |
| `POST` | `/api/membership/user/role` | add/remove a user/role membership |
| `POST` | `/api/membership/user/group` | add/remove a user/group membership |
| `POST` | `/api/membership/group/role` | add/remove a group/role membership |
| `GET`  | `/api/pool/permission/list?tenant=&userId=&groupId=` | list pool grants |
| `POST` | `/api/pool/permission/grant`| grant a `(tenant, pool?)` to a user OR a group (`pool=null` = every pool in tenant) |
| `POST` | `/api/pool/permission/revoke` | revoke a pool grant |
| `GET`  | `/api/identity/list?tenantId=...` | list verified-identity to tenant rows |
| `POST` | `/api/identity/create`      | add an `(issuer, externalId)` to tenant mapping |
| `POST` | `/api/identity/delete`      | revoke an identity row |

### Discovery + observability

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/api/config/client`        | edge host / port / TLS (open) |
| `GET`  | `/health`                   | liveness + pool / node counts (open) |
| `GET`  | `/metrics`                  | Prometheus text-format scrape (open). Disabled when `metrics.sink` pushes to CloudWatch / Azure Monitor / GCP Cloud Monitoring |

## See also

- [README.md](README.md) - architecture overview, FlightSQL routing model, RBAC entities
- [QUICKSTART.md](QUICKSTART.md) - zero-to-first-query walkthrough
- [RUNNING.md](RUNNING.md) - deployment paths, env-var reference, operational notes
