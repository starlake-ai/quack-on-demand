package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{NodeSpec, RunningNode, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.time.Instant

class CatalogHandlersSpec extends AnyFlatSpec with Matchers:

  // Key-less caller (open mode): TenantScopeCheck admits, the gate still
  // resolves tenant + tenant-db against the supervisor.
  private val NoKey: Option[String]              = None
  private val NoScope: String => Option[Nothing] = _ => None

  private def stubBackend: QuackBackend = new QuackBackend:
    def start(s: NodeSpec): IO[RunningNode] =
      IO.pure(
        RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          21000,
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
    var seenAsOf: Option[Long]   = None
    var seenLimit: Option[Int]   = None
    var seenBefore: Option[Long] = None

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
        List(snapshot)
      override def getTable(schema: String, table: String, asOf: Option[Long] = None) =
        seenAsOf = asOf
        if schema == "tpch1" && table == "region" then
          Some(CatalogTableDetailResponse(region, List(column), List(file)))
        else None
      override def snapshotExists(id: Long): Boolean = id == 3L
      override def maxSnapshotId(): Option[Long] = Some(3L)
      override def snapshotAtOrBefore(ts: java.time.Instant): Option[Long] = Some(3L)

    val (sup, store)  = supervisor()
    val handlers = new CatalogHandlers((_, _) => reader, sup, store)

    def getTable(table: String, asOf: Option[Long] = None) =
      handlers.getTable("acme", "acme_default", "tpch1", table, asOf, None, None, NoKey)(NoScope)

  "listSchemas" should "return what the reader returns" in new Stubs:
    handlers.listSchemas("acme", "acme_default", NoKey)(NoScope) shouldBe Right(schemas)

  "listSchemas" should "404 an unknown tenant" in new Stubs:
    val out = handlers.listSchemas("nope", "acme_default", NoKey)(NoScope)
    out.left.toOption.get._1 shouldBe StatusCode.NotFound

  "listSchemas" should "404 an unknown tenant-db" in new Stubs:
    val out = handlers.listSchemas("acme", "ghost_db", NoKey)(NoScope)
    out.left.toOption.get._1 shouldBe StatusCode.NotFound

  "listTables" should "filter by schema" in new Stubs:
    handlers.listTables("acme", "acme_default", "tpch1", NoKey)(NoScope) shouldBe
      Right(List(region))
    handlers.listTables("acme", "acme_default", "main", NoKey)(NoScope) shouldBe Right(Nil)

  "getTable" should "return the detail when present" in new Stubs:
    val detail = getTable("region").toOption.get
    detail.columns shouldBe List(column)
    detail.dataFiles shouldBe List(file)

  "getTable" should "404 when missing" in new Stubs:
    getTable("ghost").left.toOption.get._1 shouldBe StatusCode.NotFound

  "listSnapshots" should "return what the reader returns" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", None, None, NoKey)(NoScope) shouldBe
      Right(List(snapshot))

  "listSnapshots" should "return Nil for non-DuckLake tenant-dbs" in new Stubs:
    val gated =
      new CatalogHandlers((_, _) => reader, sup, store, (_, _) => Some(TenantDbKind.InMemory))
    gated.listSnapshots("acme", "acme_default", None, None, NoKey)(NoScope) shouldBe Right(Nil)

  "listSnapshots" should "default limit to 200 when None" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", None, None, NoKey)(NoScope)
    seenLimit shouldBe Some(200)
    seenBefore shouldBe None

  "listSnapshots" should "clamp limit 5000 to 1000" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", Some(5000), None, NoKey)(NoScope)
    seenLimit shouldBe Some(1000)

  "listSnapshots" should "clamp limit 0 to 1" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", Some(0), None, NoKey)(NoScope)
    seenLimit shouldBe Some(1)

  "listSnapshots" should "pass before through unchanged" in new Stubs:
    handlers.listSnapshots("acme", "acme_default", Some(10), Some(42L), NoKey)(NoScope)
    seenLimit shouldBe Some(10)
    seenBefore shouldBe Some(42L)

  "getTable" should "pass asOf through to the reader" in new Stubs:
    getTable("region", asOf = Some(3L))
    seenAsOf shouldBe Some(3L)
