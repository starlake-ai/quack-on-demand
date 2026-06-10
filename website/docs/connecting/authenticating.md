---
id: authenticating
title: Authenticating
---

The FlightSQL edge authenticates every connection independently of the admin UI / REST session. This page covers the two credential paths a client can use, how the tenant is resolved, and TLS. For the operator side (configuring providers), see [Authentication](/operating/authentication) and [Authentication providers](/operating/auth-providers).

## Two credential paths

The edge accepts either Basic credentials or a bearer JWT.

### Basic (username + password)

The client sends a username and password (the `user` / `password` JDBC params, or the ADBC `username` / `password` kwargs). They are validated by the tenant's configured provider, the database bcrypt backend or an OIDC provider's resource-owner-password flow. The `tenant` and `pool` must be supplied as headers / URL params; both are required.

### Bearer JWT

The client presents a validated JWT as the bearer token. The user is taken from the token's `sub` claim. The tenant is taken from a `tenant` header if present, otherwise from the configured tenant claim (default claim name `tenant`, set by `tenantClaim` / `QOD_*`). The `pool` is always required as a header / param. A `tenant` header wins over the JWT claim when both are present.

In both paths the owning database (tenant-db) is resolved server-side from `(tenant, pool)`; the client never names it. A disabled tenant or pool causes the handshake to fail as unauthenticated.

## No-auth mode

When the server has no auth providers configured, the edge runs in a v1 "trust the client" mode: it accepts the Basic username as-is (with a loud startup warning) and the admin UI skips its login screen. This is for local development only; never expose the edge without a provider configured.

## TLS

Edge TLS is on by default. On first boot the manager generates a self-signed certificate under `certs/`, so clients must either skip certificate verification or trust a CA-signed certificate you supply:

- JDBC: `useEncryption=true&disableCertificateVerification=true`.
- ADBC: `DatabaseOptions.TLS_SKIP_VERIFY = "true"`.

To remove the skip-verify flag, install a CA-signed certificate and point the edge at it; see [TLS](/operating/tls). When edge TLS is disabled, connect with `grpc://` (ADBC) or `useEncryption=false` (JDBC) instead.

## Relationship to the REST session

The username/password you use here is the same credential set the admin UI accepts, but the mechanisms are separate: the UI mints a session token for `/api/*` calls, while the edge authenticates each Flight connection with Basic or JWT. A REST `X-API-Key` or UI session token is not a Flight credential.
