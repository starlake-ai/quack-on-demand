# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test commands

```bash
sbt run                 # run the manager (forks JVM - see "JVM forking" below)
sbt test                # run the full Scala test suite (~2802 tests)
sbt assembly            # build distrib/quack-on-demand-assembly-*.jar (UI is bundled in)
sbt "testOnly ai.starlake.quack.route.RouterSpec"        # one test class
sbt "testOnly *RouterSpec -- -z \"picks the least-loaded\"" # one test by name fragment
sbt scalafmtAll         # format (scalafmt 3.10, scala3 dialect, maxColumn 100)
```

`sbt test` in a git worktree: the forked test JVM finds `libduckdb` (needed by the native `libquackwire`) through the main checkout's `.duckdb` cache, because [build.sbt](build.sbt) points `DUCKDB_CACHE_DIR` there for forked tests unless already set (macOS SIP strips `DYLD_*`, so the loader path cannot help). When the native cannot load, the affected specs fail fast with the preload paths named instead of hanging.

Boot the manager from the uber-jar (with TLS cert auto-gen + Postgres reachability probe):

```bash
./scripts/run-jar.sh           # foreground
QOD_VERSION=BUILD ./scripts/run-jar.sh   # sbt assembly first
QOD_VERSION=LOCAL ./scripts/run-jar.sh   # newest distrib/ jar, no rebuild
./scripts/stop-jar.sh            # SIGTERM → wait → SIGKILL
```

On **Windows** the manager runs natively (no WSL/Docker) via PowerShell twins:
`scripts/run-jar.ps1` / `scripts/stop-jar.ps1`, and nodes spawn through
`scripts/spawn-quack-node.ps1` (a mirror of the bash spawn script: it starts
`qod-node.exe` with the init SQL on its stdin as UTF-8 bytes, and qod-node exits when
its wrapper does). Teardown uses `taskkill /T` so the `qod-node.exe` grandchild dies
with its wrapper. The native `quackwire.dll` is
bundled automatically whenever `libquackwire/binaries/windows-x86_64/quackwire.dll`
(and `windows-aarch64/quackwire.dll` for Windows on ARM64) exists in the checkout; the
JVM loads the one for its own `os.arch`, so an ARM64 host needs an ARM64 JVM and the
windows-arm64 DuckDB (`run-jar.ps1` picks the DuckDB build from the JVM's `os.arch`). The native client is the manager's only client to its nodes (the embedded
JDBC fallback, `QOD_NATIVE_CLIENT` and `QOD_NODE_DISABLE_SSL` were dropped with DuckDB 2.0, whose
JDBC disables Arrow export): `QuackNativeSupport.bootCheck` loads it early in `Main.bootManager`
and a platform with no bundled binary or a native that
cannot load refuses to boot, naming `os.name`/`os.arch`, the expected
`libquackwire/binaries/<platform>/` file and the `DUCKDB_CACHE_DIR` hint.
Each libquackwire binary embeds `quackwire-build-info:protocol=..;duckdb=..;duckdb_source=..;quack=..`
(set by `native/quackwire/CMakeLists.txt`, which probes the linked libduckdb); `bootCheck` compares it
with `/native/VERSION` and the loaded libduckdb and refuses a stale native, and
`scripts/release/quackwire-build-info.sh` checks the same marker on the files. Until DuckDB v2.0.0
ships only osx-aarch64 is a 2.0 build: the other four vendored natives are refused at boot, so this
branch runs only on Apple Silicon. `scripts/release/check-release-pins.sh` (workflow `release pins`)
keeps the pre-release JDBC and `mavenLocal` off main. The qod CLI caches DuckDB per release
(`<user cache>/qod/duckdb/<version>/{bin,lib}`, the `DUCKDB_CACHE_DIR` layout) and exports
`DUCKDB_CACHE_DIR` to the manager JVM on every launcher path, so the preload never depends on
`DYLD_*` (stripped by SIP from `/usr/bin/java`) or a baked rpath.

**DuckDB 2.0.** The engine is the v2.0.0 release and the JDBC is `org.duckdb:duckdb_jdbc:2.0.0.0` from Maven Central. `run-jar.sh` and the qod CLI provision the CLI and libduckdb from the release's `duckdb-cli-<platform>.tar.gz` / `duckdb-shared-libs-<platform>.tar.gz` (2.x asset names; 1.x shipped `.zip`s). `scripts/dev/build-duckdb-prerelease.sh` is obsolete: it deletes the cache it writes to, the release's included, so never run it.

**Boot problems are reported, never only logged.** A tenant-db whose DuckLake init is refused
for good (`PreInitMismatchException`: a dataPath or encryption disagreement with the catalog) is
blocked: reconcile never starts its pools. `PoolSupervisor.startupProblems` lists every blocked
tenant-db and every tenant-db whose federation blob failed to resolve; the boot banner then reads
`is up, with N problem(s)` and prints each under PROBLEMS (why, then each fix on its own unwrapped
line), and `/api/pool/list` reports the pools `blocked` with `statusDetail` (`poolBlockReason`).
A dataPath mismatch's fix is built from where the path came from (`dataPathBlockReason`): derived
from `QOD_DUCKLAKE_DATA_PATH` (the row names none, as a manifest import's rows can), the row's
metastore, or its create-time `dataPath`. Replica-local: an HA follower runs no DuckLake init.

**DuckLake migration.** Only the manager's DuckLake initializer attaches with `AUTOMATIC_MIGRATION`, and only when the catalog needs it (`DuckLakeInitializer.migrationPlan`, decided under its advisory lock): a catalog older than the latest DuckLake, or one at the latest dev version last migrated by another engine build (recorded in `qod_meta.ducklake_migration` in the catalog's database; `LatestDuckLakeVersion` is pinned by a spec). A current catalog attaches with no DDL. The initializer's catalog connection carries `lock_timeout=5000`; a timeout is retry-later (`CatalogLockTimeoutException`, `PoolSupervisor.duckLakeInitPending`, retried by the leader's reconcile loop), never a blocking mismatch. Nodes, loaders and the demo seed attach as-is (DuckDB 2.0 attaches any catalog >= 1.0 without migrating). Migration is one-way: back up catalog databases before upgrading.

UI dev loop (proxies `/api/*` to `localhost:20900`):

```bash
cd ui && npm install && npm run dev
```

The UI is built into `src/main/resources/ui` by [project/UiBuild.scala](project/UiBuild.scala) as a resourceGenerator, so `sbt compile`/`sbt assembly` will run `npm ci` + `npm run build` automatically. There is no manual UI build step for a regular Scala compile.

## JVM forking (don't disable it)

[build.sbt](build.sbt) sets `Compile / run / fork := true` and `Test / fork := true` and adds `--add-opens=java.base/java.nio=ALL-UNNAMED` + `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`. Arrow Flight's `arrow-memory-unsafe` allocator reflects into `java.nio` internals; without forked JVM + the opens, Arrow crashes at class init under Java 17+. The assembly jar embeds an `Add-Opens` manifest attribute (JEP 261) so `java -jar` works without extra flags. The startup script also passes `-Darrow.allocation.manager.type=Unsafe` - without it Arrow auto-picks the netty allocator, which crashes with `NoSuchFieldError: chunkSize` because the assembly bundles a newer Netty than `arrow-memory-netty 14.0.1` reflects against. Both forks also pass `-Dio.netty.tryReflectionSetAccessible=true`, which `Main` sets itself before any Netty class loads (see "FlightSQL's memory" under "Limits on unauthenticated work"), so the suite runs the allocator production runs.

## Architecture - the bits that span multiple files

The process is a single uber-jar that exposes **five** sockets (the fourth, the native Quack
front door on `:9494`, and the fifth, the node verdict listener, are described after the list):

1. **Manager REST + React UI** on `:20900` ([ManagerServer.scala](src/main/scala/ai/starlake/quack/ondemand/ManagerServer.scala), Tapir + HTTP4s Ember). Endpoints live under `ondemand/api/*Handlers.scala`. The React SPA at `/ui/*` is served from `src/main/resources/ui` (built by the resourceGenerator above).
2. **Arrow FlightSQL edge** on `:31338` ([FlightEdgeServer.scala](src/main/scala/ai/starlake/quack/edge/FlightEdgeServer.scala) → [FlightProducerImpl.scala](src/main/scala/ai/starlake/quack/edge/FlightProducerImpl.scala) → [FlightSqlRouter.scala](src/main/scala/ai/starlake/quack/edge/FlightSqlRouter.scala)). TLS is on by default, cert auto-generated at `certs/server-{cert,key}.pem` if missing.
3. **Quack nodes** on `:21900-22500` - child processes spawned by [scripts/spawn-quack-node.sh](scripts/spawn-quack-node.sh) (local mode) or pods ([KubernetesQuackBackend.scala](src/main/scala/ai/starlake/quack/ondemand/runtime/KubernetesQuackBackend.scala)). Each node is a qod-node process (see "qod-node" below) serving DuckDB Quack, with a metastore env-var contract: `TenantDb.NodeEnvKeys` = `pgHost / pgPort / pgUser / dbName / catalogAlias / schemaName / dataPath / encrypted / createDatabase`, and nothing else of a metastore (tenant-admin input: `BASH_ENV`, `PATH` or `QOD_NODE_BIN` there once ran commands as the launcher's user). Every launcher passes only those keys and the metastore's own secrets (local spawn env, Kubernetes pod env, fleet assignment env, and `qod fleet join`'s overlay, `_NODE_ENV_KEYS`), dropping any other key with one ERROR per pool and key that says how to remove it (`NodeMetastore`); `database/create`, `database/update` and a manifest import refuse a key outside `TenantDb.KnownMetastoreKeys` (those minus the manager-stamped `encrypted`, plus `pgPassword` / `encryptionKey`) with `400 invalid_metastore_key` before writing anything, except that `database/update` takes a secret-named one (`TenantDb.SecretKeys`: no response shows it, an update leaving it out keeps it) sent empty, which removes it (`TenantDb.metastoreUpdateKeyError`). A manifest export leaves such keys out, one ERROR each, so it always imports back; it drops an encryption key silently, in any spelling (no export carries one). The secrets (`pgPassword`, `encryptionKey`, federation and object-store SQL, the node token and the node key: `NodeSecretFiles.Keys`) arrive as files under `QOD_SECRETS_DIR`, never as env vars, and the catalog password then rides a temporary DuckDB secret, never an ATTACH string (`CatalogPgSecret`). **`spawn-quack-node.sh` (or `spawn-quack-node.ps1` on Windows) is invoked by [LocalQuackBackend.scala](src/main/scala/ai/starlake/quack/ondemand/runtime/LocalQuackBackend.scala) - don't run it directly.** `LocalQuackBackend.defaultCommand` picks the script by OS: bash on Unix, `powershell.exe -File spawn-quack-node.ps1` on Windows (path from `spawnScriptWindows` / `QOD_SPAWN_SCRIPT_WINDOWS`).

4. **Native Quack protocol front door** on `:9494` ([QuackFrontDoorServer.scala](src/main/scala/ai/starlake/quack/edge/quack/QuackFrontDoorServer.scala) → [QuackFrontDoor.scala](src/main/scala/ai/starlake/quack/edge/quack/QuackFrontDoor.scala)): what a DuckDB client `ATTACH 'quack:host:9494' AS qod (TYPE quack, TOKEN 'tenant=..&pool=..&user=..&password=..')`es to. The token is the FlightSQL JDBC query string; the handshake is the Flight edge's ([EdgeHandshake.scala](src/main/scala/ai/starlake/quack/edge/EdgeHandshake.scala)); each statement runs through `FlightSqlRouter.executeWith` (the pipeline generalized over the node call) and is relayed to one raw node connection per statement ([QuackNodeLink.scala](src/main/scala/ai/starlake/quack/edge/quack/QuackNodeLink.scala)), the node's result bytes going back to the client untouched. Every PREPARE a link sends follows a fresh session handle (see "qod-node" below); any failure on a client transaction's link (a handle that fails, a statement lost in transport) ends that transaction like an admin kill (the link is disconnected, the pin dropped, the statement and every later one answered with an aborted-transaction error until the client's ROLLBACK, which is served on a fresh link; a client COMMIT lost after it was sent is answered as of unknown outcome instead), because the transaction cannot go on there: DuckDB 2.0 refuses every statement but ROLLBACK and COMMIT in an aborted transaction, the handle's CALL included, and a statement lost in transport may have run; anywhere else a refused handle is a retryable failure. Statements calling a `duckdb_*()` catalog function are soft-pinned to the node that served the session's previous one (`QuackSession.catalogNode`): the client joins its ATTACH-time schema and table listings on per-process oids, so split across nodes they yield a catalog with no tables. The manager never encodes DuckDB chunks; [QuackWire.scala](src/main/scala/ai/starlake/quack/edge/quack/QuackWire.scala) parses only headers and the small request messages and speaks Quack protocol 3 only (DuckDB 2.0 clients; a 1.x client gets `requires a DuckDB 2.0 client`). It admits a 2.0 client's `BEGIN TRANSACTION` and its INSERT data path (`scan_data_from_quack_client`, scoped to that client's own connection). Config block `quack-native` (`QOD_QUACK_*`), TLS off by default because the DuckDB client only speaks plain HTTP to loopback hosts. `QuackCompatibilitySpec` drives the real `duckdb` CLI and Python client end to end (cancelled without `duckdb` on PATH). Design: docs/superpowers/specs/2026-09-20-native-quack-front-door-design.md.

   The manager-to-node native client (libquackwire, on by default) holds a heartbeat lease on each node connection: `QOD_QUACK_NODE_LEASE_SEC` (default 30, floor 1; a heartbeat goes out every third of it). Node connections are released on error and on cancel. It is the only manager-to-node client: there is no embedded fallback.

