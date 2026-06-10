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

## Running without providers (development only)

If all provider chains are empty - no `database`, no JWT keys, no OIDC providers enabled - the edge logs a startup warning:

```
FlightSQL edge listening on ... (auth: OPEN (trust-the-client; configure auth.* in application.conf for prod))
```

In this mode the edge skips credential validation entirely and trusts whatever username the client supplies in the Basic header. A session peer ID is still minted and the `(tenant, pool, user)` tuple is still resolved from headers.

This fallback exists for local development when standing up a Postgres instance just for auth is inconvenient. Never use it in any shared or production environment. Enable at least the `database` provider (`QOD_AUTH_DB_ENABLED=true`, which is the default) before exposing the edge to any network.

For all configuration keys and their defaults see [/reference/configuration](/reference/configuration).
