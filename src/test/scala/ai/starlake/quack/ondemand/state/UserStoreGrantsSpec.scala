package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.auth.LoginRealm
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Integration test against the `qodstate_user` table. Cancelled (not failed) when `QOD_PG_HOST` is
  * not set. Uses the same default-metastore env-vars the rest of the manager runs against, so a
  * local `docker compose up -d postgres` is enough.
  */
class UserStoreGrantsSpec extends AnyFlatSpec, Matchers, BeforeAndAfterAll:

  private val pgHost = sys.env.getOrElse("QOD_PG_HOST", "")

  private val pgPort     = sys.env.getOrElse("QOD_PG_PORT", "5432")
  private val pgUser     = sys.env.getOrElse("QOD_PG_USER", "postgres")
  private val pgPassword = sys.env.getOrElse("QOD_PG_PASSWORD", "azizam")
  private val pgDb       = sys.env.getOrElse("QOD_PG_DBNAME", "qod")

  // Cancel (a clean per-test skip) rather than assume-at-construction, which
  // aborts the whole suite. `store` is lazy so the Hikari pool is built only
  // once a test has confirmed Postgres is configured.
  private def requirePg(): Unit =
    if pgHost.isEmpty then cancel("QOD_PG_HOST unset; skipping integration test")

  private lazy val store: UserStore = UserStore.fromDefaultMetastore(
    Map(
      "pgHost"     -> pgHost,
      "pgPort"     -> pgPort,
      "pgUser"     -> pgUser,
      "pgPassword" -> pgPassword,
      "dbName"     -> pgDb,
      "schemaName" -> "public",
      "dataPath"   -> "/tmp/x"
    )
  )

  private val u = s"grants-spec-${java.util.UUID.randomUUID().toString.take(6)}"

  override def afterAll(): Unit =
    // Best-effort cleanup; ignore failures so reports stay readable when PG is unavailable
    // between tests. Skip entirely when PG was never configured (all tests cancelled).
    if pgHost.isEmpty then ()
    else
      scala.util.Try {
        val c = java.sql.DriverManager.getConnection(
          s"jdbc:postgresql://$pgHost:$pgPort/$pgDb",
          pgUser,
          pgPassword
        )
        try
          // Every username this spec creates starts with the random prefix `u`.
          val ps = c.prepareStatement("DELETE FROM qodstate_user WHERE username LIKE ?")
          ps.setString(1, s"$u%")
          ps.executeUpdate()
          ps.close()
        finally c.close()
      }

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
    val c = java.sql.DriverManager.getConnection(
      s"jdbc:postgresql://$pgHost:$pgPort/$pgDb",
      pgUser,
      pgPassword
    )
    try f(c)
    finally c.close()
