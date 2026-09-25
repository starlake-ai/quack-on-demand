package ai.starlake.quack.edge

import ai.starlake.quack.edge.auth.AuthenticationService
import ai.starlake.quack.model.{PoolKey, Tenant}
import ai.starlake.quack.ondemand.rbac.AuthorizedHandshake
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.flight.*
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator
import org.apache.arrow.memory.RootAllocator

import java.io.File
import java.util.UUID
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}

final class FlightEdgeServer(
    cfg: EdgeConfig,
    router: FlightSqlRouter,
    authService: AuthenticationService,
    // Pre-resolve callback: (tenant, pool) -> tenantDb. Enforces the
    // tenant/pool disabled kill switches. Runs BEFORE authentication so
    // we can hand TenantSelector a fully-qualified target.
    lookupPool: (String, String) => Either[String, String],
    // Accept either form of the FlightSQL `tenant` connection param --
    // the surrogate id (`t-<8 hex>`) or the display name -- and return the
    // matching tenant. Used to normalize the wire value to a display name
    // for the downstream lookup/authorize callbacks (which work in
    // display-name space) AND to recover the id for the Basic auth chain
    // (which queries `qodstate_user.tenant`, which stores the id).
    resolveTenant: String => Option[Tenant],
    // Post-authentication authorize callback. Runs once per handshake
    // after the auth chain validated the credentials. It performs the
    // user-scope and pool-access gates, computes the EffectiveSet
    // (union-merging any JWT-claimed role / group names), and returns
    // an AuthorizedHandshake. The result is pinned onto
    // ConnectionContext so the per-statement ACL gate can read it
    // without further joins. See EdgeHandshake for the argument contract.
    authorize: (String, String, String, Set[String], Set[String], Boolean) => Either[
      String,
      AuthorizedHandshake
    ],
    // Branch targeting (Epic 1): (tenant, parent tenantDb, branch name) -> the branch's pool
    // key. Consulted AFTER `authorize` succeeded against the parent pool named by the client, so
    // a branch session carries exactly the parent pool's authorization and EffectiveSet. Left =
    // unknown / not live, surfaced as UNAUTHENTICATED; the default refuses every branch header.
    lookupBranch: (String, String, String) => Either[String, PoolKey] = (_, _, b) =>
      Left(s"branch '$b' not found (branching unavailable)")
) extends LazyLogging:

  private val allocator            = new RootAllocator()
  private var server: FlightServer = null.asInstanceOf[FlightServer]

  /** The transport-agnostic handshake shared with the Quack front door. */
  private val handshake = new EdgeHandshake(authService, lookupPool, resolveTenant, authorize)

  /** Sweeps expired [[ConnectionContext]] entries so a departed client's session state (the context
    * entry and the router's session row) does not linger until the peer id happens to be presented
    * again, which for a gone client is never.
    */
  private var sweeper: ScheduledExecutorService = null.asInstanceOf[ScheduledExecutorService]

  def start(): Unit =
    val producer = new FlightProducerImpl(router)
    ConnectionContext.onEvict(router.sessions.close)
    sweeper = Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, "flight-session-sweeper"); t.setDaemon(true); t
    }
    sweeper.scheduleAtFixedRate(
      () =>
        try ConnectionContext.evictExpired()
        catch case t: Throwable => logger.warn(s"Flight session sweep failed: ${t.getMessage}"),
      FlightEdgeServer.SweepSec,
      FlightEdgeServer.SweepSec,
      TimeUnit.SECONDS
    )
    val location =
      if cfg.tlsEnabled then Location.forGrpcTls(cfg.host, cfg.port)
      else Location.forGrpcInsecure(cfg.host, cfg.port)

    val builder = FlightServer
      .builder(allocator, location, producer)
      .headerAuthenticator(headerAuth)

    if cfg.tlsEnabled then
      CertGen.ensureCertFiles(cfg.tlsCertChain, cfg.tlsPrivateKey)
      builder.useTls(new File(cfg.tlsCertChain), new File(cfg.tlsPrivateKey))

    server = builder.build()
    server.start()
    val authMode =
      if authService.hasProviders then "providers configured"
      else "OPEN (trust-the-client; configure auth.* in application.conf for prod)"
    logger.info(
      s"FlightSQL edge listening on ${cfg.host}:${cfg.port} (TLS=${cfg.tlsEnabled}, auth: $authMode)"
    )

  /** Resolve credentials to a peerIdentity and bind the pool context.
    *
    * FlightSQL session model: on the first handshake (Basic or external Bearer) we return a
    * `Bearer <peerId>` token in the response headers. The client then sends that peerId-Bearer on
    * every subsequent RPC, and we look the peerId up in the ConnectionContext to recover (tenant,
    * pool, user).
    *
    * Credential validation lives in [[EdgeHandshake]]: when providers are configured every fresh
    * handshake is validated against the chain; otherwise the manager falls back to the v1 "trust
    * the client" path (username from Basic header, no credential check).
    */
  private val headerAuth: CallHeaderAuthenticator =
    new CallHeaderAuthenticator:
      override def authenticate(headers: CallHeaders): CallHeaderAuthenticator.AuthResult =
        // Read `Authorization` first; fall back to `x-qod-authorization` for
        // clients whose driver / proxy strips the standard header but passes
        // arbitrary `x-*` headers through. Confirmed needed by the Apache
        // Arrow Flight SQL ODBC build that the current Power BI connector
        // wraps: it forwards `tenant` / `pool` (which are arbitrary
        // passthrough keys) but discards `Authorization`. The two header
        // names are accepted identically; the value format is unchanged
        // (`Basic <base64>` or `Bearer <token>`).
        val authHeader = Option(headers.get("Authorization"))
          .orElse(Option(headers.get("x-qod-authorization")))
        val bearer = authHeader.filter(_.startsWith("Bearer ")).map(_.stripPrefix("Bearer "))
        val basic  = authHeader
          .filter(_.startsWith("Basic "))
          .map(_.stripPrefix("Basic "))
          .map(b64 => new String(java.util.Base64.getDecoder.decode(b64)))
        val basicPair = basic.flatMap { creds =>
          creds.split(":", 2) match
            case Array(u, p) => Some((u, p))
            case _           => None
        }
        val basicUsername = basicPair.map(_._1)
        // Diagnostic: per-RPC header summary at DEBUG. Enables operators to
        // see exactly what credentials each Flight call carries when a driver
        // (Power BI ODBC, etc.) misbehaves. Doesn't log token bytes - just
        // shapes / tenant / pool - so it's safe to leave on in prod.
        if logger.underlying.isDebugEnabled then
          val shape = (bearer.isDefined, basicPair.isDefined) match
            case (true, _)      => s"bearer(prefix=${bearer.get.take(4)}…)"
            case (false, true)  => s"basic(user=${basicUsername.getOrElse("?")})"
            case (false, false) =>
              if authHeader.isDefined then s"authHeader(unparsed='${authHeader.get.take(8)}…')"
              else "none"
          val allKeys = scala.jdk.CollectionConverters
            .SetHasAsScala(headers.keys())
            .asScala
            .toList
            .sorted
            .mkString(",")
          logger.debug(
            s"headerAuth: creds=$shape " +
              s"tenant=${Option(headers.get("tenant")).getOrElse("-")} " +
              s"pool=${Option(headers.get("pool")).getOrElse("-")} " +
              s"superuser=${Option(headers.get("superuser")).orElse(Option(headers.get("x-qod-superuser"))).getOrElse("-")} " +
              s"keys=[$allKeys]"
          )
        // Flight call headers carrying the target tenant + pool. These
        // names match how Arrow Flight JDBC drivers serialize arbitrary
        // connection-string parameters into gRPC metadata -- e.g.
        // `jdbc:arrow-flight-sql://host:port/?tenant=tpch&pool=sales`
        // arrives here as headers named `tenant`, `pool`. The owning
        // tenant-db is resolved server-side via `lookupPool`
        // (pool names are unique per tenant).
        val poolHdr   = Option(headers.get("pool"))
        val tenantHdr = Option(headers.get("tenant"))
        // `branch=<name>` (or `x-qod-branch`) targets a writable branch of the pool's tenant-db
        // (Epic 1): authorization still runs against the named parent pool; only the routing
        // target is swapped for the branch's own pool after the gates passed.
        val branchHdr = Option(headers.get("branch"))
          .orElse(Option(headers.get("x-qod-branch")))
          .map(_.trim)
          .filter(_.nonEmpty)
        // `superuser=true` picks the system realm regardless of the `tenant`
        // header. The tenant/pool headers still drive query routing -- a system
        // superuser can target a tenant's pool while authenticating against the
        // global realm. Anything other than the literal string "true"
        // (case-insensitive) is treated as false.
        //
        // Read `superuser` first; fall back to `x-qod-superuser` (mirrors the
        // Authorization / x-qod-authorization pair above). Arrow Flight JDBC
        // forwards the bare `?superuser=true` URL param as header `superuser`;
        // some ODBC stacks (Power BI / Excel via the Flight SQL ODBC driver)
        // namespace custom connection properties, so the `x-qod-` form is
        // accepted too.
        val superuserHdr = Option(headers.get("superuser"))
          .orElse(Option(headers.get("x-qod-superuser")))
          .exists(_.equalsIgnoreCase("true"))

        // Fast path: client is presenting a Bearer that's an already-known
        // session peerId from a prior handshake. Skip TenantSelector and
        // provider chain entirely.
        bearer.flatMap(b => ConnectionContext.poolFor(b).map(_ => b)) match
          case Some(knownPeerId) =>
            authResult(knownPeerId)
          case None =>
            // Anonymous peer admit-path: when there is NO Authorization
            // header at all, mint an anonymous peerId without binding any
            // ConnectionContext. Some FlightSQL clients (the Apache Arrow
            // Flight SQL ODBC driver, used by Power BI / Excel) issue
            // their connect-time GetSqlInfo + GetXdbcTypeInfo BEFORE they
            // replay the session bearer from the handshake. The server's
            // identification metadata is not tenant data, so admitting an
            // anonymous peer here lets the SQLGetInfo cache load.
            //
            // Producer methods that need tenant context look up
            // `ConnectionContext.poolFor(peer)` and reject the anonymous
            // peer downstream with UNAUTHENTICATED (see
            // FlightProducerImpl.runStatement etc.). Only the static
            // GetSqlInfo / GetXdbcTypeInfo paths are reachable anonymously.
            //
            // A malformed Authorization header (e.g. `Bearer <stale id>`,
            // or `Basic <unparseable>`) is NOT silently anonymized: it
            // falls through to handshake so the client gets a precise
            // credential error instead of a downstream "no pool bound"
            // surprise.
            if authHeader.isEmpty then authResult(s"anonymous-${UUID.randomUUID()}")
            else
              handshake.authenticate(bearer, basicPair, poolHdr, tenantHdr, superuserHdr) match
                case Left(HandshakeFailure.Unauthenticated(msg)) =>
                  throw CallStatus.UNAUTHENTICATED.withDescription(msg).toRuntimeException()
                case Left(HandshakeFailure.Unauthorized(msg)) =>
                  // R12: the principal authenticated but isn't authorized for
                  // this (tenant, pool). Surface UNAUTHORIZED (Arrow Flight's
                  // PERMISSION_DENIED) so clients can tell "wrong password"
                  // from "no access to this pool" without parsing strings.
                  throw CallStatus.UNAUTHORIZED.withDescription(msg).toRuntimeException()
                case Right(bound) =>
                  val peerId    = UUID.randomUUID().toString
                  val connId    = UUID.randomUUID().toString
                  val targetKey = branchHdr match
                    case None         => bound.poolKey
                    case Some(branch) =>
                      lookupBranch(bound.poolKey.tenant, bound.poolKey.tenantDb, branch) match
                        case Right(key) => key
                        case Left(err)  =>
                          throw CallStatus.UNAUTHENTICATED
                            .withDescription(s"branch_not_found: $err")
                            .toRuntimeException()
                  ConnectionContext.bind(
                    peer = peerId,
                    key = targetKey,
                    connectionId = connId,
                    user = bound.user,
                    effectiveSet = Some(bound.effectiveSet),
                    ttlSec = cfg.sessionTtlSec
                  )
                  authResult(peerId)

  /** Build an AuthResult that emits the peerId back as a Bearer token so the client can use it on
    * subsequent calls (this is what makes Basic+token auth in FlightSQL work).
    */
  private def authResult(peerId: String): CallHeaderAuthenticator.AuthResult =
    new CallHeaderAuthenticator.AuthResult:
      override def getPeerIdentity(): String                            = peerId
      override def appendToOutgoingHeaders(outgoing: CallHeaders): Unit =
        outgoing.insert("authorization", s"Bearer $peerId")

  def stop(): Unit =
    if sweeper != null then
      sweeper.shutdownNow()
      sweeper = null.asInstanceOf[ScheduledExecutorService]
    if server != null then
      server.close()
      server = null.asInstanceOf[FlightServer]

object FlightEdgeServer:
  /** Seconds between sweeps of expired Flight sessions. */
  val SweepSec: Long = 60L
