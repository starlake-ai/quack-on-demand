package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.{FlightSqlRouter, RouterFailure}
import ai.starlake.quack.edge.authz.{AuthzDecider, DryRunOutcome, DryRunResults, NodeHandles}
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaInput, OpaTarget, OpaUser}
import ai.starlake.quack.edge.sql.StatementHead
import ai.starlake.quack.model.{PoolKey, Tenant}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RbacUser
import cats.effect.IO

/** The OPA dry run (`POST /api/tenant/opaTest`). Without SQL: the exact `connect` input a handshake
  * would send, evaluated against the tenant's OPA without touching the decision cache; the client
  * edge is reported as `dry-run` and no token claims are carried (claims exist only on a live
  * credential).
  *
  * With SQL (decision 25): the statement runs through a node of the pool as the named user, exactly
  * as live (the router's USE prelude and policy rewriters, the node's parse screen and describer,
  * the decision pipeline), under a decide context marked dry run. The decision endpoint asks the
  * tenant's OPA past its decision cache, records what it sent and got back, and refuses the
  * statement, so nothing runs; the answer is that recorded call. Its input is the node decision's
  * own: edge `node`, no statement text, claims `{}`. OPA tenants only, one statement, a running
  * pool (a dry run never wakes one).
  */
object OpaDryRun:

  val Edge = "dry-run"

  /** What a tenant admin sees for a transport failure: the live edges' message
    * ([[OpaAuthorizer.UnavailableMessage]]), for the same reason. Superusers keep the detail for
    * debugging.
    */
  val GenericTransportError: String = OpaAuthorizer.UnavailableMessage

  /** How the statement form reaches a node ([[nodeRun]] over the router, in Main). */
  trait NodeRun:

    /** Whether `key` has a routable node now: a dry run never wakes a suspended pool. */
    def running(key: PoolKey): Boolean

    /** Runs `sql` for `eff`'s user on a node of `key` under a dry-run context: the router's failure
      * (None when the node ran it) and the OPA call the decision endpoint recorded for it, if any.
      */
    def run(
        user: String,
        key: PoolKey,
        eff: EffectiveSet,
        sql: String
    ): IO[(Option[RouterFailure], Option[DryRunOutcome])]

  /** [[NodeRun]] over `router` ([[FlightSqlRouter.dryRun]]) and the decision endpoint's `results`.
    */
  def nodeRun(router: FlightSqlRouter, results: DryRunResults): NodeRun = new NodeRun:

    def running(key: PoolKey): Boolean =
      router.supervisor.get(key).exists(st => !st.suspended && !st.disabled) &&
        router.supervisor
          .snapshot(key)
          .exists(snap => snap.nodes.exists(n => snap.loadOf(n.nodeId).routable))

    def run(
        user: String,
        key: PoolKey,
        eff: EffectiveSet,
        sql: String
    ): IO[(Option[RouterFailure], Option[DryRunOutcome])] =
      router.dryRun(user, key, sql, eff).map { (failure, nonces) =>
        // Every handle's outcome is taken, so none waits out its retention; the statement's is the
        // last OPA answered (an earlier one belongs to an attempt the router retried elsewhere).
        (failure, nonces.flatMap(results.take).lastOption)
      }

  private final case class Resolved(
      authz: OpaAuthorizer,
      tenant: Tenant,
      key: PoolKey,
      user: RbacUser,
      eff: EffectiveSet
  )

  private val NothingToAuthorize: (String, String) =
    "nothing_to_authorize" -> "the statement touches no table or location OPA would decide on"

  /** A left is `(errorCode, message)`; [[TenantHandlers.opaTest]] maps each code to its status.
    * `detailedErrors` is true only for a superuser caller: see [[GenericTransportError]].
    */
  def run(
      sup: PoolSupervisor,
      opa: Option[OpaAuthorizer],
      nodes: Option[NodeRun],
      req: OpaTestRequest,
      detailedErrors: Boolean
  ): IO[Either[(String, String), OpaTestResponse]] =
    IO.blocking(resolve(sup, opa, req)).flatMap {
      case Left(error) => IO.pure(Left(error))
      case Right(r)    =>
        req.sql.map(_.trim).filter(_.nonEmpty) match
          case None =>
            IO.blocking[Either[(String, String), OpaTestResponse]](
              Right(connect(sup, r, detailedErrors))
            )
          case Some(sql) => statement(r, nodes, sql, detailedErrors)
    }

  private def resolve(
      sup: PoolSupervisor,
      opa: Option[OpaAuthorizer],
      req: OpaTestRequest
  ): Either[(String, String), Resolved] =
    for
      authz  <- opa.toRight("opa_not_wired" -> "OPA is not wired on this manager")
      tenant <- sup
        .getTenant(req.tenant)
        .toRight("not_found" -> s"tenant '${req.tenant}' not found")
      key <- sup
        .findPoolKeyByTenantAndPoolName(tenant.id, req.pool)
        .toRight("not_found" -> s"pool '${req.pool}' not found in tenant '${tenant.id}'")
      user <- sup
        .findUserForLogin(tenant.id, req.user)
        // A superuser row is never an opa tenant's principal (OPA is never asked about it), and
        // answering it differently from an unknown user would make this a superuser-name oracle.
        .filter(_.tenant.nonEmpty)
        .toRight("not_found" -> s"user '${req.user}' not found in tenant '${tenant.id}'")
    yield Resolved(
      authz,
      tenant,
      key,
      user,
      sup.effectiveSetForUser(user.id).getOrElse(EffectiveSet(user, Nil, Nil, Nil, Nil))
    )

  /** The handshake form: the `connect` input, sent through [[OpaAuthorizer.dryRun]]. */
  private def connect(sup: PoolSupervisor, r: Resolved, detailedErrors: Boolean): OpaTestResponse =
    val target = OpaTarget(r.key.tenant, r.key.tenantDb, r.key.pool, sup.parentPoolNamesOf(r.key))
    val user   =
      OpaUser(r.user.username, r.eff.roles.map(_.name), r.eff.groups.map(_.name), Map.empty)
    val input = OpaInput.connect(target, user, Edge)
    response(
      DryRunOutcome.of("connect", input, r.authz.dryRun(r.tenant, input, "connect", Set.empty)),
      detailedErrors
    )

  /** The statement form (decision 25). */
  private def statement(
      r: Resolved,
      nodes: Option[NodeRun],
      sql: String,
      detailedErrors: Boolean
  ): IO[Either[(String, String), OpaTestResponse]] =
    if !r.authz.isOpa(r.tenant) then
      IO.pure(
        Left(
          "opa_mode_required" ->
            s"tenant '${r.tenant.id}' decides with QoD grants: only an OPA tenant's statements reach OPA"
        )
      )
    else if StatementHead.split(sql).length != 1 then
      IO.pure(
        Left("single_statement_required" -> "a statement dry run takes exactly one statement")
      )
    else
      nodes match
        case None =>
          IO.pure(Left("dry_run_not_wired" -> "statement dry runs are not wired on this manager"))
        case Some(n) if !n.running(r.key) => IO.pure(Left(notRunning(r.key)))
        case Some(n)                      =>
          n.run(r.user.username, r.key, r.eff, sql).map {
            case (_, Some(outcome)) => Right(response(outcome, detailedErrors))
            case (Some(f), None)    => Left(unanswered(r.key, f))
            // The node ran it: only a USE of the session's own path is let through.
            case (None, None) => Left(NothingToAuthorize)
          }

  private def notRunning(key: PoolKey): (String, String) =
    "pool_not_running" ->
      s"pool '${key.pool}' has no running node; start or resume it first (a dry run never wakes a pool)"

  /** A statement OPA was not asked about: why, from the router's failure. */
  private def unanswered(key: PoolKey, f: RouterFailure): (String, String) =
    val text = NodeHandles.refusalText(f.reason)
    if f.reason == FlightSqlRouter.PoolNotWoken then notRunning(key)
    // The rules allowed it and OPA had nothing to judge: the decision endpoint's dry-run refusal.
    else if text.endsWith(s"QoD: ${AuthzDecider.DryRunReason}") then NothingToAuthorize
    else
      f match
        case _: RouterFailure.Unavailable => "authz_unavailable"  -> text
        case _                            => "refused_before_opa" -> text

  private def response(o: DryRunOutcome, detailedErrors: Boolean): OpaTestResponse =
    val reason =
      if o.decision == "error" && !detailedErrors then Some(GenericTransportError) else o.reason
    OpaTestResponse(o.rule, o.input, o.decision, reason, o.decisionId)
