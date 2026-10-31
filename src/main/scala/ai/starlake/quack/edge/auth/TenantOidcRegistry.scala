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
  *     auth fields (the provider plus the keys its [[TenantOidcRegistry.Provider]] declares, the
  *     only ones its builder can read) with the one the cached authenticator was built from. Equal:
  *     the cached instance is reused. Different: a new authenticator is built and the old one
  *     closed. So under HA a tenant's SSO change made on one replica applies on every other one
  *     once its mirror is refreshed (a `qod_topology` NOTIFY, or the periodic refresh when a NOTIFY
  *     was missed), with no channel of its own.
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
  * `authConfig` keys alone (the ones its [[TenantOidcRegistry.Provider]] declares) and resolves no
  * secret. A tenant's stored client secret and its `clientSecretRef` serve the admin-console SSO
  * code exchange only ([[OidcEndpointResolver]]); the provider configs built here carry
  * `clientSecret = ""`, a field [[OidcProviderFactory]] never reads.
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
              Entry(fp, build(tenantId, fp))
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

  /** Build from the fingerprint alone, so the builder sees exactly the keys the fingerprint covers:
    * `None` when the provider is unsupported or one of its declared keys is absent.
    */
  private def build(tenantId: String, fp: Fingerprint): Option[OidcBearerAuthenticator] =
    TenantOidcRegistry.Providers.get(fp.provider).flatMap { provider =>
      val values = fp.fields.collect { case (k, Some(v)) => k -> v }.toMap
      if values.size != provider.keys.size then None
      else
        val auth = provider.make(TenantOidcRegistry.declaredOnly(fp.provider, values), roleClaim)
        logger.info(
          s"TenantOidcRegistry: built per-tenant ${fp.provider} authenticator for tenant " +
            s"'$tenantId' (${provider.keys.map(k => s"$k=${values(k)}").mkString(" ")})"
        )
        Some(auth)
    }

  /** Visible for tests. */
  private[auth] def cacheSize: Int = cache.size

object TenantOidcRegistry:

  /** One supported provider: the public `authConfig` keys its builder reads, declared once, and the
    * builder. The builder receives a lookup over those keys only (any other key fails the build),
    * and the fingerprint covers exactly those keys, so a key the builder reads is always a key
    * whose change rebuilds the authenticator.
    */
  private[auth] final class Provider(
      val keys: List[String],
      val make: (String => String, String) => OidcBearerAuthenticator
  )

  /** Every supported provider by `authProvider` value. AWS Cognito: the JWKS URL and the issuer
    * come from region + userPoolId, the audience is the app client id.
    */
  private[auth] val Providers: Map[String, Provider] = Map(
    "google" -> Provider(
      List("clientId"),
      (f, roleClaim) =>
        OidcProviderFactory.createGoogle(
          GoogleAuthConfig(
            enabled = true,
            clientId = f("clientId"),
            clientSecret = "",
            groupsLookup = false,
            serviceAccountKeyPath = "",
            groupsCacheTtlSeconds = 300L
          ),
          roleClaim
        )
    ),
    "keycloak" -> Provider(
      List("baseUrl", "realm", "clientId"),
      (f, roleClaim) =>
        OidcProviderFactory.createKeycloak(
          KeycloakAuthConfig(
            enabled = true,
            baseUrl = f("baseUrl"),
            realm = f("realm"),
            clientId = f("clientId"),
            clientSecret = ""
          ),
          roleClaim
        )
    ),
    "azure" -> Provider(
      List("tenantId", "clientId"),
      (f, roleClaim) =>
        OidcProviderFactory.createAzure(
          AzureAuthConfig(
            enabled = true,
            tenantId = f("tenantId"),
            clientId = f("clientId"),
            clientSecret = ""
          ),
          roleClaim
        )
    ),
    "aws" -> Provider(
      List("region", "userPoolId", "clientId"),
      (f, roleClaim) =>
        OidcProviderFactory.createAws(
          AwsAuthConfig(
            enabled = true,
            region = f("region"),
            userPoolId = f("userPoolId"),
            clientId = f("clientId")
          ),
          roleClaim
        )
    )
  )

  /** The lookup a builder reads through: a key outside its declaration fails the build (fail
    * closed) instead of being read unfingerprinted.
    */
  private def declaredOnly(provider: String, values: Map[String, String]): String => String =
    k =>
      values.getOrElse(
        k,
        throw new IllegalStateException(
          s"TenantOidcRegistry: the $provider builder read authConfig key '$k', which its " +
            "Provider declaration does not list"
        )
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
      Providers.get(t.authProvider).fold(Nil)(_.keys).map(k => k -> need(t.authConfig, k))
    )
