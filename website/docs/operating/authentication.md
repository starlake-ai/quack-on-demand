---
id: authentication
title: Authentication
---

The FlightSQL edge authenticates every client connection before routing any query. This page explains how the authentication chain works, how clients pass credentials, how roles and groups are extracted from tokens, and how sessions are cached.

## How authentication works

The edge maintains two independent provider chains: one for **Basic** credentials and one for **Bearer** tokens. On each new handshake the edge tries providers in the configured order and takes the result from the first provider that accepts the credentials.

**Basic chain** (username + password):

- `database` - bcrypt lookup against `qodstate_user` in Postgres. Enabled by default; the bootstrap admin is seeded on startup.
- `keycloak` - Resource Owner Password Credentials (ROPC) grant against a Keycloak token endpoint.
- `azure` - ROPC grant against the Azure AD `oauth2/v2.0/token` endpoint.

Google does not support ROPC; Google users must authenticate via Bearer token.

**Bearer chain** (JWT or OIDC token):

- External JWT - validates a token signed with a shared secret (`JWT_SECRET_KEY`) or an RSA/ECDSA public key (`JWT_PUBLIC_KEY_PATH`). Active when either key is set.
- Keycloak OIDC - JWKS-based validation. Enabled via `QOD_AUTH_KEYCLOAK_ENABLED=true`.
- Google OIDC - JWKS-based validation. Enabled via `QOD_AUTH_GOOGLE_ENABLED=true`.
- Azure AD OIDC - JWKS-based validation. Enabled via `QOD_AUTH_AZURE_ENABLED=true`.
- AWS Cognito - JWKS-based validation. Enabled via `QOD_AUTH_AWS_ENABLED=true`.

Each chain is tried in the order listed. The first provider that returns a valid `AuthenticatedProfile` wins; the others are not consulted. If a chain has no configured providers and a credential of that type arrives, the chain returns an error immediately.

## Passing credentials

### Basic auth (username + password)

Basic auth requires three pieces of information: the username, the password, and both `tenant` and `pool` identifiers. The edge has no defaults; every client must supply a fully-qualified target.

For the Arrow Flight SQL JDBC driver, place the identifiers in the connection URL and the credentials in the standard JDBC properties:

```
jdbc:arrow-flight-sql://host:31338/?tenant=tpch&pool=sales
user=alice
password=s3cr3t
useEncryption=true
```

The `tenant` and `pool` URL parameters become gRPC call headers. The edge reads them as `tenant` and `pool` header values.

For ADBC or any raw Flight client, pass the same headers directly in the call metadata alongside the `Authorization: Basic <base64(user:pass)>` header.

### Bearer auth (JWT or OIDC token)

Pass the token in the `Authorization: Bearer <token>` header. The `pool` header is always required. For `tenant`, the edge checks in this order:

1. An explicit `tenant` call header (always wins).
2. The JWT claim named by `tenantClaim` (default `tenant`, override with `PROXY_TENANT_CLAIM`).

If neither is present the handshake is rejected with a missing-tenant error.

The authenticated username is taken from the JWT `sub` claim. For JDBC:

```
jdbc:arrow-flight-sql://host:31338/?tenant=tpch&pool=sales
token=<bearer-token>
useEncryption=true
```

## Roles and groups from tokens

After a provider validates a token it extracts a role string and a set of group names from the claims. The edge uses a cascading fallback for each:

**Role extraction** (configurable claim name `QOD_AUTH_ROLE_CLAIM`, default `role`):

1. The configured claim as a string.
2. The configured claim as a list (first element taken).
3. `roles` claim as a list (first element).
4. Keycloak nested `realm_access.roles` array (first element).
5. AWS Cognito `cognito:groups` list (first element).
6. Literal `"user"` if nothing matched.

**Groups extraction** (hardcoded primary claim `groups`):

1. `groups` claim as a list (all elements).
2. `cognito:groups` list (all elements).
3. Keycloak `realm_access.roles` array (all elements).
4. Empty set if nothing matched.

These token-derived role name and group names are passed through to the post-handshake authorization step. There, the edge resolves them against `qodstate_role.name` and `qodstate_group.name` in the user's tenant and union-merges them with the principal's local RBAC role and group memberships to build the **EffectiveSet**. Unknown names are silently dropped.

See the Access control model page for a full description of how the EffectiveSet is used to evaluate per-statement table-level grants.

## Sessions

On a successful handshake the edge mints a random UUID as a session peer ID and returns it to the client as `Authorization: Bearer <peerId>`. The client sends that value on every subsequent Flight RPC; the edge looks it up in `ConnectionContext` to recover the `(tenant, pool, user, EffectiveSet)` tuple without repeating the provider chain or re-querying Postgres.

The session is cached for `sessionTtlSec` seconds (env `QOD_SESSION_TTL_SEC`, default `3600`). After the TTL expires the peer ID is evicted. The next RPC from that client arrives as an unknown Bearer, and the edge forces a full re-handshake through the auth chain.

