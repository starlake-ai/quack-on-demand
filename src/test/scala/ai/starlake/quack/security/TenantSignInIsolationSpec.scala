// src/test/scala/ai/starlake/quack/security/TenantSignInIsolationSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.ManagementOidcConfig
import ai.starlake.quack.boot.RoutedExecutor
import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.edge.auth.{
  AuthenticatedProfile,
  OidcBearerAuthenticator,
  OidcDiscovery,
  OidcEndpointResolver,
  OidcEndpoints,
  OidcSsoService,
  OidcStateCodec
}
import ai.starlake.quack.model.{
  FederatedSecret,
  Pool,
  PoolKey,
  RoleDistribution,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{ExecCaller, SessionTokenStore}
import ai.starlake.quack.ondemand.auth.{PatAuthenticator, TokenRestriction}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, RbacUser, UserGrant, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditEvent, AuditRecorder}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.route.StatementClassifier
import ai.starlake.quack.secrets.SecretRefResolver
import at.favre.lib.crypto.bcrypt.BCrypt
import cats.effect.IO
import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.classic.spi.{ILoggingEvent, ThrowableProxyUtil}
import ch.qos.logback.core.read.ListAppender
import io.circe.{ACursor, HCursor, Json}
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.net.{URI, URLDecoder, URLEncoder}
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Tenant sign-in isolation, pinned over HTTP on the real manager ([[ManagerServerHarness]]):
  *
  *   1. a tenant admin saves the tenant's OIDC client secret, and no response, manifest export,
  *      audit row or log line carries it (nor can it arrive inside `authConfig`);
  *   2. a tenant admin cannot add or change an `env:` reference (`403 superuser_required`, row
  *      unchanged); a superuser can;
  *   3. an issuer change drops the stored secret, value or reference, unless the same write
  *      supplies one;
  *   4. a tenant SSO login binds only that tenant's `qodstate_user` rows, and so does a personal
  *      access token minted from its session;
  *   5. a password login naming an OIDC tenant answers `400 sso_required`;
  *   6. a REST or MCP statement of a tenant caller never binds a superuser row; a superuser caller
  *      still binds its own.
  *
  * Wired as Main wires it, all from the harness's own supervisor: the admin-console SSO service
  * with only its network legs stubbed ([[StubIdp]]) reading tenants through the supervisor, Main's
  * resolution of a typed tenant at login, the SSO grants read from a real [[UserStore]] directory,
  * and statements through the real [[RoutedExecutor]] with a recording router leg. Password logins
  * and the statement gate read the in-memory control plane, which holds the same rows as the
  * directory for every name the SSO cases use (one `qodstate_user` table in production). The cases
  * that need personal access tokens use the local test Postgres and cancel without it.
  *
  * Failure clues name a response by its status and error code only: a body can hold a session token
  * or a client secret.
  */
class TenantSignInIsolationSpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll
    with SecurityHttpHelpers:

  TestPostgres.dropStrayTestDatabases("qodsignin")

  private val Acme   = SecurityFixtures.TenantId
  private val Globex = SecurityFixtures.GlobexTenantId

  private val IssuerA         = "https://idp-a.example"
  private val IssuerB         = "https://idp-b.example"
  private val ConsoleClientId = "acme-console"
  private val SecretA         = "acme-console-secret-a1b2c3"
  private val SecretB         = "acme-console-secret-d4e5f6"
  // An operator's reference and the value the stub resolver answers for it: the spec never reads
  // the real environment.
  private val OperatorEnvName = "QOD_TEST_ACME_IDP_SECRET"
  private val OperatorRef     = s"env:$OperatorEnvName"
  private val OperatorSecret  = "operator-held-secret-778899"
  // The manager's own session signing key: what a tenant admin must never point a tenant at.
  private val ManagerKeyRef = "env:QOD_SESSION_JWT_SECRET"

  private val CaptureProbe = "log capture window probe"

  private val DuckLakeTenantDbId = "td-signin01"
  private val DuckLakeTenantDb   = "acme_dl"
  private val DuckLakePoolId     = "p-signin01"
  private val DuckLakePoolName   = "dlbi"
  private val PreviewPath        =
    s"/api/catalog/tenant/$Acme/database/$DuckLakeTenantDb/schemas/main/tables/region/preview"

  // (tenant, username, kind): one name per realm scenario, held by the SSO directory and by the
  // control plane alike.
  private val SharedRows: List[(Option[String], String, String)] = List(
    (None, "carol", "admin"),
    (Some(Globex), "carol", "admin"),
    (Some(Acme), "carol", "admin"),
    (None, "erin", "admin"),
    (Some(Globex), "erin", "admin"),
    (None, "dave", "admin"),
    (Some(Acme), "dave", "user"),
    (Some(Acme), "carol@acme.example", "admin")
  )
  // The SSO directory: the SecurityFixtures root and alice rows (the control plane holds them
  // already), then the shared rows.
  private val DirectoryRows: List[(Option[String], String, String)] =
    (None, SecurityFixtures.RootUsername, "admin") ::
      (Some(Acme), SecurityFixtures.AliceUsername, "admin") :: SharedRows

  private var directory: UserStore      = null
  private val dbName                    = s"qodsignin_test_${System.nanoTime()}"
  private var pgUsers: UserStore        = null
  private var pats: PatStore            = null
  private var patAuth: PatAuthenticator = null

  private def seedDirectory(users: UserStore, rows: List[(Option[String], String, String)]): Unit =
    rows.foreach((tenant, username, kind) =>
      users.upsertUser(tenant, username, "directory-password", kind)
    )

  override def beforeAll(): Unit =
    // Seeded once: bcrypt is slow and every case only reads the directory.
    directory = ManagerServerHarness.makeDuckDbUserStore()
    seedDirectory(directory, DirectoryRows)
    if TestPostgres.reachable then
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      pgUsers = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      patAuth = new PatAuthenticator(pats, pgUsers.userById, u => List(UserGrant(u.tenant, u.kind)))
      // The same directory for the PAT cases, plus the MCP case's PAT owner: an acme row that is
      // also named root.
      seedDirectory(
        pgUsers,
        DirectoryRows :+ ((Some(Acme), SecurityFixtures.RootUsername, "admin"))
      )

  override def afterAll(): Unit =
    if directory != null then directory.close()
    if pats != null then pats.close()
    if pgUsers != null then pgUsers.close()
    Try(TestPostgres.dropDatabase(dbName))
    ()

  // ---------------------------------------------------------------------------------------------
  // Stubs and fixtures
  // ---------------------------------------------------------------------------------------------

  /** The tenants' identity providers, with no network: OIDC discovery for [[IssuerA]] and
    * [[IssuerB]], a token endpoint that records every exchange (its URL and form, which carries the
    * client secret), and an id_token validator answering the identity staged for the next sign-in,
    * with the nonce the manager put in the authorize redirect.
    */
  private final class StubIdp:
    private val recorded = new ConcurrentLinkedQueue[(String, String)]()

    @volatile private var staged: Option[AuthenticatedProfile] = None

    def exchanges: List[(String, String)] = recorded.asScala.toList

    def stage(username: String, email: String, nonce: String): Unit =
      staged = Some(
        AuthenticatedProfile(
          username = username,
          // The IdP's own role claim: authority comes from qodstate_user rows, never from it.
          role = "admin",
          groups = Set.empty,
          claims = Map("nonce" -> nonce, "email" -> email),
          authMethod = "oidc"
        )
      )

    private def discoveryDoc(url: String): Either[String, String] =
      List(IssuerA, IssuerB).find(i => url == s"$i/.well-known/openid-configuration") match
        case Some(i) =>
          Right(
            Json
              .obj(
                "issuer"                 -> Json.fromString(i),
                "authorization_endpoint" -> Json.fromString(s"$i/authorize"),
                "token_endpoint"         -> Json.fromString(s"$i/token"),
                "jwks_uri"               -> Json.fromString(s"$i/jwks")
              )
              .noSpaces
          )
        case None => Left(s"no stub issuer serves $url")

    val discovery: OidcDiscovery = new OidcDiscovery(httpGet = discoveryDoc)

    def exchange(tokenUrl: String, form: String): Either[String, String] =
      recorded.add(tokenUrl -> form)
      Right("""{"id_token":"stub-id-token"}""")

    def validator(ep: OidcEndpoints): OidcBearerAuthenticator =
      new OidcBearerAuthenticator(ep.provider, ep.jwksUrl, ep.issuer, ep.clientId, "role"):
        override def authenticate(token: String): Either[String, AuthenticatedProfile] =
          staged.toRight("no sign-in staged")

  /** The router leg of the routed executor, recording what reaches it and as which row. It answers
    * `Unavailable`, so a statement that got this far comes back as a failure.
    */
  private final class RecordingRoute:
    private val recorded = new ConcurrentLinkedQueue[(ExecCaller, PoolKey, Option[EffectiveSet])]()

    def calls: List[(ExecCaller, PoolKey, Option[EffectiveSet])] = recorded.asScala.toList

    val run: RoutedExecutor.Run = (caller, key, _, eff, _) =>
      IO {
        recorded.add((caller, key, eff))
        Left(RouterFailure.Unavailable("recorded by TenantSignInIsolationSpec"))
      }

  private final case class World(
      h: ManagerServerHarness.Harness,
      fix: SecurityFixtures.Fixture,
      idp: StubIdp,
      audit: RecordingTelemetryStore,
      route: RecordingRoute
  ):
    def url(path: String): String = s"${h.baseUrl}$path"
    def acme: Tenant              =
      fix.store.listTenants().find(_.id == Acme).getOrElse(fail("no acme tenant row"))
    def aliceToken(): String =
      h.mintToken(SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword, Some(Acme))
    def rootToken(): String =
      h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
    def auditRows: List[AuditEvent] = audit.events.toList

  /** acme's DuckLake tenant-db and pool, so a REST preview clears the handler's gates and reaches
    * the executor (no node needed: the pool pick falls back to the first pool by name).
    */
  private def addDuckLakePool(fix: SecurityFixtures.Fixture): Unit =
    fix.store.upsertTenantDb(
      TenantDb(
        id = DuckLakeTenantDbId,
        tenantId = Acme,
        name = DuckLakeTenantDb,
        kind = TenantDbKind.DuckLake,
        metastore = Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "5432",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> DuckLakeTenantDb,
          "schemaName" -> "main"
        ),
        dataPath = "/tmp/qod-signin-isolation-test"
      )
    )
    fix.store.upsertPool(
      Pool(
        id = DuckLakePoolId,
        tenantId = Acme,
        tenantDbId = DuckLakeTenantDbId,
        name = DuckLakePoolName,
        size = 1,
        distribution = RoleDistribution(writeonly = 0, readonly = 0, dual = 1)
      )
    )

  /** acme signs in through its IdP at [[IssuerA]], with a stored client secret. */
  private def acmeSignsInThroughSso(fix: SecurityFixtures.Fixture): Unit =
    fix.store.listTenants().find(_.id == Acme).foreach { t =>
      fix.store.upsertTenant(
        t.copy(
          authProvider = "keycloak",
          authConfig = oidcConfig(IssuerA),
          authClientSecret = Some(SecretA)
        )
      )
    }

  /** globex (a db tenant) has a password user, gina. */
  private def addGlobexPasswordUser(fix: SecurityFixtures.Fixture): Unit =
    fix.store.upsertUserWithHash(
      Some(Globex),
      "gina",
      BCrypt.withDefaults().hashToString(4, "ginapw".toCharArray),
      "admin"
    )
    ()

  /** The admin-console SSO service as ManagementAuthWiring builds it for Main, its network legs
    * stubbed: tenant scope reads the supervisor's tenants, as Main's loader does
    * (`sup.getTenantById`), so it sees what the REST writes stored; references resolve from a fixed
    * map.
    */
  private def ssoService(sup: PoolSupervisor, idp: StubIdp): OidcSsoService =
    new OidcSsoService(
      resolver = new OidcEndpointResolver(
        loadTenant = id => sup.getTenantById(id),
        secrets = SecretRefResolver.fromEnv(Map(OperatorEnvName -> OperatorSecret).get),
        discovery = idp.discovery
      ),
      // No system-scope IdP: these cases sign in through tenants only.
      mgmt = ManagementOidcConfig(),
      codec = new OidcStateCodec("tenant-signin-isolation-state-secret", 600_000L),
      roleClaim = "role",
      publicBaseUrlOf = () => "http://127.0.0.1",
      httpExchange = idp.exchange,
      buildValidator = idp.validator
    )

  /** One manager over HTTP: the SecurityFixtures control plane plus globex, acme's DuckLake pool
    * and [[SharedRows]], `seed` applied before boot. `withPats` swaps in the Postgres directory and
    * PAT store and mounts /mcp; it cancels the case when the test Postgres is unreachable.
    */
  private def withManager(
      seed: SecurityFixtures.Fixture => Unit = _ => (),
      withPats: Boolean = false
  )(body: World => Unit): Unit =
    if withPats then
      TestPostgres.ensureReachable()
      assume(pats != null, "the test Postgres was unreachable when the spec started")
    val fix = SecurityFixtures.freshStore()
    SecurityFixtures.addTenantB(fix)
    addDuckLakePool(fix)
    SharedRows.zipWithIndex.foreach { case ((tenant, username, kind), i) =>
      fix.store.upsertUserIdentity(
        RbacUser(id = s"u-shared$i", tenant = tenant, username = username, kind = kind)
      )
    }
    seed(fix)
    val idp       = new StubIdp
    val sessions  = new SessionTokenStore
    val telemetry = new RecordingTelemetryStore
    val route     = new RecordingRoute
    // Read only when withPats: pgUsers is null without the test Postgres.
    val pgUserOf: (Option[String], String) => Option[RbacUser] =
      (tenant, username) => pgUsers.userIdOf(tenant, username).flatMap(pgUsers.userById)
    val h = ManagerServerHarness.boot(
      fix.store,
      audit = new AuditRecorder(telemetry, sessions.get),
      sessions = sessions,
      patStore = Option.when(withPats)(pats),
      patUserOf = Option.when(withPats)(pgUserOf),
      patAuth = Option.when(withPats)(patAuth),
      mcpEnabled = withPats,
      users = Some(if withPats then pgUsers else directory),
      oidcOf = Some(sup => ssoService(sup, idp)),
      // Main.scala wires AuthHandlers.resolveTenant exactly so: the typed text as an id, else its
      // lowercase form.
      resolveTenantOf =
        Some(sup => raw => sup.getTenantById(raw).orElse(sup.getTenant(raw)).map(_.id)),
      executorOf = Some(sup =>
        RoutedExecutor(sup, StatementClassifier.default, route.run)(recordExecution = false)
      )
    )
    try body(World(h, fix, idp, telemetry, route))
    finally h.shutdown()

  // ---------------------------------------------------------------------------------------------
  // HTTP helpers
  // ---------------------------------------------------------------------------------------------

  private def oidcConfig(issuer: String): Map[String, String] =
    Map("issuerUrl" -> issuer, "clientId" -> ConsoleClientId)

  private def configJson(config: Map[String, String]): Json =
    Json.obj(config.toList.map((k, v) => k -> Json.fromString(v))*)

  private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  /** What a failure clue may say about a response: its status and error code, never its body, which
    * can hold a session token or a client secret.
    */
  private def statusAndCode(resp: HttpResponse[String]): String =
    s"${resp.statusCode()} ${errorCode(resp.body()).getOrElse("-")}"

  private def json(resp: HttpResponse[String]): HCursor =
    parse(resp.body()).fold(_ => fail(s"not JSON (${resp.statusCode()})"), _.hcursor)

  /** `POST /api/tenant/setAuth` for acme as `token`; `clientSecret = None` omits the field. */
  private def setAuth(
      w: World,
      token: String,
      authConfig: Map[String, String],
      clientSecret: Option[String] = None
  ): HttpResponse[String] =
    val fields = List(
      "name"         -> Json.fromString(Acme),
      "authProvider" -> Json.fromString("keycloak"),
      "authConfig"   -> configJson(authConfig)
    ) ++ clientSecret.map(s => "clientSecret" -> Json.fromString(s))
    post(w.h.httpClient, w.url("/api/tenant/setAuth"), Json.obj(fields*).noSpaces, Some(token))

  private def loginBody(username: String, password: String, tenant: Option[String]): String =
    Json
      .obj(
        (List(
          "username" -> Json.fromString(username),
          "password" -> Json.fromString(password)
        ) ++ tenant.map(t => "tenant" -> Json.fromString(t)))*
      )
      .noSpaces

  private def whoami(w: World, session: String): HCursor =
    json(get(w.h.httpClient, w.url("/api/auth/whoami"), Some(session)))

  private def cookieOf(resp: HttpResponse[String], name: String): Option[String] =
    resp.headers().allValues("set-cookie").asScala.collectFirst {
      case c if c.startsWith(s"$name=") => c.drop(name.length + 1).takeWhile(_ != ';')
    }

  private def queryParam(url: String, key: String): Option[String] =
    Option(URI.create(url).getRawQuery).toList
      .flatMap(_.split("&").toList)
      .collectFirst {
        case kv if kv.startsWith(s"$key=") =>
          URLDecoder.decode(kv.drop(key.length + 1), StandardCharsets.UTF_8)
      }

  private final case class SsoOutcome(authorize: String, location: String, session: Option[String])

  /** One admin-console SSO sign-in into `tenant` over HTTP: the start redirect to the tenant's IdP
    * (with the signed state cookie), the IdP answering `username` / `email`, then the callback.
    */
  private def ssoSignIn(w: World, tenant: String, username: String, email: String): SsoOutcome =
    val start = get(w.h.httpClient, w.url(s"/api/auth/oidc/start?tenant=$tenant&returnTo=/ui/"))
    withClue(s"oidc start: ${statusAndCode(start)}")(start.statusCode() shouldBe 302)
    val authorize   = start.headers().firstValue("location").orElse("")
    val stateCookie = cookieOf(start, OidcSsoService.STATE_COOKIE)
      .getOrElse(fail("the start redirect set no state cookie"))
    w.idp.stage(username, email, queryParam(authorize, "nonce").getOrElse(fail("no nonce")))
    val state    = stateCookie.split("::", 2).head
    val callback = w.h.httpClient.send(
      HttpRequest
        .newBuilder(
          URI.create(w.url(s"/api/auth/oidc/callback?code=stub-code&state=${enc(state)}"))
        )
        .header("Cookie", s"${OidcSsoService.STATE_COOKIE}=$stateCookie")
        .timeout(RequestTimeout)
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    withClue(s"oidc callback: ${statusAndCode(callback)}")(callback.statusCode() shouldBe 302)
    SsoOutcome(
      authorize,
      callback.headers().firstValue("location").orElse(""),
      cookieOf(callback, SessionTokenStore.CookieName).filter(_.nonEmpty)
    )

  /** MCP `run_sql` against acme's DuckLake database as `bearer`; the JSON-RPC `result`. */
  private def runSql(w: World, bearer: String, tenant: Option[String]): ACursor =
    val arguments = Json.obj(
      (List(
        "sql"      -> Json.fromString("SELECT 1"),
        "database" -> Json.fromString(DuckLakeTenantDb)
      ) ++ tenant.map(t => "tenant" -> Json.fromString(t)))*
    )
    val rpc = Json.obj(
      "jsonrpc" -> Json.fromString("2.0"),
      "id"      -> Json.fromInt(1),
      "method"  -> Json.fromString("tools/call"),
      "params"  -> Json.obj("name" -> Json.fromString("run_sql"), "arguments" -> arguments)
    )
    val resp = w.h.httpClient.send(
      HttpRequest
        .newBuilder(URI.create(w.url("/mcp")))
        .header("Content-Type", "application/json")
        .header("Authorization", s"Bearer $bearer")
        .timeout(RequestTimeout)
        .POST(HttpRequest.BodyPublishers.ofString(rpc.noSpaces, StandardCharsets.UTF_8))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    withClue(s"mcp run_sql: ${statusAndCode(resp)}")(resp.statusCode() shouldBe 200)
    json(resp).downField("result")

  private def mintPat(tenant: Option[String], username: String): String =
    val uid = pgUsers.userIdOf(tenant, username).getOrElse(fail(s"no Postgres row for $username"))
    pats.mint(uid, s"signin-$username", TokenRestriction.Unrestricted, None, 0)._2

  /** Every log event emitted while `body` runs, from every logger at every level, as
    * `(logger name, message and any stack trace)`. The root logger is opened to ALL and its console
    * appender set aside for the window, then both are restored; forked specs run one at a time in
    * their JVM, so the window sees this spec's work. Callers assert on these without printing them.
    */
  private def capturingLogs[A](body: => A): (A, List[(String, String)]) =
    val root =
      LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    val capture = new ListAppender[ILoggingEvent]()
    capture.setContext(root.getLoggerContext)
    capture.start()
    val console = Option(root.getAppender("STDOUT"))
    val level   = root.getLevel
    console.foreach(a => root.detachAppender(a))
    root.addAppender(capture)
    root.setLevel(Level.ALL)
    try
      // A probe at the lowest level: it proves the window records everything, so an empty capture
      // cannot make a "no log line carries it" assertion pass for the wrong reason.
      LoggerFactory.getLogger("TenantSignInIsolationSpec").trace(CaptureProbe)
      val result = body
      val events = capture.synchronized(capture.list.asScala.toList)
      val lines  = events.map { e =>
        val trace =
          Option(e.getThrowableProxy).map(p => ThrowableProxyUtil.asString(p)).getOrElse("")
        e.getLoggerName -> (e.getFormattedMessage + trace)
      }
      assert(lines.exists(_._2 == CaptureProbe), "the log capture window recorded no probe event")
      (result, lines)
    finally
      root.setLevel(level)
      root.detachAppender(capture)
      console.foreach(a => root.addAppender(a))
      capture.stop()

  /** Fails when `secret` appears in the text of a named response or of a captured log event (see
    * [[capturingLogs]]). The failure names only the responses and the loggers, never their text.
    */
  private def assertNeverShown(
      secret: String,
      responses: List[(String, String)],
      logEvents: List[(String, String)]
  ): Unit =
    responses.collect { case (name, text) if text.contains(secret) => name } shouldBe empty
    val leaking = logEvents.collect { case (logger, text) if text.contains(secret) => logger }
    withClue(s"loggers whose events carry the secret: ${leaking.distinct.mkString(", ")}") {
      leaking.size shouldBe 0
    }

  // ---------------------------------------------------------------------------------------------
  // 1. The client secret never leaves through a response, the manifest, the audit trail or the log
  // ---------------------------------------------------------------------------------------------

  "A tenant's client secret" should
    "stay out of the responses that follow its save (setAuth, tenant list, auth mode, manifest export, the SSO redirects), the audit trail and the log" in
    withManager() { w =>
      val alice                  = w.aliceToken()
      val root                   = w.rootToken()
      val (responses, logEvents) = capturingLogs {
        val saved = setAuth(w, alice, oidcConfig(IssuerA), clientSecret = Some(SecretA))
        withClue(s"setAuth: ${statusAndCode(saved)}") {
          saved.statusCode() shouldBe 200
          json(saved).get[Boolean]("clientSecretSet") shouldBe Right(true)
        }
        val listed = get(w.h.httpClient, w.url("/api/tenant/list"), Some(alice))
        listed.statusCode() shouldBe 200
        val exported = get(w.h.httpClient, w.url("/api/manifest/export"), Some(root))
        exported.statusCode() shouldBe 200
        // The export says a secret is set, the redaction marker in its place.
        val marked = exported
          .body()
          .linesIterator
          .exists(l => l.contains("clientSecret") && l.contains(FederatedSecret.RedactedMarker))
        marked shouldBe true
        // The public mode endpoint answers anyone, for the tenant and for the system scope.
        val tenantMode = get(w.h.httpClient, w.url(s"/api/auth/mode?tenant=$Acme"))
        val systemMode = get(w.h.httpClient, w.url("/api/auth/mode"))
        tenantMode.statusCode() shouldBe 200
        systemMode.statusCode() shouldBe 200
        val signIn = ssoSignIn(w, Acme, SecurityFixtures.AliceUsername, "alice@acme.example")
        signIn.session should not be empty
        List(
          "tenant/setAuth"     -> saved.body(),
          "tenant/list"        -> listed.body(),
          "manifest/export"    -> exported.body(),
          "auth/mode (tenant)" -> tenantMode.body(),
          "auth/mode (system)" -> systemMode.body(),
          "authorize redirect" -> signIn.authorize,
          "callback redirect"  -> signIn.location
        )
      }
      assertNeverShown(SecretA, responses, logEvents)
      w.auditRows.filter(_.toString.contains(SecretA)).map(_.action) shouldBe empty
      // The one successful write is audited, and its row says only that the stored secret changed.
      val updates = w.auditRows.filter(e =>
        e.action == AuditActions.TenantAuthUpdate && e.outcome == "ok" && e.tenant.contains(Acme)
      )
      updates.map(_.detail.get("clientKey")) shouldBe List(Some("changed"))
      // Stored as given, redacted when the row is printed, and sent where it is for.
      w.acme.authClientSecret shouldBe Some(SecretA)
      w.acme.toString should not include SecretA
      w.idp.exchanges.map(_._1) shouldBe List(s"$IssuerA/token")
      w.idp.exchanges.exists(_._2.contains(s"client_secret=${enc(SecretA)}")) shouldBe true
    }

  it should "be refused inside authConfig, on tenant/setAuth and on tenant/create, so no response can echo it" in
    withManager() { w =>
      val alice      = w.aliceToken()
      val root       = w.rootToken()
      val before     = w.acme
      val viaSetAuth = setAuth(w, alice, oidcConfig(IssuerA) + ("clientSecret" -> SecretA))
      withClue(s"setAuth: ${statusAndCode(viaSetAuth)}") {
        viaSetAuth.statusCode() shouldBe 400
        errorCode(viaSetAuth.body()) shouldBe Some("invalid_auth_config")
      }
      w.acme shouldBe before
      val create = Json.obj(
        "id"           -> Json.fromString("initech"),
        "displayName"  -> Json.fromString("Initech"),
        "authProvider" -> Json.fromString("keycloak"),
        "authConfig"   -> configJson(oidcConfig(IssuerA) + ("clientSecret" -> SecretA))
      )
      val viaCreate =
        post(w.h.httpClient, w.url("/api/tenant/create"), create.noSpaces, Some(root))
      withClue(s"tenant/create: ${statusAndCode(viaCreate)}") {
        viaCreate.statusCode() shouldBe 400
        errorCode(viaCreate.body()) shouldBe Some("invalid_auth_config")
      }
      w.fix.store.listTenants().map(_.id) should not contain "initech"
      List(viaSetAuth.body(), viaCreate.body()).exists(_.contains(SecretA)) shouldBe false
    }

  it should "be audited as changed only when the stored value changes: not for the same value sent again, never as the value" in
    withManager() { w =>
      val alice = w.aliceToken()
      // Set, the same value again, replaced, removed, removed again (nothing left to clear).
      List(Some(SecretA), Some(SecretA), Some(SecretB), Some(""), Some("")).foreach { secret =>
        val resp = setAuth(w, alice, oidcConfig(IssuerA), clientSecret = secret)
        withClue(s"setAuth: ${statusAndCode(resp)}")(resp.statusCode() shouldBe 200)
      }
      w.auditRows
        .filter(e => e.action == AuditActions.TenantAuthUpdate && e.outcome == "ok")
        .map(_.detail.get("clientKey")) shouldBe
        List(Some("changed"), None, Some("changed"), Some("changed"), None)
      w.auditRows
        .filter(e => List(SecretA, SecretB).exists(e.toString.contains))
        .map(_.action) shouldBe empty
      w.acme.authClientSecret shouldBe None
    }

  // ---------------------------------------------------------------------------------------------
  // 2. env: references are written by superusers only
  // ---------------------------------------------------------------------------------------------

  "An env: secret reference" should
    "be refused with 403 superuser_required when a tenant admin adds one, leaving the row unchanged" in
    withManager() { w =>
      val alice  = w.aliceToken()
      val before = w.acme
      val resp   = setAuth(w, alice, oidcConfig(IssuerA) + ("clientSecretRef" -> ManagerKeyRef))
      withClue(s"setAuth: ${statusAndCode(resp)}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("superuser_required")
      }
      w.acme shouldBe before
      val refusalAudited = w.auditRows.exists(e =>
        e.action == AuditActions.TenantAuthUpdate && e.outcome == "denied" &&
          e.detail.get("reason").contains("superuser_required")
      )
      refusalAudited shouldBe true
    }

  it should "be refused with 403 superuser_required when a tenant admin changes a superuser's reference" in
    withManager() { w =>
      val alice = w.aliceToken()
      val root  = w.rootToken()
      setAuth(w, root, oidcConfig(IssuerA) + ("clientSecretRef" -> OperatorRef))
        .statusCode() shouldBe 200
      val before = w.acme
      val resp   = setAuth(w, alice, oidcConfig(IssuerA) + ("clientSecretRef" -> ManagerKeyRef))
      withClue(s"setAuth: ${statusAndCode(resp)}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("superuser_required")
      }
      w.acme shouldBe before
      w.acme.authConfig.get("clientSecretRef") shouldBe Some(OperatorRef)
    }

  it should "be saved when a superuser writes it, and resolve only into the token request to its issuer" in
    withManager() { w =>
      val root                   = w.rootToken()
      val (responses, logEvents) = capturingLogs {
        val resp = setAuth(w, root, oidcConfig(IssuerA) + ("clientSecretRef" -> OperatorRef))
        withClue(s"setAuth: ${statusAndCode(resp)}") {
          resp.statusCode() shouldBe 200
          json(resp).get[Boolean]("clientSecretSet") shouldBe Right(false)
          json(resp).downField("authConfig").get[String]("clientSecretRef") shouldBe Right(
            OperatorRef
          )
        }
        val signIn = ssoSignIn(w, Acme, SecurityFixtures.AliceUsername, "alice@acme.example")
        signIn.session should not be empty
        List(
          "tenant/setAuth"     -> resp.body(),
          "authorize redirect" -> signIn.authorize,
          "callback redirect"  -> signIn.location
        )
      }
      w.acme.authConfig.get("clientSecretRef") shouldBe Some(OperatorRef)
      w.acme.authClientSecret shouldBe None
      w.idp.exchanges.map(_._1) shouldBe List(s"$IssuerA/token")
      w.idp.exchanges.exists(_._2.contains(s"client_secret=${enc(OperatorSecret)}")) shouldBe true
      // The resolved value goes nowhere else: not a response, not a redirect, not the log.
      assertNeverShown(OperatorSecret, responses, logEvents)
    }

  it should "stay when a tenant admin's write carries it unchanged under the same issuer" in
    withManager() { w =>
      val alice = w.aliceToken()
      val root  = w.rootToken()
      setAuth(w, root, oidcConfig(IssuerA) + ("clientSecretRef" -> OperatorRef))
        .statusCode() shouldBe 200
      val resp = setAuth(
        w,
        alice,
        oidcConfig(IssuerA) + ("clientSecretRef" -> OperatorRef) + ("scopes" -> "openid email")
      )
      withClue(s"setAuth: ${statusAndCode(resp)}")(resp.statusCode() shouldBe 200)
      w.acme.authConfig.get("clientSecretRef") shouldBe Some(OperatorRef)
      w.acme.authConfig.get("scopes") shouldBe Some("openid email")
    }

  // ---------------------------------------------------------------------------------------------
  // 3. A stored secret never follows an issuer change
  // ---------------------------------------------------------------------------------------------

  "An issuer change" should
    "drop the stored client secret when the write supplies none, while a same-issuer write keeps it" in
    withManager() { w =>
      val alice = w.aliceToken()
      setAuth(w, alice, oidcConfig(IssuerA), clientSecret = Some(SecretA)).statusCode() shouldBe 200
      val sameIssuer = setAuth(w, alice, oidcConfig(IssuerA) + ("scopes" -> "openid email"))
      json(sameIssuer).get[Boolean]("clientSecretSet") shouldBe Right(true)
      w.acme.authClientSecret shouldBe Some(SecretA)
      val moved = setAuth(w, alice, oidcConfig(IssuerB))
      withClue(s"setAuth: ${statusAndCode(moved)}") {
        moved.statusCode() shouldBe 200
        json(moved).get[Boolean]("clientSecretSet") shouldBe Right(false)
      }
      w.acme.authClientSecret shouldBe None
      // Each write's audit row says whether the stored secret changed: set, kept, then dropped
      // by the issuer change.
      w.auditRows
        .filter(e => e.action == AuditActions.TenantAuthUpdate && e.outcome == "ok")
        .map(_.detail.get("clientKey")) shouldBe List(Some("changed"), None, Some("changed"))
      // Nothing is left to send: the sign-in does not start and no token request leaves.
      val start = get(w.h.httpClient, w.url(s"/api/auth/oidc/start?tenant=$Acme&returnTo=/ui/"))
      withClue(s"oidc start: ${statusAndCode(start)}") {
        start.statusCode() shouldBe 400
        errorCode(start.body()) shouldBe Some("oidc_not_configured")
      }
      w.idp.exchanges.map(_._1) shouldBe empty
    }

  it should "drop a stored reference a tenant admin carries unchanged to a new issuer" in
    withManager() { w =>
      val alice = w.aliceToken()
      val root  = w.rootToken()
      setAuth(w, root, oidcConfig(IssuerA) + ("clientSecretRef" -> OperatorRef))
        .statusCode() shouldBe 200
      val moved = setAuth(w, alice, oidcConfig(IssuerB) + ("clientSecretRef" -> OperatorRef))
      withClue(s"setAuth: ${statusAndCode(moved)}")(moved.statusCode() shouldBe 200)
      w.acme.authConfig.get("issuerUrl") shouldBe Some(IssuerB)
      w.acme.authConfig.get("clientSecretRef") shouldBe None
      val start = get(w.h.httpClient, w.url(s"/api/auth/oidc/start?tenant=$Acme&returnTo=/ui/"))
      withClue(s"oidc start: ${statusAndCode(start)}") {
        start.statusCode() shouldBe 400
        errorCode(start.body()) shouldBe Some("oidc_not_configured")
      }
      w.idp.exchanges.map(_._1) shouldBe empty
    }

  it should "keep a stored reference a superuser carries unchanged to a new issuer, and resolve it into the token request to that issuer only" in
    withManager() { w =>
      val root                   = w.rootToken()
      val (responses, logEvents) = capturingLogs {
        val stored = setAuth(w, root, oidcConfig(IssuerA) + ("clientSecretRef" -> OperatorRef))
        withClue(s"setAuth: ${statusAndCode(stored)}")(stored.statusCode() shouldBe 200)
        // The superuser's own write carries the reference to the new issuer: unlike a tenant
        // admin's, it stays (a superuser may pair a reference with any issuer).
        val moved = setAuth(w, root, oidcConfig(IssuerB) + ("clientSecretRef" -> OperatorRef))
        withClue(s"setAuth: ${statusAndCode(moved)}") {
          moved.statusCode() shouldBe 200
          json(moved).get[Boolean]("clientSecretSet") shouldBe Right(false)
          json(moved).downField("authConfig").get[String]("clientSecretRef") shouldBe Right(
            OperatorRef
          )
        }
        val mode = get(w.h.httpClient, w.url(s"/api/auth/mode?tenant=$Acme"))
        withClue(s"auth mode: ${statusAndCode(mode)}") {
          mode.statusCode() shouldBe 200
          json(mode).get[String]("mode") shouldBe Right("oidc")
        }
        val signIn = ssoSignIn(w, Acme, SecurityFixtures.AliceUsername, "alice@acme.example")
        signIn.session should not be empty
        signIn.authorize should startWith(s"$IssuerB/authorize?")
        List(
          "tenant/setAuth (first write)"  -> stored.body(),
          "tenant/setAuth (issuer moved)" -> moved.body(),
          "auth/mode"                     -> mode.body(),
          "authorize redirect"            -> signIn.authorize,
          "callback redirect"             -> signIn.location
        )
      }
      w.acme.authConfig.get("issuerUrl") shouldBe Some(IssuerB)
      w.acme.authConfig.get("clientSecretRef") shouldBe Some(OperatorRef)
      w.acme.authClientSecret shouldBe None
      // The reference resolves into the token request to the issuer it was carried to, only.
      w.idp.exchanges.map(_._1) shouldBe List(s"$IssuerB/token")
      w.idp.exchanges.exists(_._2.contains(s"client_secret=${enc(OperatorSecret)}")) shouldBe true
      assertNeverShown(OperatorSecret, responses, logEvents)
    }

  it should "keep the secret the same write supplies and send it to the new issuer only" in
    withManager() { w =>
      val alice = w.aliceToken()
      setAuth(w, alice, oidcConfig(IssuerA), clientSecret = Some(SecretA)).statusCode() shouldBe 200
      val moved = setAuth(w, alice, oidcConfig(IssuerB), clientSecret = Some(SecretB))
      withClue(s"setAuth: ${statusAndCode(moved)}") {
        moved.statusCode() shouldBe 200
        json(moved).get[Boolean]("clientSecretSet") shouldBe Right(true)
      }
      w.acme.authClientSecret shouldBe Some(SecretB)
      val signIn = ssoSignIn(w, Acme, SecurityFixtures.AliceUsername, "alice@acme.example")
      signIn.session should not be empty
      signIn.authorize should startWith(s"$IssuerB/authorize?")
      w.idp.exchanges.map(_._1) shouldBe List(s"$IssuerB/token")
      w.idp.exchanges.exists(_._2.contains(s"client_secret=${enc(SecretB)}")) shouldBe true
      w.idp.exchanges.exists(_._2.contains(enc(SecretA))) shouldBe false
    }

  // ---------------------------------------------------------------------------------------------
  // 4. A tenant SSO login, and a PAT minted from its session, bind only that tenant's rows
  // ---------------------------------------------------------------------------------------------

  "A tenant SSO login" should
    "bind only the tenant's own row while a superuser row and another tenant's row share the name" in
    withManager(seed = acmeSignsInThroughSso) { w =>
      val signIn  = ssoSignIn(w, Acme, "carol", "carol@corp.example")
      val session = signIn.session.getOrElse(fail(s"no session: ${signIn.location}"))
      val who     = whoami(w, session)
      who.get[String]("username") shouldBe Right("carol")
      who.get[Boolean]("superuser") shouldBe Right(false)
      who.get[List[String]]("manageableTenants") shouldBe Right(List(Acme))
      who.get[Option[String]]("tenant") shouldBe Right(Some(Acme))
    }

  it should "be refused not_provisioned when the name has rows only in other realms" in
    withManager(seed = acmeSignsInThroughSso) { w =>
      val signIn = ssoSignIn(w, Acme, "erin", "erin@corp.example")
      signIn.location shouldBe "/ui/?error=not_provisioned"
      signIn.session shouldBe None
    }

  it should "be refused admin_required when the tenant's own row is not an admin, whatever a same-named superuser row holds" in
    withManager(seed = acmeSignsInThroughSso) { w =>
      val signIn = ssoSignIn(w, Acme, "dave", "dave@corp.example")
      signIn.location shouldBe "/ui/?error=admin_required"
      signIn.session shouldBe None
    }

  "A personal access token minted from a tenant SSO session" should
    "be owned by the tenant's own row, never a same-named superuser or other-tenant row" in
    withManager(seed = acmeSignsInThroughSso, withPats = true) { w =>
      val signIn  = ssoSignIn(w, Acme, "carol", "carol@corp.example")
      val session = signIn.session.getOrElse(fail(s"no session: ${signIn.location}"))
      val created = post(
        w.h.httpClient,
        w.url("/api/auth/pat/create"),
        Json.obj("name" -> Json.fromString("carol-from-sso")).noSpaces,
        Some(session)
      )
      // A successful body carries the raw token: the clue names only a status and an error code.
      withClue(s"pat/create: ${statusAndCode(created)}") {
        created.statusCode() shouldBe 200
      }
      val token = json(created).get[String]("token").getOrElse(fail("no token in the answer"))
      val owner = patAuth.resolve(token).map(p => (p.user.tenant, p.user.username))
      owner shouldBe Some((Some(Acme), "carol"))
    }

  // ---------------------------------------------------------------------------------------------
  // 5. No password login into an OIDC tenant
  // ---------------------------------------------------------------------------------------------

  "A password login naming an OIDC tenant" should
    "be refused with 400 sso_required before any password check, linking the tenant id however its name is typed" in
    withManager(seed = acmeSignsInThroughSso) { w =>
      // (tenant as typed, password): the id itself; a padded upper-case spelling with the right
      // password; a case variant with a wrong one. The login resolves each to the tenant id, as
      // Main does, so every refusal names the same link and the same audit tenant.
      val attempts = List(
        (Acme, SecurityFixtures.AlicePassword),
        (" ACME ", SecurityFixtures.AlicePassword),
        ("Acme", "not-alices-password")
      )
      attempts.foreach { (typed, password) =>
        val resp = post(
          w.h.httpClient,
          w.url("/api/auth/login"),
          loginBody(SecurityFixtures.AliceUsername, password, Some(typed))
        )
        withClue(s"password login into '$typed': ${statusAndCode(resp)}") {
          resp.statusCode() shouldBe 400
          errorCode(resp.body()) shouldBe Some("sso_required")
          val message = json(resp).get[String]("message").getOrElse("")
          message should (include(s"/ui/?tenant=$Acme") and include("personal access token"))
        }
      }
      // Every refusal is audited as a denied login under the tenant id the login resolved, with
      // the reason; none is a password failure (no password was checked).
      val refusals = w.auditRows.filter(e =>
        e.action == AuditActions.AuthLoginFailure && e.detail.get("reason").contains("sso_required")
      )
      refusals.map(e => (e.outcome, e.tenant)) shouldBe
        List.fill(attempts.size)(("denied", Some(Acme)))
      w.auditRows.count(_.action == AuditActions.AuthLoginFailure) shouldBe attempts.size
      // The page the message points to asks the public mode endpoint, which answers oidc for the
      // tenant however its name is typed (trimmed, any letter case).
      List(Acme, "%20ACME%20").foreach { typed =>
        val mode = get(w.h.httpClient, w.url(s"/api/auth/mode?tenant=$typed"))
        withClue(s"auth mode for '$typed': ${statusAndCode(mode)}") {
          mode.statusCode() shouldBe 200
          json(mode).get[String]("mode") shouldBe Right("oidc")
        }
      }
    }

  "A password login naming a db tenant" should
    "keep working, while an unknown tenant answers like a bad password" in
    withManager(seed = addGlobexPasswordUser) { w =>
      w.h.mintToken("gina", "ginapw", Some(Globex)) should not be empty
      val unknown =
        post(w.h.httpClient, w.url("/api/auth/login"), loginBody("gina", "ginapw", Some("nope")))
      withClue(s"login into an unknown tenant: ${statusAndCode(unknown)}") {
        unknown.statusCode() shouldBe 401
        errorCode(unknown.body()) shouldBe Some("invalid_credentials")
      }
    }

  // ---------------------------------------------------------------------------------------------
  // 6. REST and MCP statements bind a row of the caller's own realm
  // ---------------------------------------------------------------------------------------------

  "A REST statement" should
    "never bind a superuser row for a tenant session, even one named like the superuser" in
    withManager(seed = acmeSignsInThroughSso) { w =>
      // acme's IdP names the superuser; its email claim is an acme admin row, so the tenant-realm
      // login mints a tenant session called root.
      val signIn  = ssoSignIn(w, Acme, SecurityFixtures.RootUsername, "carol@acme.example")
      val session = signIn.session.getOrElse(fail(s"no session: ${signIn.location}"))
      val who     = whoami(w, session)
      who.get[String]("username") shouldBe Right(SecurityFixtures.RootUsername)
      who.get[Boolean]("superuser") shouldBe Right(false)
      val resp = get(w.h.httpClient, w.url(PreviewPath), Some(session))
      withClue(s"preview: ${statusAndCode(resp)}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("acl_denied")
        resp.body() should include(s"is not registered in tenant '$Acme'")
      }
      w.route.calls shouldBe empty
    }

  it should "bind a superuser session to its own superuser row" in withManager() { w =>
    val resp = get(w.h.httpClient, w.url(PreviewPath), Some(w.rootToken()))
    withClue(s"preview: ${statusAndCode(resp)}") {
      // The recording router leg answers Unavailable once it has seen the statement.
      resp.statusCode() shouldBe 502
      errorCode(resp.body()) shouldBe Some("preview_failed")
    }
    val bound = w.route.calls.map((caller, _, eff) =>
      (caller.superuserAdmissible, eff.map(e => (e.user.id, e.user.tenant)))
    )
    bound shouldBe List((true, Some((w.fix.rootUserId, None))))
  }

  "An MCP statement" should
    "never bind a superuser row for a tenant PAT whose owner is named like the superuser, nor the same PAT's REST preview" in
    withManager(withPats = true) { w =>
      // The PAT's owner is acme's own row named root (Postgres directory). The gate reads the
      // control plane, where acme holds no root row: the only row the name can bind there is the
      // superuser's, and the PAT's realm must refuse it.
      val pat    = mintPat(Some(Acme), SecurityFixtures.RootUsername)
      val result = runSql(w, pat, tenant = None)
      result.get[Boolean]("isError") shouldBe Right(true)
      val text = result.downField("content").downN(0).get[String]("text").getOrElse("")
      text should include(s"is not registered in tenant '$Acme'")
      // The same PAT over REST (RestCaller's PAT arm, not McpDataTools') is refused alike.
      val preview = get(w.h.httpClient, w.url(PreviewPath), Some(pat))
      withClue(s"preview: ${statusAndCode(preview)}") {
        preview.statusCode() shouldBe 403
        errorCode(preview.body()) shouldBe Some("acl_denied")
        preview.body() should include(s"is not registered in tenant '$Acme'")
      }
      w.route.calls shouldBe empty
    }

  it should "bind a superuser PAT to its own superuser row, over MCP and over the REST preview" in
    withManager(withPats = true) { w =>
      val pat = mintPat(None, SecurityFixtures.RootUsername)
      runSql(w, pat, tenant = Some(Acme))
      val preview = get(w.h.httpClient, w.url(PreviewPath), Some(pat))
      withClue(s"preview: ${statusAndCode(preview)}") {
        // The recording router leg answers Unavailable once it has seen the statement.
        preview.statusCode() shouldBe 502
        errorCode(preview.body()) shouldBe Some("preview_failed")
      }
      val bound = w.route.calls.map((caller, _, eff) =>
        (
          caller.superuserAdmissible,
          caller.patId.isDefined,
          eff.map(e => (e.user.id, e.user.tenant))
        )
      )
      bound shouldBe List.fill(2)((true, true, Some((w.fix.rootUserId, None))))
    }
