package ai.starlake.quack.ondemand.auth

import ai.starlake.quack.model.Tenant

/** Admin-UI login mode for a scope. */
enum ManagementAuthMode:
  case Db
  case Oidc

/** Why a tenant scope could not be resolved to a mode. Stable codes for the UI. */
enum ModeError(val code: String):
  case TenantNotFound    extends ModeError("tenant_not_found")
  case MisconfiguredOidc extends ModeError("tenant_oidc_misconfigured")

/** Resolves the admin-UI login mode for a scope. A tenant scope reads the tenant's `authProvider`,
  * `authConfig` and stored client secret; the system scope (no tenant) uses the manager-wide
  * `systemMode`.
  *
  * A non-`db` tenant is OIDC when it holds what
  * [[ai.starlake.quack.edge.auth.OidcEndpointResolver]] needs for the code exchange: `issuerUrl`,
  * `clientId` and a secret source, either a stored value (`Tenant.authClientSecret`) or a
  * `clientSecretRef` reference. Anything less is `MisconfiguredOidc`. A reference parked by
  * changeset 0048 (`clientSecretRefUnconfirmed`) is not a secret source: nothing reads the parked
  * key until a superuser saves the reference again.
  */
class ManagementAuthModeResolver(
    loadTenant: String => Option[Tenant],
    systemMode: ManagementAuthMode
):

  def modeFor(tenant: Option[String]): Either[ModeError, ManagementAuthMode] =
    tenant match
      case None     => Right(systemMode)
      case Some(id) =>
        loadTenant(id) match
          case None    => Left(ModeError.TenantNotFound)
          case Some(t) =>
            if t.authProvider.equalsIgnoreCase("db") then Right(ManagementAuthMode.Db)
            else if oidcReady(t) then Right(ManagementAuthMode.Oidc)
            else Left(ModeError.MisconfiguredOidc)

  private def oidcReady(t: Tenant): Boolean =
    isSet(t, TenantSignInWrite.IssuerKey) && isSet(t, "clientId") &&
      (t.authClientSecret.isDefined || isSet(t, TenantSignInWrite.SecretRefKey))

  private def isSet(t: Tenant, key: String): Boolean =
    t.authConfig.get(key).exists(_.trim.nonEmpty)
