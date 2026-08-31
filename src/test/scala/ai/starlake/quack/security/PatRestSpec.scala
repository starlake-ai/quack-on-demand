// src/test/scala/ai/starlake/quack/security/PatRestSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.ondemand.auth.PatAuthenticator
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.util.Try

/** End-to-end contract of the four personal-access-token REST routes
  * (`/api/auth/pat/{create,list,revoke,delete}`), presented with a SESSION credential. (Presenting
  * a PAT is [[PatChainRestSpec]]; the guard-level admission of a PAT as the bearer credential is
  * [[PatApiAdmissionSpec]].)
  *
  * The caller's identity comes from the credential, never from a request field, so there is no
  * tenant (or user) parameter anywhere on the surface. Two properties are load-bearing and pinned
  * here:
  *   - a session caller can only ever see and revoke its OWN tokens, and another user's id is
  *     answered `404 not_found` -- indistinguishable from an id that never existed (no existence
  *     leak);
  *   - a PAT presented on these routes IS now admitted (a reversal of this class's original,
  *     stricter rule -- see [[PatHandlers]]'s scaladoc for why that is safe), but strictly narrower
  *     than a session: it may only create, list, revoke and delete within its OWN subtree, which
  *     [[PatChainRestSpec]] covers in full. This suite's case 4 below pins the piece of that
  *     narrowing a session-focused suite still needs to know: an already-revoked bearer no longer
  *     authenticates at all, and a root PAT (unrestricted, no subtree yet) may mint a child but
  *     cannot touch anything outside itself.
  *
  * The PAT store is real Postgres (`qodstate_pat` carries an FK to `qodstate_user`), shared by
  * every case in the suite; the fixture users are created once through a real [[UserStore]] on that
  * same database, which is exactly what `Main` wires as the handler's `userIdOf`.
  */
class PatRestSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers with BeforeAndAfterAll:

  TestPostgres.dropStrayTestDatabases("qodpatrest")

  private val dbName = s"qodpatrest_test_${System.nanoTime()}"

  // Shared across every case in the suite (one database, one Liquibase run): assertions
  // must therefore filter the listing by the id they just minted and never assert an
  // absolute token count.
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
    * [[UserStore]] and `patAuth` composed into the guard exactly as `Main` does -- without it a PAT
    * presented as the credential would die at the guard's 401 instead of reaching the handler's
    * session-only gate that case 4 pins.
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
      // Whole row, as Main wires it: `enabled` is part of the handler's decision.
      patUserOf =
        Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
      patAuth =
        Some(new PatAuthenticator(pats, users.userById, u => List(UserGrant(u.tenant, u.role))))
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

  /** Flip `qodstate_user.enabled` on the PAT database directly: the login fixture is the in-memory
    * store, so this is how a session outlives the enablement of the row behind it.
    */
  private def setEnabled(tenant: Option[String], username: String, enabled: Boolean): Unit =
    val c = DriverManager.getConnection(
      TestPostgres.dbUrl(dbName),
      TestPostgres.pgUser,
      TestPostgres.pgPass
    )
    try
      val ps = c.prepareStatement(
        "UPDATE qodstate_user SET enabled = ? WHERE tenant IS NOT DISTINCT FROM ? AND username = ?"
      )
      try
        ps.setBoolean(1, enabled)
        ps.setString(2, tenant.orNull)
        ps.setString(3, username)
        ps.executeUpdate()
        ()
      finally ps.close()
    finally c.close()

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

      // Both request-shape rejections, pinned on the same session.
      val blankName = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/create",
        """{"name":"   "}""",
        apiKey = Some(session)
      )
      withClue(s"blank-name body: ${blankName.body()}") {
        blankName.statusCode() shouldBe 400
        errorCode(blankName.body()) should contain("invalid_name")
      }
      val pastExpiry = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/create",
        s"""{"name":"stale","expiresAt":"${java.time.Instant.now().minusSeconds(60)}"}""",
        apiKey = Some(session)
      )
      withClue(s"past-expiry body: ${pastExpiry.body()}") {
        pastExpiry.statusCode() shouldBe 400
        errorCode(pastExpiry.body()) should contain("invalid_expiry")
      }
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
  // 3b. delete: dead rows only, own rows only
  // ------------------------------------------------------------------

  "POST /api/auth/pat/delete" should "refuse a live token, remove a revoked one, and 404 the rest" in
    withHarness() { h =>
      val session = rootSession(h)
      val created = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/create",
        """{"name":"to-delete"}""",
        apiKey = Some(session)
      )
      created.statusCode() shouldBe 200
      val id = field(created.body(), "id").getOrElse(fail("no id in create response"))

      // Still live: delete is refused, revoke stays the only kill switch.
      val live = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/delete",
        s"""{"id":"$id"}""",
        apiKey = Some(session)
      )
      withClue(s"live-delete body: ${live.body()}") {
        live.statusCode() shouldBe 400
        errorCode(live.body()) should contain("pat_live")
      }

      post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/revoke",
        s"""{"id":"$id"}""",
        apiKey = Some(session)
      )
        .statusCode() shouldBe 200

      // Someone else's dead row is indistinguishable from a missing id.
      val bobSession = tenantSession(h, SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)
      val crossUser  = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/delete",
        s"""{"id":"$id"}""",
        apiKey = Some(bobSession)
      )
      withClue(s"cross-user delete body: ${crossUser.body()}") {
        crossUser.statusCode() shouldBe 404
        errorCode(crossUser.body()) should contain("not_found")
      }

      val deleted = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/delete",
        s"""{"id":"$id"}""",
        apiKey = Some(session)
      )
      withClue(s"delete body: ${deleted.body()}") {
        deleted.statusCode() shouldBe 200
      }

      // Gone from the listing entirely (revoke alone would keep it, flagged).
      val listed = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/list",
        // `list` declares no body input, so nothing would consume one: an unread
        // request body desynchronizes the reused HTTP/1.1 connection.
        "",
        apiKey = Some(session)
      )
      tokenEntries(listed.body())
        .filter(_.hcursor.get[String]("id").toOption.contains(id)) shouldBe empty

      // A second delete finds nothing.
      val again = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/delete",
        s"""{"id":"$id"}""",
        apiKey = Some(session)
      )
      withClue(s"second delete body: ${again.body()}") {
        again.statusCode() shouldBe 404
        errorCode(again.body()) should contain("not_found")
      }
    }

  // ------------------------------------------------------------------
  // 4. a PAT reaches its own subtree only
  //
  // This is a reversal of what this class used to guarantee (every PAT-presented
  // call refused 403 session_required). PatHandlers' scaladoc explains why the
  // reversal is safe: the revocation cascade means a leaked token cannot roll
  // forward past its own revocation, so what has to be pinned here is the
  // NARROWER surface a PAT gets instead of an outright refusal. Full chain
  // coverage (widen refusal, expiry clamp, depth cap, cascading revoke, sibling
  // and parent refusal) lives in PatChainRestSpec; this case only needs to prove
  // a session-focused reader that the four verbs are reachable, and bounded.
  // ------------------------------------------------------------------

  "a PAT presented as the credential" should
    "reach its own subtree on every PAT route, and nothing outside it" in withHarness() { h =>
      val session = rootSession(h)
      val created = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/create",
        """{"name":"agent"}""",
        apiKey = Some(session)
      )
      created.statusCode() shouldBe 200
      val raw = field(created.body(), "token").getOrElse(fail("no token in create response"))
      val id  = field(created.body(), "id").getOrElse(fail("no id in create response"))

      // create: a PAT may mint a child of ITSELF -- the reversal. Its child carries
      // `raw`'s id as parentId, so revoking `raw` (untested here, see
      // PatChainRestSpec) would take the successor with it.
      val successor = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/create",
        """{"name":"successor"}""",
        apiKey = Some(raw)
      )
      withClue(s"create-with-pat body: ${successor.body()}") {
        successor.statusCode() shouldBe 200
      }
      val successorId =
        field(successor.body(), "id").getOrElse(fail("no id in successor response"))

      // list: the subtree it minted, never its own row.
      val listed = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/list",
        // `list` declares no body input, so nothing would consume one: an unread
        // request body desynchronizes the reused HTTP/1.1 connection.
        "",
        apiKey = Some(raw)
      )
      withClue(s"list-with-pat body: ${listed.body()}")(listed.statusCode() shouldBe 200)
      val ids = tokenEntries(listed.body()).flatMap(_.hcursor.get[String]("id").toOption)
      ids should contain(successorId)
      ids should not contain id

      // revoke / delete: an id outside the subtree (here, one that never existed)
      // gets the SAME non-leak 404 an unknown id gets everywhere else on this
      // surface, never a distinct code that would out a real id as "not yours".
      val deniedRevoke = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/revoke",
        """{"id":"pat-whatever"}""",
        apiKey = Some(raw)
      )
      deniedRevoke.statusCode() shouldBe 404
      errorCode(deniedRevoke.body()) should contain("not_found")

      val deniedDelete = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/delete",
        """{"id":"pat-whatever"}""",
        apiKey = Some(raw)
      )
      deniedDelete.statusCode() shouldBe 404
      errorCode(deniedDelete.body()) should contain("not_found")
    }

  // ------------------------------------------------------------------
  // 5. no credential at all
  // ------------------------------------------------------------------

  "an anonymous caller" should "be refused 401 on every PAT route" in
    withHarness(staticApiKey = Some("k1")) { h =>
      List("create", "list", "revoke", "delete").foreach { verb =>
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

      // Delete rides the same profile allowlist as the other PAT verbs.
      val deleted = post(
        h.httpClient,
        s"${h.baseUrl}/api/auth/pat/delete",
        s"""{"id":"$id"}""",
        apiKey = Some(session)
      )
      withClue(s"delete body: ${deleted.body()}") {
        deleted.statusCode() shouldBe 200
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

  // ------------------------------------------------------------------
  // 8. a disabled owner cannot mint (use-time is already gated by PatAuthenticator)
  // ------------------------------------------------------------------

  "a disabled owner" should "be refused 403 account_disabled on a still-live session" in
    withHarness() { h =>
      // Session minted while the row was still enabled: the JWT is stateless, so it
      // outlives the disablement and the refusal has to come from the handler.
      val session = tenantSession(h, SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)
      setEnabled(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername, false)
      try
        val denied = post(
          h.httpClient,
          s"${h.baseUrl}/api/auth/pat/create",
          """{"name":"after-disable"}""",
          apiKey = Some(session)
        )
        withClue(s"create-while-disabled body: ${denied.body()}") {
          denied.statusCode() shouldBe 403
          errorCode(denied.body()) should contain("account_disabled")
        }
        // Same identity gate gates the read and the revoke.
        val deniedList = post(
          h.httpClient,
          s"${h.baseUrl}/api/auth/pat/list",
          "",
          apiKey = Some(session)
        )
        withClue(s"list-while-disabled body: ${deniedList.body()}") {
          deniedList.statusCode() shouldBe 403
          errorCode(deniedList.body()) should contain("account_disabled")
        }
      finally
        // The fixture rows are shared by the whole suite; leave bob usable.
        setEnabled(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername, true)
    }
