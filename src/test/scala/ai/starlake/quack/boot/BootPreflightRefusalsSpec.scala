package ai.starlake.quack.boot

import ai.starlake.quack.{
  FlightConfig,
  Main,
  ManagementAuthConfig,
  ManagementOidcConfig,
  ManagerConfig
}
import ai.starlake.quack.edge.auth.AuthenticationService
import ai.starlake.quack.edge.config.{AuthenticationConfig, AzureAuthConfig, OpaConfig}
import ai.starlake.quack.observability.metrics.MetricsConfig
import ai.starlake.quack.observability.metrics.MetricsConfigCodec.given
import ai.starlake.quack.security.InMemoryAuthService.emptyAuthConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** The boot gates around removed settings and authentication:
  *   - the retired settings (`BootPreflight.checkRetiredSettings`): a switch set to off that the
  *     node now enforces always (`QOD_ACL_ENABLED`, `QOD_NODE_LOCKDOWN`) and a removed knob
  *     (`QOD_ACL_FILTERED_METADATA`, `QOD_ACL_DIALECT`) stop the boot before HA validation; the
  *     ignored ones (`QOD_API_KEY`, `QOD_ADMIN_ROLE`, the switches set to anything but off) boot
  *     with a warning;
  *   - a FlightSQL edge with no authenticator at all (the handshake would refuse every client)
  *     stops the boot, also before HA validation;
  *   - a manager nobody can sign in to (no password provider and no system-scope OIDC sign-in, as
  *     `ManagementAuthWiring` serves it) stops too, also before HA validation.
  */
