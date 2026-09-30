// src/test/scala/ai/starlake/quack/mcp/McpDataToolsSpec.scala
package ai.starlake.quack.mcp

import ai.starlake.quack.{CatalogConfig, McpConfig}
import ai.starlake.quack.edge.adapter.{NodeLoadTracker, QuackResponse, TestArrow}
import ai.starlake.quack.edge.{RouterFailure, StatementHistoryStore}
import ai.starlake.quack.edge.FlightSqlRouter
import ai.starlake.quack.model.{
  FederatedSource,
  FederatedSourceType,
  PoolKey,
  Role,
  RunningNode,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  CatalogColumnEntry,
  CatalogDataFileEntry,
  CatalogHandlers,
  CatalogHistoryHandlers,
  CatalogPreviewHandlers,
  CatalogTableDetailResponse,
  CatalogTableEntry,
  ExecCaller,
  IcebergCatalogHandlers,
  ProfileHandlers,
  RestCaller,
  TagHandlers,
  TenantDbHandlers
}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.catalog.iceberg.NodeMetadataQuery
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.ondemand.telemetry.NoopTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{BigIntVector, BitVector, VarCharVector, VectorSchemaRoot}
import org.apache.arrow.vector.ipc.{ArrowReader, ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, Schema}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.time.Instant
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Data-tier MCP tool contract: tenant inference, the run_sql row cap, error surfacing, and the
  * describe_table composition. Built over the in-memory supervisor + TestArrow readers; no Postgres
  * and no Flight wire.
  */
