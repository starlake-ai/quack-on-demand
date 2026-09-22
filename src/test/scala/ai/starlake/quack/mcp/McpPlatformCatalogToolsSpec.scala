package ai.starlake.quack.mcp

import ai.starlake.quack.{CatalogConfig, FlightConfig, ManagerConfig}
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.config.{AclConfig, AuthenticationConfig, ValidationConfig}
import ai.starlake.quack.observability.metrics.MetricsConfig
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  CatalogPreviewHandlers,
  CatalogRestoreHandlers,
  CatalogUndropHandlers,
  ConfigHandlers,
  ConfigRegistry,
  HistoryHandlers,
  ManifestHandlers,
  PatHandlers,
  SessionTokenStore,
  UsageHandlers
}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.catalog.{DroppedTableEntry, DuckLakeCatalogReader}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  LiquibaseRunner,
  PatStore,
  UserStore
}
import ai.starlake.quack.ondemand.telemetry.NoopTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import io.circe.{Json, JsonObject}
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.{ArrowReader, ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.types.pojo.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.util.Try

/** restore_snapshot / undrop_table / list_recoverable: the heavy Arrow-fixture half of the platform
  * tier, split out of [[McpPlatformToolsSpec]] per the task brief (the restore/undrop executor
  * stack is disproportionately heavy next to the rest of the platform tier's cheap fixtures).
  * Fixture construction (stub backend, DuckLakeCatalogReader overrides, Arrow batches) is lifted
  * from `CatalogRestoreHandlersSpec` and `CatalogUndropHandlersSpec`.
  *
  * Every test still spins up a throwaway migrated Postgres database purely to satisfy
  * McpPlatformTools' mandatory `pats: PatHandlers` constructor argument -- see
  * [[McpPlatformToolsSpec]]'s scaladoc for why that dependency cannot be stubbed out.
  */
class McpPlatformCatalogToolsSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodmcpcat")

  private val Tenant0  = "acme"
  private val TenantDb = "acme_tpch1"

  private def emptyReader(): ArrowReader =
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

  /** Arrow batch with (change_type VARCHAR, n BIGINT) rows for the dry-run summary decode (verbatim
    * from CatalogRestoreHandlersSpec).
    */
  private def summaryReader(rows: List[(String, Long)]): ArrowReader =
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

  private def stubBackend: QuackBackend =
    StubQuackBackend.noop(portBase = 23300, countingPorts = true)

  private def supervisor(): (PoolSupervisor, InMemoryControlPlaneStore) =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
    sup
      .createTenant(Tenant(id = Tenant0, displayName = Tenant0, authProvider = "db"))
      .unsafeRunSync()
    sup
      .createTenantDb(
        Tenant0,
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
        "/tmp/qod-mcp-platform-catalog-test"
      )
      .unsafeRunSync()
    sup.createPool(PoolKey(Tenant0, TenantDb, "bi"), RoleDistribution(0, 0, 1)).unsafeRunSync()
    (sup, store)

  /** Stub reader for restore: snapshots 12, 39, 42 exist, max 42; "orders" is live with (tableId=7,
    * beginSnapshot=40), latest touching snapshot 40 (verbatim from CatalogRestoreHandlersSpec).
    */
  private def restoreReader(): DuckLakeCatalogReader =
    new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean = id == 12L || id == 39L || id == 42L
      override def maxSnapshotId(): Option[Long]     = Some(42L)
      override def currentTableInfo(schema: String, table: String): Option[(Long, Long)] =
        if schema == "tpch1" && table == "orders" then Some((7L, 40L)) else None
      override def latestTableSnapshot(schema: String, table: String): Option[Long] =
        if schema == "tpch1" && table == "orders" then Some(40L) else None
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean =
        schema == "tpch1" && table == "orders"

  /** Stub reader for undrop: "doomed" was dropped at 40 (last-live 39); snapshots 12, 39 and 42
    * exist, 42 is max (verbatim from CatalogUndropHandlersSpec).
    */
  private def undropReader(): DuckLakeCatalogReader =
    new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean = id == 12L || id == 39L || id == 42L
      override def maxSnapshotId(): Option[Long]     = Some(42L)
      override def listDroppedTables(limit: Int): List[DroppedTableEntry] =
        List(DroppedTableEntry("tpch1", "doomed", 40L, 39L, Some("2026-07-14T00:00:00Z"), true))
          .take(limit)
      override def findDroppedTable(schema: String, table: String): Option[DroppedTableEntry] =
        if schema == "tpch1" && table == "doomed" then
          Some(DroppedTableEntry("tpch1", "doomed", 40L, 39L, Some("2026-07-14T00:00:00Z"), true))
        else None
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean = false

  private def liveConfigEntries = ConfigRegistry.collect(
    ConfigRegistry.rootsFor(
      managerCls = classOf[ManagerConfig],
      flightCls = classOf[FlightConfig],
      authCls = classOf[AuthenticationConfig],
      aclCls = classOf[AclConfig],
      validationCls = classOf[ValidationConfig],
      metricsCls = classOf[MetricsConfig]
    )
  )

  /** Builds a real McpPlatformTools over the Arrow-stubbed restore/undrop handlers above, plus the
    * cheap in-memory/Noop construction for the rest of the platform tier and `federated = None`.
    * `pats` is a real Postgres-backed PatHandlers purely to satisfy the constructor (see the class
    * scaladoc); this spec never calls a PAT tool.
    */
  private def withTools(test: McpPlatformTools => Unit): Unit =
    if !TestPostgres.reachable then
      cancel(
        s"local Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}; skipping"
      )
    val dbName = s"qodmcpcat_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    var pats: PatStore = null
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val patsH = new PatHandlers(pats, new SessionTokenStore(), userOf = (_, _) => None)

      val (sup, store) = supervisor()

      var writeSql: Option[String]                           = None
      var closed                                             = false
      val readResult: IO[Either[RouterFailure, QueryResult]] =
        IO.pure(
          Right(
            QueryResult(
              summaryReader(List(("insert", 3L), ("delete", 1L))),
              () => (),
              "node-1",
              1L
            )
          )
        )
      val readExecutor: CatalogPreviewHandlers.PreviewExecutor =
        (_, _, _) => readResult
      val writeExecutor: CatalogPreviewHandlers.PreviewExecutor =
        (_, _, sql) => {
          writeSql = Some(sql);
          IO.pure(Right(QueryResult(emptyReader(), () => closed = true, "node-1", 1L)))
        }
      val undropExecutor: CatalogPreviewHandlers.PreviewExecutor =
        (_, _, sql) => {
          writeSql = Some(sql);
          IO.pure(Right(QueryResult(emptyReader(), () => closed = true, "node-1", 1L)))
        }

      val restoreH = new CatalogRestoreHandlers(
        sup,
        store,
        readExecutor,
        writeExecutor,
        (_, _) => restoreReader(),
        CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30),
        _ => None
      )
      val undropH = new CatalogUndropHandlers(
        sup,
        undropExecutor,
        (_, _) => undropReader(),
        CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30),
        _ => None
      )

      val manifest = new ManifestHandlers(
        store,
        sup,
        managerVersion = "test",
        hostname = "host",
        requireEncryption = false
      )
      val cfgH     = new ConfigHandlers(ConfigFactory.load(), liveConfigEntries)
      val historyH = new HistoryHandlers(NoopTelemetryStore)
      val usageH   = new UsageHandlers(NoopTelemetryStore)
      val scopeOf: String => Option[SessionScope] = _ => None

      val tools = new McpPlatformTools(
        restoreH,
        undropH,
        None,
        manifest,
        patsH,
        cfgH,
        historyH,
        usageH,
        scopeOf
      )
      test(tools)
    finally
      Try(if pats != null then pats.close())
      Try(TestPostgres.dropDatabase(dbName))

  private def call(
      tools: McpPlatformTools,
      name: String,
      principal: McpPrincipal,
      args: (String, Json)*
  ): Either[String, Json] =
    val tool = tools.tools.find(_.name == name).getOrElse(fail(s"tool $name not defined"))
    tool.adminOnly shouldBe true
    tool.run(principal, JsonObject(args*)).unsafeRunSync()

  "list_recoverable" should "return the recoverable tables list" in withTools { tools =>
    val out = call(
      tools,
      "list_recoverable",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb)
    )
    withClue(out)(out.isRight shouldBe true)
    val tables = out.toOption.get.hcursor.downField("tables").values.get
    tables should have size 1
    tables.head.hcursor.get[String]("table").toOption shouldBe Some("doomed")
  }

  "restore_snapshot" should "refuse when 'to' is missing" in withTools { tools =>
    val out = call(
      tools,
      "restore_snapshot",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "schema"   -> Json.fromString("tpch1"),
      "table"    -> Json.fromString("orders")
    )
    out.isLeft shouldBe true
    out.left.toOption.get should include("'to'")
  }

  it should "run a dry-run restore and return the data-diff summary" in withTools { tools =>
    val out = call(
      tools,
      "restore_snapshot",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "schema"   -> Json.fromString("tpch1"),
      "table"    -> Json.fromString("orders"),
      "to"       -> Json.fromString("39"),
      "dry_run"  -> Json.True
    )
    withClue(out)(out.isRight shouldBe true)
    val r = out.toOption.get.hcursor
    r.get[Boolean]("dryRun").toOption shouldBe Some(true)
    r.downField("summary").get[Long]("inserted").toOption shouldBe Some(3L)
    r.downField("summary").get[Long]("deleted").toOption shouldBe Some(1L)
  }

  "undrop_table" should "recreate a dropped table under its original name" in withTools { tools =>
    val out = call(
      tools,
      "undrop_table",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString(Tenant0),
      "database" -> Json.fromString(TenantDb),
      "schema"   -> Json.fromString("tpch1"),
      "table"    -> Json.fromString("doomed")
    )
    withClue(out)(out.isRight shouldBe true)
    val r = out.toOption.get.hcursor
    r.get[String]("restoredAs").toOption shouldBe Some("doomed")
    r.get[Long]("fromSnapshot").toOption shouldBe Some(39L)
  }
