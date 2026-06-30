---
id: oauth-server-setup
title: OAuth / OIDC server setup (per provider)
---

This page is the complete server-side reference for wiring an OAuth / OIDC provider into the **FlightSQL data plane** (how a SQL client - JDBC, ADBC, the Arrow Flight SQL ODBC driver behind Power BI / Excel - authenticates with a bearer token). It covers Keycloak, Google, Azure AD, and AWS Cognito: the manager config keys, the endpoints the manager derives, and the matching client setup on the identity provider.

For the admin-UI browser login (the `/ui/` single sign-on), see the "Admin UI single sign-on" section of [Authentication providers](./auth-providers.md) - that is a separate, discovery-based switch and is not what this page configures.

## How the manager validates a bearer

When a FlightSQL client presents `Authorization: Bearer <jwt>` on the handshake, the enabled OIDC bearer providers are tried in order; the first to accept wins. Each provider runs three checks:

1. **Signature** - the JWT is verified against the provider's **JWKS** (public keys), fetched from a URL the manager derives from your config.
2. **Issuer** - the token's `iss` claim must equal the provider's **expected issuer**.
3. **Audience** - the token's `aud` claim must contain the configured **client id**.

If any check fails the provider rejects the token and the next provider is tried; if none accept, the handshake returns `UNAUTHENTICATED`. (DB / Basic auth is a different path and is unaffected by these checks.)

### Derivation matrix

What the manager computes per provider from your config:

| Provider | Expected issuer (`iss`) | JWKS URL | Expected audience (`aud`) |
|---|---|---|---|
| Keycloak | `${issuer}` if set, else `${baseUrl}/realms/${realm}` | `${baseUrl}/realms/${realm}/protocol/openid-connect/certs` | `clientId` |
| Google | `https://accounts.google.com` (fixed) | `https://www.googleapis.com/oauth2/v3/certs` (fixed) | `clientId` |
| Azure AD | `https://login.microsoftonline.com/${tenantId}/v2.0` | `https://login.microsoftonline.com/${tenantId}/discovery/v2.0/keys` | `clientId` |
| AWS Cognito | `https://cognito-idp.${region}.amazonaws.com/${userPoolId}` | `${issuer}/.well-known/jwks.json` | `clientId` |

Every scalar below has a `QOD_*` env-var override (the canonical way to set it; the `application.conf` keys are bundled into the jar). All providers are **off by default**; set the `*_ENABLED` flag to turn one on. Multiple providers may be enabled at once.

## Keycloak

| Env var | conf key (`quack-flightsql.auth.keycloak.*`) | Meaning |
|---|---|---|
| `QOD_AUTH_KEYCLOAK_ENABLED` | `enabled` | enable the provider |
| `QOD_AUTH_KEYCLOAK_BASE_URL` | `baseUrl` | Keycloak base URL incl. any http relative path, e.g. `https://kc.example.com` or `https://host/auth`. Used for **JWKS** and (unless overridden) the issuer. |
| `QOD_AUTH_KEYCLOAK_REALM` | `realm` | realm name |
| `QOD_AUTH_KEYCLOAK_CLIENT_ID` | `clientId` | the client id the token's `aud` must contain (e.g. `qod-flightsql`) |
| `QOD_AUTH_KEYCLOAK_CLIENT_SECRET` | `clientSecret` | client secret (ROPC / confidential clients). Prefer a mounted Secret over the ConfigMap. |
| `QOD_AUTH_KEYCLOAK_ISSUER` | `issuer` | **issuer override**. Leave empty to derive from `baseUrl + realm`. Set it for the split-horizon case below. |

Provider-side setup depends on how the SQL client obtains the token:

