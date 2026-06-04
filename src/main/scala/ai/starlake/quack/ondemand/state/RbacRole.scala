package ai.starlake.quack.ondemand.state

import java.time.Instant

/** A row in `qodstate_role`. Roles are per-tenant bundles of
  * [[RolePermission]] rows -- the only place table permissions live in
  * the new RBAC model. `Rbac` prefix avoids collision with
  * [[ai.starlake.quack.model.Role]] (the node role enum:
  * WriteOnly / ReadOnly / Dual). */
final case class RbacRole(
    id:          String,
    tenantId:    String,
    name:        String,
    description: Option[String]  = None,
    createdAt:   Option[Instant] = None
)