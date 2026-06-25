---
id: tableau
title: Tableau
---

Tableau Desktop connects to Quack on Demand through the built-in **Other Databases (JDBC)** connector, driving the Apache Arrow Flight SQL JDBC driver. There is no custom Tableau connector to install: you drop the driver jar in Tableau's driver folder and point the generic JDBC connector at the same URL any JDBC client uses.

```
Tableau ──JDBC──▶ Arrow Flight SQL JDBC driver ──Flight SQL──▶ Quack edge (:31338, TLS)
```

This page covers installing the driver, connecting, and the one macOS/Linux gotcha that makes the connection fail in Tableau while it works in DBeaver. For the server-side `tenant` / `pool` and auth contracts the URL rides on, see [Connecting clients](/connecting/clients) and [Authenticating](/connecting/authenticating).

## Prerequisites

- **Tableau Desktop** (2021.x or later ships the "Other Databases (JDBC)" connector).
- A JRE-compatible **Apache Arrow Flight SQL JDBC driver** jar. Tableau bundles its own Java runtime (Java 17), so you only need the driver jar, not a separate JDK.
- Quack credentials: a username/password, or a bearer JWT via the driver's token option.

## Install the driver

Download the Apache Arrow Flight SQL JDBC driver jar (`org.apache.arrow:flight-sql-jdbc-driver`) from Maven Central, for example `flight-sql-jdbc-driver-19.0.0.jar`, and copy it into Tableau's driver directory:

| Platform | Driver directory |
|---|---|
| **macOS** | `~/Library/Tableau/Drivers` (or `/Library/Tableau/Drivers`) |
| **Windows** | `C:\Program Files\Tableau\Drivers` |
| **Linux** (Tableau Server) | `/opt/tableau/tableau_driver/jdbc` |

Restart Tableau after copying the jar so it is picked up.

## Connect

1. **Connect -> To a Server -> More -> Other Databases (JDBC)**.
2. **URL**: the full Flight SQL URL (see below).
3. **Dialect**: `SQL92`.
4. **Username** / **Password**: the edge credential. You can leave these blank if you put `user` / `password` in the URL instead; either works.
5. **Sign In**.

The entire connection is expressed in the URL, exactly as for any JDBC client:

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&user=alice&password=demo-alice&tenant=acme&pool=bi
```

- `useEncryption=true` matches edge TLS being on (the default); `disableCertificateVerification=true` accepts the auto-generated self-signed cert. Drop the latter once a CA-signed cert is installed (see [TLS](/operating/tls)).
- `user` / `password` are the edge credential.
- `tenant` / `pool` route the connection. They are required on every connection.

A system-realm superuser adds `superuser=true` to the same URL (the `tenant` and `pool` params stay in place because they still drive query routing):

```
jdbc:arrow-flight-sql://localhost:31338?useEncryption=true&disableCertificateVerification=true&user=root&password=demo-root&tenant=acme&pool=bi&superuser=true
```

See [Connecting clients](/connecting/clients#dbeaver-and-jdbc) for the full table of URL parameters QoD reads; the driver and URL are identical to the DBeaver recipe.

## Troubleshooting: "Bad Connection" / connection refused to the wrong IP

Symptom: Tableau reports **"Bad Connection: Tableau could not connect to the data source"**, and the Tableau log (`~/Documents/My Tableau Repository/Logs/log.txt`) contains a line like:

```
failed to connect to all addresses; last error: UNKNOWN:
ipv4:192.168.1.113:56628: Failed to connect to remote host: Connection refused
context: SendInitRequest (GrpcProtocolProxy.cpp)
```

where `192.168.1.113` is your machine's LAN address, not `127.0.0.1`. The same URL connects fine from DBeaver.

Cause: this failure is **not** the Flight SQL connection to QoD. Tableau's "Other Databases (JDBC)" connector runs the driver in a separate **Java Protocol Server** sidecar process and talks to it over gRPC on `localhost`. The sidecar binds the **loopback** interface (`127.0.0.1`). If `localhost` does not resolve to `127.0.0.1` on your machine, Tableau's main process dials the wrong address, the sidecar handshake is refused, and the connection never reaches QoD. DBeaver loads the driver in its own JVM with no sidecar, so it never makes this loopback hop, which is why it works there.

Check what `localhost` resolves to:

```bash
ping -c1 localhost      # must report 127.0.0.1
```

If it reports a LAN IP, your hosts file has remapped `localhost`. Fix it so `localhost` maps only to the loopback address:

- **macOS / Linux**: edit `/etc/hosts`
- **Windows**: edit `C:\Windows\System32\drivers\etc\hosts`

The relevant lines must read:

```
127.0.0.1   localhost
::1         localhost
```

Remove or comment out any line that points `localhost` at a non-loopback address (for example `192.168.1.113  localhost`). If you genuinely need that LAN IP under a name, give it its own hostname; do not override `localhost`.

Flush the resolver cache and fully restart Tableau:

```bash
# macOS
sudo dscacheutil -flushcache; sudo killall -HUP mDNSResponder
```

`ping -c1 localhost` should now report `127.0.0.1`, and the connection will succeed.

## Next

- [Connecting clients](/connecting/clients) - the connection target and the full JDBC parameter table.
- [Authenticating](/connecting/authenticating) - Basic vs JWT, and TLS.
- [Supported SQL](/connecting/sql) - the dialect, transactions, and querying federated catalogs.