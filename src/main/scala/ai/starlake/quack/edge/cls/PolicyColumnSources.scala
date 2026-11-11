package ai.starlake.quack.edge.cls

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.PoolKey
import cats.effect.IO
import io.circe.Json

import scala.concurrent.duration.FiniteDuration

/** Where the column policy rewriter gets a table's columns from. A DuckLake catalog of the tenant
  * is read from its Postgres metadata (`duckLake`); anything else a statement names (a federated
  * source, a `duckdb-file` or `memory` database, a DuckLake view) lives only on the nodes, so it is
  * asked of a node of the pool the statement runs on, with `DESCRIBE` as the system principal
  * (`node`), which binds the name exactly as the statement's own binding will. With no pool, or a
  * pool of another tenant, a table no DuckLake catalog lists has no columns, as before, and the
  * rewriter refuses what it cannot mask.
  *
  * `duckLake` and `node` are injected so the order and its fail-closed ends are testable without a
  * supervisor or a node. A DuckLake read that fails falls through to the node; a node failure or
  * timeout is `Nil` (the caller caches it for a tick).
  */
final class PolicyColumnSources(
    duckLake: (String, String, String, String) => IO[List[String]],
    node: (PoolKey, String) => IO[Either[RouterFailure, List[List[Json]]]],
    nodeTimeout: FiniteDuration
):
  def fetch(
      tenant: String,
      pool: Option[PoolKey],
      catalog: String,
      schemaName: String,
      tableName: String
  ): IO[List[String]] =
    duckLake(tenant, catalog, schemaName, tableName).handleError(_ => Nil).flatMap {
      case cols if cols.nonEmpty => IO.pure(cols)
      case _                     =>
        val sql = PolicyColumnSources.describeSql(catalog, schemaName, tableName)
        (pool.filter(_.tenant == tenant), sql) match
          case (Some(key), Some(sql)) =>
            node(key, sql)
              .timeout(nodeTimeout)
              .attempt
              .map {
                case Right(Right(rows)) => PolicyColumnSources.columnNames(rows)
                case _                  => Nil
              }
          case _ => IO.pure(Nil)
    }

object PolicyColumnSources:

  private def quoted(part: String): String = "\"" + part.replace("\"", "\"\"") + "\""

  /** `DESCRIBE "<catalog>"."<schema>"."<table>"`, leaving out an empty catalog or schema (no
    * session default), each part a quoted identifier so no name can end it. None without a table.
    */
  def describeSql(catalog: String, schemaName: String, tableName: String): Option[String] =
    Option.when(tableName.nonEmpty) {
      val parts = List(catalog, schemaName).filter(_.nonEmpty) :+ tableName
      "DESCRIBE " + parts.map(quoted).mkString(".")
    }

  /** The first column of each `DESCRIBE` row, `column_name`, in the table's order. */
  def columnNames(rows: List[List[Json]]): List[String] =
    rows.flatMap(_.headOption.flatMap(_.asString))
