package ai.starlake.quack.ondemand.state

/** One row from `qodstate_user` viewed as a management-plane grant.
  *
  * `tenant = None` is a superuser grant (matches the partial index `qodstate_user_admin_unique`).
  * Non-empty `tenant` is a tenant-scoped grant. `role` is the free-text label in
  * `qodstate_user.role`; only `equalsIgnoreCase("admin")` carries management privileges today.
  */
final case class UserGrant(tenant: Option[String], role: String)
