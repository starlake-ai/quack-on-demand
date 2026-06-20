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

There are three install paths, in decreasing order of recommendation.

### Option 1 - Signed `.pqx` (recommended) - not yet available

A signed connector loads under Power BI's default `Recommended` security setting once you trust the signing certificate's thumbprint. No need to lower data-extension security and no per-machine warning.

This is the path we intend to ship for production / shared deployments, but the signed build is **not yet released**. The release feed is the [QoD connector repository releases page](https://github.com/starlake-ai/pbi-adbc-driver/releases). Watch the repo for the first `*.pqx` artifact.

When the signed build is available, the procedure will be:

1. Download `QoD-<version>.pqx` from the release.
2. Copy it into `%UserProfile%\Documents\Power BI Desktop\Custom Connectors\` (create the folder if missing).
3. Trust the signing certificate's thumbprint via Group Policy (`Trusted Certificate Thumbprints`) or the registry key `HKLM:\SOFTWARE\Policies\Microsoft\Power BI Desktop\TrustedCertificateThumbprints`.
4. Restart Power BI Desktop.

### Option 2 - Unsigned `.mez` from GitHub Releases

A `.mez` is the same connector packaged unsigned. It loads in Power BI Desktop after lowering the data-extensions security setting. Suitable for dev, proof-of-concept and demos; for shared / production use prefer Option 1 once the signed build ships.

1. Download `QoD-<version>.mez` from the [release page](https://github.com/starlake-ai/pbi-adbc-driver/releases).
2. Copy it into `%UserProfile%\Documents\Power BI Desktop\Custom Connectors\` (create the folder if missing).
3. In Power BI Desktop, open **File -> Options and settings -> Options -> Security -> Data Extensions** and select **`(Not Recommended) Allow any extension to load without validation or warning.`** *(In a French UI: **Fichier -> Options et paramètres -> Options -> Sécurité -> Extensions du connecteur de données**, then the `(Non recommandé) Autoriser le chargement de toutes les extensions sans validation et avertissement` option.)*
4. **Restart Power BI Desktop fully** - close every running `PBIDesktop.exe`. Connectors load at startup; the security change does not apply to an already-running session.
5. **Get Data -> search "Quack" -> QoD (Quack on Demand)**. *(French UI: **Obtenir les données -> Plus...** then search.)*

On the on-premises gateway, copy the same `.mez` into the gateway's custom-connectors folder, enable custom connectors in the gateway app, and restart the gateway service.

### Option 3 - Build from source

Clone the connector repository and build it with the Power Query SDK. The full reference is the connector project's [INSTALL.md](https://github.com/starlake-ai/pbi-adbc-driver/blob/main/INSTALL.md); the short version:

```sh
git clone https://github.com/starlake-ai/pbi-adbc-driver.git
cd pbi-adbc-driver
dotnet build src/QoD.proj -c Release
# Output: bin/Release/QoD.mez
```

The VS Code "Power Query SDK" extension provides the MSBuild SDK + the `MakePQX` CLI used for validation and packaging. Once the `.mez` is built, deploy it as in Option 2.

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
| `... couldn't load ... not trusted` | An unsigned `.mez` without the data-extension security downgrade (Option 2), or a signed `.pqx` whose certificate thumbprint is not in the trusted list (Option 1). |
| `We couldn't convert a value of type Record to type Logical` | The 4th argument of `QoD.Database` is `trustServerCertificate` (logical). Put the `options` record in the 5th position. |
| Navigator empty (no tree under the data source) | The deployed `.mez` is older than the catalog-support fix (see the connector repo's release notes). Update to the latest release. |
| Auth fails with `no connection context for peer anonymous-...` | The deployed `.mez` is older than the per-call-credential fix. The credential must ride a custom call header (handled in the current `.mez`); update to the latest release. |
| Slow refresh on Basic auth | Quack BCrypt-verifies on every RPC. For high-volume refresh prefer a JWT (Key) credential or set up OAuth - both are validated more cheaply (signature only, no hashing). |

See also: the connector project's [INSTALL.md](https://github.com/starlake-ai/pbi-adbc-driver/blob/main/INSTALL.md) for the build, sign and gateway-deployment details.