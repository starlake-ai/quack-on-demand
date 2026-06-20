---
id: auth-providers
title: Authentication providers
---

Quack-on-Demand supports multiple authentication providers that can be enabled independently. Any combination may be active at the same time: the edge tries each enabled provider in order and accepts the first success. The full chain logic, session TTL, and how tenant identity is derived from claims are covered in the Authentication page. This page focuses on enabling and configuring each provider.

See [/reference/configuration](/reference/configuration) for the complete list of tunables.

---

## Database (built-in)

Validates username and password against the `qodstate_user` table in the control-plane Postgres using bcrypt. Enabled by default so the bootstrap admin works out of the box.

The authenticator runs one of two queries, picked by the caller-declared auth realm:

- `systemQuery` - used when the caller asked for the **system** realm (manager UI login with empty tenant; FlightSQL handshake with `?superuser=true`). Matches the row with `tenant IS NULL` - the bootstrap admin / system superuser.
- `tenantQuery` - used when the caller asked for the **tenant** realm. Matches the row with `tenant = ?`.

There is no fallback between the two: a system credential cannot authenticate a tenant-scoped login and vice versa.

```bash
QOD_AUTH_DB_ENABLED=true            # default true; set false to disable entirely

# Override if the auth DB differs from the control-plane Postgres
QOD_AUTH_DB_JDBC_URL=jdbc:postgresql://host:5432/qod
QOD_AUTH_DB_USER=postgres
QOD_AUTH_DB_PASSWORD=secret

# Custom lookup queries
# - systemQuery placeholders in order: username
# - tenantQuery placeholders in order: tenant, username
# Both must return columns: password_hash, role
QOD_AUTH_DB_SYSTEM_QUERY="SELECT password_hash, role FROM qodstate_user \
  WHERE tenant IS NULL AND username = ? LIMIT 1"
QOD_AUTH_DB_TENANT_QUERY="SELECT password_hash, role FROM qodstate_user \
  WHERE tenant = ? AND username = ? LIMIT 1"
```

Rotate the bootstrap admin password by changing `QOD_ADMIN_PASSWORD` and restarting; the row is re-hashed on every boot.

On the management plane (REST/UI), DB credentials are accepted when `auth.management.identitySource=db` (the default). Setting it to `oidc` skips the DB authenticator on the management login even with this provider enabled; the edge keeps using it.

> **Upgrading from a single-query deployment**: the old `QOD_AUTH_DB_QUERY` setting is gone, along with the `(tenant IS NULL OR tenant = ?)` wildcard fallback. Pre-existing JDBC URLs that used the bootstrap admin (`admin@localhost.local`) against tenants must now add `?superuser=true` to the URL. There is no migration on the `qodstate_user` table itself.

---

## External JWT

Validates Bearer tokens signed with a shared HMAC secret (HS256/HS512) or an RSA/EC public key. This provider is for custom JWT issuers that are not OIDC-compliant. It is activated automatically when either `JWT_SECRET_KEY` or `JWT_PUBLIC_KEY_PATH` is set; there is no separate enable flag.

```bash
# Option A: shared HMAC secret
JWT_SECRET_KEY=your-shared-secret

# Option B: RSA or EC public key (PEM file, PKCS#8 format)
JWT_PUBLIC_KEY_PATH=/etc/quack/jwt-public.pem

# Optional validation
JWT_ISSUER=https://your-issuer.example.com
JWT_AUDIENCE=quack-on-demand   # leave empty to skip audience check
```

If both `JWT_SECRET_KEY` and `JWT_PUBLIC_KEY_PATH` are set, either a valid HMAC or a valid RSA/EC signature is accepted. Token username is resolved from `preferred_username`, then `email`, then `sub`.

---

## Keycloak

Validates OIDC Bearer tokens via the Keycloak JWKS endpoint. Also supports the OAuth2 Resource Owner Password Credentials (ROPC) grant for clients that send Basic `username:password` credentials directly, such as JDBC drivers and BI tools.

