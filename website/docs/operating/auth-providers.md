---
id: auth-providers
title: Authentication providers
---

Quack-on-Demand supports multiple authentication providers that can be enabled independently. Any combination may be active at the same time: the edge tries each enabled provider in order and accepts the first success. The full chain logic, session TTL, and how tenant identity is derived from claims are covered in the Authentication page. This page focuses on enabling and configuring each provider.

See [/reference/configuration](/reference/configuration) for the complete list of tunables.

---

## Database (built-in)

Validates username and password against the `qodstate_user` table in the control-plane Postgres using bcrypt. Enabled by default so the bootstrap admin works out of the box. A row whose `tenant` column is `NULL` is a superuser and matches any tenant context; a tenant-scoped row wins over the wildcard when both exist for the same username.

```bash
QOD_AUTH_DB_ENABLED=true            # default true; set false to disable entirely

# Override if the auth DB differs from the control-plane Postgres
QOD_AUTH_DB_JDBC_URL=jdbc:postgresql://host:5432/qod
QOD_AUTH_DB_USER=postgres
QOD_AUTH_DB_PASSWORD=secret

# Custom lookup query (must return columns: password_hash, role;
# placeholders in order: tenant, username)
QOD_AUTH_DB_QUERY="SELECT password_hash, role FROM qodstate_user \
  WHERE (tenant IS NULL OR tenant = ?) AND username = ? \
  ORDER BY (tenant IS NOT NULL) DESC LIMIT 1"
```

Rotate the bootstrap admin password by changing `QOD_ADMIN_PASSWORD` and restarting; the row is re-hashed on every boot.

On the management plane (REST/UI), DB credentials are accepted when `auth.management.identitySource=db` (the default). Setting it to `oidc` skips the DB authenticator on the management login even with this provider enabled; the edge keeps using it.

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

For the full reference of all authentication tunables, including `QOD_AUTH_ROLE_CLAIM` and `QOD_SESSION_TTL_SEC`, see [/reference/configuration](/reference/configuration).
