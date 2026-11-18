package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.auth.LoginRealm
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Integration test against the `qodstate_user` table, in a database of its own that the suite
  * creates and migrates with the real changelog, so it never depends on the state of another
  * database (a local manager's `qod` is migrated, a fresh server's is empty). Cancelled (not failed)
  * when Postgres is unreachable (`SL_TEST_PG_*`).
  */
class UserStoreGrantsSpec extends AnyFlatSpec, Matchers, BeforeAndAfterAll:

  TestPostgres.dropStrayTestDatabases("qodusg")

  private val dbName = s"qodusg_test_${System.nanoTime()}"
  private var created = false

  // Cancel (a clean per-test skip) rather than assume-at-construction, which aborts the whole
  // suite. The database is created and migrated once, by the first test that finds Postgres.
  private def requirePg(): Unit =
    TestPostgres.ensureReachable()
    if !created then
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
      created = true
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()

  private def url: String = TestPostgres.dbUrl(dbName)

  private lazy val store: UserStore =
    new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)

  private val u = s"grants-spec-${java.util.UUID.randomUUID().toString.take(6)}"

  override def afterAll(): Unit =
    if created then
      scala.util.Try(store.close())
      scala.util.Try(TestPostgres.dropDatabase(dbName))

  "grantsForIdentity" should "return an empty list when no row matches" in {
    requirePg()
    store.grantsForIdentity(LoginRealm.System, s"$u-nobody", None) shouldBe Nil
  }

  it should "surface a superuser grant when the user has tenant=NULL" in {
    requirePg()
    store.upsertUser(tenant = None, username = u, plaintext = "x", kind = "admin")
    val gs = store.grantsForIdentity(LoginRealm.System, u, None)
    gs should contain(UserGrant(None, "admin"))
  }

  it should "surface tenant-scoped grants" in {
    requirePg()
    val t = "t-grants-test"
    store.upsertUser(tenant = Some(t), username = u, plaintext = "x", kind = "admin")
    val gs = store.grantsForIdentity(LoginRealm.System, u, None)
    gs.map(_.tenant).toSet should contain(Some(t))
  }

  it should "fall back to email when the primary key has no match" in {
    requirePg()
    val email = s"$u@example.com"
    store.upsertUser(
      tenant = Some("t-grants-test"),
      username = email,
      plaintext = "x",
      kind = "admin"
    )
    val gs =
      store.grantsForIdentity(LoginRealm.System, identity = s"$u-not-real", email = Some(email))
    gs.map(_.tenant) should contain(Some("t-grants-test"))
  }

  // A password/role rotation through upsertUser must not silently flip a
  // deliberately-disabled row back on. upsertUser has no `enabled` setter, so
  // the disabled state is seeded directly and must survive the update.
  it should "not re-enable a disabled row on a password/role update" in {
    requirePg()
    val t = "t-enabled-preserve"
    store.upsertUser(tenant = Some(t), username = u, plaintext = "x", kind = "admin")
    withRawConn { c =>
      val ps =
        c.prepareStatement(
          "UPDATE qodstate_user SET enabled = false WHERE tenant = ? AND username = ?"
        )
      ps.setString(1, t)
      ps.setString(2, u)
      ps.executeUpdate()
      ps.close()
    }
    // Sanity: disabled row contributes no grant.
    store.grantsForIdentity(LoginRealm.System, u, None).filter(_.tenant.contains(t)) shouldBe Nil
    // Rotate password + role; enabled must stay false.
    store.upsertUser(tenant = Some(t), username = u, plaintext = "y", kind = "reader")
    store.grantsForIdentity(LoginRealm.System, u, None).filter(_.tenant.contains(t)) shouldBe Nil
  }

  // ---- realms: a tenant realm reads only that tenant's rows ----

  it should "bind only the named tenant's row in a tenant realm, never a same-named superuser or other-tenant row" in {
    requirePg()
    val name = s"$u-r1"
    store.upsertUser(tenant = None, username = name, plaintext = "x", kind = "admin")
    store.upsertUser(tenant = Some("t-realm-a"), username = name, plaintext = "x", kind = "admin")
    store.upsertUser(tenant = Some("t-realm-b"), username = name, plaintext = "x", kind = "admin")
    store.grantsForIdentity(LoginRealm.Tenant("t-realm-a"), name, None) shouldBe
      List(UserGrant(Some("t-realm-a"), "admin"))
  }

  it should "answer no grant in a tenant realm that holds no row for the identity, whatever other realms hold" in {
    requirePg()
    val name = s"$u-r2"
    store.upsertUser(tenant = None, username = name, plaintext = "x", kind = "admin")
    store.upsertUser(tenant = Some("t-realm-b"), username = name, plaintext = "x", kind = "admin")
    store.grantsForIdentity(LoginRealm.Tenant("t-realm-a"), name, None) shouldBe Nil
  }

  it should "keep the email fallback inside the tenant realm, whatever other realms hold" in {
    requirePg()
    val name  = s"$u-r3"
    val email = s"$u-r3@example.com"
    // Other realms only: a disabled identity row in t-realm-b, email-keyed rows as superuser and
    // in t-realm-b. None belongs to t-realm-a, so none denies, stops the fallback, or grants.
    store.upsertUser(tenant = Some("t-realm-b"), username = name, plaintext = "x", kind = "admin")
    disable("t-realm-b", name)
    store.upsertUser(tenant = None, username = email, plaintext = "x", kind = "admin")
    store.upsertUser(tenant = Some("t-realm-b"), username = email, plaintext = "x", kind = "admin")
    store.grantsForIdentity(LoginRealm.Tenant("t-realm-a"), name, Some(email)) shouldBe Nil
    // The realm's own email-keyed row is bound through the fallback.
    store.upsertUser(tenant = Some("t-realm-a"), username = email, plaintext = "x", kind = "admin")
    store.grantsForIdentity(LoginRealm.Tenant("t-realm-a"), name, Some(email)) shouldBe
      List(UserGrant(Some("t-realm-a"), "admin"))
  }

  it should "deny a tenant realm whose row is disabled, with no email fallback and no other realm's row" in {
    requirePg()
    val name  = s"$u-r4"
    val email = s"$u-r4@example.com"
    store.upsertUser(tenant = Some("t-realm-a"), username = name, plaintext = "x", kind = "admin")
    store.upsertUser(tenant = Some("t-realm-b"), username = name, plaintext = "x", kind = "admin")
    store.upsertUser(tenant = Some("t-realm-a"), username = email, plaintext = "x", kind = "admin")
    disable("t-realm-a", name)
    store.grantsForIdentity(LoginRealm.Tenant("t-realm-a"), name, Some(email)) shouldBe Nil
  }

  it should "match the identity in every realm in the system realm" in {
    requirePg()
    val name = s"$u-r5"
    store.upsertUser(tenant = None, username = name, plaintext = "x", kind = "admin")
    store.upsertUser(tenant = Some("t-realm-a"), username = name, plaintext = "x", kind = "admin")
    store.upsertUser(tenant = Some("t-realm-b"), username = name, plaintext = "x", kind = "user")
    store.grantsForIdentity(LoginRealm.System, name, None).toSet shouldBe Set(
      UserGrant(None, "admin"),
      UserGrant(Some("t-realm-a"), "admin"),
      UserGrant(Some("t-realm-b"), "user")
    )
  }

  private def disable(tenant: String, username: String): Unit =
    withRawConn { c =>
      val ps = c.prepareStatement(
        "UPDATE qodstate_user SET enabled = false WHERE tenant = ? AND username = ?"
      )
      ps.setString(1, tenant)
      ps.setString(2, username)
      ps.executeUpdate()
      ps.close()
    }

  private def withRawConn(f: java.sql.Connection => Unit): Unit =
    val c = java.sql.DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try f(c)
    finally c.close()
