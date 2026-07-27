package ai.starlake.quack.route

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.{SqlParser, StatementResult, Verb}

/** Canonical (lowercased `db.schema.table`) table refs of one statement, split by access mode. Used
  * as placement keys by the cache-aware router and as observation keys by LocalityTracker.
  * Best-effort by design: parse errors and control-flow statements yield `empty`, which callers
  * treat as "no placement signal", falling back to least-loaded. Never a routing failure.
  */
final case class RoutingRefs(reads: Set[String], writes: Set[String]):
  def all: Set[String] = reads ++ writes

object RoutingRefs:
  val empty: RoutingRefs = RoutingRefs(Set.empty, Set.empty)

  def extract(sql: String, config: Config): RoutingRefs =
    val result          = SqlParser.extract(sql, config)
    val (reads, writes) = result.statements.foldLeft((Set.empty[String], Set.empty[String])) {
      case ((r, w), StatementResult.Extracted(_, _, accesses, _, _)) =>
        val rs = accesses.collect { case a if a.verb == Verb.Read => a.table.canonical }
        val ws = accesses.collect {
          case a if a.verb == Verb.Write || a.verb == Verb.Ddl => a.table.canonical
        }
        (r ++ rs, w ++ ws)
      case (acc, _) => acc // ParseError / ControlFlow contribute nothing
    }
    RoutingRefs(reads, writes)

/** Bounded LRU memo over [[RoutingRefs.extract]]. BI tools repeat statement text heavily, so the
  * common case is a map hit rather than a JSQLParser pass. Keyed by (config fingerprint, sql): the
  * same text under a different default db/schema or attached-catalog set must not collide.
  */
final class RoutingRefsCache(maxEntries: Int = 1024):
  private final case class Key(db: Option[String], schema: Option[String], cats: Int, sql: String)

  private val lru = new java.util.LinkedHashMap[Key, RoutingRefs](16, 0.75f, true):
    override def removeEldestEntry(e: java.util.Map.Entry[Key, RoutingRefs]): Boolean =
      size() > maxEntries

  def extract(sql: String, config: Config): RoutingRefs =
    val key = Key(
      config.normalizedDefaultDatabase,
      config.normalizedDefaultSchema,
      config.normalizedAttachedCatalogs.hashCode(),
      sql
    )
    lru.synchronized {
      val cached = lru.get(key)
      if cached != null then cached
      else
        val computed = RoutingRefs.extract(sql, config)
        lru.put(key, computed)
        computed
    }
