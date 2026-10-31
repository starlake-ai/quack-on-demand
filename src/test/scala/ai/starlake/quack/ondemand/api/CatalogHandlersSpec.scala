package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.catalog.{DuckLakeCatalogReader, NodeCatalog, NodeCatalogFailure}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

class CatalogHandlersSpec extends AnyFlatSpec with Matchers:

  // Key-less caller over a superuser fixture: TenantScopeCheck admits, the gate still
  // resolves tenant + tenant-db against the supervisor.
  private val NoKey: Option[String]                 = None
  private val SuperuserScope: SessionScope.Resolver = _ => SessionScope.Superuser

  private def stubBackend: QuackBackend = StubQuackBackend.noop()

  /** Supervisor over a seeded in-memory store: tenant `acme` with one tenant-db `acme_default`, so
    * the handlers' gate (tenant resolve -> scope check -> tenant-db lookup) admits the fixture
    * coordinates. Kind checks go through the injectable `kindOf`, not the store row.
    */
  private def supervisor(): (PoolSupervisor, InMemoryControlPlaneStore) =
    val store = new InMemoryControlPlaneStore()
    store.upsertTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db"))
    store.upsertTenantDb(
      TenantDb(
        id = "td-default01",
        tenantId = "acme",
        name = "acme_default",
        kind = TenantDbKind.InMemory,
        metastore = Map.empty,
        dataPath = ""
      )
    )
    val sup = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
    sup.restore()
    (sup, store)

  // Stub reader the handler talks to; the resolveReader function lets us
  // control per-tenant routing without spinning up Hikari.
  trait Stubs:
    val schemas  = List(CatalogSchemaEntry("main", 0), CatalogSchemaEntry("tpch1", 1))
    val region   = CatalogTableEntry("tpch1", "region", 5L, 1, Some("s3://lake/tpch1/region/"))
    val column   = CatalogColumnEntry(0, "r_regionkey", "INTEGER", false, false)
    val file     = CatalogDataFileEntry("s3://lake/tpch1/region.parquet", 2048L, 5L, 1L)
    val snapshot = CatalogSnapshotEntry(
      snapshotId = 3L,
      committedAt = "2026-07-07T00:00:00Z",
      schemaVersion = 1L,
      changes = "inserted_into_table:1",
      rowsAdded = 5L,
      filesAdded = 1,
      filesRemoved = 0,
      affectedTables = List(CatalogTableRef("tpch1", "region"))
    )
    var seenAsOf: Option[Long]              = None
    var seenLimit: Option[Int]              = None
    var seenBefore: Option[Long]            = None
    var seenTable: Option[(String, String)] = None

    // Subclass `DuckLakeCatalogReader` for the stub. The base class wants
    // a HikariDataSource - we pass null since none of the methods we
    // override touch it.
    val reader: DuckLakeCatalogReader = new DuckLakeCatalogReader(null):
      override def listSchemas(): List[CatalogSchemaEntry]             = schemas
      override def listTables(schema: String): List[CatalogTableEntry] =
        if schema == "tpch1" then List(region) else Nil
      override def listSnapshots(
          limit: Int = 200,
          before: Option[Long] = None,
          table: Option[(String, String)] = None
      ): List[CatalogSnapshotEntry] =
        seenLimit = Some(limit)
        seenBefore = before
        seenTable = table
        List(snapshot)
      override def getTable(schema: String, table: String, asOf: Option[Long] = None) =
        seenAsOf = asOf
        if schema == "tpch1" && table == "region" then
          Some(CatalogTableDetailResponse(region, List(column), List(file)))
        else None
      override def snapshotExists(id: Long): Boolean                       = id == 3L
      override def maxSnapshotId(): Option[Long]                           = Some(3L)
      override def snapshotAtOrBefore(ts: java.time.Instant): Option[Long] = Some(3L)

    val (sup, store) = supervisor()
    val handlers     = new CatalogHandlers((_, _) => reader, sup, store)

    def getTable(table: String, asOf: Option[Long] = None) =
      handlers.getTable("acme", "acme_default", "tpch1", table, asOf, None, None, NoKey)(
        SuperuserScope
      )

  "listSchemas" should "return what the reader returns" in new Stubs:
    handlers.listSchemas("acme", "acme_default", NoKey)(SuperuserScope) shouldBe Right(schemas)

  "listSchemas" should "404 an unknown tenant" in new Stubs:
    val out = handlers.listSchemas("nope", "acme_default", NoKey)(SuperuserScope)
    out.left.toOption.get._1 shouldBe StatusCode.NotFound

  "listSchemas" should "404 an unknown tenant-db" in new Stubs:
    val out = handlers.listSchemas("acme", "ghost_db", NoKey)(SuperuserScope)
    out.left.toOption.get._1 shouldBe StatusCode.NotFound

  "listTables" should "filter by schema" in new Stubs:
    handlers.listTables("acme", "acme_default", "tpch1", NoKey)(SuperuserScope) shouldBe
      Right(List(region))
    handlers.listTables("acme", "acme_default", "main", NoKey)(SuperuserScope) shouldBe Right(Nil)

  "getTable" should "return the detail when present" in new Stubs:
    val detail = getTable("region").toOption.get
    detail.columns shouldBe List(column)
    detail.dataFiles shouldBe List(file)

  "getTable" should "404 when missing" in new Stubs:
    getTable("ghost").left.toOption.get._1 shouldBe StatusCode.NotFound

  "listSnapshots" should "return what the reader returns" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", None, None, None, NoKey)(SuperuserScope) shouldBe
      Right(List(snapshot))

  "listSnapshots" should "return Nil for non-DuckLake tenant-dbs" in new Stubs:
    val gated =
      new CatalogHandlers((_, _) => reader, sup, store, (_, _) => Some(TenantDbKind.InMemory))
    gated.listSnapshots("acme", "acme_default", None, None, None, NoKey)(
      SuperuserScope
    ) shouldBe Right(Nil)

  "listSnapshots" should "default limit to 200 when None" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", None, None, None, NoKey)(SuperuserScope)
    seenLimit shouldBe Some(200)
    seenBefore shouldBe None

  "listSnapshots" should "clamp limit 5000 to 1000" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", Some(5000), None, None, NoKey)(SuperuserScope)
    seenLimit shouldBe Some(1000)

  "listSnapshots" should "clamp limit 0 to 1" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", Some(0), None, None, NoKey)(SuperuserScope)
    seenLimit shouldBe Some(1)

  "listSnapshots" should "pass before through unchanged" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", Some(10), Some(42L), None, NoKey)(SuperuserScope)
    seenLimit shouldBe Some(10)
    seenBefore shouldBe Some(42L)

  "getTable" should "pass asOf through to the reader" in new Stubs:
    getTable("region", asOf = Some(3L))
    seenAsOf shouldBe Some(3L)

  "listSnapshots" should "parse and pass valid schema.table through" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", None, None, Some("tpch1.region"), NoKey)(
      SuperuserScope
    )
    seenTable shouldBe Some(("tpch1", "region"))

  "listSnapshots" should "ignore dotless table filter" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", None, None, Some("nodot"), NoKey)(SuperuserScope)
    seenTable shouldBe None

  "listSnapshots" should "ignore table filter with empty parts" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", None, None, Some(".suffix"), NoKey)(
      SuperuserScope
    )
    seenTable shouldBe None
    handlers.listSnapshots("acme", "acme_default", None, None, Some("prefix."), NoKey)(
      SuperuserScope
    )
    seenTable shouldBe None

  /** Records which node-catalog reads the handler delegated; `failWith` makes every read fail. */
  trait NodeStubs extends Stubs:
    var nodeCalls: List[String]              = Nil
    var failWith: Option[NodeCatalogFailure] = None
    val orders                               = CatalogTableEntry("sales", "orders", 2L, 0, None)
    val node: NodeCatalog                    = new NodeCatalog:
      private def answer[T](call: String)(v: T) =
        nodeCalls = nodeCalls :+ call
        failWith.toLeft(v)
      def listSchemas(tenant: String, tenantDb: String) =
        answer(s"schemas $tenant/$tenantDb")(List(CatalogSchemaEntry("sales", 1)))
      def listTables(tenant: String, tenantDb: String, schema: String) =
        answer(s"tables $schema")(if schema == "sales" then List(orders) else Nil)
      def getTable(tenant: String, tenantDb: String, schema: String, table: String) =
        answer(s"table $schema.$table")(
          Option.when(table == "orders")(
            CatalogTableDetailResponse(
              orders,
              List(CatalogColumnEntry(1, "id", "INTEGER", false, true)),
              Nil
            )
          )
        )
    val fileHandlers = new CatalogHandlers(
      (_, _) => reader,
      sup,
      store,
      (_, _) => Some(TenantDbKind.DuckDbFile),
      nodeCatalog = Some(node)
    )
    def fileTable(table: String, asOf: Option[Long] = None) =
      fileHandlers.getTable("acme", "acme_default", "sales", table, asOf, None, None, NoKey)(
        SuperuserScope
      )

  "a duckdb-file tenant-db" should "list schemas and tables from the node catalog" in new NodeStubs:
    fileHandlers.listSchemas("acme", "acme_default", NoKey)(SuperuserScope) shouldBe
      Right(List(CatalogSchemaEntry("sales", 1)))
    fileHandlers.listTables("acme", "acme_default", "sales", NoKey)(SuperuserScope) shouldBe
      Right(List(orders))
    nodeCalls shouldBe List("schemas acme/acme_default", "tables sales")

  it should "return the table detail from the node catalog and 404 a missing table" in new NodeStubs:
    fileTable("orders").toOption.get.columns.map(_.name) shouldBe List("id")
    fileTable("ghost").left.toOption.get._1 shouldBe StatusCode.NotFound

  it should "400 a time-travel selector without asking the node" in new NodeStubs:
    val out = fileTable("orders", asOf = Some(3L))
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_selector"
    nodeCalls shouldBe Nil

  it should "404 no_pool when the database has no pool" in new NodeStubs:
    failWith = Some(NodeCatalogFailure.NoPool)
    val out = fileHandlers.listSchemas("acme", "acme_default", NoKey)(SuperuserScope)
    out.left.toOption.get._1 shouldBe StatusCode.NotFound
    out.left.toOption.get._2.error shouldBe "no_pool"

  it should "502 catalog_unavailable on a node failure or timeout" in new NodeStubs:
    failWith = Some(NodeCatalogFailure.Failed("boom"))
    val out = fileHandlers.listTables("acme", "acme_default", "sales", NoKey)(SuperuserScope)
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "catalog_unavailable"
    failWith = Some(NodeCatalogFailure.TimedOut)
    fileTable("orders").left.toOption.get._2.error shouldBe "catalog_unavailable"

  it should "keep snapshots empty and never touch the DuckLake reader" in new NodeStubs:
    fileHandlers.listSnapshots("acme", "acme_default", None, None, None, NoKey)(
      SuperuserScope
    ) shouldBe Right(Nil)
    seenLimit shouldBe None

  it should "stay empty when no node catalog is wired" in new Stubs:
    val bare =
      new CatalogHandlers((_, _) => reader, sup, store, (_, _) => Some(TenantDbKind.DuckDbFile))
    bare.listSchemas("acme", "acme_default", NoKey)(SuperuserScope) shouldBe Right(Nil)
    bare.listTables("acme", "acme_default", "sales", NoKey)(SuperuserScope) shouldBe Right(Nil)
