package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.PoolKey
import cats.effect.IO

/** Returns the ordered column list for one (catalog, schema, table) so the rewriter can expand
  * `SELECT *`. Calls are routinely repeated for the same key (a busy dashboard issues `SELECT *
  * FROM customer` over and over); implementations should cache. Failures return `Nil`, which the
  * rewriter's unresolvedMode then judges (a masked column of a table with no list is refused, never
  * forwarded). `tenant` is the asking principal's: a catalog name resolves among that tenant's
  * databases only. `pool` is the pool the statement runs on: a catalog only the pool's nodes hold
  * (a federated source, a `duckdb-file` or `memory` database) is read there, and with no pool it
  * has no list.
  */
trait ColumnCatalog:
  def columnsOf(
      tenant: String,
      pool: Option[PoolKey],
      catalog: String,
      schemaName: String,
      tableName: String
  ): IO[List[String]]

object ColumnCatalog:

  /** Test-only static catalog. Construct with the (catalog, schema, table) -> column-list map you
    * want. Not cached; tests run synchronously.
    */
  final class MapCatalog(rows: Map[(String, String, String), List[String]]) extends ColumnCatalog:
    def columnsOf(
        tenant: String,
        pool: Option[PoolKey],
        c: String,
        s: String,
        t: String
    ): IO[List[String]] =
      IO.pure(rows.getOrElse((c, s, t), Nil))
