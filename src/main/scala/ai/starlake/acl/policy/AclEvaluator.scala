package ai.starlake.acl.policy

import ai.starlake.acl.model.*

import java.time.Instant
import scala.collection.mutable

object AclEvaluator:

  /** Evaluate whether a user has SELECT access to a set of tables according to the given policy.
    *
    * For each table, checks the policy's grants (via a GrantIndex) for authorization at table,
    * schema, and database levels. Returns an AccessResult with per-table decisions and an overall
    * decision (Denied if ANY table is denied, Allowed if ALL are allowed).
    *
    * This is the backward-compatible method that does NOT resolve views.
    *
    * @param tables
    *   The set of tables to check access for
    * @param user
    *   The user identity (name + groups) to check
    * @param policy
    *   The ACL policy containing grants
    * @param sql
    *   The original SQL query string (included in result for traceability)
    * @param now
    *   Evaluation timestamp for grant expiration checks
    * @return
    *   AccessResult with per-table and overall access decisions
    */
  def evaluate(
      tables: Set[TableRef],
      user: UserIdentity,
      policy: AclPolicy,
      sql: String,
      now: Instant = Instant.now()
  ): AccessResult =
    val index = GrantIndex.build(policy.grants, now)

    val tableAccesses = tables.toList.map { table =>
      index.checkAccess(table, user) match
        case GrantMatchResult.Matched(_) =>
          TableAccess(
            table = table,
            decision = Decision.Allowed,
            matchedGrant = None,
            denyReason = None
          )
        case GrantMatchResult.Expired(expiredAt) =>
          TableAccess(
            table = table,
            decision = Decision.Denied,
            matchedGrant = None,
            denyReason = Some(DenyReason.ExpiredGrant(table, user, expiredAt))
          )
        case GrantMatchResult.NoMatch if policy.mode == ResolutionMode.DefaultAllow && !index.hasAnyGrant(table) =>
          TableAccess(
            table = table,
            decision = Decision.Allowed,
            matchedGrant = None,
            denyReason = None
          )
        case GrantMatchResult.NoMatch =>
          TableAccess(
            table = table,
            decision = Decision.Denied,
            matchedGrant = None,
            denyReason = Some(DenyReason.NoMatchingGrant(table, user))
          )
    }

    val overallDecision =
      if tableAccesses.isEmpty then Decision.Allowed
      else if tableAccesses.exists(_.decision == Decision.Denied) then Decision.Denied
      else Decision.Allowed

    AccessResult(
      decision = overallDecision,
      sql = sql,
      user = user,
      timestamp = Instant.now(),
      tableAccesses = tableAccesses,
      viewResolutions = Nil
    )

  /** Evaluate whether a user has SELECT access to a set of tables with view resolution.
    *
    * Resolves views using the provided lookup callback, respects authorized grant boundaries
    * (opaque views), and handles strict/permissive modes for unknown tables.
    *
    * For regular (transparent) views: user needs grant on the view AND all base tables.
    * For authorized (opaque) views: user needs only the view grant; base tables are bypassed.
    * The authorized flag is per-grant, so the same view can be opaque for one user and
    * transparent for another.
    *
    * @param tables
    *   The set of tables extracted from the SQL query
    * @param user
    *   The user identity (name + groups) to check
    * @param policy
    *   The ACL policy containing grants and resolution mode
    * @param sql
    *   The original SQL query string (included in result for traceability)
    * @param config
    *   SQL parsing configuration for re-parsing view SQL
    * @param tenant
    *   Tenant context for view resolution
    * @param lookup
    *   Tenant-aware callback that classifies table references as BaseTable, View(sql), or Unknown
    * @param modeOverride
    *   Optional mode override (takes precedence over policy.mode)
    * @param now
    *   Evaluation timestamp for grant expiration checks
    * @return
    *   AccessResult with per-table and overall access decisions, view resolutions, and resolution map
    */
  def evaluateWithViews(
      tables: Set[TableRef],
      user: UserIdentity,
      policy: AclPolicy,
      sql: String,
      config: Config,
      tenant: TenantId,
      lookup: (TenantId, TableRef) => ResourceLookupResult,
      modeOverride: Option[ResolutionMode],
      now: Instant,
      maxViewDepth: Int = 50
  ): AccessResult =
    val mode = modeOverride.getOrElse(policy.mode)
    val index = GrantIndex.build(policy.grants, now)
    val resolver = new ViewResolver(tenant, lookup, config, maxViewDepth)
    val resolutionResult = resolver.resolve(tables)

    val allAccesses = mutable.ListBuffer.empty[TableAccess]
    val viewResolutions = mutable.ListBuffer.empty[ViewResolution]

    // Process each original table through its resolution
    for table <- tables do
      val resolution = resolutionResult.resolutions(table)
      val accesses = walkResolution(table, resolution, index, user, mode)
      allAccesses ++= accesses

    // Build ViewResolution entries from the resolution map
    for (view, baseTables) <- resolutionResult.resolutionMap do
      viewResolutions += ViewResolution(view, baseTables.toList)

    val tableAccessList = allAccesses.toList

    val overallDecision =
      if tableAccessList.isEmpty then Decision.Allowed
      else if tableAccessList.exists(_.decision == Decision.Denied) then Decision.Denied
      else Decision.Allowed

    AccessResult(
      decision = overallDecision,
      sql = sql,
      user = user,
      timestamp = Instant.now(),
      tableAccesses = tableAccessList,
      viewResolutions = viewResolutions.toList,
      resolutionMap = resolutionResult.resolutionMap
    )

  /** Backward-compatible overload for v1.0 API (DatabaseAccessControl).
    *
    * Uses a dummy tenant and wraps the old-style lookup callback.
    *
    * @param tables
    *   The set of tables extracted from the SQL query
    * @param user
    *   The user identity (name + groups) to check
    * @param policy
    *   The ACL policy containing grants and resolution mode
    * @param sql
    *   The original SQL query string (included in result for traceability)
    * @param config
    *   SQL parsing configuration for re-parsing view SQL
    * @param lookup
    *   Callback that classifies table references as BaseTable, View(sql), or Unknown
    * @param modeOverride
    *   Optional mode override (takes precedence over policy.mode)
    * @param now
    *   Evaluation timestamp for grant expiration checks
    * @return
    *   AccessResult with per-table and overall access decisions, view resolutions, and resolution map
    */
  def evaluateWithViews(
      tables: Set[TableRef],
      user: UserIdentity,
      policy: AclPolicy,
      sql: String,
      config: Config,
      lookup: TableRef => ResourceLookupResult,
      modeOverride: Option[ResolutionMode],
      now: Instant,
      maxViewDepth: Int
  ): AccessResult =
    // v1.0 compatibility: wrap old callback and use dummy tenant
    val tenantAwareLookup: (TenantId, TableRef) => ResourceLookupResult =
      (_, ref) => lookup(ref)
    // Parse always succeeds for valid pattern "_v1_compat_"
    val dummyTenant = TenantId.parse("_v1_compat_") match
      case Right(t) => t
      case Left(_)  => throw new IllegalStateException("Dummy tenant ID failed to parse")
    evaluateWithViews(tables, user, policy, sql, config, dummyTenant, tenantAwareLookup, modeOverride, now, maxViewDepth)

  /** Backward-compatible overload without now parameter. */
  def evaluateWithViews(
      tables: Set[TableRef],
      user: UserIdentity,
      policy: AclPolicy,
      sql: String,
      config: Config,
      lookup: TableRef => ResourceLookupResult
  ): AccessResult =
    evaluateWithViews(tables, user, policy, sql, config, lookup, None, Instant.now(), 50)

  /** Walk a resolution tree and produce TableAccess entries for every table encountered.
    *
    * Respects authorized grant boundaries: if a view has an authorized grant for the user,
    * base tables are NOT checked (opaque boundary).
    */
  /** In DefaultAllow mode, enforce grants if one exists for this table; otherwise allow. */
  private def defaultAllowCheck(
      table: TableRef,
      index: GrantIndex,
      user: UserIdentity,
      isView: Boolean,
      warnings: List[String] = Nil
  ): List[TableAccess] =
    if index.hasAnyGrant(table) then
      index.checkAccess(table, user) match
        case GrantMatchResult.Matched(authorized) =>
          List(TableAccess(
            table = table,
            decision = Decision.Allowed,
            matchedGrant = None,
            denyReason = None,
            grantType = Some(if authorized then GrantType.Authorized else GrantType.Regular),
            isView = isView,
            warnings = warnings
          ))
        case GrantMatchResult.Expired(expiredAt) =>
          List(TableAccess(
            table = table,
            decision = Decision.Denied,
            matchedGrant = None,
            denyReason = Some(DenyReason.ExpiredGrant(table, user, expiredAt)),
            grantType = None,
            isView = isView
          ))
        case GrantMatchResult.NoMatch =>
          List(TableAccess(
            table = table,
            decision = Decision.Denied,
            matchedGrant = None,
            denyReason = Some(DenyReason.NoMatchingGrant(table, user)),
            grantType = None,
            isView = isView
          ))
    else
      List(TableAccess(
        table = table,
        decision = Decision.Allowed,
        matchedGrant = None,
        denyReason = None,
        grantType = Some(GrantType.UnknownButAllowed),
        isView = isView,
        warnings = warnings :+ s"Table '${table.canonical}' has no grants — allowed in defaultAllow mode"
      ))

  private def walkResolution(
      table: TableRef,
      resolution: SingleTableResolution,
      index: GrantIndex,
      user: UserIdentity,
      mode: ResolutionMode
  ): List[TableAccess] =
    resolution match
      case SingleTableResolution.Base =>
        // Base table: check grant directly
        index.checkAccess(table, user) match
          case GrantMatchResult.Matched(authorized) =>
            val warnings =
              if authorized then
                List(s"Grant on base table '${table.canonical}' has authorized=true which has no effect on base tables")
              else Nil
            List(TableAccess(
              table = table,
              decision = Decision.Allowed,
              matchedGrant = None,
              denyReason = None,
              grantType = Some(GrantType.Regular),
              isView = false,
              warnings = warnings
            ))
          case GrantMatchResult.Expired(expiredAt) =>
            List(TableAccess(
              table = table,
              decision = Decision.Denied,
              matchedGrant = None,
              denyReason = Some(DenyReason.ExpiredGrant(table, user, expiredAt)),
              grantType = None,
              isView = false
            ))
          case GrantMatchResult.NoMatch if mode == ResolutionMode.DefaultAllow && !index.hasAnyGrant(table) =>
            List(TableAccess(
              table = table,
              decision = Decision.Allowed,
              matchedGrant = None,
              denyReason = None,
              grantType = Some(GrantType.UnknownButAllowed),
              isView = false,
              warnings = List(s"Table '${table.canonical}' has no grants — allowed in defaultAllow mode")
            ))
          case GrantMatchResult.NoMatch =>
            List(TableAccess(
              table = table,
              decision = Decision.Denied,
              matchedGrant = None,
              denyReason = Some(DenyReason.NoMatchingGrant(table, user)),
              grantType = None,
              isView = false
            ))

      case SingleTableResolution.UnknownRef =>
        mode match
          case ResolutionMode.Strict =>
            List(TableAccess(
              table = table,
              decision = Decision.Denied,
              matchedGrant = None,
              denyReason = Some(DenyReason.UnknownView(table)),
              grantType = None,
              isView = false
            ))
          case ResolutionMode.DefaultAllow =>
            defaultAllowCheck(table, index, user, isView = false)
          case ResolutionMode.Permissive =>
            List(TableAccess(
              table = table,
              decision = Decision.Allowed,
              matchedGrant = None,
              denyReason = None,
              grantType = Some(GrantType.UnknownButAllowed),
              isView = false,
              warnings = List(s"Unknown table '${table.canonical}' allowed in permissive mode")
            ))

      case SingleTableResolution.Error(msg) =>
        mode match
          case ResolutionMode.Strict =>
            List(TableAccess(
              table = table,
              decision = Decision.Denied,
              matchedGrant = None,
              denyReason = Some(DenyReason.CallbackError(table, msg)),
              grantType = None,
              isView = false
            ))
          case ResolutionMode.DefaultAllow =>
            defaultAllowCheck(table, index, user, isView = false,
              warnings = List(s"Callback error for '${table.canonical}': $msg"))
          case ResolutionMode.Permissive =>
            List(TableAccess(
              table = table,
              decision = Decision.Allowed,
              matchedGrant = None,
              denyReason = None,
              grantType = Some(GrantType.UnknownButAllowed),
              isView = false,
              warnings = List(s"Callback error for '${table.canonical}': $msg")
            ))

      case SingleTableResolution.ParseError(viewSql, msg) =>
        // Parse errors are always denied regardless of mode
        List(TableAccess(
          table = table,
          decision = Decision.Denied,
          matchedGrant = None,
          denyReason = Some(DenyReason.ViewParseError(table, msg)),
          grantType = None,
          isView = true
        ))

      case SingleTableResolution.Cycle(path) =>
        // Cycles are always denied regardless of mode
        List(TableAccess(
          table = table,
          decision = Decision.Denied,
          matchedGrant = None,
          denyReason = Some(DenyReason.ViewResolutionCycle(path)),
          grantType = None,
          isView = true
        ))

      case SingleTableResolution.MaxDepthExceeded(path) =>
        // Max depth exceeded is always denied regardless of mode
        List(TableAccess(
          table = table,
          decision = Decision.Denied,
          matchedGrant = None,
          denyReason = Some(DenyReason.MaxViewDepthExceeded(path)),
          grantType = None,
          isView = true
        ))

      case SingleTableResolution.ResolvedView(deps, resolved) =>
        // First check if user has a grant on the view itself
        index.checkAccess(table, user) match
          case GrantMatchResult.NoMatch if mode == ResolutionMode.DefaultAllow && !index.hasAnyGrant(table) =>
            // DefaultAllow: view has no grants at all — allow and check base tables
            val viewAccess = TableAccess(
              table = table,
              decision = Decision.Allowed,
              matchedGrant = None,
              denyReason = None,
              grantType = Some(GrantType.UnknownButAllowed),
              isView = true,
              warnings = List(s"View '${table.canonical}' has no grants — allowed in defaultAllow mode")
            )
            val depAccesses = resolved.toList.flatMap { case (depRef, depResolution) =>
              walkResolution(depRef, depResolution, index, user, mode)
            }
            viewAccess :: depAccesses

          case GrantMatchResult.NoMatch =>
            // No grant on view: denied. Don't check base tables.
            List(TableAccess(
              table = table,
              decision = Decision.Denied,
              matchedGrant = None,
              denyReason = Some(DenyReason.NoMatchingGrant(table, user)),
              grantType = None,
              isView = true
            ))

          case GrantMatchResult.Expired(expiredAt) =>
            // Expired grant on view: denied. Don't check base tables.
            List(TableAccess(
              table = table,
              decision = Decision.Denied,
              matchedGrant = None,
              denyReason = Some(DenyReason.ExpiredGrant(table, user, expiredAt)),
              grantType = None,
              isView = true
            ))

          case GrantMatchResult.Matched(authorized) if authorized =>
            // Authorized (opaque) view: stop here. Base tables NOT checked.
            List(TableAccess(
              table = table,
              decision = Decision.Allowed,
              matchedGrant = None,
              denyReason = None,
              grantType = Some(GrantType.Authorized),
              isView = true
            ))

          case GrantMatchResult.Matched(_) =>
            // Regular (transparent) view: view is allowed, but must also check all dependencies.
            val viewAccess = TableAccess(
              table = table,
              decision = Decision.Allowed,
              matchedGrant = None,
              denyReason = None,
              grantType = Some(GrantType.Regular),
              isView = true
            )
            // Recursively check all dependencies
            val depAccesses = resolved.toList.flatMap { case (depRef, depResolution) =>
              walkResolution(depRef, depResolution, index, user, mode)
            }
            viewAccess :: depAccesses
