package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.StatementHistoryStore
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.telemetry.{TelemetryStore, UsageQuery}
import cats.effect.IO
import sttp.model.StatusCode

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Handlers behind `/api/profile/usage` and `/api/profile/statements`: the read-only self-service
  * view a regular (non-admin) user gets of their own activity.
  *
  * **Self-scoping is the whole security story.** The `(tenant, username)` pair comes from the
  * verified session and from nowhere else -- neither route accepts a tenant or user input -- so
  * these endpoints cannot be aimed at another principal. Rows are matched on:
  *   - `username`, exactly as recorded by the FlightSQL router, and
  *   - the session tenant's ALIAS SET (`{id, displayName}`), because telemetry and the statement
  *     ring carry whichever shape the router held at execution time. This mirrors the allow-set
  *     that [[StatementHistoryHandlers]] builds for tenant admins.
  *
  * The tenant scope is keyed off the session SCOPE, not `profile.tenant`: only a superuser matches
  * on username alone across tenants. A non-superuser whose profile carries no tenant (an OIDC
  * tenant-scoped login) falls back to its `manageableTenants`, and a non-superuser with neither
  * gets an empty alias set that matches nothing -- fail closed, never an unfiltered window. A
  * caller with no resolvable session -- the static API key, or open mode with no token at all --
  * has no identity to scope by and gets `400 no_session_identity`.
  *
  * `tenantById` is a lookup seam (`PoolSupervisor.getTenantById` in production) and `now` is
  * injectable, so the whole contract is testable without Postgres.
  */
final class ProfileHandlers(
    tokens: SessionTokenStore,
    telemetry: TelemetryStore,
    stmtHistory: StatementHistoryStore,
    tenantById: String => Option[Tenant],
    now: () => Instant = () => Instant.now()
):
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private val MaxDays       = 365
  private val DefaultDays   = 30
  private val MaxStatements = 500
  private val DefaultLimit  = 50

  def usage(days: Option[Int], token: Option[String]): Out[UsageResponse] = IO.blocking {
    sessionOf(token).map { s =>
      val d       = clamp(days, DefaultDays, MaxDays)
      val toTs    = now()
      val fromTs  = toTs.minus(d.toLong, ChronoUnit.DAYS)
      val aliases = visibleTenantAliases(s)
      val result  = telemetry.queryUsage(
        UsageQuery(groupBy = "user", tenants = aliases, pool = None, from = fromTs, to = toTs)
      )
      // The store-side `tenants` filter above is an optimization; this filter is
      // the contract. A store that ignores the hint still cannot widen the view.
      val mine = result.groups.filter { g =>
        g.username.contains(s.profile.username) && aliases.forall(_.contains(g.tenant))
      }
      UsageResponse(
        from = fromTs.toString,
        to = toTs.toString,
        groupBy = "user",
        dataStart = result.dataStart.map(_.toString),
        groups = mine.map(g =>
          UsageGroupEntry(
            tenant = g.tenant,
            pool = g.pool,
            username = g.username,
            statements = g.statements,
            errors = g.errors,
            denied = g.denied,
            engineMs = g.engineMs,
            days = g.days.map(d =>
              UsageDayEntry(
                day = d.day.toString,
                statements = d.statements,
                errors = d.errors,
                engineMs = d.engineMs
              )
            )
          )
        )
      )
    }
  }

  def statements(limit: Option[Int], token: Option[String]): Out[StatementHistoryResponse] =
    IO.delay {
      sessionOf(token).map { s =>
        val n       = clamp(limit, DefaultLimit, MaxStatements)
        val aliases = visibleTenantAliases(s)
        // Snapshot the FULL window and filter before capping, so a noisier
        // neighbour in the shared ring cannot consume the caller's budget.
        val mine = stmtHistory
          .snapshot(MaxStatements)
          .filter(r => r.user == s.profile.username && aliases.forall(_.contains(r.tenant)))
          .take(n)
        StatementHistoryResponse(mine.map(StatementHistoryHandlers.toDto))
      }
    }

  // ---------- internals ----------

  private def clamp(requested: Option[Int], default: Int, max: Int): Int =
    math.max(1, math.min(max, requested.getOrElse(default)))

  /** Resolve the caller's session, or the single error every profile route answers with. Every
    * non-Ok lookup arm collapses to the same code on purpose: the client's remedy is identical (log
    * in, then retry), and a caller presenting the static key has no identity at all.
    */
  private def sessionOf(
      token: Option[String]
  ): Either[(StatusCode, ErrorResponse), SessionTokenStore.Session] =
    tokens.lookupResult(token.getOrElse("")) match
      case SessionTokenStore.LookupResult.Ok(s) => Right(s)
      case _                                    =>
        Left(
          (
            StatusCode.BadRequest,
            ErrorResponse(
              "no_session_identity",
              "profile endpoints need a logged-in session; a static API key has no identity to scope by"
            )
          )
        )

  /** Tenant alias set the session may see, or None for an unrestricted (superuser) session.
    * Fail-closed: a non-superuser with no resolvable tenant yields an empty set (matches nothing)
    * rather than dropping the tenant predicate. Keyed on the session SCOPE, never on
    * `profile.tenant` -- an OIDC tenant login mints `profile.tenant = None` with
    * `superuser = false`, and must still be tenant-confined.
    */
  private def visibleTenantAliases(s: SessionTokenStore.Session): Option[Set[String]] =
    if s.scope.superuser then None
    else
      val ids = s.profile.tenant.map(Set(_)).getOrElse(s.scope.manageableTenants)
      Some(ids.flatMap(tenantAliases))

  /** Both shapes a recorded tenant may carry. An id that no longer resolves (tenant deleted after
    * the rows were written) degrades to the id alone rather than to "match anything".
    */
  private def tenantAliases(tenantId: String): Set[String] =
    tenantById(tenantId) match
      case Some(t) => Set(t.id, t.displayName)
      case None    => Set(tenantId)
