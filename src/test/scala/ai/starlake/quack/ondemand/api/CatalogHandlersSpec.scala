package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import org.scalatest.OptionValues._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CatalogHandlersSpec extends AnyFlatSpec with Matchers:

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
    var seenAsOf: Option[Long] = None

    // Subclass `DuckLakeCatalogReader` for the stub. The base class wants
    // a HikariDataSource - we pass null since none of the methods we
    // override touch it.
    val reader: DuckLakeCatalogReader = new DuckLakeCatalogReader(null):
      override def listSchemas(): List[CatalogSchemaEntry]             = schemas
      override def listTables(schema: String): List[CatalogTableEntry] =
        if schema == "tpch1" then List(region) else Nil
      override def listSnapshots(): List[CatalogSnapshotEntry] = List(snapshot)
      override def getTable(schema: String, table: String, asOf: Option[Long] = None) =
        seenAsOf = asOf
        if schema == "tpch1" && table == "region" then
          Some(CatalogTableDetailResponse(region, List(column), List(file)))
        else None

    val handlers = new CatalogHandlers((_, _) => reader)

  "listSchemas" should "return what the reader returns" in new Stubs:
    handlers.listSchemas("acme", "acme_default") shouldBe schemas

  "listTables" should "filter by schema" in new Stubs:
    handlers.listTables("acme", "acme_default", "tpch1") shouldBe List(region)
    handlers.listTables("acme", "acme_default", "main") shouldBe Nil

  "getTable" should "return Some when present" in new Stubs:
    val detail = handlers.getTable("acme", "acme_default", "tpch1", "region").value
    detail.columns shouldBe List(column)
    detail.dataFiles shouldBe List(file)

  "getTable" should "return None when missing" in new Stubs:
    handlers.getTable("acme", "acme_default", "tpch1", "ghost") shouldBe None

  "listSnapshots" should "return what the reader returns" in new Stubs:
    handlers.listSnapshots("acme", "acme_default") shouldBe List(snapshot)

  "listSnapshots" should "return Nil for non-DuckLake tenant-dbs" in new Stubs:
    import ai.starlake.quack.model.TenantDbKind
    val gated = new CatalogHandlers((_, _) => reader, (_, _) => Some(TenantDbKind.InMemory))
    gated.listSnapshots("acme", "acme_default") shouldBe Nil

  "getTable" should "pass asOf through to the reader" in new Stubs:
    handlers.getTable("acme", "acme_default", "tpch1", "region", Some(3L))
    seenAsOf shouldBe Some(3L)