The TTL bounds the window during which a revoked credential or a rotated token remains effective after the next policy change.

## Management plane (REST and UI)

The management plane covers `/api/*` calls from the admin UI and any REST client. It runs through the same `AuthenticationService` chain as the edge for the password check, but its **authorization** (the user's role and the set of tenants they may manage) is resolved separately by `auth.management.identitySource`.

### Identity source

`auth.management.identitySource` (env `QOD_AUTH_MANAGEMENT_IDENTITY_SOURCE`, default `db`):

- **`db`** - the authenticating profile IS the grant. Whatever provider validated the password (Database or OIDC ROPC) determines `role` and `tenant`. JWT role claims directly mint admin sessions, so this is appropriate when an OIDC IdP is trusted as the role source, or in single-system DB-only deployments.
- **`oidc`** - the JWT's role and tenant claims are **discarded**. After authentication succeeds, the management plane looks up the user in `qodstate_user` and resolves role + the set of tenants they may manage from the database. An OIDC-verified identity with no matching `qodstate_user` row is rejected with `403 not_provisioned`.

In `oidc` mode the management login skips the database authenticator entirely, even if `auth.database.enabled=true`. The DB authenticator continues to serve the FlightSQL edge.

`auth.management.identityClaim` (default `preferred_username`) picks the JWT claim matched against `qodstate_user.username`. The `email` claim is tried as a fallback.

### Multi-tenant scope

A management session carries two pieces of authorization state:

- `superuser` - `true` iff the user has a `qodstate_user` row with `tenant IS NULL` and `role = admin`. Cross-tenant by design.
- `manageableTenants` - the set of tenants where the user has `role = admin`. Empty for a pure superuser; non-empty for tenant-scoped admins.

The UI exposes both via `/api/auth/login` and `/api/auth/whoami` so a multi-tenant admin sees a per-screen tenant switcher rather than picking one tenant at login time.

A per-request guard rejects management calls that target a tenant outside `manageableTenants` (URL path, query, or body) with `403 tenant_forbidden`. Endpoints that affect cross-tenant state (`config/server`, `manifest/export`, `manifest/import`, `tenant/create`) additionally require `superuser` and return `403 superuser_required` to multi-tenant admins.

### Authentication matrix

| `database.enabled` | OIDC provider enabled | `identitySource` | Edge authentication | REST mgmt authentication | REST mgmt authorization |
|---|---|---|---|---|---|
| true | no | db | DB Basic only (Bearer rejected) | DB Basic only | role + tenant from the DB row |
| true | no | oidc | DB Basic only | impossible: no OIDC provider to call, login always fails | n/a |
| true | yes | db | DB Basic; OIDC ROPC; OIDC Bearer | DB Basic; then OIDC ROPC. First match wins. JWT role claim can mint admin. | role + tenant from the authenticating profile |
| true | yes | oidc | DB Basic; OIDC ROPC; OIDC Bearer (edge unchanged) | OIDC ROPC only (DB authenticator skipped on mgmt) | qodstate_user grant lookup; JWT role/tenant discarded |
| false | no | any | nothing enabled; all requests rejected | nothing enabled; all requests rejected | n/a |
| false | yes | db | OIDC ROPC; OIDC Bearer | OIDC ROPC only | role + tenant from the OIDC-authenticated profile; JWT role claim can mint admin |
| false | yes | oidc | OIDC ROPC; OIDC Bearer | OIDC ROPC only | qodstate_user grant lookup; JWT role/tenant discarded |

Recommended combinations:

- Local dev or bootstrap: `database.enabled=true`, no OIDC, `identitySource=db`. The seeded `admin@localhost.local / admin` flow works out of the box.
- Production with an IdP, strict OIDC management: `database.enabled=false`, OIDC enabled, `identitySource=oidc`. Every login goes through the IdP and the manager controls authorization via `qodstate_user`.
- Production with IdP and DB break-glass for edge clients: `database.enabled=true`, OIDC enabled, `identitySource=oidc`. JDBC clients can still hit FlightSQL with DB credentials, but management login is forced through the IdP.

The combination `identitySource=db` with OIDC enabled trusts whatever role claim the IdP issues; only use it when the IdP and the role mapping are fully under your control.

## Running without providers (development only)

If all provider chains are empty - no `database`, no JWT keys, no OIDC providers enabled - the edge logs a startup warning:

```
FlightSQL edge listening on ... (auth: OPEN (trust-the-client; configure auth.* in application.conf for prod))
```

In this mode the edge skips credential validation entirely and trusts whatever username the client supplies in the Basic header. A session peer ID is still minted and the `(tenant, pool, user)` tuple is still resolved from headers.

This fallback exists for local development when standing up a Postgres instance just for auth is inconvenient. Never use it in any shared or production environment. Enable at least the `database` provider (`QOD_AUTH_DB_ENABLED=true`, which is the default) before exposing the edge to any network.

For all configuration keys and their defaults see [/reference/configuration](/reference/configuration).
