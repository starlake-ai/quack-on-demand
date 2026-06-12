package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.StatementHistoryStore
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import sttp.model.StatusCode

/** Reads the router's in-memory ring buffer of recent statement executions. Newest-first, capped at
  * the configured `defaultLimit` when the client doesn't pass one.
  *
  * Tenant scope: each [[ai.starlake.quack.edge.StatementRecord]] carries `PoolKey.tenant`, which is
  * the display name today but could be the surrogate id in future router changes. A session's
  * `manageableTenants` carries tenant IDs. To stay agnostic to the on-record shape (mirroring the
  * FlightSQL handshake's "accept either form" convention), the filter builds an allow-set that
  * contains BOTH the id and the display name of every manageable tenant. Superuser, static-key, and
  * open-mode callers see the unfiltered window.
  *
  * Caveat: the filter runs AFTER the ring snapshot is taken, so an admin who happens to share the
  * buffer with a noisier tenant may see fewer than `limit` rows. This matches the same trade-off
  * already made for `PoolHandlers.listPools`.
  */
final class StatementHistoryHandlers(
    store: StatementHistoryStore,
    supervisor: PoolSupervisor,
    defaultLimit: Int = 50,
    maxLimit: Int = 500
):
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  def recent(limit: Option[Int], apiKey: Option[String])(
      scopeOf: String => Option[SessionScope]
  ): Out[StatementHistoryResponse] = IO.delay {
    val n      = limit.getOrElse(defaultLimit)
    val capped = math.max(1, math.min(maxLimit, n))
    val snap   = store.snapshot(capped)

    val filtered = apiKey.flatMap(scopeOf) match
      // Open mode / static key: unknown token => admit, full window.
      case None => snap
      // Superuser session: unrestricted.
      case Some(s) if s.superuser => snap
      // Tenant-scoped admin: build a single allow-set carrying both the id
      // and display-name for each manageable tenant. Records whose `tenant`
      // resolves to neither (e.g. a tenant deleted after recording) are
      // dropped to avoid silently leaking through the gap.
      case Some(s) =>
        val allowed: Set[String] = s.manageableTenants.flatMap { id =>
          supervisor.getTenantById(id) match
            case Some(t) => Set(t.id, t.displayName)
            case None    => Set(id)
        }
        snap.filter(r => allowed.contains(r.tenant))

    Right(StatementHistoryResponse(filtered.map(toDto)))
  }

  private def toDto(r: ai.starlake.quack.edge.StatementRecord): StatementHistoryEntry =
    StatementHistoryEntry(
      ts = r.ts.toString,
      user = r.user,
      tenant = r.tenant,
      pool = r.pool,
      nodeId = r.nodeId,
      sql = r.sql,
      durationMs = r.durationMs,
      status = r.status,
      error = r.error
    )