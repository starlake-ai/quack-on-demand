package ai.starlake.quack.ondemand.state

/** A row in `qodstate_role_column_policy`. Attaches one column-level policy (deny or mask) on
  * `catalogName.schemaName.tableName.columnName` to a role. Catalog / schema / table accept `*`
  * for wildcards (same convention as [[RolePermission]]); `columnName` is always a literal. */
final case class RoleColumnPolicy(
    id:            String,
    roleId:        String,
    catalogName:   String,
    schemaName:    String,
    tableName:     String,
    columnName:    String,
    action:        String,                 // "deny" | "mask"
    transformSql:  Option[String]
)

object RoleColumnPolicy:
  val Wildcard:     String       = "*"
  val ActionDeny:   String       = "deny"
  val ActionMask:   String       = "mask"
  val ValidActions: Set[String]  = Set(ActionDeny, ActionMask)