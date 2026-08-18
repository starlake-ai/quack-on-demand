package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.api.SessionTokenStore
import ai.starlake.quack.ondemand.state.{PatStore, RbacUser, UserGrant}

import java.time.Instant

/** A personal access token resolved to the principal it authenticates: the owning `qodstate_user`
  * row, the id of the `qodstate_pat` row that was presented (for audit), the authorization envelope
  * the caller gets, and whether that envelope carries management privileges.
  */
final case class PatPrincipal(user: RbacUser, patId: String, scope: SessionScope, isAdmin: Boolean)

/** Turns a raw PAT bearer token into a principal, mirroring what `AuthHandlers.mintSessionFor`
  * derives from a password login so a PAT is accepted wherever a session JWT is.
  *
  * Layering: [[PatStore.verify]] answers "is this token live" (unknown hash, revoked, expired all
  * collapse to `None`) but deliberately says nothing about the owning user, so the `enabled` gate
  * lives HERE -- disabling a user must lock their tokens out without revoking them one by one.
  *
  * Scope, from the owning row plus any sibling grant `grantsFor` reports:
  *   - `superuser` = the owning row is tenant-less AND carries role `admin`, the definition
  *     `AuthHandlers.mintSessionFor` applies at login. A PAT authenticates AS its owning user, so
  *     it must never outrank that user's own login session: a tenant-less non-admin row is demoted
  *     to a profile-only principal here exactly as login refuses it `admin_required`. (The data
  *     plane -- `PostgresAclValidator`, the RLS/CLS rewriters, the handshake pool gate -- keys its
  *     own bypass off `tenant.isEmpty` alone; that is the FlightSQL side's rule, not this one.)
  *   - `manageableTenants` = the tenants where this principal carries role `admin`, from the owning
  *     row or any sibling grant.
  *
  * Every entry point resolves from scratch (one store hit each, restamping `last_used_at`); a
  * caller needing more than one facet should call [[resolve]] once and read the principal.
  */
final class PatAuthenticator(
    pats: PatStore,
    userById: String => Option[RbacUser],
    grantsFor: RbacUser => List[UserGrant]
):

  /** The principal behind `token`, or `None` when the token is not a PAT at all, is unknown /
    * revoked / expired, or its owner no longer exists or is disabled. A value without the
    * [[PatStore.TokenPrefix]] (a session JWT, an API key) is rejected before any store call, so the
    * shared bearer path can try PATs first without paying a query per request.
    */
  def resolve(token: String): Option[PatPrincipal] =
    if !token.startsWith(PatStore.TokenPrefix) then None
    else
      pats
        .verify(token)
        .flatMap(rec => userById(rec.userId).filter(_.enabled).map(user => (rec.id, user)))
        .map { (patId, user) =>
          val scope = scopeFor(user)
          PatPrincipal(user, patId, scope, adminOf(scope))
        }

  /** Authorization envelope for `token`, for the `TenantScopeCheck` gates that only need the scope.
    */
  def scopeOf(token: String): Option[SessionScope] = resolve(token).map(_.scope)

  /** Whether `token` carries management privileges. `false` for every unresolvable token. */
  def isAdmin(token: String): Boolean = resolve(token).exists(_.isAdmin)

  /** A synthetic session equivalent to what a password login would mint for this principal, so the
    * session-consuming handlers (whoami, the profile endpoints) work off a PAT unchanged. The role
    * is the computed privilege level, not the raw `qodstate_user.role` label, matching the `admin`
    * / `user` distinction `LoginResponse.admin` carries.
    */
  def sessionOf(token: String): Option[SessionTokenStore.Session] =
    resolve(token).map { p =>
      SessionTokenStore.Session(
        profile = AuthenticatedProfile(
          username = p.user.username,
          role = if p.isAdmin then "admin" else "user",
          groups = Set.empty,
          claims = Map.empty,
          authMethod = "pat",
          tenant = p.user.tenant
        ),
        scope = p.scope,
        createdAt = Instant.now()
      )
    }

  private def scopeFor(user: RbacUser): SessionScope =
    val manageable = (UserGrant(user.tenant, user.role) :: grantsFor(user)).collect {
      case UserGrant(Some(t), r) if r.equalsIgnoreCase("admin") => t
    }.toSet
    SessionScope(
      superuser = user.tenant.isEmpty && user.role.equalsIgnoreCase("admin"),
      manageableTenants = manageable
    )

  /** `mintSessionFor`'s `isAdminPrincipal`. A separate "own row is admin" disjunct would be dead:
    * the owning row's grant is folded into `manageableTenants` when it is tenant-scoped, and into
    * `superuser` when it is not.
    */
  private def adminOf(scope: SessionScope): Boolean =
    scope.superuser || scope.manageableTenants.nonEmpty
