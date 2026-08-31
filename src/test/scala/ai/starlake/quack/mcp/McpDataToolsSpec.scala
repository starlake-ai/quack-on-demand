// src/test/scala/ai/starlake/quack/mcp/McpDataToolsSpec.scala
package ai.starlake.quack.mcp

import ai.starlake.quack.McpConfig
import ai.starlake.quack.edge.adapter.TestArrow
import ai.starlake.quack.edge.{RouterFailure, StatementHistoryStore}
import ai.starlake.quack.edge.FlightSqlRouter
import ai.starlake.quack.model.{PoolKey, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  CatalogColumnEntry,
  CatalogDataFileEntry,
  CatalogHandlers,
  CatalogHistoryHandlers,
  CatalogPreviewHandlers,
  CatalogTableDetailResponse,
  CatalogTableEntry,
  ProfileHandlers,
  TagHandlers,
  TenantDbHandlers
}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.ondemand.telemetry.NoopTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Data-tier MCP tool contract: tenant inference, the run_sql row cap, error surfacing, and the
  * describe_table composition. Built over the in-memory supervisor + TestArrow readers; no Postgres
  * and no Flight wire.
  */
class McpDataToolsSpec extends AnyFlatSpec with Matchers:

  private val Tenant   = "acme"
  private val TenantDb = "acme_default"

  private val patToken = "qod_pat_alice"

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
    val tenantDbs = new TenantDbHandlers(sup, federatedStore = None, catalog = None)
    val profile   = new ProfileHandlers(
      _ => None,
      NoopTelemetryStore,
      new StatementHistoryStore(),
      _ => None
    )
    new McpDataTools(cfg, executor, sup, catalog, history, tags, tenantDbs, profile, scopeOf)

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
  // my_usage
  // ------------------------------------------------------------------

  "my_usage" should "refuse the static key (no identity to scope by)" in {
    val tools = fixture(rangeExecutor(1))
    val out   = call(tools, "my_usage", McpPrincipal.StaticKey)
    out.swap.toOption.get should include("PAT")
  }