- **Basic credentials over Flight (JDBC, ADBC)** - the edge does a ROPC (resource-owner password) exchange. Create a **confidential** client with *Direct access grants* enabled, and put its id/secret in `CLIENT_ID` / `CLIENT_SECRET`.
- **Interactive auth-code + PKCE (Power BI / Excel via the Flight SQL ODBC driver)** - create a **public** client with *Standard flow* enabled and PKCE (`S256`), and register the client's redirect URI (for Power BI: `https://oauth.powerbi.com/views/oauthredirect.html`). Because this is a *different* client than `CLIENT_ID`, you must add an **audience mapper** so its token's `aud` includes `CLIENT_ID` (see [Audience](#audience-the-token-must-be-for-your-client) below).

### Split-horizon issuer (`QOD_AUTH_KEYCLOAK_ISSUER`)

Keycloak stamps the token `iss` with its **browser-facing** hostname (`KC_HOSTNAME_URL`). When the manager reaches Keycloak at a *different* in-cluster address for JWKS (e.g. tokens are minted via an ingress at `https://host/auth/realms/qod` but the manager fetches keys at `http://keycloak:8080/auth`), the derived issuer would not match the token and every bearer is rejected with `unexpected issuer`.

Set `baseUrl` to the in-cluster address (so JWKS stays reachable) and `QOD_AUTH_KEYCLOAK_ISSUER` to the browser-facing issuer:

```bash
QOD_AUTH_KEYCLOAK_BASE_URL=http://keycloak:8080/auth          # JWKS fetched here (in-cluster)
QOD_AUTH_KEYCLOAK_ISSUER=https://host/auth/realms/qod         # iss the token actually carries
```

JWKS is always derived from `baseUrl`, never from the issuer override.

## Google

| Env var | conf key (`...auth.google.*`) | Meaning |
|---|---|---|
| `QOD_AUTH_GOOGLE_ENABLED` | `enabled` | enable the provider |
| `QOD_AUTH_GOOGLE_CLIENT_ID` | `clientId` | OAuth client id; the token `aud` must contain it |
| `QOD_AUTH_GOOGLE_CLIENT_SECRET` | `clientSecret` | client secret |
| `QOD_AUTH_GOOGLE_GROUPS_LOOKUP` | `groupsLookup` | when true, enrich group membership from the Directory API |
| `QOD_AUTH_GOOGLE_SVC_ACCT_KEY_PATH` | `serviceAccountKeyPath` | service-account key for the groups lookup (domain-wide delegation) |
| `QOD_AUTH_GOOGLE_GROUPS_CACHE_TTL_SEC` | `groupsCacheTtlSeconds` | groups cache TTL |

Issuer and JWKS are fixed Google endpoints (table above). Google does **not** support ROPC, so SQL clients must present a Bearer obtained through the manager's browser token page (`/api/auth/sql-token/start`) or a service-account flow. Provider side: create an OAuth client id in Google Cloud and register the interactive client's redirect URI.

## Azure AD

| Env var | conf key (`...auth.azure.*`) | Meaning |
|---|---|---|
| `QOD_AUTH_AZURE_ENABLED` | `enabled` | enable the provider |
| `QOD_AUTH_AZURE_TENANT_ID` | `tenantId` | Azure AD (Entra) tenant id; drives the issuer + JWKS |
| `QOD_AUTH_AZURE_CLIENT_ID` | `clientId` | app registration (application) id; the token `aud` must contain it |
| `QOD_AUTH_AZURE_CLIENT_SECRET` | `clientSecret` | client secret (for ROPC) |

Issuer and JWKS are derived from `tenantId` (table above). Provider side: create an App Registration, expose it as the audience, and register redirect URIs for the interactive client. Azure supports ROPC for Basic `username:password` via the token endpoint.

## AWS Cognito

| Env var | conf key (`...auth.aws.*`) | Meaning |
|---|---|---|
| `QOD_AUTH_AWS_ENABLED` | `enabled` | enable the provider |
| `QOD_AUTH_AWS_REGION` | `region` | Cognito region, e.g. `eu-west-1`; drives issuer + JWKS |
| `QOD_AUTH_AWS_USER_POOL_ID` | `userPoolId` | Cognito user pool id |
| `QOD_AUTH_AWS_CLIENT_ID` | `clientId` | app-client id; the token `aud` must contain it |

Issuer and JWKS are derived from `region + userPoolId` (table above). ROPC is not supported; clients obtain a token from Cognito first.

Caveat: Cognito **access** tokens carry `client_id` rather than `aud`, while **id** tokens carry `aud`. Since the manager checks `aud`, present the **id token** as the bearer (or add a Cognito mechanism that populates `aud`), otherwise the audience check fails.

## Cross-cutting gotchas

### Audience: the token must be for your client

The bearer's `aud` must contain the `*_CLIENT_ID` you configured. This is automatic when the client that *minted* the token is the same one. It is **not** automatic for interactive flows where the SQL client uses its own IdP client (e.g. Power BI's `quack-powerbi` while the manager validates against `qod-flightsql`). In Keycloak, add an **Audience protocol mapper** to the interactive client:

- Mapper type `oidc-audience-mapper`, *Included Client Audience* = your `CLIENT_ID` (e.g. `qod-flightsql`), *Add to access token* = on.

The other IdPs have equivalent "add resource/audience to token" mechanisms.

### Issuer mismatch behind an ingress

See [Split-horizon issuer](#split-horizon-issuer-qod_auth_keycloak_issuer). The symptom is a bearer rejected with `unexpected issuer` while Basic/DB auth works. It applies whenever the token's `iss` differs from what the manager derives from your in-cluster config.

## Worked example: Power BI via Keycloak

This is the configuration the bundled kind rig (`charts/quack-on-demand/local-stack-k8s`) uses end to end:

1. **Data-plane provider** (manager): Keycloak enabled, JWKS in-cluster, issuer overridden to the browser host:
   ```bash
   QOD_AUTH_KEYCLOAK_ENABLED=true
   QOD_AUTH_KEYCLOAK_BASE_URL=http://keycloak:8080/auth
   QOD_AUTH_KEYCLOAK_REALM=qod
   QOD_AUTH_KEYCLOAK_CLIENT_ID=qod-flightsql
   QOD_AUTH_KEYCLOAK_ISSUER=http://<public-host>:20900/auth/realms/qod
   ```
2. **Interactive client** (Keycloak realm): `quack-powerbi`, public, Standard flow, PKCE `S256`, redirect URI `https://oauth.powerbi.com/views/oauthredirect.html`.
3. **Audience mapper** on `quack-powerbi`: `oidc-audience-mapper` adding `qod-flightsql` to the access token `aud`.
4. The Power BI connector points its `AuthorizeUrl` / `TokenUrl` at `http://<public-host>:20900/auth/realms/qod/protocol/openid-connect/{auth,token}` with `ClientId = quack-powerbi`.

With all four in place the browser login issues a token whose `iss` matches the override and whose `aud` contains `qod-flightsql`, so the FlightSQL handshake accepts it.

## See also

- [Authentication providers](./auth-providers.md) - the full provider chain, session model, and admin-UI SSO.
- [Authentication](./authentication.md) - how tenant identity is derived from claims and the realm (tenant vs system) selection.
- The connecting guides under "Connecting a client" for the JDBC / ODBC / Power BI URL and parameter syntax.