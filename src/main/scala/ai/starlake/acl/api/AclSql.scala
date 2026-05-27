package ai.starlake.acl.api

import ai.starlake.acl.{AclError, AuthorizationOutcome, Timing, TraceInfo}
import ai.starlake.acl.model.*
import ai.starlake.acl.parser.{SqlParser, StatementResult}
import ai.starlake.acl.policy.{AclEvaluator, ResourceLookupResult}
import ai.starlake.acl.store.{AclStore, LocalAclStore}

import java.nio.file.Path
import java.time.Instant

/** Tenant-aware SQL authorization API.
  *
  * Each tenant has isolated ACL definitions loaded from an AclStore. Grants
  * are cached per-tenant with optional LRU eviction.
  *
  * @param store
  *   Storage backend for reading ACL files
  * @param viewResolver
  *   Callback that classifies table references per tenant
  * @param config
  *   API configuration (caching, SQL defaults)
  */
final class AclSql(
    val store: AclStore,
    val viewResolver: (TenantId, TableRef) => ResourceLookupResult,
    val config: AclSqlConfig = AclSqlConfig.default
):

  private val cache = new TenantCache(store, config.maxTenants)

  /** Check access for a SQL statement. */
  def checkAccess(
      tenant: TenantId,
      sql: String,
      user: UserIdentity,
      trace: Boolean = false,
      sqlContext: SqlContext = SqlContext.default
  ): Either[AclError, AuthorizationOutcome] =
    checkAccessAll(tenant, sql, user, trace, sqlContext).headOption.getOrElse(
      Left(AclError.InternalError("No statements found in SQL input"))
    )

  /** String overload for tenant parameter. */
  def checkAccess(
      tenant: String,
      sql: String,
      user: UserIdentity,
      trace: Boolean,
      sqlContext: SqlContext
  ): Either[AclError, AuthorizationOutcome] =
    TenantId.parse(tenant) match
      case Right(tid) => checkAccess(tid, sql, user, trace, sqlContext)
      case Left(err) =>
        Left(AclError.ConfigError(s"Invalid tenant ID: $err"))

  /** Check access for multiple SQL statements. */
  def checkAccessAll(
      tenant: TenantId,
      sql: String,
      user: UserIdentity,
      trace: Boolean = false,
      sqlContext: SqlContext = SqlContext.default
  ): List[Either[AclError, AuthorizationOutcome]] =
    // Convert SqlContext to internal Config once per call
    val sqlConfig = sqlContext.dialect.toLowerCase match
      case "duckdb" => Config.forDuckDB(sqlContext.defaultDatabase, sqlContext.defaultSchema)
      case _        => Config.forGeneric(sqlContext.defaultDatabase, sqlContext.defaultSchema)

    // Load grants from cache
    val (policyResult, usedStale) = cache.getOrLoad(tenant)

    val now = sqlContext.now.getOrElse(Instant.now())

    policyResult match
      case Left(err: AclError.TenantNotFound) =>
        List(Left(err))
      case Left(err) =>
        List(Left(err))
      case Right(policy) =>
        processStatements(tenant, sql, user, policy, trace, usedStale, sqlConfig, now, sqlContext.maxViewDepth)

  /** Invalidate cached grants for a tenant. Next checkAccess reloads. */
  def invalidateTenant(tenant: TenantId): Unit = cache.invalidate(tenant)

  /** Invalidate all cached grants. */
  def invalidateAll(): Unit = cache.invalidateAll()

  /** Get cache status for a tenant. */
  def tenantStatus(tenant: TenantId): TenantStatus = cache.status(tenant)

  private def processStatements(
      tenant: TenantId,
      sql: String,
      user: UserIdentity,
      policy: AclPolicy,
      trace: Boolean,
      usedStale: Boolean,
      sqlConfig: Config,
      now: Instant,
      maxViewDepth: Int
  ): List[Either[AclError, AuthorizationOutcome]] =
    val t0 = System.nanoTime()
    val extraction = SqlParser.extract(sql, sqlConfig)
    val parseNanos = System.nanoTime() - t0

    if extraction.statements.isEmpty then
      List(Left(AclError.InternalError("No statements found in SQL input")))
    else
      extraction.statements.map { stmt =>
        processStatement(tenant, stmt, user, policy, sqlConfig, trace, parseNanos, usedStale, now, maxViewDepth)
      }

  private def processStatement(
      tenant: TenantId,
      stmt: StatementResult,
      user: UserIdentity,
      policy: AclPolicy,
      sqlConfig: Config,
      trace: Boolean,
      parseNanos: Long,
      usedStale: Boolean,
      now: Instant,
      maxViewDepth: Int
  ): Either[AclError, AuthorizationOutcome] = stmt match
    case StatementResult.ParseError(_, snippet, message) =>
      Left(AclError.SqlParseError(snippet, message))

    case StatementResult.NonSelect(_, snippet, stmtType) =>
      Left(AclError.SqlParseError(snippet, s"Non-SELECT statement not supported: $stmtType"))

    case StatementResult.Extracted(_, snippet, tables, qualErrors) =>
      if tables.isEmpty && qualErrors.nonEmpty then
        buildQualErrorResult(tenant, snippet, user, qualErrors, parseNanos, usedStale, trace)
      else
        evaluateAndBuildResult(tenant, snippet, tables, qualErrors, user, policy, sqlConfig, parseNanos, usedStale, trace, now, maxViewDepth)

  private def buildQualErrorResult(
      tenant: TenantId,
      sql: String,
      user: UserIdentity,
      qualErrors: List[DenyReason],
      parseNanos: Long,
      usedStale: Boolean,
      trace: Boolean
  ): Either[AclError, AuthorizationOutcome] =
    val tableAccesses = qualErrors.map { reason =>
      TableAccess(
        table = dummyTableRef(reason),
        decision = Decision.Denied,
        matchedGrant = None,
        denyReason = Some(reason),
        grantType = None,
        isView = false
      )
    }
    val result = AccessResult(
      decision = Decision.Denied,
      sql = sql,
      user = user,
      timestamp = Instant.now(),
      tableAccesses = tableAccesses,
      viewResolutions = Nil,
      tenantId = Some(tenant),
      usedStaleGrants = usedStale
    )
    val timing = Timing(parseNanos, 0L)
    val traceInfo = if trace then Some(TraceInfo(Nil, Map.empty, tableAccesses)) else None
    Right(AuthorizationOutcome(result, timing, traceInfo))

  private def evaluateAndBuildResult(
      tenant: TenantId,
      sql: String,
      tables: Set[TableRef],
      qualErrors: List[DenyReason],
      user: UserIdentity,
      policy: AclPolicy,
      sqlConfig: Config,
      parseNanos: Long,
      usedStale: Boolean,
      trace: Boolean,
      now: Instant,
      maxViewDepth: Int
  ): Either[AclError, AuthorizationOutcome] =
    val t1 = System.nanoTime()
    val result = AclEvaluator.evaluateWithViews(
      tables,
      user,
      policy,
      sql,
      sqlConfig,
      tenant,
      viewResolver,
      None,
      now,
      maxViewDepth
    )
    val evalNanos = System.nanoTime() - t1

    // Add tenant context and stale flag
    val enrichedResult = result.copy(
      tenantId = Some(tenant),
      usedStaleGrants = usedStale
    )

    // Add qualification errors if any
    val finalResult = if qualErrors.nonEmpty then
      val qualAccesses = qualErrors.map { reason =>
        TableAccess(
          table = dummyTableRef(reason),
          decision = Decision.Denied,
          matchedGrant = None,
          denyReason = Some(reason),
          grantType = None,
          isView = false
        )
      }
      val allAccesses = enrichedResult.tableAccesses ++ qualAccesses
      val finalDecision =
        if allAccesses.exists(_.decision == Decision.Denied) then Decision.Denied
        else Decision.Allowed
      enrichedResult.copy(
        decision = finalDecision,
        tableAccesses = allAccesses
      )
    else enrichedResult

    val timing = Timing(parseNanos, evalNanos)
    val traceInfo = if trace then
      Some(
        TraceInfo(
          finalResult.viewResolutions,
          finalResult.resolutionMap,
          finalResult.tableAccesses
        )
      )
    else None

    Right(AuthorizationOutcome(finalResult, timing, traceInfo))

  private def dummyTableRef(reason: DenyReason): TableRef = reason match
    case DenyReason.UnqualifiedTable(name, _)  => TableRef("unknown", "unknown", name)
    case DenyReason.NoMatchingGrant(table, _)  => table
    case DenyReason.UnknownView(viewRef)       => viewRef
    case DenyReason.ViewResolutionCycle(chain) => chain.headOption.getOrElse(TableRef("unknown", "unknown", "unknown"))
    case DenyReason.ViewParseError(viewRef, _) => viewRef
    case DenyReason.CallbackError(table, _)    => table
    case DenyReason.UnsupportedStatement(_)    => TableRef("unknown", "unknown", "unknown")
    case DenyReason.ParseError(_)              => TableRef("unknown", "unknown", "unknown")

    case DenyReason.ExpiredGrant(table, _, _) => table
    case DenyReason.MaxViewDepthExceeded(path) => path.headOption.getOrElse(TableRef("unknown", "unknown", "unknown"))

