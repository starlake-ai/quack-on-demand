package ai.starlake.quack.edge.cls

import cats.effect.IO

/** Returns the ordered column list for one (catalog, schema, table) so the rewriter can expand
  * `SELECT *`. Calls are routinely repeated for the same key (a busy dashboard issues `SELECT *
  * FROM customer` over and over); implementations should cache. Failures return `Nil` so the
  * rewriter falls back to passthrough rather than aborting the user's query. `tenant` is the asking
  * principal's: a catalog name resolves among that tenant's databases only.
  */
trait ColumnCatalog:
  def columnsOf(
      tenant: String,
      catalog: String,
      schemaName: String,
      tableName: String
  ): IO[List[String]]

  /** Whether the name has meant one and the same table, with the same column names, in every
    * snapshot the catalog holds (`DuckLakeCatalogReader.nameHistoryStable`): what a time-travel
    * read under a column or row policy needs, since policies match today's names. False when it
    * cannot tell (fail closed).
    */
  def historyStable(
      tenant: String,
      catalog: String,
      schemaName: String,
      tableName: String
  ): IO[Boolean] =
    IO.pure(false)

object ColumnCatalog:

  /** Test-only static catalog. Construct with the (catalog, schema, table) -> column-list map you
    * want. Not cached; tests run synchronously.
    */
  final class MapCatalog(
      rows: Map[(String, String, String), List[String]],
      stable: Set[(String, String, String)] = Set.empty
  ) extends ColumnCatalog:
    def columnsOf(tenant: String, c: String, s: String, t: String): IO[List[String]] =
      IO.pure(rows.getOrElse((c, s, t), Nil))
    override def historyStable(tenant: String, c: String, s: String, t: String): IO[Boolean] =
      IO.pure(stable.contains((c, s, t)))
