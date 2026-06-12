package ai.starlake.quack.ondemand

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import java.sql.DriverManager

/** Pre-bootstraps a per-tenant-db DuckLake catalog before its first Quack node spawns.
  *
  * **Problem.** When a tenant-db's first pool comes up, the supervisor fans out N concurrent
  * `spawn-quack-node.sh` calls. Each spawned `duckdb` process runs `ATTACH ducklake:postgres:...`,
  * which DuckLake implements as a batch of `CREATE TABLE __ducklake_*` against that tenant-db's
  * Postgres catalog. Concurrent `CREATE TABLE` of the same name races on Postgres's
  * `pg_type_typname_nsp_index` uniqueness:
  *
  * {{{
  * ERROR: duplicate key value violates unique constraint
  *        "pg_type_typname_nsp_index"
  * DETAIL: Key (typname, typnamespace)=(ducklake_metadata, 2200) already exists.
  * }}}
  *
  * The losing node's `ATTACH` fails, the catalog stays unattached on that session, and every later
  * `USE` / `SELECT` against it errors with `Catalog "<dbName>" does not exist!`.
  *
  * **Fix.** Call `DuckLakeInitializer.initBlocking(...)` once from `PoolSupervisor.createTenantDb`,
  * right after the per-tenant-db Postgres database is provisioned and before any node can spawn
  * against it. The call opens a one-shot embedded DuckDB connection (via the same `duckdb_jdbc`
  * driver `QuackHttpClient` already uses on the embedded path), runs
  * `INSTALL/LOAD ducklake; ATTACH ...;` against that tenant-db's Postgres, then closes. After this
  * the `ducklake_*` tables exist; every per-node `ATTACH` from `spawn-quack-node.sh` is then
  * read-only on the metadata and the pg_type race cannot fire.
  *
  * The control-plane database (`qod`) is never touched by this code - it holds `qodstate_*` /
  * `slkstate_*` only.
  *
  * Idempotent on the *same* dataPath: a re-init that matches the recorded
  * `ducklake_metadata.data_path` is a no-op. Re-init with a different dataPath is rejected by
  * DuckLake itself.
  *
  * Pure JVM - no `duckdb` CLI on the manager host required (the duckdb JDBC driver ships its own
  * embedded engine), no shell-out, no extra script in the image.
  */
