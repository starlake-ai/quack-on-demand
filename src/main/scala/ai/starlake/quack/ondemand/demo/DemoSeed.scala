package ai.starlake.quack.ondemand.demo

import org.slf4j.LoggerFactory

import java.nio.file.Files

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

  /** A `DROP SCHEMA ... CASCADE` inserted right before the `CREATE SCHEMA` line of
    * [[buildInitSql]]'s output. Used only by retry attempts in [[run]]: the observed flake (below)
    * can leave a fully-populated schema behind even though the CLI itself reported a nonzero exit,
    * and the plain `CREATE TABLE ... AS SELECT` in [[buildInitSql]] is not safe to replay against a
    * schema that already has those tables. Rebuilding via string substitution (rather than a second
    * hand-maintained template) keeps this in lockstep with [[buildInitSql]] and leaves that
    * method's output -- and the tests pinned to its exact text -- untouched.
    */
  private def buildRetryInitSql(
      pg: PgCoords,
      dbName: String,
      schema: String,
      dataPath: String,
      sf: Double
  ): String =
    val createSchema   = s"CREATE SCHEMA IF NOT EXISTS $dbName.$schema;"
    val dropThenCreate =
      s"DROP SCHEMA IF EXISTS $dbName.$schema CASCADE;\n$createSchema"
    buildInitSql(pg, dbName, schema, dataPath, sf).replace(createSchema, dropThenCreate)

  /** Run the `duckdb` CLI against `sql` once. Returns the process exit code and captured stderr.
    *
    * Uses `java.lang.ProcessBuilder` with stdin/stderr fully redirected to/from files rather than
    * `scala.sys.process`'s pipe-plus-feeder-thread plumbing (`#<` + `ProcessLogger`): the latter
    * was observed to intermittently report a bogus nonzero exit for a `duckdb` run that actually
    * completed every statement (see [[run]]'s doc). File redirection needs no extra threads and was
    * reliable across repeated trials in the same environment where the pipe form flaked, so it's
    * the safer primitive here even though the two should be behaviorally equivalent.
    */
  private def runOnce(duckdb: String, sql: String): (Int, String) =
    val sqlFile = Files.createTempFile("qod-demo-seed-", ".sql")
    val errFile = Files.createTempFile("qod-demo-seed-", ".err")
    try
      Files.writeString(sqlFile, sql)
      val pb = new java.lang.ProcessBuilder(duckdb)
      pb.redirectInput(sqlFile.toFile)
      pb.redirectOutput(java.lang.ProcessBuilder.Redirect.DISCARD)
      pb.redirectError(errFile.toFile)
      val exit = pb.start().waitFor()
      val err  = scala.util.Try(Files.readString(errFile)).getOrElse("")
      (exit, err.trim)
    catch case t: Throwable => (-1, Option(t.getMessage).getOrElse(t.toString))
    finally
      scala.util.Try(Files.deleteIfExists(sqlFile))
      scala.util.Try(Files.deleteIfExists(errFile))
      ()

  /** Run the init script through the `duckdb` CLI, retrying on failure. Returns Left with the last
    * captured stderr if every attempt fails.
    *
    * Observed on this project's dev/CI hosts: `duckdb`, invoked as a child process of the manager's
    * JVM, occasionally reports a nonzero / `-1` exit with EMPTY stderr even though every statement
    * in the script actually completed (the DuckLake schema is fully populated afterwards) -- i.e.
    * the work succeeds but the reported exit status doesn't. Root cause not pinned down; a bounded
    * retry is the pragmatic mitigation on top of the file-redirected [[runOnce]]. Retries use
    * [[buildRetryInitSql]] (`DROP SCHEMA ... CASCADE` before recreating) so a retry after a
    * "phantom failure" -- where the schema is already fully populated -- doesn't itself fail on
    * `CREATE TABLE ... already exists`.
    */
  def run(
      pg: PgCoords,
      dbName: String,
      schema: String,
      home: DemoHome,
      sf: Double,
      duckdb: String = "duckdb",
      maxAttempts: Int = 3
  ): Either[String, Unit] =
    // `PoolSupervisor.effectiveMetastoreFor` derives a DuckLake tenant-db's dataPath by replacing
    // the LAST path segment of `defaultMetastore.dataPath` (== `home.dataPath` here, per
    // DemoConfig.overlay) with the tenant-db's name (`PoolSupervisor.replaceLastSegment`) -- e.g.
    // `.../ducklake` -> `.../acme_tpch`, NOT a subdirectory of `.../ducklake`. Seeding must ATTACH
    // at that SAME computed path, or the manager's own node later ATTACHes a physically different
    // (empty) directory and reports `Catalog "acme_tpch" does not exist!` even though the seed
    // "succeeded" against the wrong path. See DuckLake's absolute-DATA_PATH matching requirement
    // (class doc above).
    val dataPath = ai.starlake.quack.ondemand.PoolSupervisor.replaceLastSegment(
      home.dataPath.toString,
      dbName
    )
    val firstSql      = buildInitSql(pg, dbName, schema, dataPath, sf)
    lazy val retrySql = buildRetryInitSql(pg, dbName, schema, dataPath, sf)
    logger.info(s"demo seed: dbgen sf=$sf into $dbName.$schema at $dataPath")
    @scala.annotation.tailrec
    def attempt(n: Int, sql: String): Either[String, Unit] =
      val (exit, err) = runOnce(duckdb, sql)
      if exit == 0 then Right(())
      else if n < maxAttempts then
        logger.warn(s"demo seed: attempt $n/$maxAttempts failed (exit $exit): $err -- retrying")
        attempt(n + 1, retrySql)
      else Left(s"demo seed failed after $maxAttempts attempts (exit $exit): $err")
    attempt(1, firstSql)
