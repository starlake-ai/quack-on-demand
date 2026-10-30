// src/test/scala/ai/starlake/quack/security/FlightEdgeHarness.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge._
import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.auth.AuthenticationService
import ai.starlake.quack.edge.cls.{ColumnCatalog, ColumnPolicyRewriter}
import ai.starlake.quack.edge.policy.ProtectedWriteGuard
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.observability.metrics.StatementInstruments
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.OpaPoolAccess
import ai.starlake.quack.ondemand.telemetry.EventJournal
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.flight.{FlightClient, Location}
import org.apache.arrow.memory.RootAllocator

import java.nio.file.Files

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
  private def stubBackend: QuackBackend = StubQuackBackend.noop()

  // ------------------------------------------------------------------
  // Stub QuackHttpAdapter: never called during handshake-only tests.
  // Returns a static ok response so the router has a valid path when
  // statement tests are eventually added.
  // ------------------------------------------------------------------
  private def stubAdapter(
      tracker: NodeLoadTracker,
      nodeQuery: String => QuackResponse
  ): QuackHttpAdapter =
    val client = new QuackHttpClient(
      // Use the test-shared allocator (never closed) so we don't fight
      // over allocator lifetimes with the harness's own RootAllocator.
      ai.starlake.quack.edge.adapter.TestArrow.sharedAllocator
    ):
      override def query(
          endpoint: String,
          token: String,
          sql: String,
          session: Option[String],
          authorize: () => String
      ): IO[QuackResponse] =
        IO(nodeQuery(sql))
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
    *   The [[RootAllocator]] owned by this harness. Close it via [[shutdown]] -- do NOT close it
    *   independently.
    * @param shutdown
    *   Stops the server and releases the allocator. Idempotent.
    */
  final case class Harness(
      host: String,
      port: Int,
      allocator: RootAllocator,
      shutdown: () => Unit,
      supervisor: PoolSupervisor,
      router: FlightSqlRouter
  ):
    /** Build a fresh [[FlightClient]] pointed at this harness.
      *
      * The caller is responsible for closing the returned client.
      */
    def newClient(): FlightClient =
      val loc = Location.forGrpcInsecure(host, port)
      FlightClient.builder(allocator, loc).build()

  /** Optional statement-pipeline wiring for specs that run statements through the edge. Every
    * default is the harness's historical behavior (no OPA, inert guards, a one-row stub node, no
    * spawned nodes), so callers that only exercise the handshake are unaffected.
    *
    * @param opa
    *   wired into the supervisor through `wireOpa` before the server starts.
    * @param nodeQuery
    *   what the stub node answers for the SQL the router forwards to it.
    * @param spawnNodes
    *   run one reconcile pass after `restore()` so seeded pools get routable (stub) nodes.
    */
  final case class RouterWiring(
      opa: Option[OpaPoolAccess] = None,
      nodeQuery: String => QuackResponse = _ => TestArrow.okResponse(),
      columnPolicyRewriter: ColumnPolicyRewriter = new ColumnPolicyRewriter(
        new ColumnCatalog.MapCatalog(Map.empty)
      ),
      protectedWriteGuard: ProtectedWriteGuard = ProtectedWriteGuard.disabled,
      lockdownFor: PoolSupervisor => PoolKey => Boolean = _ => _ => false,
      deniedBuckets: PoolSupervisor => () => Set[String] = _ => () => Set.empty,
      journal: EventJournal = EventJournal.noop,
      spawnNodes: Boolean = false,
      /** Required, like the router's: a default would let a spec forget it and narrow nothing. */
      metadataFilterRewriter: ai.starlake.quack.edge.meta.MetadataFilterRewriter
  )

  /** Boot a [[FlightEdgeServer]] on an ephemeral loopback port.
    *
    * @param store
    *   Seeded in-memory control plane store (use [[SecurityFixtures.freshStore]]).
    * @param enableProviders
    *   When `true` (default), the [[InMemoryAuthService.Service]] validates credentials against
    *   bcrypt hashes in the store. When `false`, the service reports `hasProviders = false`, and
    *   the edge refuses every handshake.
    * @param tls
    *   When `true`, a self-signed cert is auto-generated into a unique per-invocation tempdir via
    *   [[FlightEdgeServer.ensureCertFiles]]. When `false` (default), the server listens on plain
    *   gRPC.
    * @param authService
    *   the authentication service the edge validates credentials with, in place of the in-memory
    *   one `enableProviders` configures (e.g. a real chain over a stub identity provider).
    */
  def boot(
      store: InMemoryControlPlaneStore,
      enableProviders: Boolean = true,
      tls: Boolean = false,
      wiring: RouterWiring,
      authService: Option[AuthenticationService] = None
  ): Harness =
    val port = ephemeralPort()
    val host = "127.0.0.1"

    // TLS cert paths: unique tempdir per call so concurrent harnesses
    // don't race on cert file writes.
    val certDir  = Files.createTempDirectory("qod-edge-harness-tls")
    val certPath = certDir.resolve("server-cert.pem").toString
    val keyPath  = certDir.resolve("server-key.pem").toString

    val cfg = EdgeConfig(
      host = host,
      port = port,
      tlsEnabled = tls,
      tlsCertChain = certPath,
      tlsPrivateKey = keyPath,
      sessionTtlSec = 3600L
    )

    val tracker = new NodeLoadTracker
    val backend = stubBackend
    val sup     = new PoolSupervisor(backend, tracker, store)
    sup.restore()
    wiring.opa.foreach(sup.wireOpa)
    if wiring.spawnNodes then sup.reconcile().unsafeRunSync()

    val adapter  = stubAdapter(tracker, wiring.nodeQuery)
    val sessions = new SessionRegistry
    val history  = new StatementHistoryStore()
    val si       = StatementInstruments.noop

    val router = new FlightSqlRouter(
      supervisor = sup,
      sessions = sessions,
      tracker = tracker,
      adapter = adapter,
      history = history,
      stmtInstruments = si,
      columnPolicyRewriter = wiring.columnPolicyRewriter,
      journal = wiring.journal,
      lockdownFor = wiring.lockdownFor(sup),
      deniedBuckets = wiring.deniedBuckets(sup),
      protectedWriteGuard = wiring.protectedWriteGuard,
      metadataFilterRewriter = wiring.metadataFilterRewriter
    )

    val authSvc = authService.getOrElse(
      new InMemoryAuthService.Service(store, providersEnabled = enableProviders)
    )

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

    val resolveTenant = (raw: String) => sup.getTenant(raw)

    val authorize = (req: ai.starlake.quack.ondemand.rbac.AuthzRequest) =>
      sup.authorizeHandshakeDetailed(req)

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
      host = host,
      port = port,
      allocator = allocator,
      shutdown = () => {
        srv.stop()
        allocator.close()
      },
      supervisor = sup,
      router = router
    )