object DuckLakeInitializer extends LazyLogging:

  /** Blocking entry point - intended to be called from inside `PoolSupervisor.createTenantDb`
    * (already wrapped in `IO.blocking { ... }`). Skips with a warning if the metastore lacks the
    * keys needed to reach the tenant-db Postgres - the per-node ATTACH later on will surface the
    * same misconfiguration loudly.
    */
  def initBlocking(metastore: Map[String, String]): Unit =
    val dbName   = metastore.getOrElse("dbName", "")
    val dataPath = metastore.getOrElse("dataPath", "")
    if !metastore.contains("pgHost") || dbName.isEmpty || dataPath.isEmpty then
      logger.warn(
        s"DuckLake pre-init skipped: metastore missing pgHost/dbName/dataPath " +
          s"(dbName='$dbName', dataPath='$dataPath', hasPgHost=${metastore.contains("pgHost")})."
      )
    else
      val pgHost     = metastore("pgHost")
      val pgPort     = metastore.getOrElse("pgPort", "5432")
      val pgUser     = metastore.getOrElse("pgUser", "postgres")
      val pgPassword = metastore.getOrElse("pgPassword", "")
      val schemaName = metastore.getOrElse("schemaName", "main")
      runInit(pgHost, pgPort, pgUser, pgPassword, dbName, schemaName, dataPath)

  /** IO wrapper around [[initBlocking]]. Retained for callers that already compose `IO` (none today
    * inside the codebase - kept for symmetry with the rest of the bootstrap chain).
    */
  def init(metastore: Map[String, String]): IO[Unit] = IO.blocking(initBlocking(metastore))

  private def runInit(
      pgHost: String,
      pgPort: String,
      pgUser: String,
      pgPassword: String,
      dbName: String,
      schemaName: String,
      dataPath: String
  ): Unit =
    logger.info(
      s"DuckLake pre-init: ATTACHing ducklake:postgres://$pgHost:$pgPort/$dbName " +
        s"(dataPath=$dataPath, schema=$schemaName)"
    )
    Class.forName("org.duckdb.DuckDBDriver")
    Class.forName("org.postgresql.Driver")
    // Idempotently provision the target Postgres database before the
    // DuckLake ATTACH. The YAML bootstrap can run BEFORE the loader
    // scripts have created the tenant-db Postgres databases (compose's
    // `exec` loaders run after the manager comes up), so we must not
    // assume the database already exists. CREATE DATABASE cannot run
    // inside the target DB, so connect to the admin DB first.
    val adminDb = sys.env.getOrElse("PG_ADMIN_DB", "postgres")
    val adminUrl =
      s"jdbc:postgresql://$pgHost:$pgPort/${java.net.URLEncoder.encode(adminDb, "UTF-8")}"
    val adminConn = DriverManager.getConnection(adminUrl, pgUser, pgPassword)
    try
      // pg_database lookup uses a PreparedStatement bind so we never
      // SQL-format `dbName` -- which is operator-controlled today but
      // could become user-controlled if tenant-db creation ever moves
      // closer to the wire.
      val checkStmt = adminConn.prepareStatement(
        "SELECT 1 FROM pg_database WHERE datname = ?"
      )
      val exists = try
        checkStmt.setString(1, dbName)
        val rs = checkStmt.executeQuery()
        try rs.next() finally rs.close()
      finally checkStmt.close()
      if !exists then
        val createStmt = adminConn.createStatement()
        // CREATE DATABASE cannot use parameter binding for the identifier;
        // standard double-quote ident escape.
        try createStmt.execute(s"CREATE DATABASE ${quoteIdent(dbName)}")
        catch
          case t: java.sql.SQLException if t.getMessage.contains("already exists") =>
            // Lost a race with a concurrent loader; harmless.
            ()
        finally createStmt.close()
    finally
      try adminConn.close()
      catch case _: Throwable => ()

    // Side-channel Postgres connection that holds the per-dbname
    // advisory lock for the duration of the DuckLake ATTACH. Concurrent
    // initializers (e.g. multiple managers or a manager racing with a
    // K8s pod's spawn-quack-node.sh) serialize on this lock, so only
    // one process ever runs the CREATE TABLE __ducklake_metadata that
    // DuckLake's ATTACH performs on a fresh catalog. See issue #3.
    val pgUrl  = s"jdbc:postgresql://$pgHost:$pgPort/${java.net.URLEncoder.encode(dbName, "UTF-8")}"
    val pgConn = DriverManager.getConnection(pgUrl, pgUser, pgPassword)
    try
      val lockStmt = pgConn.prepareStatement(
        "SELECT pg_advisory_lock(hashtext(?))"
      )
      try
        lockStmt.setString(1, s"qod-ducklake-init:$dbName")
        lockStmt.execute()
      finally
        try lockStmt.close()
        catch case _: Throwable => ()

      val conn = DriverManager.getConnection("jdbc:duckdb:")
      try
        val stmt = conn.createStatement()
        try
          // Proxy passthrough so INSTALL works behind corporate firewalls.
          // Mirrors spawn-quack-node.sh's SET http_proxy handling. Source is
          // a sanitized env var (host:port), interpolated into a DuckDB
          // string literal via the same escape helper as ATTACH.
          proxyHostPort.foreach { hp =>
            stmt.execute(s"SET http_proxy = ${duckdbLiteral(hp)}")
          }
          stmt.execute("INSTALL ducklake; LOAD ducklake;")
          stmt.execute("INSTALL postgres; LOAD postgres;")
          storageSqlFor(dataPath).foreach(stmt.execute)
          // Two-layer escape: libpq parses the post-`ducklake:postgres:`
          // payload as a keyword=value connstring, which is itself wrapped
          // in a DuckDB string literal. Naive single-quote doubling alone
          // breaks for an apostrophe in pgPassword: `foo'bar` ->
          // `password=foo''bar` -> DuckDB un-escapes to `password=foo'bar`
          // -> libpq parses `password=foo` and chokes on the orphan
          // `'bar`. [[libpqValue]] wraps + escapes per libpq's rules,
          // [[duckdbLiteral]] then wraps the whole thing in a DuckDB
          // literal and doubles any `'` that appears (including the ones
          // [[libpqValue]] introduced).
          val connstr =
            s"ducklake:postgres:" +
              s"host=${libpqValue(pgHost)} " +
              s"port=${libpqValue(pgPort)} " +
              s"dbname=${libpqValue(dbName)} " +
              s"user=${libpqValue(pgUser)} " +
              s"password=${libpqValue(pgPassword)}"
          val attach =
            s"ATTACH ${duckdbLiteral(connstr)} " +
              s"AS ${quoteIdent(dbName)} (DATA_PATH ${duckdbLiteral(dataPath)})"
          stmt.execute(attach)
          stmt.execute(s"USE ${quoteIdent(dbName)}")
          stmt.execute(s"CREATE SCHEMA IF NOT EXISTS ${quoteIdent(schemaName)}")
          logger.info(s"DuckLake pre-init OK: $dbName.$schemaName ready at $dataPath")
        finally
          try stmt.close()
          catch case _: Throwable => ()
      finally
        try conn.close()
        catch case _: Throwable => ()

      // Release the lock. pg_advisory_lock is session-scoped so closing
      // pgConn below would also release it, but doing it explicitly
      // shrinks the window during which the lock is held.
      val unlockStmt = pgConn.prepareStatement(
        "SELECT pg_advisory_unlock(hashtext(?))"
      )
      try
        unlockStmt.setString(1, s"qod-ducklake-init:$dbName")
        unlockStmt.execute()
      finally
        try unlockStmt.close()
        catch case _: Throwable => ()
    finally
      try pgConn.close()
      catch case _: Throwable => ()

  /** Emit the SQL needed for httpfs / azure secrets when the data path lives on object storage.
    * Same shape `spawn-quack-node.sh` produces. Reads credentials from this process's env; the
    * manager pod / native jar already exports them for the JVM to see.
    *
    * Returns Nil for local-filesystem `dataPath` values - no extension needed.
    */
  private def storageSqlFor(dataPath: String): List[String] =
    val lower = dataPath.toLowerCase
    if lower.startsWith("s3://") || lower.startsWith("s3a://") ||
      lower.startsWith("gs://") || lower.startsWith("r2://")
    then
      val install = "INSTALL httpfs; LOAD httpfs;"
      val secret  = for
        key    <- sys.env.get("QOD_S3_ACCESS_KEY_ID").filter(_.nonEmpty)
        secret <- sys.env.get("QOD_S3_SECRET_ACCESS_KEY").filter(_.nonEmpty)
      yield
        val ep = sys.env
          .getOrElse("QOD_S3_ENDPOINT", "")
          .stripPrefix("http://")
          .stripPrefix("https://")
          .stripSuffix("/")
        val region   = sys.env.getOrElse("QOD_S3_REGION", "us-east-1")
        val urlStyle = sys.env.getOrElse("QOD_S3_URL_STYLE", "path")
        val useSsl   = sys.env.getOrElse("QOD_S3_USE_SSL", "true")
        s"""CREATE OR REPLACE SECRET quack_s3 (
           |  TYPE s3,
           |  KEY_ID ${duckdbLiteral(key)},
           |  SECRET ${duckdbLiteral(secret)},
           |  REGION ${duckdbLiteral(region)},
           |  ENDPOINT ${duckdbLiteral(ep)},
           |  URL_STYLE ${duckdbLiteral(urlStyle)},
           |  USE_SSL $useSsl
           |)""".stripMargin
      List(install) ++ secret.toList
    else if lower.startsWith("az://") || lower.startsWith("azure://") ||
      lower.startsWith("abfss://")
    then
      val install = "INSTALL azure; LOAD azure;"
      val secret  = sys.env.get("QOD_AZURE_CONNECTION_STRING").filter(_.nonEmpty).map { cs =>
        s"""CREATE OR REPLACE SECRET quack_azure (
           |  TYPE azure,
           |  CONNECTION_STRING ${duckdbLiteral(cs)}
           |)""".stripMargin
      }
      List(install) ++ secret.toList
    else Nil

  private def proxyHostPort: Option[String] =
    List("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy").iterator
      .flatMap(sys.env.get)
      .map(_.trim)
      .find(_.nonEmpty)
      .map { url =>
        url.stripPrefix("http://").stripPrefix("https://").stripSuffix("/")
      }

  /** DuckDB SQL string literal: wrap in single quotes, double any embedded `'`. */
  private[ondemand] def duckdbLiteral(v: String): String =
    "'" + v.replace("'", "''") + "'"

  /** Identifier quote: wrap in `"`, double any embedded `"`. */
  private def quoteIdent(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

  /** libpq keyword-value value: always wrap in `'...'`, escape `'` as `\'` and `\` as `\\`.
    * Order matters -- the backslash escape must run before the apostrophe escape, otherwise the
    * backslash we add to `\'` gets doubled.
    */
  private[ondemand] def libpqValue(v: String): String =
    "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'"