object AclSql:

  /** Create an AclSql instance with defaults using an AclStore. */
  def apply(
      store: AclStore,
      viewResolver: (TenantId, TableRef) => ResourceLookupResult
  ): AclSql = new AclSql(store, viewResolver, AclSqlConfig.default)

  /** Create an AclSql instance with defaults using a local filesystem path.
    *
    * @deprecated Use apply(store, viewResolver) instead
    */
  @deprecated("Use apply(store, viewResolver) instead", "2.0")
  def apply(
      basePath: Path,
      viewResolver: (TenantId, TableRef) => ResourceLookupResult
  ): AclSql = new AclSql(new LocalAclStore(basePath), viewResolver, AclSqlConfig.default)

  /** Create AclSql with an attached TenantWatcher for automatic cache invalidation.
    *
    * @deprecated Use AclStoreFactory for cloud-aware store + detector creation
    */
  @deprecated("Use AclStoreFactory for cloud-aware store + detector creation", "2.0")
  def withWatcher(
      basePath: Path,
      viewResolver: (TenantId, TableRef) => ResourceLookupResult,
      config: AclSqlConfig = AclSqlConfig.default,
      watcherConfig: ai.starlake.acl.watcher.WatcherConfig =
        ai.starlake.acl.watcher.WatcherConfig.default
  ): (AclSql, ai.starlake.acl.watcher.TenantWatcher) =
    val store = new LocalAclStore(basePath)
    val api = new AclSql(store, viewResolver, config)

    val listener = new ai.starlake.acl.watcher.TenantListener:
      override def onInvalidate(tenantId: TenantId): Unit =
        api.invalidateTenant(tenantId)

      override def onNewTenant(tenantId: TenantId): Unit =
        () // No action needed - will be loaded on first access

      override def onTenantDeleted(tenantId: TenantId): Unit =
        api.invalidateTenant(tenantId)

    val watcher =
      new ai.starlake.acl.watcher.TenantWatcher(basePath, listener, watcherConfig)
    (api, watcher)
