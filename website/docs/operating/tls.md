---
id: tls
title: TLS
---

The Arrow FlightSQL edge (`quack-flightsql`, default port `31338`) runs with TLS enabled by default. This page explains the default self-signed certificate behavior, how to replace it with a CA-signed certificate, how to configure clients, and how to disable TLS for development.

## Default behavior

TLS is on at startup (`PROXY_TLS_ENABLED=true`). The server reads the certificate chain and private key from:

| Setting | Default path | Override |
|---|---|---|
| Certificate chain | `certs/server-cert.pem` | `PROXY_TLS_CERT_CHAIN` |
| Private key | `certs/server-key.pem` | `PROXY_TLS_PRIVATE_KEY` |

Paths are resolved relative to the JVM working directory (the repo root when launched via `scripts/run-jar.sh`).

### Automatic self-signed certificate generation

If either file is missing at boot, the server generates a self-signed certificate automatically using the system `openssl` binary. The generated certificate has these properties:

- Algorithm: RSA 2048-bit, no passphrase
- Validity: 3650 days (approximately 10 years)
- Subject CN: `localhost`
- Subject Alternative Names: `DNS:localhost`, `IP:127.0.0.1`

`openssl` must be on `PATH`. If it is not found, or if the `openssl` command fails, the server throws a `RuntimeException` with the full openssl output and does not start. In that case, either install `openssl` or supply your own certificate files at the configured paths before starting the server.

After a successful generation, the server logs a warning at `WARN` level containing the certificate path and its SHA-256 fingerprint in colon-separated uppercase hex format, for example:

```
WARN TLS: generated self-signed cert (CN=localhost) at certs/server-cert.pem / certs/server-key.pem. SHA-256 fingerprint: 4A:7F:... JDBC clients: jdbc:arrow-flight-sql://localhost:PORT?useEncryption=true&disableCertificateVerification=true (or import the cert into a trust store for verified TLS).
```

You can use this fingerprint to verify the certificate from a client or a monitoring check.

If the certificate and key files already exist at both configured paths, the server logs an informational message and reuses them without regenerating.

## Use a CA-signed certificate

For production, replace the self-signed certificate with one signed by a trusted CA before starting the server. Place the files at the configured paths (or point the environment variables at your preferred locations):

```
PROXY_TLS_CERT_CHAIN=/etc/tls/my-cert-chain.pem
PROXY_TLS_PRIVATE_KEY=/etc/tls/my-private-key.pem
```

The certificate file must contain a PEM-encoded certificate chain (leaf first, intermediates following). The key file must be a PEM-encoded PKCS#8 or traditional RSA private key without a passphrase (the server passes both directly to the Arrow Flight `useTls` API).

Restart the server after placing the files. The server logs `TLS: reusing existing cert at <path>` if the paths exist.

## Connect clients

### Import the certificate (recommended)

The most reliable approach for the self-signed default is to import the generated `certs/server-cert.pem` into the client trust store once. After that, clients verify the server identity and no extra connection flags are needed:

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&user=admin&password=admin&tenant=tpch&pool=sales
```

For Java-based clients, use `keytool` to import the PEM into a JKS truststore and point the JDBC URL at it with `trustStore=<path>&trustStorePassword=<password>`.

### Skip certificate verification (testing only)

For quick local testing against the self-signed certificate, pass `disableCertificateVerification=true` on the JDBC URL:

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&user=admin&password=admin&tenant=tpch&pool=sales
```

For ADBC or other CLI clients, pass the `--insecure` flag instead.

Do not use certificate verification bypass in production. It removes the server identity check and exposes the connection to man-in-the-middle attacks.

## Disable TLS (development only)

Set `PROXY_TLS_ENABLED=false` to run the FlightSQL edge on plain gRPC without any TLS wrapping. The JDBC URL then uses `useEncryption=false`:

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=false&user=admin&password=admin&tenant=tpch&pool=sales
```

Do not run without TLS in any environment where the network is not fully trusted, because credentials are transmitted in cleartext.
