package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.config.{
  AwsAuthConfig,
  AzureAuthConfig,
  GoogleAuthConfig,
  KeycloakAuthConfig
}
import ai.starlake.quack.model.Tenant
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
  *   - Every lookup loads the tenant via `loadTenant` (the supervisor's in-memory tenant mirror,
  *     which `restore()` refreshes on every replica) and compares the fingerprint of its public
  *     auth fields (the provider plus the [[TenantOidcRegistry.PublicKeys]] the builder reads) with
  *     the one the cached authenticator was built from. Equal: the cached instance is reused.
  *     Different: a new authenticator is built and the old one closed. So under HA a tenant's SSO
  *     change made on one replica applies on every other one once its mirror is refreshed (a
  *     `qod_topology` NOTIFY, or the periodic refresh when a NOTIFY was missed), with no channel of
  *     its own.
  *   - A tenant that no longer exists keeps no entry; one whose provider is unsupported or lacks a
  *     required public field caches `None`. Either way the caller falls back to the global chain
  *     and any previous authenticator is closed.
  *   - On `invalidate(tenantId)`, the cached entry is dropped and closed so the next handshake
  *     re-reads `authConfig`. The `setTenantAuth` REST handler calls this on the replica serving
  *     the write.
  *
  * Swaps are serialized per tenant (`ConcurrentHashMap.compute`, which also reads the tenant, so a
  * lookup holding an older mirror read cannot reinstall superseded settings). Only the thread whose
  * swap replaced an entry closes it, so no authenticator is closed twice, and a lookup that starts
  * after a swap returned never receives the instance that swap closed.
  *
  * Bearer validation needs public fields only: where the provider publishes its signing keys, and
  * the issuer and client id (the audience) a token must carry. Every builder therefore reads public
  * `authConfig` keys alone (`keycloak`: baseUrl + realm + clientId; `google`: clientId; `azure`:
  * tenantId + clientId; `aws`: region + userPoolId + clientId) and resolves no secret. A tenant's
  * stored client secret and its `clientSecretRef` serve the admin-console SSO code exchange only
  * ([[OidcEndpointResolver]]); the provider configs built here carry `clientSecret = ""`, a field
  * [[OidcProviderFactory]] never reads.
  */
final class TenantOidcRegistry(
    loadTenant: String => Option[Tenant],
    roleClaim: String
) extends LazyLogging:

  import TenantOidcRegistry.{Entry, Fingerprint}

  // `Entry.auth == None` means "checked, no per-tenant override"; absence from the map means
  // "never checked" or "no such tenant".
  private val cache = new ConcurrentHashMap[String, Entry]()

  /** Look up the per-tenant authenticator for `tenantId`. The provider is determined by the
    * tenant's `authProvider` field. Returns `None` when the tenant doesn't exist, when the provider
    * is unsupported, or when the provider's required per-tenant fields are absent: the caller falls
    * back to the global chain. Rebuilds (closing the previous instance) when the tenant's public
    * auth fields differ from the ones the cached authenticator was built from.
    */
  def forTenant(tenantId: String): Option[OidcBearerAuthenticator] =
    val cached = cache.get(tenantId)
    val fresh  =
      if cached == null then None
      else
        loadTenant(tenantId).map(TenantOidcRegistry.fingerprintOf).filter(_ == cached.fingerprint)
    fresh match
      case Some(_) => cached.auth
      case None    => swap(tenantId)

  /** Replace the tenant's entry with one built from the tenant as loaded NOW, unless the entry
    * already matches it. Runs inside `compute`, so swaps of one tenant are serialized and each one
    * reads the mirror at its turn; the superseded entry is closed outside it, by this thread only.
    */
  private def swap(tenantId: String): Option[OidcBearerAuthenticator] =
    var superseded: Entry = null
    val now               = cache.compute(
      tenantId,
      (_, current) =>
        loadTenant(tenantId) match
          case None =>
            superseded = current
            null
          case Some(t) =>
            val fp = TenantOidcRegistry.fingerprintOf(t)
            if current != null && current.fingerprint == fp then current
            else
              superseded = current
              Entry(fp, build(tenantId, t))
    )
    if superseded != null then
      closeEntry(superseded)
      logger.info(
        s"TenantOidcRegistry: auth settings of tenant '$tenantId' changed or were removed; " +
          "closed the previous per-tenant OIDC client"
      )
    Option(now).flatMap(_.auth)

  private def closeEntry(e: Entry): Unit =
    e.auth.foreach(a =>
      try a.close()
      catch case _: Throwable => ()
    )

  /** Invalidate the cached authenticator for `tenantId`. Call this on every mutation of the
    * tenant's auth config so the next handshake re-reads from `qodstate_tenant`.
    */
  def invalidate(tenantId: String): Unit =
    val removed = cache.remove(tenantId)
    if removed != null then
      closeEntry(removed)
      logger.info(s"TenantOidcRegistry: invalidated cached OIDC client for tenant '$tenantId'")

  private def build(tenantId: String, t: Tenant): Option[OidcBearerAuthenticator] =
    t.authProvider match
      case "google"   => buildGoogle(tenantId, t)
      case "keycloak" => buildKeycloak(tenantId, t)
      case "azure"    => buildAzure(tenantId, t)
      case "aws"      => buildAws(tenantId, t)
      case _          => None

  private def need(cfg: Map[String, String], key: String): Option[String] =
    TenantOidcRegistry.need(cfg, key)

  private def buildGoogle(tenantId: String, t: Tenant): Option[OidcBearerAuthenticator] =
    for cid <- need(t.authConfig, "clientId")
    yield
      val cfg = GoogleAuthConfig(
        enabled = true,
        clientId = cid,
        clientSecret = "",
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
    yield
      val cfg = KeycloakAuthConfig(
        enabled = true,
        baseUrl = baseUrl,
        realm = realm,
        clientId = cid,
        clientSecret = ""
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
    yield
      val cfg = AzureAuthConfig(
        enabled = true,
        tenantId = aadTenant,
        clientId = cid,
        clientSecret = ""
      )
      logger.info(
        s"TenantOidcRegistry: built per-tenant Azure authenticator for tenant '$tenantId' " +
          s"(aadTenant=$aadTenant clientId=$cid)"
      )
      OidcProviderFactory.createAzure(cfg, roleClaim)

  // AWS Cognito: the JWKS URL and the issuer come from region + userPoolId, the audience is the
  // app client id.
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

object TenantOidcRegistry:

  /** The public `authConfig` keys each provider's builder reads. The fingerprint covers exactly
    * these, so a change of any of them rebuilds the authenticator; a builder that starts reading a
    * new key MUST list it here.
    */
  val PublicKeys: Map[String, List[String]] = Map(
    "google"   -> List("clientId"),
    "keycloak" -> List("baseUrl", "realm", "clientId"),
    "azure"    -> List("tenantId", "clientId"),
    "aws"      -> List("region", "userPoolId", "clientId")
  )

  /** The provider and its public fields, as the builder reads them (trimmed, empty = absent). */
  private[auth] final case class Fingerprint(
      provider: String,
      fields: List[(String, Option[String])]
  )

  private[auth] final case class Entry(
      fingerprint: Fingerprint,
      auth: Option[OidcBearerAuthenticator]
  )

  private[auth] def need(cfg: Map[String, String], key: String): Option[String] =
    cfg.get(key).map(_.trim).filter(_.nonEmpty)

  private[auth] def fingerprintOf(t: Tenant): Fingerprint =
    Fingerprint(
      t.authProvider,
      PublicKeys.getOrElse(t.authProvider, Nil).map(k => k -> need(t.authConfig, k))
    )