class McpDataToolsSpec extends AnyFlatSpec with Matchers:

  private val Tenant   = "acme"
  private val TenantDb = "acme_default"

  private val patToken       = "qod_pat_alice"
  private val NonAdminPatTok = "qod_pat_bob"

  private def patFor(tenant: Option[String], admin: Boolean): McpPrincipal =
    val scope = SessionScope(
      superuser = tenant.isEmpty && admin,
      manageableTenants = if admin then tenant.toSet else Set.empty
    )
    new McpPrincipal.Pat(
      PatPrincipal(
        user = RbacUser(
          id = "u1",
          tenant = tenant,
          username = "alice",
          role = if admin then "admin" else "user"
        ),
        patId = "pat-1",
        scope = scope,
        isAdmin = admin,
        restriction = TokenRestriction.Unrestricted
      ),
      patToken
    )

  private def scopedPat(r: TokenRestriction): McpPrincipal =
    new McpPrincipal.Pat(
      PatPrincipal(
        user = RbacUser(id = "u1", tenant = Some(Tenant), username = "alice", role = "user"),
        patId = "pat-1",
        scope = SessionScope(superuser = false, manageableTenants = Set.empty),
        isAdmin = false,
        restriction = r
      ),
      patToken
    )

  private val stubDetail = CatalogTableDetailResponse(
    CatalogTableEntry("tpch1", "region", 5L, 1, None),
    List(CatalogColumnEntry(0, "r_regionkey", "INTEGER", false, false)),
    List(CatalogDataFileEntry("s3://lake/tpch1/region.parquet", 2048L, 5L, 1L))
  )

  private val stubReader: DuckLakeCatalogReader =
    new DuckLakeCatalogReader(null):
      override def getTable(schema: String, table: String, asOf: Option[Long] = None) =
        if schema == "tpch1" && table == "region" then Some(stubDetail) else None
      override def maxSnapshotId(): Option[Long] = Some(5L)

  /** Executor answering every statement with a fresh N-row TestArrow reader. */
  private def rangeExecutor(rows: Int): CatalogPreviewHandlers.PreviewExecutor =
    (_, _, _) =>
      IO.pure(
        Right(
          ai.starlake.quack.edge.QueryResult(
            TestArrow.readerFor(s"SELECT * FROM range($rows) t(x)"),
            () => (),
            "n1",
            5L
          )
        )
      )

  private def fixture(
      executor: CatalogPreviewHandlers.PreviewExecutor,
      cfg: McpConfig = McpConfig()
  ): McpDataTools =
    val store   = new InMemoryControlPlaneStore()
    val backend = ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend.noop()
    val tracker = new ai.starlake.quack.edge.adapter.NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, store)
    sup.createTenant(ai.starlake.quack.model.Tenant(Tenant)).unsafeRunSync()
    sup.createTenantDb(Tenant, TenantDb, TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup
      .createPool(
        PoolKey(Tenant, TenantDb, "sales"),
        ai.starlake.quack.model.RoleDistribution(0, 0, 1)
      )
      .unsafeRunSync()

    val scopeOf: String => Option[SessionScope] =
      t =>
        if t == patToken then Some(SessionScope(superuser = false, manageableTenants = Set(Tenant)))
        else None

    val catalog = new CatalogHandlers((_, _) => stubReader, sup, store)
    val history = new CatalogHistoryHandlers((_, _) => stubReader, sup)
    val tags    = new TagHandlers(
      sup,
      store,
      snapshotExists = (_, _, _) => true,
      snapshotsExist = (_, _, ids) => ids
    )
    val tenantDbs =
      new TenantDbHandlers(sup, federatedStore = None, catalog = None, requireEncryption = false)
    val profile = new ProfileHandlers(
      _ => None,
      NoopTelemetryStore,
      new StatementHistoryStore(),
      _ => None
    )
    new McpDataTools(cfg, executor, sup, catalog, history, tags, tenantDbs, profile, scopeOf)

  // ------------------------------------------------------------------
  // Iceberg fixture (Task 8): a minimal real Arrow-backed NodeMetadataQuery stub, reusing the
  // approach of IcebergCatalogHandlersSpec rather than inventing a new harness.
  // ------------------------------------------------------------------

  private val IcebergAlias      = "ice"
  private val IcebergSchema     = "probe"
  private val IcebergTable      = "t"
  private val IcebergSnapshotId = "1234567890123456789" // beyond JS's 2^53, still a valid Long

  private enum AK:
    case S, L, B

  /** A real Arrow IPC stream reader over `cols`/`rows`, matching the shape
    * `ArrowRowsDecoder.decode` expects -- built the same way IcebergCatalogHandlersSpec's fixture
    * builds its canned node responses.
    */
  private def arrowReader(cols: List[(String, AK)], rows: List[List[Option[Any]]]): ArrowReader =
    val allocator = new RootAllocator()
    val fields    = cols.map {
      case (n, AK.S) => Field.nullable(n, new ArrowType.Utf8())
      case (n, AK.L) => Field.nullable(n, new ArrowType.Int(64, true))
      case (n, AK.B) => Field.nullable(n, new ArrowType.Bool())
    }
    val schema = new Schema(java.util.List.of(fields*))
    val root   = VectorSchemaRoot.create(schema, allocator)
    val out    = new ByteArrayOutputStream()
    val writer = new ArrowStreamWriter(root, null, out)
    writer.start()
    root.allocateNew()
    rows.zipWithIndex.foreach { (row, i) =>
      cols.zip(row).foreach { case ((name, kind), cell) =>
        (kind, cell) match
          case (AK.S, Some(v)) =>
            root
              .getVector(name)
              .asInstanceOf[VarCharVector]
              .setSafe(i, v.toString.getBytes("UTF-8"))
          case (AK.S, None)    => root.getVector(name).asInstanceOf[VarCharVector].setNull(i)
          case (AK.L, Some(v)) =>
            root.getVector(name).asInstanceOf[BigIntVector].setSafe(i, v.asInstanceOf[Long])
          case (AK.L, None)    => root.getVector(name).asInstanceOf[BigIntVector].setNull(i)
          case (AK.B, Some(v)) =>
            root
              .getVector(name)
              .asInstanceOf[BitVector]
              .setSafe(i, if v.asInstanceOf[Boolean] then 1 else 0)
          case (AK.B, None) => root.getVector(name).asInstanceOf[BitVector].setNull(i)
      }
    }
    root.setRowCount(rows.length)
    writer.writeBatch()
    writer.end()
    writer.close()
    root.close()
    new ArrowStreamReader(new ByteArrayInputStream(out.toByteArray), allocator)

  private def icebergSnapshotsReader(): ArrowReader = arrowReader(
    List(
      "snapshot_id"  -> AK.S,
      "parent_id"    -> AK.S,
      "seq"          -> AK.L,
      "ts_ms"        -> AK.L,
      "summary_json" -> AK.S,
      "is_current"   -> AK.B
    ),
    List(
      List(
        Some(IcebergSnapshotId),
        None,
        Some(1L),
        Some(1700000000000L),
        Some("""{"operation":"append"}"""),
        Some(true)
      )
    )
  )

  private def icebergColumnsReader(): ArrowReader = arrowReader(
    List("column_name" -> AK.S, "column_type" -> AK.S, "null" -> AK.S),
    List(List(Some("id"), Some("BIGINT"), Some("YES")))
  )

  private def icebergFilesReader(): ArrowReader = arrowReader(
    List(
      "file_path"                -> AK.S,
      "content"                  -> AK.S,
      "file_format"              -> AK.S,
      "record_count"             -> AK.L,
      "manifest_sequence_number" -> AK.L
    ),
    List(List(Some("s3://w/a.parquet"), Some("DATA"), Some("PARQUET"), Some(3L), Some(1L)))
  )

  /** Answers `iceberg_load_table_response` (snapshots), `iceberg_metadata` (files) and `DESCRIBE`
    * (columns) by substring match on the SQL, exactly like the respond() dispatch in
    * IcebergCatalogHandlersSpec.
    */
  private def icebergMeta(
      metaSqls: ListBuffer[String] = ListBuffer.empty
  ): NodeMetadataQuery =
    val node = RunningNode(
      nodeId = "n1",
      poolKey = PoolKey(Tenant, TenantDb, "sales"),
      role = Role.ReadOnly,
      host = "127.0.0.1",
      port = 21900,
      token = "tok",
      pid = None,
      podName = None,
      startedAt = Instant.EPOCH
    )
    new NodeMetadataQuery(
      readPool = (_, _) => Some(PoolKey(Tenant, TenantDb, "sales")),
      readNodes = _ => List(node),
      isAttached = (_, _) => true,
      attachSummary = (_, _) => None,
      send = (_, sql) =>
        IO {
          metaSqls += sql
          if sql.contains("iceberg_load_table_response") then
            QuackResponse.Ok(icebergSnapshotsReader(), 0L, () => ())
          else if sql.contains("iceberg_metadata") then
            QuackResponse.Ok(icebergFilesReader(), 0L, () => ())
          else if sql.contains("DESCRIBE") then
            QuackResponse.Ok(icebergColumnsReader(), 0L, () => ())
          else sys.error(s"unexpected metadata sql: $sql")
        },
      timeoutSec = 5,
      maxRows = 10000
    )

  /** `McpDataTools` wired with a stub `IcebergCatalogHandlers` exposing one enabled `ice` alias on
    * `acme_default`; `previewExec` answers the routed preview/sample query.
    */
  private def icebergFixture(
      previewExec: CatalogPreviewHandlers.PreviewExecutor,
      metaSqls: ListBuffer[String] = ListBuffer.empty,
      sampleTimeout: FiniteDuration = 30.seconds
  ): McpDataTools =
    val store   = new InMemoryControlPlaneStore()
    val backend = StubQuackBackend.noop()
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, store)
    sup.createTenant(ai.starlake.quack.model.Tenant(Tenant)).unsafeRunSync()
    sup.createTenantDb(Tenant, TenantDb, TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup
      .createPool(
        PoolKey(Tenant, TenantDb, "sales"),
        ai.starlake.quack.model.RoleDistribution(0, 0, 1)
      )
      .unsafeRunSync()

    val tdId    = sup.findTenantDb(Tenant, TenantDb).get.id
    val sources = Map(
      tdId -> List(
        FederatedSource("s1", tdId, IcebergAlias, sourceType = FederatedSourceType.IcebergRest)
      )
    )

    val icebergHandlers = new IcebergCatalogHandlers(
      sup,
      sourcesOf = id => sources.getOrElse(id, Nil),
      meta = icebergMeta(metaSqls),
      executor = previewExec,
      callerOf = RestCaller.staticOnly,
      cfg = CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30)
    )

    // Fail-closed like Main's mcpScopeOf: only the static key (none here) is "unrestricted";
    // every other token resolves to its own scope, or to NoAccess when unknown.
    val scopeOf: String => Option[SessionScope] = SessionScope.failClosed(
      None,
      Map(
        patToken       -> SessionScope(superuser = false, manageableTenants = Set(Tenant)),
        NonAdminPatTok -> SessionScope.NoAccess
      ).get
    )
    val catalog = new CatalogHandlers((_, _) => stubReader, sup, store)
    val history = new CatalogHistoryHandlers((_, _) => stubReader, sup)
    val tags    = new TagHandlers(
      sup,
      store,
      snapshotExists = (_, _, _) => true,
      snapshotsExist = (_, _, ids) => ids
    )
    val tenantDbs =
      new TenantDbHandlers(sup, federatedStore = None, catalog = None, requireEncryption = false)
    val profile = new ProfileHandlers(
      _ => None,
      NoopTelemetryStore,
      new StatementHistoryStore(),
      _ => None
    )
    new McpDataTools(
      McpConfig(),
      previewExec,
      sup,
      catalog,
      history,
      tags,
      tenantDbs,
      profile,
      scopeOf,
      iceberg = Some(icebergHandlers),
      sampleTimeout = sampleTimeout
    )

  private def call(
      tools: McpDataTools,
      name: String,
      principal: McpPrincipal,
      args: (String, Json)*
  ): Either[String, Json] =
    tools.tools
      .find(_.name == name)
      .getOrElse(fail(s"tool $name not defined"))
      .run(principal, JsonObject(args*))
      .unsafeRunSync()

  // ------------------------------------------------------------------
  // run_sql
  // ------------------------------------------------------------------

  "run_sql" should "return columns, rows and truncated=false under the cap" in {
    val tools = fixture(rangeExecutor(3))
    val out   = call(
      tools,
      "run_sql",
      McpPrincipal.StaticKey,
      "sql"      -> Json.fromString("SELECT * FROM t"),
      "database" -> Json.fromString(TenantDb),
      "tenant"   -> Json.fromString(Tenant)
    )
    val json = out.toOption.getOrElse(fail(s"expected Right, got $out"))
    json.hcursor.downField("columns").as[List[Json]].toOption.get should have size 1
    json.hcursor.downField("rows").as[List[Json]].toOption.get should have size 3
    json.hcursor.get[Boolean]("truncated").toOption shouldBe Some(false)
    json.hcursor.get[String]("nodeId").toOption shouldBe Some("n1")
  }

  it should "clamp max_rows to the server cap" in {
    val tools = fixture(rangeExecutor(10), cfg = McpConfig(maxRows = 3))
    val out   = call(
      tools,
      "run_sql",
      McpPrincipal.StaticKey,
      "sql"      -> Json.fromString("SELECT * FROM t"),
      "database" -> Json.fromString(TenantDb),
      "tenant"   -> Json.fromString(Tenant),
      // Above the server cap on purpose: the arg can only lower the cap, never raise it.
      "max_rows" -> Json.fromInt(100)
    )
    val json = out.toOption.getOrElse(fail(s"expected Right, got $out"))
    json.hcursor.downField("rows").as[List[Json]].toOption.get should have size 3
    json.hcursor.get[Boolean]("truncated").toOption shouldBe Some(true)
  }

  it should "surface an ACL denial with the validator's reason text" in {
    val denied: CatalogPreviewHandlers.PreviewExecutor =
      (_, _, _) => IO.pure(Left(RouterFailure.AccessDenied("missing RW grant on tpch1.region")))
    val tools = fixture(denied)
    val out   = call(
      tools,
      "run_sql",
      McpPrincipal.StaticKey,
      "sql"      -> Json.fromString("DELETE FROM region"),
      "database" -> Json.fromString(TenantDb),
      "tenant"   -> Json.fromString(Tenant)
    )
    out.isLeft shouldBe true
    out.swap.toOption.get should include("missing RW grant on tpch1.region")
  }

  it should "translate a resuming-pool Unavailable into an agent-actionable retry message" in {
    val resuming: CatalogPreviewHandlers.PreviewExecutor =
      (_, _, _) => IO.pure(Left(RouterFailure.Unavailable("pool is resuming; no node yet")))
    val tools = fixture(resuming)
    val out   = call(
      tools,
      "run_sql",
      McpPrincipal.StaticKey,
      "sql"      -> Json.fromString("SELECT 1"),
      "database" -> Json.fromString(TenantDb),
      "tenant"   -> Json.fromString(Tenant)
    )
    out.swap.toOption.get should include("retry")
  }

  it should "translate a node-startup connect failure into a retry message" in {
    val starting: CatalogPreviewHandlers.PreviewExecutor =
      (_, _, _) =>
        IO.pure(Left(RouterFailure.Internal("permanent failure: java.net.ConnectException")))
    val tools = fixture(starting)
    val out   = call(
      tools,
      "run_sql",
      McpPrincipal.StaticKey,
      "sql"      -> Json.fromString("SELECT 1"),
      "database" -> Json.fromString(TenantDb),
      "tenant"   -> Json.fromString(Tenant)
    )
    out.swap.toOption.get should include("retry")
  }

  // ------------------------------------------------------------------
  // caller attribution (Task 8): callerFor threads the acting token id
  // ------------------------------------------------------------------

  /** Executor that records the `ExecCaller` it was invoked with, then answers with an empty result
    * set -- used to inspect what `callerFor` built without touching the router.
    */
  private def capturingExecutor(
      captured: scala.collection.mutable.ListBuffer[ai.starlake.quack.ondemand.api.ExecCaller]
  ): CatalogPreviewHandlers.PreviewExecutor =
    (caller, _, _) =>
      captured += caller
      IO.pure(
        Right(ai.starlake.quack.edge.QueryResult(TestArrow.oneRowReader(), () => (), "n1", 1L))
      )

  it should "populate ExecCaller.patId from a PAT principal" in {
    val captured =
      scala.collection.mutable.ListBuffer.empty[ai.starlake.quack.ondemand.api.ExecCaller]
    val tools = fixture(capturingExecutor(captured))
    call(
      tools,
      "run_sql",
      patFor(Some(Tenant), admin = false),
      "sql"      -> Json.fromString("SELECT 1"),
      "database" -> Json.fromString(TenantDb),
      "tenant"   -> Json.fromString(Tenant)
    )
    captured.map(_.patId).toList shouldBe List(Some("pat-1"))
  }

  it should "leave ExecCaller.patId at None for the static key" in {
    val captured =
      scala.collection.mutable.ListBuffer.empty[ai.starlake.quack.ondemand.api.ExecCaller]
    val tools = fixture(capturingExecutor(captured))
    call(
      tools,
      "run_sql",
      McpPrincipal.StaticKey,
      "sql"      -> Json.fromString("SELECT 1"),
      "database" -> Json.fromString(TenantDb),
      "tenant"   -> Json.fromString(Tenant)
    )
    captured.map(_.patId).toList shouldBe List(None)
  }

  // ------------------------------------------------------------------
  // scope enforcement (Task 6): a restricted PAT can only narrow, never widen
  // ------------------------------------------------------------------

  it should "refuse a database outside the allowlist" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(
      tools,
      "run_sql",
      scopedPat(TokenRestriction.Unrestricted.copy(databases = Some(Set("other_db")))),
      "sql"      -> Json.fromString("SELECT 1"),
      "database" -> Json.fromString(TenantDb)
    )
    out.isLeft shouldBe true
    out.swap.toOption.get should include("not permitted")
  }

  it should "refuse a pool outside the allowlist" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(
      tools,
      "run_sql",
      scopedPat(TokenRestriction.Unrestricted.copy(pools = Some(Set("other_pool")))),
      "sql"      -> Json.fromString("SELECT 1"),
      "database" -> Json.fromString(TenantDb),
      "pool"     -> Json.fromString("sales")
    )
    out.isLeft shouldBe true
    out.swap.toOption.get should include("not permitted")
  }

  /** Executor standing in for `Main.routedExecutor`'s pool-scope gate: refuses on the RESOLVED
    * `PoolKey`, exactly like the production fix, so a test can prove the omitted-`pool`-argument
    * path (where `McpDataTools.allowedPool`'s early check never fires, because there is no argument
    * to check) is still caught once the pool is resolved and handed to the executor.
    */
  private def poolScopeAwareExecutor(rows: Int): CatalogPreviewHandlers.PreviewExecutor =
    (caller, poolKey, _) =>
      if !caller.restriction.allowsPool(poolKey.pool) then
        IO.pure(
          Left(
            RouterFailure.AccessDenied(s"pool '${poolKey.pool}' is not permitted for this token")
          )
        )
      else
        IO.pure(
          Right(
            ai.starlake.quack.edge.QueryResult(
              TestArrow.readerFor(s"SELECT * FROM range($rows) t(x)"),
              () => (),
              "n1",
              5L
            )
          )
        )

  it should "refuse a token scoped away from the resolved pool even when 'pool' is omitted" in {
    // `sales` is the only pool wired up by `fixture`, so `poolKeyFor`'s `None` branch
    // (PoolPicks.readPoolKey) resolves to it with no `pool` argument in play at all -- the
    // exact shape of the bug this fix closes.
    val tools = fixture(poolScopeAwareExecutor(1))
    val out   = call(
      tools,
      "run_sql",
      scopedPat(TokenRestriction.Unrestricted.copy(pools = Some(Set("other_pool")))),
      "sql"      -> Json.fromString("SELECT 1"),
      "database" -> Json.fromString(TenantDb)
      // no "pool" argument
    )
    out.isLeft shouldBe true
    out.swap.toOption.get should include("not permitted")
  }

  it should "allow a database inside the allowlist" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(
      tools,
      "run_sql",
      scopedPat(TokenRestriction.Unrestricted.copy(databases = Some(Set(TenantDb)))),
      "sql"      -> Json.fromString("SELECT 1"),
      "database" -> Json.fromString(TenantDb)
    )
    out.isRight shouldBe true
  }

  it should "lower the row cap via the token's maxRows, never raise it" in {
    val tools = fixture(rangeExecutor(10), cfg = McpConfig(maxRows = 8))
    val out   = call(
      tools,
      "run_sql",
      scopedPat(TokenRestriction.Unrestricted.copy(maxRows = Some(2))),
      "sql"      -> Json.fromString("SELECT 1"),
      "database" -> Json.fromString(TenantDb),
      // Above both the token cap and the server cap on purpose.
      "max_rows" -> Json.fromInt(100)
    )
    val json = out.toOption.getOrElse(fail(s"expected Right, got $out"))
    json.hcursor.downField("rows").as[List[Json]].toOption.get should have size 2
    json.hcursor.get[Boolean]("truncated").toOption shouldBe Some(true)
  }

  "list_tables" should "refuse a database outside the allowlist too" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(
      tools,
      "list_tables",
      scopedPat(TokenRestriction.Unrestricted.copy(databases = Some(Set("other_db")))),
      "database" -> Json.fromString(TenantDb)
    )
    out.isLeft shouldBe true
    out.swap.toOption.get should include("not permitted")
  }

  "describe_table" should "lower its sample preview via the token's maxRows too" in {
    // SampleRows is 5; a token capped at 2 must not see more than that in the preview,
    // even though describe_table never mentions max_rows as an argument.
    val tools = fixture(rangeExecutor(10))
    val out   = call(
      tools,
      "describe_table",
      scopedPat(TokenRestriction.Unrestricted.copy(maxRows = Some(2))),
      "database" -> Json.fromString(TenantDb),
      "schema"   -> Json.fromString("tpch1"),
      "table"    -> Json.fromString("region"),
      "tenant"   -> Json.fromString(Tenant)
    )
    val json = out.toOption.getOrElse(fail(s"expected Right, got $out"))
    json.hcursor.downField("sample").downField("rows").as[List[Json]].toOption.get should
      have size 2
  }

  // ------------------------------------------------------------------
  // list_databases (Task 6 fix-up): the allowlist must hide names, not just refuse queries
  // ------------------------------------------------------------------

  private def listedDbNames(out: Either[String, Json]): List[String] =
    out.toOption
      .flatMap(_.hcursor.downField("tenantDbs").as[List[Json]].toOption)
      .getOrElse(Nil)
      .flatMap(_.hcursor.get[String]("name").toOption)

  "list_databases" should "list everything when the axis is unrestricted" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(tools, "list_databases", scopedPat(TokenRestriction.Unrestricted))
    listedDbNames(out) should contain(TenantDb)
  }

  it should "list nothing when the allowlist is empty" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(
      tools,
      "list_databases",
      scopedPat(TokenRestriction.Unrestricted.copy(databases = Some(Set.empty)))
    )
    listedDbNames(out) shouldBe Nil
  }

  it should "hide a database outside the allowlist" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(
      tools,
      "list_databases",
      scopedPat(TokenRestriction.Unrestricted.copy(databases = Some(Set("other_db"))))
    )
    listedDbNames(out) shouldBe Nil
  }

  it should "still list a database inside the allowlist" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(
      tools,
      "list_databases",
      scopedPat(TokenRestriction.Unrestricted.copy(databases = Some(Set(TenantDb))))
    )
    listedDbNames(out) shouldBe List(TenantDb)
  }

  // ------------------------------------------------------------------
  // tenant inference
  // ------------------------------------------------------------------

  it should "infer the tenant from a tenant-scoped PAT" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(
      tools,
      "run_sql",
      patFor(Some(Tenant), admin = true),
      "sql"      -> Json.fromString("SELECT 1"),
      "database" -> Json.fromString(TenantDb)
    )
    out.isRight shouldBe true
  }

  it should "refuse an explicit tenant argument that differs from the PAT's tenant" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(
      tools,
      "run_sql",
      patFor(Some(Tenant), admin = true),
      "sql"      -> Json.fromString("SELECT 1"),
      "database" -> Json.fromString(TenantDb),
      "tenant"   -> Json.fromString("globex")
    )
    out.swap.toOption.get should include(Tenant)
  }

  it should "require an explicit tenant for the static key, naming the argument" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(
      tools,
      "run_sql",
      McpPrincipal.StaticKey,
      "sql"      -> Json.fromString("SELECT 1"),
      "database" -> Json.fromString(TenantDb)
    )
    out.swap.toOption.get should include("tenant")
  }

  // ------------------------------------------------------------------
  // describe_table
  // ------------------------------------------------------------------

  "describe_table" should "compose catalog columns with a decoded sample" in {
    val tools = fixture(rangeExecutor(2))
    val out   = call(
      tools,
      "describe_table",
      McpPrincipal.StaticKey,
      "database" -> Json.fromString(TenantDb),
      "schema"   -> Json.fromString("tpch1"),
      "table"    -> Json.fromString("region"),
      "tenant"   -> Json.fromString(Tenant)
    )
    val json = out.toOption.getOrElse(fail(s"expected Right, got $out"))
    val cols = json.hcursor.downField("columns").as[List[Json]].toOption.get
    cols.flatMap(_.hcursor.get[String]("name").toOption) should contain("r_regionkey")
    json.hcursor.downField("sample").downField("rows").as[List[Json]].toOption.get should
      have size 2
  }

  // ------------------------------------------------------------------
  // iceberg argument (Task 8): table_history and describe_table routed at an attached
  // iceberg_rest source instead of the DuckLake catalog.
  // ------------------------------------------------------------------

  "table_history" should "route to the iceberg handlers and return string snapshot ids" in {
    val tools = icebergFixture(rangeExecutor(1))
    val out   = call(
      tools,
      "table_history",
      McpPrincipal.StaticKey,
      "database" -> Json.fromString(TenantDb),
      "schema"   -> Json.fromString(IcebergSchema),
      "table"    -> Json.fromString(IcebergTable),
      "tenant"   -> Json.fromString(Tenant),
      "iceberg"  -> Json.fromString(IcebergAlias)
    )
    val json  = out.toOption.getOrElse(fail(s"expected Right, got $out"))
    val snaps = json.hcursor.downField("snapshots").as[List[Json]].toOption.get
    snaps should have size 1
    val idField = snaps.head.hcursor.downField("snapshotId")
    idField.as[String].toOption shouldBe Some(IcebergSnapshotId)
    // A JSON string, not a bare number: the id is beyond JavaScript's 2^53.
    idField.focus.get.isString shouldBe true
  }

  it should "answer the iceberg-unavailable error when handlers are not wired" in {
    val tools = fixture(rangeExecutor(1)) // no iceberg handlers: constructor default None
    val out   = call(
      tools,
      "table_history",
      McpPrincipal.StaticKey,
      "database" -> Json.fromString(TenantDb),
      "schema"   -> Json.fromString("tpch1"),
      "table"    -> Json.fromString("region"),
      "tenant"   -> Json.fromString(Tenant),
      "iceberg"  -> Json.fromString(IcebergAlias)
    )
    out shouldBe Left("iceberg catalogs are not available on this manager")
  }

  // No "iceberg omitted" case against the real `history.history` DuckLake path here: this
  // fixture's `stubReader` only overrides `getTable`/`maxSnapshotId` (what describe_table and
  // list_tables need), not `listTableHistory`, and no test in this file exercised table_history
  // before Task 8 -- wiring that up is outside this task's scope. The `case None =>` arm added
  // below is the pre-existing `history.history(...)` call, byte-for-byte, just moved under the
  // branch; describe_table's own "iceberg omitted" behavior is covered by the existing
  // "describe_table should compose catalog columns with a decoded sample" test above.

  "describe_table" should "compose iceberg detail with a 5-row sample when 'iceberg' is given" in {
    val tools = icebergFixture(rangeExecutor(10))
    val out   = call(
      tools,
      "describe_table",
      McpPrincipal.StaticKey,
      "database" -> Json.fromString(TenantDb),
      "schema"   -> Json.fromString(IcebergSchema),
      "table"    -> Json.fromString(IcebergTable),
      "tenant"   -> Json.fromString(Tenant),
      "iceberg"  -> Json.fromString(IcebergAlias)
    )
    val json = out.toOption.getOrElse(fail(s"expected Right, got $out"))
    json.hcursor.downField("table").downField("alias").as[String].toOption shouldBe
      Some(IcebergAlias)
    val cols = json.hcursor.downField("columns").as[List[Json]].toOption.get
    cols.flatMap(_.hcursor.get[String]("name").toOption) should contain("id")
    json.hcursor.downField("sample").downField("rows").as[List[Json]].toOption.get should
      have size 5
  }

  /** A tenant-acme admin PAT whose own maxRows (2) is below the sample cap. */
  private val icebergPat: McpPrincipal =
    new McpPrincipal.Pat(
      PatPrincipal(
        user = RbacUser(id = "u1", tenant = Some(Tenant), username = "alice", role = "admin"),
        patId = "pat-1",
        scope = SessionScope(superuser = false, manageableTenants = Set(Tenant)),
        isAdmin = true,
        restriction = TokenRestriction.Unrestricted.copy(maxRows = Some(2))
      ),
      patToken
    )

  private def describeIcebergIO(tools: McpDataTools, principal: McpPrincipal) =
    // Only a superuser credential names the tenant; a PAT infers it.
    val tenantArg =
      if principal == McpPrincipal.StaticKey then List("tenant" -> Json.fromString(Tenant))
      else Nil
    val args = List(
      "database" -> Json.fromString(TenantDb),
      "schema"   -> Json.fromString(IcebergSchema),
      "table"    -> Json.fromString(IcebergTable),
      "iceberg"  -> Json.fromString(IcebergAlias)
    ) ++ tenantArg
    tools.tools
      .find(_.name == "describe_table")
      .getOrElse(fail("tool describe_table not defined"))
      .run(principal, JsonObject(args*))

  private def describeIceberg(tools: McpDataTools, principal: McpPrincipal) =
    describeIcebergIO(tools, principal).unsafeRunSync()

  it should "run the iceberg sample as the PAT's owner with its restriction, never system" in {
    val seen = scala.collection.mutable.ListBuffer.empty[ExecCaller]
    val sqls = scala.collection.mutable.ListBuffer.empty[String]
    val recording: CatalogPreviewHandlers.PreviewExecutor = (caller, key, sql) =>
      seen += caller
      sqls += sql
      rangeExecutor(10)(caller, key, sql)
    val out  = describeIceberg(icebergFixture(recording), icebergPat)
    val json = out.toOption.getOrElse(fail(s"expected Right, got $out"))
    seen.toList.map(c => (c.identity, c.restriction.maxRows, c.patId, c.system)) shouldBe
      List(("alice", Some(2), Some("pat-1"), false))
    // The PAT's maxRows lowers the sample: one row past the cap is fetched, the cap is returned.
    sqls.toList shouldBe List(
      s"""SELECT * FROM "$IcebergAlias"."$IcebergSchema"."$IcebergTable" LIMIT 3"""
    )
    json.hcursor.downField("sample").downField("rows").as[List[Json]].toOption.get should
      have size 2
    json.hcursor.downField("sample").get[Boolean]("truncated").toOption shouldBe Some(true)
  }

  it should "degrade to the iceberg detail alone when the sample is denied or fails" in {
    val denied: CatalogPreviewHandlers.PreviewExecutor =
      (_, _, _) => IO.pure(Left(RouterFailure.AccessDenied("no grant on ice.probe.t")))
    val boom: CatalogPreviewHandlers.PreviewExecutor =
      (_, _, _) => IO.raiseError(new java.util.concurrent.TimeoutException("slow node"))
    for exec <- List(denied, boom); principal <- List(McpPrincipal.StaticKey, icebergPat) do
      val out  = describeIceberg(icebergFixture(exec), principal)
      val json = out.toOption.getOrElse(fail(s"expected Right, got $out"))
      json.hcursor.downField("table").downField("alias").as[String].toOption shouldBe
        Some(IcebergAlias)
      json.hcursor.downField("columns").as[List[Json]].toOption.get should not be empty
      json.hcursor.downField("sample").focus shouldBe None
  }

  it should "degrade to the iceberg detail alone when the sample never completes" in {
    val never: CatalogPreviewHandlers.PreviewExecutor = (_, _, _) => IO.never
    val tools = icebergFixture(never, sampleTimeout = 200.millis)
    val out   = describeIcebergIO(tools, icebergPat).timeout(10.seconds).unsafeRunSync()
    val json  = out.toOption.getOrElse(fail(s"expected Right, got $out"))
    json.hcursor.downField("columns").as[List[Json]].toOption.get should not be empty
    json.hcursor.downField("sample").focus shouldBe None
  }

  /** A tenant-scoped, NON-admin PAT: its scope manages no tenant. */
  private val nonAdminPat: McpPrincipal =
    new McpPrincipal.Pat(
      PatPrincipal(
        user = RbacUser(id = "u2", tenant = Some(Tenant), username = "bob", role = "user"),
        patId = "pat-2",
        scope = SessionScope.NoAccess,
        isAdmin = false,
        restriction = TokenRestriction.Unrestricted
      ),
      NonAdminPatTok
    )

  "the iceberg tools" should "refuse a non-admin PAT before any metadata read or statement" in {
    val metaSqls                                     = ListBuffer.empty[String]
    val execCalls                                    = ListBuffer.empty[String]
    val exec: CatalogPreviewHandlers.PreviewExecutor = (caller, key, sql) =>
      execCalls += sql
      rangeExecutor(10)(caller, key, sql)
    val tools  = icebergFixture(exec, metaSqls)
    val common = List(
      "database" -> Json.fromString(TenantDb),
      "schema"   -> Json.fromString(IcebergSchema),
      "table"    -> Json.fromString(IcebergTable),
      "iceberg"  -> Json.fromString(IcebergAlias)
    )
    for tool <- List("describe_table", "table_history") do
      val out = call(tools, tool, nonAdminPat, common*)
      out.swap.toOption.getOrElse(fail(s"$tool: expected Left, got $out")) should
        include("tenant_forbidden")
    metaSqls shouldBe empty
    execCalls shouldBe empty
  }

  "describe_table" should "answer the iceberg-unavailable error when handlers are not wired" in {
    val tools = fixture(rangeExecutor(10))
    val out   = call(
      tools,
      "describe_table",
      McpPrincipal.StaticKey,
      "database" -> Json.fromString(TenantDb),
      "schema"   -> Json.fromString(IcebergSchema),
      "table"    -> Json.fromString(IcebergTable),
      "tenant"   -> Json.fromString(Tenant),
      "iceberg"  -> Json.fromString(IcebergAlias)
    )
    out shouldBe Left("iceberg catalogs are not available on this manager")
  }

  // ------------------------------------------------------------------
  // my_usage
  // ------------------------------------------------------------------

  "my_usage" should "refuse the static key (no identity to scope by)" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(tools, "my_usage", McpPrincipal.StaticKey)
    out.swap.toOption.get should include("PAT")
  }
