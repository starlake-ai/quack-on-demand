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
import org.apache.arrow.vector.{BigIntVector, IntVector, VarCharVector, VectorSchemaRoot}
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

  /** `ducklake_table_changes`-shaped Arrow stream: (snapshot_id, rowid, change_type, id) rows. */
  private def changesReader(
      rows: List[(Long, Long, String, Int)]
  ): org.apache.arrow.vector.ipc.ArrowReader =
    val allocator = new RootAllocator()
    val schema    = new Schema(
      java.util.List.of(
        Field.nullable("snapshot_id", new ArrowType.Int(64, true)),
        Field.nullable("rowid", new ArrowType.Int(64, true)),
        Field.nullable("change_type", new ArrowType.Utf8()),
        Field.nullable("id", new ArrowType.Int(32, true))
      )
    )
    val root   = VectorSchemaRoot.create(schema, allocator)
    val out    = new ByteArrayOutputStream()
    val writer = new ArrowStreamWriter(root, null, out)
    writer.start()
    root.allocateNew()
    rows.zipWithIndex.foreach { case ((snap, rid, ct, id), i) =>
      root.getVector("snapshot_id").asInstanceOf[BigIntVector].setSafe(i, snap)
      root.getVector("rowid").asInstanceOf[BigIntVector].setSafe(i, rid)
      root.getVector("change_type").asInstanceOf[VarCharVector].setSafe(i, ct.getBytes("UTF-8"))
      root.getVector("id").asInstanceOf[IntVector].setSafe(i, id)
    }
    root.setRowCount(rows.length)
    writer.writeBatch()
    writer.end()
    writer.close()
    root.close()
    new ArrowStreamReader(new ByteArrayInputStream(out.toByteArray), allocator)

  /** Aggregate-summary-shaped Arrow stream: (change_type, n) rows. */
  private def summaryReader(counts: List[(String, Long)]): org.apache.arrow.vector.ipc.ArrowReader =
    val allocator = new RootAllocator()
    val schema    = new Schema(
      java.util.List.of(
        Field.nullable("change_type", new ArrowType.Utf8()),
        Field.nullable("n", new ArrowType.Int(64, true))
      )
    )
    val root   = VectorSchemaRoot.create(schema, allocator)
    val out    = new ByteArrayOutputStream()
    val writer = new ArrowStreamWriter(root, null, out)
    writer.start()
    root.allocateNew()
    counts.zipWithIndex.foreach { case ((ct, n), i) =>
      root.getVector("change_type").asInstanceOf[VarCharVector].setSafe(i, ct.getBytes("UTF-8"))
      root.getVector("n").asInstanceOf[BigIntVector].setSafe(i, n)
    }
    root.setRowCount(counts.length)
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

    // dataDiff issues two statements (summary, then page); this queue hands them out in order
    // while still recording every SQL text.
    var seenSqls: List[String]                                        = Nil
    var executorResults: List[IO[Either[RouterFailure, QueryResult]]] = Nil
    val queuedExecutor: CatalogPreviewHandlers.PreviewExecutor        =
      (connectionId, user, poolKey, sql) =>
        seenSqls = seenSqls :+ sql
        seenUser = Some(user)
        seenPoolKey = Some(poolKey)
        executorResults match
          case head :: tail =>
            executorResults = tail
            head
          case Nil => executorResult

    def handlers(
        cfgOverride: CatalogConfig = cfg,
        sessionsOverride: String => Option[SessionTokenStore.Session] = _ => None,
        readerOverride: DuckLakeCatalogReader = stubReader(),
        executorOverride: CatalogPreviewHandlers.PreviewExecutor = executor
    ): CatalogPreviewHandlers =
      new CatalogPreviewHandlers(
        sup,
        store,
        sessionsOverride,
        executorOverride,
        (_, _) => readerOverride,
        cfgOverride,
        catalogAlias = (t, td) => sup.effectiveMetastoreFor(t, td).getOrElse("dbName", td),
        audit = audit
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

    def dataDiff(
        h: CatalogPreviewHandlers,
        tenant: String = "acme",
        tenantDb: String = "acme_tpch1",
        schema: String = "tpch1",
        table: String = "region",
        from: String = "12",
        to: String = "42",
        limit: Option[Int] = None,
        cursor: Option[String] = None,
        changeType: Option[String] = None,
        apiKey: Option[String] = NoKey
    ) =
      h.dataDiff(tenant, tenantDb, schema, table, from, to, limit, cursor, changeType, apiKey)(
        NoScope
      ).unsafeRunSync()

    def queueDiffResults(
        summary: List[(String, Long)],
        page: List[(Long, Long, String, Int)]
    ): Unit =
      executorResults = List(
        IO.pure(Right(QueryResult(summaryReader(summary), () => (), "node-1", 5L))),
        IO.pure(Right(QueryResult(changesReader(page), () => (), "node-1", 5L)))
      )

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
      audit = audit
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
      audit = audit
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

  // ---------- data diff (Spec 02) ----------

  "dataDiff" should "pair update pre/post rows, strip metadata columns, and pass the summary through" in new Stubs:
    queueDiffResults(
      summary = List(("insert", 1L), ("update_preimage", 1L), ("update_postimage", 1L)),
      page = List(
        (13L, 7L, "update_preimage", 1),
        (13L, 7L, "update_postimage", 2),
        (13L, 9L, "insert", 3)
      )
    )
    val h   = handlers(executorOverride = queuedExecutor)
    val out = dataDiff(h)
    val r   = out.toOption.get
    r.summary shouldBe DataDiffSummary(1, 0, 1)
    r.columns.map(_.name) shouldBe List("id")
    r.rows should have size 2
    r.rows.head.changeType shouldBe "update"
    r.rows.head.snapshotId shouldBe 13L
    r.rows.head.before.get.flatMap(_.asNumber.flatMap(_.toInt)) shouldBe List(1)
    r.rows.head.after.get.flatMap(_.asNumber.flatMap(_.toInt)) shouldBe List(2)
    r.rows(1).changeType shouldBe "insert"
    r.rows(1).row.get.flatMap(_.asNumber.flatMap(_.toInt)) shouldBe List(3)
    r.nextCursor shouldBe None
    r.truncated shouldBe false
    r.from shouldBe 12L
    r.to shouldBe 42L

  it should "interpolate the catalog alias and from+1 bound into the SQL and order the page deterministically" in new Stubs:
    queueDiffResults(summary = Nil, page = Nil)
    val h = handlers(executorOverride = queuedExecutor)
    dataDiff(h)
    seenSqls should have size 2
    seenSqls.head shouldBe
      "SELECT change_type, count(*) AS n FROM ducklake_table_changes('acme_tpch1', 'tpch1', 'region', 13, 42) GROUP BY change_type"
    seenSqls(1) should include(
      "FROM ducklake_table_changes('acme_tpch1', 'tpch1', 'region', 13, 42)"
    )
    seenSqls(1) should include("ORDER BY snapshot_id, rowid, change_type")

  it should "apply the changeType filter and cursor predicate in the page SQL" in new Stubs:
    queueDiffResults(summary = Nil, page = Nil)
    val h = handlers(executorOverride = queuedExecutor)
    dataDiff(h, cursor = Some("13:7"), changeType = Some("update"))
    seenSqls(1) should include(" AND (snapshot_id, rowid) > (13, 7)")
    seenSqls(1) should include(" AND change_type IN ('update_postimage', 'update_preimage')")

  it should "400 invalid_bounds when from resolves after to, without calling the executor" in new Stubs:
    val h   = handlers(executorOverride = queuedExecutor)
    val out = dataDiff(h, from = "42", to = "12")
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_bounds"
    seenSqls shouldBe Nil

  it should "400 invalid_filter on an unknown changeType" in new Stubs:
    val h   = handlers(executorOverride = queuedExecutor)
    val out = dataDiff(h, changeType = Some("upsert"))
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_filter"
    seenSqls shouldBe Nil

  it should "400 invalid_cursor on a malformed cursor" in new Stubs:
    val h   = handlers(executorOverride = queuedExecutor)
    val out = dataDiff(h, cursor = Some("abc"))
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_cursor"
    seenSqls shouldBe Nil

  it should "410 snapshot_expired on an expired bound" in new Stubs:
    val h   = handlers(executorOverride = queuedExecutor)
    val out = dataDiff(h, from = "11")
    out.left.toOption.get._1 shouldBe StatusCode.Gone
    out.left.toOption.get._2.error shouldBe "snapshot_expired"

  it should "stop at the entry cap and hand back a keyset cursor for the next page" in new Stubs:
    queueDiffResults(
      summary = List(("insert", 2L)),
      page = List((13L, 7L, "insert", 1), (13L, 8L, "update_preimage", 2))
    )
    val h   = handlers(executorOverride = queuedExecutor)
    val out = dataDiff(h, limit = Some(1))
    val r   = out.toOption.get
    r.rows.map(_.changeType) shouldBe List("insert")
    r.nextCursor shouldBe Some("13:7")
    r.truncated shouldBe true

  it should "pass a genuinely orphaned update half-row through with its raw change type" in new Stubs:
    queueDiffResults(
      summary = List(("update_preimage", 1L)),
      page = List((13L, 8L, "update_preimage", 2))
    )
    val h   = handlers(executorOverride = queuedExecutor)
    val out = dataDiff(h)
    val r   = out.toOption.get
    r.rows.map(_.changeType) shouldBe List("update_preimage")
    r.rows.head.row.get.flatMap(_.asNumber.flatMap(_.toInt)) shouldBe List(2)
    r.truncated shouldBe false
    r.nextCursor shouldBe None

  it should "audit ok with from/to/rowsReturned detail and never row contents" in new Stubs:
    queueDiffResults(summary = List(("insert", 1L)), page = List((13L, 9L, "insert", 3)))
    val h = handlers(executorOverride = queuedExecutor)
    dataDiff(h)
    val e = telemetryStore.events.last
    e.action shouldBe ai.starlake.quack.ondemand.telemetry.AuditActions.CatalogDataDiffRead
    e.outcome shouldBe "ok"
    e.detail.get("from") shouldBe Some("12")
    e.detail.get("to") shouldBe Some("42")
    e.detail.get("rowsReturned") shouldBe Some("1")
    e.detail.values.exists(_.contains("3")) shouldBe false

  it should "audit denied on a gate rejection" in new Stubs:
    val h = handlers(executorOverride = queuedExecutor)
    dataDiff(h, tenant = "nope")
    val e = telemetryStore.events.last
    e.action shouldBe ai.starlake.quack.ondemand.telemetry.AuditActions.CatalogDataDiffRead
    e.outcome shouldBe "denied"

  it should "502 diff_failed when the executor fails" in new Stubs:
    executorResults = List(IO.pure(Left(RouterFailure.Internal("node crashed"))))
    val h   = handlers(executorOverride = queuedExecutor)
    val out = dataDiff(h)
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "diff_failed"

  it should "403 acl_denied when the executor reports AccessDenied" in new Stubs:
    executorResults = List(IO.pure(Left(RouterFailure.AccessDenied("no select"))))
    val h   = handlers(executorOverride = queuedExecutor)
    val out = dataDiff(h)
    out.left.toOption.get._1 shouldBe StatusCode.Forbidden
    out.left.toOption.get._2.error shouldBe "acl_denied"
