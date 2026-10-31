package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.model.{PoolKey, RoleDistribution, SnapshotTag, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.{DuckLakeCatalogReader, SchemaDiffResult}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
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

class CatalogPreviewHandlersSpec extends AnyFlatSpec with Matchers:

  private val RootKey: Option[String]               = Some(IdentityFixtures.RootSessionTok)
  private val SuperuserScope: SessionScope.Resolver = _ => SessionScope.Superuser

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

  private def stubBackend: QuackBackend = StubQuackBackend.noop(countingPorts = true)

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
    val (sup, store)                  = supervisor()
    val telemetryStore                = new RecordingTelemetryStore
    val audit                         = new AuditRecorder(telemetryStore, _ => None)
    var seenSql: Option[String]       = None
    var seenUser: Option[String]      = None
    var seenPoolKey: Option[PoolKey]  = None
    var seenCallers: List[ExecCaller] = Nil
    var executorResult: IO[Either[RouterFailure, QueryResult]] =
      IO.pure(
        Right(QueryResult(oneRowReader(), () => (), "node-1", 5L))
      )

    val executor: CatalogPreviewHandlers.PreviewExecutor =
      (caller, poolKey, sql) =>
        seenSql = Some(sql)
        seenCallers = seenCallers :+ caller
        seenUser = Some(caller.identity)
        seenPoolKey = Some(poolKey)
        executorResult

    // dataDiff issues two statements (summary, then page); this queue hands them out in order
    // while still recording every SQL text.
    var seenSqls: List[String]                                        = Nil
    var executorResults: List[IO[Either[RouterFailure, QueryResult]]] = Nil
    val queuedExecutor: CatalogPreviewHandlers.PreviewExecutor        =
      (caller, poolKey, sql) =>
        seenSqls = seenSqls :+ sql
        seenCallers = seenCallers :+ caller
        seenUser = Some(caller.identity)
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
        executorOverride: CatalogPreviewHandlers.PreviewExecutor = executor,
        patsOverride: String => Option[PatPrincipal] = _ => None
    ): CatalogPreviewHandlers =
      new CatalogPreviewHandlers(
        sup,
        store,
        IdentityFixtures.restCaller(sessionsOverride, patsOverride),
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
        apiKey: Option[String] = RootKey
    ) =
      h.preview(tenant, tenantDb, schema, table, asOf, asOfTag, asOfTs, limit, apiKey)(
        SuperuserScope
      ).unsafeRunSync()

    def schemaDiff(
        h: CatalogPreviewHandlers,
        tenant: String = "acme",
        tenantDb: String = "acme_tpch1",
        schema: String = "tpch1",
        table: String = "region",
        from: String = "12",
        to: String = "42",
        apiKey: Option[String] = RootKey
    ) =
      h.schemaDiff(tenant, tenantDb, schema, table, from, to, apiKey)(SuperuserScope)
        .unsafeRunSync()

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
        apiKey: Option[String] = RootKey
    ) =
      h.dataDiff(tenant, tenantDb, schema, table, from, to, limit, cursor, changeType, apiKey)(
        SuperuserScope
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
    val h                                   = handlers()
    val foreignScope: SessionScope.Resolver =
      _ => SessionScope(superuser = false, manageableTenants = Set("other"))
    val out = h
      .preview("acme", "acme_tpch1", "tpch1", "region", None, None, None, None, Some("tok"))(
        foreignScope
      )
      .unsafeRunSync()
    out.left.toOption.get._1 shouldBe StatusCode.Forbidden

  /** A `duckdb-file` tenant-db `acme_files` (catalog alias `filesdb`) and a `memory` one
    * `acme_mem1`, each with a pool, on the Stubs' supervisor.
    */
  private def addNodeHeldDbs(sup: PoolSupervisor): Unit =
    sup
      .createTenantDb(
        "acme",
        "files",
        TenantDbKind.DuckDbFile,
        Map("dbName" -> "filesdb", "schemaName" -> "main"),
        "/tmp/qod-preview-test/files.db"
      )
      .unsafeRunSync()
    sup.createTenantDb("acme", "mem1", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup.createPool(PoolKey("acme", "acme_files", "files_bi"), RoleDistribution(1, 0, 0)).unsafeRunSync()
    sup.createPool(PoolKey("acme", "acme_mem1", "mem_bi"), RoleDistribution(1, 0, 0)).unsafeRunSync()

  it should "preview a duckdb-file table qualified with the node's catalog alias" in new Stubs:
    addNodeHeldDbs(sup)
    val out =
      preview(handlers(), tenantDb = "acme_files", schema = "main", table = "t", limit = Some(10))
    out.isRight shouldBe true
    seenSql shouldBe Some("""SELECT * FROM "filesdb"."main"."t" LIMIT 11""")
    seenPoolKey shouldBe Some(PoolKey("acme", "acme_files", "files_bi"))

  it should "preview a memory table through DuckDB's built-in memory catalog" in new Stubs:
    addNodeHeldDbs(sup)
    preview(handlers(), tenantDb = "acme_mem1", schema = "main", table = "t", limit = Some(10))
    seenSql shouldBe Some("""SELECT * FROM "memory"."main"."t" LIMIT 11""")

  it should "400 invalid_selector for time travel on a non-DuckLake tenant-db, without a query" in new Stubs:
    addNodeHeldDbs(sup)
    val out =
      preview(handlers(), tenantDb = "acme_files", schema = "main", table = "t", asOf = Some(42L))
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_selector"
    seenSql shouldBe None

  it should "keep the diffs DuckLake-only (400 invalid_kind)" in new Stubs:
    addNodeHeldDbs(sup)
    val out = schemaDiff(handlers(), tenantDb = "acme_files", schema = "main", table = "t")
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_kind"
    val data = dataDiff(handlers(), tenantDb = "acme_files", schema = "main", table = "t")
    data.left.toOption.get._2.error shouldBe "invalid_kind"

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

  it should "pass the PoolKey and the caller's identity to the executor" in new Stubs:
    val h = handlers()
    preview(h)
    seenPoolKey shouldBe Some(PoolKey("acme", "acme_tpch1", "bi"))
    seenUser shouldBe Some(IdentityFixtures.RootUsername)

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
      RestCaller(),
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
    val h                                   = handlers()
    val foreignScope: SessionScope.Resolver =
      _ => SessionScope(superuser = false, manageableTenants = Set("other"))
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

  it should "short-circuit from == to into an empty diff without calling the executor" in new Stubs:
    val h   = handlers(executorOverride = queuedExecutor)
    val out = dataDiff(h, from = "42", to = "42")
    val r   = out.toOption.get
    r.summary shouldBe DataDiffSummary(0, 0, 0)
    r.rows shouldBe Nil
    r.truncated shouldBe false
    seenSqls shouldBe Nil

  it should "pair correctly when postimage precedes preimage (the engine's actual sort order)" in new Stubs:
    // ORDER BY snapshot_id, rowid, change_type sorts 'update_postimage' BEFORE
    // 'update_preimage' ('post' < 'pre'), so production always sees this order.
    queueDiffResults(
      summary = List(("update_preimage", 1L), ("update_postimage", 1L)),
      page = List((13L, 7L, "update_postimage", 2), (13L, 7L, "update_preimage", 1))
    )
    val h   = handlers(executorOverride = queuedExecutor)
    val out = dataDiff(h)
    val r   = out.toOption.get
    r.rows should have size 1
    r.rows.head.changeType shouldBe "update"
    r.rows.head.before.get.flatMap(_.asNumber.flatMap(_.toInt)) shouldBe List(1)
    r.rows.head.after.get.flatMap(_.asNumber.flatMap(_.toInt)) shouldBe List(2)

  it should "400 invalid_cursor on an all-digit cursor beyond Long range" in new Stubs:
    val h   = handlers(executorOverride = queuedExecutor)
    val out = dataDiff(h, cursor = Some("99999999999999999999:1"))
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_cursor"
    seenSqls shouldBe Nil

  it should "404 not_found on from == to for an unknown table (agreeing with from < to)" in new Stubs:
    val h = handlers(
      executorOverride = queuedExecutor,
      readerOverride = stubReader(knownTables = Set(("tpch1", "other")))
    )
    val out = dataDiff(h, from = "42", to = "42")
    out.left.toOption.get._1 shouldBe StatusCode.NotFound
    out.left.toOption.get._2.error shouldBe "not_found"

  // ---------- executor identity (PAT regression) ----------

  import IdentityFixtures.*

  "preview" should "run a superuser session as its user, never as a system caller" in new Stubs:
    val h = handlers()
    preview(h, apiKey = Some(RootSessionTok))
    seenCallers.map(c => (c.identity, c.restriction, c.patId, c.system)) shouldBe
      List((RootUsername, TokenRestriction.Unrestricted, None, false))

  it should "run a session as its user, unrestricted" in new Stubs:
    val h = handlers(sessionsOverride = sessionOf)
    preview(h, apiKey = Some(SessionTok))
    seenCallers.map(c => (c.identity, c.restriction, c.patId)) shouldBe
      List(("alice", TokenRestriction.Unrestricted, None))

  it should "run a PAT as its owner, with the PAT's restriction and id (never superuser)" in new Stubs:
    val h = handlers(patsOverride = patOf)
    preview(h, apiKey = Some(PatTok))
    seenCallers.map(c => (c.identity, c.restriction, c.patId)) shouldBe
      List(("alice", PatRestriction, Some(PatId)))

  it should "cap the fetch at the PAT's maxRows" in new Stubs:
    val h = handlers(patsOverride = patOf)
    preview(h, apiKey = Some(PatTok), limit = Some(50))
    seenSql shouldBe Some(s"""SELECT * FROM "tpch1"."region" LIMIT ${PatMaxRows + 1}""")

  it should "401 an unresolvable token without calling the executor" in new Stubs:
    val h   = handlers(sessionsOverride = sessionOf, patsOverride = patOf)
    val out = preview(h, apiKey = Some("qod_pat_unknown"))
    out.left.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.Unauthorized, "unauthorized"))
    seenCallers shouldBe Nil

  it should "401 an absent credential without calling the executor" in new Stubs:
    val h   = handlers(sessionsOverride = sessionOf, patsOverride = patOf)
    val out = preview(h, apiKey = None)
    out.left.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.Unauthorized, "unauthorized"))
    seenCallers shouldBe Nil

  it should "never flag a REST caller as a system caller" in new Stubs:
    val h = handlers(sessionsOverride = sessionOf, patsOverride = patOf)
    preview(h, apiKey = Some(RootSessionTok))
    preview(h, apiKey = Some(SessionTok))
    preview(h, apiKey = Some(PatTok))
    seenCallers.map(_.system) shouldBe List(false, false, false)

  it should "run a session or PAT of a tenant user NAMED 'superuser' as that user, not system" in new Stubs:
    val h = handlers(sessionsOverride = sentinelSessionOf, patsOverride = sentinelPatOf)
    preview(h, apiKey = Some(SentinelSessionTok))
    preview(h, apiKey = Some(SentinelPatTok))
    seenCallers.map(c => (c.identity, c.system, c.patId)) shouldBe
      List(("superuser", false, None), ("superuser", false, Some("pat-sentinel")))

  "dataDiff" should "run a superuser session as its user, never as a system caller" in new Stubs:
    queueDiffResults(summary = Nil, page = Nil)
    val h = handlers(executorOverride = queuedExecutor)
    dataDiff(h, apiKey = Some(RootSessionTok))
    seenCallers.map(c => (c.identity, c.restriction, c.patId, c.system)).distinct shouldBe
      List((RootUsername, TokenRestriction.Unrestricted, None, false))

  it should "run a session as its user, unrestricted" in new Stubs:
    queueDiffResults(summary = Nil, page = Nil)
    val h = handlers(executorOverride = queuedExecutor, sessionsOverride = sessionOf)
    dataDiff(h, apiKey = Some(SessionTok))
    seenCallers.map(c => (c.identity, c.restriction, c.patId)).distinct shouldBe
      List(("alice", TokenRestriction.Unrestricted, None))

  it should "run a PAT as its owner, with the PAT's restriction and id (never superuser)" in new Stubs:
    queueDiffResults(summary = Nil, page = Nil)
    val h = handlers(executorOverride = queuedExecutor, patsOverride = patOf)
    dataDiff(h, apiKey = Some(PatTok))
    seenCallers should have size 2
    seenCallers.map(c => (c.identity, c.restriction, c.patId)).distinct shouldBe
      List(("alice", PatRestriction, Some(PatId)))
    // The page size honours the PAT's maxRows: fetch = limit * 2 + 2.
    seenSqls(1) should endWith(s"LIMIT ${PatMaxRows * 2 + 2}")

  it should "401 an unresolvable token without calling the executor" in new Stubs:
    val h =
      handlers(
        executorOverride = queuedExecutor,
        sessionsOverride = sessionOf,
        patsOverride = patOf
      )
    val out = dataDiff(h, apiKey = Some("qod_pat_unknown"))
    out.left.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.Unauthorized, "unauthorized"))
    seenCallers shouldBe Nil

  it should "401 an absent credential without calling the executor" in new Stubs:
    val h =
      handlers(
        executorOverride = queuedExecutor,
        sessionsOverride = sessionOf,
        patsOverride = patOf
      )
    val out = dataDiff(h, apiKey = None)
    out.left.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.Unauthorized, "unauthorized"))
    seenCallers shouldBe Nil

