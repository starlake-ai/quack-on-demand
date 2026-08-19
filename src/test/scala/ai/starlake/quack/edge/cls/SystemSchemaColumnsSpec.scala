package ai.starlake.quack.edge.cls

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The static map itself: shapes were probed against DuckDB v1.5.4 (see the source comment in
  * SystemSchemaColumns). These tests pin the lookup semantics (case-insensitivity, fail-closed
  * None for anything unknown) and a few load-bearing column lists the FlightSQL metadata path
  * depends on.
  */
class SystemSchemaColumnsSpec extends AnyFlatSpec with Matchers:

  "isSystemSchema" should "match information_schema and pg_catalog case-insensitively" in {
    SystemSchemaColumns.isSystemSchema("information_schema") shouldBe true
    SystemSchemaColumns.isSystemSchema("INFORMATION_SCHEMA") shouldBe true
    SystemSchemaColumns.isSystemSchema("pg_catalog") shouldBe true
    SystemSchemaColumns.isSystemSchema("PG_CATALOG") shouldBe true
    SystemSchemaColumns.isSystemSchema("tpch1") shouldBe false
    SystemSchemaColumns.isSystemSchema("") shouldBe false
  }

  "columnsOf" should "return schemata's columns in DuckDB order" in {
    SystemSchemaColumns.columnsOf("information_schema", "schemata") shouldBe Some(
      List(
        "catalog_name",
        "schema_name",
        "schema_owner",
        "default_character_set_catalog",
        "default_character_set_schema",
        "default_character_set_name",
        "sql_path"
      )
    )
  }

  it should "be case-insensitive on both schema and table" in {
    SystemSchemaColumns.columnsOf("INFORMATION_SCHEMA", "Schemata") shouldBe
      SystemSchemaColumns.columnsOf("information_schema", "schemata")
    SystemSchemaColumns.columnsOf("PG_CATALOG", "PG_TABLES") shouldBe
      SystemSchemaColumns.columnsOf("pg_catalog", "pg_tables")
  }

  it should "cover the tables the FlightSQL metadata path browses" in {
    val tablesCols = SystemSchemaColumns.columnsOf("information_schema", "tables")
    tablesCols should not be empty
    tablesCols.get should contain allOf ("table_catalog", "table_schema", "table_name", "table_type")

    val columnsCols = SystemSchemaColumns.columnsOf("information_schema", "columns")
    columnsCols should not be empty
    columnsCols.get should contain allOf ("table_schema", "table_name", "column_name", "data_type", "ordinal_position")

    SystemSchemaColumns.columnsOf("pg_catalog", "pg_tables") shouldBe Some(
      List(
        "schemaname",
        "tablename",
        "tableowner",
        "tablespace",
        "hasindexes",
        "hasrules",
        "hastriggers"
      )
    )
    SystemSchemaColumns.columnsOf("pg_catalog", "pg_namespace") shouldBe Some(
      List("oid", "nspname", "nspowner", "nspacl")
    )
  }

  it should "return None (fail closed) for an unknown system table" in {
    SystemSchemaColumns.columnsOf("information_schema", "no_such_table") shouldBe None
    SystemSchemaColumns.columnsOf("pg_catalog", "pg_no_such") shouldBe None
  }

  it should "return None for user schemas even when the table name collides with a system one" in {
    SystemSchemaColumns.columnsOf("tpch1", "tables") shouldBe None
    SystemSchemaColumns.columnsOf("", "schemata") shouldBe None
    SystemSchemaColumns.columnsOf("main", "pg_tables") shouldBe None
  }
