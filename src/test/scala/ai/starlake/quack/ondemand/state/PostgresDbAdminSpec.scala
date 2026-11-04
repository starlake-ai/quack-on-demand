package ai.starlake.quack.ondemand.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.util.Try

/** Integration test for [[PostgresDbAdmin]]. Requires a local Postgres
  * reachable with the SL_TEST_PG_* env vars (defaults: localhost:5432,
  * user `postgres`, password `azizam`). */
class PostgresDbAdminSpec extends AnyFlatSpec with Matchers:

  ai.starlake.quack.ondemand.state.testkit.TestPostgres.dropStrayTestDatabases("qod_adm")


  private val pgHost = sys.env.getOrElse("SL_TEST_PG_HOST",     "localhost")
  private val pgPort = sys.env.getOrElse("SL_TEST_PG_PORT",     "5432")
  private val pgUser = sys.env.getOrElse("SL_TEST_PG_USER",     "postgres")
  private val pgPass = sys.env.getOrElse("SL_TEST_PG_PASSWORD", "azizam")

  Class.forName("org.postgresql.Driver")

  private def adminUrl: String = s"jdbc:postgresql://$pgHost:$pgPort/postgres"

  private def pgReachable: Boolean =
    Try {
      val c = DriverManager.getConnection(adminUrl, pgUser, pgPass)
      try c.isValid(2) finally c.close()
    }.getOrElse(false)

  private def exists(name: String): Boolean =
    val c = DriverManager.getConnection(adminUrl, pgUser, pgPass)
    try
      val ps = c.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")
      try
        ps.setString(1, name)
        val rs = ps.executeQuery()
        try rs.next() finally rs.close()
      finally ps.close()
    finally c.close()

  private def withFreshName(test: (PostgresDbAdmin, String) => Unit): Unit =
    if !pgReachable then cancel(
      s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"
    )
    val name  = s"qod_adm_test_${System.nanoTime()}"
    val admin = new PostgresDbAdmin(pgHost, pgPort, pgUser, pgPass)
    try test(admin, name)
    finally Try(admin.dropDatabase(name))

  "PostgresDbAdmin" should "create a database that did not exist" in withFreshName { (admin, name) =>
    exists(name) shouldBe false
    admin.createDatabase(name) shouldBe Right(())
    exists(name) shouldBe true
  }

  it should "be idempotent on a second create" in withFreshName { (admin, name) =>
    admin.createDatabase(name) shouldBe Right(())
    // Second call must not throw and the DB stays in place.
    admin.createDatabase(name) shouldBe Right(())
    exists(name) shouldBe true
  }

  it should "drop a database that exists" in withFreshName { (admin, name) =>
    admin.createDatabase(name) shouldBe Right(())
    exists(name) shouldBe true
    admin.dropDatabase(name) shouldBe Right(())
    exists(name) shouldBe false
  }

  it should "be idempotent on a second drop" in withFreshName { (admin, name) =>
    admin.createDatabase(name) shouldBe Right(())
    admin.dropDatabase(name)   shouldBe Right(())
    admin.dropDatabase(name)   shouldBe Right(())
    exists(name) shouldBe false
  }

  private def inDb(name: String)(sql: String*): Unit =
    val c = DriverManager.getConnection(s"jdbc:postgresql://$pgHost:$pgPort/$name", pgUser, pgPass)
    try
      val st = c.createStatement()
      try sql.foreach(st.execute)
      finally st.close()
    finally c.close()

  "PostgresDbAdmin.holdsAnything" should "read a database that does not exist as holding nothing" in
    withFreshName { (admin, name) =>
      admin.holdsAnything(name) shouldBe Right(false)
      exists(name) shouldBe false
    }

  it should "read a fresh database as holding nothing" in withFreshName { (admin, name) =>
    admin.createDatabase(name) shouldBe Right(())
    admin.holdsAnything(name) shouldBe Right(false)
  }

  it should "see every kind of object a database can hold" in {
    List(
      "CREATE TABLE t (id int)",
      "CREATE TABLE ducklake_metadata (key text)",
      "CREATE VIEW v AS SELECT 1 AS id",
      "CREATE SEQUENCE s",
      "CREATE SCHEMA own",
      "CREATE SCHEMA own; CREATE TABLE own.t (id int)",
      "CREATE FUNCTION f() RETURNS int LANGUAGE sql AS 'SELECT 1'",
      "CREATE TYPE ty AS ENUM ('a')",
      "SELECT lo_create(0)"
    ).foreach { ddl =>
      withFreshName { (admin, name) =>
        admin.createDatabase(name) shouldBe Right(())
        inDb(name)(ddl)
        withClue(ddl)(admin.holdsAnything(name) shouldBe Right(true))
      }
    }
  }

  it should "surface a Left when it cannot read the database" in:
    val bad = new PostgresDbAdmin("127.0.0.1", "1", pgUser, pgPass)
    bad.holdsAnything(s"qod_adm_bad_${System.nanoTime()}").isLeft shouldBe true

  it should "surface a Left when the admin server is unreachable" in:
    // Use an unreachable host:port instead of bad credentials -- local
    // dev Postgres often uses `trust` auth and would happily accept a
    // bogus password, making the credential variant a false negative.
    val bad = new PostgresDbAdmin("127.0.0.1", "1", pgUser, pgPass)
    val out = bad.createDatabase(s"qod_adm_bad_${System.nanoTime()}")
    out.isLeft shouldBe true
