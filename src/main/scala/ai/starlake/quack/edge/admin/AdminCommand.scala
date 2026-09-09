package ai.starlake.quack.edge.admin

/** Fully-qualified table tuple as stored on qodstate rows. Missing leading segments in the source
  * SQL are padded with "*" (1 segment = table only, 2 = schema.table).
  */
final case class TableRef(catalog: String, schema: String, table: String):
  override def toString: String = s"$catalog.$schema.$table"

enum Principal:
  case User(name: String)
  case Group(name: String)

enum PolicyFilter:
  case All
  case OnTable(ref: TableRef)
  case ForRole(role: String)

/** `ON POOL bi` (qualifier None) or `ON POOL tpch.bi` (qualifier Some("tpch")). */
final case class PoolTarget(qualifier: Option[String], pool: String)

/** One parsed admin statement. Producing this ADT is the entire job of AdminSqlParser; semantics
  * (name resolution, authz, storage) live in AdminStatementExecutor.
  */
enum AdminCommand:
  case CreateRole(name: String)
  case DropRole(name: String, ifExists: Boolean)
  case GrantRoleTo(role: String, to: Principal)
  case RevokeRoleFrom(role: String, from: Principal)
  case AlterGroupAddUser(group: String, user: String)
  case AlterGroupDropUser(group: String, user: String)
  case GrantTable(verb: String, ref: TableRef, role: String)          // verb: RO | RW | DDL | ALL
  case RevokeTable(verb: Option[String], ref: TableRef, role: String) // None = any verb
  case GrantPool(pool: PoolTarget, to: Principal)
  case RevokePool(pool: PoolTarget, from: Principal)
  case CreateRowPolicy(ref: TableRef, role: String, predicateSql: String, orReplace: Boolean)
  case DropRowPolicy(ref: TableRef, role: String, ifExists: Boolean)
  case CreateColumnPolicy(
      ref: TableRef,
      column: String,
      role: String,
      action: String, // "deny" | "mask"
      transformSql: Option[String],
      orReplace: Boolean
  )
  case DropColumnPolicy(ref: TableRef, column: String, role: String, ifExists: Boolean)
  // Always a TENANT user in the session tenant - the dialect cannot mint superusers
  // (tenant-NULL rows are unreachable by construction), consistent with the
  // no-privilege-escalation rule: only superusers mint superusers, via REST.
  case CreateUser(name: String, password: String, admin: Boolean)
  case AlterUserPassword(name: String, password: String)
  case AlterUserRequirePasswordChange(name: String)
  case AlterUserEnabled(name: String, enabled: Boolean)
  case DropUser(name: String, ifExists: Boolean)
  case ShowRoles
  case ShowGrants(role: String)
  case ShowGrantsForUser(user: String)
  case ShowRowPolicies(filter: PolicyFilter)
  case ShowColumnPolicies(filter: PolicyFilter)
  case ShowPoolGrants(principal: Option[Principal])
  case ShowUsers