```bash
QOD_AUTH_KEYCLOAK_ENABLED=true
QOD_AUTH_KEYCLOAK_BASE_URL=https://keycloak.example.com
QOD_AUTH_KEYCLOAK_REALM=your-realm
QOD_AUTH_KEYCLOAK_CLIENT_ID=quack-client
QOD_AUTH_KEYCLOAK_CLIENT_SECRET=client-secret
```

The JWKS URL is derived automatically as `{baseUrl}/realms/{realm}/protocol/openid-connect/certs`. For ROPC, the token endpoint is `{baseUrl}/realms/{realm}/protocol/openid-connect/token`.

---

## Google

Validates OIDC Bearer tokens issued by Google (`https://accounts.google.com`). Google does not support the ROPC grant; users must supply a Bearer token obtained via browser-based OAuth or a service account flow. Optionally, a Google Workspace service account with domain-wide delegation can enrich group membership from the Directory API.

```bash
QOD_AUTH_GOOGLE_ENABLED=true
QOD_AUTH_GOOGLE_CLIENT_ID=123456789.apps.googleusercontent.com
QOD_AUTH_GOOGLE_CLIENT_SECRET=GOCSPX-...

# Optional: Google Workspace groups lookup via Directory API
QOD_AUTH_GOOGLE_GROUPS_LOOKUP=true
QOD_AUTH_GOOGLE_SVC_ACCT_KEY_PATH=/etc/quack/google-svc-acct.json
QOD_AUTH_GOOGLE_GROUPS_CACHE_TTL_SEC=300   # default 300 s
```

The service account key JSON must contain `client_email`, `private_key`, and `token_uri`. The account needs domain-wide delegation with the `https://www.googleapis.com/auth/admin.directory.group.readonly` scope. Group results are cached per user for `QOD_AUTH_GOOGLE_GROUPS_CACHE_TTL_SEC` seconds.

### Per-tenant Google OAuth clients

The HOCON block above defines a **single** Google OAuth client used by every Google tenant by default. To give a tenant its own client (separate consent screen, separate `clientId`, independent revocation blast radius), set per-tenant fields on the tenant's auth provider in the admin UI or via `setTenantAuth`:

| Field | Value |
|---|---|
| `clientId` | The tenant's Google OAuth client ID, e.g. `<tenant>.apps.googleusercontent.com` |
| `clientSecretRef` | A reference to the secret, NOT the literal value. Today only `env:NAME` is supported (e.g. `env:GOOGLE_CS_TPCH`) |

When BOTH are set, the edge builds a per-tenant `OidcBearerAuthenticator` and substitutes it into the bearer chain for that tenant's handshakes. Tenants that leave the fields blank keep using the global block.

The `clientSecret` is never stored in the tenant row. Only the reference (`env:NAME`) is persisted, and the env var must be set on the manager process at boot. Mutations via `setTenantAuth` automatically invalidate the cached per-tenant authenticator.

This mechanism is currently Google-only; Keycloak / Azure / AWS still share a single per-manager OAuth client.

---

## Azure AD

Validates OIDC Bearer tokens issued by Azure Active Directory. Also supports ROPC for Basic `username:password` authentication via the Azure OAuth2 token endpoint.

```bash
QOD_AUTH_AZURE_ENABLED=true
QOD_AUTH_AZURE_TENANT_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
QOD_AUTH_AZURE_CLIENT_ID=your-app-client-id
QOD_AUTH_AZURE_CLIENT_SECRET=your-app-client-secret
```

The JWKS URL is derived as `https://login.microsoftonline.com/{tenantId}/discovery/v2.0/keys`. For ROPC the token endpoint is `https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token`.

---

## AWS Cognito

Validates OIDC Bearer tokens from an AWS Cognito User Pool. ROPC is not supported; clients must obtain a token from Cognito before connecting.

```bash
QOD_AUTH_AWS_ENABLED=true
QOD_AUTH_AWS_REGION=us-east-1
QOD_AUTH_AWS_USER_POOL_ID=us-east-1_AbCdEfGhI
QOD_AUTH_AWS_CLIENT_ID=your-app-client-id
```

The JWKS URL is derived as `https://cognito-idp.{region}.amazonaws.com/{userPoolId}/.well-known/jwks.json`.

---

## Browser OAuth (authorization-code flow)

