package ai.starlake.quack.security

import ai.starlake.quack.ondemand.auth.{PatAuthenticator, TokenRestriction}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import scala.util.Try

/** End-to-end SCIM 2.0 provisioning over the real ManagerServer: bearer-token auth (the SCIM
  * transport), user and group lifecycle, filters, PATCH semantics, and the tenant perimeter.
  *
  * The cases act as the fixture superuser, whose login session rides the Bearer (the guard admits a
  * session there as it does on X-API-Key), so they need no database. One case presents the
  * credential an IdP connector is documented to hold, a tenant admin's personal access token; it
  * runs only where the test Postgres is reachable (`qodstate_pat` has an FK to `qodstate_user`).
  */
class ScimSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers with BeforeAndAfterAll:
  import ManagerServerHarness.*
  import SecurityFixtures.*

  TestPostgres.dropStrayTestDatabases("qodscim")

  private val dbName           = s"qodscim_test_${System.nanoTime()}"
  private var users: UserStore = null
  private var pats: PatStore   = null
  private var fix: Fixture     = scala.compiletime.uninitialized
  private var harness: Harness = scala.compiletime.uninitialized
  // The fixture superuser's login session: the default Bearer.
  private var session: String = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    fix = freshStore()
    addTenantB(fix)
    // A globex tenant admin, to prove a tenant-scoped credential works against a
    // display-name base URL (globex's id is t-globex01, unlike acme where id = name).
    fix.store.upsertUserWithHash(
      Some(GlobexTenantId),
      "gina",
      at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(10, "ginapw".toCharArray),
      "admin"
    )
    harness = boot(fix.store)
    session = harness.superuserSession()
    // The tenant-admin PAT case's store; without Postgres that case cancels.
    if TestPostgres.reachable then
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      // The PAT's owner, mirrored from the login fixture: qodstate_pat's FK is on this row.
      users.upsertUser(Some(TenantId), AliceUsername, AlicePassword, "admin")

  override def afterAll(): Unit =
    if harness != null then harness.shutdown()
    if pats != null then pats.close()
    if users != null then users.close()
    if users != null then Try(TestPostgres.dropDatabase(dbName))
    ()

  private def scimUrl(rest: String, tenant: String = TenantId): String =
    s"${harness.baseUrl}/api/scim/v2/$tenant/$rest"

  /** SCIM-style request: Authorization: Bearer, application/scim+json body. */
  private def scim(
      method: String,
      url: String,
      body: Option[String] = None,
      bearer: Option[String] = Some(session)
  ): HttpResponse[String] =
    val b = HttpRequest.newBuilder(URI.create(url)).timeout(RequestTimeout)
    bearer.foreach(t => b.header("Authorization", s"Bearer $t"))
    val withBody = body match
      case Some(payload) =>
        b.header("Content-Type", "application/scim+json")
          .method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
      case None => b.method(method, HttpRequest.BodyPublishers.noBody())
    harness.httpClient.send(withBody.build(), HttpResponse.BodyHandlers.ofString())

  private def field(body: String, key: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[String](key).toOption)

  // ---------------- auth transport ----------------

  "SCIM" should "refuse an anonymous request with 401" in:
    val r = scim("GET", scimUrl("Users"), bearer = None)
    r.statusCode() shouldBe 401

  it should "accept a superuser session as an OAuth bearer token" in:
    val r = scim("GET", scimUrl("ServiceProviderConfig"))
    r.statusCode() shouldBe 200
    r.body() should include("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig")
    r.headers().firstValue("Content-Type").orElse("") should include("scim+json")

  it should "refuse a cross-tenant admin session with 403" in:
    val aliceToken = harness.mintToken(AliceUsername, AlicePassword, Some(TenantName))
    val b          = HttpRequest
      .newBuilder(URI.create(scimUrl("Users", tenant = GlobexTenantName)))
      .header("X-API-Key", aliceToken)
      .GET()
      .timeout(RequestTimeout)
    val r = harness.httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString())
    r.statusCode() shouldBe 403

  it should "accept a lowercase bearer scheme (RFC 7235)" in:
    val b = HttpRequest
      .newBuilder(URI.create(scimUrl("ServiceProviderConfig")))
      .header("Authorization", s"bearer $session")
      .GET()
      .timeout(RequestTimeout)
    val r = harness.httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString())
    r.statusCode() shouldBe 200

  it should "admit a tenant admin on a display-name base URL (id != name)" in:
    // Login by tenant id (the login form's own id-or-name handling is not under
    // test here); the display-name leg under test is the SCIM base URL below.
    val ginaToken = harness.mintToken("gina", "ginapw", Some(GlobexTenantId))
    val b         = HttpRequest
      .newBuilder(URI.create(scimUrl("Users", tenant = GlobexTenantName)))
      .header("X-API-Key", ginaToken)
      .GET()
      .timeout(RequestTimeout)
    val r = harness.httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString())
    withClue(r.body())(r.statusCode() shouldBe 200)

  it should "admit a tenant admin session on its own tenant" in:
    val aliceToken = harness.mintToken(AliceUsername, AlicePassword, Some(TenantName))
    val b          = HttpRequest
      .newBuilder(URI.create(scimUrl("Users")))
      .header("X-API-Key", aliceToken)
      .GET()
      .timeout(RequestTimeout)
    val r = harness.httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString())
    r.statusCode() shouldBe 200

  it should "pin a tenant-admin PAT presented as the bearer to its own tenant" in:
    // What an IdP connector is documented to hold: a tenant admin's personal access token.
    TestPostgres.ensureReachable()
    val uid = users
      .userIdOf(Some(TenantId), AliceUsername)
      .getOrElse(fail(s"fixture user $AliceUsername not found"))
    val (_, raw) = pats.mint(uid, "scim-connector", TokenRestriction.Unrestricted, None, 0)
    // Its own harness and fixture store, with the PAT wiring Main has when Postgres is configured;
    // the other cases' store is left alone.
    val patFix = freshStore()
    addTenantB(patFix)
    val h = boot(
      patFix.store,
      patStore = Some(pats),
      patUserOf =
        Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
      patAuth =
        Some(new PatAuthenticator(pats, users.userById, u => List(UserGrant(u.tenant, u.kind))))
    )
    def listUsers(tenant: String): HttpResponse[String] =
      val b = HttpRequest
        .newBuilder(URI.create(s"${h.baseUrl}/api/scim/v2/$tenant/Users"))
        .header("Authorization", s"Bearer $raw")
        .GET()
        .timeout(RequestTimeout)
      h.httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString())
    try
      val own = listUsers(TenantId)
      withClue(own.body())(own.statusCode() shouldBe 200)
      own.body() should include("urn:ietf:params:scim:api:messages:2.0:ListResponse")
      // Another tenant's base URL: the PAT carries its owner's tenant scope and nothing more.
      val crossed = listUsers(GlobexTenantName)
      withClue(crossed.body())(crossed.statusCode() shouldBe 403)
    finally h.shutdown()

  // ---------------- users ----------------

  it should "create a user without a password, listing it as active" in:
    val r = scim(
      "POST",
      scimUrl("Users"),
      Some("""{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
              |"userName":"carol@example.com","externalId":"okta-1",
              |"emails":[{"value":"carol@example.com","primary":true}]}""".stripMargin)
    )
    r.statusCode() shouldBe 201
    field(r.body(), "userName") shouldBe Some("carol@example.com")
    field(r.body(), "externalId") shouldBe Some("okta-1")
    val row = fix.store.findUser(Some(TenantId), "carol@example.com")
    row.map(_.enabled) shouldBe Some(true)
    row.flatMap(_.email) shouldBe Some("carol@example.com")

  it should "create a user with active:false already disabled (no enabled window)" in:
    val r = scim(
      "POST",
      scimUrl("Users"),
      Some("""{"userName":"dave@example.com","active":false,"password":"davepw01"}""")
    )
    r.statusCode() shouldBe 201
    fix.store.findUser(Some(TenantId), "dave@example.com").map(_.enabled) shouldBe Some(false)

  it should "refuse a duplicate userName with 409 uniqueness" in:
    val r = scim("POST", scimUrl("Users"), Some("""{"userName":"carol@example.com"}"""))
    r.statusCode() shouldBe 409
    r.body() should include("uniqueness")

  it should "find a user by userName and by externalId filters" in:
    val byName = scim("GET", scimUrl("Users?filter=userName%20eq%20%22carol@example.com%22"))
    byName.statusCode() shouldBe 200
    byName.body() should include(""""totalResults":1""")
    val byExt = scim("GET", scimUrl("Users?filter=externalId%20eq%20%22okta-1%22"))
    byExt.body() should include("carol@example.com")
    val miss = scim("GET", scimUrl("Users?filter=userName%20eq%20%22nobody%22"))
    miss.body() should include(""""totalResults":0""")

  it should "deactivate via PATCH (including Entra's string booleans) and cut login" in:
    // Harness artifact: the control-plane store (InMemory) and the credential store
    // (DuckDB UserStore) are separate here, so the SCIM-created row has no hash on
    // the control-plane side and the enabled-rewrite would refuse. In production both
    // are the same Postgres row. Seed the hash the way the fixtures do.
    fix.store.upsertUserWithHash(
      Some(TenantId),
      "carol@example.com",
      "$2a$10$seedseedseedseedseedse",
      "user",
      email = Some("carol@example.com")
    )
    val id = fix.store.findUser(Some(TenantId), "carol@example.com").map(_.id).get
    val r  = scim(
      "PATCH",
      scimUrl(s"Users/$id"),
      Some("""{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
              |"Operations":[{"op":"Replace","path":"active","value":"False"}]}""".stripMargin)
    )
    r.statusCode() shouldBe 200
    fix.store.findUser(Some(TenantId), "carol@example.com").map(_.enabled) shouldBe Some(false)
    val back = scim(
      "PATCH",
      scimUrl(s"Users/$id"),
      Some("""{"Operations":[{"op":"replace","value":{"active":true}}]}""")
    )
    back.statusCode() shouldBe 200
    fix.store.findUser(Some(TenantId), "carol@example.com").map(_.enabled) shouldBe Some(true)

  it should "apply an externalId PATCH under a Turkish default locale" in:
    // externalId is the RFC 7644 canonical spelling, camelCase with a capital I. The handler
    // lowercases the incoming path and matches "externalid", so under a Turkish or Azeri default
    // locale the fold emits a dotless i, no match arm fires, and the operation is silently
    // dropped while SCIM still answers 200. That is the normal case for a conforming IdP, not an
    // edge case, so the connector drifts out of sync on its stable user identifier.
    val previous = java.util.Locale.getDefault
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
    try
      // Guard: if the JVM does not actually fold here, the rest of this test proves nothing.
      "externalId".toLowerCase should not be "externalid"
      val id = fix.store.findUser(Some(TenantId), "carol@example.com").map(_.id).get
      val r  = scim(
        "PATCH",
        scimUrl(s"Users/$id"),
        Some("""{"Operations":[{"op":"replace","path":"externalId","value":"okta-tr-1"}]}""")
      )
      r.statusCode() shouldBe 200
      fix.store.findUser(Some(TenantId), "carol@example.com").flatMap(_.externalId) shouldBe
        Some("okta-tr-1")
    finally java.util.Locale.setDefault(previous)

  it should "refuse a userName rename with scimType mutability" in:
    val id = fix.store.findUser(Some(TenantId), "carol@example.com").map(_.id).get
    val r  = scim("PUT", scimUrl(s"Users/$id"), Some("""{"userName":"renamed"}"""))
    r.statusCode() shouldBe 400
    r.body() should include("mutability")

  it should "not expose another tenant's user by id" in:
    val id = fix.store.findUser(Some(TenantId), "carol@example.com").map(_.id).get
    val r  = scim("GET", scimUrl(s"Users/$id", tenant = GlobexTenantName))
    r.statusCode() shouldBe 404

  it should "never list or match the superuser realm" in:
    val r = scim("GET", scimUrl("Users?filter=userName%20eq%20%22root%22"))
    r.body() should include(""""totalResults":0""")

  // ---------------- groups ----------------

  it should "create a group with members and read them back" in:
    val carolId = fix.store.findUser(Some(TenantId), "carol@example.com").map(_.id).get
    val r       = scim(
      "POST",
      scimUrl("Groups"),
      Some(s"""{"displayName":"analysts","externalId":"okta-g1",
               |"members":[{"value":"$carolId"}]}""".stripMargin)
    )
    r.statusCode() shouldBe 201
    // The 201 body must echo the just-written externalId (IdPs reconcile the echo).
    field(r.body(), "externalId") shouldBe Some("okta-g1")
    val gid = field(r.body(), "id").get
    val got = scim("GET", scimUrl(s"Groups/$gid"))
    got.statusCode() shouldBe 200
    got.body() should include(carolId)
    got.body() should include("carol@example.com")

  it should "remove a member via the SCIM filter path and re-add via add" in:
    val carolId = fix.store.findUser(Some(TenantId), "carol@example.com").map(_.id).get
    val gid     = fix.store.findGroup(TenantId, "analysts").map(_.id).get
    val rm      = scim(
      "PATCH",
      scimUrl(s"Groups/$gid"),
      Some(s"""{"Operations":[{"op":"remove","path":"members[value eq \\"$carolId\\"]"}]}""")
    )
    rm.statusCode() shouldBe 200
    fix.store.listUsersInGroup(gid) shouldBe Nil
    val add = scim(
      "PATCH",
      scimUrl(s"Groups/$gid"),
      Some(s"""{"Operations":[{"op":"add","path":"members","value":[{"value":"$carolId"}]}]}""")
    )
    add.statusCode() shouldBe 200
    fix.store.listUsersInGroup(gid) shouldBe List(carolId)

  it should "honor a case-variant member filter path (RFC 7644 case-insensitivity)" in:
    val carolId = fix.store.findUser(Some(TenantId), "carol@example.com").map(_.id).get
    val gid     = fix.store.findGroup(TenantId, "analysts").map(_.id).get
    val rm      = scim(
      "PATCH",
      scimUrl(s"Groups/$gid"),
      Some(s"""{"Operations":[{"op":"Remove","path":"Members[value eq \\"$carolId\\"]"}]}""")
    )
    withClue(rm.body())(rm.statusCode() shouldBe 200)
    fix.store.listUsersInGroup(gid) shouldBe Nil

  it should "replace the member set on PUT, refusing members from other tenants" in:
    val gid = fix.store.findGroup(TenantId, "analysts").map(_.id).get
    val bad = scim(
      "PUT",
      scimUrl(s"Groups/$gid"),
      Some(s"""{"displayName":"analysts","members":[{"value":"${fix.rootUserId}"}]}""")
    )
    bad.statusCode() shouldBe 400
    val bobIn = scim(
      "PUT",
      scimUrl(s"Groups/$gid"),
      Some(s"""{"displayName":"analysts","members":[{"value":"${fix.bobUserId}"}]}""")
    )
    bobIn.statusCode() shouldBe 200
    fix.store.listUsersInGroup(gid) shouldBe List(fix.bobUserId)

  it should "find a group by displayName filter" in:
    val r = scim("GET", scimUrl("Groups?filter=displayName%20eq%20%22analysts%22"))
    r.body() should include(""""totalResults":1""")

  it should "delete a group and then a user with 204" in:
    val gid = fix.store.findGroup(TenantId, "analysts").map(_.id).get
    scim("DELETE", scimUrl(s"Groups/$gid")).statusCode() shouldBe 204
    fix.store.findGroup(TenantId, "analysts") shouldBe None
    val uid = fix.store.findUser(Some(TenantId), "carol@example.com").map(_.id).get
    scim("DELETE", scimUrl(s"Users/$uid")).statusCode() shouldBe 204
    fix.store.findUser(Some(TenantId), "carol@example.com") shouldBe None

  it should "refuse an unsupported filter as invalidFilter" in:
    val r = scim("GET", scimUrl("Users?filter=userName%20co%20%22car%22"))
    r.statusCode() shouldBe 400
    r.body() should include("invalidFilter")
