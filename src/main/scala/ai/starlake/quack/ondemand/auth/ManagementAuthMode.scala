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

/** Resolves the admin-UI login mode for a scope. A tenant scope reads the tenant's `authProvider`
  * and `authConfig`; the system scope (no tenant) uses the manager-wide `systemMode`.
  */
class ManagementAuthModeResolver(
    loadTenant: String => Option[Tenant],
    systemMode: ManagementAuthMode
):
  // The three authConfig keys the admin-UI OidcEndpointResolver reads.
  private val RequiredOidcKeys = List("issuerUrl", "clientId", "clientSecretRef")

  def modeFor(tenant: Option[String]): Either[ModeError, ManagementAuthMode] =
    tenant match
      case None     => Right(systemMode)
      case Some(id) =>
        loadTenant(id) match
          case None    => Left(ModeError.TenantNotFound)
          case Some(t) =>
            if t.authProvider.equalsIgnoreCase("db") then Right(ManagementAuthMode.Db)
            else if RequiredOidcKeys.forall(k => t.authConfig.get(k).exists(_.trim.nonEmpty)) then
              Right(ManagementAuthMode.Oidc)
            else Left(ModeError.MisconfiguredOidc)
