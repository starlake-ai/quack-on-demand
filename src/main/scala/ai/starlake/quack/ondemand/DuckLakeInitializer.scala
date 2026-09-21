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
  * The control-plane database (`qod`) is never touched by this code - it holds `qodstate_*` only.
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
    *
    * `encrypted` is an explicit parameter rather than read off the metastore map: at the
    * catalog-creating call site (`PoolSupervisor.createTenantDb`) the metastore in hand is the
    * caller-supplied one, which is stamped with `"encrypted" -> "true"` only later by
    * `effectiveMetastoreFor` - relying on the map here would silently create the catalog
    * unencrypted. It carries no default on purpose: a defaulted `false` here is the same
    * implicit-dangerous-value hazard, one no caller would notice until the catalog was already
    * created unencrypted and unfixable.
    */
  def initBlocking(metastore: Map[String, String], encrypted: Boolean): Unit =
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
      runInit(pgHost, pgPort, pgUser, pgPassword, dbName, schemaName, dataPath, encrypted)

  /** IO wrapper around [[initBlocking]]. Retained for callers that already compose `IO` (none today
    * inside the codebase - kept for symmetry with the rest of the bootstrap chain).
    */
  def init(metastore: Map[String, String], encrypted: Boolean): IO[Unit] =
    IO.blocking(initBlocking(metastore, encrypted))

  private def runInit(
      pgHost: String,
      pgPort: String,
      pgUser: String,
      pgPassword: String,
      dbName: String,
      schemaName: String,
      dataPath: String,
      encrypted: Boolean
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
    val adminDb  = sys.env.getOrElse("PG_ADMIN_DB", "postgres")
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
      val exists =
        try
          checkStmt.setString(1, dbName)
          val rs = checkStmt.executeQuery()
          try rs.next()
          finally rs.close()
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

    val conn = DriverManager.getConnection("jdbc:duckdb:")
    try
      val stmt = conn.createStatement()
      try
        // Proxy passthrough so INSTALL works behind corporate firewalls.
        // Mirrors spawn-quack-node.sh's SET http_proxy handling. Source is
        // a sanitized env var (host:port), interpolated into a DuckDB
        // string literal via the same escape helper as ATTACH.
        //
        // Extension INSTALL/LOAD and the proxy/storage SET statements touch
        // only this DuckDB connection's own state -- no shared Postgres
        // catalog -- so they run OUTSIDE the advisory lock. Only the ATTACH
        // below (and its CREATE TABLE __ducklake_metadata) races across
        // initializers, so the lock is taken just around it. Extensions must
        // still be loaded on THIS connection before the ATTACH runs.
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
        val attach = attachSql(connstr, dbName, dataPath, encrypted)

        // Side-channel Postgres connection that holds the per-dbname
        // advisory lock only for the duration of the DuckLake ATTACH.
        // Concurrent initializers (e.g. multiple managers or a manager
        // racing with a K8s pod's spawn-quack-node.sh) serialize on this
        // lock, so only one process ever runs the CREATE TABLE
        // __ducklake_metadata that DuckLake's ATTACH performs on a fresh
        // catalog. See issue #3.
        val pgUrl =
          s"jdbc:postgresql://$pgHost:$pgPort/${java.net.URLEncoder.encode(dbName, "UTF-8")}"
        val pgConn = DriverManager.getConnection(pgUrl, pgUser, pgPassword)
        try
          // Two installations (e.g. a dev checkout and the `qod` launcher) can end up
          // sharing the same control-plane Postgres. When that happens this manager's
          // effective dataPath for `dbName` no longer matches what the OTHER installation
          // already recorded in this tenant-db's own `ducklake_metadata`, and the ATTACH
          // below would fail per-node with DuckDB's raw DATA_PATH mismatch error -- once
          // per spawn attempt, forever. Catch it here instead, before the lock and the
          // ATTACH, with one clear message naming the fix.
          guardDataPath(pgConn, dbName, dataPath)
          guardEncryption(pgConn, dbName, encrypted)

          val lockStmt = pgConn.prepareStatement(
            "SELECT pg_advisory_lock(hashtext(?))"
          )
          try
            lockStmt.setString(1, s"qod-ducklake-init:$dbName")
            lockStmt.execute()
          finally
            try lockStmt.close()
            catch case _: Throwable => ()

          stmt.execute(attach)
          stmt.execute(s"USE ${quoteIdent(dbName)}")
          stmt.execute(s"CREATE SCHEMA IF NOT EXISTS ${quoteIdent(schemaName)}")
          logger.info(s"DuckLake pre-init OK: $dbName.$schemaName ready at $dataPath")

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
      finally
        try stmt.close()
        catch case _: Throwable => ()
    finally
      try conn.close()
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
    ai.starlake.quack.model.SqlLiterals.duckdbLiteral(v)

  /** Identifier quote: wrap in `"`, double any embedded `"`. */
  private def quoteIdent(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

  /** libpq keyword-value value: always wrap in `'...'`, escape `'` as `\'` and `\` as `\\`. Order
    * matters -- the backslash escape must run before the apostrophe escape, otherwise the backslash
    * we add to `\'` gets doubled.
    */
  private[ondemand] def libpqValue(v: String): String =
    "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'"

  /** The DuckLake ATTACH, extracted so its option list is assertable without a live Postgres.
    * `ENCRYPTED` is passed on every attach, not only the one that creates the catalog: the DuckLake
    * documentation does not say whether the flag must be repeated once
    * `ducklake_metadata.encrypted` exists, and passing it always is correct under either answer.
    * This mirrors how DATA_PATH is already passed on every attach and cross-checked by
    * [[guardDataPath]].
    */
  private[ondemand] def attachSql(
      connstr: String,
      dbName: String,
      dataPath: String,
      encrypted: Boolean
  ): String =
    val opts =
      if encrypted then s"DATA_PATH ${duckdbLiteral(dataPath)}, ENCRYPTED"
      else s"DATA_PATH ${duckdbLiteral(dataPath)}"
    s"ATTACH ${duckdbLiteral(connstr)} AS ${quoteIdent(dbName)} ($opts)"

  /** Compares the catalog's recorded `ducklake_metadata.encrypted` against what the tenant-db row
    * asks for. `None` recorded means a fresh catalog, which the ATTACH is about to stamp. Same role
    * as [[guardDataPath]]: turn a per-spawn DuckLake error that repeats forever into one message
    * naming the cause and the fix.
    */
  private[ondemand] def encryptionMismatch(
      recorded: Option[String],
      wanted: Boolean
  ): Option[String] =
    recorded.map(_.trim.toLowerCase) match
      case Some("true") if !wanted =>
        Some(
          "this DuckLake catalog was created encrypted (ducklake_metadata.encrypted='true') but the " +
            "tenant-db row asks for no encryption. Encryption cannot be removed from an existing " +
            "catalog: create a new database with encrypted=false and copy the data."
        )
      case Some("false") if wanted =>
        Some(
          "this DuckLake catalog was created unencrypted (ducklake_metadata.encrypted='false') but " +
            "the tenant-db row asks for encryption. Encryption cannot be enabled on an existing " +
            "catalog: create a new database with encrypted=true and copy the data."
        )
      case _ => None

  /** A permanent disagreement between what the control-plane row says about a catalog and what the
    * catalog itself already recorded. Callers (`PoolSupervisor`) catch this type to log at ERROR
    * and refuse to proceed, unlike the generic "first pool spawn will retry" handling for other
    * init failures: none of these is transient, and retrying only reproduces the same DuckDB error
    * on every node spawn, forever.
    */
  sealed abstract class PreInitMismatchException(message: String) extends RuntimeException(message)

  /** Raised by [[guardDataPath]] when the effective dataPath this manager is about to ATTACH with
    * does not match the dataPath already recorded in the tenant-db's `ducklake_metadata`.
    */
  final case class DataPathMismatchException(message: String)
      extends PreInitMismatchException(message)

  /** Raised by [[guardEncryption]] when the tenant-db row's `encrypted` flag disagrees with the
    * catalog's recorded `ducklake_metadata.encrypted`. Permanent by construction: DuckLake stamps
    * `encrypted` once, at catalog creation, and neither engine can encrypt or decrypt an existing
    * catalog in place.
    */
  final case class EncryptionMismatchException(message: String)
      extends PreInitMismatchException(message)

  /** Strict URI scheme prefix, e.g. `s3://`, `gs://`, `az://`. Mirrors the scheme regex
    * `PoolSupervisor.replaceLastSegment` uses for the same "is this a URI root" question.
    */
  private val schemeRe = """^[a-zA-Z][a-zA-Z0-9+\-.]*://.*$""".r

  /** A path is comparable when it is unambiguous regardless of the process's cwd: an absolute
    * filesystem path (leading `/`) or a scheme URL. A relative path (`./foo`, `foo/bar`) resolves
    * differently depending on where the comparing process happens to run, so it cannot be compared
    * reliably here -- DuckDB remains the enforcer for that case.
    */
  private def isComparable(path: String): Boolean =
    path.startsWith("/") || schemeRe.matches(path)

  /** Guard against two installations sharing one control-plane Postgres while pointing tenant-db
    * `dbName` at different DuckLake storage roots. Queries `ducklake_metadata` on the tenant-db's
    * OWN Postgres connection (`pgConn`, already opened by [[runInit]]) -- never the control-plane
    * database, which holds `qodstate_*` only.
    *
    * Outcomes:
    *   - no `ducklake_metadata` table, or no `data_path` row: fresh catalog, PASS silently
    *     (`runInit`'s ATTACH will create it).
    *   - both `effectiveDataPath` and the recorded value are comparable ([[isComparable]]): PASS if
    *     equal after stripping a trailing `/`, else throw [[DataPathMismatchException]].
    *   - either side is relative: WARN with both values and PASS -- DuckDB is the enforcer here,
    *     since relative resolution depends on the attaching process's cwd and can't be compared
    *     reliably from the manager.
    */
  private[ondemand] def guardDataPath(
      pgConn: java.sql.Connection,
      dbName: String,
      effectiveDataPath: String
  ): Unit =
    val existsStmt = pgConn.prepareStatement(
      "SELECT 1 FROM information_schema.tables WHERE table_name = ? LIMIT 1"
    )
    val tableExists =
      try
        existsStmt.setString(1, "ducklake_metadata")
        val rs = existsStmt.executeQuery()
        try rs.next()
        finally rs.close()
      finally existsStmt.close()
    if tableExists then
      val valueStmt = pgConn.prepareStatement(
        "SELECT value FROM ducklake_metadata WHERE key = 'data_path' LIMIT 1"
      )
      val recordedOpt =
        try
          val rs = valueStmt.executeQuery()
          try if rs.next() then Some(rs.getString(1)) else None
          finally rs.close()
        finally valueStmt.close()
      recordedOpt.foreach { recorded =>
        if !isComparable(effectiveDataPath) || !isComparable(recorded) then
          logger.warn(
            s"DuckLake data_path check for '$dbName': at least one of the effective dataPath " +
              s"('$effectiveDataPath') or the catalog's recorded data_path ('$recorded') is a " +
              "relative path, so they cannot be compared reliably from the manager. Proceeding; " +
              "DuckDB will enforce the DATA_PATH match at ATTACH time."
          )
        else if effectiveDataPath.stripSuffix("/") != recorded.stripSuffix("/") then
          throw DataPathMismatchException(
            s"tenant-db $dbName: control-plane dataPath ($effectiveDataPath) does not match the " +
              s"DuckLake catalog's recorded data_path ($recorded). This control plane is probably " +
              "shared by two installations (e.g. a dev checkout and the qod launcher on the same " +
              "Postgres). Fix: point them at separate control planes with QOD_PG_DBNAME, or reset " +
              "this world (drop the control-plane and tenant-db databases, then re-seed), or if the " +
              "data really moved, update the database's dataPath via POST /api/database/update. " +
              "After fixing, update the database via POST /api/database/update or restart the " +
              "manager to re-attempt."
          )
      }

  /** What a `ducklake_metadata` lookup actually found. Three answers rather than two: a catalog
    * with no `ducklake_metadata` table at all is a fresh one, whereas a table that exists but
    * carries no row for the key is an ESTABLISHED catalog that simply never recorded that key.
    * Collapsing both into `None` would leave [[guardEncryption]] blind in the one case it exists
    * for, an encrypted tenant-db row pointed at a catalog whose creation never wrote the key.
    */
  private[ondemand] enum MetadataRead:
    case NoTable
    case NoRow
    case Value(value: String)

  /** The schema holding `ducklake_metadata`, or None when no schema has it. Mirrors
    * `BranchCloner.metaSchema`: prefer `public` when the table exists in more than one schema,
    * otherwise take whichever schema has it.
    */
  private def metadataSchema(pgConn: java.sql.Connection): Option[String] =
    val schemaStmt = pgConn.prepareStatement(
      "SELECT table_schema FROM information_schema.tables WHERE table_name = 'ducklake_metadata' " +
        "ORDER BY (table_schema = 'public') DESC LIMIT 1"
    )
    try
      val rs = schemaStmt.executeQuery()
      try if rs.next() then Some(rs.getString(1)) else None
      finally rs.close()
    finally schemaStmt.close()

  /** Resolves the schema holding `ducklake_metadata` and reads the `value` recorded for `key`. */
  private[ondemand] def readMetadata(
      pgConn: java.sql.Connection,
      key: String
  ): MetadataRead =
    metadataSchema(pgConn) match
      case None         => MetadataRead.NoTable
      case Some(schema) =>
        val valueStmt = pgConn.prepareStatement(
          s"SELECT value FROM ${quoteIdent(schema)}.ducklake_metadata WHERE key = ? LIMIT 1"
        )
        try
          valueStmt.setString(1, key)
          val rs = valueStmt.executeQuery()
          try
            // A row whose value is SQL NULL is no recorded value at all, and reads the same as a
            // missing row rather than producing a null-bearing Value.
            val found = if rs.next() then Option(rs.getString(1)) else None
            found.fold[MetadataRead](MetadataRead.NoRow)(MetadataRead.Value(_))
          finally rs.close()
        finally valueStmt.close()

  /** Both answers [[guardEncryption]] needs, from ONE statement: what the catalog recorded for
    * `encrypted`, and whether it is established (has recorded its own `data_path`, i.e. a DuckLake
    * ATTACH has committed the metadata rows).
    *
    * One statement rather than two because this guard runs BEFORE the advisory lock, so it can
    * observe a concurrent initializer mid-creation. Read separately, a competing ATTACH committing
    * between them yields `encrypted` = NoRow followed by `data_path` = present, which
    * [[recordedEncryption]] reads as "established and unencrypted": a genuinely encrypted catalog
    * then fails an encrypted tenant-db row with a spurious mismatch, and since the resulting block
    * is in-memory, it holds until a manager restart. Reading both keys in one snapshot makes that
    * interleaving unrepresentable.
    */
  private[ondemand] def readEncryptionState(
      pgConn: java.sql.Connection
  ): (MetadataRead, Boolean) =
    metadataSchema(pgConn) match
      case None         => (MetadataRead.NoTable, false)
      case Some(schema) =>
        val stmt = pgConn.prepareStatement(
          s"SELECT key, value FROM ${quoteIdent(schema)}.ducklake_metadata " +
            "WHERE key IN ('encrypted', 'data_path')"
        )
        try
          val rs = stmt.executeQuery()
          try
            // Same NULL handling as readMetadata: a row whose value is SQL NULL is no recorded
            // value at all, so it never enters the map and reads as a missing row.
            val found = scala.collection.mutable.Map.empty[String, String]
            while rs.next() do
              val k = rs.getString(1)
              Option(rs.getString(2)).foreach(v => found.put(k, v))
            val read =
              found.get("encrypted").fold[MetadataRead](MetadataRead.NoRow)(MetadataRead.Value(_))
            (read, found.contains("data_path"))
          finally rs.close()
        finally stmt.close()

  /** Folds a [[readMetadata]] answer for the `encrypted` key into the value [[encryptionMismatch]]
    * compares against.
    *
    * `NoRow` on an established catalog means the catalog exists and never recorded the key; read
    * that as unencrypted. DuckLake as of DuckDB 08e34c447b writes `encrypted='false'` explicitly
    * (see `DuckLakeInitializerRaceSpec`, which observes it against a real ATTACH), so this arm is
    * defensive today; it is what keeps the guard from being silently neutered if a release ever
    * omits the key, which would be exactly the case the guard was written for. `NoRow` on a catalog
    * that is not established yet is a concurrent initializer's half-written metadata: nothing to
    * compare.
    */
  private[ondemand] def recordedEncryption(
      read: MetadataRead,
      established: Boolean
  ): Option[String] =
    read match
      case MetadataRead.Value(v)             => Some(v)
      case MetadataRead.NoRow if established => Some("false")
      case MetadataRead.NoRow                => None
      case MetadataRead.NoTable              => None

  /** Fails fast when the catalog's recorded encryption disagrees with the tenant-db row. Reads
    * through the same connection and at the same point as [[guardDataPath]], before the advisory
    * lock, so the failure is one clear message rather than DuckLake's raw error once per spawn.
    *
    * A catalog with no `ducklake_metadata` yet is a fresh one: nothing to compare. A catalog that
    * HAS the table but no `encrypted` row is read through [[recordedEncryption]], which treats it
    * as unencrypted once the catalog is established.
    *
    * Throws [[EncryptionMismatchException]] rather than a bare `RuntimeException` so callers can
    * tell this permanent misconfiguration apart from a transient init failure, exactly as they do
    * for [[DataPathMismatchException]]. Routing it into the retry path would let reconcile keep
    * spawning nodes that each reproduce the mismatch, which is the outcome this guard exists to
    * prevent.
    */
  private[ondemand] def guardEncryption(
      pgConn: java.sql.Connection,
      dbName: String,
      encrypted: Boolean
  ): Unit =
    val (read, established) = readEncryptionState(pgConn)
    encryptionMismatch(recordedEncryption(read, established), encrypted).foreach { msg =>
      throw EncryptionMismatchException(s"DuckLake catalog '$dbName': $msg")
    }
