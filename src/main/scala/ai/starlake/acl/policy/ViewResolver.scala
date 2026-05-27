package ai.starlake.acl.policy

import ai.starlake.acl.model.{Config, TableRef, TenantId}
import ai.starlake.acl.parser.{SqlParser, StatementResult}

import scala.collection.mutable

/** Result of resolving a single table reference. */
enum SingleTableResolution:
  case Base
  case ResolvedView(directDependencies: Set[TableRef], resolved: Map[TableRef, SingleTableResolution])
  case Cycle(path: List[TableRef])
  case ParseError(viewSql: String, message: String)
  case UnknownRef
  case Error(message: String)
  case MaxDepthExceeded(path: List[TableRef])

/** Aggregate result of resolving a set of table references. */
final case class ViewResolutionResult(
    resolutions: Map[TableRef, SingleTableResolution],
    resolutionMap: Map[TableRef, Set[TableRef]]
)

/** Recursively resolves table references through views using a caller-provided callback.
  *
  * The callback classifies each table as BaseTable, View(sql), or Unknown.
  * Views are recursively resolved until only base tables remain.
  * Cycle detection is per-path (diamond dependencies are NOT cycles).
  * Callback results are cached per-invocation (each table is looked up at most once).
  *
  * @param tenant the tenant context for this resolution
  * @param lookup tenant-aware callback that classifies a table reference
  * @param config SQL parsing configuration for re-parsing view SQL
  */
class ViewResolver(tenant: TenantId, lookup: (TenantId, TableRef) => ResourceLookupResult, config: Config, maxViewDepth: Int = 50):

  /** Cache for callback results (cleared per resolve() invocation). */
  private val lookupCache: mutable.Map[TableRef, ResourceLookupResult] = mutable.Map.empty

  /** Tracks callback exceptions to distinguish from genuine Unknown results. */
  private val callbackErrors: mutable.Map[TableRef, String] = mutable.Map.empty

  /** Cache for already-resolved table references to avoid redundant work. */
  private val resolvedCache: mutable.Map[TableRef, SingleTableResolution] = mutable.Map.empty

  /** Look up a table reference using the callback, caching the result.
    * Callback exceptions are caught and recorded in callbackErrors.
    */
  private def cachedLookup(ref: TableRef): ResourceLookupResult =
    lookupCache.getOrElseUpdate(
      ref, {
        try lookup(tenant, ref)
        catch
          case e: Exception =>
            callbackErrors(ref) = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
            ResourceLookupResult.Unknown
      }
    )

  /** Resolve a single table reference, detecting cycles via the current path.
    *
    * @param ref  the table reference to resolve
    * @param path the current resolution path (for cycle detection)
    * @return the resolution result for this table
    */
  private def resolveOne(ref: TableRef, path: List[TableRef]): SingleTableResolution =
    resolvedCache.get(ref) match
      case Some(cached) => cached
      case None =>
        if path.size > maxViewDepth then
          return SingleTableResolution.MaxDepthExceeded(path)
        val result = cachedLookup(ref) match
          case ResourceLookupResult.BaseTable =>
            SingleTableResolution.Base

          case ResourceLookupResult.View(sql) =>
            val extraction = SqlParser.extract(sql, config)
            val parseErrors = extraction.statements.collect {
              case StatementResult.ParseError(_, _, msg) => msg
            }
            // View SQL that parses as NonSelect (e.g. unstripped CREATE VIEW) must
            // also be treated as a parse error: it yields zero extracted tables, which
            // would silently bypass transparent-view dependency checks.
            val nonSelectErrors = extraction.statements.collect {
              case StatementResult.NonSelect(_, _, stmtType) =>
                s"View SQL parsed as non-SELECT statement ($stmtType) — view_definition may not have been properly stripped"
            }
            val allErrors = parseErrors ++ nonSelectErrors
            if allErrors.nonEmpty then
              SingleTableResolution.ParseError(sql, allErrors.mkString("; "))
            else
              val allTables = extraction.allTables
              if allTables.isEmpty then
                SingleTableResolution.ResolvedView(Set.empty, Map.empty)
              else
                val subResults = mutable.Map.empty[TableRef, SingleTableResolution]

                for underlying <- allTables do
                  if path.contains(underlying) then
                    subResults(underlying) = SingleTableResolution.Cycle(path :+ underlying)
                  else
                    val sub = resolveOne(underlying, path :+ underlying)
                    subResults(underlying) = sub

                // If any sub-result (direct or recursive) is a cycle or max depth exceeded, propagate it
                val firstBlocker = subResults.values.collectFirst {
                  case c: SingleTableResolution.Cycle            => c
                  case m: SingleTableResolution.MaxDepthExceeded => m
                }
                firstBlocker match
                  case Some(blocker) => blocker
                  case None          => SingleTableResolution.ResolvedView(allTables, subResults.toMap)

          case ResourceLookupResult.Unknown =>
            if callbackErrors.contains(ref) then SingleTableResolution.Error(callbackErrors(ref))
            else SingleTableResolution.UnknownRef

        // Only cache non-cycle results; cycles are path-dependent
        result match
          case _: SingleTableResolution.Cycle            => ()
          case _: SingleTableResolution.MaxDepthExceeded => ()
          case _                                          => resolvedCache(ref) = result
        result

  /** Resolve a set of table references, returning resolution details and a transitive base table map.
    *
    * Clears all caches before resolution (each invocation is independent).
    *
    * @param tables the set of table references to resolve
    * @return resolution result with per-table resolutions and transitive base table mapping
    */
  def resolve(tables: Set[TableRef]): ViewResolutionResult =
    lookupCache.clear()
    callbackErrors.clear()
    resolvedCache.clear()

    val resolutions = tables.map(t => t -> resolveOne(t, List(t))).toMap
    val resolutionMap = resolutions.collect {
      case (ref, resolution) =>
        ref -> collectBaseTables(resolution)
    }.filter(_._2.nonEmpty)

    ViewResolutionResult(resolutions, resolutionMap)

  /** Collect all transitive base tables from a resolution tree. */
  private def collectBaseTables(resolution: SingleTableResolution): Set[TableRef] =
    resolution match
      case SingleTableResolution.Base => Set.empty
      case SingleTableResolution.ResolvedView(_, resolved) =>
        resolved.flatMap { case (ref, sub) =>
          sub match
            case SingleTableResolution.Base => Set(ref)
            case _                          => collectBaseTables(sub)
        }.toSet
      case _ => Set.empty

object ViewResolver:
  /** Backward-compatible factory for v1.0 API (tests and DatabaseAccessControl).
    *
    * Uses a dummy tenant and wraps the old-style lookup callback.
    *
    * @param lookup callback that classifies a table reference
    * @param config SQL parsing configuration for re-parsing view SQL
    */
  def apply(lookup: TableRef => ResourceLookupResult, config: Config, maxViewDepth: Int = 50): ViewResolver =
    val tenantAwareLookup: (TenantId, TableRef) => ResourceLookupResult =
      (_, ref) => lookup(ref)
    val dummyTenant = TenantId.parse("_v1_compat_") match
      case Right(t) => t
      case Left(_)  => throw new IllegalStateException("Dummy tenant ID failed to parse")
    new ViewResolver(dummyTenant, tenantAwareLookup, config, maxViewDepth)
