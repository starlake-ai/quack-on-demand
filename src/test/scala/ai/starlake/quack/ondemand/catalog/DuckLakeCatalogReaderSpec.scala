package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.state.testkit.PostgresFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

class DuckLakeCatalogReaderSpec extends AnyFlatSpec with Matchers with PostgresFixture:

  "listSchemas" should "return every user schema in the catalog with table counts" in
    withCatalog("tpch") { (reader, _) =>
      val schemas = reader.listSchemas()
      schemas.map(_.name) should contain("tpch1")
      schemas.find(_.name == "tpch1").value.tableCount shouldBe 1
    }

  "listTables" should "return every table in a schema with row + data-file counts" in
    withCatalog("tpch") { (reader, _) =>
      val tables = reader.listTables(schema = "tpch1")
      tables.map(_.name).sorted shouldBe List("region")
      val region = tables.head
      region.schema shouldBe "tpch1"
      region.rowCount shouldBe 5L
      region.dataFileCount should be >= 1
    }

  "getTable" should "include columns with types, nullability, and PK flag" in
    withCatalog("tpch") { (reader, _) =>
      val detail = reader.getTable(schema = "tpch1", table = "region").value
      detail.columns.map(_.name) should contain allOf ("r_regionkey", "r_name", "r_comment")
      val rk = detail.columns.find(_.name == "r_regionkey").value
      rk.typeName.toUpperCase should startWith("INT")
      rk.nullable shouldBe false
      // DuckLake (as of v0.3) doesn't model PRIMARY KEY in its metadata, so
      // isPrimaryKey is always false. See DuckLakeCatalogReader for details.
      rk.isPrimaryKey shouldBe false
      val nullable = detail.columns.find(_.name == "r_comment").value
      nullable.nullable shouldBe true
    }

  "getTable" should "list the parquet data files backing the table" in
    withCatalog("tpch") { (reader, _) =>
      val detail = reader.getTable(schema = "tpch1", table = "region").value
      detail.dataFiles should not be empty
      val f = detail.dataFiles.head
      f.path should fullyMatch regex """.*\.parquet"""
      f.sizeBytes should be > 0L
      f.rowCount should be > 0L
    }

  "getTable" should "return None when the table doesn't exist" in
    withCatalog("tpch") { (reader, _) =>
      reader.getTable(schema = "tpch1", table = "does_not_exist") shouldBe None
    }
