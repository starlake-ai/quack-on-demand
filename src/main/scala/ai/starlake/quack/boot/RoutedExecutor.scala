package ai.starlake.quack.boot

import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{CatalogPreviewHandlers, ExecCaller}
import ai.starlake.quack.ondemand.rbac.{AuthzRequest, EffectiveSet, HandshakeDenial}
import ai.starlake.quack.route.StatementClassifier
import cats.effect.IO

/** The routed executor behind every
  * [[ai.starlake.quack.ondemand.api.CatalogPreviewHandlers.PreviewExecutor CatalogPreviewHandlers.PreviewExecutor]]
  * (REST preview, data diff, restore, undrop, branch change counts, the MCP data tools): token
  * restriction checks, EffectiveSet resolution and attenuation, then the router.
  */
object RoutedExecutor:

  /** The router leg: `(caller, poolKey, sql, effectiveSet, recordExecution)`. Main binds
    * `FlightSqlRouter.execute(..., adminDispatch = false)`; tests record what reaches it.
    */
  type Run =
    (ExecCaller, PoolKey, String, Option[EffectiveSet], Boolean) => IO[
      Either[RouterFailure, QueryResult]
    ]

  // Adapts FlightSqlRouter.execute to PreviewExecutor, mirroring the FlightSQL
  // handshake's EffectiveSet resolution: a system caller (ExecCaller.system) gets a
  // synthetic superuser EffectiveSet (NOT None, which PostgresAclValidator
  // denies fail-safe) and is never attenuated -- a system caller is definitionally
  // TokenRestriction.Unrestricted; real sessions resolve through
  // sup.authorizeHandshakeDetailed (same gate + 60s cache as the handshake, edge "mcp"), a
  // Denied short-circuiting to AccessDenied and an Unavailable (the tenant's OPA unreachable)
  // to RouterFailure.Unavailable before fsRouter.execute.
  // recordExecution = false for read-only probes (preview, data diff, restore
  // dry-run); true for undrop's CTAS and restore's CREATE OR REPLACE so the
  // snapshot carries the author stamp.
  // Attenuation runs AFTER the EffectiveSet is resolved -- for real sessions,
  // after PoolSupervisor's 60s-TTL cache read -- and BEFORE the router runs.
  // PoolSupervisor caches that closure per user keyed on
  // (userId, jwtRoles.hashCode, jwtGroups.hashCode), not per token, so two
  // differently-scoped tokens belonging to the same user share one cached
  // closure; each call narrows its own copy on the way out, and the narrowed
  // copy is never the thing fed back into that cache.
  // Caveat: the handler-level timeout cannot cancel the underlying IO.blocking
  // node call, so a timed-out preview may leave that call to finish unobserved
  // (bounded by previewTimeoutSec, pre-existing on the shared executor path;
  // durable fix is bracketing the connection inside QuackHttpClient). The
  // per-caller `stmtTimeoutMs` below is the same kind of bound: it is a
  // BOUNDED WAIT, not a cancellation of the statement in flight.
  def apply(sup: PoolSupervisor, classifier: StatementClassifier, route: Run)(
      recordExecution: Boolean
  ): CatalogPreviewHandlers.PreviewExecutor =
    (caller, poolKey, sql) =>
      // Privilege from the typed flag ONLY (ExecCaller.system at trusted internal sites), never
      // from the user name: names are not reserved, and a tenant user called "superuser" used to
      // match the old `identity == "superuser"` test and run with the synthetic superuser set.
      val isSuperuser = caller.system
      val effectiveSetIO: IO[Either[ai.starlake.quack.edge.RouterFailure, Option[
        ai.starlake.quack.ondemand.rbac.EffectiveSet
      ]]] =
        // `pools` axis, enforced here on the RESOLVED pool key rather than only at the MCP
        // call sites that happen to take a `pool` argument: this is the single choke point
        // every current and future PreviewExecutor caller routes through (run_sql,
        // describe_table's sample fetch, preview, data diff, restore dry-run, undrop), so a
        // token scoped to `pools` cannot reach an out-of-scope pool merely by omitting the
        // argument and letting the caller pick one for it. `allowsPool` is `pools.forall(_
        // .contains(pool))`, so `TokenRestriction.Unrestricted` (every superuser call site,
        // and any token that never set the axis) always passes here.
        // A branch pool (Epic 1) is never named on the axis: it inherits the axis from
        // the pools of its parent tenant-db (any allowed parent pool admits the branch).
        val poolAllowed =
          if caller.restriction.allowsPool(poolKey.pool) then true
          else if ai.starlake.quack.ondemand.branch.BranchNames.isBranchPool(poolKey.pool) then
            sup
              .findTenantDb(poolKey.tenant, poolKey.tenantDb)
              .flatMap(_.branchOf)
              .exists { parentId =>
                sup
                  .list()
                  .map(_.key)
                  .filter(k =>
                    k.tenant == poolKey.tenant && sup
                      .findTenantDb(k.tenant, k.tenantDb)
                      .exists(_.id == parentId)
                  )
                  .exists(k => caller.restriction.allowsPool(k.pool))
              }
          else false
        // branchOnly (Epic 1): writes are admitted only on a branch pool. Classified here on
        // the raw statement, the same classifier the router applies downstream.
        val writeOnMain =
          caller.restriction.branchOnly &&
            !ai.starlake.quack.ondemand.branch.BranchNames.isBranchPool(poolKey.pool) && {
              val k = classifier.classify(sql)
              k == ai.starlake.quack.model.StatementKind.Dml ||
              k == ai.starlake.quack.model.StatementKind.Ddl
            }
        if !poolAllowed then
          IO.pure(
            Left(
              ai.starlake.quack.edge.RouterFailure
                .AccessDenied(s"pool '${poolKey.pool}' is not permitted for this token")
            )
          )
        else if writeOnMain then
          IO.pure(
            Left(
              ai.starlake.quack.edge.RouterFailure.AccessDenied(
                "access denied: write_requires_branch: this token may only write on a branch " +
                  "(create_branch, then pass branch=<name>)"
              )
            )
          )
        else if isSuperuser then
          val superuser = ai.starlake.quack.ondemand.state.RbacUser(
            id = "",
            tenant = None,
            username = caller.identity,
            kind = "admin"
          )
          IO.pure(
            Right(
              Some(
                ai.starlake.quack.ondemand.rbac
                  .EffectiveSet(superuser, Nil, Nil, Nil, Nil)
              )
            )
          )
        else
          IO.delay(
            sup.authorizeHandshakeDetailed(
              AuthzRequest(
                poolKey.tenant,
                poolKey.pool,
                caller.identity,
                // The caller's realm: only a superuser caller may bind a superuser row.
                superuserAdmissible = caller.superuserAdmissible,
                edge = "mcp",
                // Attenuate before gate 4: an opa tenant's connect sees only the token's roles.
                restriction = caller.restriction
              )
            )
          ).map {
            case Left(HandshakeDenial.Unavailable(reason)) =>
              Left(
                ai.starlake.quack.edge.RouterFailure
                  .Unavailable(s"authorization service unavailable: $reason")
              )
            case Left(denial) =>
              Left(ai.starlake.quack.edge.RouterFailure.AccessDenied(denial.message))
            case Right(authorized) => Right(Some(authorized.effectiveSet))
          }
      effectiveSetIO.flatMap {
        case Left(denied) => IO.pure(Left(denied))
        case Right(eff)   =>
          // Skip attenuation for the superuser sentinel rather than relying on
          // attenuatedBy's Unrestricted no-op path: this keeps "the synthetic
          // superuser set is never narrowed" an explicit invariant here, not
          // an incidental consequence of every superuser caller currently
          // being built with ExecCaller.unrestricted.
          val narrowed =
            if isSuperuser then eff
            else
              eff.map(e =>
                ai.starlake.quack.ondemand.rbac.Attenuation.attenuatedBy(e, caller.restriction)
              )
          // Main binds `route` to FlightSqlRouter.execute with adminDispatch = false: this closure
          // is the single choke point every PreviewExecutor caller shares, and PAT attenuation
          // narrows `narrowed` but the SQL admin dialect's own authorization does not know about
          // that ceiling, so a claimed admin statement must stay on the pre-dialect routed path.
          val run = route(caller, poolKey, sql, narrowed, recordExecution)
          // BOUNDED WAIT, not a cancellation: past this many milliseconds the
          // caller of this executor gets a failure back, but the underlying
          // IO.blocking node call keeps running unobserved and may still
          // complete -- the same caveat as previewTimeoutSec above, and the
          // same best-effort gap FlightSqlRouter already has between register
          // and attachCancel. Durable cancellation (bracketing the connection
          // inside QuackHttpClient) is deliberately deferred to a later
          // sub-project; do not describe this branch as killing or aborting
          // the statement.
          caller.restriction.stmtTimeoutMs match
            case Some(ms) if ms > 0 =>
              run.timeoutTo(
                scala.concurrent.duration.FiniteDuration(
                  ms.toLong,
                  java.util.concurrent.TimeUnit.MILLISECONDS
                ),
                IO.pure(
                  Left(
                    ai.starlake.quack.edge.RouterFailure
                      .Unavailable(s"statement exceeded this token's ${ms}ms limit")
                  )
                )
              )
            case _ => run
      }