class BootPreflightRefusalsSpec extends AnyFlatSpec with Matchers:
  import Main.given

  private val noEnv: String => Option[String]             = _ => None
  private def keyEnv(v: String): String => Option[String] = Map("QOD_API_KEY" -> v).get

  "checkRetiredSettings" should "warn, not refuse, a non-blank QOD_API_KEY or quack-on-demand.apiKey" in {
    BootPreflight.checkRetiredSettings(keyEnv("k1"), ConfigFactory.empty()) shouldBe
      Right(List(BootPreflight.IgnoredApiKeyWarning))
    val raw = ConfigFactory.parseString("quack-on-demand.apiKey = \"k1\"")
    BootPreflight.checkRetiredSettings(noEnv, raw) shouldBe
      Right(List(BootPreflight.IgnoredApiKeyWarning))
  }

  it should "pass an empty or blank key, and an absent one, silently" in {
    BootPreflight.checkRetiredSettings(keyEnv(""), ConfigFactory.empty()) shouldBe Right(Nil)
    BootPreflight.checkRetiredSettings(keyEnv("   "), ConfigFactory.empty()) shouldBe Right(Nil)
    val blank = ConfigFactory.parseString("quack-on-demand.apiKey = \" \"")
    BootPreflight.checkRetiredSettings(noEnv, blank) shouldBe Right(Nil)
    BootPreflight.checkRetiredSettings(noEnv, ConfigFactory.empty()) shouldBe Right(Nil)
  }

  it should "carry the ignored-key warning verbatim" in {
    BootPreflight.IgnoredApiKeyWarning shouldBe
      "WARNING: QOD_API_KEY (quack-on-demand.apiKey) was removed and is ignored: the manager no " +
      "longer accepts a static API key, so clients sending it are refused. Use a personal access " +
      "token (qod auth pat create) or a qod login session, and unset QOD_API_KEY."
  }

  /** Boots with HA on over the local runtime, which HA validation always refuses: a boot that gets
    * past the retired-setting gate stops there, before it opens any socket or reaches Postgres (no
    * probe, no Liquibase).
    */
  private def bootUntilHaRefusal(env: String => Option[String], authOverlay: String = ""): Unit =
    boot(
      env,
      "quack-on-demand { ha.enabled = true, runtimeType = local, embeddedPostgres.enabled = false }" +
        "\n" + authOverlay
    )

  private def boot(env: String => Option[String], overlay: String): Unit =
    val source = ConfigSource
      .string(overlay)
      .withFallback(ConfigSource.default)
    val opa = OpaConfig
      .validate(source.at("quack-flightsql.opa").loadOrThrow[OpaConfig])
      .fold(e => fail(e), identity)
    Main.bootManager(
      source.at("quack-on-demand").loadOrThrow[ManagerConfig],
      source.at("quack-flightsql").loadOrThrow[FlightConfig],
      source.at("quack-flightsql.auth").loadOrThrow[AuthenticationConfig],
      source.at("quack-on-demand.metrics").loadOrThrow[MetricsConfig],
      opa,
      env = env,
      rawConfig = ConfigFactory.empty()
    )
    ()

  "Main.bootManager" should "go on to HA validation with a set QOD_API_KEY, warning only" in {
    val err = intercept[RuntimeException](bootUntilHaRefusal(keyEnv("k1")))
    err.getMessage should include("ha.enabled=true requires runtimeType=kubernetes or fleet")
  }

  it should "go on to HA validation with an empty QOD_API_KEY" in {
    val err = intercept[RuntimeException](bootUntilHaRefusal(keyEnv("")))
    err.getMessage should include("ha.enabled=true requires runtimeType=kubernetes or fleet")
  }

  /** HA over Kubernetes with no session secret configured: every replica reads the control plane's,
    * so HA validation passes. Lockout on with no SMTP relay is the next refusal (after the native
    * library check), still before the boot opens any socket or reaches Postgres, and stops it
    * there.
    */
  it should "go past HA validation with no session secret configured" in {
    val err = intercept[RuntimeException](
      boot(
        noEnv,
        "quack-on-demand { ha.enabled = true, runtimeType = kubernetes, " +
          "embeddedPostgres.enabled = false, auth.management.sessionJwtSecret = \"\", " +
          "auth.lockout.enabled = true, smtp.host = \"\" }"
      )
    )
    err.getMessage should startWith("auth.lockout.enabled is true but no SMTP relay")
  }

  /** Every `quack-flightsql.auth` provider off, whatever the test environment sets. */
  private val NoEdgeAuth =
    "quack-flightsql.auth { database.enabled = false, keycloak.enabled = false, " +
      "google.enabled = false, azure.enabled = false, aws.enabled = false, " +
      "jwt { secretKey = \"\", publicKeyPath = \"\" } }\n" +
      // System-scope OIDC set: it gates the admin UI and REST only, so it must not admit the edge.
      "quack-on-demand.auth.management { identitySource = oidc, " +
      "oidc { issuerUrl = \"https://accounts.google.com\", clientId = \"c\" } }"

  it should "refuse a FlightSQL edge with no authenticator before HA validation" in {
    val err = intercept[RuntimeException](bootUntilHaRefusal(noEnv, NoEdgeAuth))
    err.getMessage shouldBe BootPreflight.NoEdgeAuthMessage
  }

  /** A bearer provider only (Google has no password grant), and system-scope sign-in by password:
    * the edge has an authenticator, but nothing could create a session.
    */
  private val NoLogin =
    "quack-flightsql.auth { database.enabled = false, keycloak.enabled = false, " +
      "google { enabled = true, clientId = \"c\" }, azure.enabled = false, aws.enabled = false, " +
      "jwt { secretKey = \"\", publicKeyPath = \"\" } }\n" +
      "quack-on-demand.auth.management { identitySource = db, " +
      "oidc { issuerUrl = \"https://accounts.google.com\", clientId = \"c\" } }"

  it should "refuse a manager nobody can sign in to before HA validation" in {
    val err = intercept[RuntimeException](bootUntilHaRefusal(noEnv, NoLogin))
    err.getMessage should startWith("nothing can sign in to this manager")
  }

  /** The management auth block with only the fields the login gate reads set. */
  private def mgmt(
      identitySource: String = "db",
      issuerUrl: String = "",
      clientId: String = ""
  ): ManagementAuthConfig =
    ManagementAuthConfig(
      identitySource = identitySource,
      sessionJwtSecret = "",
      sessionCookieSecure = "auto",
      sessionCookiePath = "/api",
      oidc = ManagementOidcConfig(issuerUrl = issuerUrl, clientId = clientId)
    )

  private val google = "https://accounts.google.com"

  "checkLoginAvailable" should "refuse with neither a password provider nor system-scope OIDC" in {
    val out = BootPreflight.checkLoginAvailable(hasPasswordProvider = false, mgmt())
    out.isLeft shouldBe true
    out.left.toOption.get should include("QOD_AUTH_DB_ENABLED")
    out.left.toOption.get should include("QOD_MGMT_OIDC_ISSUER_URL")
    out.left.toOption.get should include("QOD_MGMT_IDENTITY_SOURCE=oidc")
  }

  it should "treat a blank issuer URL or client id as unset" in {
    BootPreflight
      .checkLoginAvailable(false, mgmt("oidc", issuerUrl = "  ", clientId = "c"))
      .isLeft shouldBe true
    BootPreflight
      .checkLoginAvailable(false, mgmt("oidc", issuerUrl = google, clientId = " "))
      .isLeft shouldBe true
  }

  it should "not count a configured issuer while the system scope signs in by password (db)" in {
    // ManagementAuthWiring hands the system scope no OIDC client under identitySource=db, so the
    // QOD_MGMT_OIDC_* values serve no sign-in there.
    BootPreflight
      .checkLoginAvailable(false, mgmt("db", issuerUrl = google, clientId = "c"))
      .isLeft shouldBe true
  }

  it should "boot with a password provider alone" in {
    BootPreflight.checkLoginAvailable(hasPasswordProvider = true, mgmt()) shouldBe Right(())
  }

  it should "boot with system-scope OIDC alone (identitySource=oidc, issuer and client id)" in {
    BootPreflight.checkLoginAvailable(
      hasPasswordProvider = false,
      mgmt(" OIDC ", issuerUrl = google, clientId = "c")
    ) shouldBe Right(())
  }

  "AuthenticationService.hasPasswordProvider" should "follow the basic chain, not the bearer one" in {
    def has(cfg: AuthenticationConfig): Boolean =
      val svc = new AuthenticationService(cfg, "")
      try svc.hasPasswordProvider
      finally svc.close()
    // Every provider off: no basic chain at all.
    has(emptyAuthConfig) shouldBe false
    // Google is bearer-only (no password grant): still no password provider.
    has(
      emptyAuthConfig.copy(google = emptyAuthConfig.google.copy(enabled = true, clientId = "c"))
    ) shouldBe
      false
    // Azure adds a resource-owner-password provider to the basic chain.
    has(
      emptyAuthConfig.copy(azure =
        AzureAuthConfig(enabled = true, tenantId = "t", clientId = "c", clientSecret = "s")
      )
    ) shouldBe true
  }

  private val jwtSecret =
    emptyAuthConfig.copy(jwt =
      emptyAuthConfig.jwt.copy(secretKey = "edge-jwt-secret-of-at-least-256-bits-0123")
    )
  private val jwtPublicKey =
    emptyAuthConfig.copy(jwt = emptyAuthConfig.jwt.copy(publicKeyPath = "/keys/issuer.pem"))
  private val databaseAuth =
    emptyAuthConfig.copy(database = emptyAuthConfig.database.copy(enabled = true))
  private val keycloakAuth = emptyAuthConfig.copy(keycloak =
    emptyAuthConfig.keycloak
      .copy(enabled = true, baseUrl = "https://kc", realm = "r", clientId = "c")
  )
  private val googleAuth =
    emptyAuthConfig.copy(google = emptyAuthConfig.google.copy(enabled = true, clientId = "c"))
  private val azureAuth = emptyAuthConfig.copy(azure =
    AzureAuthConfig(enabled = true, tenantId = "t", clientId = "c", clientSecret = "s")
  )
  private val awsAuth = emptyAuthConfig.copy(aws =
    emptyAuthConfig.aws.copy(enabled = true, region = "eu-west-1", userPoolId = "p", clientId = "c")
  )

  "checkEdgeAuthAvailable" should "refuse a chain with no provider at all" in {
    BootPreflight.checkEdgeAuthAvailable(emptyAuthConfig) shouldBe
      Left(BootPreflight.NoEdgeAuthMessage)
  }

  it should "name what to set, and that system-scope OIDC does not count" in {
    val msg = BootPreflight.NoEdgeAuthMessage
    for env <- List(
        "QOD_AUTH_DB_ENABLED=true",
        "QOD_AUTH_KEYCLOAK_ENABLED",
        "QOD_AUTH_AZURE_ENABLED",
        "JWT_SECRET_KEY",
        "JWT_PUBLIC_KEY_PATH",
        "QOD_AUTH_GOOGLE_ENABLED",
        "QOD_AUTH_AWS_ENABLED",
        "QOD_MGMT_OIDC"
      )
    do msg should include(env)
  }

  it should "admit each provider kind on its own" in {
    for (label, cfg) <- List(
        "database"       -> databaseAuth,
        "keycloak"       -> keycloakAuth,
        "azure"          -> azureAuth,
        "google"         -> googleAuth,
        "aws"            -> awsAuth,
        "jwt secret key" -> jwtSecret,
        "jwt public key" -> jwtPublicKey
      )
    do withClue(label)(BootPreflight.checkEdgeAuthAvailable(cfg) shouldBe Right(()))
  }

  "AuthenticationService.configuresProvider" should "agree with the chains the service builds" in {
    // The boot check reads the config at the top of bootManager, before any socket; the handshake
    // reads the built chains. They must agree for every provider kind the service can build
    // without a database or a key file.
    for cfg <- List(emptyAuthConfig, keycloakAuth, azureAuth, googleAuth, awsAuth, jwtSecret) do
      val svc = new AuthenticationService(cfg, "")
      try AuthenticationService.configuresProvider(cfg) shouldBe svc.hasProviders
      finally svc.close()
  }

  "AuthenticationService.configuresPasswordProvider" should "agree with the basic chain built" in {
    // The login check reads the config at the top of the boot; the service builds the chain later.
    for cfg <- List(emptyAuthConfig, keycloakAuth, azureAuth, googleAuth, awsAuth, jwtSecret) do
      val svc = new AuthenticationService(cfg, "")
      try AuthenticationService.configuresPasswordProvider(cfg) shouldBe svc.hasPasswordProvider
      finally svc.close()
    AuthenticationService.configuresPasswordProvider(databaseAuth) shouldBe true
  }

  /** A boot that gets past the lockout check reaches the decide-URL check before it probes
    * Postgres: Kubernetes with neither QOD_INTERNAL_URL nor QOD_POD_IP stops there, and so does
    * fleet mode without QOD_INTERNAL_URL.
    */
  "Main.bootManager" should "refuse a decide URL the runtime's nodes cannot reach, before reaching Postgres" in {
    val urls =
      "nodeAuthz { internalUrl = \"\", podIp = \"\" }, host = \"0.0.0.0\", auth.lockout.enabled = false"
    // Both names of the Kubernetes runtime.
    for runtime <- List("kubernetes", "k8s") do
      val k8s = intercept[RuntimeException](
        boot(
          noEnv,
          s"quack-on-demand { runtimeType = $runtime, embeddedPostgres.enabled = false, $urls }"
        )
      )
      withClue(runtime)(k8s.getMessage should startWith("the node decide URL is http://127.0.0.1:"))
    val fleet = intercept[RuntimeException](
      boot(
        noEnv,
        "quack-on-demand { runtimeType = fleet, fleet.joinToken = \"x\", " +
          s"embeddedPostgres.enabled = false, $urls }"
      )
    )
    fleet.getMessage shouldBe BootPreflight.InternalUrlFleetMessage
  }

  private def switchEnv(name: String, v: String): String => Option[String] = Map(name -> v).get

  /** The switches the node now enforces always: the variable, every configuration spelling, the
    * refusal for off and the warning for anything else.
    */
  private val enforcedSwitches = List(
    (
      "QOD_ACL_ENABLED",
      List("quack-flightsql.acl.enabled = %s"),
      BootPreflight.DisabledAclRefusal,
      BootPreflight.IgnoredAclEnabledWarning
    ),
    (
      "QOD_NODE_LOCKDOWN",
      List("quack-flightsql.nodeLockdown { enabled = %s }", "quack-flightsql.nodeLockdown = %s"),
      BootPreflight.DisabledNodeLockdownRefusal,
      BootPreflight.IgnoredNodeLockdownWarning
    )
  )

  /** The knobs that no longer exist: refused whatever their value. */
  private val removedKnobs = List(
    (
      "QOD_ACL_FILTERED_METADATA",
      "quack-flightsql.acl.filteredMetadata",
      BootPreflight.RemovedAclFilteredMetadataMessage
    ),
    ("QOD_ACL_DIALECT", "quack-flightsql.acl.dialect", BootPreflight.RemovedAclDialectMessage)
  )

  "checkRetiredSettings" should "refuse an enforced switch set to off, in any spelling" in {
    for (name, paths, refusal, _) <- enforcedSwitches; v <- List("false", "FALSE", " no ", "off") do
      withClue(s"$name='$v' ")(
        BootPreflight.checkRetiredSettings(switchEnv(name, v), ConfigFactory.empty()) shouldBe
          Left(refusal)
      )
      for p <- paths do
        withClue(s"${p.format(v.trim)} ")(
          BootPreflight.checkRetiredSettings(
            noEnv,
            ConfigFactory.parseString(p.format(v.trim))
          ) shouldBe Left(refusal)
        )
  }

  it should "boot an enforced switch set to anything but off, warning that it is ignored" in {
    for (name, paths, _, warning) <- enforcedSwitches; v <- List("true", "yes", "on", "", "  ") do
      withClue(s"$name='$v' ")(
        BootPreflight.checkRetiredSettings(switchEnv(name, v), ConfigFactory.empty()) shouldBe
          Right(List(warning))
      )
    for (_, paths, _, warning) <- enforcedSwitches; p <- paths do
      withClue(s"${p.format("true")} ")(
        BootPreflight.checkRetiredSettings(
          noEnv,
          ConfigFactory.parseString(p.format("true"))
        ) shouldBe
          Right(List(warning))
      )
  }

  it should "warn on a lockdown block with no enabled key, or a null" in {
    for body <- List("quack-flightsql.nodeLockdown {}", "quack-flightsql.nodeLockdown = null") do
      withClue(s"$body ")(
        BootPreflight.checkRetiredSettings(noEnv, ConfigFactory.parseString(body)) shouldBe
          Right(List(BootPreflight.IgnoredNodeLockdownWarning))
      )
  }

  it should "let the variable override the configuration, as ${?VAR} did" in {
    val raw = ConfigFactory.parseString("quack-flightsql.acl.enabled = false")
    BootPreflight.checkRetiredSettings(switchEnv("QOD_ACL_ENABLED", "true"), raw) shouldBe
      Right(List(BootPreflight.IgnoredAclEnabledWarning))
  }

  it should "refuse each removed knob set to any value, empty included" in {
    for (name, path, message) <- removedKnobs; v <- List("true", "false", "duckdb", "", "  ") do
      withClue(s"$name='$v' ")(
        BootPreflight.checkRetiredSettings(switchEnv(name, v), ConfigFactory.empty()) shouldBe
          Left(message)
      )
    for (_, path, message) <- removedKnobs; v <- List("true", "false", "duckdb", "null") do
      withClue(s"$path = $v ")(
        BootPreflight.checkRetiredSettings(noEnv, ConfigFactory.parseString(s"$path = $v")) shouldBe
          Left(message)
      )
  }

  it should "warn on a non-blank QOD_ADMIN_ROLE or quack-on-demand.admin.role, and accept QOD_ADMIN_KIND" in {
    BootPreflight.checkRetiredSettings(
      switchEnv("QOD_ADMIN_ROLE", "admin"),
      ConfigFactory.empty()
    ) shouldBe
      Right(List(BootPreflight.IgnoredAdminRoleWarning))
    BootPreflight.checkRetiredSettings(
      noEnv,
      ConfigFactory.parseString("quack-on-demand.admin.role = admin")
    ) shouldBe Right(List(BootPreflight.IgnoredAdminRoleWarning))
    BootPreflight.checkRetiredSettings(
      switchEnv("QOD_ADMIN_ROLE", ""),
      ConfigFactory.empty()
    ) shouldBe
      Right(Nil)
    BootPreflight.checkRetiredSettings(
      switchEnv("QOD_ADMIN_KIND", "admin"),
      ConfigFactory.parseString("quack-on-demand.admin.kind = admin")
    ) shouldBe Right(Nil)
  }

  it should "refuse before warning, and gather every warning in order" in {
    val both: String => Option[String] =
      Map("QOD_API_KEY" -> "k1", "QOD_ACL_ENABLED" -> "false").get
    BootPreflight.checkRetiredSettings(both, ConfigFactory.empty()) shouldBe
      Left(BootPreflight.DisabledAclRefusal)
    val many: String => Option[String] =
      Map("QOD_API_KEY" -> "k1", "QOD_NODE_LOCKDOWN" -> "true", "QOD_ADMIN_ROLE" -> "admin").get
    BootPreflight.checkRetiredSettings(many, ConfigFactory.empty()) shouldBe Right(
      List(
        BootPreflight.IgnoredApiKeyWarning,
        BootPreflight.IgnoredNodeLockdownWarning,
        BootPreflight.IgnoredAdminRoleWarning
      )
    )
  }

  it should "pass the bundled configuration and the variables that remain" in {
    // The bundled application.conf must hold no retired key, or every default boot is refused.
    BootPreflight.checkRetiredSettings(noEnv, ConfigFactory.load()) shouldBe Right(Nil)
    // QOD_ACL_MODE (quack-flightsql.opa.defaultMode) picks a tenant's engine: not retired.
    BootPreflight.checkRetiredSettings(
      switchEnv("QOD_ACL_MODE", "opa"),
      ConfigFactory.parseString("quack-flightsql.opa.defaultMode = opa")
    ) shouldBe Right(Nil)
    for name <- List(
        "QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS",
        "QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS",
        "QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS"
      )
    do
      BootPreflight.checkRetiredSettings(switchEnv(name, "true"), ConfigFactory.empty()) shouldBe
        Right(Nil)
  }

  it should "carry the refusals and warnings verbatim, each naming the variable to unset" in {
    BootPreflight.DisabledAclRefusal shouldBe
      "QOD_ACL_ENABLED=false (quack-flightsql.acl.enabled) cannot be honored: the setting was " +
      "removed and qod-node now checks every statement against the grants (or the tenant's OPA) " +
      "always, so authorization cannot be turned off. Unset QOD_ACL_ENABLED."
    BootPreflight.IgnoredAclEnabledWarning shouldBe
      "WARNING: QOD_ACL_ENABLED (quack-flightsql.acl.enabled) was removed and is ignored: qod-node " +
      "checks every statement against the grants (or the tenant's OPA) regardless of this " +
      "setting. Unset QOD_ACL_ENABLED."
    BootPreflight.DisabledNodeLockdownRefusal shouldBe
      "QOD_NODE_LOCKDOWN=false (quack-flightsql.nodeLockdown) cannot be honored: the setting was " +
      "removed and qod-node now refuses ATTACH, INSTALL, LOAD, protected settings and " +
      "unauthorized files to non-superusers on every pool, always, so lockdown cannot be turned " +
      "off; the extension settings follow quack-on-demand.nodeExtensions. Unset " +
      "QOD_NODE_LOCKDOWN."
    BootPreflight.IgnoredNodeLockdownWarning shouldBe
      "WARNING: QOD_NODE_LOCKDOWN (quack-flightsql.nodeLockdown) was removed and is ignored: " +
      "qod-node refuses ATTACH, INSTALL, LOAD, protected settings and unauthorized files to " +
      "non-superusers on every pool regardless of this setting. Unset QOD_NODE_LOCKDOWN."
    BootPreflight.RemovedAclFilteredMetadataMessage shouldBe
      "QOD_ACL_FILTERED_METADATA (quack-flightsql.acl.filteredMetadata) was removed: a tenant " +
      "user's catalog listings are narrowed to the objects the user is granted. To give a " +
      "principal unfiltered listings, grant it RO on information_schema. Unset " +
      "QOD_ACL_FILTERED_METADATA."
    BootPreflight.RemovedAclDialectMessage shouldBe
      "QOD_ACL_DIALECT (quack-flightsql.acl.dialect) was removed: the manager reads SQL in the " +
      "DuckDB dialect only, and nothing selects another. Unset QOD_ACL_DIALECT."
    BootPreflight.IgnoredAdminRoleWarning shouldBe
      "WARNING: QOD_ADMIN_ROLE (quack-on-demand.admin.role) was renamed QOD_ADMIN_KIND " +
      "(quack-on-demand.admin.kind) and is ignored. It only sets the account kind of an admin " +
      "created on first boot. Set QOD_ADMIN_KIND instead and unset QOD_ADMIN_ROLE."
  }

  "Main.bootManager" should "refuse an enforced switch set to off before HA validation" in {
    for (name, _, refusal, _) <- enforcedSwitches do
      val err = intercept[RuntimeException](bootUntilHaRefusal(switchEnv(name, "false")))
      withClue(name)(err.getMessage shouldBe refusal)
  }

  it should "refuse a removed knob before HA validation" in {
    for (name, _, message) <- removedKnobs do
      val err = intercept[RuntimeException](bootUntilHaRefusal(switchEnv(name, "true")))
      withClue(name)(err.getMessage shouldBe message)
  }

  it should "go on to HA validation with an enforced switch set to on, warning only" in {
    for (name, _, _, _) <- enforcedSwitches do
      val err = intercept[RuntimeException](bootUntilHaRefusal(switchEnv(name, "true")))
      withClue(name)(
        err.getMessage should include("ha.enabled=true requires runtimeType=kubernetes or fleet")
      )
  }
