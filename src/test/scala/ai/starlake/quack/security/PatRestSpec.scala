// src/test/scala/ai/starlake/quack/security/PatRestSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserStore}
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** End-to-end contract of the three personal-access-token REST routes
  * (`/api/auth/pat/{create,list,revoke}`).
  *
  * The routes are session-JWT-only and strictly self-scoped: the caller's identity comes from the
  * session token, never from a request field, so there is no tenant (or user) parameter anywhere on
  * the surface. Two properties are load-bearing and pinned here:
  *   - a PAT can never manage PATs (`403 session_required`), so a leaked token cannot mint itself a
  *     successor or revoke the tokens that would lock it out;
  *   - a caller can only ever see and revoke its OWN tokens, and another user's id is answered
  *     `404 not_found` -- indistinguishable from an id that never existed (no existence leak).
  *
  * The PAT store is real Postgres (`qodstate_pat` carries an FK to `qodstate_user`), shared by
  * every case in the suite; the fixture users are created once through a real [[UserStore]] on that
  * same database, which is exactly what `Main` wires as the handler's `userIdOf`.
  */
class PatRestSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers with BeforeAndAfterAll:

  TestPostgres.dropStrayTestDatabases("qodpatrest")

  private val dbName = s"qodpatrest_test_${System.nanoTime()}"

  private var users: UserStore = null
  private var pats: PatStore   = null

  override def beforeAll(): Unit =
    if TestPostgres.reachable then
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      // Mirror the in-memory login fixture into the PAT database: the qodstate_pat
      // FK is on the user row id, so the id the handler resolves must exist here.
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

  /** Boot a harness wired to the shared PAT store, with `userIdOf` resolved through the real
    * [[UserStore]] exactly as `Main` does.
    */
  private def withHarness(
      staticApiKey: Option[String] = None
  )(body: ManagerServerHarness.Harness => Unit): Unit =
    TestPostgres.ensureReachable()
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(
      fix.store,
      staticApiKey = staticApiKey,
      patStore = Some(pats),
      patUserIdOf = Some((tenant, username) => users.userIdOf(tenant, username))
    )
    try body(h)
    finally h.shutdown()

  private def field(body: String, name: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[String](name).toOption)

  private def tokenEntries(body: String): List[io.circe.Json] =
    parse(body).toOption
      .flatMap(_.hcursor.get[List[io.circe.Json]]("tokens").toOption)
      .getOrElse(Nil)

  private def entryFlag(entry: io.circe.Json, name: String): Option[Boolean] =
    entry.hcursor.get[Boolean](name).toOption

  private def rootSession(h: ManagerServerHarness.Harness): String =
    h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)

  private def tenantSession(h: ManagerServerHarness.Harness, user: String, pass: String): String =
    h.mintToken(user, pass, tenant = Some(SecurityFixtures.TenantId))

  // ------------------------------------------------------------------
  // 1 + 2. mint, then list
  // ------------------------------------------------------------------

  "POST /api/auth/pat/create" should "mint a prefixed token, list it once, and never echo it again" in
    withHarness() { h =>
      val session = rootSession(h)
      val created = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/create",
        """{"name":"claude-code"}""",
        apiKey = Some(session)
      )
      withClue(s"create body: ${created.body()}") {
        created.statusCode() shouldBe 200
      }
      val raw = field(created.body(), "token").getOrElse(fail("no token in create response"))
      raw should startWith(PatStore.TokenPrefix)
      field(created.body(), "name") shouldBe Some("claude-code")
      val id = field(created.body(), "id").getOrElse(fail("no id in create response"))

      // The raw token is a real credential against the store exactly once.
      pats.verify(raw).map(_.id) shouldBe Some(id)

      val listed = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/list",
        // `list` declares no body input, so nothing would consume one: an unread
        // request body desynchronizes the reused HTTP/1.1 connection.
        "",
        apiKey = Some(session)
      )
      withClue(s"list body: ${listed.body()}") {
        listed.statusCode() shouldBe 200
      }
      val mine =
        tokenEntries(listed.body()).filter(_.hcursor.get[String]("id").toOption.contains(id))
      mine should have size 1
      entryFlag(mine.head, "revoked") shouldBe Some(false)
      // The listing is metadata only: the raw secret is unrecoverable after mint.
      listed.body() should not include raw
    }

  // ------------------------------------------------------------------
  // 3. revoke, then revoke again
  // ------------------------------------------------------------------

  "POST /api/auth/pat/revoke" should "flip the listing to revoked and 404 a second revoke" in
    withHarness() { h =>
      val session = rootSession(h)
      val created = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/create",
        """{"name":"to-revoke"}""",
        apiKey = Some(session)
      )
      created.statusCode() shouldBe 200
      val id  = field(created.body(), "id").getOrElse(fail("no id in create response"))
      val raw = field(created.body(), "token").getOrElse(fail("no token in create response"))

      val revoked = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/revoke",
        s"""{"id":"$id"}""",
        apiKey = Some(session)
      )
      withClue(s"revoke body: ${revoked.body()}") {
        revoked.statusCode() shouldBe 200
      }
      pats.verify(raw) shouldBe None

      val listed = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/list",
        // `list` declares no body input, so nothing would consume one: an unread
        // request body desynchronizes the reused HTTP/1.1 connection.
        "",
        apiKey = Some(session)
      )
      val entry = tokenEntries(listed.body())
        .find(_.hcursor.get[String]("id").toOption.contains(id))
        .getOrElse(fail(s"revoked token missing from listing: ${listed.body()}"))
      entryFlag(entry, "revoked") shouldBe Some(true)

      // Already revoked is not distinguishable from unknown: both 404.
      val again = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/revoke",
        s"""{"id":"$id"}""",
        apiKey = Some(session)
      )
      withClue(s"second revoke body: ${again.body()}") {
        again.statusCode() shouldBe 404
        errorCode(again.body()) should contain("not_found")
      }
    }

  // ------------------------------------------------------------------
  // 4. a PAT may not manage PATs
  // ------------------------------------------------------------------

  "a PAT presented as the credential" should "be refused 403 session_required" in withHarness() {
    h =>
      val session = rootSession(h)
      val created = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/create",
        """{"name":"agent"}""",
        apiKey = Some(session)
      )
      created.statusCode() shouldBe 200
      val raw = field(created.body(), "token").getOrElse(fail("no token in create response"))

      // Open-mode harness on purpose: the api-key guard is out of the way, so what
      // answers here is the handler's own session-only gate (which is what must hold
      // once the guard learns to admit PATs as a bearer credential).
      val denied = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/create",
        """{"name":"successor"}""",
        apiKey = Some(raw)
      )
      withClue(s"create-with-pat body: ${denied.body()}") {
        denied.statusCode() shouldBe 403
        errorCode(denied.body()) should contain("session_required")
      }

      val deniedList = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/list",
        // `list` declares no body input, so nothing would consume one: an unread
        // request body desynchronizes the reused HTTP/1.1 connection.
        "",
        apiKey = Some(raw)
      )
      deniedList.statusCode() shouldBe 403
      errorCode(deniedList.body()) should contain("session_required")

      val deniedRevoke = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/revoke",
        """{"id":"pat-whatever"}""",
        apiKey = Some(raw)
      )
      deniedRevoke.statusCode() shouldBe 403
      errorCode(deniedRevoke.body()) should contain("session_required")
  }

  // ------------------------------------------------------------------
  // 5. no credential at all
  // ------------------------------------------------------------------

  "an anonymous caller" should "be refused 401 on every PAT route" in
    withHarness(staticApiKey = Some("k1")) { h =>
      List("create", "list", "revoke").foreach { verb =>
        // Empty body on purpose: the guard rejects before any decode, and a body
        // it never reads would poison the next request on the same connection.
        val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/pat/$verb", "")
        withClue(s"anonymous $verb body: ${resp.body()}") {
          resp.statusCode() shouldBe 401
        }
      }
    }

  // ------------------------------------------------------------------
  // 6. a regular (role=user) session manages its own tokens
  // ------------------------------------------------------------------

  "a role=user session" should "create, list and revoke its own PATs through the profile allowlist" in
    withHarness(staticApiKey = Some("k1")) { h =>
      // Static key set, so the guard is enforcing: reaching the handler at all
      // proves the three paths are on the non-admin profile allowlist.
      val session = tenantSession(h, SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)

      // Same session on an admin route stays sealed -- the allowlist widened by
      // exactly the PAT paths, not by the /api/auth namespace.
      val sealedOff = get(h.httpClient, s"${h.baseUrl}/api/pool/list", apiKey = Some(session))
      withClue(s"GET /api/pool/list body: ${sealedOff.body()}") {
        sealedOff.statusCode() shouldBe 403
        errorCode(sealedOff.body()) should contain("admin_required")
      }

      val created = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/create",
        """{"name":"bob-agent"}""",
        apiKey = Some(session)
      )
      withClue(s"create body: ${created.body()}") {
        created.statusCode() shouldBe 200
      }
      val id = field(created.body(), "id").getOrElse(fail("no id in create response"))

      val listed = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/list",
        // `list` declares no body input, so nothing would consume one: an unread
        // request body desynchronizes the reused HTTP/1.1 connection.
        "",
        apiKey = Some(session)
      )
      withClue(s"list body: ${listed.body()}") {
        listed.statusCode() shouldBe 200
      }
      tokenEntries(listed.body()).flatMap(_.hcursor.get[String]("id").toOption) should contain(id)

      val revoked = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/revoke",
        s"""{"id":"$id"}""",
        apiKey = Some(session)
      )
      withClue(s"revoke body: ${revoked.body()}") {
        revoked.statusCode() shouldBe 200
      }
    }

  // ------------------------------------------------------------------
  // 7. cross-user isolation
  // ------------------------------------------------------------------

  "one user" should "neither see nor revoke another user's token" in withHarness() { h =>
    val alice = tenantSession(h, SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword)
    val bob   = tenantSession(h, SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)

    val created = post(
      h.httpClient,
      s"${h.baseUrl}/api/auth/pat/create",
      """{"name":"alice-only"}""",
      apiKey = Some(alice)
    )
    withClue(s"create body: ${created.body()}") {
      created.statusCode() shouldBe 200
    }
    val aliceId  = field(created.body(), "id").getOrElse(fail("no id in create response"))
    val aliceRaw = field(created.body(), "token").getOrElse(fail("no token in create response"))

    // Bob's listing never contains it.
    val bobList = post(h.httpClient, s"${h.baseUrl}/api/auth/pat/list", "", apiKey = Some(bob))
    bobList.statusCode() shouldBe 200
    tokenEntries(bobList.body()).flatMap(
      _.hcursor.get[String]("id").toOption
    ) should not contain aliceId

    // And revoking it answers the same 404 an unknown id gets.
    val stolen = post(
      h.httpClient,
      s"${h.baseUrl}/api/auth/pat/revoke",
      s"""{"id":"$aliceId"}""",
      apiKey = Some(bob)
    )
    withClue(s"cross-user revoke body: ${stolen.body()}") {
      stolen.statusCode() shouldBe 404
      errorCode(stolen.body()) should contain("not_found")
    }
    val unknown = post(
      h.httpClient,
      s"${h.baseUrl}/api/auth/pat/revoke",
      """{"id":"pat-does-not-exist"}""",
      apiKey = Some(bob)
    )
    unknown.statusCode() shouldBe 404
    errorCode(unknown.body()) should contain("not_found")

    // Alice's token is untouched by the attempt.
    pats.verify(aliceRaw).map(_.id) shouldBe Some(aliceId)
  }
