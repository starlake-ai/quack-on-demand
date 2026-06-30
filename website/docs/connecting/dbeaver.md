---
id: dbeaver
title: DBeaver
---

DBeaver connects to Quack on Demand through the Apache Arrow Flight SQL JDBC driver. There is no custom plugin to install: you register the driver jar once, then point a normal JDBC connection at the edge. The whole connection (target, routing, credentials) is expressed in the JDBC URL plus, for OAuth, one driver property.

```
DBeaver ──JDBC──▶ Arrow Flight SQL JDBC driver ──Flight SQL──▶ Quack edge (:31338, TLS)
```

This page is the click-by-click DBeaver walkthrough. For the full list of URL parameters the edge reads and the broader JDBC/Spark recipe, see [Connecting clients](/connecting/clients#dbeaver-and-jdbc); for the credential and TLS contracts, see [Authenticating](/connecting/authenticating).

## 1. Register the driver

DBeaver does not ship the Arrow Flight SQL driver, so add it once:

1. **Database -> Driver Manager -> New**.
2. **Settings**:
   - Driver Name: `Quack on Demand` (anything memorable).
   - Class Name: `org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`.
   - URL Template: `jdbc:arrow-flight-sql://{host}:{port}`.
   - Default Port: `31338`.
3. **Libraries -> Add Artifact**, paste the Maven coordinate and **Download**:
   ```
   org.apache.arrow:flight-sql-jdbc-driver:RELEASE
   ```
   (or **Add File** if you already have the jar). Pin a concrete version instead of `RELEASE` if you want reproducible installs.
4. **OK** to save the driver.

## 2. Create the connection

1. **Database -> New Database Connection**, pick **Quack on Demand**.
2. On the **Main** tab, set the **URL** directly (the simplest path, since every QoD parameter rides the URL):

   ```
   jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&tenant=acme&pool=bi&user=alice&password=demo-alice
   ```

   - `useEncryption=true` matches edge TLS (on by default); `disableCertificateVerification=true` accepts the auto-generated self-signed cert. Remove the latter once a CA-signed cert is installed.
   - `tenant` and `pool` are **required**: the edge applies no defaults and routes on these. A system-realm superuser adds `superuser=true` (and still sets `tenant` / `pool`, which keep driving query routing).

3. **Test Connection**, then **Finish**.

## 3. Authenticating

Pick the row that matches how the edge is configured. `tenant` / `pool` are required in every case; the credential only changes how identity is proven.

### Username and password

Set `user` / `password` in the URL (above). With Basic auth the edge BCrypt-verifies the local user; with an OIDC provider wired for *Direct access grants*, the edge exchanges them for a token (ROPC). No browser needed.

### OAuth bearer token (browser login)

When the edge is wired to an OIDC provider (Keycloak / Azure AD / Google) and you want interactive SSO / MFA, the Arrow Flight SQL JDBC driver cannot run the browser flow in-process, so the manager serves a browser **token page**:

1. Open `https://<gateway>:20900/api/auth/sql-token/start` in a browser. The manager redirects you to the provider's login (resolved via OIDC discovery, so the URL is reachable from your host).
2. Log in. The callback renders the **"Quack on Demand token"** page; click **Copy**.
3. In DBeaver, open the connection's **Driver properties** tab and add a property named **`token`** with the copied JWT as its value. Leave `user` / `password` unset.

   Equivalently, append `&token=<jwt>` to the URL and drop `user` / `password`:
   ```
   jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&tenant=acme&pool=bi&token=<jwt>
   ```

The page mints an **id token** whose `aud` is the edge's own client id, which is what the edge validates (it rejects access tokens whose audience is the provider's own resource). The token carries identity, not routing, so `tenant` / `pool` stay in the URL. The token expires; when it does, repeat the three steps for a fresh one.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `UNAUTHENTICATED: 'tenant' header required` / `'pool' header required` | The edge applies no defaults. Add `tenant=...` and `pool=...` to the URL. |
| `pool '<x>' not found in tenant '<y>'` | The bearer was accepted; the named pool does not exist in that tenant. Check the pool name and that you addressed the right tenant. |
| TLS / certificate handshake failure | Edge TLS is on. Keep `useEncryption=true`; add `disableCertificateVerification=true` until a CA-signed cert is installed. |
| Bearer rejected as wrong audience | Present the **id token** from the token page, not an access token. The page already returns the right one. |

For the server-side auth model (realms, provider chain, audience derivation) see [Authenticating](/connecting/authenticating) and [Authentication providers](/operating/auth-providers).