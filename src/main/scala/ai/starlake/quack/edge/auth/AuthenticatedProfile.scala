package ai.starlake.quack.edge.auth

/** Result of successful authentication from any provider.
  *
  * `tenant` is the resolved tenant id when the principal authenticated with a tenant-scoped
  * credential (e.g. `qodstate_user.tenant` non-NULL), and `None` for system superusers /
  * cross-tenant Bearer tokens.
  *
  * `role` describes the account and grants nothing on the data plane: the database authenticator's
  * is the row's kind (`admin` / `user`), an identity provider's is its first role claim, else
  * `user`. `tokenRoles` and `groups` are the role and group names an identity provider's token
  * carries, which the data-plane handshake resolves against the tenant's roles and groups. A
  * profile no token produced (the database authenticator's, a PAT's, a management session's)
  * carries none, so an account kind can never stand for a tenant role or group of that name.
  */
case class AuthenticatedProfile(
    username: String,
    role: String,
    groups: Set[String],
    claims: Map[String, String],
    authMethod: String,
    tenant: Option[String] = None,
    tokenRoles: Set[String] = Set.empty
)