/** Credentials shared by the executor-identity cases of the catalog handler specs: a superuser's
  * session (`root`, the default credential of those specs), a session for `alice`, and a PAT owned
  * by `alice` carrying a non-trivial restriction.
  */
object IdentityFixtures:
  val SessionTok                       = "alice-session"
  val PatTok                           = "qod_pat_alice"
  val PatId                            = "pat-00000001"
  val PatMaxRows                       = 3
  val PatRestriction: TokenRestriction =
    TokenRestriction.Unrestricted.copy(pools = Some(Set("bi")), maxRows = Some(PatMaxRows))

  /** A superuser's login session: what the specs run as by default. */
  val RootUsername   = "root"
  val RootSessionTok = "root-session"
  private val root   = AuthenticatedProfile(RootUsername, "admin", Set.empty, Map.empty, "db", None)
  val rootSessionOf: String => Option[SessionTokenStore.Session] = t =>
    Option.when(t == RootSessionTok)(
      SessionTokenStore.Session(root, SessionScope.Superuser, Instant.now())
    )

  /** The specs' executor-identity resolver: the root session first, then `sessionsOverride`'s
    * sessions, then `patsOverride`'s PATs. Anything else, no credential included, is a 401.
    */
  def restCaller(
      sessionsOverride: String => Option[SessionTokenStore.Session] = _ => None,
      patsOverride: String => Option[PatPrincipal] = _ => None
  ): RestCaller =
    RestCaller(sessionOf = t => rootSessionOf(t).orElse(sessionsOverride(t)), patOf = patsOverride)

  private val alice =
    AuthenticatedProfile("alice", "admin", Set.empty, Map.empty, "db", Some("acme"))

  val sessionOf: String => Option[SessionTokenStore.Session] = t =>
    Option.when(t == SessionTok)(
      SessionTokenStore.Session(alice, SessionScope(false, Set("acme")), Instant.now())
    )

  // A tenant-scoped user whose NAME is the old sentinel: user names are not reserved.
  val SentinelSessionTok = "sentinel-session"
  val SentinelPatTok     = "qod_pat_sentinel"
  private val sentinel   =
    AuthenticatedProfile("superuser", "admin", Set.empty, Map.empty, "db", Some("acme"))
  val sentinelSessionOf: String => Option[SessionTokenStore.Session] = t =>
    Option.when(t == SentinelSessionTok)(
      SessionTokenStore.Session(sentinel, SessionScope(false, Set("acme")), Instant.now())
    )
  val sentinelPatOf: String => Option[PatPrincipal] = t =>
    Option.when(t == SentinelPatTok)(
      PatPrincipal(
        user = RbacUser(
          id = "u-sentinel",
          tenant = Some("acme"),
          username = "superuser",
          kind = "admin"
        ),
        patId = "pat-sentinel",
        scope = SessionScope(false, Set("acme")),
        isAdmin = true,
        restriction = TokenRestriction.Unrestricted
      )
    )

  val patOf: String => Option[PatPrincipal] = t =>
    Option.when(t == PatTok)(
      PatPrincipal(
        user = RbacUser(id = "u-alice", tenant = Some("acme"), username = "alice", kind = "admin"),
        patId = PatId,
        scope = SessionScope(false, Set("acme")),
        isAdmin = true,
        restriction = PatRestriction
      )
    )
