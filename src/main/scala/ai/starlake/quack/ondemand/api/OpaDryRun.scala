package ai.starlake.quack.ondemand.api

import ai.starlake.acl.model.Config
import ai.starlake.acl.parser.TableAccess
import ai.starlake.quack.edge.opa.{Decision, OpaAuthorizer, OpaInput, OpaTarget, OpaUser}
import ai.starlake.quack.edge.sql.{AccessGate, GateOutcome}
import ai.starlake.quack.model.{StatementKind, TenantDb}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.route.StatementClassifier
import io.circe.Json

/** Builds the exact OPA input a real handshake (no SQL) or statement would send and evaluates it
  * against the tenant's OPA, without executing anything and without touching the decision cache.
  *
  * Differences from the live edge, all deliberate: the client edge is reported as `dry-run`, no
  * token claims are carried (claims exist only on a live credential), the statement class comes
  * from the default keyword classifier (not the operator-tuned one), and unqualified table names
  * resolve against the pool's catalog alias and schema (falling back to the tenant-db name and
  * `main`) with no session `USE` state.
  */
object OpaDryRun:

  val Edge = "dry-run"

  /** What a tenant admin sees for a transport failure: the live edges' message
    * ([[OpaAuthorizer.UnavailableMessage]]), for the same reason. Superusers keep the detail for
    * debugging.
    */
  val GenericTransportError: String = OpaAuthorizer.UnavailableMessage

  /** A left is `(errorCode, message)`; `not_found` maps to 404, anything else to 400.
    * `detailedErrors` is true only for a superuser caller: see [[GenericTransportError]].
    */
  def run(
      sup: PoolSupervisor,
      opa: Option[OpaAuthorizer],
      req: OpaTestRequest,
      detailedErrors: Boolean
  ): Either[(String, String), OpaTestResponse] =
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
      eff = sup
        .effectiveSetForUser(user.id)
        .getOrElse(EffectiveSet(user, Nil, Nil, Nil, Nil))
      target = OpaTarget(key.tenant, key.tenantDb, key.pool, sup.parentPoolNamesOf(key))
      u      = OpaUser(user.username, eff.roles.map(_.name), eff.groups.map(_.name), Map.empty)
      built <- req.sql.map(_.trim).filter(_.nonEmpty) match
        case None => Right(("connect", OpaInput.connect(target, u, Edge), Set.empty[TableAccess]))
        case Some(sql) =>
          val meta    = sup.get(key).map(_.metastore).getOrElse(Map.empty)
          val catalog = TenantDb.catalogAlias(meta, key.tenantDb)
          val schema  = meta.get("schemaName").filter(_.nonEmpty).getOrElse("main")
          val cfg     = Config.forDuckDB(Some(catalog), Some(schema), Set(catalog))
          AccessGate.evaluate(
            sql,
            cfg,
            catalog,
            filteredMetadata = false,
            clientDataScans = false
          ) match
            case GateOutcome.Refuse(msg, _, _)  => Left("refused_before_opa" -> msg)
            case GateOutcome.NothingGated(_, _) =>
              Left("nothing_to_authorize" -> "the statement touches no table OPA would decide on")
            case GateOutcome.Gated(gated, _) =>
              val cls = StatementClassifier.classify(sql) match
                case StatementKind.Ddl => "DDL"
                case StatementKind.Dml => "WRITE"
                case _                 => "READ"
              val text = Option.when(tenant.acl.sendStatementText)(sql)
              Right(("statement", OpaInput.statement(target, u, Edge, cls, gated, text), gated))
      (rule, input, accesses) = built
    yield toResponse(rule, input, authz.dryRun(tenant, input, rule, accesses), detailedErrors)

  private def toResponse(
      rule: String,
      input: Json,
      d: Decision,
      detailedErrors: Boolean
  ): OpaTestResponse = d match
    case Decision.Allow(id)           => OpaTestResponse(rule, input, "allow", None, id)
    case Decision.Deny(reason, _, id) => OpaTestResponse(rule, input, "deny", Some(reason), id)
    case Decision.Error(cause)        =>
      val shown = if detailedErrors then cause else GenericTransportError
      OpaTestResponse(rule, input, "error", Some(shown), None)
