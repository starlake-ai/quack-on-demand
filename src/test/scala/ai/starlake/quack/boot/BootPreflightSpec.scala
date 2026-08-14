package ai.starlake.quack.boot

import ai.starlake.quack.ondemand.state.LiquibaseRunner
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.util.Try

/** Boot-time gates for Phase 2 account lockout:
  *   - `checkLockoutSmtp` is a pure function: lockout enabled with no reachable SMTP relay is
  *     refused, because a locked-out user with no working reset-email path is stranded.
  *   - a fresh-DB migration check that changelog 0030 actually lands `failed_attempts` /
  *     `locked_at` on `qodstate_user`.
  */
class BootPreflightSpec extends AnyFlatSpec with Matchers:

  "checkLockoutSmtp" should "refuse lockout enabled with no SMTP host configured" in {
    val result = BootPreflight.checkLockoutSmtp(lockoutEnabled = true, smtpHost = None)
    result match
      case Left(msg) => msg should include("QOD_SMTP_HOST")
      case Right(()) => fail("expected Left naming QOD_SMTP_HOST")
  }

  it should "refuse lockout enabled with an empty-string SMTP host" in {
    val result = BootPreflight.checkLockoutSmtp(lockoutEnabled = true, smtpHost = Some(""))
    result match
      case Left(msg) => msg should include("QOD_SMTP_HOST")
      case Right(()) => fail("expected Left naming QOD_SMTP_HOST")
  }

  it should "allow lockout enabled with a configured SMTP host" in {
    BootPreflight.checkLockoutSmtp(
      lockoutEnabled = true,
      smtpHost = Some("smtp.example.com")
    ) shouldBe Right(())
  }

  it should "allow lockout disabled regardless of SMTP configuration" in {
    BootPreflight.checkLockoutSmtp(lockoutEnabled = false, smtpHost = None) shouldBe Right(())
  }

  TestPostgres.dropStrayTestDatabases("qodlck")

  private def withFreshDb(test: String => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodlck_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      test(url)
    finally Try(TestPostgres.dropDatabase(dbName))

  "changelog 0030" should "add failed_attempts and locked_at to qodstate_user" in withFreshDb {
    url =>
      val conn = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        val rs      = conn.getMetaData.getColumns(null, null, "qodstate_user", null)
        val columns = scala.collection.mutable.Set.empty[String]
        try while rs.next() do columns += rs.getString("COLUMN_NAME").toLowerCase
        finally rs.close()
        columns should contain("failed_attempts")
        columns should contain("locked_at")
        columns should contain("email")
      finally conn.close()
  }
