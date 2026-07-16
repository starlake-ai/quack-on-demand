package ai.starlake.quack.ondemand.demo

import org.slf4j.LoggerFactory

import scala.sys.process.*

/** Seeds the demo tenant-db with a small TPC-H dataset via DuckDB's built-in `dbgen()`. Mirrors
  * `scripts/load-tpch-dbgen.sh`: ATTACH the DuckLake-over-Postgres catalog, create the schema, then
  * `CALL dbgen(...)` into the default in-memory database and `CREATE TABLE ... AS SELECT *` each of
  * the 8 TPC-H tables into the DuckLake schema.
  *
  * `dbgen()` only writes to a native DuckDB catalog (validated against real DuckLake-over-Postgres:
  * `CALL dbgen(sf = ..., catalog = ..., schema = ...)` and `USE db.schema; CALL dbgen(sf = ...)`
  * both fail with `Invalid Input Error: dbgen is only supported for DuckDB database files`), so it
  * cannot target an ATTACHed DuckLake catalog directly - hence the CTAS copy step below.
  *
  * Runs the loader in the same process/host as the manager, so the DuckLake absolute-`DATA_PATH`
  * matching requirement holds by construction.
  */
object DemoSeed:

  private val logger = LoggerFactory.getLogger(getClass)

  private val Tables =
    List("region", "nation", "customer", "supplier", "part", "partsupp", "orders", "lineitem")

  /** The DuckDB init script. `dataPath` MUST be the same absolute path the manager's nodes read. */
  def buildInitSql(
      pg: PgCoords,
      dbName: String,
      schema: String,
      dataPath: String,
      sf: Double
  ): String =
    val ctas = Tables
      .map(t => s"CREATE TABLE $dbName.$schema.$t AS SELECT * FROM memory.main.$t;")
      .mkString("\n")
    s"""INSTALL ducklake; LOAD ducklake;
       |INSTALL postgres; LOAD postgres;
       |INSTALL tpch;     LOAD tpch;
       |ATTACH 'ducklake:postgres:host=${pg.host} port=${pg.port} dbname=$dbName user=${pg.user} password=${pg.password}' AS $dbName
       |  (DATA_PATH '$dataPath');
       |CREATE SCHEMA IF NOT EXISTS $dbName.$schema;
       |CALL dbgen(sf = $sf);
       |$ctas
       |""".stripMargin

  /** Pipe the init script into the `duckdb` CLI. Returns Left with captured stderr on failure. */
  def run(
      pg: PgCoords,
      dbName: String,
      schema: String,
      home: DemoHome,
      sf: Double,
      duckdb: String = "duckdb"
  ): Either[String, Unit] =
    val sql = buildInitSql(pg, dbName, schema, home.dataPath.toString, sf)
    val err = new StringBuilder
    logger.info(s"demo seed: dbgen sf=$sf into $dbName.$schema at ${home.dataPath}")
    val exit =
      try
        (Seq(duckdb) #< new java.io.ByteArrayInputStream(sql.getBytes("UTF-8")))
          .!(ProcessLogger(_ => (), e => err.append(e).append('\n')))
      catch case t: Throwable => err.append(t.getMessage); -1
    if exit == 0 then Right(())
    else Left(s"demo seed failed (exit $exit): ${err.toString.trim}")
