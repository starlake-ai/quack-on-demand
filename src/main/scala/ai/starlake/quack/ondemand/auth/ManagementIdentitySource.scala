package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.ondemand.state.UserGrant

/** Source of truth for management-plane authorization.
  *
  * `Db` keeps the legacy behavior: `qodstate_user` is BOTH the identity directory (password) and the
  * role/tenant source. `Oidc` splits the two: the IdP verifies identity, and `qodstate_user` is
  * consulted for role + the set of tenants the principal manages.
  */
sealed abstract class ManagementIdentitySource

object ManagementIdentitySource:
  case object Db   extends ManagementIdentitySource
  case object Oidc extends ManagementIdentitySource

  def fromConfig(raw: String): ManagementIdentitySource =
    raw.trim.toLowerCase match
      case "db"   => Db
      case "oidc" => Oidc
      case other  =>
        throw new IllegalArgumentException(
          s"unknown auth.management.identitySource: '$other' (expected: db | oidc)"
        )

/** Resolve a verified identity (+ optional email claim) to its `UserGrant`s. Production binding is
  * a method ref onto `UserStore.grantsForIdentity`.
  */
type GrantsLookup = (String, Option[String]) => List[UserGrant]