A lightweight HTTP server that handles the OAuth 2.0 authorization-code grant for interactive clients such as the Quack-on-Demand UI or local CLI tools. Off by default. It reuses the first enabled OIDC provider (Keycloak, Google, or Azure AD, in that order) to resolve the authorization and token endpoints.

```bash
QOD_AUTH_OAUTH_ENABLED=true
QOD_AUTH_OAUTH_PORT=8888                          # default 8888
QOD_AUTH_OAUTH_BASE_URL=https://quack.example.com # public redirect base; defaults to http://localhost:{port}
QOD_AUTH_OAUTH_SCOPES="openid profile email"      # default "openid profile email"
QOD_AUTH_OAUTH_SESSION_TIMEOUT_SEC=3600           # pending session TTL; default 3600 s
QOD_AUTH_OAUTH_DISABLE_TLS=false                  # default false
```

At least one OIDC provider (Keycloak, Google, or Azure AD) must be enabled alongside this provider, or the server will fail to start. AWS Cognito is not supported as an OAuth flow backend.

---

## Admin UI single sign-on (OIDC)

The providers above govern the **FlightSQL data plane** (how a SQL client authenticates). This section is separate: it controls how operators log in to the **admin UI** at `/ui/`.

By default (`auth.management.identitySource=db`) the admin UI shows a username / password / tenant form. Set `identitySource=oidc` to make the admin UI a **pure SSO client**: the password form is removed and the browser is redirected to your identity provider.

It is **provider-agnostic**: it uses OIDC Discovery, so it works with any compliant IdP (Keycloak, Google, Azure AD, Okta, Auth0, Cognito, ...). You configure an **issuer URL** and a client id/secret; the manager resolves the authorize / token / end-session / JWKS endpoints from `${issuerUrl}/.well-known/openid-configuration`.

`qodstate_user` remains authoritative for role and tenant scope: the IdP verifies identity, and the matching `qodstate_user` grant decides what the operator may manage. IdP role/tenant claims are discarded.

```bash
QOD_MGMT_IDENTITY_SOURCE=oidc                       # turn on admin-UI SSO (default: db)

# System / superuser scope (the bare /ui/ login uses this issuer):
QOD_MGMT_OIDC_ISSUER_URL=https://idp.example.com/realms/qod
QOD_MGMT_OIDC_CLIENT_ID=qod-admin
QOD_MGMT_OIDC_CLIENT_SECRET=...                     # confidential client secret
QOD_MGMT_OIDC_SCOPES="openid email profile"         # default "openid email profile"

# Externally visible manager URL, used to build the redirect_uri. MUST match the
# redirect URI registered on the IdP client. When unset, derived from the request.
QOD_MGMT_PUBLIC_BASE_URL=https://qod.example.com
```

Register this **redirect URI** on each IdP client:

```
${QOD_MGMT_PUBLIC_BASE_URL}/api/auth/oidc/callback
```

### Login URLs and scope

- `/ui/` (no tenant) is the **system / superuser** login and authenticates against the manager-wide issuer above. Only a superuser (`qodstate_user.tenant IS NULL`, role admin) may complete it; a non-superuser is rejected and must sign in through their tenant.
- `/ui/?tenant=<id>` authenticates against **that tenant's** OIDC client and requires an admin grant for that tenant.

### Per-tenant OIDC

A tenant authenticates against its own issuer, configured in `qodstate_tenant.authConfig` with these generic keys (set them via the tenant auth API / admin UI):

| Key | Meaning |
|---|---|
| `issuerUrl` | the tenant's OIDC issuer (discovery base) |
| `clientId` | the tenant's OIDC client id |
| `clientSecretRef` | a secret reference (e.g. `env:ACME_CLIENT_SECRET`) resolved at runtime |
| `scopes` | optional; defaults to `openid email profile` |

### Logout

Logout is **RP-initiated**: it clears the `qod_session` cookie and redirects to the IdP end-session endpoint so the IdP session is terminated as well.

---

For the full reference of all authentication tunables, including `QOD_AUTH_ROLE_CLAIM` and `QOD_SESSION_TTL_SEC`, see [/reference/configuration](/reference/configuration).
