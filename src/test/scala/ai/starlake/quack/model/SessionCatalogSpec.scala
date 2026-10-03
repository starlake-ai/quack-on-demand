package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins the session-catalog resolver for every kind and metastore shape. The router's
  * `ValidationContext` defaults and the REST edge's `<catalog>`/`<schema>` both come from here, so
  * a change to one of these rows changes what the ACL validates AND what the edge qualifies.
  */
class SessionCatalogSpec extends AnyFlatSpec with Matchers:

  private val lake = TenantDbKind.DuckLake.wireValue
  private val file = TenantDbKind.DuckDbFile.wireValue
  private val mem  = TenantDbKind.InMemory.wireValue

  "SessionCatalog.database" should "use catalogAlias over dbName for the attaching kinds" in:
    val meta = Map("dbName" -> "acme_br", TenantDb.CatalogAliasKey -> "acme_main")
    SessionCatalog.database(lake, meta) shouldBe Some("acme_main")
    SessionCatalog.database(file, meta) shouldBe Some("acme_main")

  it should "fall back to dbName when no catalogAlias is set" in:
    val meta = Map("dbName" -> "acme_sales")
    SessionCatalog.database(lake, meta) shouldBe Some("acme_sales")
    SessionCatalog.database(file, meta) shouldBe Some("acme_sales")

  it should "ignore an empty catalogAlias and use dbName" in:
    val meta = Map("dbName" -> "acme_sales", TenantDb.CatalogAliasKey -> "")
    SessionCatalog.database(lake, meta) shouldBe Some("acme_sales")

  it should "resolve nothing for an attaching kind whose metastore names no catalog" in:
    SessionCatalog.database(lake, Map.empty) shouldBe None
    SessionCatalog.database(file, Map("dbName" -> "")) shouldBe None

  it should "always resolve DuckDB's built-in memory catalog for the memory kind" in:
    SessionCatalog.database(mem, Map.empty) shouldBe Some("memory")
    // The effective metastore of a memory pool carries dbName=memory; an operator-set dbName or
    // alias is still not the session catalog, the node never ATTACHes anything for this kind.
    SessionCatalog.database(mem, Map("dbName" -> "other")) shouldBe Some("memory")
    SessionCatalog.database(mem, Map(TenantDb.CatalogAliasKey -> "x")) shouldBe Some("memory")

  it should "resolve nothing for an unknown kind" in:
    SessionCatalog.database("iceberg", Map("dbName" -> "acme_sales")) shouldBe None

  it should "let the tenant-db override win for every kind, known or not" in:
    for kind <- List(lake, file, mem, "iceberg") do
      SessionCatalog.database(kind, Map("dbName" -> "d"), Some("fedpg")) shouldBe Some("fedpg")

  "SessionCatalog.schema" should "use the metastore schemaName for the attaching kinds" in:
    SessionCatalog.schema(lake, Map("schemaName" -> "curated")) shouldBe Some("curated")
    SessionCatalog.schema(file, Map("schemaName" -> "curated")) shouldBe Some("curated")

  it should "resolve nothing for an attaching kind with no or an empty schemaName" in:
    SessionCatalog.schema(lake, Map.empty) shouldBe None
    SessionCatalog.schema(file, Map("schemaName" -> "")) shouldBe None

  it should "always resolve main for the memory kind" in:
    SessionCatalog.schema(mem, Map.empty) shouldBe Some("main")
    SessionCatalog.schema(mem, Map("schemaName" -> "other")) shouldBe Some("main")

  it should "resolve nothing for an unknown kind" in:
    SessionCatalog.schema("iceberg", Map("schemaName" -> "curated")) shouldBe None

  it should "let the tenant-db override win for every kind, known or not" in:
    for kind <- List(lake, file, mem, "iceberg") do
      SessionCatalog.schema(kind, Map("schemaName" -> "s"), Some("public")) shouldBe Some("public")
