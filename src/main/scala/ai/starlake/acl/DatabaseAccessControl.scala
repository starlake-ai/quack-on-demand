package ai.starlake.acl

import ai.starlake.acl.api.SqlContext
import ai.starlake.acl.model.*
import ai.starlake.acl.parser.{SqlParser, StatementResult}
import ai.starlake.acl.policy.{AclEvaluator, ResolutionMode, ResourceLookupResult}

import java.time.Instant

/** Public API facade for SQL authorization checking.
  *
  * Orchestrates the parsing, evaluation, and view resolution pipeline behind
  * a clean, thread-safe interface. Use the builder pattern to construct instances:
  *
  * {{{
  * val dac = DatabaseAccessControl.builder()
  *   .policy(myPolicy)
  *   .build()
  *
  * val sqlCtx = SqlContext(Some("mydb"), Some("public"), "duckdb")
  * dac match {
  *   case Right(ctrl) =>
  *     val result = ctrl.authorize(sql, user, lookup, sqlContext = sqlCtx)
  *     if result.isAllowed then println("Access granted")
  *   case Left(err) =>
  *     println(s"Configuration error: ${err.message}")
  * }
  * }}}
  */
final class DatabaseAccessControl private (
    val policy: AclPolicy,
    val modeOverride: Option[ResolutionMode]
) {

  /** Authorize a single SQL statement.
    *
    * For multi-statement SQL, use authorizeAll() instead.
    *
    * @param sql
    *   The SQL query to authorize
    * @param user
    *   The user identity to check
    * @param lookup
    *   Callback that classifies table references (BaseTable, View, Unknown)
    * @param trace
    *   Whether to include trace information in the result
    * @param sqlContext
    *   SQL context (database, schema, dialect) for this call
    * @return
    *   Either an error or the authorization outcome
    */
  def authorize(
      sql: String,
      user: UserIdentity,
      lookup: TableRef => ResourceLookupResult,
      trace: Boolean = false,
      sqlContext: SqlContext = SqlContext.default
  ): Either[AclError, AuthorizationOutcome] = {
    val results = authorizeAll(sql, user, lookup, trace, sqlContext)
    results.headOption.getOrElse(
      Left(AclError.InternalError("No statements found in SQL input"))
    )
  }

  /** Authorize multiple SQL statements.
    *
    * Parses the input as multi-statement SQL and returns a result for each statement.
    * Parse errors, non-SELECT statements, and successful evaluations are all represented.
    *
    * @param sql
    *   The SQL string (may contain multiple semicolon-separated statements)
    * @param user
    *   The user identity to check
    * @param lookup
    *   Callback that classifies table references (BaseTable, View, Unknown)
    * @param trace
    *   Whether to include trace information in each result
    * @param sqlContext
    *   SQL context (database, schema, dialect) for this call
    * @return
    *   List of Either[AclError, AuthorizationOutcome] for each statement
    */
  def authorizeAll(
      sql: String,
      user: UserIdentity,
      lookup: TableRef => ResourceLookupResult,
      trace: Boolean = false,
      sqlContext: SqlContext = SqlContext.default
  ): List[Either[AclError, AuthorizationOutcome]] = {
    // Convert SqlContext to internal Config once per call
    val sqlConfig = sqlContext.dialect.toLowerCase match
      case "duckdb" => Config.forDuckDB(sqlContext.defaultDatabase, sqlContext.defaultSchema)
      case _        => Config.forGeneric(sqlContext.defaultDatabase, sqlContext.defaultSchema)

    val now = sqlContext.now.getOrElse(Instant.now())

    // Measure parse timing
    val t0 = System.nanoTime()
    val extraction = SqlParser.extract(sql, sqlConfig)
    val parseNanos = System.nanoTime() - t0

    if extraction.statements.isEmpty then
      // Empty input - return error for empty SQL
      List(Left(AclError.InternalError("No statements found in SQL input")))
    else
      extraction.statements.map { stmt =>
        processStatement(stmt, user, lookup, trace, parseNanos, sqlConfig, now, sqlContext.maxViewDepth)
      }
  }

  /** Create a new instance with a different policy. */
  def withPolicy(newPolicy: AclPolicy): DatabaseAccessControl =
    new DatabaseAccessControl(newPolicy, modeOverride)

  private def processStatement(
      stmt: StatementResult,
      user: UserIdentity,
      lookup: TableRef => ResourceLookupResult,
      trace: Boolean,
      parseNanos: Long,
      sqlConfig: Config,
      now: Instant,
      maxViewDepth: Int
  ): Either[AclError, AuthorizationOutcome] = stmt match {

    case StatementResult.ParseError(_, snippet, message) =>
      Left(AclError.SqlParseError(snippet, message))

    case StatementResult.NonSelect(_, snippet, stmtType) =>
      Left(AclError.SqlParseError(snippet, s"Non-SELECT statement not supported: $stmtType"))

    case StatementResult.Extracted(_, snippet, tables, qualErrors) =>
      if tables.isEmpty && qualErrors.nonEmpty then
        // Only qualification errors, no resolved tables -- build denied result
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
        val deniedResult = AccessResult(
          decision = Decision.Denied,
          sql = snippet,
          user = user,
          timestamp = Instant.now(),
          tableAccesses = tableAccesses,
          viewResolutions = Nil
        )
        val timing = Timing(parseNanos, 0L)
        val traceInfo = if trace then Some(TraceInfo(Nil, Map.empty, tableAccesses)) else None
        Right(AuthorizationOutcome(deniedResult, timing, traceInfo))
      else
        // Measure evaluate/resolve timing
        val t1 = System.nanoTime()
        val result = AclEvaluator.evaluateWithViews(
          tables,
          user,
          policy,
          snippet,
          sqlConfig,
          lookup,
          modeOverride,
          now,
          maxViewDepth
        )
        val evalNanos = System.nanoTime() - t1

        // Add qualification errors as additional denied TableAccess entries if any
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
          val allAccesses = result.tableAccesses ++ qualAccesses
          val finalDecision =
            if allAccesses.exists(_.decision == Decision.Denied) then Decision.Denied
            else Decision.Allowed
          result.copy(
            decision = finalDecision,
            tableAccesses = allAccesses
          )
        else result

        val timing = Timing(parseNanos, evalNanos)
        val traceInfo = if trace then
          Some(TraceInfo(
            finalResult.viewResolutions,
            finalResult.resolutionMap,
            finalResult.tableAccesses
          ))
        else None

        Right(AuthorizationOutcome(finalResult, timing, traceInfo))
  }

  private def dummyTableRef(reason: DenyReason): TableRef = reason match {
    case DenyReason.UnqualifiedTable(name, _)   => TableRef("unknown", "unknown", name)
    case DenyReason.NoMatchingGrant(table, _)   => table
    case DenyReason.UnknownView(viewRef)        => viewRef
    case DenyReason.ViewResolutionCycle(chain)  => chain.headOption.getOrElse(TableRef("unknown", "unknown", "unknown"))
    case DenyReason.ViewParseError(viewRef, _)  => viewRef
    case DenyReason.CallbackError(table, _)     => table
    case DenyReason.UnsupportedStatement(_)     => TableRef("unknown", "unknown", "unknown")
    case DenyReason.ParseError(_)               => TableRef("unknown", "unknown", "unknown")

    case DenyReason.ExpiredGrant(table, _, _)  => table
    case DenyReason.MaxViewDepthExceeded(path) => path.headOption.getOrElse(TableRef("unknown", "unknown", "unknown"))
  }
}

