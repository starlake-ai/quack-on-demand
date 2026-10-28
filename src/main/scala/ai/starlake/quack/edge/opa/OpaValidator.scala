package ai.starlake.quack.edge.opa

import ai.starlake.quack.edge.sql.*
import ai.starlake.quack.model.{PoolKey, Tenant}
import com.typesafe.scalalogging.LazyLogging

/** Statement gate for `opa` tenants. Shares [[ai.starlake.quack.edge.sql.AccessGate AccessGate]]
  * with PostgresAclValidator, so OPA sees exactly the accesses QoD would have checked. Every
  * `Refuse` denies (there is no wildcard escape: OPA cannot judge what the parser could not
  * resolve). Transport errors become
  * [[ai.starlake.quack.edge.sql.ValidatorUnavailable ValidatorUnavailable]] carrying
  * [[OpaAuthorizer.UnavailableMessage]] only; the cause is in the manager log.
  */
final class OpaValidator(
    authorizer: OpaAuthorizer,
    tenantOf: String => Option[Tenant],
    parentPoolsOf: PoolKey => List[String],
    defaultDatabase: String,
    defaultSchema: String,
    dialect: String,
    filteredMetadata: Boolean
) extends StatementValidator,
      LazyLogging:

  private val Source = Map("authz_source" -> "opa")

  override def validate(context: ValidationContext): ValidationResult =
    (context.effectiveSet, context.poolKey) match
      case (None, _) => Denied("no RBAC principal bound to session; deny", meta = Source)
      case (Some(eff), _) if eff.user.tenant.isEmpty => Allowed
      case (Some(_), None)        => Denied("no pool bound to session; deny", meta = Source)
      case (Some(eff), Some(key)) =>
        tenantOf(key.tenant) match
          case None    => Denied(s"tenant '${key.tenant}' not found; deny", meta = Source)
          case Some(t) =>
            val config = AccessGate.parserConfig(context, defaultDatabase, defaultSchema, dialect)
            val sessionCatalog = context.defaultDatabase.getOrElse(defaultDatabase)
            AccessGate.evaluate(
              context.statement,
              config,
              sessionCatalog,
              filteredMetadata,
              context.clientDataScans,
              context.rewriteOf,
              admission = context.fileAdmission
            ) match
              case GateOutcome.Refuse(msg, _, _) =>
                logger.info(s"OPA gate refused for user:${context.username}: $msg")
                Denied(msg, meta = Source)
              case GateOutcome.NothingGated(_, _) => Allowed
              case GateOutcome.Gated(gated, _)
                  if eff.verbCeiling.exists(c => gated.exists(a => !c.contains(a.verb))) =>
                // A PAT's verb ceiling: attenuation clipped `permissions`, which this arm never
                // reads, so the ceiling is enforced here, before OPA is asked anything.
                val ceiling = eff.verbCeiling.getOrElse(Set.empty)
                val outside = gated.filter(a => !ceiling.contains(a.verb))
                val names   =
                  outside.toList.map(a => s"${a.table.canonical}:${a.verb}").sorted.mkString(", ")
                Denied(
                  s"user:${context.username} denied by token verb ceiling on $names",
                  outside,
                  Source
                )
              case GateOutcome.Gated(gated, _) =>
                val target = OpaTarget(key.tenant, key.tenantDb, key.pool, parentPoolsOf(key))
                val user   = OpaUser(
                  context.username,
                  eff.roles.map(_.name),
                  eff.groups.map(_.name),
                  eff.claims
                )
                authorizer.statement(
                  t,
                  target,
                  user,
                  eff.user.id,
                  context.edge,
                  context.statementClass,
                  gated,
                  context.statement
                ) match
                  case Decision.Allow(_)                 => Allowed
                  case Decision.Deny(reason, denied, id) =>
                    val names =
                      denied.toList
                        .map(a => s"${a.table.canonical}:${a.verb}")
                        .sorted
                        .mkString(", ")
                    Denied(
                      s"user:${context.username} denied by policy on $names: $reason",
                      denied,
                      Source ++ id.map("opa_decision_id" -> _)
                    )
                  case Decision.Error(_) => ValidatorUnavailable(OpaAuthorizer.UnavailableMessage)
