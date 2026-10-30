// src/test/scala/ai/starlake/quack/security/HandlerAuditSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.model.{FederatedSecret, FederatedSource, Tenant}
import ai.starlake.quack.ondemand.InProcessDuckDb
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.*
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{FederatedSourceOps, UserStore}
import ai.starlake.quack.ondemand.telemetry.{AuditEvent, AuditRateLimiter, AuditRecorder}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import org.scalatest.OptionValues

import java.sql.DriverManager

/** Handler-level audit spec. Tests that [[RoleHandlers]] records control-plane audit events on the
  * two key outcomes:
  *   - `ok`: successful mutation with actor, tenant, target and detail populated.
  *   - `denied`: scope-gate rejection records the attempt before returning 403.
  *
  * Uses the same in-memory fixture stack as [[RbacTenantScopeSpec]] (no HTTP, no Postgres).
  */
class HandlerAuditSpec extends AnyFlatSpec with Matchers with OptionValues:

  // A superuser's session, resolved by the audit recorder: an admitted call always carries a user.
  private val RootSessionTok                                          = "root-session"
  private val rootLookup: String => Option[SessionTokenStore.Session] = t =>
    Option.when(t == RootSessionTok)(
      SessionTokenStore.Session(
        AuthenticatedProfile(
          SecurityFixtures.RootUsername,
          "admin",
          Set.empty,
          Map.empty,
          "db",
          None
        ),
        SessionScope.Superuser,
        java.time.Instant.now()
      )
    )

  // ---------------------------------------------------------------------------
  // Stub QuackBackend: no real child processes (mirrors ManagerServerHarness).
  // ---------------------------------------------------------------------------

  private def stubBackend: QuackBackend = StubQuackBackend.noop()

  // ---------------------------------------------------------------------------
  // DuckDB-backed UserStore (mirrors ManagerServerHarness.makeDuckDbUserStore).
  // ---------------------------------------------------------------------------

  private def makeDuckDbUserStore(): UserStore =
    Class.forName("org.duckdb.DuckDBDriver")
    val tmpFile = java.nio.file.Files.createTempFile("qod-audit-test-users", ".duckdb")
    tmpFile.toFile.delete()
    tmpFile.toFile.deleteOnExit()
    val jdbcUrl = InProcessDuckDb.url(tmpFile.toAbsolutePath.toString)
    val c       = DriverManager.getConnection(jdbcUrl)
    try
      c.createStatement()
        .execute(
          """CREATE TABLE IF NOT EXISTS qodstate_user (
          |  id            TEXT PRIMARY KEY,
          |  tenant        TEXT,
          |  username      TEXT NOT NULL,
          |  password_hash TEXT NOT NULL,
          |  kind          TEXT NOT NULL DEFAULT 'user',
          |  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          |  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
          |)""".stripMargin
        )
    finally c.close()
    new UserStore(jdbcUrl, "", "")

  // ---------------------------------------------------------------------------
  // Build a RoleHandlers over the SecurityFixtures in-memory store.
  // Returns (handlers, tenantId).
  // ---------------------------------------------------------------------------

  private def buildRoleHandlers(audit: AuditRecorder): (RoleHandlers, String) =
    val fix     = SecurityFixtures.freshStore()
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(stubBackend, tracker, fix.store)
    sup.restore()
    val userStore    = makeDuckDbUserStore()
    val userHandlers = new UserHandlers(sup, userStore)
    val roles        = new RoleHandlers(sup, userHandlers, audit)
    (roles, SecurityFixtures.TenantId)

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  "createRole" should "audit ok with the acting user, action, tenant, and target on success" in {
    val store        = new RecordingTelemetryStore
    val audit        = new AuditRecorder(store, rootLookup)
    val (roles, tid) = buildRoleHandlers(audit)
    roles
      .createRole(RoleCreateRequest(tid, "auditors", None), Some(RootSessionTok))(_ =>
        SessionScope.Superuser
      )
      .unsafeRunSync()
    store.events should not be empty
    val e = store.events.head
    e.family shouldBe "control-plane"
    e.action shouldBe "role.create"
    e.outcome shouldBe "ok"
    e.tenant shouldBe Some(tid)
    e.actor shouldBe SecurityFixtures.RootUsername
    e.actorRealm shouldBe "system"
    e.target.isDefined shouldBe true
    e.detail.get("name") shouldBe Some("auditors")
  }

  it should "audit denied when the caller's session scope excludes the target tenant" in {
    val store        = new RecordingTelemetryStore
    val audit        = new AuditRecorder(store, _ => None)
    val (roles, tid) = buildRoleHandlers(audit)
    // Non-superuser scope scoped to a DIFFERENT tenant: TenantScopeCheck rejects.
    val foreignScope = SessionScope(superuser = false, manageableTenants = Set("other-tenant"))
    roles
      .createRole(
        RoleCreateRequest(tid, "auditors", None),
        Some("alice-session-token")
      )(_ => foreignScope)
      .unsafeRunSync()
    store.events should not be empty
    val e = store.events.head
    e.action shouldBe "role.create"
    e.outcome shouldBe "denied"
    e.tenant shouldBe Some(tid)
  }

  "TenantHandlers.setTenantAuth" should
    "audit ok with clientKey -> changed when the write sends a client secret, never the value" in {
      val store = new RecordingTelemetryStore
      val audit = new AuditRecorder(store, rootLookup)
      val fix   = SecurityFixtures.freshStore()
      val sup   = new PoolSupervisor(stubBackend, new NodeLoadTracker, fix.store)
      sup.restore()
      val tenants = new TenantHandlers(sup, onAuthChanged = _ => (), audit = audit)
      val req     = SetTenantAuthRequest(
        SecurityFixtures.TenantId,
        "keycloak",
        Map("issuerUrl" -> "https://idp.example.com/realms/acme", "clientId" -> "qod"),
        clientSecret = Some("kc-s3cr3t")
      )
      // Root's superuser session: the scope resolves to a superuser, so the caller is one.
      tenants
        .setTenantAuth(req, Some(RootSessionTok))(_ => SessionScope.Superuser)
        .unsafeRunSync()
        .isRight shouldBe
        true
      val e = store.events.find(_.action == "tenant.auth.update").value
      e.outcome shouldBe "ok"
      e.tenant shouldBe Some(SecurityFixtures.TenantId)
      e.detail shouldBe Map("authProvider" -> "keycloak", "clientKey" -> "changed")
    }

  it should "tell onAuthChanged the tenant after a stored write, and not after a refused one" in {
    val fix = SecurityFixtures.freshStore()
    val sup = new PoolSupervisor(stubBackend, new NodeLoadTracker, fix.store)
    sup.restore()
    val changed = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val tenants = new TenantHandlers(
      sup,
      onAuthChanged = id => { changed.add(id); () },
      audit = AuditRecorder.noop
    )
    val refused = SetTenantAuthRequest(
      SecurityFixtures.TenantId,
      "keycloak",
      Map("issuerUrl" -> "https://idp.example.com/realms/acme", "clientSecretRef" -> "env:X"),
      clientSecret = Some("kc-s3cr3t")
    )
    val tenantAdmin =
      SessionScope(superuser = false, manageableTenants = Set(SecurityFixtures.TenantId))
    tenants.setTenantAuth(refused, Some("alice-session"))(_ => tenantAdmin).unsafeRunSync()
    changed.isEmpty shouldBe true
    val stored = SetTenantAuthRequest(
      SecurityFixtures.TenantId,
      "keycloak",
      Map("issuerUrl" -> "https://idp.example.com/realms/acme", "clientId" -> "qod"),
      clientSecret = None
    )
    tenants
      .setTenantAuth(stored, Some(RootSessionTok))(_ => SessionScope.Superuser)
      .unsafeRunSync()
      .isRight shouldBe true
    changed.toArray.toList shouldBe List(SecurityFixtures.TenantId)
  }

  it should "audit denied with the refusal code when a write rule refuses it, storing nothing" in {
    val store = new RecordingTelemetryStore
    val audit = new AuditRecorder(store, _ => None)
    val fix   = SecurityFixtures.freshStore()
    val sup   = new PoolSupervisor(stubBackend, new NodeLoadTracker, fix.store)
    sup.restore()
    val tenants = new TenantHandlers(sup, onAuthChanged = _ => (), audit = audit)
    val req     = SetTenantAuthRequest(
      SecurityFixtures.TenantId,
      "keycloak",
      Map("issuerUrl" -> "https://idp.example.com/realms/acme", "clientSecretRef" -> "env:X"),
      clientSecret = Some("kc-s3cr3t")
    )
    val tenantAdmin =
      SessionScope(superuser = false, manageableTenants = Set(SecurityFixtures.TenantId))
    val out = tenants.setTenantAuth(req, Some("alice-session"))(_ => tenantAdmin)
    out.unsafeRunSync().left.map(_._1.code) shouldBe Left(403)
    val rows = store.events.filter(_.action == "tenant.auth.update")
    rows.map(_.outcome) shouldBe List("denied")
    rows.head.tenant shouldBe Some(SecurityFixtures.TenantId)
    // The refusal code, never the request: no secret value, no reference in the row.
    rows.head.detail shouldBe Map("reason" -> "superuser_required")
    fix.store.listTenants().find(_.id == SecurityFixtures.TenantId).map(_.authProvider) shouldBe
      Some("db")
  }

  private val AcmeIssuer = "https://idp.example.com/realms/acme"
  private val AcmeConfig = Map("issuerUrl" -> AcmeIssuer, "clientId" -> "qod")

  /** The outcome and detail of the one `tenant.auth.update` row that a write by root's superuser
    * session leaves on a keycloak tenant `acme` holding `stored` as its client secret.
    */
  private def auditedWrite(
      stored: Option[String],
      config: Map[String, String],
      clientSecret: Option[String]
  ): (String, Map[String, String]) =
    val store = new RecordingTelemetryStore
    val fix   = SecurityFixtures.freshStore()
    fix.store.upsertTenant(
      Tenant(
        SecurityFixtures.TenantId,
        displayName = SecurityFixtures.TenantName,
        authProvider = "keycloak",
        authConfig = AcmeConfig,
        authClientSecret = stored
      )
    )
    val sup = new PoolSupervisor(stubBackend, new NodeLoadTracker, fix.store)
    sup.restore()
    val tenants = new TenantHandlers(
      sup,
      onAuthChanged = _ => (),
      audit = new AuditRecorder(store, rootLookup)
    )
    val req = SetTenantAuthRequest(SecurityFixtures.TenantId, "keycloak", config, clientSecret)
    tenants
      .setTenantAuth(req, Some(RootSessionTok))(_ => SessionScope.Superuser)
      .unsafeRunSync()
      .isRight shouldBe
      true
    val rows = store.events.filter(_.action == "tenant.auth.update")
    rows.size shouldBe 1
    (rows.head.outcome, rows.head.detail)

  it should "audit ok without clientKey when the write leaves the stored secret as it was" in
    Seq(
      ("omits it under the same issuer", Some("kc-s3cr3t"), AcmeConfig + ("scopes" -> "x"), None),
      ("sends the stored value again", Some("kc-s3cr3t"), AcmeConfig, Some("kc-s3cr3t")),
      ("clears a secret that is not stored", None, AcmeConfig, Some(""))
    ).foreach { (what, stored, config, sent) =>
      withClue(what) {
        auditedWrite(stored, config, sent) shouldBe ("ok", Map("authProvider" -> "keycloak"))
      }
    }

  it should "audit ok with clientKey -> changed whenever a write changes the stored secret" in {
    val moved = AcmeConfig.updated("issuerUrl", "https://other.example.com")
    Seq(
      ("an issuer change drops it", Some("kc-s3cr3t"), moved, None),
      ("an empty value removes it", Some("kc-s3cr3t"), AcmeConfig, Some("")),
      ("a different value replaces it", Some("kc-s3cr3t"), AcmeConfig, Some("n3w-s3cr3t")),
      (
        "a new reference replaces it",
        Some("kc-s3cr3t"),
        AcmeConfig + ("clientSecretRef" -> "env:ACME_OIDC_SECRET"),
        None
      )
    ).foreach { (what, stored, config, sent) =>
      withClue(what) {
        auditedWrite(stored, config, sent) shouldBe
          ("ok", Map("authProvider" -> "keycloak", "clientKey" -> "changed"))
      }
    }
  }

  // ---------------------------------------------------------------------------
  // In-memory FederatedSourceOps stub for federation audit tests.
  // ---------------------------------------------------------------------------

  private class InMemoryFederatedSourceOps extends FederatedSourceOps:
    private val sources = scala.collection.mutable.Map.empty[String, FederatedSource]
    private val secrets = scala.collection.mutable.Map.empty[String, FederatedSecret]

    def upsertSource(s: FederatedSource): Unit                                = sources(s.id) = s
    def deleteSource(id: String): Unit                                        = sources -= id
    def getSource(tenantDbId: String, alias: String): Option[FederatedSource] =
      sources.values.find(s => s.tenantDbId == tenantDbId && s.alias == alias)
    def listSources(tenantDbId: String): List[FederatedSource] =
      sources.values.filter(_.tenantDbId == tenantDbId).toList.sortBy(_.alias)
    def upsertSecret(s: FederatedSecret): Unit             = secrets(s.id) = s
    def deleteSecret(sourceId: String, name: String): Unit =
      secrets.filterInPlace((_, s) => !(s.federatedSourceId == sourceId && s.name == name))
    def getSecret(sourceId: String, name: String): Option[FederatedSecret] =
      secrets.values.find(s => s.federatedSourceId == sourceId && s.name == name)
    def listSecrets(sourceId: String): List[FederatedSecret] =
      secrets.values.filter(_.federatedSourceId == sourceId).toList.sortBy(_.name)
    def tenantDbIdsWithSources(): Set[String] =
      sources.values.filterNot(_.disabled).map(_.tenantDbId).toSet

  // ---------------------------------------------------------------------------
  // Build FederatedSourceHandlers over the in-memory store.
  // ---------------------------------------------------------------------------

  private def buildFederatedHandlers(audit: AuditRecorder): FederatedSourceHandlers =
    val memStore = new InMemoryFederatedSourceOps
    val tid      = SecurityFixtures.TenantId
    val tdId     = SecurityFixtures.TenantDbId
    val resolver = (tn: String, tdn: String) =>
      if tn == SecurityFixtures.TenantName && tdn == SecurityFixtures.TenantDbName then Some(tdId)
      else None
    val tenantIdResolver = (tn: String) =>
      if tn == SecurityFixtures.TenantName then Some(tid) else None
    new FederatedSourceHandlers(
      memStore,
      resolver,
      tenantIdResolver,
      audit,
      scopeOf = _ => SessionScope.Superuser,
      catalogAliasOf = _ => None
    )

  // ---------------------------------------------------------------------------
  // Federation audit tests
  // ---------------------------------------------------------------------------

  "FederatedSourceHandlers.createSource" should
    "audit ok with the acting user and the tenant id on success" in {
      val store = new RecordingTelemetryStore
      val audit = new AuditRecorder(store, rootLookup)
      val h     = buildFederatedHandlers(audit)
      h.createSource(
        SecurityFixtures.TenantName,
        SecurityFixtures.TenantDbName,
        FederatedSourceCreateRequest(alias = "ext_s3", setupSql = Some("ATTACH ..."), None, false),
        Some(RootSessionTok)
      ).unsafeRunSync()
      store.events should not be empty
      val e = store.events.head
      e.family shouldBe "control-plane"
      e.action shouldBe "federation.source.upsert"
      e.outcome shouldBe "ok"
      e.actor shouldBe SecurityFixtures.RootUsername
      e.tenant shouldBe Some(SecurityFixtures.TenantId)
      e.target shouldBe Some("ext_s3")
    }

  it should "audit the STORED alias, not the raw request alias" in {
    // The row is written under the alias `toSource` resolved (normalized here, or a grandfathered
    // legacy spelling), so auditing `req.alias` would leave the trail naming something that is
    // not in the table. `Ext_S3` and `ext_s3` are one row to DuckDB and to the store.
    val store = new RecordingTelemetryStore
    val audit = new AuditRecorder(store, rootLookup)
    val h     = buildFederatedHandlers(audit)
    val out   = h
      .createSource(
        SecurityFixtures.TenantName,
        SecurityFixtures.TenantDbName,
        FederatedSourceCreateRequest(alias = "Ext_S3", setupSql = Some("ATTACH ..."), None, false),
        Some(RootSessionTok)
      )
      .unsafeRunSync()
      .toOption
      .value
    // Pinned against the handler's OWN response rather than a hand-copied string, so the two
    // cannot drift: whatever alias the row got is what the audit line must name.
    out.alias shouldBe "ext_s3"
    store.events.head.target shouldBe Some(out.alias)
  }

  // ---------------------------------------------------------------------------
  // AuthHandlers fixture
  // ---------------------------------------------------------------------------

  private def buildAuthHandlers(audit: AuditRecorder): (AuthHandlers, SessionTokenStore) =
    val fix      = SecurityFixtures.freshStore()
    val sessions = new SessionTokenStore()
    val authSvc  = new InMemoryAuthService.Service(fix.store, providersEnabled = true)
    val handlers = new AuthHandlers(
      authService = authSvc,
      tokens = sessions,
      identitySource = ai.starlake.quack.ondemand.auth.ManagementIdentitySource.Db,
      grantsForIdentity = (_, _, _) => Nil,
      audit = audit,
      patSessionOf = _ => None
    )
    (handlers, sessions)

  // ---------------------------------------------------------------------------
  // Auth audit tests
  // ---------------------------------------------------------------------------

  "AuthHandlers.login" should
    "audit ok with superuser realm=system and authMethod in detail, no password key" in {
      val store         = new RecordingTelemetryStore
      val audit         = new AuditRecorder(store, _ => None)
      val (handlers, _) = buildAuthHandlers(audit)
      handlers
        .login(LoginRequest(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword))
        .unsafeRunSync()
      store.events should not be empty
      val e = store.events.head
      e.family shouldBe "auth"
      e.action shouldBe "auth.login"
      e.outcome shouldBe "ok"
      e.actor shouldBe SecurityFixtures.RootUsername
      e.actorRealm shouldBe "system"
      e.detail.get("authMethod") shouldBe defined
      e.detail.keys.exists(k => k.toLowerCase.contains("password")) shouldBe false
    }

  it should "audit a failed login (wrong password) as denied, detail username only, no password key" in {
    val store         = new RecordingTelemetryStore
    val audit         = new AuditRecorder(store, _ => None)
    val (handlers, _) = buildAuthHandlers(audit)
    handlers
      .login(LoginRequest(SecurityFixtures.BobUsername, "not-the-right-password"))
      .unsafeRunSync()
    store.events should not be empty
    val e = store.events.head
    e.family shouldBe "auth"
    e.action shouldBe "auth.login.failure"
    e.outcome shouldBe "denied"
    e.detail.get("username") shouldBe Some(SecurityFixtures.BobUsername)
    e.detail.keys.exists(k => k.toLowerCase.contains("password")) shouldBe false
  }

  "apiKeyGuard" should
    "emit at most one auth.api-key.failure per source per minute on a bad key" in {
      val fix     = SecurityFixtures.freshStore()
      val store   = new RecordingTelemetryStore
      val audit   = new AuditRecorder(store, _ => None)
      val limiter = new AuditRateLimiter()
      val harness = ManagerServerHarness.boot(
        fix.store,
        audit = audit,
        auditLimiter = limiter
      )
      try
        def badRequest(): Unit =
          val req = HttpRequest
            .newBuilder(URI.create(s"${harness.baseUrl}/api/tenant/list"))
            .header("X-API-Key", "wrong-key")
            .GET()
            .build()
          harness.httpClient.send(req, HttpResponse.BodyHandlers.ofString())
          ()
        badRequest()
        badRequest()
        val failures = store.events.filter(_.action == "auth.api-key.failure")
        failures should have size 1
        failures.head.outcome shouldBe "denied"
      finally harness.shutdown()
    }
  // ---------------------------------------------------------------------------
  // Helper: build auth handlers with a real session-lookup closure so the audit
  // recorder can resolve JWTs to actor names before the token is revoked.
  // ---------------------------------------------------------------------------

  private def buildAuthHandlersWithSessionLookup(
      audit: AuditRecorder
  ): (AuthHandlers, SessionTokenStore) =
    val fix      = SecurityFixtures.freshStore()
    val sessions = new SessionTokenStore()
    val authSvc  = new InMemoryAuthService.Service(fix.store, providersEnabled = true)
    val handlers = new AuthHandlers(
      authService = authSvc,
      tokens = sessions,
      identitySource = ai.starlake.quack.ondemand.auth.ManagementIdentitySource.Db,
      grantsForIdentity = (_, _, _) => Nil,
      audit = audit,
      patSessionOf = _ => None
    )
    (handlers, sessions)

  "AuthHandlers.logout" should "record actor from the session token before revoking" in {
    val store    = new RecordingTelemetryStore
    val sessions = new SessionTokenStore()
    // Wire session lookup so actorOf resolves the JWT to a username.
    val audit    = new AuditRecorder(store, sessions.get)
    val fix      = SecurityFixtures.freshStore()
    val authSvc  = new InMemoryAuthService.Service(fix.store, providersEnabled = true)
    val handlers = new AuthHandlers(
      authService = authSvc,
      tokens = sessions,
      identitySource = ai.starlake.quack.ondemand.auth.ManagementIdentitySource.Db,
      grantsForIdentity = (_, _, _) => Nil,
      audit = audit,
      patSessionOf = _ => None
    )
    val loginResult = handlers
      .login(LoginRequest(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword))
      .unsafeRunSync()
    val tok = loginResult match
      case Right(r) => r._2.token
      case Left(_)  => fail("login failed")
    store.events.clear()
    // Logout via cookie path - actor must be captured BEFORE the token is revoked.
    handlers.logout(apiKey = None, cookie = Some(tok)).unsafeRunSync()
    val logoutEvents = store.events.filter(_.action == "auth.logout")
    logoutEvents should have size 1
    val e = logoutEvents.head
    e.actor shouldBe SecurityFixtures.RootUsername
    e.outcome shouldBe "ok"
  }

  "AuthHandlers.oidcLogout" should "record actor from the session token before revoking" in {
    val store    = new RecordingTelemetryStore
    val sessions = new SessionTokenStore()
    val audit    = new AuditRecorder(store, sessions.get)
    val fix      = SecurityFixtures.freshStore()
    val authSvc  = new InMemoryAuthService.Service(fix.store, providersEnabled = true)
    val handlers = new AuthHandlers(
      authService = authSvc,
      tokens = sessions,
      identitySource = ai.starlake.quack.ondemand.auth.ManagementIdentitySource.Db,
      grantsForIdentity = (_, _, _) => Nil,
      audit = audit,
      patSessionOf = _ => None
    )
    val loginResult = handlers
      .login(LoginRequest(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword))
      .unsafeRunSync()
    val tok = loginResult match
      case Right(r) => r._2.token
      case Left(_)  => fail("login failed")
    store.events.clear()
    // oidcLogout revokes the cookie; actor must be captured BEFORE revocation.
    handlers.oidcLogout(sessionCookie = Some(tok), forwardedProto = None).unsafeRunSync()
    val revokeEvents = store.events.filter(_.action == "auth.revoke")
    revokeEvents should have size 1
    val e = revokeEvents.head
    e.actor shouldBe SecurityFixtures.RootUsername
    e.outcome shouldBe "ok"
  }
