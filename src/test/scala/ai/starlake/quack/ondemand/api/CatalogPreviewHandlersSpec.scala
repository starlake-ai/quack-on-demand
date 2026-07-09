package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{IntVector, VectorSchemaRoot}
import org.apache.arrow.vector.ipc.{ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, Schema}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class CatalogPreviewHandlersSpec extends AnyFlatSpec with Matchers:

  private val NoKey: Option[String]                   = None
  private val NoScope: String => Option[SessionScope] = _ => None

  /** One-row IntVector "id" Arrow stream, reused as a canned successful QueryResult payload. */
  private def oneRowReader(): org.apache.arrow.vector.ipc.ArrowReader =
    val allocator = new RootAllocator()
    val field     = Field.nullable("id", new ArrowType.Int(32, true))
    val schema    = new Schema(java.util.List.of(field))
    val root      = VectorSchemaRoot.create(schema, allocator)
    val out       = new ByteArrayOutputStream()
    val writer    = new ArrowStreamWriter(root, null, out)
    writer.start()
    root.allocateNew()
    root.getVector("id").asInstanceOf[IntVector].setSafe(0, 1)
    root.setRowCount(1)
    writer.writeBatch()
    writer.end()
    writer.close()
    root.close()
    new ArrowStreamReader(new ByteArrayInputStream(out.toByteArray), allocator)

  private def stubBackend: QuackBackend = new QuackBackend:
    private val counter                     = new AtomicInteger(21000)
    def start(s: NodeSpec): IO[RunningNode] =
      IO.pure(
        RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          counter.getAndIncrement(),
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

  /** Tenant `acme` with one DuckLake tenant-db `acme_tpch1` and one running pool `bi` (so
    * `sup.list()` has a `PoolState` to pick for previews). `withPool = false` skips pool creation
    * to exercise the `no_pool` gate.
    */
  private def supervisor(withPool: Boolean = true): (PoolSupervisor, InMemoryControlPlaneStore) =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
    sup.createTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db")).unsafeRunSync()
    sup
      .createTenantDb(
        "acme",
        "tpch1",
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> "ignored",
          "schemaName" -> "main"
        ),
        "/tmp/qod-preview-test"
      )
      .unsafeRunSync()
    if withPool then
      sup.createPool(PoolKey("acme", "acme_tpch1", "bi"), RoleDistribution(1, 0, 0)).unsafeRunSync()
    (sup, store)

  /** Stub `DuckLakeCatalogReader`: snapshot 42 exists and is the max; everything else is absent. */
  private def stubReader(): DuckLakeCatalogReader =
    new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean                       = id == 42L
      override def maxSnapshotId(): Option[Long]                           = Some(42L)
      override def snapshotAtOrBefore(ts: java.time.Instant): Option[Long] = Some(42L)

  private val cfg = CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30)

  trait Stubs:
    val (sup, store)                 = supervisor()
    val telemetryStore               = new RecordingTelemetryStore
    val audit                        = new AuditRecorder(telemetryStore, _ => None)
    var seenSql: Option[String]      = None
    var seenUser: Option[String]     = None
    var seenPoolKey: Option[PoolKey] = None
    var executorResult: IO[Either[RouterFailure, QueryResult]] =
      IO.pure(
        Right(QueryResult(oneRowReader(), () => (), "node-1", 5L))
      )

    val executor: CatalogPreviewHandlers.PreviewExecutor =
      (connectionId, user, poolKey, sql) =>
        seenSql = Some(sql)
        seenUser = Some(user)
        seenPoolKey = Some(poolKey)
        executorResult

    def handlers(
        cfgOverride: CatalogConfig = cfg,
        sessionsOverride: String => Option[SessionTokenStore.Session] = _ => None
    ): CatalogPreviewHandlers =
      new CatalogPreviewHandlers(
        sup,
        store,
        sessionsOverride,
        executor,
        (_, _) => stubReader(),
        cfgOverride,
        audit
      )

    def preview(
        h: CatalogPreviewHandlers,
        tenant: String = "acme",
        tenantDb: String = "acme_tpch1",
        schema: String = "tpch1",
        table: String = "region",
        asOf: Option[Long] = None,
        asOfTag: Option[String] = None,
        asOfTs: Option[Instant] = None,
        limit: Option[Int] = None,
        apiKey: Option[String] = NoKey
    ) =
      h.preview(tenant, tenantDb, schema, table, asOf, asOfTag, asOfTs, limit, apiKey)(NoScope)
        .unsafeRunSync()

  "preview" should "404 an unknown tenant" in new Stubs:
    val h   = handlers()
    val out = preview(h, tenant = "nope")
    out.left.toOption.get._1 shouldBe StatusCode.NotFound

  it should "403 a cross-tenant caller" in new Stubs:
    val h                                            = handlers()
    val foreignScope: String => Option[SessionScope] =
      _ => Some(SessionScope(superuser = false, manageableTenants = Set("other")))
    val out = h
      .preview("acme", "acme_tpch1", "tpch1", "region", None, None, None, None, Some("tok"))(
        foreignScope
      )
      .unsafeRunSync()
    out.left.toOption.get._1 shouldBe StatusCode.Forbidden

  it should "400 invalid_kind for a non-DuckLake tenant-db" in new Stubs:
    val (nonDuckLakeSup, nonDuckLakeStore) = supervisor(withPool = false)
    nonDuckLakeSup
      .createTenantDb(
        "acme",
        "mem1",
        TenantDbKind.InMemory,
        Map.empty,
        ""
      )
      .unsafeRunSync()
    val h = new CatalogPreviewHandlers(
      nonDuckLakeSup,
      nonDuckLakeStore,
      _ => None,
      executor,
      (_, _) => stubReader(),
      cfg,
      audit
    )
    val out = preview(h, tenantDb = "acme_mem1")
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_kind"

  it should "400 when both asOf and asOfTag are supplied" in new Stubs:
    val h   = handlers()
    val out = preview(h, asOf = Some(42L), asOfTag = Some("v1"))
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest

  it should "410 an expired snapshot id" in new Stubs:
    val h   = handlers()
    val out = preview(h, asOf = Some(1L))
    out.left.toOption.get._1 shouldBe StatusCode.Gone

  it should "build SQL with the AT (VERSION => n) clause and clamp limit to the cap" in new Stubs:
    val h = handlers()
    preview(h, asOf = Some(42L), limit = Some(999999))
    seenSql shouldBe Some(
      """SELECT * FROM "tpch1"."region" AT (VERSION => 42) LIMIT 100"""
    )

  it should "omit the AT clause when no selector is supplied" in new Stubs:
    val h = handlers()
    preview(h, limit = Some(10))
    seenSql shouldBe Some("""SELECT * FROM "tpch1"."region" LIMIT 10""")

  it should "double-quote-escape a table name containing a double quote" in new Stubs:
    val h = handlers()
    preview(h, table = "re\"gion", limit = Some(10))
    seenSql shouldBe Some("""SELECT * FROM "tpch1"."re""gion" LIMIT 10""")

  it should "pass PoolKey and (no-session) superuser-equivalent identity to the executor" in new Stubs:
    val h = handlers()
    preview(h)
    seenPoolKey shouldBe Some(PoolKey("acme", "acme_tpch1", "bi"))

  it should "pass the session's username when a session token is presented" in new Stubs:
    val profile = AuthenticatedProfile("alice", "user", Set.empty, Map.empty, "db", Some("acme"))
    val session = SessionTokenStore.Session(
      profile,
      SessionScope(superuser = false, manageableTenants = Set("acme")),
      Instant.now()
    )
    val h = handlers(sessionsOverride = tok => if tok == "alice-tok" then Some(session) else None)
    preview(h, apiKey = Some("alice-tok"))
    seenUser shouldBe Some("alice")

  it should "404 no_pool when the tenant-db has no pools" in new Stubs:
    val (noPoolSup, noPoolStore) = supervisor(withPool = false)
    val h                        = new CatalogPreviewHandlers(
      noPoolSup,
      noPoolStore,
      _ => None,
      executor,
      (_, _) => stubReader(),
      cfg,
      audit
    )
    val out = preview(h)
    out.left.toOption.get._1 shouldBe StatusCode.NotFound
    out.left.toOption.get._2.error shouldBe "no_pool"

  it should "403 acl_denied when the executor reports AccessDenied" in new Stubs:
    executorResult = IO.pure(Left(RouterFailure.AccessDenied("no select on tpch1.region")))
    val h   = handlers()
    val out = preview(h)
    out.left.toOption.get._1 shouldBe StatusCode.Forbidden
    out.left.toOption.get._2.error shouldBe "acl_denied"

  it should "502 preview_failed for any other RouterFailure" in new Stubs:
    executorResult = IO.pure(Left(RouterFailure.Internal("node crashed")))
    val h   = handlers()
    val out = preview(h)
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "preview_failed"

  it should "502 within the configured timeout when the executor hangs" in new Stubs:
    executorResult = IO.never
    val h       = handlers(cfgOverride = cfg.copy(previewTimeoutSec = 1))
    val start   = System.currentTimeMillis()
    val out     = preview(h)
    val elapsed = System.currentTimeMillis() - start
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "preview_failed"
    elapsed should be < 3000L

  it should "audit CatalogPreviewRead on success with rowsReturned" in new Stubs:
    val h = handlers()
    preview(h)
    telemetryStore.events should not be empty
    val e = telemetryStore.events.last
    e.action shouldBe ai.starlake.quack.ondemand.telemetry.AuditActions.CatalogPreviewRead
    e.outcome shouldBe "ok"
    e.detail.get("rowsReturned") shouldBe Some("1")

  it should "audit denied on a gate rejection" in new Stubs:
    val h = handlers()
    preview(h, tenant = "nope")
    telemetryStore.events should not be empty
    val e = telemetryStore.events.last
    e.action shouldBe ai.starlake.quack.ondemand.telemetry.AuditActions.CatalogPreviewRead
    e.outcome shouldBe "denied"

  it should "always audit preview reads even when auditCatalogReads is off" in new Stubs:
    val h = handlers(cfgOverride = cfg.copy(auditCatalogReads = false))
    preview(h)
    telemetryStore.events.count(
      _.action == ai.starlake.quack.ondemand.telemetry.AuditActions.CatalogPreviewRead
    ) shouldBe 1
