package ai.starlake.quack.edge.sql

import ai.starlake.quack.ondemand.rbac.EffectiveSet
import com.auth0.jwt.interfaces.Claim

case class ValidationContext(
    username: String,
    database: String,
    statement: String,
    peer: String,
    claims: Map[String, Claim],
    // Per-pool defaults for SQL parser qualification - when an unqualified
    // table is referenced in the statement, the parser fills these in to
    // produce a fully-qualified TableRef. Falls back to global config when
    // a validator instance was built with its own defaults.
    defaultDatabase: Option[String] = None,
    defaultSchema:   Option[String] = None,
    // Legacy free-text groups + role from `AuthenticatedProfile` (JWT
    // claims). Retained for back-compat with the JWT-claim ACL path; new
    // code reads the RBAC graph through [[effectiveSet]] instead.
    groups: Set[String] = Set.empty,
    role:   String      = "",
    // Phase C: the closure of (roles, groups, permissions, pool grants)
    // computed once at handshake. PostgresAclValidator reads
    // `effectiveSet.permissions` instead of querying qodstate_role_permission
    // per statement. `None` means "no handshake state pinned" -- the
    // validator denies any tenant-scoped statement in that case so a
    // misconfigured wiring can't accidentally grant unfiltered access.
    effectiveSet: Option[EffectiveSet] = None
)

sealed trait ValidationResult
case object Allowed extends ValidationResult
case class Denied(reason: String) extends ValidationResult

trait StatementValidator:
  def validate(context: ValidationContext): ValidationResult

/** No-op validator. Used when ACL enforcement is disabled. */
object AllowAllValidator extends StatementValidator:
  override def validate(context: ValidationContext): ValidationResult = Allowed

object StatementValidator:
  /** No-op factory - short alias for [[AllowAllValidator]]. */
  def allowAll: StatementValidator = AllowAllValidator
