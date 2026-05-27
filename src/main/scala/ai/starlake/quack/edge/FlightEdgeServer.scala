package ai.starlake.quack.edge

import ai.starlake.quack.edge.auth.{AuthenticatedProfile, AuthenticationService}
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
    authService: AuthenticationService
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
        val poolHdr = Option(headers.get("X-Pool"))

        // Fast path: client is presenting a Bearer that's an already-known
        // session peerId from a prior handshake. Skip TenantSelector and
        // provider chain entirely.
        bearer.flatMap(b => ConnectionContext.poolFor(b).map(_ => b)) match
          case Some(knownPeerId) =>
            authResult(knownPeerId)
          case None =>
            handshake(bearer, basicPair, basicUsername, poolHdr)

      /** Mint a new session peerId. Runs the configured auth chain when one
        * exists; otherwise trusts the client (legacy v1). On success we resolve
        * tenant/pool via TenantSelector and bind the connection context. */
      private def handshake(
          bearer: Option[String],
          basicPair: Option[(String, String)],
          basicUsername: Option[String],
          poolHdr: Option[String]
      ): CallHeaderAuthenticator.AuthResult =
        val validation: Either[String, Option[AuthenticatedProfile]] =
          if !authService.hasProviders then
            // No backends configured — keep v1 trust-the-client.
            Right(None)
          else
            // Real auth chain. Bearer validated through JWT/OIDC providers;
            // Basic through DB + ROPC providers.
            (bearer, basicPair) match
              case (Some(token), _) =>
                authService.authenticateBearer(token).map(Some(_))
              case (None, Some((u, p))) =>
                authService.authenticateBasic(u, p).map(Some(_))
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

            TenantSelector.resolve(
              bearer        = bearer,
              headers       = poolHdr.map(p => "X-Pool" -> p).toMap,
              username      = resolvedUsername,
              tenantClaim   = cfg.tenantClaim,
              defaultTenant = cfg.defaultTenant,
              defaultPool   = cfg.defaultPool
            ) match
              case Right(resolved) =>
                val peerId = UUID.randomUUID().toString
                val connId = UUID.randomUUID().toString
                // Carry the authenticated profile's groups + role into the
                // ConnectionContext so the ACL validator can match
                // group:<g> / role:<r> principals, not just user:<name>.
                val groups = profileOpt.map(_.groups).getOrElse(Set.empty)
                val role   = profileOpt.map(_.role).getOrElse("")
                ConnectionContext.bind(peerId, resolved.poolKey, connId, resolved.user, groups, role, cfg.sessionTtlSec)
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