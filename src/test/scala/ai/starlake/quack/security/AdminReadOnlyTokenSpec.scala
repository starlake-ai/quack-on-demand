// src/test/scala/ai/starlake/quack/security/AdminReadOnlyTokenSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.ondemand.auth.{PatAuthenticator, TokenRestriction}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditQuery, AuditRecorder}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

/** A PAT restricted with `adminReadOnly` keeps its owner's scope but reaches only a closed list of
  * read routes. Everything else is refused, whatever its spelling, and the token is no credential
  * at all on `/mcp`.
  */
class AdminReadOnlyTokenSpec
    extends AnyFlatSpec
    with Matchers
    with SecurityHttpHelpers
    with BeforeAndAfterAll:

  TestPostgres.dropStrayTestDatabases("qodpatro")

  private val dbName = s"qodpatro_test_${System.nanoTime()}"

  private var users: UserStore          = null
  private var pats: PatStore            = null
  private var patAuth: PatAuthenticator = null

  override def beforeAll(): Unit =
    if TestPostgres.reachable then
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      patAuth = new PatAuthenticator(pats, users.userById, u => List(UserGrant(u.tenant, u.kind)))
      users.upsertUser(None, SecurityFixtures.RootUsername, SecurityFixtures.RootPassword, "admin")
      users.upsertUser(
        Some(SecurityFixtures.TenantId),
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        "admin"
      )

  override def afterAll(): Unit =
    if pats != null then pats.close()
    if users != null then users.close()

  private val ReadOnly = TokenRestriction.Unrestricted.copy(adminReadOnly = true)

  private def mint(
      restriction: TokenRestriction,
      tenant: Option[String] = None,
      username: String = SecurityFixtures.RootUsername
  ): String =
    val uid = users.userIdOf(tenant, username).get
    pats.mint(uid, s"ro-${System.nanoTime()}", restriction, None, 0)._2

  private def withHarness(
      telemetry: RecordingTelemetryStore = new RecordingTelemetryStore
  )(body: ManagerServerHarness.Harness => Unit): Unit =
    TestPostgres.ensureReachable()
    val fix = SecurityFixtures.freshStore()
    SecurityFixtures.addGlobexTenant(fix.store)
    val h = ManagerServerHarness.boot(
      fix.store,
      audit = new AuditRecorder(telemetry, _ => None),
      telemetryStore = telemetry,
      patStore = Some(pats),
      patUserOf =
        Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
      patAuth = Some(patAuth),
      mcpEnabled = true
    )
    try body(h)
    finally h.shutdown()

  private def assertReadOnlyRefusal(resp: HttpResponse[String]): Unit =
    withClue(resp.body()) {
      resp.statusCode() shouldBe 403
      errorCode(resp.body()) shouldBe Some("token_read_only")
    }

  private val AllowedReads = List(
    "/api/tenant/list",
    "/api/database/list",
    "/api/pool/list",
    "/api/security/audit",
    "/api/audit/list",
    "/api/history/statements",
    "/api/manifest/export"
  )

  "An adminReadOnly token" should "reach every read route on the list" in withHarness() { h =>
    val token = mint(ReadOnly)
    AllowedReads.foreach { path =>
      val resp = get(h.httpClient, s"${h.baseUrl}$path", Some(token))
      withClue(s"$path: ${resp.body()}") {
        errorCode(resp.body()) should not be Some("token_read_only")
        resp.statusCode() should not be 401
        resp.statusCode() should not be 403
      }
    }
  }

  private val RefusedPosts = List(
    "/api/manifest/import",
    "/api/role/create",
    "/api/role/delete",
    "/api/tenant/create",
    "/api/auth/pat/create",
    "/api/tenant/list"
  )

  RefusedPosts.foreach { path =>
    it should s"be refused on POST $path" in withHarness() { h =>
      assertReadOnlyRefusal(post(h.httpClient, s"${h.baseUrl}$path", "{}", Some(mint(ReadOnly))))
    }
  }

  private val RefusedGets = List(
    "/api/config/server",
    "/api/auth/pat/list",
    "/api/auth/whoami",
    "/api/manifest/export/"
  )

  RefusedGets.foreach { path =>
    it should s"be refused on GET $path, which is not on the list" in withHarness() { h =>
      assertReadOnlyRefusal(get(h.httpClient, s"${h.baseUrl}$path", Some(mint(ReadOnly))))
    }
  }

  private val Spellings = List("/%61pi/manifest/import", "////api/manifest/import")

  Spellings.foreach { path =>
    it should s"be refused on the spelling $path" in withHarness() { h =>
      assertReadOnlyRefusal(post(h.httpClient, s"${h.baseUrl}$path", "{}", Some(mint(ReadOnly))))
    }
  }

  it should "leave the manifest unchanged after a refused import" in withHarness() { h =>
    val token  = mint(ReadOnly)
    val before =
      get(h.httpClient, s"${h.baseUrl}/api/manifest/export?mode=rbac&tenant=acme", Some(token))
    val body = "roles: []\ngroups: []\n"
    assertReadOnlyRefusal(
      post(
        h.httpClient,
        s"${h.baseUrl}/api/manifest/import?mode=rbac&tenant=acme",
        body,
        Some(token)
      )
    )
    get(
      h.httpClient,
      s"${h.baseUrl}/api/manifest/export?mode=rbac&tenant=acme",
      Some(token)
    ).body() shouldBe before.body()
  }

  it should "keep its owner's tenant scope" in withHarness() { h =>
    val token = mint(ReadOnly, Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
    val resp  = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", Some(token))
    withClue(resp.body()) {
      resp.statusCode() shouldBe 200
      resp.body() should include(SecurityFixtures.TenantId)
      resp.body() should not include SecurityFixtures.GlobexTenantId
    }
  }

  it should "stay read-only in a stored token" in {
    TestPostgres.ensureReachable()
    val raw = mint(ReadOnly)
    pats.verify(raw).map(_.restriction.adminReadOnly) shouldBe Some(true)
  }

  it should "not be accepted on /mcp" in withHarness() { h =>
    val request = HttpRequest
      .newBuilder(URI.create(s"${h.baseUrl}/mcp"))
      .header("Content-Type", "application/json")
      .header("Authorization", s"Bearer ${mint(ReadOnly)}")
      .POST(
        HttpRequest.BodyPublishers.ofString(
          """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""",
          StandardCharsets.UTF_8
        )
      )
      .timeout(RequestTimeout)
      .build()
    h.httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 401
  }

  it should "leave an audit row when refused" in {
    val telemetry = new RecordingTelemetryStore
    withHarness(telemetry) { h =>
      assertReadOnlyRefusal(
        post(h.httpClient, s"${h.baseUrl}/api/manifest/import", "{}", Some(mint(ReadOnly)))
      )
      telemetry.listAudit(AuditQuery()).map(_.event.action) should contain(
        AuditActions.AuthTokenReadOnly
      )
    }
  }

  "A token without the restriction" should "still reach a route off the list" in withHarness() {
    h =>
      val resp = get(
        h.httpClient,
        s"${h.baseUrl}/api/config/server",
        Some(mint(TokenRestriction.Unrestricted))
      )
      errorCode(resp.body()) should not be Some("token_read_only")
      resp.statusCode() shouldBe 200
  }

  "TokenRestriction.narrow" should "keep adminReadOnly when a child does not ask for it" in {
    TokenRestriction.narrow(ReadOnly, TokenRestriction.Unrestricted).map(_.adminReadOnly) shouldBe
      Right(true)
  }

  it should "let a child add adminReadOnly" in {
    TokenRestriction.narrow(TokenRestriction.Unrestricted, ReadOnly).map(_.adminReadOnly) shouldBe
      Right(true)
  }
