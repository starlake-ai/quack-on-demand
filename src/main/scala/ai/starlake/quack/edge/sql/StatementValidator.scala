package ai.starlake.quack.edge.sql

import ai.starlake.gizmo.proxy.config.{AclConfig, SessionConfig, ValidationConfig}

import com.typesafe.scalalogging.LazyLogging

import com.auth0.jwt.interfaces.Claim

case class ValidationContext(
    username: String,
    database: String,
    statement: String,
    peer: String,
    claims: Map[String, Claim],
    // Per-pool defaults for SQL parser qualification — when an unqualified
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

class DefaultStatementValidator(config: ValidationConfig)
    extends StatementValidator,
      LazyLogging:

  override def validate(context: ValidationContext): ValidationResult =
    if !config.enabled then
      logger.debug("Validation disabled, allowing statement")
      Allowed
    else if config.rules.bypassUsersList.contains(context.username) then
      logger.info(s"User ${context.username} bypasses validation")
      Allowed
    else
      // Log the validation attempt
      logger.info(
        s">> Validating statement for user=${context.username}, " +
          s"database=${context.database}, peer=${context.peer}"
      )
      logger.info(s">> Statement: ${context.statement}")

      // Apply validation rules
      val result = applyRules(context)

      result match
        case Allowed =>
          logger.info(s"Statement ALLOWED for user=${context.username}")
        case Denied(reason) =>
          logger.warn(
            s"Statement DENIED for user=${context.username}: $reason"
          )

      result

  private def applyRules(context: ValidationContext): ValidationResult =
    // This is just an example
    // We need to query Starlake statement validator here.
    val statement = context.statement.trim.toUpperCase
    logger.debug(s"Validating statement: $statement")
    if statement.startsWith("DROP DATABASE") || statement.startsWith(
        "DROP TABLE"
      )
    then return Denied("DROP operations are not allowed")

    if config.rules.allowByDefault then Allowed
    else if statement.startsWith("SELECT") ||
      statement.startsWith("INSERT") ||
      (statement.startsWith("UPDATE") && statement.contains("WHERE"))
    then Allowed
    else Denied("Statement type not explicitly allowed")

/** No-op validator. Useful as a "no ACL configured" default and for tests
  * that don't care about access control. */
object AllowAllValidator extends StatementValidator:
  override def validate(context: ValidationContext): ValidationResult = Allowed

object StatementValidator:
  /** No-op factory — short alias for [[AllowAllValidator]]. */
  def allowAll: StatementValidator = AllowAllValidator

  def apply(config: ValidationConfig): StatementValidator =
    new DefaultStatementValidator(config)

  def acl(aclConfig: AclConfig, sessionConfig: SessionConfig): StatementValidator =
    new AclStatementValidator(aclConfig, sessionConfig)

  def composite(first: StatementValidator, second: StatementValidator): StatementValidator =
    new CompositeStatementValidator(first, second)
