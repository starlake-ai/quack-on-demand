package ai.starlake.quack.edge.auth

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.auth.ParkedSecretRefs
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters.*

/** Unit coverage for [[TenantOidcRegistry]]: dispatch by provider, `None` when a provider's public
  * fields are incomplete, no client secret involved, cache hits and invalidation. Building an
  * [[OidcBearerAuthenticator]] opens no connection (its JWKS source fetches lazily), so these tests
  * build real ones and read back the issuer and audience they validate tokens against.
  */
class TenantOidcRegistrySpec extends AnyFlatSpec with Matchers:

  private def tenant(
      id: String,
      provider: String = "google",
      cfg: Map[String, String] = Map.empty
  ): Tenant =
    Tenant(
      id = id,
      displayName = id,
      authProvider = provider,
      authConfig = cfg
    )

  private def registryOf(t: Option[Tenant]): TenantOidcRegistry =
    new TenantOidcRegistry(loadTenant = _ => t, roleClaim = "role")

  private val GoogleClientId = "abc.apps.googleusercontent.com"

  // -- provider dispatch -----------------------------------------------------

  "TenantOidcRegistry" should "return None for a tenant on an unsupported provider" in:
    registryOf(Some(tenant("t-1", provider = "okta", cfg = Map("realm" -> "x"))))
      .forTenant("t-1") shouldBe None

  it should "return None when the tenant doesn't exist" in:
    registryOf(None).forTenant("t-ghost") shouldBe None

  // -- google ----------------------------------------------------------------

  it should "return None when google clientId is unset (tenant on global Google client)" in:
    registryOf(Some(tenant("t-2", cfg = Map("hd" -> "example.com")))).forTenant("t-2") shouldBe None

  it should "build a google authenticator from clientId alone, with no client secret" in:
    val built = registryOf(Some(tenant("t-g", cfg = Map("clientId" -> GoogleClientId))))
      .forTenant("t-g")
    built.map(_.providerName) shouldBe Some("google")
    built.map(_.expectedAudience) shouldBe Some(GoogleClientId)

  it should "build a google authenticator whatever clientSecretRef the tenant carries" in:
    // The registry resolves no reference, so one that names no environment variable gates nothing.
    val cfg = Map(
      "clientId"        -> GoogleClientId,
      "clientSecretRef" -> "env:QOD_TENANT_OIDC_REGISTRY_SPEC_UNSET"
    )
    val built = registryOf(Some(tenant("t-g2", cfg = cfg))).forTenant("t-g2")
    built.map(_.providerName) shouldBe Some("google")

  it should "build a google authenticator for a tenant whose reference changeset 0048 parked" in:
    val cfg   = Map("clientId" -> GoogleClientId, ParkedSecretRefs.ParkedKey -> "env:GOOGLE_CS")
    val built = registryOf(Some(tenant("t-g3", cfg = cfg))).forTenant("t-g3")
    built.map(_.providerName) shouldBe Some("google")

  // -- keycloak --------------------------------------------------------------

  it should "return None when keycloak is missing clientId" in:
    val cfg = Map("baseUrl" -> "https://keycloak.example.com", "realm" -> "tpch")
    registryOf(Some(tenant("t-kc-1", provider = "keycloak", cfg = cfg)))
      .forTenant("t-kc-1") shouldBe None

  it should "build a keycloak authenticator from baseUrl, realm and clientId" in:
    val cfg = Map(
      "baseUrl"  -> "https://keycloak.example.com",
      "realm"    -> "tpch",
      "clientId" -> "quack-flightsql"
    )
    val built = registryOf(Some(tenant("t-kc-2", provider = "keycloak", cfg = cfg)))
      .forTenant("t-kc-2")
    built.map(_.providerName) shouldBe Some("keycloak")
    built.map(_.expectedIssuer) shouldBe Some("https://keycloak.example.com/realms/tpch")
    built.map(_.expectedAudience) shouldBe Some("quack-flightsql")

  // -- azure -----------------------------------------------------------------

  it should "return None when azure is missing tenantId" in:
    registryOf(Some(tenant("t-az-1", provider = "azure", cfg = Map("clientId" -> "azure-app"))))
      .forTenant("t-az-1") shouldBe None

  it should "build an azure authenticator from tenantId and clientId" in:
    val aad   = "11111111-2222-3333-4444-555555555555"
    val cfg   = Map("tenantId" -> aad, "clientId" -> "azure-app")
    val built = registryOf(Some(tenant("t-az-2", provider = "azure", cfg = cfg)))
      .forTenant("t-az-2")
    built.map(_.providerName) shouldBe Some("azure")
    built.map(_.expectedIssuer) shouldBe Some(s"https://login.microsoftonline.com/$aad/v2.0")
    built.map(_.expectedAudience) shouldBe Some("azure-app")

  // -- aws -------------------------------------------------------------------

  it should "return None when aws is missing userPoolId/clientId" in:
    registryOf(Some(tenant("t-aws-1", provider = "aws", cfg = Map("region" -> "us-east-1"))))
      .forTenant("t-aws-1") shouldBe None

  it should "build an aws cognito authenticator from region, userPoolId and clientId" in:
    val cfg = Map(
      "region"     -> "us-east-1",
      "userPoolId" -> "us-east-1_ABCDE",
      "clientId"   -> "cognito-app-client-id"
    )
    val built = registryOf(Some(tenant("t-aws-2", provider = "aws", cfg = cfg)))
      .forTenant("t-aws-2")
    built.map(_.providerName) shouldBe Some("aws-cognito")
    built.map(_.expectedAudience) shouldBe Some("cognito-app-client-id")

  // -- cache + invalidate ----------------------------------------------------

  it should "reuse the cached authenticator while the tenant's settings are unchanged" in:
    // Every lookup reads the tenant again (the supervisor's in-memory mirror) to compare its
    // public auth fields with the ones the cached authenticator was built from; an unchanged
    // tenant gets the SAME instance, never a rebuild per handshake.
    val r      = registryOf(Some(tenant("t-5", cfg = Map("clientId" -> GoogleClientId))))
    val first  = r.forTenant("t-5")
    val second = r.forTenant("t-5")
    first shouldBe defined
    second.get should be theSameInstanceAs first.get
    first.get.isClosed shouldBe false

  it should "drop the cached entry on invalidate, forcing a reload" in:
    var loads = 0
    val r     = new TenantOidcRegistry(
      loadTenant = _ => {
        loads += 1
        Some(tenant("t-6", cfg = Map("clientId" -> GoogleClientId)))
      },
      roleClaim = "role"
    )
    r.forTenant("t-6") shouldBe defined
    r.invalidate("t-6")
    r.forTenant("t-6") shouldBe defined
    loads shouldBe 2

  it should "keep no entry for a tenant that does not exist" in:
    // A handshake names its tenant: an unknown id must not leave an entry behind, or a client
    // naming random tenants would grow the map without bound.
    val r = registryOf(None)
    r.forTenant("t-7") shouldBe None
    r.forTenant("t-8") shouldBe None
    r.cacheSize shouldBe 0

  // -- settings changed on another replica (HA) --------------------------------

  /** The supervisor's tenant mirror, shared by two replicas' registries the way every replica's
    * `restore()` refreshes its own copy from the same control-plane rows.
    */
  private final class TenantMirror(initial: Tenant*):
    private val rows =
      new java.util.concurrent.ConcurrentHashMap[String, Tenant]()
    initial.foreach(t => rows.put(t.id, t))
    def load(id: String): Option[Tenant] = Option(rows.get(id))
    def put(t: Tenant): Unit             = { rows.put(t.id, t); () }
    def delete(id: String): Unit         = { rows.remove(id); () }
    def registry(): TenantOidcRegistry   = new TenantOidcRegistry(load, "role")

  private def keycloak(id: String, realm: String): Tenant =
    tenant(
      id,
      provider = "keycloak",
      cfg = Map(
        "baseUrl"  -> "https://keycloak.example.com",
        "realm"    -> realm,
        "clientId" -> "quack-flightsql"
      )
    )

  it should "rebuild on a peer replica once the shared tenant rows carry new auth settings" in:
    val mirror    = new TenantMirror(keycloak("t-ha", "old"))
    val serving   = mirror.registry()
    val peer      = mirror.registry()
    val oldOnPeer = peer.forTenant("t-ha").get
    oldOnPeer.expectedIssuer shouldBe "https://keycloak.example.com/realms/old"
    serving.forTenant("t-ha") shouldBe defined

    // setTenantAuth on the serving replica: the row changes, the serving replica invalidates its
    // own entry; the peer only sees the row through its next restore().
    mirror.put(keycloak("t-ha", "new"))
    serving.invalidate("t-ha")

    val newOnPeer = peer.forTenant("t-ha").get
    newOnPeer.expectedIssuer shouldBe "https://keycloak.example.com/realms/new"
    newOnPeer should not be theSameInstanceAs(oldOnPeer)
    oldOnPeer.isClosed shouldBe true
    newOnPeer.isClosed shouldBe false
    // ...and the new one is then reused.
    peer.forTenant("t-ha").get should be theSameInstanceAs newOnPeer

  it should "rebuild when the provider changes even if the shared keys are equal" in:
    val mirror = new TenantMirror(tenant("t-pv", cfg = Map("clientId" -> GoogleClientId)))
    val r      = mirror.registry()
    val before = r.forTenant("t-pv").get
    before.providerName shouldBe "google"
    mirror.put(
      tenant(
        "t-pv",
        provider = "aws",
        cfg = Map("clientId" -> GoogleClientId, "region" -> "eu-west-1", "userPoolId" -> "p")
      )
    )
    r.forTenant("t-pv").map(_.providerName) shouldBe Some("aws-cognito")
    before.isClosed shouldBe true

  it should "keep the cached instance when only a non-auth field of the tenant changes" in:
    val mirror = new TenantMirror(keycloak("t-same", "r"))
    val r      = mirror.registry()
    val before = r.forTenant("t-same").get
    mirror.put(
      keycloak("t-same", "r").copy(
        displayName = "renamed",
        authConfig = keycloak("t-same", "r").authConfig + ("hd" -> "example.com")
      )
    )
    r.forTenant("t-same").get should be theSameInstanceAs before
    before.isClosed shouldBe false

  it should "fall back to None and close the old authenticator once the tenant is deleted" in:
    val mirror = new TenantMirror(keycloak("t-del", "r"))
    val r      = mirror.registry()
    val before = r.forTenant("t-del").get
    mirror.delete("t-del")
    r.forTenant("t-del") shouldBe None
    before.isClosed shouldBe true
    r.cacheSize shouldBe 0

  it should "fall back to None and close the old one when the provider becomes unsupported" in:
    val mirror = new TenantMirror(keycloak("t-db", "r"))
    val r      = mirror.registry()
    val before = r.forTenant("t-db").get
    mirror.put(keycloak("t-db", "r").copy(authProvider = "db"))
    r.forTenant("t-db") shouldBe None
    before.isClosed shouldBe true

  it should "fall back to None and close the old one when a required public field is cleared" in:
    val mirror = new TenantMirror(keycloak("t-empty", "r"))
    val r      = mirror.registry()
    val before = r.forTenant("t-empty").get
    mirror.put(keycloak("t-empty", "r").copy(authConfig = Map("baseUrl" -> "x", "realm" -> "r")))
    r.forTenant("t-empty") shouldBe None
    before.isClosed shouldBe true
    // A provider configured again builds afresh.
    mirror.put(keycloak("t-empty", "back"))
    r.forTenant("t-empty").map(_.expectedIssuer) shouldBe
      Some("https://keycloak.example.com/realms/back")

  it should "close the entry invalidate drops" in:
    val mirror = new TenantMirror(keycloak("t-inv", "r"))
    val r      = mirror.registry()
    val before = r.forTenant("t-inv").get
    r.invalidate("t-inv")
    before.isClosed shouldBe true
    r.forTenant("t-inv").get should not be theSameInstanceAs(before)

  it should "never hand out a closed authenticator to a lookup made after a swap returned" in:
    // Handshakes race a settings flip: every lookup that STARTS after the flip's own lookup
    // returned must get the new issuer, open; every instance is closed at most once, and only
    // the superseded ones are closed.
    val mirror   = new TenantMirror(keycloak("t-race", "r0"))
    val r        = mirror.registry()
    val pool     = java.util.concurrent.Executors.newFixedThreadPool(8)
    val seen     = java.util.concurrent.ConcurrentHashMap.newKeySet[OidcBearerAuthenticator]()
    val failures = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    try
      (1 to 40).foreach { round =>
        val realm   = s"r$round"
        val stop    = new java.util.concurrent.atomic.AtomicBoolean(false)
        val readers = (1 to 6).map { _ =>
          pool.submit(
            new Runnable:
              def run(): Unit =
                while !stop.get() do r.forTenant("t-race").foreach(seen.add)
          )
        }
        mirror.put(keycloak("t-race", realm))
        val swapped = r.forTenant("t-race").get
        // From here on every lookup starts after the swap returned.
        (1 to 50).foreach { _ =>
          val a = r.forTenant("t-race").get
          if a.isClosed then failures.add(s"round $round: closed instance handed out")
          if !a.expectedIssuer.endsWith(s"/realms/$realm") then
            failures.add(s"round $round: stale issuer ${a.expectedIssuer}")
        }
        stop.set(true)
        readers.foreach(_.get())
        swapped.expectedIssuer should endWith(s"/realms/$realm")
      }
    finally pool.shutdownNow()
    failures.asScala.toList shouldBe Nil
    val current = r.forTenant("t-race").get
    seen.asScala.filterNot(_ eq current).foreach(_.isClosed shouldBe true)
    seen.asScala.foreach(a => a.closeCount should be <= 1)
    current.isClosed shouldBe false
