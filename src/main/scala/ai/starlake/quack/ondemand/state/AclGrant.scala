package ai.starlake.quack.ondemand.state

import java.time.Instant

/** A single ACL grant: `principal` is allowed `permission` on the target
  * table set. Any of `catalog/schema/table` may be `None` to act as a
  * wildcard (e.g. all tables in a schema, or all schemas in a catalog).
  *
  * `principal` is a free-form string with a `type:name` convention —
  * `user:alice`, `group:engineers`, `role:admin`. The validator at
  * enforcement time (Phase 2) will match against the authenticated
  * profile's username, groups, and role with the same convention. */
final case class AclGrant(
    id: Option[Long],
    tenantId: String,
    principal: String,
    catalogName: Option[String],
    schemaName: Option[String],
    tableName: Option[String],
    permission: String,
    grantedAt: Option[Instant]
)

object AclGrant:
  val ValidPermissions: Set[String] =
    Set("SELECT", "INSERT", "UPDATE", "DELETE", "ALL")