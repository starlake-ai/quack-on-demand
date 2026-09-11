package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.util.Try

/** Integration test for [[ControlPlaneBootstrap]]. Requires a local Postgres reachable with the
  * SL_TEST_PG_* env vars (defaults: localhost:5432, user `postgres`, password `azizam`).
  */
class ControlPlaneBootstrapSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qod_cpb")

  private def meta(dbName: String, extra: Map[String, String] = Map.empty): Map[String, String] =
    Map(
      "pgHost"     -> TestPostgres.pgHost,
      "pgPort"     -> TestPostgres.pgPort.toString,
      "pgUser"     -> TestPostgres.pgUser,
      "pgPassword" -> TestPostgres.pgPass,
      "dbName"     -> dbName
    ) ++ extra

  private def exists(name: String): Boolean =
    val c = DriverManager.getConnection(TestPostgres.adminUrl, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      val ps = c.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")
      try
        ps.setString(1, name)
        val rs = ps.executeQuery()
        try rs.next()
        finally rs.close()
      finally ps.close()
    finally c.close()

  private def withFreshName(test: String => Unit): Unit =
    TestPostgres.ensureReachable()
    val name = s"qod_cpb_test_${System.nanoTime()}"
    try test(name)
    finally Try(TestPostgres.dropDatabase(name))

  "ControlPlaneBootstrap" should "create the control-plane database when the server is up but the database is missing" in withFreshName {
    name =>
      exists(name) shouldBe false
      ControlPlaneBootstrap.ensureDatabase(meta(name)) shouldBe Right(true)
      exists(name) shouldBe true
  }

  it should "be a no-op when the database already exists" in withFreshName { name =>
    ControlPlaneBootstrap.ensureDatabase(meta(name)) shouldBe Right(true)
    ControlPlaneBootstrap.ensureDatabase(meta(name)) shouldBe Right(false)
    exists(name) shouldBe true
  }

  it should "refuse when the server is unreachable instead of trying to create" in withFreshName {
    name =>
      // Port 1 is never a Postgres; connectTimeout keeps the failure fast.
      val result =
        ControlPlaneBootstrap.ensureDatabase(meta(name, Map("pgPort" -> "1")), timeoutSec = 2)
      result.isLeft shouldBe true
      exists(name) shouldBe false
  }

  it should "refuse on bad credentials without creating the database" in withFreshName { name =>
    // On a trust-auth dev Postgres every password is accepted, so there is no auth failure to
    // classify; the scenario only exists under md5/scram. Cancel rather than fake it.
    val acceptsAnyPassword = Try {
      val c = DriverManager.getConnection(
        TestPostgres.adminUrl,
        TestPostgres.pgUser,
        "definitely-wrong"
      )
      c.close()
      true
    }.getOrElse(false)
    if acceptsAnyPassword then
      cancel("local Postgres accepts any password (trust auth); skipping bad-credentials case")
    val result = ControlPlaneBootstrap.ensureDatabase(meta(name) + ("pgPassword" -> "definitely-wrong"))
    result.isLeft shouldBe true
    // An auth failure must never be classified as "database missing": nothing gets created.
    exists(name) shouldBe false
  }
