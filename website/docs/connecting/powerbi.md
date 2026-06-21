---
id: powerbi
title: Power BI
---

Power BI / Microsoft Fabric connects to Quack on Demand through the **QoD custom connector**, which wraps the Apache Arrow Flight SQL ODBC driver. The same `.mez` works in Power BI Desktop and the on-premises data gateway, so a report you author on the desktop refreshes the same way in the Power BI Service.

```
Power BI ──M──▶ QoD.mez ──ODBC──▶ Arrow Flight SQL ODBC driver ──Flight SQL──▶ Quack edge (:31338, TLS)
```

This page covers installing the connector and connecting. For the server-side `tenant` / `pool` and auth contracts that the connector rides on, see [Connecting clients](/connecting/clients) and [Authenticating](/connecting/authenticating).

## Prerequisites

- **Power BI Desktop**, and optionally the **on-premises data gateway** for refresh in the Power BI Service.
- The **Apache Arrow Flight SQL ODBC driver** registered on the machine running PBI Desktop or the gateway. The connector wraps it; without the driver installed the connector loads but cannot open a session.
- Quack credentials in one of the three supported kinds:
  - Username + password (validated against the Quack Postgres / BCrypt backend)
  - Static JWT (validated against the external JWT backend)
  - OAuth 2.0 / OIDC token (Keycloak / Entra ID / Google / Cognito)

## Install the connector

### Install the prerequisite: Arrow Flight SQL ODBC driver

The connector wraps the Arrow Flight SQL ODBC driver - it must be installed on every machine that runs Power BI Desktop or the on-premises data gateway, otherwise the connector loads but every connection fails with `[Apache Arrow][Flight SQL] (500) ... failed to connect`.

Download the latest Dremio build of the driver from the official Dremio download server:

