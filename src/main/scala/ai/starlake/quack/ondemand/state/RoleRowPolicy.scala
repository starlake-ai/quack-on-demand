package ai.starlake.quack.ondemand.state

/** A row in `qodstate_role_row_policy`. Attaches one row-filter predicate on
  * `catalogName.schemaName.tableName` to a role. Catalog / schema / table accept `*` for wildcards
  * (same convention as [[RolePermission]] / [[RoleColumnPolicy]]). `predicateSql` is a boolean SQL
  * expression that may embed identity substitution tokens (`${user}`, `${tenant}`, `${tenantId}`,
  * `${groups}`, `${roles}`) filled at rewrite time.
  */
final case class RoleRowPolicy(
    id: String,
    roleId: String,
    catalogName: String,
    schemaName: String,
    tableName: String,
    predicateSql: String
)

object RoleRowPolicy:
  val Wildcard: String = "*"
