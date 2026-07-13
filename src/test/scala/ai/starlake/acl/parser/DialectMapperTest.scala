package ai.starlake.acl.parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ai.starlake.acl.model.{Config, DenyReason, TableRef}
import net.sf.jsqlparser.schema.Table

class DialectMapperTest extends AnyFunSuite with Matchers {

  private def makeTable(name: String, schema: String = null, database: String = null): Table = {
    val t = new Table()
    t.setName(name): Unit
    if schema != null then t.setSchemaName(schema): Unit
    if database != null then t.setDatabaseName(database): Unit
    t
  }

  test("forConfig returns ANSI mapper for generic config") {
    val mapper = DialectMapper.forConfig(Config.forGeneric("db", "schema"))
    mapper shouldBe DialectMapper.ansi
  }

  test("forConfig returns DuckDB mapper for duckdb config") {
    val mapper = DialectMapper.forConfig(Config.forDuckDB("db", "schema"))
    mapper shouldBe DialectMapper.duckdb
  }

  test("ANSI mapper resolves fully-qualified three-part name") {
    val config = Config.forGeneric("defaultdb", "defaultschema")
    val table  = makeTable("orders", schema = "public", database = "mydb")
    val result = DialectMapper.ansi.toTableRef(table, config)
    result shouldBe Right(TableRef("mydb", "public", "orders"))
  }

  test("ANSI mapper applies default database for two-part name") {
    val config = Config.forGeneric("defaultdb", "defaultschema")
    val table  = makeTable("orders", schema = "public")
    val result = DialectMapper.ansi.toTableRef(table, config)
    result shouldBe Right(TableRef("defaultdb", "public", "orders"))
  }

  test("ANSI mapper applies both defaults for one-part name") {
    val config = Config.forGeneric("defaultdb", "defaultschema")
    val table  = makeTable("orders")
    val result = DialectMapper.ansi.toTableRef(table, config)
    result shouldBe Right(TableRef("defaultdb", "defaultschema", "orders"))
  }

  test("ANSI mapper returns UnqualifiedTable when database default missing") {
    val config = Config.forGeneric(defaultSchema = Some("public"))
    val table  = makeTable("orders")
    val result = DialectMapper.ansi.toTableRef(table, config)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get shouldBe a[DenyReason.UnqualifiedTable]
    val reason = result.left.toOption.get.asInstanceOf[DenyReason.UnqualifiedTable]
    reason.missingPart shouldBe "database"
    reason.tableName shouldBe "orders"
  }

  test("ANSI mapper returns UnqualifiedTable when schema default missing") {
    val config = Config.forGeneric(defaultDatabase = Some("mydb"))
    val table  = makeTable("orders")
    val result = DialectMapper.ansi.toTableRef(table, config)
    result shouldBe a[Left[?, ?]]
    val reason = result.left.toOption.get.asInstanceOf[DenyReason.UnqualifiedTable]
    reason.missingPart shouldBe "schema"
  }

  test("ANSI mapper normalizes table names to lowercase via TableRef") {
    val config = Config.forGeneric("DB", "SCHEMA")
    val table  = makeTable("Orders", schema = "Public", database = "MyDB")
    val result = DialectMapper.ansi.toTableRef(table, config)
    result shouldBe Right(TableRef("MyDB", "Public", "Orders"))
    // TableRef normalizes internally
    result.toOption.get.database shouldBe "mydb"
    result.toOption.get.schema shouldBe "public"
    result.toOption.get.table shouldBe "orders"
  }

  test("DuckDB mapper delegates to ANSI for three-part names") {
    val config       = Config.forDuckDB("defaultdb", "defaultschema")
    val table        = makeTable("orders", schema = "public", database = "mydb")
    val ansiResult   = DialectMapper.ansi.toTableRef(table, config)
    val duckdbResult = DialectMapper.duckdb.toTableRef(table, config)
    ansiResult.toOption.get shouldEqual duckdbResult.toOption.get
  }

  test("DuckDB mapper treats two-part name as schema.table under the default catalog") {
    val config       = Config.forDuckDB("defaultdb", "main")
    val table        = makeTable("orders", schema = "sales") // JSqlParser: schema=sales
    val duckdbResult = DialectMapper.duckdb.toTableRef(table, config)
    // sales is the schema, the session's default catalog fills the database part
    duckdbResult shouldBe Right(TableRef("defaultdb", "sales", "orders"))
  }

  test("DuckDB mapper keeps the written schema for two-part names regardless of defaultSchema") {
    val config       = Config.forDuckDB("defaultdb", "public")
    val table        = makeTable("orders", schema = "tpch1")
    val duckdbResult = DialectMapper.duckdb.toTableRef(table, config)
    duckdbResult shouldBe Right(TableRef("defaultdb", "tpch1", "orders"))
  }

