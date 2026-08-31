// src/test/scala/ai/starlake/quack/security/PatChainRestSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.ondemand.auth.PatAuthenticator
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** End-to-end contract of PAT-minted children: `/api/auth/pat/create` presented WITH a PAT (rather
  * than a session), the resulting chain (`parentId`, `depth`), the subtree scoping on
  * `list`/`revoke`/`delete`, and the cascading revoke. See [[PatHandlers]]'s scaladoc for the
  * reasoning that makes admitting a PAT here safe; [[PatRestSpec]] and [[PatApiAdmissionSpec]]
  * cover the session-caller and guard-admission halves of the same surface.
  *
  * Fixture and helpers mirror [[PatRestSpec]] verbatim (own database prefix `qodpatchain` so the
  * two suites never collide).
  */
class PatChainRestSpec
    extends AnyFlatSpec
    with Matchers
    with SecurityHttpHelpers
    with BeforeAndAfterAll:

  TestPostgres.dropStrayTestDatabases("qodpatchain")

  private val dbName = s"qodpatchain_test_${System.nanoTime()}"

  private var users: UserStore = null
  private var pats: PatStore   = null

  override def beforeAll(): Unit =
    if TestPostgres.reachable then
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      users.upsertUser(None, SecurityFixtures.RootUsername, SecurityFixtures.RootPassword, "admin")
      users.upsertUser(
        Some(SecurityFixtures.TenantId),
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        "admin"
      )
      users.upsertUser(
        Some(SecurityFixtures.TenantId),
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        "user"
      )

  override def afterAll(): Unit =
    if pats != null then pats.close()
    if users != null then users.close()
    Try(TestPostgres.dropDatabase(dbName))
    ()

  private def withHarness(
      staticApiKey: Option[String] = None,
      env: Map[String, String] = Map.empty
  )(body: ManagerServerHarness.Harness => Unit): Unit =
    TestPostgres.ensureReachable()
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(
      fix.store,
      staticApiKey = staticApiKey,
      patStore = Some(pats),
      patUserOf =
        Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
      patAuth =
        Some(new PatAuthenticator(pats, users.userById, u => List(UserGrant(u.tenant, u.role)))),
      env = env
    )
    try body(h)
    finally h.shutdown()

  private def field(body: String, name: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[String](name).toOption)

  private def tokenEntries(body: String): List[io.circe.Json] =
    parse(body).toOption
      .flatMap(_.hcursor.get[List[io.circe.Json]]("tokens").toOption)
      .getOrElse(Nil)

  private def rootSession(h: ManagerServerHarness.Harness): String =
    h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)

  private def createBody(
      name: String,
      scope: String = ""
  ): String =
    val extra = if scope.isEmpty then "" else "," + scope
    s"""{"name":"$name"$extra}"""

  private def mint(h: ManagerServerHarness.Harness, cred: String, body: String) =
    post(h.httpClient, s"${h.baseUrl}/api/auth/pat/create", body, apiKey = Some(cred))

  private def revoke(h: ManagerServerHarness.Harness, cred: String, id: String) =
    post(
      h.httpClient,
      s"${h.baseUrl}/api/auth/pat/revoke",
      s"""{"id":"$id"}""",
      apiKey = Some(cred)
    )

  private def listing(h: ManagerServerHarness.Harness, cred: String) =
    post(h.httpClient, s"${h.baseUrl}/api/auth/pat/list", "", apiKey = Some(cred))

  "POST /api/auth/pat/create" should "let a PAT mint a narrower child" in withHarness() { h =>
    val session = rootSession(h)
    val root    = mint(h, session, createBody("root", """"databases":["a","b"]"""))
    root.statusCode() shouldBe 200
    val rootToken = field(root.body(), "token").get

    val child = mint(h, rootToken, createBody("child", """"databases":["a"]"""))
    withClue(s"child body: ${child.body()}")(child.statusCode() shouldBe 200)
    field(child.body(), "token").get should startWith(PatStore.TokenPrefix)

    val childId = field(child.body(), "id").get
    pats.verify(field(child.body(), "token").get).map(_.restriction.databases) shouldBe
      Some(Some(Set("a")))
    pats.verify(field(child.body(), "token").get).map(_.depth) shouldBe Some(1)
    pats.verify(field(child.body(), "token").get).flatMap(_.parentId) shouldBe
      Some(field(root.body(), "id").get)
    childId should not be empty
  }

  it should "refuse a child that widens an axis, naming the axis" in withHarness() { h =>
    val session   = rootSession(h)
    val rootToken =
      field(mint(h, session, createBody("r2", """"databases":["a"]""")).body(), "token").get
    val child = mint(h, rootToken, createBody("c2", """"databases":["a","c"]"""))
    child.statusCode() shouldBe 400
    field(child.body(), "error") shouldBe Some("pat_scope_widens")
    child.body() should include("databases")
  }

  // Expiry clamps rather than refusing: a child asking to outlive its parent is a
  // mistake to correct, and refusing would make "no expiry requested" an error.
  it should "clamp a child expiry to the parent's" in withHarness() { h =>
    val session      = rootSession(h)
    val parentExpiry = java.time.Instant.now().plusSeconds(3600)
    val rootToken    = field(
      mint(h, session, createBody("r3", s""""expiresAt":"$parentExpiry"""")).body(),
      "token"
    ).get
    val far   = java.time.Instant.now().plusSeconds(86400)
    val child = mint(h, rootToken, createBody("c3", s""""expiresAt":"$far""""))
    child.statusCode() shouldBe 200
    val childExpiry = pats.verify(field(child.body(), "token").get).flatMap(_.expiresAt).get
    childExpiry.isAfter(parentExpiry) shouldBe false
  }

  it should "refuse a child under a revoked parent with the non-leak 404" in withHarness() { h =>
    val session   = rootSession(h)
    val root      = mint(h, session, createBody("r4"))
    val rootToken = field(root.body(), "token").get
    revoke(h, session, field(root.body(), "id").get).statusCode() shouldBe 200
    val child = mint(h, rootToken, createBody("c4"))
    child.statusCode() shouldBe 401 // the revoked bearer no longer authenticates at all
  }

  it should "refuse beyond maxDepth" in withHarness(
    env = Map("QOD_PAT_MAX_DEPTH" -> "2")
  ) { h =>
    val session = rootSession(h)
    val d0      = field(mint(h, session, createBody("d0")).body(), "token").get
    val d1      = field(mint(h, d0, createBody("d1")).body(), "token").get
    val d2      = mint(h, d1, createBody("d2"))
    d2.statusCode() shouldBe 200
    val d3 = mint(h, field(d2.body(), "token").get, createBody("d3"))
    d3.statusCode() shouldBe 400
    field(d3.body(), "error") shouldBe Some("pat_depth_exceeded")
  }

  // The cascade is what makes PAT minting admissible at all: without it a leaked
  // token could mint a successor and roll forward past its own revocation.
  "POST /api/auth/pat/revoke" should "kill the whole subtree from the root" in withHarness() { h =>
    val session   = rootSession(h)
    val root      = mint(h, session, createBody("cr"))
    val rootToken = field(root.body(), "token").get
    val childTok  = field(mint(h, rootToken, createBody("cc")).body(), "token").get

    revoke(h, session, field(root.body(), "id").get).statusCode() shouldBe 200

    pats.verify(rootToken) shouldBe None
    pats.verify(childTok) shouldBe None
  }

  it should "let a PAT revoke its own child" in withHarness() { h =>
    val session   = rootSession(h)
    val rootToken = field(mint(h, session, createBody("pr")).body(), "token").get
    val child     = mint(h, rootToken, createBody("pc"))
    revoke(h, rootToken, field(child.body(), "id").get).statusCode() shouldBe 200
    pats.verify(field(child.body(), "token").get) shouldBe None
  }

  it should "refuse a PAT revoking a sibling, with the same 404 as an unknown id" in
    withHarness() { h =>
      val session = rootSession(h)
      val aTok    = field(mint(h, session, createBody("sa")).body(), "token").get
      val b       = mint(h, session, createBody("sb"))
      revoke(h, aTok, field(b.body(), "id").get).statusCode() shouldBe 404
      revoke(h, aTok, "pat-does-not-exist").statusCode() shouldBe 404
      // The sibling is untouched: a refusal must not have side effects.
      pats.verify(field(b.body(), "token").get).isDefined shouldBe true
    }

  it should "refuse a PAT revoking its own parent" in withHarness() { h =>
    val session  = rootSession(h)
    val root     = mint(h, session, createBody("up"))
    val childTok =
      field(mint(h, field(root.body(), "token").get, createBody("uc")).body(), "token").get
    revoke(h, childTok, field(root.body(), "id").get).statusCode() shouldBe 404
    pats.verify(field(root.body(), "token").get).isDefined shouldBe true
  }

  it should "refuse a PAT revoking itself" in withHarness() { h =>
    val session   = rootSession(h)
    val root      = mint(h, session, createBody("self"))
    val rootToken = field(root.body(), "token").get
    revoke(h, rootToken, field(root.body(), "id").get).statusCode() shouldBe 404
    pats.verify(rootToken).isDefined shouldBe true
  }

  "POST /api/auth/pat/list" should "show the subtree to a PAT and everything to a session" in
    withHarness() { h =>
      val session   = rootSession(h)
      val root      = mint(h, session, createBody("la"))
      val rootToken = field(root.body(), "token").get
      val child     = mint(h, rootToken, createBody("lb"))
      val unrelated = mint(h, session, createBody("lc"))

      val patView = tokenEntries(listing(h, rootToken).body())
        .flatMap(_.hcursor.get[String]("id").toOption)
      patView should contain(field(child.body(), "id").get)
      patView should not contain field(unrelated.body(), "id").get
      patView should not contain field(root.body(), "id").get

      val sessionView = tokenEntries(listing(h, session).body())
        .flatMap(_.hcursor.get[String]("id").toOption)
      sessionView should contain allOf (
        field(root.body(), "id").get,
        field(unrelated.body(), "id").get
      )
    }
