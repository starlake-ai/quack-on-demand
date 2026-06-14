package ai.starlake.quack.ondemand.state

import java.time.Instant

/** A row in `qodstate_role_permission`. Attaches one table permission (`verb` on
  * `catalog.schema.table`) to a role. `*` in any of catalog / schema / table is the literal
  * wildcard. `verb` must be one of [[RolePermission.ValidVerbs]].
  *
  * The verb vocabulary is intentionally coarser than SQL keywords because the enforcement layer
  * (`PostgresAclValidator`) only distinguishes Read / Write / Ddl per parsed statement. Storing
  * granular SELECT / INSERT / UPDATE / DELETE would be a lie: a grant of `INSERT` would silently
  * also admit DELETE on the same table. The four values below line up exactly with what the
  * validator actually checks.
  */
final case class RolePermission(
    id: String,
    roleId: String,
    catalogName: String,
    schemaName: String,
    tableName: String,
    verb: String,
    grantedAt: Option[Instant] = None
)

object RolePermission:
  /** Canonical grant verbs. Match `PostgresAclValidator.verbCovers` 1:1.
    *
    *   - `RO` covers any SELECT-class access.
    *   - `RW` covers SELECT + any DML (INSERT/UPDATE/DELETE/MERGE/TRUNCATE) target.
    *   - `DDL` covers CREATE/DROP/ALTER target.
    *   - `ALL` covers everything (RO + RW + DDL).
    */
  val ValidVerbs: Set[String] = Set("RO", "RW", "DDL", "ALL")
  val Wildcard: String        = "*"
