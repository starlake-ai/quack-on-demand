package ai.starlake.acl.model

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ConfigTest extends AnyFunSuite with Matchers {

  test("forGeneric creates config with normalized defaults") {
    val config = Config.forGeneric("MyDB", "Public")
    config.normalizedDefaultDatabase shouldBe Some("mydb")
    config.normalizedDefaultSchema shouldBe Some("public")
    config.dialect.name shouldBe "generic"
  }

  test("forDuckDB creates config with duckdb dialect") {
    val duckdb = Config.forDuckDB("MyDB", "Public")
    duckdb.defaultDatabase shouldBe Some("MyDB")
    duckdb.defaultSchema shouldBe Some("Public")
    duckdb.dialect.name shouldBe "duckdb"
  }

  test("normalizedDefaultDatabase is lowercase") {
    val config = Config.forGeneric("ANALYTICS", "main")
    config.normalizedDefaultDatabase shouldBe Some("analytics")
    config.defaultDatabase shouldBe Some("ANALYTICS")
  }

  test("normalizedDefaultSchema is lowercase") {
    val config = Config.forGeneric("db", "RAW_DATA")
    config.normalizedDefaultSchema shouldBe Some("raw_data")
    config.defaultSchema shouldBe Some("RAW_DATA")
  }

  test("dialect has three-part identifier mapping and case-insensitive sensitivity") {
    val config = Config.forGeneric("db", "schema")
    config.dialect.identifierMapping shouldBe IdentifierMapping.ThreePart
    config.dialect.caseSensitivity shouldBe CaseSensitivity.CaseInsensitive
  }

  test("forGeneric with None defaults creates config with None normalized values") {
    val config = Config.forGeneric()
    config.defaultDatabase shouldBe None
    config.defaultSchema shouldBe None
    config.normalizedDefaultDatabase shouldBe None
    config.normalizedDefaultSchema shouldBe None
    config.dialect.name shouldBe "generic"
  }

  test("forGeneric with partial defaults (database only, schema only)") {
    val dbOnly = Config.forGeneric(defaultDatabase = Some("MyDB"))
    dbOnly.defaultDatabase shouldBe Some("MyDB")
    dbOnly.defaultSchema shouldBe None
    dbOnly.normalizedDefaultDatabase shouldBe Some("mydb")
    dbOnly.normalizedDefaultSchema shouldBe None

    val schemaOnly = Config.forGeneric(defaultSchema = Some("Public"))
    schemaOnly.defaultDatabase shouldBe None
    schemaOnly.defaultSchema shouldBe Some("Public")
    schemaOnly.normalizedDefaultDatabase shouldBe None
    schemaOnly.normalizedDefaultSchema shouldBe Some("public")
  }

  test("convenience String overload produces same result as explicit Some") {
    val fromString = Config.forGeneric("MyDB", "Public")
    val fromOption = Config.forGeneric(Some("MyDB"), Some("Public"))
    fromString shouldEqual fromOption
  }
}
