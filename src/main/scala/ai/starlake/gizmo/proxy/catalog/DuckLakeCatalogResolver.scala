package ai.starlake.gizmo.proxy.catalog

import ai.starlake.acl.model.{TableRef, TenantId}
import ai.starlake.acl.policy.ResourceLookupResult
import ai.starlake.gizmo.proxy.config.SessionConfig
import com.typesafe.scalalogging.LazyLogging
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.create.view.CreateView

import java.sql.{Connection, DriverManager, PreparedStatement}
import java.util.concurrent.ConcurrentHashMap

/** Resolves table references against the DuckLake catalog via an in-process DuckDB JDBC connection.
  *
  * Queries information_schema.tables and duckdb_views() to determine whether a TableRef is a base
  * table, a view (with its SQL definition), or unknown. Results are cached with a configurable TTL.
  *
  * Thread-safety: all JDBC operations are synchronized on connectionLock since DuckDB in-process
  * mode does not support concurrent queries on the same connection.
  *
  * The tenantId parameter is accepted but not used for catalog lookups — the resolver connects to a
  * single DuckLake catalog defined by SessionConfig. The parameter is part of the ViewResolver
  * callback signature for future multi-tenant catalog support.
  */
class DuckLakeCatalogResolver(
    sessionConfig: SessionConfig,
    cacheTtlMs: Long = 60_000L,
    maxCacheSize: Int = 10_000
) extends AutoCloseable,
      LazyLogging:

  private case class CachedEntry(result: ResourceLookupResult, loadedAt: Long)

  private val cache = new ConcurrentHashMap[String, CachedEntry]()
  private var connection: Connection | Null = null
  private var psIsBaseTable: PreparedStatement | Null = null
  private var psGetViewSql: PreparedStatement | Null = null
  private val connectionLock = new Object()

  /** Resolve a table reference to its catalog type.
    *
    * Fail-safe behavior: on any error (connection failure, init failure, query failure),
    * returns Unknown. In strict mode this means denied (secure default). In permissive mode
    * the admin has explicitly chosen to allow unknown tables.
    */
  def resolve(tenantId: TenantId, tableRef: TableRef): ResourceLookupResult =
    val key = tableRef.canonical

    // Check cache first (lock-free read)
    val cached = cache.get(key)
    val now = System.currentTimeMillis()
    if cached != null && (now - cached.loadedAt) < cacheTtlMs then return cached.result

    // Cache miss or expired — query catalog under lock
    try
      val result = connectionLock.synchronized {
        // Double-check cache inside lock to avoid redundant queries when
        // multiple threads wait on the same expired entry
        val rechecked = cache.get(key)
        val recheckNow = System.currentTimeMillis()
        if rechecked != null && (recheckNow - rechecked.loadedAt) < cacheTtlMs then
          rechecked.result
        else
          val conn = getOrCreateConnection()
          val res = queryTable(conn, tableRef)
          cache.put(key, CachedEntry(res, recheckNow))
          evictIfNeeded()
          res
      }
      result
    catch
      case e: Exception =>
        logger.warn(s"Catalog lookup failed for ${tableRef.canonical}: ${e.getMessage}", e)
        // Reset connection so next call attempts reconnection
        resetConnection()
        // Don't cache errors — retry on next call.
        // Return Unknown: in strict mode → denied (secure). In permissive → admin chose to allow.
        ResourceLookupResult.Unknown

  /** Evict oldest entries when cache exceeds maxCacheSize. */
  private def evictIfNeeded(): Unit =
    if cache.size() > maxCacheSize then
      import scala.jdk.CollectionConverters.*
      // Find and remove the oldest 10% of entries to avoid evicting on every put
      val entriesToRemove = cache.size() - (maxCacheSize * 9 / 10)
      cache.entrySet().asScala.toSeq
        .sortBy(_.getValue.loadedAt)
        .take(entriesToRemove)
        .foreach(e => cache.remove(e.getKey))
      logger.info(s"Cache eviction: removed $entriesToRemove oldest entries (size now ${cache.size()})")

  /** Clear all cached catalog entries. */
  def invalidateCache(): Unit =
    cache.clear()
    logger.debug("Catalog resolver cache invalidated")

  override def close(): Unit =
    connectionLock.synchronized {
      closeStatements()
      if connection != null then
        try connection.nn.close()
        catch case e: Exception => logger.warn(s"Error closing catalog connection: ${e.getMessage}")
        finally connection = null
    }

  // Note: this method acquires connectionLock internally. When called from resolve(),
  // the caller already holds the lock — this is safe because JVM synchronized is reentrant.
  // The internal lock is kept because tests call this method directly without holding the lock.
  private[catalog] def getOrCreateConnection(): Connection =
    connectionLock.synchronized {
      if connection == null || connection.nn.isClosed then
        logger.info("Opening DuckDB JDBC connection for catalog resolution")
        closeStatements()
        val conn = DriverManager.getConnection("jdbc:duckdb:")
        initConnection(conn)
        connection = conn
        prepareStatements(conn)
      connection.nn
    }

  private[catalog] def initConnection(conn: Connection): Unit =
    val initSql = buildInitSql()
    val stmt = conn.createStatement()
    try
      // Execute each SQL statement separately (DuckDB doesn't support multi-statement execute)
      initSql
        .split(";")
        .map(_.trim)
        .filter(_.nonEmpty)
        .foreach { sql =>
          logger.debug(s"Catalog init: $sql")
          stmt.execute(sql + ";")
        }
      logger.info(s"DuckDB catalog connection initialized for database '${sessionConfig.slProjectId}'")
    finally stmt.close()

  private def prepareStatements(conn: Connection): Unit =
    psIsBaseTable = conn.prepareStatement(
      """SELECT 1 FROM information_schema.tables
        |WHERE table_catalog = ? AND table_schema = ? AND table_name = ?
        |AND table_type = 'BASE TABLE'""".stripMargin
    )
    psGetViewSql = conn.prepareStatement(
      """SELECT view_definition FROM information_schema.views
        |WHERE table_catalog = ? AND table_schema = ? AND table_name = ?""".stripMargin
    )

  private def closeStatements(): Unit =
    try if psIsBaseTable != null then psIsBaseTable.nn.close()
    catch case _: Exception => ()
    finally psIsBaseTable = null
    try if psGetViewSql != null then psGetViewSql.nn.close()
    catch case _: Exception => ()
    finally psGetViewSql = null

  private def resetConnection(): Unit =
    connectionLock.synchronized {
      closeStatements()
      if connection != null then
        try connection.nn.close()
        catch case _: Exception => ()
        finally connection = null
    }

  /** Escape single quotes in SQL string literals to prevent syntax errors. */
  private def escapeSql(value: String): String = value.replace("'", "''")

  /** Escape a SQL identifier by wrapping in double quotes (DuckDB convention). */
  private def escapeIdentifier(value: String): String =
    "\"" + value.replace("\"", "\"\"") + "\""

  private[catalog] def buildInitSql(): String =
    val env = sys.env
    val dbId = sessionConfig.slProjectId
    val quotedDbId = escapeIdentifier(dbId)
    // The proxy JVM runs on the host, not inside Docker. Replace Docker's
    // host.docker.internal with localhost so the resolver can reach PostgreSQL.
    val pgHost =
      if sessionConfig.pgHost == "host.docker.internal" then "localhost"
      else sessionConfig.pgHost

    // Build optional S3 secret SQL (same logic as ProxyServer)
    val s3SecretSql =
      (env.get("AWS_KEY_ID"), env.get("AWS_SECRET"), env.get("AWS_REGION"), env.get("AWS_ENDPOINT")) match
        case (Some(keyId), Some(secret), Some(region), Some(endpoint)) =>
          val noSchemeEndpoint =
            if endpoint.contains("://") then endpoint.split("://").last
            else endpoint
          val useSSL = if endpoint.startsWith("https:") then "true" else "false"
          val urlStyle =
            if noSchemeEndpoint.contains("s3.amazonaws.com") then "vhost" else "path"

          s"""CREATE OR REPLACE SECRET s3_$dbId
             |   (TYPE s3, KEY_ID '${escapeSql(keyId)}', SECRET '${escapeSql(secret)}', REGION '${escapeSql(region)}', ENDPOINT '${escapeSql(noSchemeEndpoint)}', USE_SSL '$useSSL', URL_STYLE '$urlStyle')""".stripMargin
        case _ => ""

    val pgSecret =
      s"""CREATE OR REPLACE SECRET pg_$dbId
         |   (TYPE postgres, HOST '${escapeSql(pgHost)}', PORT ${sessionConfig.pgPort}, DATABASE $quotedDbId, USER '${escapeSql(sessionConfig.pgUsername)}', PASSWORD '${escapeSql(sessionConfig.pgPassword)}')""".stripMargin

    val ducklakeSecret =
      s"""CREATE OR REPLACE SECRET $dbId
         |   (TYPE ducklake, METADATA_PATH '', DATA_PATH '${escapeSql(sessionConfig.slDataPath)}', METADATA_PARAMETERS MAP {'TYPE': 'postgres', 'SECRET': 'pg_$dbId'})""".stripMargin

    val parts = Seq(
      "install ducklake",
      "load ducklake",
      pgSecret,
      s3SecretSql,
      ducklakeSecret,
      s"ATTACH IF NOT EXISTS 'ducklake:$dbId' AS $quotedDbId (READ_ONLY, AUTOMATIC_MIGRATION TRUE)",
      s"USE $quotedDbId"
    ).filter(_.nonEmpty)

    parts.mkString(";\n") + ";"

  private[catalog] def queryTable(conn: Connection, ref: TableRef): ResourceLookupResult =
    // Check views FIRST: DuckLake catalogs may list views as BASE TABLE in
    // information_schema.tables, so checking isBaseTable first would misidentify them.
    // information_schema.views is authoritative for view detection.
    getViewSql(ref) match
      case Some(sql) =>
        logger.debug(s"Catalog resolved ${ref.canonical} as View, SQL length=${sql.length}")
        ResourceLookupResult.View(sql)
      case None =>
        if isBaseTable(ref) then
          logger.debug(s"Catalog resolved ${ref.canonical} as BaseTable")
          ResourceLookupResult.BaseTable
        else
          logger.debug(s"Catalog resolved ${ref.canonical} as Unknown (not in information_schema.views nor information_schema.tables)")
          ResourceLookupResult.Unknown

  private def isBaseTable(ref: TableRef): Boolean =
    val ps = psIsBaseTable.nn
    ps.clearParameters()
    ps.setString(1, ref.database)
    ps.setString(2, ref.schema)
    ps.setString(3, ref.table)
    val rs = ps.executeQuery()
    try rs.next()
    finally rs.close()

  private def getViewSql(ref: TableRef): Option[String] =
    val ps = psGetViewSql.nn
    ps.clearParameters()
    ps.setString(1, ref.database)
    ps.setString(2, ref.schema)
    ps.setString(3, ref.table)
    val rs = ps.executeQuery()
    try
      if rs.next() then
        Option(rs.getString("view_definition"))
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(stripCreateViewPrefix)
      else None
    finally rs.close()

  /** DuckDB/DuckLake returns view_definition as "CREATE VIEW name AS SELECT ..."
    * but the ViewResolver expects just the SELECT statement.
    * Uses JSqlParser to properly extract the SELECT body, handling edge cases like
    * quoted view names containing " AS " and multiline SQL.
    * Falls back to regex-based extraction when JSqlParser cannot parse DuckDB-specific
    * syntax (e.g. schema-qualified functions like main.date_part()).
    */
  private[catalog] def stripCreateViewPrefix(sql: String): String =
    try
      val stmt = CCJSqlParserUtil.parse(sql)
      stmt match
        case cv: CreateView => cv.getSelect.toString
        case _ => sql // Not a CREATE VIEW, return as-is
    catch
      case _: Exception =>
        // JSqlParser failed (e.g. DuckDB-specific syntax). Try regex fallback.
        regexStripCreateView(sql).getOrElse(sql)

  /** Regex fallback for extracting SELECT body from CREATE VIEW statements.
    * Handles: CREATE [OR REPLACE] [TEMP[ORARY]] VIEW [IF NOT EXISTS] [schema.]name AS <select>
    */
  private[catalog] def regexStripCreateView(sql: String): Option[String] =
    val pattern = """(?is)^\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:TEMP(?:ORARY)?\s+)?VIEW\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:"[^"]*"|[^\s]+)\s+AS\s+(.+)$""".r
    pattern.findFirstMatchIn(sql).map(_.group(1).trim)
