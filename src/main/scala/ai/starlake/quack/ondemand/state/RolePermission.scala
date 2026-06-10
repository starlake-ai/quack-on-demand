package ai.starlake.quack.ondemand.state

import java.time.Instant

/** A row in `qodstate_role_permission`. Attaches one table permission (`verb` on
  * `catalog.schema.table`) to a role. `*` in any of catalog / schema / table is the literal
  * wildcard. `verb` must be one of [[RolePermission.ValidVerbs]].
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
  val ValidVerbs: Set[String] = Set("SELECT", "INSERT", "UPDATE", "DELETE", "ALL")
  val Wildcard: String        = "*"
