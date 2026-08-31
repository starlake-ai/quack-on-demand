// src/test/scala/ai/starlake/quack/security/UserLockPatSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.ondemand.auth.{PatAuthenticator, TokenRestriction}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** The self-lock guard's PAT arm, pinned end-to-end (real Postgres-backed PAT, not a session JWT).
  *
  * `UserHandlers.updateUser`'s self-lock check resolves the caller through the composed `sessionOf`
  * (session JWT, then PAT via [[PatAuthenticator.sessionOf]]), then looks the caller back up in the
  * SUPERVISOR's control-plane store (`sup.findUser`) by `(rowTenant, username)` - i.e. the
  * harness's in-memory [[SecurityFixtures.Fixture]] store, NOT the Postgres-backed [[UserStore]]
  * the PAT itself resolves against. A PAT minted for alice therefore self-locks against
  * `fix.aliceUserId` (the in-memory row), which is what this spec targets.
  *
  * Modeled on [[PatApiAdmissionSpec]]: same `TestPostgres` fixture (fresh scratch database,
  * Liquibase-migrated, real `PatStore` + `UserStore` + `PatAuthenticator` with row-only grants),
  * kept in its own file because [[UserLockSpec]] is harness-only (in-memory `sessionOf`) today.
  */
class UserLockPatSpec
    extends AnyFlatSpec
    with Matchers
    with SecurityHttpHelpers
    with BeforeAndAfterAll:

  TestPostgres.dropStrayTestDatabases("qoduserlock")

  private val dbName = s"qoduserlock_test_${System.nanoTime()}"

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
      // Row-only grants, matching PatApiAdmissionSpec: a PAT is bound to one user row.
      patAuth = new PatAuthenticator(pats, users.userById, u => List(UserGrant(u.tenant, u.role)))
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

  /** Mint a live PAT for `(tenant, username)` directly against the Postgres-backed store. */
  private def mintFor(tenant: Option[String], username: String): String =
    val uid = users
      .userIdOf(tenant, username)
      .getOrElse(fail(s"fixture user $username not found"))
    val (_, raw) = pats.mint(uid, s"spec-$username", TokenRestriction.Unrestricted, None, 0)
    raw

  /** Static key is always configured: reaching the handler with a PAT proves the guard's PAT arm
    * admitted it, exactly like PatApiAdmissionSpec.
    */
  private def withHarness(
      body: (ManagerServerHarness.Harness, SecurityFixtures.Fixture) => Unit
  ): Unit =
    TestPostgres.ensureReachable()
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(
      fix.store,
      staticApiKey = Some("lock-pat-key"),
      patStore = Some(pats),
      patUserOf =
        Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
      patAuth = Some(patAuth)
    )
    try body(h, fix)
    finally h.shutdown()

  "the self-lock guard's PAT arm" should "refuse alice's own PAT locking her own row" in withHarness {
    (h, fix) =>
      val raw = mintFor(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)

      val denied = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/update",
        s"""{"id":"${fix.aliceUserId}","enabled":false}""",
        apiKey = Some(raw)
      )
      withClue(s"PAT self-lock body: ${denied.body()}") {
        denied.statusCode() shouldBe 400
        errorCode(denied.body()) should contain("cannot_lock_self")
      }
  }

  it should "still let that same PAT lock a different user (bob)" in withHarness { (h, fix) =>
    val raw = mintFor(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)

    val locked = post(
      h.httpClient,
      s"${h.baseUrl}/api/user/update",
      s"""{"id":"${fix.bobUserId}","enabled":false}""",
      apiKey = Some(raw)
    )
    withClue(s"PAT lock-bob body: ${locked.body()}") {
      locked.statusCode() shouldBe 200
    }
    locked.body() should include(""""enabled":false""")
  }
