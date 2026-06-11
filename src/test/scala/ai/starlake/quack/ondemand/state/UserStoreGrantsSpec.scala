package ai.starlake.quack.ondemand.state

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Integration test against the `qodstate_user` table. Skipped when `QOD_PG_HOST` is not set. Uses
  * the same default-metastore env-vars the rest of the manager runs against, so a local
  * `docker compose up -d postgres` is enough.
  */
class UserStoreGrantsSpec extends AnyFlatSpec, Matchers, BeforeAndAfterAll:

  private val pgHost = sys.env.getOrElse("QOD_PG_HOST", "")
  assume(pgHost.nonEmpty, "QOD_PG_HOST unset; skipping integration test")

  private val pgPort     = sys.env.getOrElse("QOD_PG_PORT", "5432")
  private val pgUser     = sys.env.getOrElse("QOD_PG_USER", "postgres")
  private val pgPassword = sys.env.getOrElse("QOD_PG_PASSWORD", "azizam")
  private val pgDb       = sys.env.getOrElse("QOD_PG_DBNAME", "qod")

  private val store: UserStore = UserStore.fromDefaultMetastore(
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
    // between tests.
    scala.util.Try {
      val c = java.sql.DriverManager.getConnection(
        s"jdbc:postgresql://$pgHost:$pgPort/$pgDb",
        pgUser,
        pgPassword
      )
      try
        val ps = c.prepareStatement("DELETE FROM qodstate_user WHERE username = ?")
        ps.setString(1, u)
        ps.executeUpdate()
        ps.close()
      finally c.close()
    }

  "grantsForIdentity" should "return an empty list when no row matches" in {
    store.grantsForIdentity(s"$u-nobody", None) shouldBe Nil
  }

  it should "surface a superuser grant when the user has tenant=NULL" in {
    store.upsertUser(tenant = None, username = u, plaintext = "x", role = "admin")
    val gs = store.grantsForIdentity(u, None)
    gs should contain(UserGrant(None, "admin"))
  }

  it should "surface tenant-scoped grants" in {
    val t = "t-grants-test"
    store.upsertUser(tenant = Some(t), username = u, plaintext = "x", role = "admin")
    val gs = store.grantsForIdentity(u, None)
    gs.map(_.tenant).toSet should contain(Some(t))
  }

  it should "fall back to email when the primary key has no match" in {
    val email = s"$u@example.com"
    store.upsertUser(
      tenant = Some("t-grants-test"),
      username = email,
      plaintext = "x",
      role = "admin"
    )
    val gs = store.grantsForIdentity(identity = s"$u-not-real", email = Some(email))
    gs.map(_.tenant) should contain(Some("t-grants-test"))
  }