package ai.starlake.quack.edge.sql

import ai.starlake.quack.model.PoolKey
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
    effectiveSet: Option[EffectiveSet] = None,
    // The pool the statement is routed against. `None` only for validators exercised
    // outside the router (unit tests constructing a bare context); the router always
    // fills this in.
    poolKey: Option[PoolKey] = None,
    // Audit origin of the statement: "flightsql" for the Arrow edge, "quack" for the
    // native Quack front door. Mirrors `FlightSqlRouter.executeWith`'s `source` param.
    edge: String = "",
    // Collapsed statement shape the router's classifier derived: "READ" | "WRITE" | "DDL".
    // ADVISORY ONLY: the classifier looks at the first statement, so a multi-statement batch
    // may carry more than this says. The parsed table-access set (the `accesses` a validator
    // gates, each with its own verb) is authoritative; never authorize on this field alone.
    statementClass: String = "",
    // True only for a statement that arrived through the native Quack front door, the one edge
    // that relays SEND_DATA on the client's own node connection. A DuckDB 2.0 client's INSERT
    // reads its rows from scan_data_from_quack_client, which waits for that data: admitting it
    // anywhere else would park the statement on the node forever. Set by the front door's call
    // into `FlightSqlRouter.executeWith` and nowhere else; false keeps the scan refused as an
    // unsupported construct (AccessGate, ProtectedWriteGuard).
    clientDataScans: Boolean = false,
    // The statement the caller wrote, when `statement` is an edge rewrite of it (column or row
    // policies, metadata filter) the router re-validates. The quoted identifiers the rewrite
    // introduced (catalog column names of a star expansion, policy SQL) are no names the caller
    // chose, so they are not screened as misread table names (DottedIdentifierScreen); every one
    // the caller wrote still is. None for a statement as the caller wrote it.
    rewriteOf: Option[String] = None,
    // The file locations the statement's principal may name (its tenant's authorized locations,
    // Task 12): a file reader call or a lone COPY naming only those passes the file screens.
    // Closed, the default, admits nothing, which keeps every file access refused.
    fileAdmission: FileAdmission = FileAdmission.Closed
)

sealed trait ValidationResult
case object Allowed extends ValidationResult
case class Denied(
    reason: String,
    unauthorized: Set[ai.starlake.acl.parser.TableAccess] = Set.empty,
    // Extra audit details for the denial row (e.g. authz_source, opa_decision_id).
    meta: Map[String, String] = Map.empty
) extends ValidationResult

/** The deciding authority could not be reached (OPA timeout / refused / 5xx). Fail closed, but
  * surface as retryable UNAVAILABLE rather than a permission error.
  */
final case class ValidatorUnavailable(reason: String) extends ValidationResult

trait StatementValidator:
  def validate(context: ValidationContext): ValidationResult

/** No-op validator. Used when ACL enforcement is disabled. */
object AllowAllValidator extends StatementValidator:
  override def validate(context: ValidationContext): ValidationResult = Allowed

object StatementValidator:
  /** No-op factory -- short alias for [[AllowAllValidator]]. */
  def allowAll: StatementValidator = AllowAllValidator