5. **Node verdict listener** on the REST port + 1 (`QOD_INTERNAL_PORT`, `quack-on-demand.nodeAuthz.internalPort`; the chart's 20901; [NodeInternalServer.scala](src/main/scala/ai/starlake/quack/edge/authz/NodeInternalServer.scala)): the only place serving `POST /api/internal/authz/decide` and `/credentials`, in plain HTTP, so clients filling the REST port's slots cannot stop authorization. It binds loopback for a loopback decide URL, the URL's own address when that is a literal address of this host, else the REST bind address (`BootPreflight.internalBindHost`; no name is looked up). A call's body, up to 1 MiB, is read before its signature can be checked, as the signature covers it, so a client that reaches this port can hold a slot and about 1.4 MiB of heap per connection: at most 256 slots (`QOD_INTERNAL_MAX_CONNECTIONS`), a 5 s body deadline, a 10 s idle timeout. Keep clients off this port with the network: filling it stops authorization, and on Kubernetes with no NetworkPolicy any pod reaches it. `BootPreflight.checkInternalPort` refuses a port equal to another listener's or in the node port range.

**Limits on unauthenticated work** (docs/superpowers/specs/2026-11-01-unauthenticated-work-limits-design.md). `BoundedBody` reads a request body under a size and a time limit on REST, MCP and the node routes, in arrays of at most 64 KiB while it arrives (G1 gives a larger array whole regions, so one growing array cost twice its size while a client stalled), and refuses JSON nested past 256 levels (`BoundedBody.MaxNesting`, REST `400 json_too_deep`): circe's failure message prints the value it refused, its printer recurses per level and a `StackOverflowError` is fatal under `IOApp`, so 8 KB of `[[[...]]]` halted the JVM, inside any size limit and inside Tapir, where nothing can catch it. A request reading a body before a credential (:9494, public REST routes, node routes) refuses chunked framing with 411 before pulling it, as Ember rescans a chunk-size line per read. REST (`quack-on-demand.restLimits`, `QOD_REST_*`) applies it inside `apiKeyGuard`: 64 KiB on core public routes and 1 MiB on a module's public route (`QOD_REST_MAX_MODULE_PUBLIC_BODY_BYTES`), both read before the route; 16 MiB, after admission, everywhere else under `/api` and on `/mcp`; the guard reads no body outside `/api` (`/mcp` applies the limit itself), so a module route that reads one lives under `/api`. :9494 (`QuackBodyRead`) reads the message header first: a CONNECTION_REQUEST gets 64 KiB and 30 s, a message naming a live session 256 MiB (`QOD_QUACK_MAX_BODY_BYTES`) and 300 s (`QOD_QUACK_CONNECT_BODY_TIMEOUT_SEC` <= `QOD_QUACK_BODY_TIMEOUT_SEC` <= 86400, refused at boot otherwise), and anything else is answered from the bytes already read; with TLS on, its reads have a 60 s deadline, as Ember runs the handshake before its own timeouts start. `QuackCredentials` refuses a token of more than 32 `key=value` pairs before it decodes any.

An out-of-memory error is fatal to the manager, so what connections hold before any credential must fit the heap. Ember parses a request line and a header block into objects before any route runs and holds them for the whole request, about 40 heap bytes per wire byte, applying its limit to each: `QOD_REST_MAX_HEADER_BYTES` 8 KiB, `QOD_QUACK_MAX_HEADER_BYTES` 4 KiB, the verdict listener 4 KiB, answered 431 (`BoundedBody.headerLimitHandler`, total, since Ember calls it as a plain function on a failed parse). `ConnectionBudget` takes no more connections than fit a fifth of the heap, whatever `QOD_REST_MAX_CONNECTIONS`, `QOD_QUACK_MAX_CONNECTIONS` and `QOD_INTERNAL_MAX_CONNECTIONS` say (at 1 GiB 260, 431 (322 with TLS) and 145; at the chart's 2 GiB 520, 863 (644 with TLS) and 256), and boot prints a line when it takes fewer, saying only to raise the heap for the verdict listener, whose limits are constants. A manager with a module's public routes counts the larger of the REST public body limit and the module's (`ManagerServer.unauthenticatedBodyBytes`; 1 MiB by default, so 118 REST connections at 1 GiB and 237 at 2 GiB). A client that stops reading holds its response in memory until the idle timeout, so no anonymous route answers with a large copy per request: `MetricsEndpoint` shares one scrape for 4 s, written in chunks of at most 8 KiB, with a 30 s deadline on a response and 10 s on the wait for a scrape (503).

**FlightSQL's memory.** Arrow leaves gRPC's message and header limits at `Integer.MAX_VALUE`, so `FlightEdgeServer` sets them: 16 MiB per message (`PROXY_MAX_INBOUND_MESSAGE_BYTES`, `EdgeConfig.maxInboundMessageBytes`, whatever the method; gRPC answers `RESOURCE_EXHAUSTED` as soon as the length prefix arrives, before it holds any of the message, with or without credentials) and 64 KiB per header list (`PROXY_MAX_HEADER_BYTES`; a client that ignores the limit the server's SETTINGS advertise gets a GOAWAY, which `FlightEdgeLimitsSpec` pins with a hand-written HTTP/2 client). Direct memory was the other fatal path. Netty counted its buffers in the JDK's own direct budget (`java.nio.Bits`: `-XX:MaxDirectMemorySize`, the heap's size when unset), so anonymous calls stalled one byte short of a 16 MiB message, about 22 MiB of direct memory each, filled it, and an ordinary REST socket read then failed in `sun.nio.ch.Util.getTemporaryDirectBuffer` with an `OutOfMemoryError` on a compute thread, which halts the JVM: the image at the chart's settings exited with code 1 (`OOMKilled` false) within 25 s of 64 stalled calls and 300 anonymous REST threads. `NettyMemory.configure()`, the first statement of `object Main` (`NettyMemorySpec` pins that by reading the source and by starting a JVM without the flag), sets `io.netty.tryReflectionSetAccessible=true` before any Netty class loads. With the jar's `Add-Opens java.base/java.nio`, Netty then takes its no-cleaner allocator (`PlatformDependent.useDirectBufferNoCleaner`): it allocates with `Unsafe`, outside `Bits`, counts against `io.netty.maxDirectMemory` (the JDK's limit unless set) and fails the stream that asks past it. The same load leaves the JVM running with Netty pinned at its cap (the chart's 512 MiB, or 1 GiB at the JVM's own defaults); gRPC logs each refusal as `Transport failed` at INFO, below the `io.grpc` WARN clamp of `logback.xml`, so the default log shows nothing for most of them and, now and then, a Netty WARN naming `OutOfDirectMemoryError`. The Flight server is `grpc-netty` on `io.netty`; `grpc-netty-shaded` (a transitive dependency) is on the classpath too, reads a relocated property name and serves no listener. Direct memory can therefore reach twice `-XX:MaxDirectMemorySize`, Netty's and the JDK's each, and `-Dio.netty.maxDirectMemory=<bytes>` bounds Netty's alone: 64 clients sending 15 MiB messages took a 4 GiB container to 3.0 GiB at the JVM's own defaults. `Main.bootManager` prints `NettyMemory.SharedWarning` on stderr when `NettyMemory.separate` is false (a `-cp` launch without `--add-opens=java.base/java.nio=ALL-UNNAMED`, or an operator who set `io.netty.tryReflectionSetAccessible=false` or `io.netty.maxDirectMemory=0`).

**FlightSQL before a credential.** gRPC holds a stream's headers until its client ends it, queues a call before any interceptor runs and has no limit of its own on streams, and Arrow runs a DoPut on a thread that waits for its first message past the client's disconnect. On the 0.11.0 image, with no credentials and on one connection, the container was killed out of memory between 36,000 and about 54,000 DoPuts of HEADERS alone, and 40,000 GetFlightInfo streams holding 60 KiB of headers gave `OutOfMemoryError` and a process that stayed up and answered nothing. So nothing before a credential is unbounded. `AnonymousCallGuard`, a gRPC interceptor that Arrow's `headerAuth` runs ahead of (a credential that fails never reaches it), refuses a call with no credentials with `UNAUTHENTICATED` unless its method is `GetFlightInfo` or `DoGet` and it is not compressed (`ListFlights` answers `UNIMPLEMENTED`: pyarrow's `wait_for_available()` takes nothing else for an answer), holds at most `PROXY_MAX_ANONYMOUS_CALLS` (256, then `UNAVAILABLE`), and refuses a message past `FlightProducerImpl.MaxAnonymousCommandBytes` (4 KiB) and what wraps it as it arrives; `FlightProducerImpl.refuseAnonymous` then serves only `CommandGetSqlInfo` and `CommandGetXdbcTypeInfo`, what the ODBC driver behind Power BI and Excel asks for before it replays its bearer. `ConnectionProbation` wraps gRPC's protocol negotiator (an `@Internal` API, as nothing public binds a client that never acknowledges SETTINGS; `FlightEdgeLimitsSpec` pins it) and holds each connection, until a call on it passes `headerAuth` with a credential, to at most 8 streams open (`MaxStreams`), 16 created (`MaxTotalStreams`), `PROXY_MAX_HEADER_BYTES` + 16 KiB read (`readBudget`, 80 KiB, plus one read buffer of slack, then it stops reading until proof or the timer: autoRead off and `read` dropped, both needed, as `Http2ConnectionHandler` and `SslHandler` ask for a read after every read), 60 s (closed from the pipeline's first context, as gRPC's own close waits for every stream), and a place among at most `PROXY_MAX_UNAUTHENTICATED_CONNECTIONS` (1024) and no more than fit a tenth of the heap at `heldBytes` (2.66 MiB with TLS, 4.61 MiB without: 76 and 44 places at 2 GiB, 38 and 22 at 1 GiB; computed from field counts, not measured), one past that being closed at once, before TLS. A first statement larger than the budget pauses there until its credential is checked, so with none that works it waits for the timer and reads `UNAVAILABLE`. DATA on a stream refused past a limit closes the connection (Netty keeps no record of a refused stream; GOAWAY), which is how a real client's next call ends past 16 streams, and it then reconnects. A call waiting for a thread keeps its headers whatever became of its connection (up to 108 KiB, and about 60 KiB for a 20-byte HEADERS frame, as gRPC decodes a `-bin` value into one array per comma-separated piece and HPACK references it for a byte), and the pool (`FlightEdgeServer.boundedExecutor`, `PROXY_FLIGHT_THREADS`, 512, at least 16) has an unbounded queue, so a stalled pool would let reconnects fill the heap: while more tasks wait than `ConnectionProbation.maxBacklog` (the pool's threads, and no more than fit a twentieth of the heap; a waiting call whose client left counts twice), a new connection is closed at once, with or without a credential. A connection that has shown a credential does not count and is touched by none of this; it shares event loops, the pool and the direct memory budget with everyone, so a flood can slow it. `FlightProducerImpl.acceptPut` checks the session first, then takes one of `PROXY_FLIGHT_THREADS / 4` slots (`tryAcquire`; `UNAVAILABLE` "too many concurrent updates; retry") and one 30 s deadline, by interrupt (`TIMED_OUT`), over the wait for the first message and the drain, which stops at 16 MiB (`INVALID_ARGUMENT`; each batch counted as at least 1 KiB). JDBC and ADBC half-close at once; Arrow Java's literal `FlightSqlClient.executeUpdate` reads its answer before it half-closes, so it gets `TIMED_OUT`.

A tenant admin picks an issuer, and anyone can then call the routes that talk to it, so nothing an issuer serves may halt the manager (a hanging one can hold FlightSQL's threads: "Known and accepted" below). `BoundedHttp` reads its answers, and OPA's, under a size limit and a deadline over the whole exchange, over HTTP/1.1 only (the JDK's HTTP/2 reader queues frames with no bound: one anonymous request halted the manager), with a client of its own for each exchange, shut down when the exchange ends (the JDK keeps a connection whose response headers it refuses for as long as the client lives, the pool of a kept client has no size limit so a key set redirecting to new addresses left a connection per hop, and a client shut down under exchanges still running on it leaks their sockets; so a client kept for its origin let anonymous callbacks exhaust descriptors and halt the manager), and at most 128 exchanges at once, half to any one origin (256 for OPA; past that it fails at once, and `OidcDiscovery` does not remember that as a failure). The known price is a TIME_WAIT on the manager's host for each exchange: on Linux, sustained exchanges to one identity provider or OPA instance above about 470 a second run out of local ports unless `net.ipv4.tcp_tw_reuse=1`. Errors quote no status line or body of a server's answer, only its status code, as the status line of whatever a URL names would be a network probe; a discovery body that is no JSON is answered with one fixed text (`OidcDiscovery.NotJson`), since the parser's own failure quotes the start of it. `shutdownNow` needs JDK 21: `BootPreflight.checkHttpClientShutdown` prints a warning on stderr at boot on an older one (`BoundedHttp.canShutdownNow`). `OidcDiscovery` refuses a document whose `jwks_uri`, `token_endpoint` or `authorization_endpoint` is not an absolute http(s) URL (`isHttpUrl`), or whose field, or the issuer URL, is over 2048 characters (`MaxFieldChars`, `MaxIssuerUrlChars`), as a failure naming the field and never its value; a failure is remembered for 10 s and a document for a day. `OidcBearerAuthenticator.authenticate` runs under `TooDeep`, since a signed token can nest a claim deep enough to overflow its printing. Its JWKS retriever has 50 KiB and 5 s, and no `file:`. A logout writes a row and calls Starlake only for a live session; a refused `sso_required` login writes one row a minute per tenant, and a denied SSO redeem one a minute (`publicDenialAudit`); `AuditRecorder.restAs` strips control characters (a tab or line break reads as a space) and caps `actor`, `tenant` and `target` at 256 characters (`MaxNameChars`) and each `detail` value at 1024 (`MaxDetailChars`).

Known and accepted (spec section 12): a connection flood can still fill the slots of :20900 and :9494 (4 to 17 requests or, on a TLS :9494, handshake packets per second), which in fleet mode also stops the servers' heartbeats (they stay on REST; their nodes are unroutable after `heartbeatTimeoutSec`, 30 s); a tenant admin rotating several hanging issuers and an anonymous flood can make another tenant's SSO login fail while they last; a YAML manifest nested about 1000 levels overflows its parser and halts the manager, but `manifest/import` is superuser-only. On :31338: about 1.3 silent connections a second (60 s each) keep new connections out at a 2 GiB heap with TLS; a flood of wrong passwords holds Flight threads in bcrypt (password checks have no gate), and bearer tokens naming a tenant whose identity provider hangs park them on one JWKS lock (Nimbus' `CachingJWKSetSource`, up to 15 s each, the lock held 10 s in every 30), so either stalls FlightSQL for every tenant while it lasts, `maxBacklog` keeping it off the heap; `DatabaseAuthenticator.verifyPassword` holds one of its 5 connections through bcrypt and gives up after 5 s, so clients retrying a wrong password keep a correct login from being checked (it reads `AuthFailure.Unavailable`: Flight `UNAVAILABLE`, REST `503 auth_unavailable`, never a failed login, as a `SQLException` says nothing about the password), and those 5 connections are also all that limits bcrypt today, so removing the hold needs a gate on the checks; a client with a valid login is held to none of these limits and can still fill the heap; behind an HTTP/2 proxy probation applies per upstream connection, so once that one has shown a credential only `AnonymousCallGuard` binds calls with no credentials, and what waits for a thread has no limit there; no refusal here is logged or counted.

A FlightSQL request flows: `client → FlightProducerImpl → AuthenticationService → FlightSqlRouter.execute (policy holders' guard, CLS / RLS rewriters, metadata filter) → StatementClassifier (READ/WRITE/DDL) → Router.pick(snapshot, kind) → QuackHttpAdapter (session handle, then the statement) → child node's /quack HTTP endpoint → qod-node's parse screen and plan decision (verdict from the replica's DecideRoute / AuthzDecider) → Arrow stream back through Flight`. The node's verdict is the only table, function, setting and file check: the gateway has none of its own (in-engine authorization Phase D). Every FlightSQL request (and every REST / MCP statement through `FlightSqlRouter.execute`) runs on a node connection of its own, so a transaction lives within one request: `execute` refuses a request that leaves a transaction open or commits / rolls back one it did not open (`FlightSqlRouter.transactionControl`, `400`-class `BadRequest` naming the native front door), and `BEGIN; ...; COMMIT` sent as one request runs and leaves the session with no transaction state. Interactive transactions are the native front door's (it holds the link). A transport failure outside a client transaction is retried once on another node only when the statement never left (`notSent` on `QuackError.Transient` / `NodeOutcome.Transient`: no connection, a refused or unanswered session handle, a draining node) or is a lone read (`FlightSqlRouter.repeatable`); any other statement (a write, DDL, a batch, `BEGIN; ...; COMMIT`) may have committed before its answer was lost and is answered INTERNAL "outcome is unknown" (`FlightSqlRouter.outcomeUnknown`), never sent twice. Prepare never runs an `EXPLAIN ANALYZE` or a write answering with rows (`RETURNING`, a WITH-headed write): their schema is advertised absent (`PrepareStrategy.Unprobed`). A metadata call's stream carries the schema its FlightInfo announced (`FlightProducerImpl.underAnnounced`), as ADBC refuses a difference; `getStreamCatalogs` lists no `memory` catalog, so a client that starts from the catalog list, as Power BI does, shows nothing of a memory database. Tests for the routing core ([RouterSpec.scala](src/test/scala/ai/starlake/quack/route/RouterSpec.scala), [StatementClassifierSpec.scala](src/test/scala/ai/starlake/quack/route/StatementClassifierSpec.scala), [FlightSqlRouterSpec.scala](src/test/scala/ai/starlake/quack/edge/FlightSqlRouterSpec.scala)) exercise this without the Flight wire.

### qod-node (the node process)

Every node is a qod-node process ([native/qod-node](native/qod-node/)): our own executable on the
unmodified official DuckDB release library (the shared libduckdb on Linux and macOS, the release
static libraries on Windows), the host the in-engine authorization work builds on (design:
docs/superpowers/specs/2026-10-23-in-engine-authorization-design.md). The spawn scripts hand it the
init SQL on stdin (`--init -`), never on disk; it runs it statement by statement and carries on past
a failing one, as the DuckDB CLI did, and does the strict steps itself: the node's secret directory
first, then `INSTALL` / `LOAD quack` (right before the first init statement that restricts the node's
file or network access, so after the trusted statements that
set `custom_extension_repository` / `extension_directory`; else after the init SQL), `remote_pushdown`
put back into `disabled_optimizers` if a trusted init statement dropped it, the parse screen and
`quack_serve`, with the token, the node key and the init nonce it reads from `QOD_SECRETS_DIR` (an
ephemeral directory it removes once read; the spawn script removes it too on every exit). The init
stream's first line is `-- qod:init-nonce <nonce>` (`QOD_INIT_NONCE`, fresh per start); qod-node
refuses to run any init SQL when it is missing or differs from the nonce it read. It has no CLI
dot-commands, so init SQL needs no dot-command screen. It exits when its
wrapper does (`--parent-pid`) or on SIGTERM; a stop while it is still starting ends it at once. A
failing init statement is logged with DuckDB's error minus the `LINE n:` statement excerpt (init SQL
carries credentials).

Every statement the manager sends a node runs for a verified principal. Before each PREPARE on a node
connection (a relayed client statement, a stamp prelude, a COMMIT) the manager sends its own PREPARE
`CALL system.main.qod_session('<handle>')`: a session handle `<base64url JSON>.<hex HMAC-SHA256>` naming
the principal (`NodePrincipal`; `System` for probes, stats, attach checks, Iceberg metadata,
maintenance, merges, front-door admin results and RoutedExecutor's system caller), the node, the pool,
a single-use nonce and a 60 s expiry, signed with the node's key (`NodeKeys`: `HMAC-SHA256(secret,
"qod-node-key/v1/" + nodeId)`). The call is qualified, like the screen, so that no macro a session
creates in its default catalog or temp schema can shadow it. The key reaches qod-node as the secret
file `QOD_NODE_AUTHZ_KEY` and the id as `QOD_NODE_ID` (`--node-id`); the secret is `QOD_NODE_KEY_SECRET`
when set (at least 32 characters, identical on every replica), else one value generated on first boot
and kept in the control plane (`qodstate_node_authz`, Liquibase `0047`), so rotating it means restarting
every node. Unless `QOD_NODE_KEY_SECRET` is set, whoever can read the control-plane database (backups,
dumps, read replicas) can mint a handle for any principal on any node. `QOD_INTERNAL_URL` (the replica
URL carried in every handle, which nodes call for verdicts; `quack-on-demand.nodeAuthz.internalUrl`)
defaults to `http://<QOD_POD_IP>:<internal port>` when the pod IP is set (the Helm chart sets it from
the downward API, so each replica names itself), else `http://127.0.0.1:<internal port>`.
`BootPreflight.checkInternalUrl` refuses, before any socket opens, an unset one in fleet mode (each
replica's own address on the servers' private network, never a load balancer or an address several
replicas share), a loopback or unspecified host on Kubernetes (`kubernetes` or `k8s`, any loopback
form `inet_aton` reads; a hand-written manifest must set `QOD_POD_IP` or `QOD_INTERNAL_URL`), and any
URL qod-node's parser (`ParseHttpUrl`) refuses: not lowercase `http://`, user info, a query or
fragment, a bad port. It also refuses an explicit URL naming the REST port, which no longer answers
nodes. Boot prints
`node decide URL: <url> (listening on <host>:<port>)`. The chart fails on `env.QOD_INTERNAL_URL` at
any replica count (its Service has no verdict port), on `env.QOD_INTERNAL_PORT` and on
`env.QOD_POD_IP`; its NetworkPolicies let node pods reach the manager pods on TCP 20901 (the verdict
listener, where every call authenticates with the node's key), whatever `networkPolicy.ingressFrom`
says, in the release namespace only.

qod-node checks the handle in its parse screen (`qod_screen`, installed as
`quack_authorization_function`, which refuses any PREPARE on a connection with no current principal)
and in `qod_session`'s bind (nonce, one principal per connection), and a `post_bind` hook refuses
every statement on a quack connection without one. A refused handle drops the connection unsent
(retryable). The health probe's canary runs a handle-less `SELECT 1`: a node that answers it is
unhealthy and never routed to. A node started before session handles does not know `qod_session` and
is never routed to either. Nor is a node of an earlier node protocol, on any backend: the handle's
`v` is 4 (`NodeHandles.Version` = qod-node's `kHandleVersion`, node protocol 6), and such a node
refuses every handle of another version, the probe's included (and a protocol 6 node refuses theirs).
Restart every node after upgrading. What a principal may do is decided on
the node (below).

A handle lives 60 s and the node checks it at each bind, so a write that may run long sends one PREPARE
per statement, each after a handle of its own: a stamped write (`QuackHttpClient.queryStamped`: the
stamp, the statement, the COMMIT, which fires when the result is read) and the merge
(`QuackHttpAdapter.sendAll`). A table restore and an undrop are stamped writes: one statement under
its own commit message (`ExecCaller.commitMessage`), so the replace may run as long as
`restoreTimeoutSec` (`undropTimeoutSec`). A write that names its commit message is stamped whatever
`QOD_STAMP_WRITES` says (the knob governs the `flightsql <verb>` stamp of client writes) and its stamp
is never best-effort: a restore's commit fence matches that message and both settle a lost answer by
it. `QuackHttpClient.queryStampedRequired` sends nothing when the stamp fails (a transient failure is
`notSent`, so the router may try another node; a node's own refusal is not retried), and the router
refuses a named write no stamp applies to (`QuackHttpAdapter.NoStamp`). A client write's stamp stays
best-effort (`queryStamped`). A replace that outlives the bound is cancelled and rolls back when its
statement answers, so no COMMIT can land after a timeout. A restore's COMMIT fires when its result is
read (`WriteResults.drain`, whose failure says by its type whether the node itself refused,
`nodeRefused`). A statement that failed before the COMMIT is a refusal (409 `concurrent_write` or 502
`restore_failed`) and the fence row comes off. Every end of the COMMIT (a clean answer, a lost one (a
broken connection, a 5xx), the node's own error, a timeout) is settled by the catalog first, by the
restore's own commit message, which also names `newSnapshot`: a node's error says what the node saw, not
what the catalog's Postgres did, and a connection to the catalog lost during the metadata COMMIT can
leave that commit durable. Found is landed; a catalog that cannot be read is `restore_outcome_unknown`
(check the table before retrying); absent is a refusal only when nothing can land (the node itself
refused the COMMIT, or a timeout cancelled the call before any COMMIT went out) and
`restore_outcome_unknown` otherwise, never a refusal, since a COMMIT may be in flight (a kill leaves one
on another thread) or in DuckLake's retries and a read can beat it. The fence row stays armed on every
unknown outcome: a late COMMIT is still held to the table state the restore validated and cannot erase a
write committed meanwhile, and a leftover row matches only that restore's own message. An undrop's
`undrop_outcome_unknown` is the same idea without a fence: a timeout or a failure reading its result's end
is settled from the catalog too. Found is success. A catalog that cannot be read is
`undrop_outcome_unknown`, and so is an absent commit with a table now holding the target name, since
another writer may have created it. An absent commit with no such table is `undrop_failed`, though after a
lost answer a COMMIT still in flight can land; it says `retryable` and "nothing was committed" only when the
node itself refused the COMMIT because DuckLake gave up on its retries. A restore answers that refusal `409
concurrent_write`, like the fence's (`MergeDecision.retriesExhausted`).

**Node-side authorization (node protocol 6).** A non-superuser statement is decided on what
DuckDB resolved for it, on the node, before it runs, and nowhere else. The parse screen re-parses every PREPARE with
DuckDB's own parser and refuses what binding would already do outside the node (IMPORT, CONNECT,
DESCRIBE / SHOW / SUMMARIZE of a table or query (the listing forms such as SHOW TABLES stay), PRAGMAs
off a read-only list, the fresh-connection functions,
`query()`/`query_table()` with a non-literal argument; a literal one is screened like the statement,
at any depth), any call-shaped name the generated classification does not list or classes superuser,
anywhere in the text, and, in every slot DuckDB evaluates while binding (SET values,
table-function and PRAGMA arguments, LIMIT/OFFSET, COPY options, AT clauses, ...), any function not
classed allowed and `nextval`/`setval`/`currval`; every location a statement names (file readers,
COPY, ATTACH, IMPORT, EXPORT, LOAD) is sent to the manager as a `kind = screen` description before
binding. Replacement scans are off for such a session (`ClientConfig::use_replacement_scans`, no SQL
reaches it): `FROM 'x.csv'` is "table not found". Every node opens with `remote_pushdown` in
`disabled_optimizers` (DuckLake-on-Postgres folded constant calls before `post_bind`).
A DuckLake read at any snapshot (current, `AT (...)`, the change feed) is described as the table its
stable id names now (DuckLake keeps one oid per table id), under its current names, columns matched
by field id, `renamed: true` when the statement wrote an old name (the decider then refuses it to a
principal with column or row policies); a table dropped since is refused to non-superusers.
Every principal, superusers and the manager included, is refused SET / RESET of
`disabled_optimizers`, `enable_optimizer`, `debug_disable_optimizer`,
`quack_authentication_function` and `quack_authorization_function` on a quack connection (`QoD:
<name> cannot be changed`, the describer's `IsLockedSetting`); only a trusted init statement can
touch them, and qod-node puts `remote_pushdown` back after the init SQL.
`post_bind` describes the bound plan (objects with access and read columns, functions, settings with
the scope DuckDB applies, control operations; canonical JSON, fingerprint = SHA-256) and walks the call
names as written, in the client text and transitively in every view, secure view, macro, table macro
and stored expression the statement reaches (generated columns read, defaults of write targets, CHECK
constraints, triggers), resolved as DuckDB resolves them: a name that resolves to a
non-built-in entry or that the classification does not list is refused. `pre_optimize` describes the
plan again, so an extension's own plan rewrite cannot slip past; `OnFinalizePrepare` refuses anything
that reached it without a verdict and `OnRebindPreparedStatement` rebinds when the principal or policy
version changed. The node asks the replica named in the handle: `POST /api/internal/authz/decide`
(`DecideRoute`, on the verdict listener, never in OpenAPI, CLI parity or
`McpCoverageSpec`), body signed with the node key (`X-QoD-Node`, `X-QoD-Signature`), answered with a
verdict in the handles' wire format (`<base64url JSON>.<hex HMAC>`). `AuthzDecider` judges with the
effective set the handle was minted for (`MintedHandles`, by nonce, kept 5 min past expiry; an unknown
nonce is a retryable refusal): superusers and the system principal never call it; always-on rules
(superuser-only functions and settings, global scope, writes or DDL in `system`, the
`__ducklake_metadata_*` catalogs, extension operators; the temp catalog always allowed); objects by the
tenant's ACL mode through the existing grant matching or OPA (`OpaInput` gains `columns`, `via`,
`locations`); every function and setting by classification (`native/qod-node/functions.json` and
`macros.json` from qod-node, `src/main/resources/authz/function-classes.tsv` and
`setting-classes.tsv`; an unknown one is refused "function <name> is not classified"); files by
`LocationChecker`; PAT `branchOnly` and attenuation. There is no switch: enforcement is always on.
`BootPreflight.checkRetiredSettings` (decision 28) judges the removed settings, from the variable or
else its `quack-flightsql.acl.*` / `quack-flightsql.nodeLockdown` path: `QOD_ACL_ENABLED` and
`QOD_NODE_LOCKDOWN` set to off (`false`/`no`/`off`) refuse boot, any other value boots with a stderr
warning that it is enforced regardless; `QOD_ACL_FILTERED_METADATA` and `QOD_ACL_DIALECT` refuse boot
at any value; `QOD_API_KEY` and `QOD_ADMIN_ROLE` (renamed `QOD_ADMIN_KIND`) only warn. Warnings go to
stderr, never the logger, whose default level is ERROR. A plan
verdict lives 60 s (an OPA tenant's: its OPA cache TTL,
at least 5 s) and is cached per node (LRU, `QOD_AUTHZ_CACHE_ENTRIES`, default 10000) by principal,
policy version, session context and fingerprint (statements that differ only in constants share
one); screen verdicts are never cached. Decide timeout `QOD_AUTHZ_DECIDE_TIMEOUT_MS` (default
2000). The manager reads both (`quack-on-demand.nodeAuthzLimits`, refused at boot outside qod-node's
ranges, 1 to 60000 ms and 0 to 10000000 entries) and forwards them to every node on every runtime:
the local spawn env, the Kubernetes pod env and the fleet assignment's `authzLimits`
(`NodeAuthzLimitsConfig`); a node never inherits them from elsewhere. A timeout, an unreachable
replica or a 503 is `QoD: authorization unavailable, retry`, which the gateway answers as Flight
`UNAVAILABLE` (`FlightSqlRouter.isNodeAuthzUnavailable`) for a single statement and the native front
door relays; a multi-statement submission so refused is answered `INVALID_ARGUMENT` "not retryable
as a whole: the statements before the refused one may have run" (`FlightSqlRouter.BatchNotRetried`).
Every other node refusal reads `QoD: <reason>`, is permanent and is booked as a denial
(`FlightSqlRouter.isNodeRefusal`: Flight `UNAUTHORIZED`, `access denied: Permission Error: QoD:
...`, history status `denied`, a `data-denial` audit event). qod-node
resolves a `QOD_INTERNAL_URL` host name once per 30 s per name (single-flight, stale addresses served
for 1 s after a failed refresh), outside the decide deadline. The policy version is
`qodstate_tenant.policy_version` (Liquibase `0050`, values from the global
`qodstate_policy_version_seq`), bumped by the plpgsql trigger `qod_bump_policy_version()` on every RBAC
table and on the tenant's ACL and OPA columns (`0051` adds `qodstate_tenant_location`, `0052`
`qodstate_user_s3_credential`, which bumps the user's tenant; `0054` adds
`qod_bump_every_policy_version()`, which bumps every tenant, on a change to the data-path deny set:
`qodstate_tenant_db` and `qodstate_managed_prefix`), carried in every handle
with `ctx`, the SHA-256 of the session's authorization context (JWT roles and groups, a PAT's
restriction, the pool, every claim of a bearer token), so a restricted token never reuses a full
session's verdict, and with `path`, the session's default `<catalog>.<schema>` (the router's USE
prelude), signed. A secure view's body runs with its owner's rights (`via` on its objects and
functions) and only for reads; a boundary is refused while the session holds a temp entry other than a
table, sequence, index or DuckDB's own pivot enum, a `SET VARIABLE` variable, a session setting other
than `schema` / `search_path`, a path other than the handle's, or when its definition holds an
unqualified table, a plain view, a non-built-in type, `query()`, a time-travel read or a call that
resolves outside the system catalog. The gateway's text screens, its table-ACL check
(`PostgresAclValidator`, `OpaValidator`, `StatementValidator`) and its file screens are gone (Phase D,
decision 29). What the manager still refuses before a node sees a statement: for a principal holding
column or row policies only, the parse screens the policy rewriters depend on (`ProtectedWriteGuard`
in `edge/policy`, decision 27: a quoted identifier holding a dot, a catalog-reaching call such as
`query()` / `query_table()` or a SQL-valued SET, an ambiguous two-part name headed by an attached
catalog, a batch that moves the schema or search path, and a policy rewrite reaching anything the
original did not, `RewriteScope`); for every caller, a client data scan off the native front door
(`FlightSqlRouter.clientDataScanRefusal`); and, for a principal the metadata filter narrows, a COPY
it cannot read alone (`FlightSqlRouter.copyListingRefusal`). For tenant principals the gateway rewrites `DESCRIBE`,
`SHOW <table>`, `PRAGMA table_info` / `show`, `SUMMARIZE <table>` and (when the metadata filter
narrows) `SHOW TABLES` into SELECTs over the filtered listings plus a read of the object
(`RefusedForms`), and has the node evaluate a computed file location (`ComputedLocations`, a File
function's first argument or COPY's `TO (<expr>)`, at most 4 KiB) as the principal and substitutes the
literal, so the full screen judges it; any failure leaves the statement as written (inside a native
front door transaction the evaluation runs on the transaction's own link, and a failing evaluation, a
NULL or a non-text value aborts the transaction, as DuckDB would; a gateway refusal of it leaves the
transaction open).

Files by path, for non-superusers (decisions 15, 18, 23). **S3 only with keys of the user's own.**
Every user registers credentials for a scope `s3://<bucket>/` or `s3://<bucket>/<prefix>/` (whole
segments, a lowercase bucket, never an access point alias) from their profile session or PAT: REST
`POST /api/profile/s3/list|add|remove` (on the profile allowlist), `qod profile s3 list|add|remove`
(the secret and a session token only from stdin or a prompt), the profile page. Rows are in
`qodstate_user_s3_credential` (Liquibase `0052`, one per user and scope, at most 32 per user:
`too_many_credentials`; the secret write-only, `secretSet` on responses, redacted in `toString` and
logs; not in manifests; deleted with the user). A tenant admin lists and removes their own users'
credentials (`POST /api/user/s3/list|remove`, `TenantScopeCheck`, `qod user s3 list|remove`, the admin
user view), never adds one; MCP lists and removes only (`list_my_s3_credentials`,
`remove_my_s3_credential`). An endpoint other than AWS's own must be listed in
`quack-on-demand.userS3.allowedEndpoints` (`QOD_USER_S3_ALLOWED_ENDPOINTS`: `host[:port]` for TLS,
`http://host[:port]` for plain HTTP; empty means AWS only). A scope at or under a data folder that
counts against the user is refused when added (`400 scope_in_data_folder`) and again at use, through
one function, `LiveLocationRules.foldersAgainst`, at add, in the statement's rules and in the
credentials a replica serves. A folder of a row holding complete keys of its own
(`TenantDb.holdsOwnKeys`, not on the managed store's key nor the manager's own, `QOD_S3_ACCESS_KEY_ID`, its branch's parent holding them too) counts
against the users of that row's tenant only; anything else against every non-superuser: QoD placed,
managed storage or a branch of it, a superuser's row signing with the manager's keys, a local path,
`DataFolders.Unplaced`, a path a row merely states. So do these, whatever the row holds: several rows at
one path when one does not qualify (else each one's tenant), a path a holder left (a drop, keys cleared)
within `RecentMs`, a managed prefix within retention; a store read that fails gives no list, so no
verdict. A location several folders cover is refused if any counts against the user.
`namedDataPathTaken` and `409 data_path_taken` keep the whole set (`LiveLocationRules.dataFolders`),
whoever owns it. **On a bucket tenants share, a user's own keys reach another tenant's folder holding
its own keys wherever the store allows them, for reads and writes; the store's key permissions are what
protects it.** A node holds no secret of another tenant's row. A change of a row's keys moves every
tenant's policy version (`0057`). Every handle carries `creds`, the
SHA-256 of the user's credential ids and update times (empty for none); when it differs from what a
node connection last installed, or a client ROLLBACK undid those secrets, `qod_session` fetches the set
from the handle's replica (`POST /api/internal/authz/credentials`, `CredentialsRoute`, mounted beside
the decide endpoint and signed the same way, honored only for a handle that replica minted
(`IssuedHandles`), answered in the verdicts' signed format and bound to the digest), keeps it per
principal and digest for 5 minutes, and installs one connection secret `qod_uc_<id8>` per credential
(DuckDB's `connection` secret storage: invisible to other connections, transactional, every s3 key
named so no session setting fills one in). A failed fetch refuses the statement, retryable, and no
statement runs on that connection until a prelude succeeds. A user's `s3://` URL is admitted only
under one of their own scopes and outside every data folder that counts against them; one that a node secret of its own (an
Iceberg catalog's `qod_ice_*`, an operator's) would sign over the user's is refused on the node. The
keys travel manager to node signed, not encrypted, like the node token: a private network is
required. The keys sit in plaintext in the control-plane database (`qodstate_user_s3_credential.
secret_access_key`, `session_token`), so its backups and replicas carry them. Superusers are not
restricted by these rules, but the node's own keys no longer sign for them outside the data path: they
use credentials they registered, or a secret they create.
**Local folders are tenant-authorized** (`qodstate_tenant_location`, Liquibase `0051`, kind `local`
only; REST `POST /api/tenant/location/list|add|remove` (superusers and the tenant's admins; an `s3://`
location is refused, `400 invalid_location`), `qod tenant location list|add|remove`, MCP
`list_tenant_locations` / `add_tenant_location` / `remove_tenant_location`, the tenant page of the UI,
manifest `tenants[].locations`): an absolute POSIX folder under `<root>/<tenant>/`, `root` being
`QOD_TENANT_FILES_ROOT` (`quack-on-demand.tenantFiles.root`; unset = no local folder can be authorized;
a Windows path cannot be stored, so Windows nodes admit no local folder). No path may lie under any
database's data path (every tenant's, branch siblings, managed prefixes within retention), compared
on whole segments; relative paths, empty, `.` and `..` segments, percent-escapes and globs in a stored
folder are refused; the node resolves the deepest existing ancestor of a path (symlinks included)
before the check, but does not judge links below it (CONTRACT item 33): through a glob segment
(DuckDB expands one with `stat()`, following links) or a COPY with `PARTITION_BY`,
`PER_THREAD_OUTPUT` or `OVERWRITE` (its directory walk follows linked directories), a symbolic link
inside an authorized folder can lead out of it. Keeping tenant folders free of symbolic links is an
operator duty, uploads that preserve links (`rsync -a`, some SFTP clients) included; users cannot
create links with SQL. The stakes: such a link can point into any tenant's data folder, and a COPY
with `OVERWRITE` deletes files under its target. A data path the checker cannot place
on the node (a relative one off the local runtime, or `DataFolders.Unplaced`: a DuckLake or
duckdb-file row with no data path while the default metastore has none) denies every local location
to every tenant. Every other scheme (`http(s)`, `gs`, `az`, `r2`) is refused to non-superusers. Not
covered: an attached Iceberg catalog's raw warehouse files (decision 21); a node reads whatever files
its catalog lists, so the manager prints one stdout `WARN:` line per `iceberg_rest` source (`IcebergSourceWarning`, whose advice is `QOD_NODE_ALLOW_LOCAL_FILES=false`: disabled ones too, from `FederatedSourceHandlers.createSource` on a create or a change, and from `BootPreflight.warnIcebergSources` at each boot, never at node spawn), naming the source and nothing of its connection. An Iceberg catalog that
neither vends credentials nor has `awsCredentials` reads its table data with each reading user's own
keys. Iceberg tables on S3 are read-only through QoD for every principal, superusers included, until
upstream duckdb-iceberg signs its commits with connection secrets (DuckDB v2.0.0 commits manifests
without them, and qod-node installs every registered credential `IN connection`: HTTP 403 at
commit). An Iceberg source's `awsCredentials` carry no
S3 endpoint, so an S3-compatible (non-AWS) warehouse is read only with users' own keys or vended
credentials. A change of the default metastore's `dataPath`
in the configuration bumps no policy version: restart the nodes after one.

**Node S3 hardening (decision 23).** Every node opens with `enable_external_file_cache` and
`enable_http_metadata_cache` false (both are node-wide and keyed by path, so a session could read
bytes another user's keys fetched). Nodes hold no unscoped `quack_s3` any more (only the manager's own
embedded DuckDB in `DuckLakeInitializer` does): the data path signs with `qod_data_s3` (the manager's
`QOD_S3_*` keys, or `PROVIDER credential_chain` after `INSTALL aws` / `LOAD aws` when there are none)
or the database's own `qod_db_store`, scoped to the data path and its branches' `<path>__br_` prefix
(a branch node: the parent's and its own), every key named; `quack_azure`, the manager's
`QOD_AZURE_CONNECTION_STRING`, exists under the same two rules: only on a row with no `objectStore`, and
scoped to those data scopes. When httpfs loads, qod-node resets the
GLOBAL settings httpfs copied from `AWS_*` / `DUCKDB_S3_*` to their defaults
(`ResetS3EnvironmentDefaults`), and when aws loads it sets `aws_default_credentials` false (its default
storage would sign any URL no secret matches with the instance's credential chain). Non-superusers may
not CREATE or DROP a SECRET (any storage, `connection` included), SET or RESET an `s3_*` or
`external_file_cache_*` setting nor any of `kReservedSettings` in `screen_rules.cpp` (secret
defaults, certificate verification, proxies, `extra_http_headers`, the HTTP client and connection
caching, credential refresh, `gcs_user_project`, etag checks, the caches), nor ATTACH (an attached
database keeps the keys it was opened with and serves every connection).

`allow_community_extensions`, `allow_unsigned_extensions` and `autoinstall_known_extensions` are
false on every node unless the operator sets `quack-on-demand.nodeExtensions.{allowCommunity,
allowUnsigned, autoinstallKnown}` (`QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS`,
`QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS`, `QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS`; no Helm value: set them
in the chart's `env`), which reach qod-node as `--allow-community-extensions` etc. through the
non-secret spawn env on every backend and apply before the database opens, so init SQL cannot load
community or unsigned code either. Only superusers change them at runtime, and DuckDB refuses either
`allow_*` once the database runs. With autoinstall off, the spawn scripts `INSTALL httpfs` and
`INSTALL avro` before the tenant's initSql (an `extension_directory` or
`custom_extension_repository` a database's initSql sets does not apply to those two), and any other
statement that relied on autoinstall fails unless its extension is installed explicitly.

**Local files off (`QOD_NODE_ALLOW_LOCAL_FILES=false`, `quack-on-demand.nodeFiles.allowLocal`, default
`true`; no Helm value: set it in the chart's `env`).** Every launcher hands the variable to the spawn
script (local spawn env, Kubernetes pod env, the fleet assignment's `nodeFiles`, `qod fleet join`), and
only the exact value `false` makes the script end the init SQL, after every INSTALL, LOAD and ATTACH, the
federation SQL included, with `NodeFilesConfig.InitStatements` (written out in both scripts, pinned by
`NodeFilesScriptsSpec`, and fenced from the text above by two empty marker lines, so that a trusted text
that never closes a quote cannot swallow them): `LOAD httpfs`; `allowed_directories` set to the remote
schemes, azure's only where the init SQL loaded azure, since DuckDB stores a scheme with no file system
loaded as a local path; `allowed_paths` set to the five CA bundles httpfs looks for (`CaBundles`, re-check
the list at every DuckDB pin bump), since it reads them through DuckDB's file system and refuses every
HTTPS request when it may not ask; `enable_external_access` false. Then nothing on the node opens a local
path outside DuckDB's own temp directory and those five files, for anyone, a catalog's file list,
deletion schedule or Iceberg manifest included (`Permission Error: Cannot access file ...`), and DuckDB
refuses SET or RESET of `enable_external_access`, `allowed_directories`, `allowed_paths` and
`temp_directory` while it runs. Spills keep working. It applies at each node's next start, and the health
probe checks it: with `false` it also reads `current_setting('enable_external_access')` on each node, as
the system principal (`QuackHttpAdapter.healthProbe`, `QuackBackend.allowsLocalFiles`), and only an answer
of exactly `false` passes. Anything else, a failed read included, holds the node out of routing like a node
that runs the canary (`ProbeVerdict.LocalFilesNotOff`, `NodeLoad.localFilesNotOff`, which a statement's
success does not clear): it is unhealthy, a statement answers as for no routable node, one ERROR line says
so, and the pool's `statusDetail` names the node and the setting and says to restart it or to upgrade its
image or fleet server (`status` does not move, as node health is not what it says). So an older node image
or `qod fleet join`, a node started before the setting and a switch statement that failed at init all
show. The probe has no threshold, so neither has this: a read that fails holds the node for one tick, and
the next read answering `false` lets it go. Reconcile goes by whether the process lives, so it never
restarts such a node: it stays as it is until someone restarts it, with no loop on a server whose release
will never apply the setting. With `true` the probe sends nothing more. A row whose resolved data path is
local (or a DuckLake or duckdb-file row with none) is blocked (`PoolSupervisor.localFilesBlock`, read from
the row, so on every replica), a `memory` row naming a local path included, though no node opens that path
(it is only the scope of the row's object-store secret): reconcile starts none of its
pools, no maintenance or merge node starts, no DuckLake init runs, `startupProblems` and the banner list
it, `/api/pool/list` says `blocked` with `statusDetail`. A create or an update that would make one, on a
derived path too (a tenant admin naming a path reads `403 superuser_required` first), and a pool create,
scale-up, resume or node restart of one,
answer `400 local_files_off`; a statement that would wake a suspended pool of one is refused at once, Flight
`INVALID_ARGUMENT`, naming the setting and no path (`FlightSqlRouter.CannotWakeLocalFilesOff`, no wait for
`resumeHoldTimeoutSec`); a manifest still imports one, and it is then blocked, so a backup always restores. `QOD_TENANT_FILES_ROOT` set with it refuses boot (`BootPreflight.checkNodeFiles`); the shipped
default data path is not judged, only rows and creates. The costs: what the init SQL did not load stays
out, `INSTALL` and `LOAD` being refused to superusers too, so of the extensions only `icu`, `json`,
`parquet` (built in) and `httpfs` always work, `ducklake` and `postgres` on a DuckLake node, `iceberg` and
`avro` where an Iceberg source loaded them, anything else only through a database's or pool's initSql;
`duckdb_extensions()` reads a `Permission Error`; a federated source that failed at a node's start no
longer heals on its own, nor does one added to a running node attach (`IcebergAttachVerifier`'s re-attach
starts with INSTALL, and a late Postgres ATTACH is refused anyway): restart the node; a `ca_cert_file` at a
path of its own is refused, so a private CA goes in the system bundle; views over local files (`qod serve
<local file>`, whose `memory` row names no path, so it is not blocked) start and refuse every read.
Residuals: it covers local files only and says nothing about what the node's own keys reach on object
storage. DuckDB leaves the temp directory allowed: a superuser reads and writes it, a non-superuser is
refused by the location rules, and a file list naming a file there reads it, which holds only spill files
(no Parquet: they read as nothing) and what a superuser put there; a superuser's init SQL that moves
`temp_directory` moves that allowance with it, and a tenant admin's cannot (it is not on the initSql
allowlist). The five CA files stay open to DuckDB, for reading and for writing: a superuser's statement
reads them, and writes them as far as the node's OS user may, and a deletion schedule naming one of them
removes it when the node's OS user may (root; the node images run as uid 1000). A local file a superuser's
init SQL attached before the switch stays reachable (DuckDB adds attached paths to `allowed_paths`). A
superuser's `enable_logging` with file storage at a local path aborts the node (DuckDB's log writer throws
past the switch), and reconcile starts it again. A node is routable from its start until the probe's first
read of it, about `healthCheckIntervalSec`, as with the canary, a statement that wakes a pool included, and
no probe visits a maintenance or merge node. Windows has not run it: `.ps1` is pinned by its dry run only.

**Tenant initSql runs under an allowlist.** `initSqlTrusted` on `qodstate_tenant_db` and
`qodstate_pool` (Liquibase `0053`, read-only on responses) records who wrote the text: a superuser,
the manager (`qod serve` boot provisioning, manifest import, a branch copying its parent) or an empty
text is trusted; a tenant admin's is not (`InitSqlTrust`). Trust rides the secret file's name
(`tenantDbInitSqlTrusted` / `tenantPoolInitSqlTrusted` vs `tenantDbInitSql` / `tenantPoolInitSql`);
the spawn scripts wrap an untrusted text between `-- qod:untrusted-begin <nonce> <label>` and
`-- qod:untrusted-end <nonce>`, and qod-node (`init_policy.cpp`) runs such a section statement by
statement, each parsed by DuckDB and run only when it is one `SET` / `RESET` (default or GLOBAL scope,
one literal) of the 15 names in `native/qod-node/init-settings.txt`, a read-only `PRAGMA` with literal
arguments, or `INSTALL` / `LOAD` by bare name of the 21 in `init-extensions.txt` (no `FORCE`, `FROM`,
`VERSION`, path or URL); anything else is skipped and logged by position, never by text. A database's
initSql runs before the catalog ATTACH, a pool's after it and before the federation SQL. Writes
(`database/create|update`, `pool/create`, `POST /api/pool/setInitSql` / `qod pool set-init-sql` / MCP
`set_pool_init_sql`) check a tenant admin's changed text with `qod-node --check-init` (SQL on stdin,
nothing run) and refuse `400 init_sql_refused` naming each statement's position and reason; where the
manager has no local qod-node (Kubernetes, fleet without `QOD_FLEET_EPHEMERAL=local`) the write is
accepted unchecked with `initSqlWarning`. A tenant admin's initSql holds at most 65536 bytes; the
`qod:untrusted-` marker text is refused in any initSql, every writer. A superuser re-saving an
unchanged text marks it trusted; boot WARNs per untrusted row that loses statements
(`InitSqlTrustReport`).
Federation sources of type `sql` (free-form `setupSql`) are superuser-only to create or change (`403
superuser_required`; `setup_sql_trusted`, false for every sql row stored before `0053`,
boot WARN per such row), and every `{{secret.NAME}}` in a `sql` template must sit inside a plain
single-quoted literal (`SecretPlaceholderScan`, `400 invalid_federated_source` naming the
placeholder); a stored template's unquoted placeholder is substituted only for a plain-token value
next to no quote, `-`, `/`, `*`, `$` or letter, else that source's block is left out
with a WARN. Manifests carry `initSqlTrusted` and `setupSqlTrusted`. A source's stored secrets, an
`externalRef`'s included, go only where they were saved for, the rule `TenantSignInWrite` holds for a client
secret: on a source holding any, `FederatedSourceHandlers.createSource` refuses a writer who is not a superuser
another `connection` or `config` (`403 superuser_required`, nothing written, audited denied), compared by
`FederatedSourceRules.sameConfig` on the whole value as its type reads it, so a client sending the stored values
back changes nothing, and likewise a write that would leave a read-only one writable, judged on the row it would
leave (`changesUseOfSecrets`), whoever made the source, since secrets record no author; its other fields stay open,
as do a source holding none and a delete and recreate. Every true to false written logs a WARN.

**Read-only federated catalogs bind every principal (decision 24).** A federated source's `readOnly`
flag (once it holds a secret, only a superuser makes it writable) is enforced on the node, superusers and the system principal included: since the node never asks
the manager about a superuser's statement, the set travels in the signed handle (the handle's required
`readOnly`, the pool's aliases folded with `Locale.ROOT`, sorted; `ReadOnlyCatalogs` /
`FederatedReadOnlyCatalogs`, disabled sources included, the system principal's handles carry `[]`).
qod-node refuses, in `post_bind` and `pre_optimize`, before the verdict cache and any decide call, a
plan that writes into such a catalog, creates, drops or alters there, or ATTACHes or DETACHes it
(`ReadOnlyRefusal`: `QoD: catalog <alias> is read-only`, not retryable). A tenant-db the lookup cannot
find fails the statement rather than caching "none". A federated source write (create, update,
delete) applies on the next statement: the serving replica drops the read-only and attached-catalog
caches of the tenant-db's pools (`FederatedSourceCaches.sourcesChanged`) and sends `qod_topology`,
whose `restore()` drops every peer's (`PoolSupervisor.onRestore`); the 60 s per-pool TTL stays only as a
backstop. Typed sources (`iceberg_rest`, `postgres`, `mysql`) keep the engine-level `READ_ONLY`
ATTACH option too, which binds at node spawn only. Residuals under "Residuals" below.

**Authorization metrics (decision 26).** Per replica: `qod_authz_decisions_total{tenant, kind=plan|screen,
outcome=allow|refuse|unavailable, mode=qod|opa}` and the distribution
`qod_authz_decide_duration_ms{kind}` (milliseconds, buckets 0.5 to 5000; Prometheus exports its
`_bucket`, `_count`, `_sum` and `_max`), both recorded by `DecideRoute` (`AuthzInstruments`); dry runs,
and calls refused by authentication or as malformed, are not decisions. The bounded caches report
Micrometer's Caffeine meters (`cache_size`, `cache_gets_total{result=hit|miss}`,
`cache_evictions_total`, ...) under `cache` = `qod_minted_handles`, `qod_issued_handles`,
`qod_dry_run_results`, `qod_effective_sets` and `qod_opa_decisions` (the last only while
`QOD_OPA_CACHE_TTL_SEC` > 0), plus `qod_cache_capacity{cache}`, their configured bound
(`CacheMetrics`); these are per replica, so sum them across replicas. Each node answers
`system.main.qod_authz_stats()` (one row of BIGINT columns since node start: `plan_cache_hits`,
`plan_cache_misses`, `decide_calls`, `decide_unavailable`, `decide_refused`, `local_refusals`,
`screen_calls`, `verdict_cache_size`, `verdict_cache_evictions`, `credential_sets_size`; system
principal only, a superuser gets `QoD: function qod_authz_stats is superuser-only` and a tenant user
the parse screen's `QoD: table function qod_authz_stats is not available to non-superusers`). Every
replica's health probe reads it after the canary (`NodeAuthzStats`; a failed read keeps the node
healthy and logs at DEBUG), but only the HA leader exports `qod_node_authz_<counter>_total{tenant,
pool, node}` (the seven counters plus `verdict_cache_evictions`, from deltas, a lower value counting
as a node restart) and the gauges `qod_node_authz_verdict_cache_size` /
`qod_node_authz_credential_sets_size` (`MetricsBindings`, `isLeader`; a single manager always
leads), on Prometheus scrapes only, after a node's first healthy probe tick. `screen_calls` includes
the manager's own statements (preludes, the probe's reads). `QOD_AUTHZ_HANDLE_CONTEXTS_MAX`
(`quack-on-demand.nodeAuthz.handleContextsMax`, default 200000, boot refuses below 1000) bounds the
session handle contexts and nonces a replica keeps, each for 360 s (`MintedHandles`,
`IssuedHandles`): about `QOD_AUTHZ_HANDLE_CONTEXTS_MAX / 360` tenant statements per second per
replica (about 555 at the default); past it statements fail with `QoD: authorization unavailable,
retry: authorization context expired; retry` (retryable), signalled by `cache_evictions_total{cache="qod_minted_handles"}`. A rising
`qod_node_authz_verdict_cache_evictions_total` means `QOD_AUTHZ_CACHE_ENTRIES` is too small.

**Open sessions follow the policy version.** A FlightSQL or native front door session resolves its
effective set again on its first statement after its tenant's policy version (or this replica's
policy generation) moved (`SessionGrants`): a revoke reaches it on its next statement, a re-grant
restores it without a reconnect, and a user disabled, deleted or without the pool is refused there
(a control-plane outage during that check is a retryable `UNAVAILABLE`). On the native door a refused
statement inside a client transaction ends that transaction ("transaction aborted: <reason>; ROLLBACK
to continue"), and only a lone ROLLBACK is admitted, under no grants. A native client transaction
whose pinned node leaves the pool is fenced the same way (`FlightSqlRouter.PinLostAnswer`).

**Accepted over-refusals** (owner rulings, CONTRACT Addendum 4 items 13-21, 24, 30-32a: changing one
needs the owner; never loosen a rule to fix one). For non-superusers: an unclassified name (a typo
included) reads "is not classified"; a superuser-made macro, or a catalog macro shadowing a built-in
name (inside a built-in macro's body too), is refused until classified or called as `system.main`; a
plain view or stored expression (generated column, default, CHECK, trigger) that calls a user macro, an
unclassified or (outside a secure view) superuser-class function, the `histogram(<table>, <column>)`
table macro, or a `query()` that is not a literal query, or views nested past 16, makes its view or
table unreadable or unwritable; secure views follow the definition and session rules above; DESCRIBE
`key` / table_info `pk` are NULL; for an OPA tenant's user with no read-covering QoD grant, DESCRIBE and
SHOW TABLES are empty and SUMMARIZE is refused (the listings follow QoD grants, which OPA does not narrow);
`*.*.*` never covers a federated catalog (grant it by its alias). It does cover DuckDB's
`memory` catalog on the memory database's own pool (never on another pool, where `memory` is
instance-wide) and a custom `catalogAlias` (CONTRACT item 34's gap closed with the gateway screens;
pinned by `AuthzEndToEndCases` "a memory database" and "a database with a custom catalog alias"), so a
`qod serve` user holding the default `qod_all_tables` reads a `.duckdb` file attached under its stem
(`sales`) and the loose-file views in `memory`.

**Residuals** (known, not holes to report): foldable constant arguments are evaluated during bind
before a refusal (no value reaches the client); `pg_sleep` / `sleep_ms` are allowed, in bind-time
slots too; SQL stored by others (view bodies, defaults) runs its bind-time slots as the reader; an
optimizer extension loaded after qod-node is not re-described; statements an extension runs on its own
untagged connections (DuckLake metadata, Iceberg commits) are admitted as node-internal; under the
FlightSQL prepare probe a computed location and a SUMMARIZE run twice, and a fresh FlightSQL query
costs two decisions (the GetFlightInfo schema probe is a plan of its own). Read-only federated catalogs
bind superusers only through what the node describes (decision 24): a superuser can still write
through a raw passthrough (`postgres_execute`, `postgres_query`, `mysql_*`, whose SQL argument the
node does not read), a maintenance function taking a catalog name, an ATTACH of the same source under
another alias, or CHECKPOINT / VACUUM; each is superuser-only, so no other principal reaches them. A
superuser's `SET GLOBAL` of a parser setting can make the policy rewriters read a policy holder's
statement differently from the node. A principal holding
create on a schema may create a plain view over a DuckLake metadata catalog (`__ducklake_metadata_*`):
nothing is read at creation, and every non-superuser read through it is refused ("... is DuckLake
catalog metadata, which only superusers reach"), because a plain view runs with its reader's rights
(spec decision 4; owner ruling C16). In an OPA dry run the plan never executes, but what happens while
binding still does (the parse screen's checks, a user's S3 credential fetch). A restore that cannot
take every permit of the versions lock within 5 s (`PoolSupervisor.RestoreLockWaitMs`) is skipped with
a WARN and owed to the next local mutation, NOTIFY or snapshot refresh; a slow `createTenantDb`
(database creation and DuckLake init run inside the mutation) skips restores that way, and the owed
restore's latency lands on the next request thread. A multi-statement submission binds each statement
as it reaches it, under the one handle sent before its PREPARE, so a statement that binds more than
60 s after the send is refused `qod-node: this connection has no verified principal`, COMMIT included,
for superusers too (a single statement may run longer). The merge is not affected: it sends one PREPARE
per statement.

Which binary: `QodNodeBinary` (`ondemand/runtime/`) resolves it once at boot for every manager that
starts nodes on its host (runtime local, or fleet with `QOD_FLEET_EPHEMERAL=local`) and refuses to
boot without one. `QOD_NODE_BIN` wins (a local build; the Windows launchers' download), else the
binary the jar bundles for its platform, installed as `<root>/<sha12>/bin/qod-node` beside a
`<root>/<sha12>/lib` link to the libduckdb the manager loaded (a vendored build's only rpath is
`../lib`). Either way it must answer `--build-info` with this build's node protocol
(`QOD_NODE_PROTOCOL` in native/qod-node/CMakeLists.txt = `QodNodeBinary.Protocol`) and DuckDB
release; `--build-info` also checks the libduckdb it loaded. `LocalQuackBackend` hands it to every
spawn as `QOD_NODE_BIN`. Where no manager starts the node, the same protocol check runs elsewhere:
`qod fleet join` refuses (exit 2) a qod-node whose marker names another protocol than its
`qod_node.NODE_PROTOCOL`, and the node image build fails on one other than its `ARG
QOD_NODE_PROTOCOL` (both pinned to `QOD_NODE_PROTOCOL`).

Vendoring follows libquackwire: `native/qod-node/binaries/<platform>/qod-node` for linux-x86_64,
linux-aarch64, osx-x86_64 and osx-aarch64, with `.sha256` companions and a `VERSION` stamp,
refreshed by `scripts/release/refresh-qod-node-binaries.sh` (the host's cmake build plus the
artifacts of a green `qod-node` CI run of HEAD; `FETCH_CI=0` for the host alone), which also
refreshes the copies the qod wheel bundles for `qod fleet join` (`cli/src/qod_cli/qod-node/`, gated
by `cli/tests/test_qod_node_bundle.py`). `verify_qod_node_binaries` gates releases and snapshots.
Windows' static `qod-node.exe` is too large to bundle: release.yml publishes
`qod-node-windows-<arch>.exe` with each release (qod-node.yml refreshes the snapshot's), and
`qod start` / `qod serve` / `run-jar.ps1` download the one of the jar's release. The Kubernetes node
image ships the vendored binary and libduckdb in `/opt/qod-node`, and no DuckDB CLI. Build and test
locally: `cmake -S native/qod-node -B native/qod-node/build -DQOD_NODE_TESTS=ON
-DDUCKDB_HOME=<.duckdb/2.0.0, with include/>`, then `DUCKDB_CLI=<release duckdb> ctest --test-dir
native/qod-node/build`; forked Scala tests use that build (build.sbt sets `QOD_NODE_BIN`).

### State storage (Postgres only)

The control plane lives in a dedicated Postgres database (`qod` by default) holding normalized `qodstate_*` tables managed by Liquibase: `qodstate_tenant`, `qodstate_tenant_db`, `qodstate_pool`, `qodstate_node` for the registry; `qodstate_user`, `qodstate_role`, `qodstate_role_permission`, `qodstate_group`, `qodstate_user_role`, `qodstate_user_group`, `qodstate_group_role`, `qodstate_pool_permission` for the RBAC graph; `qodstate_federated_source`, `qodstate_federated_secret` for federation. Each managed tenant-db (e.g. `tpch_tpch1`) is a **separate** Postgres database next to it, holding the DuckLake `__ducklake_*` catalog. The `qodstate_*` prefix keeps control-plane tables from colliding with DuckLake's `__ducklake_*` namespace inside any shared database.

**Built-in RBAC objects.** Every tenant carries four protected built-ins (`BuiltinRbac`): roles `qod_no_tables` / `qod_all_tables` (`*.*.* ALL`), groups `qod_no_pools` / `qod_all_pools` (tenant-wide pool grant, `pool_id NULL`), flagged `builtin` (Liquibase `0045`, which also renamed `qodstate_user.role` to `kind`). Seeded by `createTenantWithBuiltins`, the manifest importer, and the idempotent boot `BuiltinRbacBackfill`: it runs on every replica before `PoolSupervisor` loads its caches, one transaction per tenant with per-tenant failure isolation (a failing tenant is logged and skipped, never fatal to boot), renames a NON-built-in row already holding a built-in name to the first free `<name>_renamed` / `<name>_renamed_N`, and folds a pristine legacy `admin` role (exactly one `* * * ALL` permission, no column/row policy) into `qod_all_tables`, moving its user and group edges. Deleting or editing a built-in answers `409 builtin_protected` from the supervisor choke points; the `qod_` prefix is reserved (case-insensitive, `400 reserved_name`), and manifest export refuses (`400 reserved_name`) a user-made `qod_` row rather than emit an unreplayable manifest (export omits the built-ins themselves). Tenant-user creation (REST, CLI `--role` / `--group`, MCP, SQL `CREATE USER ... ROLES ... GROUPS ...`) requires `UserMemberships`: omitted lists default to the permissive built-ins (`qod_all_tables` + `qod_all_pools`, i.e. ALL on every table of every pool), empty lists are refused (`400 roles_required` / `groups_required`), unknown names `400 unknown_role` / `unknown_group`; superusers (sending either list is a 400), SCIM and manifest import are exempt. New tenants no longer get a legacy `admin` role. The seeded boot admin's account kind is `quack-on-demand.admin.kind` / `QOD_ADMIN_KIND` (formerly `QOD_ADMIN_ROLE`).

**What a database row may reach.** A DuckLake tenant-db's Postgres database is named `<tenant>_<suffix>`, and a new tenant's name holds letters and digits only and starts with a letter (`Names.normalizeTenantId`, in `PoolSupervisor.createTenant`, so REST, MCP, `qod serve`'s provisioning and a module's direct call all get it; the REST handler maps it to `400 invalid_id`; a manifest import writes through the store and keeps the name it brings), so the first `_` of a database name marks where the tenant's ends. `TenantDb.onDefaultServer` decides the rest: host and port against the default's, as text after normalizing ASCII case, one trailing dot, brackets and a port's leading zeros, never resolved (`127.0.0.1` is not `localhost`), and false for a stored `pgHost`, `pgPort`, `pgUser`, `dbName` or `schemaName` holding whitespace, as no connection string reads such a value as one value (`validateSafety` refuses whitespace in each on every write, and any of `? & = #` in `pgHost`, which no JDBC URL reads as one host). QoD's login goes only to QoD's server: `TenantDb.withDefaults`, the one fill, used by `effectiveMetastoreFor` and `createTenantDb`, copies the default `pgUser` and `pgPassword` onto no other row, whoever wrote it, so an old such row fails to connect; every other reader (the DuckLake initializer, the catalog reader, the branch cloner, the merge fence, maintenance and merge nodes, every launcher through `NodeMetastore`) takes its map from there; the spawn scripts have no password fallback; `NodePgAddress` reads a server the same way, host and port. `effectiveMetastoreFor` of an unknown tenant and database holds neither that login nor the default `dbName`. `database/create` and `database/update` answer `400 own_login_required` (`TenantDb.ownLoginError`, in the supervisor) to a DuckLake metastore on another server without both, judging the stored row's result, since `mergeSecretKeys` keeps a secret an update leaves out, and refuse a move of server with the stored password left out (`movedPasswordError`): sent again, or sent empty to remove it. No exception for a superuser. On QoD's server a create never adopts unless its caller says so: `PoolSupervisor.createTenantDb`'s `writerTrusted` has no default (`TenantDbHandlers` passes it for a superuser's scope, which `qod serve`'s provisioning is; a manifest import writes rows and adopts through the init); otherwise a name another tenant's row holds, or a database holding anything (`DbAdmin.holdsAnything`), is `409 database_name_taken`, touching nothing, and an empty one is reused (a crash between the create and the row write; a crash after the DuckLake init leaves `ducklake_*` tables, which then take a superuser). An update moving a row onto QoD's server meets the same rule. A row on another server gets no `CREATE DATABASE` and no `DROP DATABASE` on any server: `initBlocking`'s `createMissingDatabase` is false there (a missing catalog is `400 catalog_database_missing`, nothing written), the bash spawn script creates only on the manager's `createDatabase` stamp (`TenantDb.CreateDatabaseKey`, never a caller's key), a branch is `400 branch_own_server`, and `dropCatalogDatabase` logs what it leaves at ERROR, on `database/delete` and on a tenant delete alike. It drops `td.name` only, only for a DuckLake row, never the control-plane database. `database/update` refuses a changed `dbName` of a DuckLake row, `400 db_name_fixed` (`TenantDb.dbNameChangeError`), unless `TenantDbPatch.writerTrusted`, a superuser; the rule below answers such a writer first; a branch registers its own through `registerBranchTenantDb`. A module's direct `updateTenantDb` states `writerTrusted` or meets all of this. `catalogAlias` and `schemaName` stay free: they only name things inside the row's own catalog.

**What a caller is shown of QoD's Postgres.** `HandlerResolvers.metastoreFor` is the one place a metastore leaves in an answer (`PoolHandlers.respond`, `TenantDbHandlers.toResponse`, both with a required viewer, and through them MCP): no secret for anyone, and for a caller who is not a superuser `TenantDb.metastoreShownToTenant`, nothing the defaults filled in and, on QoD's own server, no `pgHost`, `pgPort` or `pgUser`, whoever stored them. A row on another server shows its own. Error text has one rule too, `TenantDb.textShownToTenant`, a fixed text in place of one naming that host or user in the forms a connection's error uses, or the port beside that host (a bare value is left, as a refusal can quote a name the caller sent, and a tenant's own source on the usual port keeps its text): a blocked pool's `statusDetail` (`poolBlockReasonForTenant`, the reason only, as a fix can print the row's metastore), a REST answer of 5xx or 409 past `apiKeyGuard` (`ManagerServer.withServerTextWithheld`, the original at ERROR) and an MCP tool's error REST would answer so (`McpToolArgs.bridge` marks it, `McpRoutes` reads the mark). Other 4xx text stays, as it quotes the caller's own request. A statement's error on the data plane relays the node's text and is not read by this rule. `PoolSupervisor.updateTenantDb` keeps what such a writer is not shown when they leave it out, and refuses them naming any of those keys on QoD's server in one fixed text (`TenantDb.unshownServerKeyError`), so no answer says whether a value is right.

**What only a superuser chooses for a database.** `TenantDb.superuserChoiceError` is the one check, for any writer that is not a superuser: `PoolSupervisor.createTenantDb` asks it unless `writerTrusted`, which has no default (a module states it; `TenantDbHandlers` passes the caller's scope, so REST and MCP alike, and `qod serve`'s provisioning, which is `SessionScope.Superuser`, passes), `TenantDbHandlers.createRefusal` asks it too, so that a refused write never reaches the initSql check that starts a process, and `PoolSupervisor.updateTenantDb` asks it first of all its checks unless `TenantDbPatch.writerTrusted`, on the row the update would leave; `403 superuser_required`, `SupervisorError.SuperuserChoice`, nothing written. A manifest import and a branch's registration write rows and do not come here. Nothing judges an existing row at boot. It decides which Postgres server a row uses: none of `TenantDb.ServerKeys` (`pgHost`, `pgPort`, `pgUser`, `pgPassword`, `dbName`) may be added, changed or removed on any row, the row's result compared with the stored one, since an update's map can drop a key. `dbName` stays free on a `duckdb-file` row, where it names a catalog in the file, and one equal to the row's own name reads as none, as `effectiveMetastoreFor` resolves it. A password is held or not, in any spelling, and one sent counts as a change whatever it says; `GET /api/database/metastore-defaults` is superuser-only and the console's forms offer none of these keys to anyone else (`ui/src/api/metastoreForm.ts` carries them back as stored on an edit). A database's Postgres server is trusted as far as its nodes are: whoever controls it reaches what those nodes reach. A superuser who names one accepts that; an existing such row keeps working and is not judged; the login rule, the no-fill, no create or drop on another server, the `dbName` rule and the whitespace guard above stay for superusers' rows and stored rows. It also decides where the data lies, as a path is signed only with the keys the row holds, or a superuser's: a create names no path, or a folder of a remote store, not a bucket's or a container's root and with no `.`, `..`, glob or escape in it (`namesRemoteFolder`), with own keys (`holdsOwnKeys`: the explicit `objectStore` keys `ObjectStoreSecret` reads, complete for the scheme, so never a fallback to `QOD_S3_*` or the instance's credential chain; a local path with keys does not count), and never `metastore.dataPath`; an `objectStore` goes only with such a path. An update leaves `metastore.dataPath` as it is, and takes a change to `objectStore` (a secret sent is a change whatever it says, never compared) only on a row holding complete own keys, which stay complete, and any other value of it only with the scheme's secret sent in the same request (a stored secret goes only with what it was saved with, as a tenant's client secret goes only to its issuer, `TenantSignInWrite`); any other row takes none, adding, changing and clearing alike, a managed-storage row and a branch of one included (`managedRow`: their `qodstate_managed_prefix` row, since those keys are QoD's and `holdsOwnKeys` would read them as the row's); a value left as stored is not judged. `holdsOwnKeys` checks presence, not that the keys work, so it decides what a tenant admin may choose for a path of their own, never for one QoD signs. A DuckLake create naming no path, from any caller, gets `PoolSupervisor.derivedDataPath`: `siblingDataPath` of the default data path under the composed name, the derivation a row with no path already had, stored on the row. No default, or a default that is a bucket's root, is `400 invalid_contract`; a place `DataFolders.overlap` finds at, under or above any of `dataPaths()`, by whole segments, is `409 data_path_taken`, naming no one. So is a path an untrusted writer names, against all the location rules deny (`PoolSupervisor.dataFolderDenySet`, which `Main` wires to `LiveLocationRules.dataFolders`: so a folder dropped within its ten minutes and a managed prefix within retention too; unreadable, the create fails).

The legacy `file` mode (single JSON blob) was dropped 2026-06-12 along with the `stateStorage` and `statePath` config keys -- `PostgresControlPlaneStore` is always wired. Connections come from a HikariCP pool (size 20 on the control-plane store, 10 on `UserStore`); `close()` on the trait is called from the manager's shutdown hook.

### Managed object storage (QoD-provisioned data paths)

A DuckLake database can be created with `managedStorage: true` (REST `database/create`, CLI `qod database create --managed-storage`, admin UI storage mode "Managed (QoD-provisioned)") instead of a caller-supplied `dataPath`/`objectStore`. The manager then resolves `dataPath = s3://<bucket>/<tenant>_<dbname>-<id8>/` (`ManagedPrefix` in `ondemand/storage/`, `id8` = first 8 chars of the tenant-db surrogate id, so recreating a deleted name lands on a fresh empty prefix) and fills the database's `objectStore` map from the `quack-on-demand.managedObjectStore` config block (`QOD_MANAGED_STORE_ENABLED / _ENDPOINT / _REGION / _BUCKET / _ACCESS_KEY_ID / _SECRET_ACCESS_KEY / _URL_STYLE / _RETAIN_DAYS / _PURGE_SWEEP_SEC`, disabled by default), so every downstream mechanism (node `CREATE SECRET`, `SecretKeys` redaction, spawn env, manifest export) applies unchanged. `managedStorage` is exclusive with `dataPath`/`objectStore`, requires `kind=ducklake`, and 400s naming the env when the block is off; `database/update` has no `managedStorage` field, so there is no BYO-to-managed migration.

Every managed create writes a tombstone row in `qodstate_managed_prefix` (Liquibase `0027`); `database/delete` stamps `deleted_at` + `purge_eligible_at` (`+retainDays`, or now with `purgeManagedData: true` / `--purge-managed-data`). `ManagedStoreWiring` (in `boot/`) sweeps due rows every `purgeSweepSec` (60s floor), HA-leader-gated inside `IO.defer`, listing and batch-deleting objects in bounded batches per prefix per tick and stamping `purged_at` when a listing comes back empty; the retained window doubles as the undrop window. The root bucket is created if missing by a boot probe that only ever WARNs. **The managed bucket must have versioning OFF**: on a versioned bucket deletes write delete markers, listings go empty, and the worker stamps a prefix purged while non-current versions keep billing.

### Encryption at rest (create-time, per tenant-db)

`TenantDb.encrypted` (Liquibase `0039`) turns on at-rest encryption for one database. It is
**create-time only in both directions, and so is the key**: neither engine can encrypt, decrypt or
re-key in place, so `database/update` refuses a changed *or emptied* `encryptionKey`, and
`ManifestImporter` refuses any change of the flag itself (the only path in the system that could
flip it, since the update DTO has no such field).

- `kind=ducklake`: `ENCRYPTED` rides every ATTACH. DuckLake mints a key per Parquet file into
  `ducklake_data_file.encryption_key`; QoD holds no key material. Repeating `ENCRYPTED` on a
  non-creating attach is legal, and an unencrypted catalog records `encrypted='false'` explicitly.
- `kind=duckdb-file`: `ENCRYPTION_KEY` from `metastore.encryptionKey`, minted by `EncryptionKeyGen`
  when the caller supplies none. In `TenantDb.SecretKeys`, so it never round-trips through any
  response.
- `kind=memory`: refused, nothing is at rest.

The catalog-creating ATTACH is [DuckLakeInitializer.scala](src/main/scala/ai/starlake/quack/ondemand/DuckLakeInitializer.scala),
and it is the site that decides a DuckLake catalog's encryption **permanently**. `guardEncryption`
compares `ducklake_metadata.encrypted` against the row and throws a typed
`EncryptionMismatchException` (blocking, like `DataPathMismatchException`) rather than letting
DuckLake's raw error repeat once per spawn forever. Both metadata keys are read in one statement:
the guard runs before the advisory lock, so a two-statement read could observe a concurrent
initializer mid-write and block a pool until manager restart.

**`INSTALL httpfs; LOAD httpfs;` is forced whenever encryption is on**, regardless of dataPath
scheme. Encryption needs OpenSSL, which httpfs provides; under mbedtls DuckDB 1.4.1+ silently
REFUSES WRITES to an encrypted database. `STORAGE_SQL` only loads httpfs for remote paths, so a
local encrypted file would otherwise come up read-only with no error.

Asymmetry worth knowing: DuckLake errors when attaching an unencrypted catalog WITH `ENCRYPTED`,
but attaching an encrypted catalog WITHOUT the flag silently succeeds, so `guardEncryption` is the
only thing catching an `encrypted=false` row on an encrypted catalog.

Surfaces: REST `database/create`, `qod database create --encrypted [--encryption-key]`, the MCP
admin tool, manifest round-trip, admin UI. `QOD_REQUIRE_ENCRYPTION` (`quack-on-demand.requireEncryption`,
default false) refuses an unencrypted create on **both** the REST and manifest-import paths. Branches
inherit `parent.encrypted`; `BranchCloner` copies `ducklake_metadata` wholesale so the clone is
already correct. An exported manifest redacts `encryptionKey`, so it cannot recreate an encrypted
`duckdb-file` database: deliberate, the key must not leave the control plane.

**Trust boundary:** encryption at rest moves the boundary to the control plane. Whoever can read the
control-plane Postgres can decrypt the data, for both kinds, and no design at this layer changes
that. Note an exported manifest still carries `pgPassword` verbatim, which for an encrypted DuckLake
database opens the catalog holding every per-file key. Design: docs/superpowers/specs/2026-09-21-encryption-at-rest-design.md.

### Branches (Epic 1, writable zero-copy clones)

A branch of a DuckLake tenant-db is a separate DuckLake catalog: `BranchCloner`
(`ondemand/branch/`) copies every `ducklake_*` table into a new Postgres database
under one REPEATABLE READ transaction, rewrites data/delete file paths to the
parent's absolute location and points `data_path` at a **sibling** prefix
`<parent>__br_<id8>/` (never nested: the parent's orphan sweep would delete it).
The clone is registered as a `qodstate_tenant_db` row with `branch_of` set and
metastore key `catalogAlias` = the parent's alias, so the branch's own pool
(`__br_<id8>`, one Dual node) attaches it under the parent's DuckDB name and
grants, policies and user SQL apply unchanged (`TenantDb.catalogAlias` is the one
helper every alias-derivation site goes through; the spawn scripts honour
`catalogAlias`). Rows live in `qodstate_branch` / `qodstate_branch_merge`
(Liquibase `0037`). `BranchService` owns the lifecycle: create -> propose ->
merge / discard / TTL expiry (`BranchWiring` sweep, leader-gated); the merge is described after this
paragraph. Change sets come from `BranchChanges` (pure, over `ducklake_snapshot_changes` verbs; flush
artifacts and compaction are not touches). `PinnedSetResolver` pins every live branch's fork
snapshot, and maintenance expires nothing at or after the oldest one (`expiryFloor`), since reading
main's changes needs consecutive snapshots; the maintenance scheduler skips branch rows;
`deleteTenantDb` refuses a parent with live branches. Targeting: FlightSQL header `branch=<name>`
(authorized against the named parent pool), MCP `branch` argument, `qod sql --branch`.
`TokenRestriction.branchOnly` (PAT `--branch-only`, column `branch_only`) refuses
WRITE/DDL on a non-branch pool in `routedExecutor`. Merge is REST/CLI only (no
MCP tool, `McpCoverageSpec` exclusion). See
docs/superpowers/specs/2026-09-20-branching-v1-design.md and 2026-11-01-branch-three-way-merge-design.md.

**Merge:** approver != proposer, on an ephemeral `__merge` node with the branch attached as `qod_branch`;
every table the branch modified merges cell by cell, matched by `rowid` (main's side is empty when only
the branch changed it), in three stages: Prepare once, then
Decide and Apply per attempt, `RowMergeRunner` and `RowMergeSql`, working tables in the node's
in-memory database; a statement with an empty rowid set is never sent, because a semi-join on an empty
table reads the whole table; Apply is DELETE, UPDATE, INSERT in that order, because an UPDATE costs the
table its statistics for the rest of the transaction. A conflict refuses the merge unless the proposal
names a winner, `--on-conflict main|branch`; `--key` catches rows both sides inserted; `--hold-writers`
on the merge holds other commits on the merged tables. A merge names the proposal it approves,
`proposalId` / `--proposal`, because proposing again replaces the open row: another one in its place is
`409 proposal_replaced`, and one that has merged is `409 already_merged`, naming the main snapshot and the
tag, which is what a retry after a lost answer reads, ahead of the live-branch lookup that would call the
branch gone or a new branch's. A branch's commit lands once: every look reads the message of every proposal of
the branch; the open one's settles, another's refuses propose and merge, `409 proposal_unsettled`. An
Apply is marked on the row before it goes, `error` with the timeout prefix, so a crash reads like a
timed-out send: the proposal stays open and the fence row stays. A stage that writes is a list of single
statements on one connection, `QuackHttpAdapter.sendAll`, each after a session handle of its own, because
a handle lives 60 s and the node checks it at each bind: a PREPARE holding several statements is refused
past 60 s, COMMIT included. A timed-out list sends nothing more and closes, rolling back; a dead
manager's node runs the statement in flight and stops, so only a COMMIT already in flight can land, and
its connection keeps its transaction open on the parent's catalog until the node goes. Known residual,
not a hole to report: once a retry arms, a COMMIT still in flight is held to the retry's snapshot, so a
main commit between the first run's snapshot and its first catalog read has no check. The commit is located by its unique message,
tagged `merge-<branch>-<id8>`, then the branch is torn down; `MergeFence` holds that commit to the
validated main snapshot through a trigger on `ducklake_snapshot_changes` in the parent catalog DB,
schema `qod_merge_fence`, refusing unless every later snapshot is row data or maintenance on other
tables, because DuckLake's own conflict check misses main writes that land before the merge's first read
or into a table the merge drops.

**Discard and expiry** never run under a run of that branch: a propose or a merge holds the branch's own
lock (`BranchService.runLock`, inside the parent's `__merge`) from start to end, and `retire`, the only
path to `Discarded` or `Expired`, tries it around a fresh read of the row and the status writes, then tears
down with it released. Busy, a discard answers `409 branch_busy` and a sweep skips the branch. An open
proposal whose own commit is on main is never abandoned: a discard answers `409 proposal_unsettled` and a
sweep skips the branch with an ERROR, so it stays, holding back expiry on the parent, until a merge records
it; a look that cannot be read is `502 catalog_unavailable`, or a WARN and the next sweep. A propose past
the branch's TTL is `409 branch_expired`, for every principal, before any node and again under the locks,
leaving an open proposal as it is: since a sweep skips a branch a run holds, a loop of proposals would
otherwise keep it, and the expiry floor, past the TTL. A merge past the TTL stays allowed: it is an admin's,
it cannot loop, and it is how an unsettled proposal gets recorded.

**Who may branch:** any principal who may connect to one of the parent's pools, over REST and MCP, in the
tenant it belongs to (`BranchHandlers.gate`, the one gate of every endpoint; design 8.2). Own tenant only:
`s.profile.tenant` for a session, `p.user.tenant` for a PAT, because the handshake resolves a user name
per tenant; anything else is `403 tenant_forbidden`, a tenant that does not exist included. Then the
routed executor's own decision (`caller.authzRequest`, on the pools the token's `databases` and `pools`
axes allow): `403 no_pool_access` with no reason, also for a database that does not exist; `503
authz_unavailable` when it cannot be decided. A superuser or an admin of THE TENANT IN HAND passes without
a pool grant (`BranchHandlers.adminOf`); an admin of another tenant who is a plain member here is a plain
member here. So a `--drop-admin` PAT reaches these endpoints as the plain user it is. Merge is admin only
(`admin_required`), with no MCP tool. A non-admin proposes and discards only a branch they own (`403
not_owner`) and asks for a TTL from 1 up to the server's default when it has one (`400 invalid_ttl`, naming
it; absent takes the default even when that is no TTL, the operator's own choice): a branch with no TTL
stops snapshot expiry above its fork for the whole database. The owner's rule on who gets in, the numbers
and the names below go together: open one without the others and it leaks.

**What a non-admin is shown.** Decide compares as the system principal, so with a write grant and a mask
or a row policy, `hasConflicts` is an equality oracle on what main wrote since the fork, through a key or
an UPDATE of a cell main also changed. So a non-admin who may not count a table gets no `rowConflicts` for
it (`None`), never the flagged row an admin gets (an admin can lift their own mask), and a merge row shows
a non-admin no row numbers at all. A non-admin who may count sees the numbers: a policy holder's count is
refused on every table (`CatalogReachingCalls`), so such a viewer holds no policy. Names follow filtered
metadata: `BranchActor.sees` covers a table when the handshake's effective set, narrowed by the token,
covers it for reading (`RO`, `RW` or `ALL`, exactly what `SHOW TABLES` lists), the parent's catalog alias
being the session catalog (`BranchHandlers.seenBy`), so a DDL-only holder who alters a table on their own
branch reads it only through `otherTables`, as their `SHOW TABLES` hides it.
`changes`, a merge row's summary, conflicts and keys list what they see, with no raw `unsupported` verb;
`BranchChangesResponse.otherTables` counts the rest; `mergeable` is the whole branch's. Tables they do not
see are not counted as them. `diff` and `schema-diff` of such a table answer exactly as for no such table,
at the point where that is decided (the `hidden` argument of `CatalogPreviewHandlers.dataDiff` /
`schemaDiff`, which has no default: the catalog's own endpoints pass `NoneHidden`), and a key on one as for
a table not in the change set, so its columns cannot be probed. An OPA tenant's users see what their QoD
grants cover for reading, as with their `SHOW TABLES`, since OPA does not narrow the handshake's effective
set: nothing with no such grant, and every name on the default built-ins. Residuals, not holes to report: refusal text and a merge row's
`error` can name a table of the change set; `list` shows every branch's owner and name; a proposer sees
whether an admin's merge of their proposal was refused for row conflicts, one bit per merge an admin runs;
and `threeWay` and a visible table's conflict reason tell a policy holder that main touched a table they
cannot fully read, one bit per table, not testable per guess.

### Pool suspend/resume (scale-to-zero)

`qodstate_pool.suspended` marks a pool scaled to zero WITH its role
distribution kept (unlike `stopPool`, which zeroes it, and `disabled`, the
never-auto-woken kill switch). Reconcile skips suspended pools. Reconcile
also heals a crash mid-suspend by draining any still-live nodes of a
suspended pool. The FlightSQL edge wakes a suspended pool on the first
statement: `resumePool(key, "query")` then a bounded poll
(`resumeHoldTimeoutSec`, env `PROXY_RESUME_HOLD_TIMEOUT_SEC`, default 60s)
until a routable node appears, else a retryable "pool is resuming"
UNAVAILABLE. REST: `POST /api/pool/suspend`, `POST /api/pool/resume`,
`startSuspended` on create. SPI: `PoolSuspended` / `PoolResumed` events with
reason `rest | query | module | idle`. See
docs/superpowers/specs/2026-07-18-pool-suspend-resume-design.md.

**Idle hibernation (core, ported from the hosted module).** A leader-gated sweep
(`HibernationWiring` in `boot/`, config `quack-on-demand.hibernation`, envs
`QOD_HIBERNATE_ENABLED` / `QOD_HIBERNATE_SWEEP_SEC` / `QOD_HIBERNATE_IDLE_MIN`)
suspends a running pool once it has served no statements for its idle window,
with reason `idle`. Participation is per-pool opt-in via `Pool.idleTimeoutSec`
(0 = explicit opt-out, floor 5 min) unless `defaultIdleMinutes > 0` sets a
manager-wide default, so the sweep is inert on a fresh install, like autoscale
without a band. Signal: `PoolActivity` (in `route/`) records StatementExecuted
(router sink) and PoolResumed (supervisor sink) timestamps, flushed
GREATEST-upsert by every replica to `qodstate_pool_activity` (Liquibase `0035`);
the decision core (`ondemand/hibernate/Hibernation.candidates`) is pure, with a
leader-local first-seen baseline so never-queried pools hibernate too. With
`enabled=false` Main does not wire `PoolActivity.sink` at all (the sweep's flush
is its sole drainer, same invariant as `PoolLoadStats`).

### Demand scale-out (owner-declared band)

Pools with `minNodes`/`maxNodes` set (create-time or `POST /api/pool/setAutoscale`)
gain readers under sustained load and shed them when quiet, never leaving the
band; writers are never touched and scale-in stops at `minNodes` (zero stays
hibernation's job). Load signal: StatementExecuted -> per-pool 1-minute buckets
(`PoolLoadStats`) flushed upsert-add by every replica to `qodstate_pool_load`;
the HA leader decides (`Autoscale.decide`, pure) and acts through
`PoolSupervisor.scale(reason = "autoscale")`, so quota gating applies. Config
under `quack-on-demand.autoscale` (env `QOD_AUTOSCALE_*`); `enabled` (default
true, env `QOD_AUTOSCALE_ENABLED`) is the manager-wide kill switch and the sweep
is inert without a band anyway. `minNodes == maxNodes` is a legal band that holds
the pool at exactly that size (the per-pool way to opt out). With the switch off
Main does not wire `PoolLoadStats` into the router's event sink at all, since the
sweep is that sink's sole drainer. Manual scale outside the band is refused
(`outside_band`).
See docs/superpowers/specs/2026-08-11-demand-scale-out-policy-design.md.

### HA mode (opt-in, Kubernetes only)

`QOD_HA_ENABLED=true` (helm: `replicaCount > 1`) runs N active-active managers.
All replicas serve REST + FlightSQL; one holds a Postgres session advisory lock
(`HaCoordinator`) and runs the singleton duties (reconcile respawns, bootstrap,
`qod serve` provisioning, DuckLake init, revoked-jti purge). Pool mutations serialize across replicas via
per-pool advisory locks (`PoolLocker`; a single non-HA manager serializes them in-process
through `PoolLocker.inProcess()`, so pool mutations are per-pool serialized on every runtime); caches propagate via LISTEN/NOTIFY on
`qod_topology` / `qod_rbac` / `qod_revocation` with a periodic snapshot-refresh
fallback (a federated source write sends `qod_topology` too, and a tenant's SSO settings rebuild its
`TenantOidcRegistry` authenticator on every replica whose tenant mirror changed). A `restore()` waits
up to 5 s for this replica's own running mutations (the versions lock, `PoolSupervisor.RestoreLockWaitMs`)
and is otherwise skipped with a WARN and run later, never published unlocked. JWT revocations persist in `qodstate_revoked_jti`. Every replica signs
sessions with the same secret without any configuration: an unset
`QOD_SESSION_JWT_SECRET` resolves to the one row in `qodstate_boot_secret` (see
"Session model"), so HA no longer refuses an empty one. HA with the local
backend is refused at config load. See
docs/superpowers/specs/2026-07-02-manager-ha-zero-downtime-design.md.

### `qod serve` boot provisioning (`ServeProvisionHook`)

`qod serve` has the manager provision its tenant, database and pool, so it holds no credential
for it: it writes `serve-provision.json` (owner-only: the database body can carry object-store
credentials) in its data dir, `{"tenant","database","pool"}`, the bodies of
`POST /api/tenant/create`, `/api/database/create` and `/api/pool/create`, deletes any earlier
outcome, and passes the spec's absolute path as `QOD_SERVE_PROVISION` (read like
`QOD_BOOTSTRAP_YAML`: env, else a JVM system property; unset or blank does nothing).
`ServeProvisionHook` (`ondemand/bootstrap/`) runs in the leader duties (`LeaderDuties`, `boot/`)
after `restore()` and before the DuckLake init and `reconcile()`, so a new database is
initialized and its pool spawned in the same boot; a single manager runs it before the REST
server binds, an HA follower only once promoted. It calls the REST create functions
(`createTenantAs` / `createTenantDbAs` / `createPoolAs`, every check and quota included) as
`SessionScope.Superuser`, audited with the actor `system`. Add-only: an object that exists and
matches is left alone; one that differs is refused and never updated (a database whose kind,
`dataPath` or `initSql` differ, or a pool name already serving another database, each reason
naming the way out: `qod serve <target> --name <other>` / `--pool <other>`), and nothing is ever
deleted. It stops at the first refusal and writes `serve-provision.outcome.json` beside the
spec, atomically: `{"status":"ok","created":[...]}` or
`{"status":"failed","step":"spec|tenant|database|pool","reason":"..."}` (`spec`: the file could
not be read or decoded). A failure never stops the boot, and no log line, audit row or outcome carries a
body or a secret. The launcher waits for `/ready`, then for the outcome within
`--ready-timeout`, deletes the spec once it has the outcome, and prints its banner or the failed
step with its hand-run command (`finish by hand:`). Attach mode (a manager already running)
still provisions over REST, with the caller's own credential. `DemoBootstrapHook`
(`QOD_BOOTSTRAP_YAML`) is unrelated: its manifest import replaces rows wholesale and runs only on
an empty store.

### The demo on S3 (`qod serve --demo --s3` / `--s3-path`)

The manager reads S3 keys only from its environment, so the CLI settles everything (`demo_s3.py`) and
hands it to the jar as `QOD_S3_*` plus `QOD_DEMO_S3_PATH` and `QOD_DEMO_S3_LOCAL`, an internal contract
not documented for `docker run`. `DemoS3` is the jar's half. The path's last segment must be
`qod-demo-<8 hex>`, the only folder `DemoS3.deleteRun` can delete, and the default data path is
`<run folder>/data`, since a row with no path derives its folder by replacing the last segment
(`replaceLastSegment`): the run folder itself would put the data beside it. Whenever a path is set and
`QOD_DEMO_S3_LOCAL` is not `1`, every client listener binds `127.0.0.1`, as the demo's superuser logins
are public and the environment holds real keys. `QOD_DUCKLAKE_DATA_PATH` is deliberately ignored by the
demo. The local store is a pinned SeaweedFS (`launcher.WEED_VERSION`, a sha256 per asset in
`_WEED_ASSETS`, hashed again at each bump), on loopback, with random keys. `weed_command` must keep
`-ip`, `-ip.bind`, `-logdir` and the `=0` flags (else a LAN bind, fixed ports 8181 and 9101, and logs in
the system temp folder). It is stopped with a hard kill, as it can take many seconds to honor SIGTERM.
`sweep_orphan` removes a store whose `qod` died, and does nothing while the CLI named `owner` in
`store.json` lives. Temporary AWS credentials are refused, as `QOD_S3_*` has no session token.
`DemoS3Spec` needs a real `weed` (`QOD_DEMO_WEED_BIN` or PATH) and is cancelled without one.
`--s3` is refused before any download on a platform with no SeaweedFS build (Windows on ARM); with
`--demo` and no `--s3-path` the object-store flags are refused, not ignored; a half key pair given as
flags is refused. Two limits on deleting the run folder in the user's bucket: on Windows, Ctrl-C stops
the demo before it can delete the folder (`subprocess.call` terminates the jar about 0.25 s later), so
the user removes it by hand (the `--s3-path` help says so); and a `qod stop` run from another terminal
uses its usual 10 s `FORCE_AFTER`, where the demo's own Ctrl-C waits up to 90 s
(`demo.BUCKET_FORCE_AFTER`, unless `FORCE_AFTER` is set), so it can stop the demo mid-delete: give that
command `FORCE_AFTER=90`. After a Ctrl-C teardown, `_run_supervised` waits `FORCE_AFTER`
(`config.force_after_seconds`) for the manager to exit before killing it, as `perform_stop` returns once
the ports close while the JVM is still deleting the run folder.

### Edge config + catalog ([quack.edge.config](src/main/scala/ai/starlake/quack/edge/config/), [quack.edge.catalog](src/main/scala/ai/starlake/quack/edge/catalog/))

The auth/ACL/session config types and the DuckLake catalog resolver live under [quack.edge.config](src/main/scala/ai/starlake/quack/edge/config/) and [quack.edge.catalog](src/main/scala/ai/starlake/quack/edge/catalog/). The pureconfig `ProductHint`s in [Main.scala](src/main/scala/ai/starlake/quack/Main.scala) override the `derives ConfigReader` defaults to use camelCase (matching `application.conf`) instead of pureconfig's kebab-case.

### Grant matching (the node decider)

Table grants are matched in one place, the node decision pipeline: `AuthzDecider` (`edge/authz/`, behind `DecideRoute`) judges the objects of the plan qod-node bound against the session's cached **EffectiveSet** (closure of roles · groups · `qodstate_role_permission` rows reachable through them) through `GrantMatch` (`edge/sql/`). Superusers (`qodstate_user.tenant IS NULL`) and the system principal are allowed by the node locally and never reach it; an OPA tenant's objects go to its OPA instead (below). There is no gateway validator and no switch to turn the check off: `PostgresAclValidator`, `OpaValidator`, `TenantRoutingValidator` and `StatementValidator.allowAll` were deleted in Phase D (decision 29).

The `EffectiveSet` is cached in `PoolSupervisor` (`qod_effective_sets`, at most 10000 entries) with a 60s TTL keyed by `(userId, jwtRoles.hashCode, jwtGroups.hashCode)`; every RBAC mutator (`createRole`, `addUserRole`, `grantPoolPermission`, etc.) calls `invalidateEffectiveCache()` so a freshly-granted role takes effect on the next statement without waiting on the TTL: a new handshake resolves it, and an open session resolves it again on its first statement after the policy version moved ("Open sessions follow the policy version" above).

**DML / DDL grants are enforced per-table.** The node describes every object a plan reads or writes with its access; `GrantMatch.verbCovers` maps the canonical grant verbs (`RO`/`RW`/`DDL`/`ALL`, see `RolePermission.ValidVerbs`) onto the collapsed `Read | Write | Ddl`. A role permission of `verb=ALL` covers anything; `verb=RW` covers `Read` and `Write` but not `Ddl`; `verb=RO` covers only `Read`. A wildcard catalog grant covers only the session's own catalogs (`GrantMatch.catalogMatch`), never a sibling tenant's. Trigger DDL is superuser-only (tenant admins included).

### OPA authorization (per tenant)

`qodstate_tenant.acl_mode` (Liquibase `0044`, null = `QOD_ACL_MODE`, default `qod`) hands a tenant's data-access decisions to its own OPA server: pool access at handshake (gate 4 through `OpaPoolAccess`) and table and file access per statement, asked by the node decider (`AuthzDecider`, `OpaAuthorizer.decide`) on the objects DuckDB resolved, after the rules QoD always enforces: each access carries `kind`, `columns` and `via` (the outermost secure view it is read through), and `locations` lists the files already admitted, with the scope that admitted each (`OpaInput.decide`, `client.edge` `node`, never `statement.text`). The gateway sends OPA nothing per statement. QoD grant tables are ignored for that tenant; memberships are still sent as identity. Any mode other than exactly `qod` is OPA.
Fail closed: only a literal `result.allow == true` allows; `row_filter` / `masks` are rejected; an outage, timeout or >1 MiB body is `UNAVAILABLE`, never a permission error, and the client sees only `"OPA unreachable or returned an error (see the manager log)"` (`OpaAuthorizer.UnavailableMessage`, also the dry run's text for a non-superuser): the tenant admin picks the OPA URL, so the transport cause (host, port, status) goes to the manager's WARN log only, and `OpaClient.jdkPost` masks the bearer token in it. Superusers never reach OPA. Decisions cache for `QOD_OPA_CACHE_TTL_SEC` keyed on every input field; Rego changes never kill live sessions.
Config block `quack-flightsql.opa` (`QOD_ACL_MODE`, `QOD_OPA_URL`, `QOD_OPA_TIMEOUT_MS`, `QOD_OPA_CACHE_TTL_SEC`); per tenant via `POST /api/tenant/setAcl` / `qod tenant set-acl` (token write-only, redacted in manifests). Dry run `qod tenant opa-test` (`POST /api/tenant/opaTest`, `OpaDryRun`): the handshake form asks the `connect` rule from the manager; the statement form (`--sql`, decision 25) runs one statement through `FlightSqlRouter.dryRun` on a node of the pool, as the named tenant user, under a `DecidePurpose.DryRun` context (`"purpose":"dryRun"` in the signed context, so it never shares a cached verdict), records the decider's outcome and the exact OPA input by nonce (`DryRunResults`, 5 min) and refuses the plan, so it never executes; it reports that input. It never wakes a pool: no routable node is `409 pool_not_running`. Other answers: `400 opa_mode_required` (tenant on QoD grants), `400 single_statement_required`, `400 refused_before_opa` (the gateway's or the node's refusal, or a superuser / system principal: a dry run runs only for a tenant user), `400 nothing_to_authorize`, `503 authz_unavailable`. Known limitation: filtered metadata still follows QoD grants, which OPA does not narrow, so an OPA tenant's user with no read-covering QoD grant gets empty DESCRIBE and SHOW TABLES answers and a refused SUMMARIZE (the gateway's rewrites read those listings), while one on the default built-ins sees every name. The starter policy in examples/opa covers `columns`, `via` and `locations`.
Starter policy and operator guide: [examples/opa/](examples/opa/README.md). Design: docs/superpowers/specs/2026-10-01-opa-authorization-design.md.

### REST authZ - tenant scope on every RBAC endpoint

Every handler in `api/{User,Role,Group,Membership,PoolPermission}Handlers.scala` calls `TenantScopeCheck.{reject,rejectForResource,rejectForUser}` before mutating. Id-only endpoints (e.g. `POST /api/role/delete {id}`) resolve the owning tenant via 5 supervisor lookup helpers (`tenantForUser`, `tenantForRole`, `tenantForGroup`, `tenantForRolePermission`, `tenantForPoolPermission`) before applying the gate. Tenant-A admin sessions and PATs get `403 tenant_forbidden` on any tenant-B resource; superusers bypass. Every caller is a session or a PAT (the static API key is gone), and the scope resolver is total (`SessionScope.resolver`): no credential, or one that stopped resolving, is `NoAccess`, never trusted. Missing ids 404 (no cross-tenant existence leak via differential error codes). See `RbacTenantScopeSpec` for the 14 cases that pin the contract.

### Tenant sign-in

A management login takes its authority only from `qodstate_user` rows. In db mode a profile the DB authenticator produced is its own grant (`AuthHandlers.mintSessionFor` tests `authMethod == DatabaseAuthenticator.AuthMethod`): its `(tenant, role)` is the row whose password was verified (for a tenant password login an admin row, or a regular user's row as a profile-only session). Every other login only proves who the user is, and its grants are read from the rows of the realm it was verified in (`LoginRealm`, `ondemand/auth/`): the tenant SSO callback, every system login under `identitySource=oidc` (the DB authenticator's too), and a password login answered by the manager-wide Keycloak or Azure password grant, under either `identitySource`. The basic chain tries that grant after the DB authenticator, so with one enabled a db tenant's password form also accepts the IdP's passwords (any failed DB check falls through to it). The IdP's role claim and any tenant it carries are discarded; `UserStore.grantsForIdentity(realm, ...)` is asked with `LoginRealm.Tenant(t)` for a tenant login (the SSO callback, or a db tenant's password form) and `LoginRealm.System` without a tenant. The email fallback of that lookup is decided by the entry point (`mintSessionFor`'s `lookupEmail`, which has no default): a password login hands none, so it signs in as the QoD user of that tenant with the same user name as the IdP account, with that row's rights only, and the lookup never retries with the email, which is why the IdP's user names must be assigned by an admin, never self-chosen (the seeded superusers `admin@localhost.local` and `admin` are names an IdP realm must not hand out; this holds on the data plane too, where a `superuser=true` FlightSQL or front door handshake binds the superuser row a token's `preferred_username` names); the SSO callback hands the token's email only when the IdP marks it verified (`email_verified` true), else an address the account holder typed could name the superuser's row. The IdP authenticators (`OidcBearerAuthenticator`, `JwtBearerAuthenticator`, `ResourceOwnerPasswordAuthenticator`) name the user by the token's `preferred_username`, else its `email` claim, else its subject, and refuse a token naming none of them (the password grant never falls back to the typed name, a bearer is never named `unknown`), so a token without `preferred_username` is named by an address its holder may have typed. One shared rule, `IdentityNaming.userNameMayBindRow` (`edge/auth/`), lets a name that came from the email claim (the profile's claims carry no `preferred_username` and `email` equals the user name) bind a row only when `email_verified` is `"true"`, on every plane: `mintSessionFor` then binds nothing and answers `403 not_provisioned` with no lookup; `EdgeHandshake` (FlightSQL and the native Quack front door, so the password grant on a basic handshake, a manager-wide bearer and a tenant's bearer from `TenantOidcRegistry` alike) refuses the identity as `Unauthenticated` with `IdentityNaming.UnverifiedEmailRefusal` before the supervisor gate, so no row is looked up and no JWT role or group is resolved (it refuses a blank user name, `IdentityNaming.namesNoUser`, the same way, which `TenantSelector` would otherwise replace with the token's `sub`). The database authenticator's profile carries no `email` claim and is never refused. `ResourceOwnerPasswordAuthenticator` carries `preferred_username` and `email_verified` into the profile's claims for that rule (`sub` and its other claims are as before: the FlightSQL data plane and OPA inputs read them). So a token with an `email` claim, no `preferred_username` and no true `email_verified` is refused on every plane (a token with neither claim is named by `sub` and unaffected); the likeliest breaks are an operator's external JWT signer that names users by `email` (fix: mint `preferred_username` or `email_verified: true`) and Azure v1 access tokens, since Azure never sends `email_verified` (fix: v2 tokens, which carry `preferred_username`). The tenant realm matches rows of tenant `t` only (its email fallback too), never a superuser row or another tenant's, so such a session is never `superuser`; its minted profile names tenant `t` whatever `authMethod` it carries (an SSO profile's is the issuer host, which a tenant admin picks), so a PAT minted from it is owned by the tenant's row; and an identity with rows only in other tenants or only a superuser row gets `403 not_provisioned` (the SSO callback lands on `/ui/?error=not_provisioned`). An IdP `admin` claim makes nobody admin or superuser. Superusers sign in through the bare `/ui/` or a password login without a tenant (`LoginRealm.System`, unchanged). The SSO service is wired whatever `identitySource` says, so tenant SSO works under the default `db` too (0.10.1 answered `/api/auth/oidc/start` with `404 auth_mode_disabled` unless `identitySource=oidc`); `identitySource` only picks the bare `/ui/` login mode and how that login's grants resolve, and under `db` that login stays password-only even when `QOD_MGMT_OIDC_*` are set. A tenant's SSO also needs the tenant itself to sign in through its IdP: `oidcStart` and `oidcCallback` serve a tenant scope only when `ManagementAuthModeResolver.modeFor(Some(t))` is `Oidc` (else `400 oidc_not_configured`, or a redirect to `/ui/?error=oidc_not_configured` with nothing minted), so a tenant switched to `db` without clearing its auth config keeps no SSO. On a db-mode manager the tenant SSO redirect URI is built from `QOD_MGMT_PUBLIC_BASE_URL` (`http://localhost:<port>` when unset, and the boot warning for that is gated on `identitySource=oidc`, at WARN). REST and MCP statements follow the realm rule: `ExecCaller.superuserAdmissible` (a session's `scope.superuser`; for a PAT, whether its owner has no tenant) reaches `AuthzRequest.superuserAdmissible`, which has no default (nor does `PoolSupervisor.authorizeHandshake`'s), so a caller that is not a superuser never binds a superuser row; the flag grants nothing, and `ExecCaller.system` stays the only route to the synthetic superuser set. A tenant whose console sign-in is OIDC (`ManagementAuthModeResolver`: a non-`db` provider with `issuerUrl`, `clientId` and a secret) takes no password on `POST /api/auth/login`: `400 sso_required` (its message links `/ui/?tenant=<tenant id>`), before any password check, so no lockout counter moves; an unknown tenant, or one whose sign-in is incomplete, still authenticates first (`401 invalid_credentials`, `400 tenant_oidc_misconfigured`), and the FlightSQL and Quack data planes still take its users' passwords. `GET /api/auth/mode` trims and lowercases the tenant it is given, as tenant ids are lowercase slugs.

Tenant admins configure their own tenant's sign-in (`tenant/setAuth`, MCP `set_tenant_auth`, `qod tenant set-auth`, the UI's Auth Provider page); `TenantSignInWrite` holds every write rule. The client secret is a write-only value (`qodstate_tenant.auth_client_secret`, Liquibase `0048`; `clientSecret` on the request, `clientSecretSet` on responses, `***REDACTED***` in manifests, redacted in `Tenant.toString`; the CLI never takes it on argv: `--client-secret-stdin`, `--client-secret-prompt`, `--clear-client-secret`) bound to the issuer it was saved with, the `setTenantAcl` rule for the OPA token: a save that changes `issuerUrl` drops the stored value unless it supplies a new one, and drops a carried `clientSecretRef` unless a superuser makes it (a superuser may pair a reference with the new issuer in one write; a tenant admin's carried reference is dropped). A `clientSecretRef` resolves from the manager's own environment, so only a superuser may add or change one (anyone else gets `403 superuser_required` and nothing is written), as for a federation `externalRef`. One secret source per tenant: a supplied value replaces a reference, a reference a superuser adds or changes replaces the value, and a write that does both is `400 secret_conflict`; a whitespace-only value is `400 invalid_client_secret`, and a `clientSecret` key inside `authConfig` is refused (`400 invalid_auth_config`, on `tenant/create` too). The admin UI warns before a save that would drop the secret or leave an OIDC sign-in incomplete (`issuerUrl`, `clientId` and a secret source are all needed; until then only a superuser, through the system login, can open the tenant's console).

`0048` parked every reference stored before it under `clientSecretRefUnconfirmed`, which nothing reads (that tenant's sign-in is misconfigured: `GET /api/auth/mode` answers `tenant_oidc_misconfigured`, the SSO start `oidc_not_configured`, and its password login `400 tenant_oidc_misconfigured` once the password verifies), so after the upgrade every tenant whose sign-in used a reference stays misconfigured until a superuser restores it (its admins can store the secret themselves only with a PAT or a session they already hold). Under an HA rolling upgrade a 0.10.x replica still serving writes stores a tenant admin's reference without the superuser rule and the migration, which already ran, does not park it: finish the rollout before tenant admins edit sign-in settings, or review the references afterwards. Boot logs one ERROR line per parked tenant (`ParkedSecretRefs`): the tenant, the reference, a request to confirm that its `issuerUrl` is the tenant's own identity provider, and the restore command. The reference is shown only when it starts with a known prefix (`env:`, `aws-sm:`, `gcp-sm:`, `azure-kv:`, `vault:`) and goes into the command only when it is made of `[A-Za-z0-9_:./=-]` (else the command shows `clientSecretRef=...`, and the full value is in `qod --json tenant list`); any other parked value prints as `<not a secret reference, not shown>` and the line advises setting a secret source (the tenant's own client secret, or a reference) instead of restoring it. `TenantSignInWrite.settleParkedKey` is the one rule for the key, shared with the manifest import: a save keeps it only when it carries it unchanged, keeps `issuerUrl` and holds no secret source; any other save drops it (a secret value or a reference, a moved issuer, a key the request adds, changes or leaves out), whatever the client (REST, MCP, CLI, UI, manifest), which ends the error line while the sign-in stays misconfigured until a secret source is set. Manifests export `clientSecret` as `***REDACTED***`; import keeps the stored value only for the marker with an unchanged issuer (`TenantSignInWrite.issuerOf`), refuses the marker otherwise, a whitespace-only value (an empty string means no secret), a `clientSecret` key in `authConfig` and a secret together with a reference, and an absent `clientSecret` clears a stored one; a `clientSecretRef` imports as written, so an export taken before 0.11.0 re-activates its references without the parked review. `TenantOidcRegistry` (FlightSQL bearer validation) reads no secret: a tenant's public fields alone (`google`: `clientId`; `keycloak`: `baseUrl`, `realm`, `clientId`; `azure`: `tenantId`, `clientId`; `aws`: `region`, `userPoolId`, `clientId`) give it its own authenticator, so a tenant admin can turn it on, and its tokens must carry that client id as audience. Design: docs/superpowers/specs/2026-10-24-tenant-signin-isolation-design.md.

### SCIM 2.0 provisioning (`/api/scim/v2/{tenant}`)

`ScimEndpoints` / `ScimHandlers` (in `ondemand/api/`) serve RFC 7643/7644 Users and Groups
over the RBAC store for IdP connectors (Okta, Entra): CRUD, `eq` filters
(userName/externalId/displayName), pagination, PATCH (active toggle incl. Entra's string
booleans, member add/remove/replace), plus ServiceProviderConfig/ResourceTypes/Schemas.
Bodies are raw strings (SCIM clients send `application/scim+json`, and errors must be the
SCIM envelope, not `ErrorResponse`). Auth: a tenant-admin PAT (a session also works) as `Authorization: Bearer`,
accepted by `apiKeyGuard` for the `/api/scim/` prefix ONLY, alongside the usual
header/cookie; a tenant-scoped PAT reaches only its own tenant's base URL. Mapping: userName=username (immutable), active=enabled, primary
email=email (EmailPolicy applies), externalId=new `external_id` columns (Liquibase
`0036`, written only by SCIM). The superuser realm is invisible to SCIM; passwordless
creates get a random password (users sign in via tenant OIDC). SCIM routes are excluded
from CLI parity (`cli/tests/test_rest_parity.py`) as machine-to-machine. See `ScimSpec`.

### Session model - JWT in HttpOnly cookie

`SessionTokenStore` is a stateless JWT signer/verifier (HS256, secret from `manager.auth.management.sessionJwtSecret`). `mintWithScope` returns the compact JWT; `get` parses + verifies + reconstructs the `Session` from claims. Revocation: bounded in-process jti denylist (lost on restart; the documented trade-off for going stateless).

On the wire:
- `AuthHandlers.login` returns the JWT both in the JSON body (`LoginResponse.token`, for the CLI and scripts, which send it as `X-API-Key`) AND as `Set-Cookie: qod_session=<jwt>; HttpOnly; Secure; SameSite=Lax; Path=/api`.
- `ManagerServer.apiKeyGuard` admits a session or a live PAT and nothing else, on the `X-API-Key` header (CLI, scripts; the name is historical) OR the `qod_session` cookie (browser), plus `Authorization: Bearer` on `/api/scim/**`. `/mcp`, outside the guard, admits only a live PAT as `Authorization: Bearer` (sessions are refused there).
- UI does not stash a token in localStorage; fetch's same-origin credentials policy auto-attaches the cookie.

Cookie attributes are configurable: `sessionCookieSecure` (env `QOD_SESSION_COOKIE_SECURE`, default `true`), `sessionCookiePath` (env `QOD_SESSION_COOKIE_PATH`, default `/api` -- override behind a path-rewriting reverse proxy). The JWT exp is absolute (8h from mint by default, env `QOD_SESSION_IDLE_TTL_SEC`); there's no sliding-window refresh. The secret is resolved by `BootPreflight.withSessionSecret` (over `SessionSecretStore`, `ondemand/state/`) right after the Liquibase apply and before anything reads it: a non-empty (after trim) `QOD_SESSION_JWT_SECRET` wins, is used as given and is never written to the database; otherwise the first boot generates one and stores it in `qodstate_boot_secret` (Liquibase `0049`, row `session_jwt`; Postgres generates the value, 96 hex digits from three `gen_random_uuid()`, at least as strong as `SessionTokenStore.randomSecret()`, so it never travels as a bind parameter that statement logging would record), and every later boot and every HA replica reads it (`INSERT ... ON CONFLICT (name) DO NOTHING` then `SELECT`, so replicas booting together converge on one value). Sessions and password-reset links therefore survive restarts. While a value is configured the stored row is left untouched, so removing the env falls back to it. Boot prints one line naming the source (`from QOD_SESSION_JWT_SECRET (quack-on-demand.auth.management.sessionJwtSecret)`, `generated and stored in the control plane`, `stored in the control plane`), never the value (println, not the logger, so the default ERROR level can't swallow it); `/api/config` shows the configured key only as `(set)` / `(unset)`, and neither manifest export nor telemetry reads the table. Every consumer takes the resolved `mgrCfg`: `SessionTokenStore`, `ResetTokenStore`, the management `OidcStateCodec` and `SqlTokenOidcService`. Rotation: change `QOD_SESSION_JWT_SECRET`, or, for the stored one, `DELETE FROM qodstate_boot_secret WHERE name = 'session_jwt'` and restart every replica; either way every session and outstanding reset link dies. Set a value only when another service verifies the sessions: Starflow (`qod start --with-starflow` generates one into the profile, Compose's `SESSION_JWT_SECRET` feeds both `QOD_SESSION_JWT_SECRET` and the `JWT_SECRET_KEY` bearer provider). **Trust boundary (read is not write):** a writer of the control-plane database (the Postgres admin, or anyone holding the control-plane login, nodes that inherit it included) gains nothing new, since it can already rewrite `qodstate_user`. A read-only holder (a `pg_dump` backup, a snapshot, a read replica, a SELECT-only role) now also gets the management plane: on an install with no `QOD_SESSION_JWT_SECRET` it can forge a superuser session. That comes on top of the credentials the same database already stores, among them: each tenant-db's catalog `pgPassword` and duckdb-file `encryptionKey` (`qodstate_tenant_db.metastore_params`), its object-store keys (`object_store_params`), inline federation secrets (`qodstate_federated_secret.value`), the node-key secret (`qodstate_node_authz`), the manager's bearer to each node's `/quack` endpoint (`qodstate_node.token`), tenant OIDC client secrets (`qodstate_tenant.auth_client_secret`), tenant OPA tokens (`qodstate_tenant.opa_token`) and each user's own S3 keys (`qodstate_user_s3_credential.secret_access_key`, `session_token`). Protect control-plane backups and replicas like the secret itself; when they are less trusted than the manager, pin `QOD_SESSION_JWT_SECRET` from an external secret store: nothing is persisted then, and backups never carry it.

Forced password change: `qodstate_user.must_change_password` (set via `mustChangePassword`
on `user/create` / `user/update`) makes `DatabaseAuthenticator` refuse the password on BOTH
the REST login (401 `password_change_required`) and the FlightSQL handshake, until the user
swaps it through the public pre-session `POST /api/auth/change-password` (current password
is the credential; new must differ; clears the flag). The auth queries MUST project
`(password_hash, kind, enabled, must_change_password)` - a shorter custom
`QOD_AUTH_DB_SYSTEM_QUERY` / `QOD_AUTH_DB_TENANT_QUERY` fails boot and every login.

**Regular-user profile sessions.** The manager UI is not admin-exclusive: a tenant-scoped
`kind=user` principal can log in through their tenant (the blank/system login and OIDC SSO
stay admin-only). `AuthHandlers.mintSessionFor` accepts a DB-mode `Tenant(t)` login from any
principal holding a grant on `t`, not just an admin, and mints a non-admin, profile-only
session (`LoginResponse.admin = false`). `ManagerServer.apiKeyGuard` demotes such sessions (and
a non-admin's PATs) to a fixed allowlist (`isProfileApi`: `/api/auth/whoami`,
`/api/auth/logout`, `/api/profile/usage`, `/api/profile/statements`, `/api/profile/pools`, the
caller's own `/api/auth/pat/{create,list,revoke,delete}`, their own S3 credentials
`/api/profile/s3/{list,add,remove}`, and `/api/auth/sso/ticket` when the
Starlake integration is on; `isBranchApi`: `POST /api/branch/{create,propose,discard}` and the `GET`s
`/api/branch/tenant/<t>/database/<d>/branches[/<b>[/changes|/diff|/schema-diff]]`, matched on method and
whole segments, never a prefix, which also skip the guard's URL-tenant check since `BranchHandlers.gate`
decides) and answers `403 admin_required` on everything else; the UI mounts
only the `/profile` route (own password change; own usage/statements; own S3 credentials) for them.
`GET /api/profile/pools` (`qod profile pools`) answers
`{"pools":[{"tenant":...,"tenantDb":...,"pool":...,"status":...}]}` (the field names and
`status` values of `/api/pool/list`, `ready`, `disabled` or `blocked`, nothing else) for the pools of the
caller's tenants (every tenant for a superuser) that it may connect to, each decided by the
FlightSQL handshake's own `PoolSupervisor.authorizeHandshakeDetailed` for the request
`ExecCaller.authzRequest` builds, and narrowed by a PAT's `databases` / `pools`; a denied
pool is omitted, and an unavailable decision (the tenant's OPA unreachable) answers
`503 authz_unavailable`. Starflow lists pools through it with the user's own session (a
starlake-api change), so it holds no QoD credential of its own.

### Account lockout, SMTP, and self-service password reset

`qodstate_user` carries an optional `email` column (Liquibase `0029-user-email`). A row with an email can use the public self-service reset flow and is eligible for lockout; a row with a non-email username and no email cannot reset by email and is never subject to lockout.

When the username itself is in email format, `email` is auto-set to it and immutable - `user/create` / `user/update`, manifest import, AND boot seeding (`BootPreflight.seedAdminUsers` derives `email = username` for an email-format admin, matching the 0031 backfill so fresh and upgraded installs agree) all follow the rule; a conflicting value is rejected with `400 invalid_email`; pre-existing rows are backfilled by Liquibase `0031-user-email-from-username`. This means the default seeded admin `admin@localhost.local` carries `email = admin@localhost.local`, so it IS lockable when lockout is on and reset-eligible. A locked or forgotten superuser password is recovered with `qod admin reset-password` (writes the new hash straight into the control-plane database and clears `failed_attempts` / `locked_at`, for every configured admin name or the one `--username` names; it re-enables a disabled superuser only when `--username` names it, which also revives that account's personal access tokens, while a reset without `--username` leaves a disabled account disabled and says so; works with the manager down against an external control plane; it never starts Postgres, so for the `qod serve` embedded one that Postgres must be running and a stopped one is refused), or by another superuser through `qod user update --password`; a deleted superuser row comes back at the next boot with `QOD_ADMIN_PASSWORD` set (`BootPreflight.seedAdminUsers` creates any configured admin whose row is missing). A restart does NOT reset it: the admin is seeded only when its row is missing, and `QOD_ADMIN_PASSWORD` is used on that first boot only (`qod start` / `qod serve` prompt for it then and never store it). With the default `QOD_ADMIN_USERNAME` (`admin@localhost.local,admin`) both superusers share that first-boot password: rotate each one (`qod auth change-password --username <name>` per name); `qod admin reset-password` resets every configured admin name by default (`--username` targets one). Because `admin@localhost.local` is not a routable mailbox, its self-service email reset does not deliver by default - set `QOD_ADMIN_USERNAME` to a real address for email self-recovery.

**SMTP** (`quack-on-demand.smtp`, env `QOD_SMTP_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_FROM` / `_STARTTLS`): when `host` is unset (default), Main wires `LogMailSender` (logs recipient/subject, never actually sends); setting `QOD_SMTP_HOST` switches to `SmtpMailSender` (Jakarta Mail). `QOD_PUBLIC_BASE_URL` (`quack-on-demand.publicBaseUrl`, default `""`) is the externally-visible origin used to build the mailed reset link (`$publicBaseUrl/ui/reset-password?token=...`); left empty the link is host-relative and Main logs a boot warning.

**Self-service reset**: `POST /api/auth/forgot-password {tenant?, username}` is a public endpoint (no credential) that always returns 200 regardless of whether the account exists or has an email - anti-enumeration is load-bearing, and the lookup + send are decoupled so response timing doesn't leak account existence either. `POST /api/auth/reset-password {token, newPassword}` (also public) redeems the token. The token (`ResetTokenStore`) is a stateless, single-use, 1-hour HS256 JWT signed with the same secret as `QOD_SESSION_JWT_SECRET`; single-use is enforced not by a redemption ledger but by embedding a fingerprint of the password hash at mint time, so any password change invalidates every outstanding link. `qod auth forgot-password` / `qod auth reset-password` are the CLI equivalents; `qod user create/update --email` sets the column.

**Account lockout** (`quack-on-demand.auth.lockout`, env `QOD_AUTH_LOCKOUT_ENABLED` default `false`, `QOD_AUTH_LOCKOUT_MAX_FAILURES` default `10`) is opt-in. Enabling it requires a working SMTP relay - `BootPreflight.checkLockoutSmtp` refuses to start otherwise, naming `QOD_SMTP_HOST` in the error - because a locked-out user with no mail path would have no way back in. Lockout only ever applies to rows with a non-null `email`; a row with a non-email username and no email can never be locked no matter how many failed attempts, whereas the seeded superuser `admin@localhost.local` carries `email = username` and so is lockable. `DatabaseAuthenticator` checks the lock before bcrypt and, on a locked row, returns `AuthFailure.AccountLocked` -> REST `401 {"error":"account_locked","message":"account locked - use forgot password to reset your credentials"}` (same failure wins the FlightSQL handshake too). Failures accumulate atomically per row and reset to zero on any successful login. Recovery is either the self-service reset above or an admin password reset (`user/update` with a new `password`, or the CLI equivalent) - both unconditionally clear `failed_attempts` and `locked_at` as part of the password write.

### K8s backend - per-pod and per-pool Secrets

**No secret ever sits in a node process's environment or command line, on any backend** (a node user with SQL reads `getenv()` and `/proc/<pid>/{environ,cmdline}` of qod-node and of its wrapper script). `NodeSecretFiles` (`ondemand/runtime/`) owns the key list (`pgPassword`, `encryptionKey`, `extraSetupSql`, `objectStoreSql`, `QOD_NODE_TOKEN`, `QOD_NODE_AUTHZ_KEY`, `tenantDbInitSql`, `tenantDbInitSqlTrusted`, `tenantPoolInitSql`, `tenantPoolInitSqlTrusted`, `QOD_INIT_NONCE`, the `QOD_S3_*` / `QOD_AZURE_*` / `QOD_GCS_*` credentials), mirrored by `SECRET_KEYS` in both spawn scripts and `_SECRET_KEYS` in `fleet_join.py` (`NodeSecretFilesSpec` pins all four). A metastore supplies only its own two (`pgPassword`, `encryptionKey`, any spelling); every other file comes from the manager. The spawn script reads them from one file per key in `$QOD_SECRETS_DIR` (all but the token and the node key, which qod-node reads itself, and the nonce, which both read) and ignores (with a WARN) any that still arrives as an env var; the token is no longer argv `$2`. Local (`LocalQuackBackend`, sh and ps1) and fleet (`qod fleet join`) write a fresh owner-only directory (0700 / files 0600, owner-only ACL on Windows) with `QOD_SECRETS_DIR_EPHEMERAL=1`, which the script deletes right after reading; the launcher also deletes it on process exit (local) or once the node is up / failed / stopped (fleet), and sweeps stale ones at boot. `LocalQuackBackend` also drops the manager's own inherited credentials (`NodeSecretFiles.isInheritedSecret`: `*PASSWORD*`, `*SECRET*`, `*TOKEN*`, `*API_KEY*`...; `AWS_*` / `AZURE_*` / `GOOGLE_*` kept for DuckDB's credential chain). The psql CREATE DATABASE fallback in the script (only on the manager's `createDatabase` stamp, a DuckLake row of QoD's own server) uses a private `PGPASSFILE`, never `PGPASSWORD`. Users' own S3 keys never reach a node as a file or an env var either: qod-node fetches them per connection from the handle's replica and holds them only as that connection's secrets (decision 23).

`KubernetesQuackBackend` creates one Pod + one Service per node, plus up to four Secrets, all delivered as files of ONE read-only projected volume `qod-node-secrets` (mode 0400, which kubelet turns into 0440 group `fsGroup`, mounted at `/var/run/qod-secrets` = the pod's `QOD_SECRETS_DIR`), never as env vars or `secretKeyRef`s:
- **Per-pod token Secret** `qod-token-${nodeId}`. Holds the manager-minted bearer (`QOD_NODE_TOKEN`) the manager presents on calls to that pod's `/quack` endpoint, the node's key (`authzKey`, projected as the file `QOD_NODE_AUTHZ_KEY`), derived from the node-key secret and the node id so every start writes the same one, and a fresh init nonce on every write (`initNonce`, projected as `QOD_INIT_NONCE`). The pod's plain env carries its id as `QOD_NODE_ID` (not a secret; a metastore entry of that name is dropped). `discoverExisting` reads the Secret on manager restart to repopulate the in-memory token cache, so adopted pods don't 401 after a redeploy.
- **Per-pool federation Secret** `qod-fedsql--${tenant}--${tenantDb}--${pool}` (each name as a node id reads it). Holds the resolved federation SQL (`spec.extraSetupSql`) and the tenant-db's and the pool's initSql, each under the file name its trust gives it and only when non-empty; a pod projects only the keys of its own spec, so a pod started for a trusted text never reads a later untrusted one as trusted. GC'd when the last pod of the pool stops; rotation = update the Secret once, restart pods.
- **Per-pod object-store Secret** `qod-store-${nodeId}` when `spec.objectStoreSql` is non-empty.
- **Per-pool node-env Secret** `qod-nodeenv--${tenant}--${tenantDb}--${pool}` (each name as a node id reads it). Holds the sensitive metastore values, `TenantDb.NodeSecretEnvKeys` = `pgPassword` + `encryptionKey`, plus the manager's own object-store credentials (`NodeSecretFiles.CloudCredKeys`, formerly forwarded as plain env). The Secret keeps a key's spelling (`PgPassword`); the volume item maps it to the canonical file name. The volume items and the Secret's contents derive from ONE filtered map. GC'd with the last pod of the pool.

The labels `quack-tenant`, `quack-tenant-db` and `quack-pool` hold the name as it is, or `qod.<name>.qod` when a label value could not hold it (`PoolLabelValue`: `__merge`, `__maint`, `__br_<id8>`, any older name with `_` at either end, which an API server answers with a 422), everywhere they are written, selected on and read back. A node id is a Service name and the `quack-node-id` label, both at most 63 characters: on every runtime, `PoolSupervisor.nodeId` composes `quack--<tenant>--<db>--<pool>--<index>`, each name lowercase with `_` as `-`. Past 63, or with a name the rule below refuses, it keeps its start and ends `-<hash>-<index>`, the hash being 16 hex characters of a SHA-256 of the tenant, tenant-db, pool and index as given, joined by a NUL (Postgres stores no NUL, so no name holds one). The index stays last because `MissingSlots` and `placementForNodeId` read it; a plain id has `--` before it and a hashed one a hex digit, so the two never meet. A branch's id is always hashed, its names holding `__`. A per-pool Secret is `<prefix>--<tenant>--<db>--<pool>`, or that first id's hash when it was hashed (`KubernetesQuackBackend.perPoolSecretName`). Only a fresh slot's id is composed; a node with a row keeps the id in it, so a node an earlier release started keeps `quack-<tenant>-<db>-<pool>-<index>`, past 63 too on the local and fleet runtimes, and `NodeCalls.MaxNodeIdLength` stays 255. Such a pod reads its per-pool Secrets under the old names until its next start: they stay until no pod in the namespace reads them, collected at each `start`, each `stop` and at boot in `discoverExisting` (`legacyPerPoolSecretName`, `collectLegacySecrets`). A merge node's id holds neither name and stays within 35; a maintenance node's cuts the database's name to stay within 63.

**The rule on names.** A new tenant, database or pool name, read lowercase with `_` as `-`, holds no `--` and neither starts nor ends with `-` (`Names.readsAsOneField`), so `--` splits a plain id back into its key and two pools can never read alike. A pool name is also 1..63 letters, digits, `_` or `-`, starting with a letter (`Names.poolNameRefusal`). Tenant and database names cannot hold `-`, but a pool can, so `PoolSupervisor.createPool` refuses a pool whose converted name another pool of its database has, read from the store under a lock on the converted name: `409 pool_reads_alike`, naming it. The rule lives in the supervisor's three create functions, so REST, MCP, `qod serve` provisioning and the hosted signup, which calls the supervisor directly, all meet it: `400 invalid_id` for a tenant, `400 invalid_name` for a database or a pool. The SQL dialect and SCIM create none of these. The manager's own names hold `__` by design and are made by paths that do not ask: `createPool(internalName = true)` and `registerBranchTenantDb`; no request reaches them. Nothing stored is judged: an update never renames, and a manifest import keeps importing a name an earlier release could make, with one ERROR per name (`ManifestImporter.namesANewCreateWouldRefuse`), so a backup always restores. Pools that already read alike get no boot report. `qodstate_node.node_id` is the primary key and `upsertNode` moves the row, so such a pair held one row per id, and at the first boot a node with a row keeps its id while a pool left with none starts in the new form (fleet: once a server is free). On Kubernetes a running pod keeps reading the old-named Secrets, whoever last wrote them, until its next start. Two pools of one database that differ only by `_` and `-` read alike in the new form too, so a fresh slot of the one on a stored id takes the other's id and they restart each other as before: only recreating one under another name ends it, and on Kubernetes the other's nodes then need a restart, since deleting the first removes the per-pool Secrets they share. A label value past 63 characters still gets a 422: a name made before the rule with `_` at either end past 55 characters once wrapped, or a pool name past 63.

All referenced Secrets must exist BEFORE pod create (kubelet will not start a pod whose volume references a missing Secret), so `start(spec)` runs `ensureTokenSecret`, `ensureFederationSecret`, `ensureNodeEnvSecret` and `ensureObjectStoreSecret` first, then creates the pod. The node image entrypoint passes only the port. The mounted files stay readable inside the pod by a node user with unrestricted SQL (a superuser; tenant users' file reads are decided on the node), and nothing bounds what that credential reaches yet: a database without its own metastore credentials inherits `QOD_PG_USER` / `QOD_PG_PASSWORD`, the control plane's login, so one read opens the `qod` database and every tenant's catalog (and the Postgres server itself when that login is a superuser). The per-database least-privilege Postgres role (docs/superpowers/specs/2026-10-11-node-least-privilege-pg-role-design.md) would bound it, but is design only. Interim mitigation: tenant users cannot read the mounted files (the node refuses every file outside a tenant's authorized folders and the user's own S3 scopes to non-superusers, on every pool; per-pool lockdown and `QOD_NODE_LOCKDOWN` are gone, and a set `QOD_NODE_LOCKDOWN` refuses boot), and in the Helm chart `postgres.user` has no default (the chart `required`s it and documents a non-superuser `CREATEDB` role). The chart also `required`s `admin.password` or `admin.existingSecret` (nobody could log in otherwise: the manager has no static API key) and `fail`s on a non-empty `apiKey` value (an all-empty block left in an older values file passes).

**Upgrading:** pods created by an earlier manager keep their old env shape and are NOT migrated in place. Restart every node after upgrading, for example by scaling each pool down and back up.

### Fleet backend (bare servers, no Kubernetes)

`QOD_RUNTIME_TYPE=fleet` plus `QOD_FLEET_JOIN_TOKEN` and `QOD_INTERNAL_URL` (boot refuses without it: each replica's own plain `http://` address on the servers' private network, which every node calls for its verdicts). Linux/macOS servers join by running
`qod fleet join` (`cli/src/qod_cli/fleet_join.py`, a long-running process), which heartbeats `POST /api/fleet/heartbeat` (header
`X-Fleet-Token`, public at the guard only in fleet mode; the handler checks the token) and
runs the one node the reply assigns (its token and node key `authzKey` as secret files, its id as `QOD_NODE_ID`, a fresh `QOD_INIT_NONCE` minted per start, the tenant-db's and pool's initSql as `tenantDbInitSql` / `tenantPoolInitSql` files with their trust flags; the manager's own cpu/memory SETs ride `resourceSql`, which replaced `dbInitSql`) via the bundled spawn script, bound to `QOD_NODE_BIND`. Servers must run the manager's qod release: an older join process does not know the split assignment.
Two tables: `qodstate_fleet_server` (identity, assignment; locked by claims) and
`qodstate_fleet_heartbeat` (server-reported state and capacity; rewritten every interval, never
locked by claims). `FleetQuackBackend` (`ondemand/runtime/`) claims with one
`UPDATE ... FOR UPDATE OF s SKIP LOCKED` plus a memory-fit predicate and waits for the server to
report `running`. Liveness comes from the database clock (`silent_seconds`), never the JVM's, so
HA replicas agree. `NoFreeServer(reason)` is the one failure the supervisor tolerates: the slot
stays pending and reconcile fills it when a server joins (`MissingSlots`). Silent past
`heartbeatTimeoutSec` = unroutable but kept; past `reassignAfterSec` = dead, respawned elsewhere
through `claimReplacing` (release the dead holder and claim the replacement in ONE store
transaction, rolled back when no server qualifies).
With no free server the dead server keeps its assignment and its node row, so if it returns
first its join process still runs the node at the same epoch and reconcile adopts it with no restart; a
drained or removed holder's node goes pending instead. A partial or cancelled spawn rolls back
the nodes it started (`spawnAll`, `guaranteeCase`). The server holding an assignment is the
truth for a live node's address and token: reconcile adopts through `QuackBackend.located` and
rewrites a node row that still names the previous server (a manager that died between the claim
and the node row write).
Join approval (Liquibase `0041`): a new server row starts unapproved and the claim query skips
it (`AND s.approved`), so a pending server never receives an assignment or its credentials. The
heartbeat handler resolves the client address from the TCP peer (`X-Forwarded-For` only when the
peer is in `QOD_FLEET_TRUSTED_PROXIES`, walked from the right; unknown never matches) and
auto-approves when it is in `QOD_FLEET_AUTO_APPROVE` (default `0.0.0.0/0,::/0`, boot WARNs while
open; empty = none). Pending servers are re-judged every heartbeat, approved ones never; an
accepted re-address (drained server) resets approval and is judged again. Approval is bound to the
source it was granted from (`approved_source`, Liquibase `0042`: the auto-approving heartbeat's, or
the latest source on an admin approve): a heartbeat from another source is refused
`409 source_change_refused` with nothing written, unless that source is itself in the list (rebind)
or the server is drained and unassigned (approval reset, judged again). An approved row with no
binding (the 0042 upgrade leaves every earlier server unbound; not backfilled, since the advertised
host is self-reported and differs from the source behind a proxy or NAT) binds only to a known
source inside the list; from anywhere else it is refused `409 approval_unbound` with nothing
written, unless drained and unassigned (approval reset, judged again): drain it, approve it
once it shows as pending, then undrain it.
Admin approve refuses `409 source_unknown` while the server's latest heartbeat has no known source
(`ApproveResult.SourceUnknown`), so an approval never starts unbound. The drain window is closed: no re-address while the row still holds
an assignment (drain flips unschedulable, then releases), and the heartbeat never returns an
assignment to an unapproved row or to a source the row is not bound to. `qod fleet approve` /
`POST /api/fleet/server/approve` approves by hand (recording the approving user in `approved_by`); `remove` forgets approval and also accepts a
live pending server. Existing rows were backfilled `approved_by = 'upgrade'`. Design:
docs/superpowers/specs/2026-09-26-fleet-join-approval-design.md.
A known name reporting a new address is refused unless drained and unassigned (shared-token takeover guard).
`QOD_FLEET_EPHEMERAL=local` runs maintenance and merge nodes on the manager host instead of a
fleet server. Manager-to-node is plain HTTP: fleet mode needs a private network.
Containers: `docker/fleet-worker` is the server image (`qod fleet join` + DuckDB; requires
`QOD_FLEET_NAME` and `QOD_FLEET_ADVERTISE_HOST`, publishes the node port under the same number),
`host_capacity` reports cgroup limits, and `QOD_FLEET_NODE_PG_HOST` / `_PORT` rewrite the managed
Postgres address in assignments only (`NodePgAddress`). `docker-compose.fleet.yml` layers fleet mode
with two workers on a separate network onto the bundled stack. Design:
docs/superpowers/specs/2026-10-05-fleet-docker-design.md.
Fleet backend design: docs/superpowers/specs/2026-09-25-fleet-backend-design.md.

### Manager module SPI (hosted-service plug-in)

`ai.starlake.quack.spi` defines `ManagerModule`: jars on the classpath declaring
`META-INF/services/ai.starlake.quack.spi.ManagerModule` are loaded at boot
(`ModuleLoader` in `ondemand/module/`), get their Liquibase changelog applied
(`qodhosted_*` tables on the control-plane DB), contribute Tapir endpoints /
public path prefixes / static SPA mounts to `ManagerServer`, receive async
`ManagerEvent`s (bounded lossy queues - freshness signals, not ledgers), and can
register HA-leader-gated recurring tasks. Zero-module boot is unchanged OSS
behavior. See docs/superpowers/specs/2026-07-18-manager-module-spi-design.md.

## Configuration

Every scalar in [src/main/resources/application.conf](src/main/resources/application.conf) accepts a `QOD_*` env-var override (or `PROXY_*` for FlightSQL edge keys). Prefer env vars over editing the conf - the conf is bundled into the jar at build time. Defaults: `:20900` REST, `:31338` FlightSQL (TLS on), Postgres `localhost:5432` user `postgres` / `azizam`, admin users `admin@localhost.local` and `admin` with NO built-in password (chosen at the first `qod start` / `qod serve`, or `QOD_ADMIN_PASSWORD` on first boot; `scripts/run-jar.sh` exports `admin` for dev only). `quack-on-demand.defaultMetastore.dataPath` ships with a developer machine's absolute path - override it before running outside that environment.

`QOD_SESSION_JWT_SECRET` needs no pinning: when it is unset, the first boot generates the session secret and stores it in the control-plane database, and every restart and HA replica reads it (see "Session model"; no secret is ever printed). Pin one only when another service must verify the sessions (Starflow). The static API key is gone: a non-empty `QOD_API_KEY`, or `quack-on-demand.apiKey` in an operator's config file, is ignored with a stderr warning naming the replacements (a PAT from `qod auth pat create`, or a `qod login` session), and an empty value passes silently; `qod start` / `qod serve` warn too, naming where it is set, and never pass it to the manager. Right after the retired-setting gate, boot refuses an empty FlightSQL auth chain (`BootPreflight.checkEdgeAuthAvailable`; `AuthenticationService.configuresProvider` mirrors the chains the service builds): with no basic and no bearer provider the FlightSQL edge (which has no enable switch) and the Quack front door would admit no client, since `EdgeHandshake` refuses every connection when the chain is empty. So `QOD_AUTH_DB_ENABLED=false` boots only with a Keycloak or Azure password backend or a bearer provider (`JWT_SECRET_KEY` / `JWT_PUBLIC_KEY_PATH`, `QOD_AUTH_GOOGLE_ENABLED`, `QOD_AUTH_AWS_ENABLED`); system-scope OIDC gates only the admin UI and REST, adds no FlightSQL provider, and is refused at boot as the only provider. Then boot refuses when nothing could ever create a session (`BootPreflight.checkLoginAvailable`, over `AuthenticationService.configuresPasswordProvider`): no password backend (`QOD_AUTH_DB_ENABLED=false` and no Keycloak or Azure password backend) and no system-scope OIDC sign-in, which counts only with `QOD_MGMT_IDENTITY_SOURCE=oidc` and both `QOD_MGMT_OIDC_ISSUER_URL` and `QOD_MGMT_OIDC_CLIENT_ID` set, so a bearer provider alone needs system-scope OIDC too. Both auth refusals read the configuration alone and run at the top of `bootManager`, before it opens any socket (with `QOD_PG_EMBEDDED` the embedded Postgres is already up, and stopped again on the refusal). Automation uses PATs, and `qod admin reset-password` recovers a superuser (`--username <name>` also re-enables a disabled one).

`federation.secretStore` defaults to `dispatch` (route per secret by externalRef prefix). The `aws-sm` / `gcp-sm` / `azure-kv` / `vault` single-backend modes are refused at config load -- their resolvers are stubs that `NotImplementedError` at node spawn. Under `dispatch` mode the stubs stay wired but the runtime error spells out the supported alternatives (`postgres` inline value, `env:` prefix).

## Operator runbook

[plugins/qod/skills/quack-on-demand/SKILL.md](plugins/qod/skills/quack-on-demand/SKILL.md) is the operator runbook: `qod` CLI recipes for tenant/pool/ACL CRUD, typical failure modes. When the user asks operational questions ("how do I create a pool", "why is auth failing"), prefer the patterns there over reinventing them. It is deliberately checkout-free (it must work for users who only installed the CLI or the Claude Code plugin) - never add repo paths, scripts, or sbt invocations to it. The wheel bundles a copy at `cli/src/qod_cli/skills/quack-on-demand/SKILL.md` (served by `qod skill install`); `cli/tests/test_skill_freshness.py` fails until the copy is refreshed after any skill edit.

## Things to avoid

- **Don't disable JVM forking** in build.sbt - see "JVM forking" above.
- **Classify every function and setting at a DuckDB pin bump** (and whenever qod-node registers a
  function or a node starts loading another extension). Nodes refuse an unclassified one to
  non-superusers ("function <name> is not classified"). Run `DUCKDB_CLI=<the node's release duckdb>
  scripts/dev/gen-qod-node-classification.sh` (it rebuilds `native/qod-node/functions.json`,
  `settings.json` and `macros.json` from the host's qod-node build and the release CLI with every
  extension a node loads, then the generated `src/main/resources/authz/function-classes.tsv`,
  `setting-classes.tsv`, `native/qod-node/file-functions.txt`, `admitted-table-functions.txt` and
  `native/qod-node/src/function_classes.inc`), review every `review:` default it prints, change the rule tables in
  `scripts/dev/classify-qod-node.py` where a default is wrong (a setting is `session` only when changing
  it for one session cannot reach credentials, files, I/O endpoints, resources or other sessions), then
  rebuild qod-node. `FunctionClassesSpec` (its fixture case needs `python3`), `FunctionClassesHeaderSpec`
  and `SettingClassesSpec` fail until the tables and the JSON agree; `InitSettingsAllowlistSpec` fails
  when a setting of the tenant initSql allowlist changes class. The classifier makes a built-in
  scalar macro whose body reaches a catalog listing superuser-class (`pg_get_viewdef`: with the
  gateway screens gone it would print every view's SQL); that rule reads scalar macros only, so
  review any new built-in table macro that reads a listing by hand (DuckDB 2.0.0 has none).
- **Add every new RBAC table to the policy-version trigger.** `qod_bump_policy_version()` (Liquibase
  `0050`, extended by `0051` and `0052`) bumps `qodstate_tenant.policy_version` on writes to the
  tables it is attached to, and `qod_bump_every_policy_version()` (`0054`) bumps every tenant on a
  change to the data-path deny set, a row's object-store keys included (`0057`; attach a new source of data folders to that one); a table that changes what a principal may do but is not attached (a new
  grant or policy table, a new tenant ACL column, a new source of data folders) never invalidates
  cached verdicts, so nodes keep allowing for up to the verdict's 60 s. Attach it in the same changeset
  that creates it.
- **A node S3 secret is scoped to a data path and names every key.** DuckDB signs a URL with the
  secret of the longest matching scope, and httpfs fills any key a secret lacks from the session's
  `s3_*` settings. An unscoped or bucket-wide node secret would sign users' URLs with the node's keys
  (qod-node's screen then refuses every URL it shadows), and a missing key lets a session supply it.
  The spawn scripts' `qod_data_s3` / `qod_db_store`, `ObjectStoreSecret` and qod-node's `qod_uc_*`
  name `KEY_ID`, `SECRET`, `SESSION_TOKEN`, `REGION`, `ENDPOINT`, `URL_STYLE`, `USE_SSL`,
  `KMS_KEY_ID`, `URL_COMPATIBILITY_MODE` and `REQUESTER_PAYS` (re-check that list against httpfs's
  `S3AuthResolver::ReadConfig` at every DuckDB pin bump).
- **Don't invoke `scripts/spawn-quack-node.sh` directly** - it's spawned by `LocalQuackBackend` with the right `<port> <manager-marker>` argv, its secrets (node token and node key included) as files in a private `QOD_SECRETS_DIR`, and the env contract (`QOD_NODE_ID` included: it refuses to run without one). Manual invocation will leak ports and confuse the supervisor.
- **Refresh the bundled copies whenever you touch `scripts/` or the operator skill.** The CLI wheel ships its own copies at `cli/src/qod_cli/scripts/spawn-quack-node.{sh,ps1}` and `cli/src/qod_cli/skills/quack-on-demand/SKILL.md`. Nothing generates them: a plain `cp` is the mechanism, and `cli/tests/test_demo.py` / `test_skill_freshness.py` are the only enforcement. Editing a canonical script without refreshing its copy fails those tests in a way that reads like an unrelated pre-existing failure.
- **Never put two statements on one line of SQL fed to the DuckDB CLI** (the demo seeder, the loaders). When a statement fails, the CLI skips the rest of that line, so a failing `INSTALL` would silently skip the `LOAD` after it. A single statement may span several lines. (qod-node splits a node's init SQL on DuckDB's own tokenizer, so this does not bite there.)
- **Don't edit the bundled `application.conf` for local tweaks** - set the `QOD_*` env var instead, or the change vanishes on the next `sbt assembly`.
- **Always tear the manager down with `scripts/stop-jar.sh`, never an IDE/JVM kill** (or a bare `kill` of the java PID). Each node's qod-node lives exactly as long as its `spawn-quack-node.sh` wrapper (it watches the wrapper's process), so an unclean manager exit leaves the wrappers, reparented to PID 1, and their nodes running and holding node ports `21900+`. The next manager adopts a live leftover it has a row for, or stops it before replacing it (`LocalQuackBackend.stopStray`), and skips any other port something listens on (`PortAllocator`), so its own nodes come up; a leftover with no row (a merge or maintenance node, or any node after a NUKE) keeps its memory and port until swept, and a merge node left this way can hold many GB; one bound to another address (`QOD_NODE_BIND`) goes unseen. Recovery: kill the orphans with `scripts/kill-quack-nodes.sh --port <that manager's REST port>` (`--all` is the explicit blunt reset that sweeps every node on the host, every manager's, and the only mode that matches the orphans of a released manager from before the per-manager marker, whose wrappers carry the node token where the marker now goes; `--unmarked` matches only wrappers whose argv ends at the port, which at HEAD means a `qod fleet join` node on this host). `stop-jar.sh` does SIGTERM -> wait -> SIGKILL and reaps its own manager's children, so it never leaves its own orphans; other managers' nodes it deliberately leaves alone (it counts only port-only `--unmarked` wrappers in its note, so an earlier release's orphans go unreported). Stops are per-manager: the wrapper argv and node directory carry the manager's marker `<rest-port>-<boot id>`, and `stop-jar.sh` / `stop-jar.ps1` / `qod stop` / `kill-quack-nodes.sh` only touch the nodes of the manager on their `QOD_ON_DEMAND_PORT`, so stopping an isolated test stack never takes down the nodes of the manager on :20900. Ctrl-C on a foreground `run-jar.sh` (or `qod start`) is safe: both supervise the JVM in its own process group and turn the interrupt into that same teardown.
