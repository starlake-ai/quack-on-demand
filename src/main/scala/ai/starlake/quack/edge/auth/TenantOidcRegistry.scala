package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.{
  AwsAuthConfig,
  AzureAuthConfig,
  GoogleAuthConfig,
  KeycloakAuthConfig
}
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.secrets.SecretRefResolver
import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent.ConcurrentHashMap

/** Per-tenant override of the global OIDC bearer authenticators.
  *
  * For each tenant, looks at `qodstate_tenant.authConfig` and, if a per-tenant OIDC client is
  * configured for the tenant's `authProvider`, builds a dedicated [[OidcBearerAuthenticator]] that
  * validates tokens against THAT tenant's identity-provider settings instead of the manager-wide
  * ones in `quack-flightsql.auth.<provider>.*`.
  *
  * Supports `google` / `keycloak` / `azure` / `aws` (the same set as the global bearer chain).
  *
  *   - On cache miss, the tenant is loaded via `loadTenant` and dispatched by `authProvider`. If
  *     the provider is unsupported or the required per-tenant fields are absent, the registry
  *     returns `None` and the caller falls back to the global chain.
  *   - On `invalidate(tenantId)`, the cached entry is dropped so the next handshake re-reads
  *     `authConfig`. The `setTenantAuth` REST handler MUST call this when it mutates a tenant's
  *     auth.
  *
  * Per-tenant secrets are never stored as literals: `authConfig.clientSecretRef` must be a prefixed
  * reference (e.g. `env:GOOGLE_CS_TPCH`) that the [[SecretRefResolver]] turns into the plaintext
  * value at registry-build time. AWS Cognito JWT validation doesn't need a client secret, so the
  * AWS builder skips the secret-ref lookup entirely.
  */
final class TenantOidcRegistry(
    loadTenant: String => Option[Tenant],
    secrets: SecretRefResolver,
    roleClaim: String
) extends LazyLogging:

  // Sentinel: `None` means "checked, no per-tenant override"; absence from the map means
  // "never checked." Using Optional-ish via a sealed wrapper avoids a second concurrent map.
  private val cache = new ConcurrentHashMap[String, Option[OidcBearerAuthenticator]]()

  /** Look up the per-tenant authenticator for `tenantId`. The provider is determined by the
    * tenant's `authProvider` field. Returns `None` when the tenant doesn't exist, when the provider
    * is unsupported, or when the required per-tenant fields are absent / unresolvable -- the caller
    * falls back to the global chain.
    */
  def forTenant(tenantId: String): Option[OidcBearerAuthenticator] =
    cache.computeIfAbsent(tenantId, build)

  /** Invalidate the cached authenticator for `tenantId`. Call this on every mutation of the
    * tenant's auth config so the next handshake re-reads from `qodstate_tenant`.
    */
  def invalidate(tenantId: String): Unit =
    val removed = cache.remove(tenantId)
    if removed != null then
      removed.foreach(a =>
        try a.close()
        catch case _: Throwable => ()
      )
      logger.info(s"TenantOidcRegistry: invalidated cached OIDC client for tenant '$tenantId'")

  private def build(tenantId: String): Option[OidcBearerAuthenticator] =
    loadTenant(tenantId).flatMap { t =>
      t.authProvider match
        case "google"   => buildGoogle(tenantId, t)
        case "keycloak" => buildKeycloak(tenantId, t)
        case "azure"    => buildAzure(tenantId, t)
        case "aws"      => buildAws(tenantId, t)
        case _          => None
    }

  private def need(cfg: Map[String, String], key: String): Option[String] =
    cfg.get(key).map(_.trim).filter(_.nonEmpty)

  private def resolveSecret(
      tenantId: String,
      provider: String,
      ref: String
  ): Option[String] =
    secrets.resolve(ref) match
      case Right(v)  => Some(v)
      case Left(err) =>
        logger.warn(
          s"TenantOidcRegistry: tenant '$tenantId' $provider clientSecretRef did not resolve " +
            s"($err); skipping per-tenant client and falling back to global"
        )
        None

  private def buildGoogle(tenantId: String, t: Tenant): Option[OidcBearerAuthenticator] =
    for
      cid    <- need(t.authConfig, "clientId")
      ref    <- need(t.authConfig, "clientSecretRef")
      secret <- resolveSecret(tenantId, "google", ref)
    yield
      val cfg = GoogleAuthConfig(
        enabled = true,
        clientId = cid,
        clientSecret = secret,
        groupsLookup = false,
        serviceAccountKeyPath = "",
        groupsCacheTtlSeconds = 300L
      )
      logger.info(
        s"TenantOidcRegistry: built per-tenant Google authenticator for tenant '$tenantId' " +
          s"(clientId=$cid)"
      )
      OidcProviderFactory.createGoogle(cfg, roleClaim)

  private def buildKeycloak(tenantId: String, t: Tenant): Option[OidcBearerAuthenticator] =
    for
      baseUrl <- need(t.authConfig, "baseUrl")
      realm   <- need(t.authConfig, "realm")
      cid     <- need(t.authConfig, "clientId")
      ref     <- need(t.authConfig, "clientSecretRef")
      secret  <- resolveSecret(tenantId, "keycloak", ref)
    yield
      val cfg = KeycloakAuthConfig(
        enabled = true,
        baseUrl = baseUrl,
        realm = realm,
        clientId = cid,
        clientSecret = secret
      )
      logger.info(
        s"TenantOidcRegistry: built per-tenant Keycloak authenticator for tenant '$tenantId' " +
          s"(baseUrl=$baseUrl realm=$realm clientId=$cid)"
      )
      OidcProviderFactory.createKeycloak(cfg, roleClaim)

  private def buildAzure(tenantId: String, t: Tenant): Option[OidcBearerAuthenticator] =
    for
      aadTenant <- need(t.authConfig, "tenantId")
      cid       <- need(t.authConfig, "clientId")
      ref       <- need(t.authConfig, "clientSecretRef")
      secret    <- resolveSecret(tenantId, "azure", ref)
    yield
      val cfg = AzureAuthConfig(
        enabled = true,
        tenantId = aadTenant,
        clientId = cid,
        clientSecret = secret
      )
      logger.info(
        s"TenantOidcRegistry: built per-tenant Azure authenticator for tenant '$tenantId' " +
          s"(aadTenant=$aadTenant clientId=$cid)"
      )
      OidcProviderFactory.createAzure(cfg, roleClaim)

  // AWS Cognito JWT validation needs only the JWKS URL (region + userPoolId) and the app client
  // ID; no client secret is required. So no SecretRefResolver round-trip for AWS.
  private def buildAws(tenantId: String, t: Tenant): Option[OidcBearerAuthenticator] =
    for
      region <- need(t.authConfig, "region")
      pool   <- need(t.authConfig, "userPoolId")
      cid    <- need(t.authConfig, "clientId")
    yield
      val cfg = AwsAuthConfig(
        enabled = true,
        region = region,
        userPoolId = pool,
        clientId = cid
      )
      logger.info(
        s"TenantOidcRegistry: built per-tenant AWS Cognito authenticator for tenant '$tenantId' " +
          s"(region=$region userPoolId=$pool clientId=$cid)"
      )
      OidcProviderFactory.createAws(cfg, roleClaim)

  /** Visible for tests. */
  private[auth] def cacheSize: Int = cache.size
