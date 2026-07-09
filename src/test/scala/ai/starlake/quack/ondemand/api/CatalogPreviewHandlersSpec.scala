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
  SnapshotTag,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.catalog.{DuckLakeCatalogReader, SchemaDiffResult}
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

  /** Stub `DuckLakeCatalogReader`: snapshots 12 and 42 exist, 42 is the max; everything else is
    * absent. `knownTables` gates `tableExistsAt` (empty set = every (schema, table) resolves,
    * matching the original preview-only behavior); `diffResult` is returned verbatim by
    * `schemaDiff`.
    */
  private def stubReader(
      knownTables: Set[(String, String)] = Set.empty,
      diffResult: SchemaDiffResult = SchemaDiffResult(Nil, Nil, Nil, Nil)
  ): DuckLakeCatalogReader =
    new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean                       = id == 12L || id == 42L
      override def maxSnapshotId(): Option[Long]                           = Some(42L)
      override def snapshotAtOrBefore(ts: java.time.Instant): Option[Long] = Some(42L)
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean =
        knownTables.isEmpty || knownTables.contains((schema, table))
      override def schemaDiff(
          schema: String,
          table: String,
          from: Long,
          to: Long
      ): SchemaDiffResult = diffResult

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
        sessionsOverride: String => Option[SessionTokenStore.Session] = _ => None,
        readerOverride: DuckLakeCatalogReader = stubReader()
    ): CatalogPreviewHandlers =
      new CatalogPreviewHandlers(
        sup,
        store,
        sessionsOverride,
        executor,
        (_, _) => readerOverride,
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

    def schemaDiff(
        h: CatalogPreviewHandlers,
        tenant: String = "acme",
        tenantDb: String = "acme_tpch1",
        schema: String = "tpch1",
        table: String = "region",
        from: String = "12",
        to: String = "42",
        apiKey: Option[String] = NoKey
    ) =
      h.schemaDiff(tenant, tenantDb, schema, table, from, to, apiKey)(NoScope).unsafeRunSync()

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

  it should "build SQL with the AT (VERSION => n) clause, clamp limit to the cap, and fetch one extra row so truncation is observable" in new Stubs:
    val h = handlers()
    preview(h, asOf = Some(42L), limit = Some(999999))
    seenSql shouldBe Some(
      """SELECT * FROM "tpch1"."region" AT (VERSION => 42) LIMIT 101"""
    )

  it should "omit the AT clause when no selector is supplied" in new Stubs:
    val h = handlers()
    preview(h, limit = Some(10))
    seenSql shouldBe Some("""SELECT * FROM "tpch1"."region" LIMIT 11""")

  it should "double-quote-escape a table name containing a double quote" in new Stubs:
    val h = handlers()
    preview(h, table = "re\"gion", limit = Some(10))
    seenSql shouldBe Some("""SELECT * FROM "tpch1"."re""gion" LIMIT 11""")

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

  // ---------- schema-diff (Task 6) ----------

  "schemaDiff" should "404 an unknown tenant" in new Stubs:
    val h   = handlers()
    val out = schemaDiff(h, tenant = "nope")
    out.left.toOption.get._1 shouldBe StatusCode.NotFound

  it should "403 a cross-tenant caller" in new Stubs:
    val h                                            = handlers()
    val foreignScope: String => Option[SessionScope] =
      _ => Some(SessionScope(superuser = false, manageableTenants = Set("other")))
    val out = h
      .schemaDiff("acme", "acme_tpch1", "tpch1", "region", "12", "42", Some("tok"))(foreignScope)
      .unsafeRunSync()
    out.left.toOption.get._1 shouldBe StatusCode.Forbidden

  it should "accept a numeric snapshot id for both from and to" in new Stubs:
    val h   = handlers()
    val out = schemaDiff(h, from = "12", to = "42")
    out.isRight shouldBe true
    out.toOption.get.from shouldBe 12L
    out.toOption.get.to shouldBe 42L

  it should "accept a tag name for from or to" in new Stubs:
    store.createSnapshotTag(
      SnapshotTag(
        id = "tag-1",
        tenant = "acme",
        tenantDb = "acme_tpch1",
        name = "v1",
        snapshotId = 12L
      )
    )
    val h   = handlers()
    val out = schemaDiff(h, from = "v1", to = "42")
    out.isRight shouldBe true
    out.toOption.get.from shouldBe 12L
    out.toOption.get.to shouldBe 42L

  it should "yield an all-empty diff when from == to" in new Stubs:
    val h   = handlers()
    val out = schemaDiff(h, from = "42", to = "42")
    out.isRight shouldBe true
    val body = out.toOption.get
    body.added shouldBe empty
    body.removed shouldBe empty
    body.typeChanged shouldBe empty
    body.nullabilityChanged shouldBe empty

  it should "404 not_found for an unknown tag on either bound" in new Stubs:
    val h   = handlers()
    val out = schemaDiff(h, from = "nope-tag", to = "42")
    out.left.toOption.get._1 shouldBe StatusCode.NotFound
    out.left.toOption.get._2.error shouldBe "not_found"

  it should "410 an expired snapshot id on either bound" in new Stubs:
    val h   = handlers()
    val out = schemaDiff(h, from = "1", to = "42")
    out.left.toOption.get._1 shouldBe StatusCode.Gone

  it should "404 not_found for a table unknown at both bounds" in new Stubs:
    val h   = handlers(readerOverride = stubReader(knownTables = Set(("tpch1", "other"))))
    val out = schemaDiff(h, schema = "tpch1", table = "region")
    out.left.toOption.get._1 shouldBe StatusCode.NotFound
    out.left.toOption.get._2.error shouldBe "not_found"

  it should "surface added/removed/typeChanged/nullabilityChanged from the reader" in new Stubs:
    val diff = SchemaDiffResult(
      added = List(CatalogColumnEntry(2, "new_col", "BIGINT", true, false)),
      removed = List(CatalogColumnEntry(1, "old_col", "VARCHAR", true, false)),
      typeChanged = List(("renamed_type", "VARCHAR", "TEXT")),
      nullabilityChanged = List(("nullable_col", false, true))
    )
    val h    = handlers(readerOverride = stubReader(diffResult = diff))
    val out  = schemaDiff(h)
    val body = out.toOption.get
    body.added.map(_.name) shouldBe List("new_col")
    body.removed.map(_.name) shouldBe List("old_col")
    body.typeChanged shouldBe List(SchemaDiffColumnType("renamed_type", "VARCHAR", "TEXT"))
    body.nullabilityChanged shouldBe List(SchemaDiffNullability("nullable_col", false, true))

  it should "audit CatalogSchemaDiffRead on success" in new Stubs:
    val h = handlers()
    schemaDiff(h)
    telemetryStore.events should not be empty
    val e = telemetryStore.events.last
    e.action shouldBe ai.starlake.quack.ondemand.telemetry.AuditActions.CatalogSchemaDiffRead
    e.outcome shouldBe "ok"
    e.detail.get("from") shouldBe Some("12")
    e.detail.get("to") shouldBe Some("42")

  it should "audit CatalogSchemaDiffRead denied on a gate rejection" in new Stubs:
    val h = handlers()
    schemaDiff(h, tenant = "nope")
    telemetryStore.events should not be empty
    val e = telemetryStore.events.last
    e.action shouldBe ai.starlake.quack.ondemand.telemetry.AuditActions.CatalogSchemaDiffRead
    e.outcome shouldBe "denied"
    // Pre-resolution denial: no snapshot ids exist yet, so the detail map stays empty.
    e.detail.get("from") shouldBe None
    e.detail.get("to") shouldBe None

  it should "audit CatalogSchemaDiffRead denied on an unknown table" in new Stubs:
    val h = handlers(readerOverride = stubReader(knownTables = Set(("tpch1", "other"))))
    schemaDiff(h, schema = "tpch1", table = "region")
    telemetryStore.events should not be empty
    val e = telemetryStore.events.last
    e.action shouldBe ai.starlake.quack.ondemand.telemetry.AuditActions.CatalogSchemaDiffRead
    e.outcome shouldBe "denied"
    // Post-resolution denial: the resolved snapshot bounds are carried in the detail map.
    e.detail.get("from") shouldBe Some("12")
    e.detail.get("to") shouldBe Some("42")
