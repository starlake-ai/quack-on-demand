package ai.starlake.quack.edge.cls

import cats.effect.IO

/** Returns the ordered column list for one (catalog, schema, table) so the rewriter can expand
  * `SELECT *`. Calls are routinely repeated for the same key (a busy dashboard issues `SELECT *
  * FROM customer` over and over); implementations should cache. Failures return `Nil` so the
  * rewriter falls back to passthrough rather than aborting the user's query.
  */
trait ColumnCatalog:
  def columnsOf(catalog: String, schemaName: String, tableName: String): IO[List[String]]

object ColumnCatalog:

  /** Test-only static catalog. Construct with the (catalog, schema, table) -> column-list map
    * you want. Not cached; tests run synchronously. */
  final class MapCatalog(rows: Map[(String, String, String), List[String]]) extends ColumnCatalog:
    def columnsOf(c: String, s: String, t: String): IO[List[String]] =
      IO.pure(rows.getOrElse((c, s, t), Nil))