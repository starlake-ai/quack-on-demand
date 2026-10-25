package ai.starlake.quack.ondemand.auth

import java.util.Locale
import ai.starlake.quack.ondemand.state.UserGrant

/** Source of truth for management-plane authorization.
  *
  * `Db` uses `qodstate_user` as BOTH the identity directory (password) and the role/tenant source:
  * the row the database authenticator verified is the grant. `Oidc` splits the two: the IdP
  * verifies identity, and `qodstate_user` is consulted for role + the set of tenants the principal
  * manages. Under either value an identity an IdP proved (an SSO callback, the manager-wide
  * Keycloak / Azure password grant) is read the second way (`AuthHandlers.mintSessionFor`): an
  * IdP's role claim grants nothing.
  */
sealed abstract class ManagementIdentitySource

object ManagementIdentitySource:
  case object Db   extends ManagementIdentitySource
  case object Oidc extends ManagementIdentitySource

  def fromConfig(raw: String): ManagementIdentitySource =
    raw.trim.toLowerCase(Locale.ROOT) match
      case "db"   => Db
      case "oidc" => Oidc
      case other  =>
        throw new IllegalArgumentException(
          s"unknown auth.management.identitySource: '$other' (expected: db | oidc)"
        )

/** Resolve a verified identity (+ optional email claim) to its `UserGrant`s within the realm the
  * login was verified in ([[LoginRealm]]). Bound to `UserStore.grantsForIdentity` at startup. The
  * email is the lookup's fallback key, so the caller hands one over only when the IdP vouches for
  * it (an SSO token with `email_verified`); a password login hands none.
  */
type GrantsLookup = (LoginRealm, String, Option[String]) => List[UserGrant]
