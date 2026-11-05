package ai.starlake.quack.ondemand.demo

import ai.starlake.quack.ondemand.{CatalogPgSecret, InProcessDuckDb}
import com.typesafe.scalalogging.LazyLogging

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

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
object DemoSeed extends LazyLogging:

  private val Tables =
    List("region", "nation", "customer", "supplier", "part", "partsupp", "orders", "lineitem")

  /** The DuckDB init script. `dataPath` MUST be the same absolute path the manager's nodes read. */
  def buildInitSql(
      pg: PgCoords,
      dbName: String,
      schema: String,
      dataPath: String,
      sf: Double,
      storageSql: List[String] = Nil
  ): String =
    val ctas = Tables
      .map(t => s"CREATE TABLE $dbName.$schema.$t AS SELECT * FROM memory.main.$t;")
      .mkString("\n")
    // Each statement on lines of its own: the CLI skips the rest of a line after a failure.
    val storageBlock = storageSql.map(_ + ";\n").mkString
    // The password rides a temporary secret, never the attach path ([[CatalogPgSecret]]).
    val secret = CatalogPgSecret.Name
    s"""INSTALL ducklake;
       |LOAD ducklake;
       |INSTALL postgres;
       |LOAD postgres;
       |INSTALL tpch;
       |LOAD tpch;
       |$storageBlock${CatalogPgSecret.createSql(secret, pg.password)};
       |ATTACH 'ducklake:postgres:host=${pg.host} port=${pg.port} dbname=$dbName user=${pg.user}' AS $dbName
       |  (DATA_PATH '$dataPath', ${CatalogPgSecret.metadataParameters(secret)});
       |${CatalogPgSecret.dropSql(secret)};
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
      sf: Double,
      storageSql: List[String]
  ): String =
    val createSchema   = s"CREATE SCHEMA IF NOT EXISTS $dbName.$schema;"
    val dropThenCreate =
      s"DROP SCHEMA IF EXISTS $dbName.$schema CASCADE;\n$createSchema"
    buildInitSql(pg, dbName, schema, dataPath, sf, storageSql).replace(createSchema, dropThenCreate)

  /** The first lines fed to every seed run. DuckDB loads every persistent secret of its secret
    * directory on first secret use, and the default is the OS user's `~/.duckdb/stored_secrets`;
    * the manager's unreachable [[InProcessDuckDb.secretDirectory]] replaces it before any
    * statement. Under `.bail on`, so a failure aborts the run instead of loading the default; piped
    * input otherwise carries on past errors, as before.
    */
  private[demo] def secretPrelude: String =
    val dir = InProcessDuckDb.secretDirectory.toString.replace("'", "''")
    s".bail on\nSET secret_directory = '$dir';\nSELECT count(*) FROM duckdb_secrets();\n.bail off\n"

  /** The environment a seed run gets: `base` without any `AWS_*` or `DUCKDB_S3_*` variable. httpfs
    * copies those into session settings, which fill any key the seed's secret lacks (it names no
    * `ENDPOINT` when there is none), so an ambient `AWS_ENDPOINT_URL` or `DUCKDB_S3_ENDPOINT` could
    * redirect the seed. The seed needs none of them, in either mode: its S3 secret names its own
    * keys.
    */
  private[demo] def seedEnvironment(base: Map[String, String]): Map[String, String] =
    base.filterNot((k, _) => k.startsWith("AWS_") || k.startsWith("DUCKDB_S3_"))

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
      Files.writeString(sqlFile, secretPrelude + sql)
      // -no-init: never the OS user's ~/.duckdbrc.
      val pb        = new java.lang.ProcessBuilder(duckdb, "-no-init")
      val inherited = pb.environment()
      val kept      = seedEnvironment(inherited.asScala.toMap)
      inherited.keySet().removeIf(k => !kept.contains(k))
      // DuckDB 2.0's CLI turns errors into JSON under an agent env var (AI_AGENT, CLAUDECODE)
      // when stdout is not a terminal; keep the plain stderr this seeder reports.
      pb.environment().put("DUCKDB_AGENT_MODE", "0")
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
      defaultDataPath: String,
      sf: Double,
      storageSql: List[String],
      redact: String => String,
      duckdb: String = "duckdb",
      maxAttempts: Int = 3
  ): Either[String, Unit] =
    // `PoolSupervisor.effectiveMetastoreFor` derives a DuckLake tenant-db's dataPath by replacing
    // the LAST path segment of `defaultMetastore.dataPath` (`defaultDataPath` here, per
    // DemoConfig.overlay) with the tenant-db's name (`PoolSupervisor.replaceLastSegment`) -- e.g.
    // `.../ducklake` -> `.../acme_tpch`, NOT a subdirectory of `.../ducklake`. Seeding must ATTACH
    // at that SAME computed path, or the manager's own node later ATTACHes a physically different
    // (empty) directory and reports `Catalog "acme_tpch" does not exist!` even though the seed
    // "succeeded" against the wrong path. See DuckLake's absolute-DATA_PATH matching requirement
    // (class doc above).
    val dataPath =
      ai.starlake.quack.ondemand.PoolSupervisor.replaceLastSegment(defaultDataPath, dbName)
    val firstSql      = buildInitSql(pg, dbName, schema, dataPath, sf, storageSql)
    lazy val retrySql = buildRetryInitSql(pg, dbName, schema, dataPath, sf, storageSql)
    logger.info(s"demo seed: dbgen sf=$sf into $dbName.$schema at $dataPath")
    @scala.annotation.tailrec
    def attempt(n: Int, sql: String): Either[String, Unit] =
      val (exit, rawErr) = runOnce(duckdb, sql)
      // DuckDB's errors can quote the statement, and the storage SQL holds keys.
      val err = redact(rawErr)
      if exit == 0 then Right(())
      else if n < maxAttempts then
        logger.warn(s"demo seed: attempt $n/$maxAttempts failed (exit $exit): $err -- retrying")
        attempt(n + 1, retrySql)
      else Left(s"demo seed failed after $maxAttempts attempts (exit $exit): $err")
    attempt(1, firstSql)
