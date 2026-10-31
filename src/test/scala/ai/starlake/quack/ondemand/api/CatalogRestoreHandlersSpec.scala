package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.{ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.types.pojo.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.time.Instant

class CatalogRestoreHandlersSpec extends AnyFlatSpec with Matchers:

  private val RootKey: Option[String]               = Some(IdentityFixtures.RootSessionTok)
  private val SuperuserScope: SessionScope.Resolver = _ => SessionScope.Superuser

  private def emptyReader(): org.apache.arrow.vector.ipc.ArrowReader =
    val allocator = new RootAllocator()
    val schema    = new Schema(java.util.List.of())
    val root      = VectorSchemaRoot.create(schema, allocator)
    val out       = new ByteArrayOutputStream()
    val writer    = new ArrowStreamWriter(root, null, out)
    writer.start()
    root.setRowCount(0)
    writer.writeBatch()
    writer.end()
    writer.close()
    root.close()
    new ArrowStreamReader(new ByteArrayInputStream(out.toByteArray), allocator)

  private def stubBackend: QuackBackend =
    StubQuackBackend.noop(portBase = 23100, countingPorts = true)

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
        "/tmp/qod-restore-test"
      )
      .unsafeRunSync()
    if withPool then
      sup.createPool(PoolKey("acme", "acme_tpch1", "bi"), RoleDistribution(0, 0, 1)).unsafeRunSync()
    (sup, store)

  /** Arrow batch with (change_type VARCHAR, n BIGINT) rows for the dry-run summary decode. */
  private def summaryReader(rows: List[(String, Long)]): org.apache.arrow.vector.ipc.ArrowReader =
    val allocator = new RootAllocator()
    val ctField   = new org.apache.arrow.vector.types.pojo.Field(
      "change_type",
      org.apache.arrow.vector.types.pojo.FieldType.nullable(
        org.apache.arrow.vector.types.pojo.ArrowType.Utf8.INSTANCE
      ),
      null
    )
    val nField = new org.apache.arrow.vector.types.pojo.Field(
      "n",
      org.apache.arrow.vector.types.pojo.FieldType.nullable(
        new org.apache.arrow.vector.types.pojo.ArrowType.Int(64, true)
      ),
      null
    )
    val schema = new Schema(java.util.List.of(ctField, nField))
    val root   = VectorSchemaRoot.create(schema, allocator)
    val ct     = root.getVector("change_type").asInstanceOf[org.apache.arrow.vector.VarCharVector]
    val nv     = root.getVector("n").asInstanceOf[org.apache.arrow.vector.BigIntVector]
    rows.zipWithIndex.foreach { case ((t, c), idx) =>
      ct.setSafe(idx, t.getBytes("UTF-8")); nv.setSafe(idx, c)
    }
    root.setRowCount(rows.length)
    val out    = new ByteArrayOutputStream()
    val writer = new ArrowStreamWriter(root, null, out)
    writer.start(); writer.writeBatch(); writer.end(); writer.close(); root.close()
    new ArrowStreamReader(new ByteArrayInputStream(out.toByteArray), allocator)

  /** Stub reader for restore: snapshots 12, 39, 42 exist, max 42; `live` maps live tables to their
    * (tableId, beginSnapshot); `latest` is the table's newest touching snapshot.
    */
  private def stubReader(
      live: Map[(String, String), (Long, Long)] = Map(("tpch1", "orders") -> (7L, 40L)),
      latest: Long = 40L,
      existsAt: (String, String, Long) => Boolean = (_, _, _) => true
  ): DuckLakeCatalogReader =
    new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean = id == 12L || id == 39L || id == 42L
      override def maxSnapshotId(): Option[Long]     = Some(42L)
      override def currentTableInfo(schema: String, table: String): Option[(Long, Long)] =
        live.get((schema, table))
      override def latestTableSnapshot(schema: String, table: String): Option[Long] =
        live.get((schema, table)).map(_ => latest)
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean =
        live.contains((schema, table)) && existsAt(schema, table, snapshotId)

  trait Stubs:
    val (sup, store)               = supervisor()
    val telemetryStore             = new RecordingTelemetryStore
    val audit                      = new AuditRecorder(telemetryStore, _ => None)
    var readSql: Option[String]    = None
    var writeSql: Option[String]   = None
    var readPool: Option[PoolKey]  = None
    var writePool: Option[PoolKey] = None
    var readUser: Option[String]   = None
    var writeUser: Option[String]  = None
    var closed                     = false
    var readResult: IO[Either[RouterFailure, QueryResult]] =
      IO.pure(
        Right(
          QueryResult(
            summaryReader(
              List(
                ("insert", 3L),
                ("delete", 1L),
                ("update_postimage", 2L),
                ("update_preimage", 2L)
              )
            ),
            () => (),
            "node-1",
            1L
          )
        )
      )
    var writeResult: IO[Either[RouterFailure, QueryResult]] =
      IO.pure(Right(QueryResult(emptyReader(), () => closed = true, "node-1", 1L)))
    var readCallers: List[ExecCaller]                        = Nil
    var writeCallers: List[ExecCaller]                       = Nil
    val readExecutor: CatalogPreviewHandlers.PreviewExecutor =
      (caller, poolKey, sql) => {
        readSql = Some(sql); readPool = Some(poolKey); readUser = Some(caller.identity)
        readCallers = readCallers :+ caller
        readResult
      }
    val writeExecutor: CatalogPreviewHandlers.PreviewExecutor =
      (caller, poolKey, sql) => {
        writeSql = Some(sql); writePool = Some(poolKey); writeUser = Some(caller.identity)
        writeCallers = writeCallers :+ caller
        writeResult
      }

    def handlers(
        readerOverride: DuckLakeCatalogReader = stubReader(),
        cfgOverride: CatalogConfig = CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30),
        sessionsOverride: String => Option[SessionTokenStore.Session] = _ => None,
        patsOverride: String => Option[PatPrincipal] = _ => None
    ): CatalogRestoreHandlers =
      new CatalogRestoreHandlers(
        sup,
        store,
        readExecutor,
        writeExecutor,
        (_, _) => readerOverride,
        cfgOverride,
        IdentityFixtures.restCaller(sessionsOverride, patsOverride),
        audit = audit
      )

    def restore(
        h: CatalogRestoreHandlers,
        to: String = "39",
        dryRun: Option[Boolean] = None,
        expected: Option[Long] = None,
        schema: String = "tpch1",
        table: String = "orders",
        apiKey: Option[String] = RootKey
    ) =
      h.restore(
        RestoreRequest("acme", "acme_tpch1", schema, table, to, dryRun, expected),
        apiKey
      )(SuperuserScope)
        .unsafeRunSync()

  "restore" should "404 an unknown tenant" in new Stubs:
    val h   = handlers()
    val out = h
      .restore(
        RestoreRequest("nope", "acme_tpch1", "tpch1", "orders", "39"),
        RootKey
      )(SuperuserScope)
      .unsafeRunSync()
    out.left.toOption.get._1 shouldBe StatusCode.NotFound
    out.left.toOption.get._2.error shouldBe "not_found"
    telemetryStore.events.last.action shouldBe AuditActions.CatalogRestore
    telemetryStore.events.last.outcome shouldBe "denied"

  it should "400 invalid_name on unsafe characters in schema" in new Stubs:
    val h   = handlers()
    val out = restore(h, schema = "tp;ch")
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_name"
    writeSql shouldBe None

  it should "400 invalid_snapshot_ref on empty, overflowing, or non-positive expected snapshot" in new Stubs:
    val h = handlers()
    restore(h, to = "").left.toOption.get._2.error shouldBe "invalid_snapshot_ref"
    restore(h, to = "9" * 25).left.toOption.get._2.error shouldBe "invalid_snapshot_ref"
    restore(h, expected = Some(0L)).left.toOption.get._2.error shouldBe "invalid_snapshot_ref"

  it should "404 an unknown tag" in new Stubs:
    val h   = handlers()
    val out = restore(h, to = "nosuchtag")
    out.left.toOption.get._1 shouldBe StatusCode.NotFound

  it should "410 snapshot_expired for a nonexistent id between existing ones" in new Stubs:
    val h   = handlers()
    val out = restore(h, to = "13")
    out.left.toOption.get._1 shouldBe StatusCode.Gone
    out.left.toOption.get._2.error shouldBe "snapshot_expired"

  it should "422 for a snapshot id beyond max" in new Stubs:
    val h   = handlers()
    val out = restore(h, to = "99")
    out.left.toOption.get._1 shouldBe StatusCode.UnprocessableEntity

  it should "404 not_found (mentioning undrop) when the table is not live" in new Stubs:
    val h   = handlers(readerOverride = stubReader(live = Map.empty))
    val out = restore(h)
    out.left.toOption.get._1 shouldBe StatusCode.NotFound
    out.left.toOption.get._2.error shouldBe "not_found"
    out.left.toOption.get._2.message should include("undrop")

  it should "422 invalid_snapshot when the table does not resolve at the target snapshot" in new Stubs:
    val h   = handlers(readerOverride = stubReader(existsAt = (_, _, _) => false))
    val out = restore(h)
    out.left.toOption.get._1 shouldBe StatusCode.UnprocessableEntity
    out.left.toOption.get._2.error shouldBe "invalid_snapshot"

  it should "409 concurrent_write when expectedCurrentSnapshot is stale" in new Stubs:
    val h   = handlers()
    val out = restore(h, expected = Some(41L))
    out.left.toOption.get._1 shouldBe StatusCode.Conflict
    out.left.toOption.get._2.error shouldBe "concurrent_write"
    writeSql shouldBe None

  it should "no-op a dry-run to a target at or after the current snapshot" in new Stubs:
    val h   = handlers()
    val out = restore(h, to = "42", dryRun = Some(true))
    val r   = out.toOption.get
    r.summary shouldBe Some(DataDiffSummary(0, 0, 0))
    r.newSnapshot shouldBe None
    r.dryRun shouldBe true
    readSql shouldBe None

  it should "run the dry-run summary query and fold the aggregate" in new Stubs:
    val h   = handlers()
    val out = restore(h, to = "39", dryRun = Some(true))
    readSql.get should include(
      "ducklake_table_changes('acme_tpch1', 'tpch1', 'orders', 40, 40)"
    )
    val r = out.toOption.get
    r.summary shouldBe Some(DataDiffSummary(3, 1, 2))
    writeSql shouldBe None
    val ok = telemetryStore.events.last
    ok.outcome shouldBe "ok"
    ok.detail.get("dryRun") shouldBe Some("true")

  it should "prefer the dual pool over an earlier-sorted write-only pool for the dry-run read" in new Stubs:
    sup.createPool(PoolKey("acme", "acme_tpch1", "aro"), RoleDistribution(1, 0, 0)).unsafeRunSync()
    restore(handlers(), to = "39", dryRun = Some(true))
    readPool shouldBe Some(PoolKey("acme", "acme_tpch1", "bi"))

  it should "run the dry-run summary and the execute both as the caller, never as system" in new Stubs:
    val profile = AuthenticatedProfile("alice", "user", Set.empty, Map.empty, "db", Some("acme"))
    val session = SessionTokenStore.Session(
      profile,
      SessionScope(superuser = false, manageableTenants = Set("acme")),
      Instant.now()
    )
    val h = handlers(sessionsOverride = tok => if tok == "alice-tok" then Some(session) else None)
    restore(h, to = "39", dryRun = Some(true), apiKey = Some("alice-tok"))
    readUser shouldBe Some("alice")
    readCallers.map(_.system) shouldBe List(false)
    restore(h, to = "39", apiKey = Some("alice-tok"))
    writeUser shouldBe Some("alice")

  it should "execute the CREATE OR REPLACE and report the new snapshot" in new Stubs:
    val h   = handlers()
    val out = restore(h, to = "39")
    writeSql.get shouldBe
      """CREATE OR REPLACE TABLE "tpch1"."orders" AS SELECT * FROM "tpch1"."orders" AT (VERSION => 39)"""
    closed shouldBe true
    val r = out.toOption.get
    r.newSnapshot shouldBe Some(40L)
    r.summary shouldBe None
    r.dryRun shouldBe false
    val ok = telemetryStore.events.last
    ok.outcome shouldBe "ok"
    ok.detail.contains("newSnapshot") shouldBe true

  it should "reflect a post-execute begin_snapshot that differs from the pre-capture" in new Stubs:
    var info   = (7L, 40L)
    val reader = new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean = id == 12L || id == 39L || id == 42L
      override def maxSnapshotId(): Option[Long]     = Some(42L)
      override def currentTableInfo(schema: String, table: String): Option[(Long, Long)] =
        Some(info)
      override def latestTableSnapshot(schema: String, table: String): Option[Long] = Some(40L)
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean = true
    writeResult = IO {
      info = (8L, 43L)
      Right(QueryResult(emptyReader(), () => closed = true, "node-1", 1L))
    }
    val h   = handlers(readerOverride = reader)
    val out = restore(h, to = "39")
    out.toOption.get.newSnapshot shouldBe Some(43L)

  it should "403 acl_denied when the executor reports AccessDenied" in new Stubs:
    writeResult = IO.pure(Left(RouterFailure.AccessDenied("nope")))
    val h   = handlers()
    val out = restore(h)
    out.left.toOption.get._1 shouldBe StatusCode.Forbidden
    out.left.toOption.get._2.error shouldBe "acl_denied"

  it should "409 concurrent_write when the failure reason mentions a conflict" in new Stubs:
    writeResult = IO.pure(Left(RouterFailure.Internal("transaction Conflict detected")))
    val h   = handlers()
    val out = restore(h)
    out.left.toOption.get._1 shouldBe StatusCode.Conflict
    out.left.toOption.get._2.error shouldBe "concurrent_write"

  it should "502 restore_failed for any other executor failure" in new Stubs:
    writeResult = IO.pure(Left(RouterFailure.Internal("boom")))
    val h   = handlers()
    val out = restore(h)
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "restore_failed"

  it should "502 restore_failed with a timeout message when the CTAS neither returns nor commits" in new Stubs:
    writeResult = IO.never
    val h = handlers(cfgOverride =
      CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30, restoreTimeoutSec = 1)
    )
    val out = restore(h)
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "restore_failed"
    out.left.toOption.get._2.message should include("timed out")

  it should "report success when the CTAS commits despite the timeout (post-timeout probe)" in new Stubs:
    writeResult = IO.never
    var info    = (7L, 40L)
    val toggled = new java.util.concurrent.atomic.AtomicBoolean(false)
    val reader  = new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean = id == 12L || id == 39L || id == 42L
      override def maxSnapshotId(): Option[Long]     = Some(42L)
      override def currentTableInfo(schema: String, table: String): Option[(Long, Long)] =
        if toggled.get() then Some((8L, 43L)) else { toggled.set(true); Some(info) }
      override def latestTableSnapshot(schema: String, table: String): Option[Long] = Some(40L)
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean = true
    val h = handlers(
      readerOverride = reader,
      cfgOverride =
        CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30, restoreTimeoutSec = 1)
    )
    val out = restore(h)
    out.toOption.get.newSnapshot shouldBe Some(43L)
    telemetryStore.events.last.outcome shouldBe "ok"

  // ---------- executor identity (PAT regression) ----------

  "restore's replace" should "run a superuser session as its user, never as a system caller" in new Stubs:
    restore(handlers(), to = "39", apiKey = Some(IdentityFixtures.RootSessionTok)).isRight shouldBe
      true
    writeCallers.map(c => (c.identity, c.restriction, c.patId, c.system)) shouldBe
      List((IdentityFixtures.RootUsername, TokenRestriction.Unrestricted, None, false))

  it should "run a session as its user, unrestricted" in new Stubs:
    val h = handlers(sessionsOverride = IdentityFixtures.sessionOf)
    restore(h, to = "39", apiKey = Some(IdentityFixtures.SessionTok)).isRight shouldBe true
    writeCallers.map(c => (c.identity, c.restriction, c.patId)) shouldBe
      List(("alice", TokenRestriction.Unrestricted, None))

  it should "run a PAT as its owner, with the PAT's restriction and id (never superuser)" in new Stubs:
    val h = handlers(patsOverride = IdentityFixtures.patOf)
    restore(h, to = "39", apiKey = Some(IdentityFixtures.PatTok)).isRight shouldBe true
    writeCallers.map(c => (c.identity, c.restriction, c.patId)) shouldBe
      List(("alice", IdentityFixtures.PatRestriction, Some(IdentityFixtures.PatId)))

  it should "run the PAT's dry run as the PAT's owner, with the PAT" in new Stubs:
    val h = handlers(patsOverride = IdentityFixtures.patOf)
    restore(
      h,
      to = "39",
      dryRun = Some(true),
      apiKey = Some(IdentityFixtures.PatTok)
    ).isRight shouldBe
      true
    readCallers.map(c => (c.identity, c.system, c.patId)) shouldBe
      List(("alice", false, Some(IdentityFixtures.PatId)))
    writeCallers shouldBe Nil

  it should "answer a dry run the node refuses the caller with 403, never retrying as system" in new Stubs:
    readResult = IO.pure(Left(RouterFailure.AccessDenied("QoD: access denied: no grant on orders")))
    val h   = handlers(patsOverride = IdentityFixtures.patOf)
    val out = restore(h, to = "39", dryRun = Some(true), apiKey = Some(IdentityFixtures.PatTok))
    out.left.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.Forbidden, "acl_denied"))
    readCallers.map(_.system) shouldBe List(false)

  it should "401 an unresolvable token on both legs without calling either executor" in new Stubs:
    val h = handlers(
      sessionsOverride = IdentityFixtures.sessionOf,
      patsOverride = IdentityFixtures.patOf
    )
    List(Some(true), None).foreach { dry =>
      val out = restore(h, to = "39", dryRun = dry, apiKey = Some("qod_pat_unknown"))
      out.left.toOption.map(e => (e._1, e._2.error)) shouldBe
        Some((StatusCode.Unauthorized, "unauthorized"))
    }
    readCallers shouldBe Nil
    writeCallers shouldBe Nil

  it should "401 an absent credential on both legs without calling either executor" in new Stubs:
    val h = handlers(
      sessionsOverride = IdentityFixtures.sessionOf,
      patsOverride = IdentityFixtures.patOf
    )
    List(Some(true), None).foreach { dry =>
      val out = restore(h, to = "39", dryRun = dry, apiKey = None)
      out.left.toOption.map(e => (e._1, e._2.error)) shouldBe
        Some((StatusCode.Unauthorized, "unauthorized"))
    }
    readCallers shouldBe Nil
    writeCallers shouldBe Nil
