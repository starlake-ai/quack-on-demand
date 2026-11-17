package ai.starlake.quack.edge.rls

import java.util.Locale

/** What the column-policy step learned of a statement's tables for the row-policy wrap of search
  * spec 4.2 rule 3 (owner ruling 2026-11-14, review I6): `resolved` every table it resolved, `real`
  * those holding a real column named `rowid`, which shadows DuckDB's virtual one. Names are
  * canonical: `catalog.schema.table`, unquoted, each part qualified by the session defaults as both
  * rewriters qualify it, in lower case.
  *
  * A table the step did not resolve counts as holding a real `rowid` ([[holdsRealRowid]]), as does
  * one whose columns the catalog could not list, so a statement the step never read (it returned
  * early, or failed) leaves every table in doubt and the wrap refuses: fail closed.
  */
final case class RealRowidTables(resolved: Set[String], real: Set[String]):

  def holdsRealRowid(canonical: String): Boolean =
    real.contains(canonical) || !resolved.contains(canonical)

  def ++(other: RealRowidTables): RealRowidTables =
    RealRowidTables(resolved ++ other.resolved, real ++ other.real)

object RealRowidTables:

  /** Nothing resolved: every table counts as holding a real `rowid`. */
  val Unknown: RealRowidTables = RealRowidTables(Set.empty, Set.empty)

  def canonical(catalog: String, schema: String, table: String): String =
    s"$catalog.$schema.$table".toLowerCase(Locale.ROOT)
