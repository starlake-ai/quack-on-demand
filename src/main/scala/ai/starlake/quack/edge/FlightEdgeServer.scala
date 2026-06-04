package ai.starlake.quack.edge

import ai.starlake.quack.edge.auth.{AuthenticatedProfile, AuthenticationService}
import ai.starlake.quack.ondemand.rbac.AuthorizedHandshake
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.flight.*
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator
import org.apache.arrow.memory.RootAllocator

import java.io.File
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.UUID

final class FlightEdgeServer(
    cfg: EdgeConfig,
    router: FlightSqlRouter,
    authService: AuthenticationService,
    // Pre-resolve callback: (tenant, pool) -> tenantDb. Enforces the
    // tenant/pool disabled kill switches. Runs BEFORE authentication so
    // we can hand TenantSelector a fully-qualified target.
    lookupPool: (String, String) => Either[String, String],
    // Phase C: post-authentication authorize callback. Runs once per
    // handshake after the auth chain validated the credentials. It
    // performs the user-scope and pool-access gates, computes the
    // EffectiveSet, and returns an AuthorizedHandshake. The result
    // is pinned onto ConnectionContext so the per-statement ACL gate
    // can read it without further joins.
    authorize: (String, String, String) => Either[String, AuthorizedHandshake]
) extends LazyLogging:

  private val allocator = new RootAllocator()
  private var server: FlightServer = null.asInstanceOf[FlightServer]

  def start(): Unit =
    val producer = new FlightProducerImpl(router, cfg.tenantClaim)
    val location =
      if cfg.tlsEnabled then Location.forGrpcTls(cfg.host, cfg.port)
      else Location.forGrpcInsecure(cfg.host, cfg.port)

    val builder = FlightServer
      .builder(allocator, location, producer)
      .headerAuthenticator(headerAuth)

    if cfg.tlsEnabled then
      ensureCertFiles(cfg.tlsCertChain, cfg.tlsPrivateKey)
      builder.useTls(new File(cfg.tlsCertChain), new File(cfg.tlsPrivateKey))

    server = builder.build()
    server.start()
    val authMode =
      if authService.hasProviders then "providers configured"
      else "OPEN (trust-the-client; configure auth.* in application.conf for prod)"
    logger.info(
      s"FlightSQL edge listening on ${cfg.host}:${cfg.port} (TLS=${cfg.tlsEnabled}, auth: $authMode)"
    )

  /** Auto-generate a self-signed cert/key pair at the configured paths when
    * either file is missing. Dev convenience so `useEncryption=true` works
    * out of the box; production deploys should supply CA-signed material.
    * Uses the system `openssl` (no JVM crypto deps, no JPMS reflection
    * headaches under Java 17+). CN + SAN are bound to `localhost`. */
  private def ensureCertFiles(certPath: String, keyPath: String): Unit =
    val certFile = Path.of(certPath)
    val keyFile  = Path.of(keyPath)
    if Files.exists(certFile) && Files.exists(keyFile) then
      logger.info(s"TLS: reusing existing cert at $certPath")
    else
      Option(certFile.getParent).foreach(Files.createDirectories(_))
      Option(keyFile.getParent).foreach(Files.createDirectories(_))
      val cmd = List(
        "openssl", "req", "-x509",
        "-newkey", "rsa:2048", "-nodes", "-days", "3650",
        "-keyout", keyPath, "-out", certPath,
        "-subj", "/CN=localhost",
        "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"
      )
      val pb = new java.lang.ProcessBuilder(cmd*).redirectErrorStream(true)
      val proc = pb.start()
      val output = new String(proc.getInputStream.readAllBytes())
      val rc = proc.waitFor()
      if rc != 0 then
        throw new RuntimeException(
          s"openssl failed to generate cert (rc=$rc): $output. " +
          "Install openssl or supply your own cert/key at the configured paths."
        )
      val fp = sha256Fingerprint(Files.readAllBytes(certFile))
      logger.warn(
        s"TLS: generated self-signed cert (CN=localhost) at $certPath / $keyPath. " +
        s"SHA-256 fingerprint: $fp. " +
        "JDBC clients: jdbc:arrow-flight-sql://localhost:PORT?useEncryption=true&disableCertificateVerification=true " +
        "(or import the cert into a trust store for verified TLS)."
      )

  private def sha256Fingerprint(certBytes: Array[Byte]): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(certBytes).map(b => f"$b%02X").mkString(":")

  /** Resolve credentials to a peerIdentity and bind the pool context.
    *
    * FlightSQL session model: on the first handshake (Basic or external Bearer)
    * we return a `Bearer <peerId>` token in the response headers. The client
    * then sends that peerId-Bearer on every subsequent RPC, and we look the
    * peerId up in the ConnectionContext to recover (tenant, pool, user).
    *
    * Credential validation:
    *   - If `authService.hasProviders` is true, every fresh handshake is
    *     validated against the configured chain (Database + ROPC for Basic,
    *     external JWT + OIDC for Bearer). On success we mint a session peerId
    *     and bind it. The session peerId acts as an opaque session cookie.
    *   - Otherwise the manager falls back to the v1 "trust the client" path
    *     (username from Basic header, no credential check). Useful for local
    *     dev; flagged with a startup warning.
    */
  private val headerAuth: CallHeaderAuthenticator =
    new CallHeaderAuthenticator:
      override def authenticate(headers: CallHeaders): CallHeaderAuthenticator.AuthResult =
        val authHeader = Option(headers.get("Authorization"))
        val bearer = authHeader.filter(_.startsWith("Bearer ")).map(_.stripPrefix("Bearer "))
        val basic = authHeader
          .filter(_.startsWith("Basic "))
          .map(_.stripPrefix("Basic "))
          .map(b64 => new String(java.util.Base64.getDecoder.decode(b64)))
        val basicPair = basic.flatMap { creds =>
          creds.split(":", 2) match
            case Array(u, p) => Some((u, p))
            case _           => None
        }
        val basicUsername = basicPair.map(_._1)
        // Flight call headers carrying the target tenant + pool. These
        // names match how Arrow Flight JDBC drivers serialize arbitrary
        // connection-string parameters into gRPC metadata -- e.g.
        // `jdbc:arrow-flight-sql://host:port/?tenant=tpch&pool=sales`
        // arrives here as headers named `tenant`, `pool`. The owning
        // tenant-db is resolved server-side via `lookupPool`
        // (pool names are unique per tenant).
        val poolHdr   = Option(headers.get("pool"))
        val tenantHdr = Option(headers.get("tenant"))

        // Fast path: client is presenting a Bearer that's an already-known
        // session peerId from a prior handshake. Skip TenantSelector and
        // provider chain entirely.
        bearer.flatMap(b => ConnectionContext.poolFor(b).map(_ => b)) match
          case Some(knownPeerId) =>
            authResult(knownPeerId)
          case None =>
            handshake(bearer, basicPair, basicUsername, poolHdr, tenantHdr)

      /** Mint a new session peerId. Runs the configured auth chain when one
        * exists; otherwise trusts the client (legacy v1). On success we resolve
        * tenant/pool via TenantSelector and bind the connection context. */
      private def handshake(
          bearer: Option[String],
          basicPair: Option[(String, String)],
          basicUsername: Option[String],
          poolHdr: Option[String],
          tenantHdr: Option[String]
      ): CallHeaderAuthenticator.AuthResult =
        // Pre-resolve the target (tenant, tenantDb, pool) from the headers
        // + basic username BEFORE authenticating. The Basic provider chain
        // needs (tenant, pool) to look up the right qodstate_user row -- a
        // user with the same name in two different (tenant, pool) scopes is
        // a different principal. The bearer body is decoded without
        // signature verification here; cryptographic verification still
        // happens inside authenticateBearer below.
        val hdrs = poolHdr.map("pool" -> _).toMap ++
                   tenantHdr.map("tenant" -> _).toMap
        val preResolved: Either[String, Resolved] = TenantSelector.resolve(
          bearer         = bearer,
          headers        = hdrs,
          username       = basicUsername,
          tenantClaim    = cfg.tenantClaim,
          lookupPool     = lookupPool
        )

        val validation: Either[String, Option[AuthenticatedProfile]] =
          if !authService.hasProviders then
            // No backends configured - keep v1 trust-the-client.
            Right(None)
          else
            (bearer, basicPair) match
              case (Some(token), _) =>
                // Bearer auth verifies the JWT signature; tenant/pool
                // scoping is enforced later by the (tenant, pool) headers
                // on top of the verified claims.
                authService.authenticateBearer(token).map(Some(_))
              case (None, Some((_, p))) =>
                preResolved match
                  case Right(r) =>
                    authService.authenticateBasic(
                      Some(r.poolKey.tenant), r.user, p
                    ).map(Some(_))
                  case Left(err) =>
                    Left(s"missing tenant scope for Basic auth: $err")
              case _ =>
                Left("no credentials presented")

        validation match
          case Left(err) =>
            throw CallStatus.UNAUTHENTICATED.withDescription(err).toRuntimeException()
          case Right(profileOpt) =>
            // Prefer the authenticated username when a provider validated;
            // fall back to whatever the Basic header carried (legacy path).
            val resolvedUsername = profileOpt.map(_.username).orElse(basicUsername)
            // Bearer present but unknown to ConnectionContext AND no provider
            // claimed it AND no Basic credentials → stale session token from a
            // prior manager restart. Emit a clearer error than TenantSelector
            // would (which would otherwise complain about a missing username).
            if bearer.isDefined && profileOpt.isEmpty && resolvedUsername.isEmpty then
              throw CallStatus.UNAUTHENTICATED
                .withDescription("session expired; please reconnect with username/password")
                .toRuntimeException()

            // Re-run TenantSelector with the (possibly enriched) username
            // from the validated profile -- the pre-resolve above used the
            // raw Basic username; for Bearer auth the JWT's `sub` claim is
            // only available after authenticateBearer.
            TenantSelector.resolve(
              bearer         = bearer,
              headers        = hdrs,
              username       = resolvedUsername,
              tenantClaim    = cfg.tenantClaim,
              lookupPool     = lookupPool
            ) match
              case Right(resolved) =>
                // Phase C handshake gates 4 + 5: user-scope + pool-access.
                // EffectiveSet is computed once here and pinned to
                // ConnectionContext; the per-statement validator reads it
                // without re-querying Postgres.
                authorize(resolved.poolKey.tenant, resolved.poolKey.pool, resolved.user) match
                  case Left(err) =>
                    // Arrow Flight's CallStatus has UNAUTHENTICATED but no
                    // dedicated PERMISSION_DENIED until later versions; reuse
                    // UNAUTHENTICATED with a "permission denied: " prefix so
                    // FlightSQL clients can still surface the reason text.
                    throw CallStatus.UNAUTHENTICATED
                      .withDescription(s"permission denied: $err")
                      .toRuntimeException()
                  case Right(auth) =>
                    val peerId = UUID.randomUUID().toString
                    val connId = UUID.randomUUID().toString
                    val _      = profileOpt // role/groups from JWT no longer threaded
                    ConnectionContext.bind(
                      peer         = peerId,
                      key          = resolved.poolKey,
                      connectionId = connId,
                      user         = resolved.user,
                      effectiveSet = Some(auth.effectiveSet),
                      ttlSec       = cfg.sessionTtlSec
                    )
                    authResult(peerId)
              case Left(err) =>
                throw CallStatus.UNAUTHENTICATED.withDescription(err).toRuntimeException()

  /** Build an AuthResult that emits the peerId back as a Bearer token so the
    * client can use it on subsequent calls (this is what makes Basic+token
    * auth in FlightSQL work). */
  private def authResult(peerId: String): CallHeaderAuthenticator.AuthResult =
    new CallHeaderAuthenticator.AuthResult:
      override def getPeerIdentity(): String = peerId
      override def appendToOutgoingHeaders(outgoing: CallHeaders): Unit =
        outgoing.insert("authorization", s"Bearer $peerId")

  def stop(): Unit =
    if server != null then
      server.close()
      server = null.asInstanceOf[FlightServer]