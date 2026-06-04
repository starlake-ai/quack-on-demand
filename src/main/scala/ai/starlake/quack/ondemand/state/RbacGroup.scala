package ai.starlake.quack.ondemand.state

/** A row in `qodstate_group`. Per-tenant container that bundles roles
  * (via [[GroupRoleEdge]]) and pool grants (via [[PoolPermission]]).
  * Users belong to groups via [[UserGroupEdge]]. */
final case class RbacGroup(
    id:          String,
    tenantId:    String,
    name:        String,
    description: Option[String] = None
)