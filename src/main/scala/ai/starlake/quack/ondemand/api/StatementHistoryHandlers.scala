package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.StatementHistoryStore
import cats.effect.IO
import sttp.model.StatusCode

/** Reads the router's in-memory ring buffer of recent statement
  * executions. Newest-first, capped at the configured `defaultLimit`
  * when the client doesn't pass one. */
final class StatementHistoryHandlers(
    store: StatementHistoryStore,
    defaultLimit: Int = 50,
    maxLimit:     Int = 500
):
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def recent(limit: Option[Int]): Out[StatementHistoryResponse] = IO.delay {
    val n = limit.getOrElse(defaultLimit)
    val capped = math.max(1, math.min(maxLimit, n))
    Right(StatementHistoryResponse(store.snapshot(capped).map(toDto)))
  }

  private def toDto(r: ai.starlake.quack.edge.StatementRecord): StatementHistoryEntry =
    StatementHistoryEntry(
      ts         = r.ts.toString,
      user       = r.user,
      tenant     = r.tenant,
      pool       = r.pool,
      nodeId     = r.nodeId,
      sql        = r.sql,
      durationMs = r.durationMs,
      status     = r.status,
      error      = r.error
    )
