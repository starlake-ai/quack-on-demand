// src/test/scala/ai/starlake/quack/security/FlightEdgeHarness.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge._
import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.sql.StatementValidator
import ai.starlake.quack.model.{NodeSpec, RunningNode}
import ai.starlake.quack.observability.metrics.StatementInstruments
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.flight.{FlightClient, Location}
import org.apache.arrow.memory.RootAllocator

import java.nio.file.Files
import java.time.Instant

/** Test harness: boots the real [[FlightEdgeServer]] on an ephemeral port, against the in-memory
  * store seeded by [[SecurityFixtures]]. Auto-close by calling [[Harness.shutdown]].
  *
  * The harness exercises the full handshake gate (auth chain, tenant/pool resolution, authorize
  * callback) without running real statement execution -- the stub [[QuackHttpAdapter]] is never
  * reached during a plain connect-and-authenticate round-trip.
  */
object FlightEdgeHarness:

  // ------------------------------------------------------------------
  // Stub QuackBackend: no real child processes. Mirrors the pattern
  // used in ManagerServerHarness and FlightSqlRouterSpec.
  // ------------------------------------------------------------------
  private def stubBackend: QuackBackend = new QuackBackend:
    def start(s: NodeSpec): IO[RunningNode] =
      IO.pure(
        RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          21000,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
      )
    def stop(id: String)    = IO.unit
    def isAlive(id: String) = true
    def discoverExisting()  = IO.pure(Nil)
    def cleanup()           = IO.unit

  // ------------------------------------------------------------------
  // Stub QuackHttpAdapter: never called during handshake-only tests.
  // Returns a static ok response so the router has a valid path when
  // statement tests are eventually added.
  // ------------------------------------------------------------------
  private def stubAdapter(tracker: NodeLoadTracker): QuackHttpAdapter =
    val client = new QuackHttpClient(
      // Use the test-shared allocator (never closed) so we don't fight
      // over allocator lifetimes with the harness's own RootAllocator.
      ai.starlake.quack.edge.adapter.TestArrow.sharedAllocator,
      nativeClient   = false,
      nodeDisableSsl = true
    ):
      override def query(
          endpoint: String,
          token:    String,
          sql:      String,
          session:  Option[String]
      ): IO[QuackResponse] =
        IO.pure(TestArrow.okResponse())
    new QuackHttpAdapter(client, tracker)

  // ------------------------------------------------------------------
  // Find a free port on loopback by briefly binding a ServerSocket to
  // port 0, recording the OS-assigned port, then releasing it.
  // FlightServer doesn't expose port-0 binding natively, so this
  // find-then-bind dance is the standard approach for test code.
  // ------------------------------------------------------------------
  private def ephemeralPort(): Int =
    val ss = new java.net.ServerSocket(0)
    try ss.getLocalPort
    finally ss.close()

  // ------------------------------------------------------------------
  // Public API
  // ------------------------------------------------------------------

  /** A running [[FlightEdgeServer]] wrapped in a test-friendly handle.
    *
    * @param host
    *   Always `127.0.0.1`.
    * @param port
    *   The OS-assigned port the server is listening on.
    * @param allocator
    *   The [[RootAllocator]] owned by this harness. Close it via
    *   [[shutdown]] -- do NOT close it independently.
    * @param shutdown
    *   Stops the server and releases the allocator. Idempotent.
    */
  final case class Harness(
      host:      String,
      port:      Int,
      allocator: RootAllocator,
      shutdown:  () => Unit
  ):
    /** Build a fresh [[FlightClient]] pointed at this harness.
      *
      * The caller is responsible for closing the returned client.
      */
    def newClient(): FlightClient =
      val loc = Location.forGrpcInsecure(host, port)
      FlightClient.builder(allocator, loc).build()

  /** Boot a [[FlightEdgeServer]] on an ephemeral loopback port.
    *
    * @param store
    *   Seeded in-memory control plane store (use [[SecurityFixtures.freshStore]]).
    * @param enableProviders
    *   When `true` (default), the [[InMemoryAuthService.Service]] validates
    *   credentials against bcrypt hashes in the store. When `false`, the
    *   service reports `hasProviders = false` and lets the Flight server
    *   fall through to the "trust-the-client" legacy path.
    * @param tls
    *   When `true`, a self-signed cert is auto-generated into a unique
    *   per-invocation tempdir via [[FlightEdgeServer.ensureCertFiles]].
    *   When `false` (default), the server listens on plain gRPC.
    */
  def boot(
      store:           InMemoryControlPlaneStore,
      enableProviders: Boolean = true,
      tls:             Boolean = false
  ): Harness =
    val port    = ephemeralPort()
    val host    = "127.0.0.1"

    // TLS cert paths: unique tempdir per call so concurrent harnesses
    // don't race on cert file writes.
    val certDir  = Files.createTempDirectory("qod-edge-harness-tls")
    val certPath = certDir.resolve("server-cert.pem").toString
    val keyPath  = certDir.resolve("server-key.pem").toString

    val cfg = EdgeConfig(
      host          = host,
      port          = port,
      tlsEnabled    = tls,
      tlsCertChain  = certPath,
      tlsPrivateKey = keyPath,
      sessionTtlSec = 3600L
    )

    val tracker  = new NodeLoadTracker
    val backend  = stubBackend
    val sup      = new PoolSupervisor(backend, tracker, store)
    sup.restore()

    val adapter  = stubAdapter(tracker)
    val sessions = new SessionRegistry
    val history  = new StatementHistoryStore()
    val si       = StatementInstruments.noop

    val router = new FlightSqlRouter(
      supervisor     = sup,
      sessions       = sessions,
      tracker        = tracker,
      adapter        = adapter,
      validator      = StatementValidator.allowAll,
      history        = history,
      stmtInstruments = si
    )

    val authSvc = new InMemoryAuthService.Service(store, providersEnabled = enableProviders)

    val lookupPool: (String, String) => Either[String, String] =
      (tenant, pool) =>
        sup.findPoolKeyByTenantAndPoolName(tenant, pool) match
          case None      => Left(s"pool '$pool' not found in tenant '$tenant'")
          case Some(key) =>
            sup.getTenant(key.tenant) match
              case Some(t) if t.disabled =>
                Left(s"tenant '${key.tenant}' is disabled")
              case _ =>
                sup.get(key) match
                  case Some(s) if s.disabled =>
                    Left(s"pool '${key.pool}' in tenant '${key.tenant}' is disabled")
                  case _ =>
                    Right(key.tenantDb)

    val resolveTenant =
      (raw: String) =>
        if ai.starlake.quack.model.Names.looksLikeTenantId(raw) then sup.getTenantById(raw)
        else sup.getTenant(raw)

    val authorize =
      (tenant: String, pool: String, username: String, jwtRoles: Set[String], jwtGroups: Set[String]) =>
        sup.authorizeHandshake(tenant, pool, username, jwtRoles, jwtGroups)

    val allocator = new RootAllocator()

    val srv = new FlightEdgeServer(
      cfg,
      router,
      authSvc,
      lookupPool,
      resolveTenant,
      authorize
    )
    srv.start()

    Harness(
      host      = host,
      port      = port,
      allocator = allocator,
      shutdown  = () => {
        srv.stop()
        allocator.close()
      }
    )
