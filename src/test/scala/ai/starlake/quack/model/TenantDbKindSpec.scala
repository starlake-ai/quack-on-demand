package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantDbKindSpec extends AnyFlatSpec with Matchers {

  "TenantDbKind.fromWire" should "decode each known wire value" in {
    TenantDbKind.fromWire("ducklake") shouldBe Right(TenantDbKind.DuckLake)
    TenantDbKind.fromWire("duckdb-file") shouldBe Right(TenantDbKind.DuckDbFile)
    TenantDbKind.fromWire("memory") shouldBe Right(TenantDbKind.InMemory)
  }

  it should "reject unknown wire values with the offending input" in {
    TenantDbKind.fromWire("postgres") shouldBe Left("unknown TenantDbKind: 'postgres'")
    TenantDbKind.fromWire("") shouldBe Left("unknown TenantDbKind: ''")
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
    "pgHost"     -> "localhost",
    "pgPort"     -> "5432",
    "pgUser"     -> "u",
    "pgPassword" -> "p",
    "dbName"     -> "tpch",
    "schemaName" -> "main"
  )

  "TenantDb.validate" should "accept a well-formed ducklake config" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckLake, pgMeta, "/tmp/d")
    TenantDb.validate(td) shouldBe None
  }

  it should "reject ducklake missing pg keys" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckLake, pgMeta - "pgHost", "/tmp/d")
    TenantDb.validate(td).get should include("pgHost")
  }

  it should "reject ducklake with empty dataPath" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckLake, pgMeta, "")
    TenantDb.validate(td).get should include("dataPath")
  }

  it should "accept duckdb-file with dbName, schemaName, and a path" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckDbFile,
      Map("dbName" -> "tpch", "schemaName" -> "main"),
      "/tmp/foo.duckdb"
    )
    TenantDb.validate(td) shouldBe None
  }

  it should "reject memory kind with non-empty metastore" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.InMemory, Map("dbName" -> "tpch"), "")
    TenantDb.validate(td).get should include("empty metastore")
  }

  it should "accept memory kind with empty metastore and empty dataPath" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.InMemory, Map.empty, "")
    TenantDb.validate(td) shouldBe None
  }

  // --- SQL-injection hardening on node-bootstrap identifiers/paths ---

  it should "reject a schemaName carrying an injected statement" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckLake,
      pgMeta.updated("schemaName", "main; ATTACH 'x' AS y"),
      "/tmp/d"
    )
    TenantDb.validate(td).get should include("schemaName")
  }

  it should "reject a schemaName containing a space" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckLake,
      pgMeta.updated("schemaName", "not valid"),
      "/tmp/d"
    )
    TenantDb.validate(td).get should include("schemaName")
  }

  it should "reject a schemaName on a duckdb-file kind too" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckDbFile,
      Map("dbName" -> "tpch", "schemaName" -> "s; DROP TABLE t"),
      "/tmp/foo.duckdb"
    )
    TenantDb.validate(td).get should include("schemaName")
  }

  it should "accept a normal schemaName" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckLake, pgMeta, "/tmp/d")
    TenantDb.validate(td) shouldBe None
  }

  it should "reject a dataPath containing a single quote" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckLake,
      pgMeta,
      "/tmp/d'); ATTACH 'evil' AS e --"
    )
    TenantDb.validate(td).get should include("dataPath")
  }

  it should "reject a dataPath containing a semicolon" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckLake, pgMeta, "/tmp/d; DETACH x")
    TenantDb.validate(td).get should include("dataPath")
  }

  it should "reject a dataPath containing a backslash or newline" in {
    val bs = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckLake, pgMeta, "/tmp/d\\evil")
    TenantDb.validate(bs).get should include("dataPath")
    val nl = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckLake, pgMeta, "/tmp/d\nUSE bad")
    TenantDb.validate(nl).get should include("dataPath")
  }

  it should "accept an absolute filesystem dataPath" in {
    val td = TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckLake, pgMeta, "/var/lib/qod/tpch1")
    TenantDb.validate(td) shouldBe None
  }

  it should "accept an s3 URI dataPath" in {
    val td =
      TenantDb("td-1", "t-1", "tpch1", TenantDbKind.DuckLake, pgMeta, "s3://bucket/path/to-data_01")
    TenantDb.validate(td) shouldBe None
  }

  // --- SQL-injection hardening on pg* connection params (single-quoted literals) ---

  it should "reject a pgPassword containing a single quote" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckLake,
      pgMeta.updated("pgPassword", "p'; ATTACH 'evil' AS e; --"),
      "/tmp/d"
    )
    TenantDb.validate(td).get should include("pgPassword")
  }

  it should "reject a pgPassword containing a semicolon" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckLake,
      pgMeta.updated("pgPassword", "p;drop"),
      "/tmp/d"
    )
    TenantDb.validate(td).get should include("pgPassword")
  }

  it should "accept a pgPassword with other symbols but no literal-breaking chars" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckLake,
      pgMeta.updated("pgPassword", "aB3$%^&*()-_=+.,<>?/|{}[]~`"),
      "/tmp/d"
    )
    TenantDb.validate(td) shouldBe None
  }

  it should "reject a pgUser containing a single quote" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckLake,
      pgMeta.updated("pgUser", "u' OR '1'='1"),
      "/tmp/d"
    )
    TenantDb.validate(td).get should include("pgUser")
  }

  it should "reject a pgHost containing a semicolon" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckLake,
      pgMeta.updated("pgHost", "host'; DETACH x; --"),
      "/tmp/d"
    )
    TenantDb.validate(td).get should include("pgHost")
  }

  it should "reject a non-numeric pgPort" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckLake,
      pgMeta.updated("pgPort", "5432; DROP"),
      "/tmp/d"
    )
    TenantDb.validate(td).get should include("pgPort")
  }

  it should "accept a numeric pgPort" in {
    val td =
      TenantDb(
        "td-1",
        "t-1",
        "tpch1",
        TenantDbKind.DuckLake,
        pgMeta.updated("pgPort", "5433"),
        "/x"
      )
    TenantDb.validate(td) shouldBe None
  }

  // --- duckdb-file dbName is caller-supplied and must be an identifier ---

  it should "reject a duckdb-file dbName containing a double quote" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckDbFile,
      Map("dbName" -> "db\"; USE bad", "schemaName" -> "main"),
      "/tmp/foo.duckdb"
    )
    TenantDb.validate(td).get should include("dbName")
  }

  it should "reject a duckdb-file dbName containing a space" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckDbFile,
      Map("dbName" -> "db name", "schemaName" -> "main"),
      "/tmp/foo.duckdb"
    )
    TenantDb.validate(td).get should include("dbName")
  }

  it should "accept a normal duckdb-file dbName" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckDbFile,
      Map("dbName" -> "tpch", "schemaName" -> "main"),
      "/tmp/foo.duckdb"
    )
    TenantDb.validate(td) shouldBe None
  }

  // --- objectStore values feed ObjectStoreSecret's Azure connection-string concatenation ---

  it should "reject an objectStore value containing a semicolon, naming the offending key" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckLake,
      pgMeta,
      "/tmp/d",
      objectStore = Map("azure_account_key" -> "key;BlobEndpoint=https://evil")
    )
    TenantDb.validate(td).get should include("azure_account_key")
  }

  it should "accept an objectStore map with no literal-breaking characters" in {
    val td = TenantDb(
      "td-1",
      "t-1",
      "tpch1",
      TenantDbKind.DuckLake,
      pgMeta,
      "/tmp/d",
      objectStore = Map("s3_access_key_id" -> "k", "s3_secret_access_key" -> "sk")
    )
    TenantDb.validate(td) shouldBe None
  }
}
