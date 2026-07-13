package ai.starlake.quack.edge.sql

import ai.starlake.quack.ondemand.rbac.EffectiveSet

case class ValidationContext(
    username: String,
    database: String,
    statement: String,
    peer: String,
    // Per-pool defaults for SQL parser qualification -- when an
    // unqualified table is referenced in the statement, the parser
    // fills these in to produce a fully-qualified TableRef. Falls back
    // to the validator's construction-time defaults when None.
    defaultDatabase: Option[String] = None,
    defaultSchema: Option[String] = None,
    // Catalog names attached on the pool's node sessions (tenant-db ATTACH alias,
    // enabled federation aliases, DuckDB built-ins). Drives the parser's
    // ambiguous-two-part-name denial. Empty set = feature off (safe default for
    // tests and non-DuckDB dialects).
    attachedCatalogs: Set[String] = Set.empty,
    // The closure of (roles, groups, permissions, pool grants)
    // computed once at handshake. PostgresAclValidator reads
    // `effectiveSet.permissions` instead of querying
    // qodstate_role_permission per statement. `None` means no
    // handshake state pinned -- the validator denies any tenant-scoped
    // statement in that case so a misconfigured wiring can't
    // accidentally grant unfiltered access.
    effectiveSet: Option[EffectiveSet] = None
)

sealed trait ValidationResult
case object Allowed extends ValidationResult
case class Denied(
    reason: String,
    unauthorized: Set[ai.starlake.acl.parser.TableAccess] = Set.empty
) extends ValidationResult

trait StatementValidator:
  def validate(context: ValidationContext): ValidationResult

/** No-op validator. Used when ACL enforcement is disabled. */
object AllowAllValidator extends StatementValidator:
  override def validate(context: ValidationContext): ValidationResult = Allowed

object StatementValidator:
  /** No-op factory -- short alias for [[AllowAllValidator]]. */
  def allowAll: StatementValidator = AllowAllValidator
