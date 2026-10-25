package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.ondemand.state.UserGrant

/** The realm a management login was verified in. It bounds the `qodstate_user` rows the session may
  * take its authority from (`GrantsLookup`, `UserStore.grantsForIdentity`), for every login whose
  * identity was proven by something other than the database authenticator's check of the one row:
  * an SSO callback, and a password login answered by the manager-wide Keycloak or Azure password
  * grant (whatever role claim its IdP issues). A `Db`-mode login the database authenticator
  * verified reads no realm: its grant is the authenticated profile's `(tenant, role)`, the row
  * whose password was checked.
  *
  *   - `System`: the login named no tenant (the bare `/ui/` SSO, a password login without a
  *     tenant). Its authorities are operator-configured (the manager-wide IdP, the system DB query,
  *     the manager-wide password backends), so grants may come from the rows of every realm,
  *     superuser rows and tenant rows alike: this is how a multi-tenant admin session is built.
  *   - `Tenant(id)`: the login was verified for tenant `id`: through that tenant's SSO callback (a
  *     tenant that signs in through OIDC takes no password login), or through the password form of
  *     a `db` tenant when the manager-wide password grant answers it. Only rows with `tenant = id`
  *     count, never a superuser row and never another tenant's row. At most one row matches
  *     (`qodstate_user_scoped_unique` is `(tenant, username)`), so such a session is never
  *     superuser and manages at most `{id}`.
  */
enum LoginRealm:
  case System
  case Tenant(id: String)

  /** True when `grant` comes from a row of this realm: every grant for `System`, only the tenant's
    * own rows for `Tenant(id)`.
    */
  def admits(grant: UserGrant): Boolean = this match
    case LoginRealm.System     => true
    case LoginRealm.Tenant(id) => grant.tenant.contains(id)