  test("DuckDB mapper two-part name without defaultDatabase yields UnqualifiedTable") {
    val config = Config.forDuckDB(defaultSchema = Some("main"))
    val table  = makeTable("orders", schema = "tpch1")
    val result = DialectMapper.duckdb.toTableRef(table, config)
    result shouldBe a[Left[?, ?]]
    val reason = result.left.toOption.get.asInstanceOf[DenyReason.UnqualifiedTable]
    reason.missingPart shouldBe "database"
  }

  test("DuckDB mapper delegates to ANSI for one-part names") {
    val config       = Config.forDuckDB("defaultdb", "main")
    val table        = makeTable("orders")
    val duckdbResult = DialectMapper.duckdb.toTableRef(table, config)
    duckdbResult shouldBe Right(TableRef("defaultdb", "main", "orders"))
  }

  test("DuckDB isFileReference detects string-literal file references") {
    // Use SQL parsing to create a Table with file reference, as jsqlparser 5.4
    // handles quote stripping differently when using setName directly
    import net.sf.jsqlparser.parser.CCJSqlParserUtil
    import net.sf.jsqlparser.statement.select.{PlainSelect, Select}
    val stmt        = CCJSqlParserUtil.parse("SELECT * FROM 'file.parquet'")
    val select      = stmt.asInstanceOf[Select]
    val plainSelect = select.getPlainSelect
    val fromItem    = plainSelect.getFromItem
    fromItem shouldBe a[Table]
    DuckDBDialectMapper.isFileReference(fromItem.asInstanceOf[Table]) shouldBe true

    val normalTable = new Table()
    normalTable.setName("orders"): Unit
    DuckDBDialectMapper.isFileReference(normalTable) shouldBe false
  }

  test("DuckDB mapper resolves the demo pattern schema.table to the pool catalog") {
    val config       = Config.forDuckDB("acme_tpch", "main")
    val table        = makeTable("customer", schema = "tpch1")
    val duckdbResult = DialectMapper.duckdb.toTableRef(table, config)
    duckdbResult shouldBe Right(TableRef("acme_tpch", "tpch1", "customer"))
  }

  test("ANSI mapper reports database missing before schema when both absent") {
    val config = Config.forGeneric()
    val table  = makeTable("orders")
    val result = DialectMapper.ansi.toTableRef(table, config)
    result shouldBe a[Left[?, ?]]
    val reason = result.left.toOption.get.asInstanceOf[DenyReason.UnqualifiedTable]
    reason.missingPart shouldBe "database"
  }

  // ---- attached-catalog awareness ----

  private val attachedCfg = Config
    .forDuckDB(Some("acme_tpch"), Some("tpch1"))
    .copy(attachedCatalogs = Set("acme_tpch", "fedpg", "memory", "system", "temp"))

  test("DuckDB mapper with attachedCatalogs denies a two-part name whose head is an attached catalog") {
    val table  = makeTable("orders", schema = "fedpg")
    val result = DuckDBDialectMapper.toTableRef(table, attachedCfg)
    result match
      case Left(DenyReason.AmbiguousCatalogRef(name, catalog)) =>
        name shouldBe "fedpg.orders"
        catalog shouldBe "fedpg"
      case other => fail(s"expected AmbiguousCatalogRef, got $other")
  }

  test("DuckDB mapper with attachedCatalogs denies when the head equals the session default catalog itself") {
    val table = makeTable("customer", schema = "acme_tpch")
    DuckDBDialectMapper.toTableRef(table, attachedCfg) match
      case Left(DenyReason.AmbiguousCatalogRef(_, catalog)) => catalog shouldBe "acme_tpch"
      case other => fail(s"expected AmbiguousCatalogRef, got $other")
  }

  test("DuckDB mapper with attachedCatalogs matches attached catalogs case-insensitively") {
    val table = makeTable("orders", schema = "FedPG")
    DuckDBDialectMapper.toTableRef(table, attachedCfg) match
      case Left(DenyReason.AmbiguousCatalogRef(_, _)) => succeed
      case other => fail(s"expected AmbiguousCatalogRef, got $other")
  }

  test("DuckDB mapper with attachedCatalogs resolves a non-attached head schema-first") {
    val table = makeTable("customer", schema = "tpch1")
    DuckDBDialectMapper.toTableRef(table, attachedCfg) shouldBe
      Right(TableRef("acme_tpch", "tpch1", "customer"))
  }

  test("DuckDB mapper resolves everything schema-first when attachedCatalogs is empty (standalone parser)") {
    val cfg   = Config.forDuckDB(Some("acme_tpch"), Some("tpch1"))
    val table = makeTable("orders", schema = "fedpg")
    DuckDBDialectMapper.toTableRef(table, cfg) shouldBe
      Right(TableRef("acme_tpch", "fedpg", "orders"))
  }

  test("DuckDB mapper leaves three-part names untouched even when the head is attached") {
    val table = makeTable("orders", schema = "public", database = "fedpg")
    DuckDBDialectMapper.toTableRef(table, attachedCfg) shouldBe
      Right(TableRef("fedpg", "public", "orders"))
  }
}
