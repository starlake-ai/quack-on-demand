package ai.starlake.quack.ondemand.state.testkit

import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import org.scalatest.Assertions

import java.nio.file.{Files, Path}
import scala.sys.process._
import scala.util.Try

/** Spins up a Postgres-backed DuckLake catalog for one test. Reuses the developer's local Postgres
  * (postgres@localhost:5432, password `azizam`)
  *   - same default the rest of the suite assumes - and a temporary database name per test so
  *     parallel runs don't collide.
  */
trait PostgresFixture:

  private val pgHost = TestPostgres.pgHost
  private val pgPort = TestPostgres.pgPort
  private val pgUser = TestPostgres.pgUser
  private val pgPass = TestPostgres.pgPass

  /** The temp database name and DATA_PATH of the catalog currently open under `withCatalog`, set
    * right after seeding so a test body can rebuild its own ATTACH (e.g. a second `duckdb` CLI
    * session driving the maintenance chain). `None` outside the `withCatalog` call.
    */
  protected var currentDbName: Option[String] = None
  protected var currentDataPath: Option[Path] = None

  def withCatalog[A](catalogPrefix: String, extraSql: String = "")(
      test: (DuckLakeCatalogReader, Path) => A
  ): A =
    TestPostgres.ensureReachable()
    val dbName   = s"${catalogPrefix}_test_${System.nanoTime()}"
    val dataPath = Files.createTempDirectory(s"$catalogPrefix-data-")
    try
      createDatabase(dbName)
      try
        seedCatalog(dbName, dataPath, extraSql)
        currentDbName = Some(dbName)
        currentDataPath = Some(dataPath)
        val meta = Map(
          "pgHost"     -> pgHost,
          "pgPort"     -> pgPort.toString,
          "pgUser"     -> pgUser,
          "pgPassword" -> pgPass,
          "dbName"     -> dbName,
          "schemaName" -> "main",
          "dataPath"   -> dataPath.toString
        )
        val reader = DuckLakeCatalogReader(meta)
        try test(reader, dataPath)
        finally quietly(reader.close())
      finally quietly(dropDatabase(dbName))
    finally
      currentDbName = None
      currentDataPath = None
      quietly {
        if Files.exists(dataPath) then
          Files
            .walk(dataPath)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p => Files.deleteIfExists(p))
      }

  /** Run SQL against the catalog currently open under `withCatalog`, in a fresh duckdb CLI session
    * (same mechanism the fixture's own seeder uses). The ATTACH alias is always `lake`. Fails the
    * test on a non-zero exit. Must be called from within a `withCatalog` block.
    */
  protected def runSqlOnCatalog(body: String): Unit =
    val dbName   = currentDbName.getOrElse(Assertions.fail("runSqlOnCatalog outside withCatalog"))
    val dataPath = currentDataPath.getOrElse(Assertions.fail("runSqlOnCatalog outside withCatalog"))
    val attach   =
      s"""INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres;
         |ATTACH 'ducklake:postgres:host=$pgHost port=$pgPort dbname=$dbName user=$pgUser password=$pgPass' AS lake
         |  (DATA_PATH '$dataPath');
         |$body
         |""".stripMargin
    val tmp = Files.createTempFile("chain", ".sql")
    Files.writeString(tmp, attach)
    try
      val rc = (s"duckdb" #< tmp.toFile).!
      assert(rc == 0, s"duckdb chain exit=$rc")
    finally Files.deleteIfExists(tmp)

  private def quietly(op: => Unit): Unit =
    try op
    catch case _: Throwable => ()

  private def createDatabase(dbName: String): Unit =
    runPsql("postgres", s"""CREATE DATABASE "$dbName"""")

  private def dropDatabase(dbName: String): Unit =
    runPsql("postgres", s"""DROP DATABASE IF EXISTS "$dbName"""")

  private def seedCatalog(dbName: String, dataPath: Path, extraSql: String): Unit =
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
         |$extraSql
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