object DatabaseAccessControl {

  /** Create a new builder for constructing a DatabaseAccessControl instance. */
  def builder(): Builder = new Builder()

  /** Direct constructor (no validation).
    *
    * Prefer using the builder for configuration validation.
    */
  def apply(policy: AclPolicy): DatabaseAccessControl =
    new DatabaseAccessControl(policy, None)

  /** Fluent builder for DatabaseAccessControl.
    *
    * Collects policies and optional mode override. SQL context (database, schema,
    * dialect) is provided per-call via SqlContext, not at build time.
    */
  final class Builder private[acl] () {
    private var policies: List[AclPolicy] = Nil
    private var modeOverride: Option[ResolutionMode] = None

    /** Add a single policy. */
    def policy(p: AclPolicy): Builder = {
      policies = policies :+ p
      this
    }

    /** Add multiple policies. */
    def policies(ps: List[AclPolicy]): Builder = {
      policies = policies ++ ps
      this
    }

    /** Set the resolution mode override. */
    def mode(m: ResolutionMode): Builder = {
      modeOverride = Some(m)
      this
    }

    /** Build the DatabaseAccessControl instance.
      *
      * @return
      *   Either a configuration error or a valid instance
      */
    def build(): Either[AclError, DatabaseAccessControl] = {
      if policies.isEmpty then
        return Left(AclError.ConfigError("At least one policy is required"))

      // Merge grants from all policies
      val allGrants = policies.flatMap(_.grants)
      val mergedMode = modeOverride.orElse(policies.headOption.map(_.mode)).getOrElse(ResolutionMode.Strict)
      val mergedPolicy = AclPolicy(allGrants, mergedMode)

      Right(new DatabaseAccessControl(mergedPolicy, modeOverride))
    }
  }

  // ---------------------------------------------------------------------------
  // Extension methods for ergonomic result handling
  // ---------------------------------------------------------------------------

  extension (result: Either[AclError, AuthorizationOutcome])

    /** True if the result is a successful authorization (allowed). */
    def isAllowed: Boolean = result match {
      case Right(outcome) => outcome.isAllowed
      case Left(_)        => false
    }

    /** True if the result is a successful authorization but denied. */
    def isDenied: Boolean = result match {
      case Right(outcome) => outcome.isDenied
      case Left(_)        => false
    }

    /** True if the result is an error (parse error, config error, etc.). */
    def isError: Boolean = result.isLeft

    /** Extract the outcome if present. */
    def outcome: Option[AuthorizationOutcome] = result.toOption

    /** Extract the error if present. */
    def error: Option[AclError] = result.left.toOption
}
