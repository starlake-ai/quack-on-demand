package ai.starlake.quack.edge.sql

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
    // Groups + role propagated from `AuthenticatedProfile` via
    // ConnectionContext. The ACL validator expands them into
    // `group:<g>` and `role:<r>` principals so grants targeted at
    // groups / roles match alongside `user:<username>`.
    groups: Set[String] = Set.empty,
    role:   String      = ""
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
