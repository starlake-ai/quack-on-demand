package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
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
import ai.starlake.quack.ondemand.catalog.{DroppedTableEntry, DuckLakeCatalogReader}
import ai.starlake.quack.ondemand.runtime.QuackBackend
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
import java.util.concurrent.atomic.AtomicInteger

class CatalogUndropHandlersSpec extends AnyFlatSpec with Matchers:

  private val NoKey: Option[String]                   = None
  private val NoScope: String => Option[SessionScope] = _ => None

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

  private def stubBackend: QuackBackend = new QuackBackend:
    private val counter                     = new AtomicInteger(23000)
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
        "/tmp/qod-undrop-test"
      )
      .unsafeRunSync()
    if withPool then
      sup.createPool(PoolKey("acme", "acme_tpch1", "bi"), RoleDistribution(0, 0, 1)).unsafeRunSync()
    (sup, store)

  /** Stub reader: `doomed` was dropped at 40 (last-live 39); snapshots 12, 39 and 42 exist, 42 is
    * max; `liveTables` gates the collision probe.
    */
  private def stubReader(
      dropped: List[DroppedTableEntry] = List(
        DroppedTableEntry("tpch1", "doomed", 40L, 39L, Some("2026-07-14T00:00:00Z"), true)
      ),
      liveTables: Set[(String, String)] = Set.empty
  ): DuckLakeCatalogReader =
    new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean = id == 12L || id == 39L || id == 42L
      override def maxSnapshotId(): Option[Long]     = Some(42L)
      override def listDroppedTables(limit: Int): List[DroppedTableEntry] = dropped.take(limit)
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean =
        liveTables.contains((schema, table))

  trait Stubs:
    val (sup, store)            = supervisor()
    val telemetryStore          = new RecordingTelemetryStore
    val audit                   = new AuditRecorder(telemetryStore, _ => None)
    var seenSql: Option[String] = None
    var closed                  = false
    var executorResult: IO[Either[RouterFailure, QueryResult]] =
      IO.pure(Right(QueryResult(emptyReader(), () => closed = true, "node-1", 1L)))
    val executor: CatalogPreviewHandlers.PreviewExecutor =
      (connectionId, user, poolKey, sql) =>
        seenSql = Some(sql)
        executorResult

    def handlers(readerOverride: DuckLakeCatalogReader = stubReader()): CatalogUndropHandlers =
      new CatalogUndropHandlers(
        sup,
        executor,
        (_, _) => readerOverride,
        CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30),
        _ => None,
        audit = audit
      )

    def undrop(
        h: CatalogUndropHandlers,
        tenant: String = "acme",
        tenantDb: String = "acme_tpch1",
        schema: String = "tpch1",
        table: String = "doomed",
        asName: Option[String] = None,
        fromSnapshot: Option[Long] = None,
        apiKey: Option[String] = NoKey
    ) =
      h.undrop(UndropRequest(tenant, tenantDb, schema, table, asName, fromSnapshot), apiKey)(
        NoScope
      ).unsafeRunSync()

  "recoverable" should "return the reader's dropped tables" in new Stubs:
    val out = handlers().recoverable("acme", "acme_tpch1", None, NoKey)(NoScope).unsafeRunSync()
    val r   = out.toOption.get
    r.tables.map(_.table) shouldBe List("doomed")
    r.tables.head.lastLiveSnapshot shouldBe 39L
    r.tables.head.recoverable shouldBe true

  it should "404 an unknown tenant and 403 a cross-tenant caller" in new Stubs:
    val h = handlers()
    h.recoverable("nope", "acme_tpch1", None, NoKey)(NoScope)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.NotFound
    val foreign: String => Option[SessionScope] =
      _ => Some(SessionScope(superuser = false, manageableTenants = Set("other")))
    h.recoverable("acme", "acme_tpch1", None, Some("tok"))(foreign)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.Forbidden

  "undrop" should "recreate under the original name via a routed CTAS at the last-live snapshot" in new Stubs:
    val out = undrop(handlers())
    val r   = out.toOption.get
    r shouldBe UndropResponse("tpch1", "doomed", "doomed", 39L)
    seenSql shouldBe Some(
      """CREATE TABLE "tpch1"."doomed" AS SELECT * FROM "tpch1"."doomed" AT (VERSION => 39)"""
    )
    closed shouldBe true

  it should "404 when no such dropped table exists" in new Stubs:
    val out = undrop(handlers(), table = "ghost")
    out.left.toOption.get._1 shouldBe StatusCode.NotFound
    out.left.toOption.get._2.error shouldBe "not_found"
    seenSql shouldBe None

  it should "410 snapshot_expired when the source snapshot is gone" in new Stubs:
    val h = handlers(
      readerOverride =
        stubReader(dropped = List(DroppedTableEntry("tpch1", "doomed", 14L, 13L, None, false)))
    )
    val out = undrop(h)
    out.left.toOption.get._1 shouldBe StatusCode.Gone
    out.left.toOption.get._2.error shouldBe "snapshot_expired"

  it should "409 name_conflict on a live target unless asName is supplied" in new Stubs:
    val h   = handlers(readerOverride = stubReader(liveTables = Set(("tpch1", "doomed"))))
    val out = undrop(h)
    out.left.toOption.get._1 shouldBe StatusCode.Conflict
    out.left.toOption.get._2.error shouldBe "name_conflict"
    val ok = undrop(h, asName = Some("doomed_restored"))
    ok.toOption.get.restoredAs shouldBe "doomed_restored"
    seenSql.get should include(""""tpch1"."doomed_restored" AS SELECT * FROM "tpch1"."doomed"""")

  it should "honor an explicit fromSnapshot when it exists" in new Stubs:
    val out = undrop(handlers(), fromSnapshot = Some(12L))
    out.toOption.get.fromSnapshot shouldBe 12L
    seenSql.get should include("AT (VERSION => 12)")

  it should "400 invalid_name on unsafe characters in schema, table, or asName" in new Stubs:
    val h = handlers()
    undrop(h, asName = Some("bad\"name")).left.toOption.get._2.error shouldBe "invalid_name"
    undrop(h, schema = "x;y").left.toOption.get._2.error shouldBe "invalid_name"
    undrop(h, table = "a b").left.toOption.get._2.error shouldBe "invalid_name"
    seenSql shouldBe None

  it should "map executor outcomes to acl_denied and undrop_failed" in new Stubs:
    executorResult = IO.pure(Left(RouterFailure.AccessDenied("no grant")))
    val h      = handlers()
    val denied = undrop(h)
    denied.left.toOption.get._1 shouldBe StatusCode.Forbidden
    denied.left.toOption.get._2.error shouldBe "acl_denied"
    executorResult = IO.pure(Left(RouterFailure.Internal("boom")))
    val failed = undrop(h)
    failed.left.toOption.get._1 shouldBe StatusCode.BadGateway
    failed.left.toOption.get._2.error shouldBe "undrop_failed"

  it should "audit ok with schema/table/restoredAs/fromSnapshot and denied on gate rejection" in new Stubs:
    val h = handlers()
    undrop(h)
    val ok = telemetryStore.events.last
    ok.action shouldBe AuditActions.CatalogUndrop
    ok.outcome shouldBe "ok"
    ok.detail.get("restoredAs") shouldBe Some("doomed")
    ok.detail.get("fromSnapshot") shouldBe Some("39")
    undrop(h, tenant = "nope")
    val denied = telemetryStore.events.last
    denied.action shouldBe AuditActions.CatalogUndrop
    denied.outcome shouldBe "denied"
