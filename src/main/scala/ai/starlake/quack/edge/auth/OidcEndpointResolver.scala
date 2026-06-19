package ai.starlake.quack.edge.auth

import ai.starlake.quack.ManagementOidcConfig
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.secrets.SecretRefResolver

/** Resolves authorize/token/end-session/jwks endpoints for an admin-UI SSO login via OIDC
  * Discovery. System scope uses the manager-wide `auth.management.oidc`; tenant scope reads generic
  * keys (`issuerUrl`, `clientId`, `clientSecretRef`, `scopes`) from `qodstate_tenant.authConfig`.
  * Provider-agnostic: any OIDC-compliant issuer works.
  */
class OidcEndpointResolver(
    loadTenant: String => Option[Tenant],
    secrets: SecretRefResolver,
    discovery: OidcDiscovery
) extends com.typesafe.scalalogging.LazyLogging:

  private final case class ScopeOidc(
      issuerUrl: String,
      clientId: String,
      clientSecret: String,
      scopes: String
  )

  def endpointsFor(
      scope: OidcScope,
      mgmt: ManagementOidcConfig
  ): Either[OidcSsoError, OidcEndpoints] =
    scopeConfig(scope, mgmt).flatMap { sc =>
      discovery.resolve(sc.issuerUrl).left.map(_ => OidcSsoError.DiscoveryFailed).map { doc =>
        OidcEndpoints(
          provider = issuerHost(sc.issuerUrl),
          authorizeUrl = doc.authorizationEndpoint,
          tokenUrl = doc.tokenEndpoint,
          endSessionUrl = doc.endSessionEndpoint,
          jwksUrl = doc.jwksUri,
          issuer = doc.issuer,
          clientId = sc.clientId,
          clientSecret = sc.clientSecret,
          scopes = sc.scopes
        )
      }
    }

  private def scopeConfig(
      scope: OidcScope,
      mgmt: ManagementOidcConfig
  ): Either[OidcSsoError, ScopeOidc] =
    scope match
      case OidcScope.System =>
        if mgmt.issuerUrl.trim.nonEmpty && mgmt.clientId.trim.nonEmpty then
          Right(ScopeOidc(mgmt.issuerUrl, mgmt.clientId, mgmt.clientSecret, scopesOr(mgmt.scopes)))
        else Left(OidcSsoError.ScopeNotConfigured)
      case OidcScope.Tenant(tenantId) =>
        loadTenant(tenantId) match
          case None    => Left(OidcSsoError.ScopeNotConfigured)
          case Some(t) =>
            val cfg = t.authConfig
            (need(cfg, "issuerUrl"), need(cfg, "clientId"), need(cfg, "clientSecretRef")) match
              case (Some(issuer), Some(cid), Some(ref)) =>
                secrets.resolve(ref) match
                  case Right(secret) =>
                    Right(ScopeOidc(issuer, cid, secret, scopesOr(cfg.getOrElse("scopes", ""))))
                  case Left(err) =>
                    logger.warn(
                      s"OIDC SSO: tenant '$tenantId' clientSecretRef did not resolve ($err)"
                    )
                    Left(OidcSsoError.ScopeNotConfigured)
              case _ => Left(OidcSsoError.ScopeNotConfigured)

  private def need(cfg: Map[String, String], key: String): Option[String] =
    cfg.get(key).map(_.trim).filter(_.nonEmpty)

  private def scopesOr(s: String): String =
    if s.trim.nonEmpty then s.trim else "openid email profile"

  private def issuerHost(issuerUrl: String): String =
    try java.net.URI.create(issuerUrl).getHost
    catch case _: Exception => issuerUrl
