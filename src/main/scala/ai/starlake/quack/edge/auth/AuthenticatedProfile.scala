package ai.starlake.quack.edge.auth

/** Result of successful authentication from any provider.
  *
  * `tenant` is the resolved tenant id when the principal authenticated with a tenant-scoped
  * credential (e.g. `qodstate_user.tenant` non-NULL), and `None` for system superusers /
  * cross-tenant Bearer tokens.
  */
case class AuthenticatedProfile(
    username: String,
    role: String,
    groups: Set[String],
    claims: Map[String, String],
    authMethod: String,
    tenant: Option[String] = None
)