| Platform | Installer |
|---|---|
| **Windows** (Power BI Desktop, gateway) | [`arrow-flight-sql-odbc-LATEST-win64.msi`](https://download.dremio.com/arrow-flight-sql-odbc-driver/arrow-flight-sql-odbc-LATEST-win64.msi) |

The full release index (per-version artifacts) is at [download.dremio.com/arrow-flight-sql-odbc-driver/](https://download.dremio.com/arrow-flight-sql-odbc-driver/).

For Power BI Desktop on Windows, run the MSI as administrator; the installer registers the ODBC driver under the name **`Arrow Flight SQL ODBC Driver`** in both the 32-bit and 64-bit ODBC data-source administrator. The QoD connector references that exact name, so no extra configuration is needed.

To confirm the install, open **ODBC Data Sources (64-bit) -> Drivers** tab and verify **`Arrow Flight SQL ODBC Driver`** is listed.

### Install the connector

The connector ships as two artifacts:

- **`QoD.mez`** - the connector packaged **unsigned**; loads only with the data-extensions security setting **lowered** (Option 1).
- **`QoD.pqx`** - a packed, **signed** connector; loads under the **Recommended** security setting once you trust its signing certificate's thumbprint (Option 3). This is how you allow **only** the QoD connector while still blocking other uncertified extensions.

Three install paths follow.

:::caution Using OAuth 2.0 / OIDC?
The OAuth endpoints and client ID are **compiled into the `.mez`** (`OAuthConfig` in `QoD.pq`), so the generic released build cannot carry your identity provider. To use OAuth you must **build the connector from source** with `OAuthConfig` pointed at your IdP - see [Option 2 - Build with your own OAuth support](#option-2---build-with-your-own-oauth-support). The **Username / Password** and **Key (static JWT)** credential kinds work with the pre-built release in Option 1.
:::

### Option 1 - Unsigned `.mez` from GitHub Releases (Dev only)

A `.mez` is the connector packaged unsigned. It loads in Power BI Desktop after lowering the data-extensions security setting. This pre-built release supports **Username / Password** and **Key (static JWT)** authentication; for **OAuth** build from source (Option 2) so your IdP endpoints are baked in.

1. Download `QoD-<version>.mez` from the [release page](https://github.com/starlake-ai/pbi-adbc-driver/releases).
2. Copy it into `%UserProfile%\Documents\Power BI Desktop\Custom Connectors\` (create the folder if missing).
3. In Power BI Desktop, open **File -> Options and settings -> Options -> Security -> Data Extensions** and select **`(Not Recommended) Allow any extension to load without validation or warning.`** *(In a French UI: **Fichier -> Options et paramètres -> Options -> Sécurité -> Extensions du connecteur de données**, then the `(Non recommandé) Autoriser le chargement de toutes les extensions sans validation et avertissement` option.)*
4. **Restart Power BI Desktop fully** - close every running `PBIDesktop.exe`. Connectors load at startup; the security change does not apply to an already-running session.
5. **Get Data -> search "Quack" -> QoD (Quack on Demand)**. *(French UI: **Obtenir les données -> Plus...** then search.)*

On the on-premises gateway, copy the same `.mez` into the gateway's custom-connectors folder, enable custom connectors in the gateway app, and restart the gateway service.

### Option 2 - Only if you need OAuth

Build from source when you need **OAuth**, or want to change any compiled-in connector setting. A `.mez` is **just a flat zip** of the connector source plus icons - **no Power Query SDK or compiler is required**; any zip tool produces a valid connector.

**1. Get the source:**

```sh
git clone https://github.com/starlake-ai/pbi-adbc-driver.git
cd pbi-adbc-driver
```

**2. For OAuth, edit `OAuthConfig` in `src/QoD.pq`** to point at your identity provider. Example for a Keycloak realm `qod`:

```m
OAuthConfig = [
    AuthorizeUrl = "https://<keycloak-host>/realms/qod/protocol/openid-connect/auth",
    TokenUrl     = "https://<keycloak-host>/realms/qod/protocol/openid-connect/token",
    LogoutUrl    = "https://<keycloak-host>/realms/qod/protocol/openid-connect/logout",
    ClientId     = "quack-powerbi",
    Scope        = "openid profile email offline_access",
    RedirectUri  = "https://oauth.powerbi.com/views/oauthredirect.html"
];
```

The IdP must have a **public client** (no secret) with **PKCE = S256** and the redirect URI `https://oauth.powerbi.com/views/oauthredirect.html` registered. Legacy Keycloak prefixes every path with `/auth` (e.g. `https://<host>/auth/realms/qod/...`). See [Authenticating](/connecting/authenticating) for the provider-side setup and the Quack-side token-trust configuration.

`RedirectUri` stays constant; swap the endpoints + `ClientId` for your provider. Ready-to-paste examples (also in the `src/QoD.pq` header comments):

**Microsoft Entra ID (Azure AD), v2.0** - `{tenant}` = directory GUID or `organizations`; public client + PKCE; scope must include `offline_access` for refresh tokens:

```m
    AuthorizeUrl = "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize",
    TokenUrl     = "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token",
    LogoutUrl    = "https://login.microsoftonline.com/{tenant}/oauth2/v2.0/logout",
    ClientId     = "{application-client-id}",
    Scope        = "openid profile email offline_access",
```

**AWS Cognito** (Hosted UI - needs an App client + a Cognito domain); `{domain}` = your prefix/custom domain, `{region}` = e.g. `eu-west-1`. Cognito issues a refresh token for the auth-code grant automatically (`offline_access` accepted but not required):

```m
    AuthorizeUrl = "https://{domain}.auth.{region}.amazoncognito.com/oauth2/authorize",
    TokenUrl     = "https://{domain}.auth.{region}.amazoncognito.com/oauth2/token",
    LogoutUrl    = "https://{domain}.auth.{region}.amazoncognito.com/logout",
    ClientId     = "{app-client-id}",
    Scope        = "openid profile email",
```

**Google Identity** (OAuth client of type "Web application"). Note: Google does **not** use the `offline_access` scope - it returns a refresh token only when the authorize request carries `access_type=offline` (+ `prompt=consent`), so refresh needs a connector tweak; the `LogoutUrl` is token revocation (best-effort):

```m
    AuthorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
    TokenUrl     = "https://oauth2.googleapis.com/token",
    LogoutUrl    = "https://oauth2.googleapis.com/revoke",
    ClientId     = "{client-id}.apps.googleusercontent.com",
    Scope        = "openid profile email",
```

**3. Package the `.mez`** - zip `QoD.pq`, `resources.resx` and the icon PNGs at the **root** of the archive (not inside a subfolder).

Windows (PowerShell):

```powershell
$stage = Join-Path $env:TEMP qodmez
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item src\QoD.pq, src\resources.resx $stage
Copy-Item src\icons\QoD*.png $stage
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($stage, "$PWD\QoD.mez")
```

macOS / Linux:

```sh
mkdir -p stage
cp src/QoD.pq src/resources.resx stage/
cp src/icons/QoD*.png stage/
( cd stage && zip -X ../QoD.mez QoD.pq resources.resx QoD*.png )
```

This produces `QoD.mez`. Deploy it as in Option 1.

> Optionally validate the M syntax with the Power Query SDK's `MakePQX compile` before packaging - but it is not required to produce a working `.mez`.

### Option 3 - Signed `.pqx` (production, keep the Recommended security setting)

A `.pqx` is a **packed, signed** connector. Unlike the unsigned `.mez`, it loads **without** lowering the data-extensions setting: keep **`(Recommended) Only allow ... trusted`** and trust the signing certificate's thumbprint instead. That is the way to allow **only** the QoD connector while other uncertified extensions stay blocked. You need a code-signing certificate - either CA-issued or **self-signed** (fine for internal / enterprise use).

**1. (self-signed) create a code-signing certificate** - Windows / PowerShell (skip if you have a CA-issued `.pfx`):

```powershell
$cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject "CN=QoD Connector (self-signed)" `
  -CertStoreLocation Cert:\CurrentUser\My -KeyExportPolicy Exportable -KeyUsage DigitalSignature -KeySpec Signature
$cert.Thumbprint        # the SHA-1 thumbprint you trust below
$pw = ConvertTo-SecureString "changeit" -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath code-signing.pfx -Password $pw   # private key, to sign
Export-Certificate    -Cert $cert -FilePath qod-codesign.cer                 # public, for the trust store
```

**2. Sign** the `.mez` into a `.pqx` with `MakePQX`.

`MakePQX` ships with the **Power Query SDK** - install the [Power Query SDK extension](https://marketplace.visualstudio.com/items?itemName=PowerQuery.vscode-powerquery-sdk) in VS Code (or the [`Microsoft.PowerQuery.SdkTools` NuGet package](https://www.nuget.org/packages/Microsoft.PowerQuery.SdkTools)). It drops `MakePQX.exe` under the SDK tools folder (e.g. `%USERPROFILE%\.vscode\extensions\powerquery.vscode-powerquery-sdk-*\.nuget\Microsoft.PowerQuery.SdkTools.*\tools\`); add that to `PATH` or call it by full path.

```sh
MakePQX pack --mez QoD.mez --target QoD.pqx --certificate code-signing.pfx --password changeit
MakePQX verify QoD.pqx
```

**3. Install + trust** on every machine that loads the connector:

- Copy `QoD.pqx` into `%UserProfile%\Documents\Power BI Desktop\Custom Connectors\`.
- Keep the security setting at **`(Recommended) Only allow ... trusted`**.
- Allowlist the certificate thumbprint (deployable org-wide via the Group Policy *Trusted Certificate Thumbprints*):

  ```powershell
  $key = "HKLM:\SOFTWARE\Policies\Microsoft\Power BI Desktop"
  New-Item -Path $key -Force | Out-Null
  New-ItemProperty -Path $key -Name "TrustedCertificateThumbprints" -PropertyType MultiString -Value @($cert.Thumbprint) -Force
  ```

- **Self-signed certs only:** import the public cert into Trusted Root so the signature chain validates (CA-issued certs skip this):

  ```powershell
  Import-Certificate -FilePath qod-codesign.cer -CertStoreLocation Cert:\LocalMachine\Root
  ```

- **Restart** Power BI Desktop fully (or the gateway service).

The same `.pqx` + thumbprint trust works on the on-premises gateway. Full publisher/admin detail is in the connector's [INSTALL.md](https://github.com/starlake-ai/pbi-adbc-driver/blob/main/INSTALL.md).

## Connect

Open **Get Data -> QoD (Quack on Demand)** and provide:

| Field | Example | Notes |
|-------|---------|-------|
| Server | `quack.example.com:31338` | `host:port`; defaults to port `31338` if omitted |
| Tenant | `acme` | Sent as a gRPC call header; matches a Quack tenant |
| Pool | `bi` | Sent as a gRPC call header; matches a Quack pool within the tenant |
| Trust server certificate | `true` / `false` | Required (4th argument). `true` only for a self-signed dev endpoint; `false` against a CA-signed cert |

Pick a credential kind in the next dialog:

| Power BI credential | Authenticates against | Wire format |
|---|---|---|
| Username / Password | Quack `qodstate_user` (BCrypt) | `Basic base64(user:password)` |
| Key (static JWT) | External JWT backend (HS256 / RS256 / ECDSA) | `Bearer <jwt>` |
| OAuth 2.0 | OIDC backend (Keycloak / Entra ID / Google / Cognito) | `Bearer <access_token>` |

All three collapse to a single per-call gRPC header that the server validates on every RPC. There is no separate Flight handshake step for the connector to drive.

The equivalent M call:

```m
QoD.Database("quack.example.com:31338", "acme", "bi", true)
```

`trustServerCertificate` is the required 4th argument; an optional 5th `options` record accepts `UseEncryption`, `ConnectionTimeout`, `CommandTimeout`. The Navigator then shows the data catalogs the user has been granted on (`acme_tpch` / `acme_default` / etc.), with their schemas and tables underneath; ducklake's metadata catalogs (`__ducklake_metadata_*`) also appear and can be ignored.

## DirectQuery vs Import

The connector advertises `SupportsDirectQuery = true`, so the **Connect** vs **Import** choice in the load dialog is available on every dataset.

| Mode | When to use | Notes |
|---|---|---|
| **Import** | Datasets that fit in memory; reports that need full Power BI modeling and DAX | Loads on refresh; the .mez sets `Supports*Literals = true` so the M engine inlines literal values into folded SQL rather than emitting `?` parameter binds (Quack has no parameter binding) |
| **DirectQuery** | Large datasets that must stay in Quack | The driver folds projection, filters, joins, GROUP BY, ORDER BY, LIMIT/OFFSET into the SQL Quack runs |

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Connector missing from **Get Data** | `.mez` is not in `Documents\Power BI Desktop\Custom Connectors\` or PBI was not restarted after lowering the security setting. |
| `... couldn't load ... not trusted` | The `.mez` was loaded without the data-extension security downgrade. Enable *Allow any extension...* and restart Power BI Desktop (Option 1). |
| `We couldn't convert a value of type Record to type Logical` | The 4th argument of `QoD.Database` is `trustServerCertificate` (logical). Put the `options` record in the 5th position. |
| Navigator empty (no tree under the data source) | The deployed `.mez` is older than the catalog-support fix (see the connector repo's release notes). Update to the latest release. |
| Auth fails with `no connection context for peer anonymous-...` | The deployed `.mez` is older than the per-call-credential fix. The credential must ride a custom call header (handled in the current `.mez`); update to the latest release. |
| Slow refresh on Basic auth | Quack BCrypt-verifies on every RPC. For high-volume refresh prefer a JWT (Key) credential or set up OAuth - both are validated more cheaply (signature only, no hashing). |

See also: the connector project's [INSTALL.md](https://github.com/starlake-ai/pbi-adbc-driver/blob/main/INSTALL.md) for build and gateway-deployment details.