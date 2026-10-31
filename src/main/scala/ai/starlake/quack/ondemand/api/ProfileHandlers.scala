package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.StatementHistoryStore
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.HandshakeDenial
import ai.starlake.quack.ondemand.telemetry.{TelemetryStore, UsageQuery}
import cats.effect.IO
import sttp.model.StatusCode

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Handlers behind `/api/profile/usage`, `/api/profile/statements` and `/api/profile/pools`: the
  * read-only self-service view a regular (non-admin) user gets of their own activity and of the
  * pools they may use.
  *
  * **Self-scoping is the whole security story.** The `(tenant, username)` pair comes from the
  * verified session and from nowhere else -- no route accepts a tenant or user input -- so these
  * endpoints cannot be aimed at another principal. Rows are matched on:
  *   - `username`, exactly as recorded by the FlightSQL router, and
  *   - the session tenant's ALIAS SET (`{id, displayName}`), because telemetry and the statement
  *     ring carry whichever shape the router held at execution time. This mirrors the allow-set
  *     that [[StatementHistoryHandlers]] builds for tenant admins.
  *
  * The tenant scope is keyed off the session SCOPE, not `profile.tenant`: only a superuser matches
  * on username alone across tenants. A non-superuser whose profile carries no tenant (a
  * system-realm login in OIDC mode) falls back to its `manageableTenants`, and a non-superuser with
  * neither gets an empty alias set that matches nothing: fail closed, never an unfiltered window. A
  * caller whose credential no longer resolves (a session that expired between the guard and the
  * handler) has no identity to scope by and gets `400 no_session_identity`.
  *
  * `tenantById` is a lookup seam (`PoolSupervisor.getTenantById` in production) and `now` is
  * injectable, so the whole contract is testable without Postgres.
  *
  * `sessionOf` is the composed bearer lookup (session JWT first, then PAT), not the raw
  * `SessionTokenStore`: a personal access token authenticates AS its owning user and must see the
  * same self-scoped profile a login session would.
  */
final class ProfileHandlers(
    sessionOf: String => Option[SessionTokenStore.Session],
    telemetry: TelemetryStore,
    stmtHistory: StatementHistoryStore,
    tenantById: String => Option[Tenant],
    // The pool listing: the supervisor's pools and handshake decision, and the resolver that
    // turns the bearer into the caller the routed executor would run (session or PAT).
    sup: PoolSupervisor,
    callers: RestCaller,
    now: () => Instant = () => Instant.now()
):
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private val MaxDays       = 365
  private val DefaultDays   = 30
  private val MaxStatements = 500
  private val DefaultLimit  = 50

  def usage(days: Option[Int], token: Option[String]): Out[UsageResponse] = IO.blocking {
    requireSession(token).map { s =>
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
      requireSession(token).map { s =>
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

  /** `GET /api/profile/pools`: the pools the caller may connect to.
    *
    * Which tenants: every tenant for a superuser identity (a superuser session, or a PAT whose
    * owning row has no tenant: the handshake's own superuser rule); anyone else's own (the
    * profile's tenant, else the session's manageable tenants, as the usage filter reads them).
    * Never wider: the handshake resolves a user name per tenant, and in another tenant the same
    * name can be another user.
    *
    * Which pools: a PAT's `databases` and `pools` axes narrow the candidates first; each remaining
    * pool is then decided by `PoolSupervisor.authorizeHandshakeDetailed` with the request the
    * routed executor sends for the same caller ([[ExecCaller.authzRequest]] of
    * [[RestCaller.callerOf]]): the kill switches, a registered and enabled user, then the pool
    * grant or the tenant's OPA (a superuser skips that last step). A denied pool is left out
    * without a reason. An unavailable decision (the tenant's OPA unreachable) answers 503 with the
    * handshake's message: an outage never reads as a shorter list.
    */
  def pools(token: Option[String]): Out[ProfilePoolsResponse] = IO.blocking {
    callers.resolve(token) match
      case Right(principal @ RestCaller.Principal.Session(s)) =>
        listFor(
          principal,
          if s.scope.superuser then None
          else Some(s.profile.tenant.map(Set(_)).getOrElse(s.scope.manageableTenants))
        )
      case Right(principal @ RestCaller.Principal.Pat(p)) =>
        listFor(principal, p.user.tenant.map(Set(_)))
      // Anything that is not a session or a PAT has no identity to list for.
      case _ => Left(NoSessionIdentity)
  }

  // ---------- internals ----------

  private def clamp(requested: Option[Int], default: Int, max: Int): Int =
    math.max(1, math.min(max, requested.getOrElse(default)))

  /** The pools of `tenants` (None: every tenant) that `principal` may connect to. Decided lazily
    * and in order: the first unavailable decision answers for the whole listing, so no later pool
    * is asked about (each OPA-mode decision is a round trip).
    */
  private def listFor(
      principal: RestCaller.Principal,
      tenants: Option[Set[String]]
  ): Either[(StatusCode, ErrorResponse), ProfilePoolsResponse] =
    val caller  = RestCaller.callerOf("profile-pools", principal)
    val decided = sup
      .list()
      .filter(p => tenants.forall(_.contains(p.key.tenant)))
      .filter(p =>
        caller.restriction.allowsDatabase(p.key.tenantDb) &&
          caller.restriction.allowsPool(p.key.pool)
      )
      .sortBy(_.key.toString)
      .to(LazyList)
      .map(p => p -> sup.authorizeHandshakeDetailed(caller.authzRequest(p.key)))
    decided.collectFirst { case (_, Left(HandshakeDenial.Unavailable(msg))) => msg } match
      case Some(msg) =>
        Left((StatusCode.ServiceUnavailable, ErrorResponse("authz_unavailable", msg)))
      case None =>
        Right(ProfilePoolsResponse(decided.toList.collect { case (p, Right(_)) =>
          ProfilePool(
            p.key.tenant,
            p.key.tenantDb,
            p.key.pool,
            PoolHandlers.statusOf(
              p,
              blocked = sup.poolBlockReason(p.key).isDefined
            )
          )
        }))

  /** The one refusal every profile route answers when the caller has no identity to scope by. */
  private val NoSessionIdentity: (StatusCode, ErrorResponse) =
    (
      StatusCode.BadRequest,
      ErrorResponse(
        "no_session_identity",
        "profile endpoints need a logged-in session or a personal access token"
      )
    )

  /** Resolve the caller's session, or the single error every profile route answers with. Every
    * failed lookup collapses to the same code on purpose: the client's remedy is identical (log in,
    * then retry).
    */
  private def requireSession(
      token: Option[String]
  ): Either[(StatusCode, ErrorResponse), SessionTokenStore.Session] =
    token.flatMap(sessionOf) match
      case Some(s) => Right(s)
      case None    => Left(NoSessionIdentity)

  /** Tenant alias set the session may see, or None for an unrestricted (superuser) session.
    * Fail-closed: a non-superuser with no resolvable tenant yields an empty set (matches nothing)
    * rather than dropping the tenant predicate. Keyed on the session SCOPE, never on
    * `profile.tenant` -- a system-realm login in OIDC mode mints `profile.tenant = None` with
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
