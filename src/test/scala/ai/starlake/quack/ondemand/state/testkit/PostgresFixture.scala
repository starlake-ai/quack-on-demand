package ai.starlake.quack.ondemand.state.testkit

import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader

import java.nio.file.{Files, Path}
import scala.sys.process._

/** Spins up a Postgres-backed DuckLake catalog for one test. Reuses the developer's local Postgres
  * (postgres@localhost:5432, password `azizam`)
  *   - same default the rest of the suite assumes - and a temporary database name per test so
  *     parallel runs don't collide.
  */
trait PostgresFixture:

  private val pgHost = sys.env.getOrElse("SL_TEST_PG_HOST", "localhost")
  private val pgPort = sys.env.getOrElse("SL_TEST_PG_PORT", "5432").toInt
  private val pgUser = sys.env.getOrElse("SL_TEST_PG_USER", "postgres")
  private val pgPass = sys.env.getOrElse("SL_TEST_PG_PASSWORD", "azizam")

  def withCatalog[A](catalogPrefix: String)(test: (DuckLakeCatalogReader, Path) => A): A =
    val dbName   = s"${catalogPrefix}_test_${System.nanoTime()}"
    val dataPath = Files.createTempDirectory(s"$catalogPrefix-data-")

    createDatabase(dbName)
    var reader: DuckLakeCatalogReader = null
    try
      seedCatalog(dbName, dataPath)
      val meta = Map(
        "pgHost"     -> pgHost,
        "pgPort"     -> pgPort.toString,
        "pgUser"     -> pgUser,
        "pgPassword" -> pgPass,
        "dbName"     -> dbName,
        "schemaName" -> "main",
        "dataPath"   -> dataPath.toString
      )
      reader = DuckLakeCatalogReader(meta)
      test(reader, dataPath)
    finally
      if reader != null then reader.close()
      dropDatabase(dbName)
      if Files.exists(dataPath) then
        Files
          .walk(dataPath)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => Files.deleteIfExists(p))

  private def createDatabase(dbName: String): Unit =
    runPsql("postgres", s"""CREATE DATABASE "$dbName"""")

  private def dropDatabase(dbName: String): Unit =
    runPsql("postgres", s"""DROP DATABASE IF EXISTS "$dbName"""")

  private def seedCatalog(dbName: String, dataPath: Path): Unit =
    val sql =
      s"""INSTALL ducklake; LOAD ducklake;
         |INSTALL postgres; LOAD postgres;
         |ATTACH 'ducklake:postgres:host=$pgHost port=$pgPort dbname=$dbName user=$pgUser password=$pgPass' AS lake
         |  (DATA_PATH '${dataPath.toString}');
         |CREATE SCHEMA IF NOT EXISTS lake.tpch1;
         |-- DuckLake (as of v0.3 in DuckDB 1.5.x) does NOT support PRIMARY KEY /
         |-- UNIQUE constraints - the catalog rejects CREATE TABLE with them. We
         |-- still mark the PK column NOT NULL so nullability is observable.
         |CREATE TABLE lake.tpch1.region (
         |  r_regionkey INTEGER NOT NULL,
         |  r_name      VARCHAR NOT NULL,
         |  r_comment   VARCHAR
         |);
         |INSERT INTO lake.tpch1.region VALUES
         |  (0, 'AFRICA',  'a'),
         |  (1, 'AMERICA', 'b'),
         |  (2, 'ASIA',    'c'),
         |  (3, 'EUROPE',  'd'),
         |  (4, 'MIDDLE EAST', 'e');
         |-- DuckLake inlines small inserts in `ducklake_inlined_data_*`
         |-- Postgres tables rather than writing parquet straight away. Force
         |-- materialization so `ducklake_data_file` actually contains rows
         |-- (the catalog browser shows parquet files, not inlined batches).
         |CALL ducklake_flush_inlined_data('lake');
         |""".stripMargin
    val tmp = Files.createTempFile("seed", ".sql")
    Files.writeString(tmp, sql)
    try
      val rc = (s"duckdb" #< tmp.toFile).!
      assert(rc == 0, s"duckdb seed exit=$rc")
    finally Files.deleteIfExists(tmp)

  private def runPsql(targetDb: String, sql: String): Unit =
    val env = Seq("PGPASSWORD" -> pgPass)
    val rc  = Process(
      Seq("psql", "-h", pgHost, "-p", pgPort.toString, "-U", pgUser, "-d", targetDb, "-tAc", sql),
      None,
      env*
    ).!
    assert(rc == 0, s"psql ($sql) exit=$rc")
