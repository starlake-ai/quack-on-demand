package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantDbKindSpec extends AnyFlatSpec with Matchers {

  "TenantDbKind.fromWire" should "decode each known wire value" in {
    TenantDbKind.fromWire("ducklake")    shouldBe Right(TenantDbKind.DuckLake)
    TenantDbKind.fromWire("duckdb-file") shouldBe Right(TenantDbKind.DuckDbFile)
    TenantDbKind.fromWire("memory")      shouldBe Right(TenantDbKind.InMemory)
  }

  it should "reject unknown wire values with the offending input" in {
    TenantDbKind.fromWire("postgres") shouldBe Left("unknown TenantDbKind: 'postgres'")
    TenantDbKind.fromWire("")         shouldBe Left("unknown TenantDbKind: ''")
  }

  "wireValue" should "round-trip every kind through fromWire" in {
    val all: List[TenantDbKind] =
      List(TenantDbKind.DuckLake, TenantDbKind.DuckDbFile, TenantDbKind.InMemory)
    all.foreach { k =>
      TenantDbKind.fromWire(k.wireValue) shouldBe Right(k)
    }
  }
}

class TenantDbValidationSpec extends AnyFlatSpec with Matchers {

  private val pgMeta = Map(
    "pgHost" -> "localhost", "pgPort" -> "5432",
    "pgUser" -> "u", "pgPassword" -> "p",
    "dbName" -> "tpch", "schemaName" -> "main"
  )

  "TenantDb.validate" should "accept a well-formed ducklake config" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckLake, pgMeta, "/tmp/d")
    TenantDb.validate(td) shouldBe None
  }

  it should "reject ducklake missing pg keys" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckLake,
                     pgMeta - "pgHost", "/tmp/d")
    TenantDb.validate(td).get should include("pgHost")
  }

  it should "reject ducklake with empty dataPath" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckLake, pgMeta, "")
    TenantDb.validate(td).get should include("dataPath")
  }

  it should "accept duckdb-file with dbName, schemaName, and a path" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckDbFile,
                     Map("dbName" -> "tpch", "schemaName" -> "main"),
                     "/tmp/foo.duckdb")
    TenantDb.validate(td) shouldBe None
  }

  it should "reject memory kind with non-empty metastore" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.InMemory,
                     Map("dbName" -> "tpch"), "")
    TenantDb.validate(td).get should include("empty metastore")
  }

  it should "accept memory kind with empty metastore and empty dataPath" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.InMemory,
                     Map.empty, "")
    TenantDb.validate(td) shouldBe None
  }
}
