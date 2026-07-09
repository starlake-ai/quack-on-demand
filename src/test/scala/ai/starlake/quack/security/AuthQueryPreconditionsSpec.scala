// src/test/scala/ai/starlake/quack/security/AuthQueryPreconditionsSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.auth.AuthQueryPreconditions
import ai.starlake.quack.edge.config.DatabaseAuthConfig
import ai.starlake.quack.ondemand.state.LiquibaseRunner
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.util.Try

/** Config-load-time gate for `DatabaseAuthConfig.systemQuery` / `tenantQuery`: both queries must
  * project exactly three columns `(password_hash, role, enabled)`. This mirrors, at startup, the
  * runtime enforcement pinned by `UserEnabledAuthSpec` ("reject authentication when a legacy custom
  * query does not project the enabled column").
  */
class AuthQueryPreconditionsSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodaqp")

  private val ThreeColumnQuery =
    "SELECT password_hash, role, enabled FROM qodstate_user WHERE tenant IS NULL AND username = ? LIMIT 1"
  private val TwoColumnQuery =
    "SELECT password_hash, role FROM qodstate_user WHERE tenant IS NULL AND username = ? LIMIT 1"

  private def config(systemQuery: String, tenantQuery: String) =
    DatabaseAuthConfig(
      enabled = true,
      jdbcUrl = "unused",
      username = "unused",
      password = "unused",
      systemQuery = systemQuery,
      tenantQuery = tenantQuery
    )

  private def withFreshDb(test: String => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodaqp_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      test(url)
    finally Try(TestPostgres.dropDatabase(dbName))

  "AuthQueryPreconditions" should "accept queries that project all three required columns" in
    withFreshDb { url =>
      val conn = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        AuthQueryPreconditions.validate(
          conn,
          config(ThreeColumnQuery, ThreeColumnQuery)
        ) shouldBe Right(())
      finally conn.close()
    }

  it should "refuse a systemQuery that omits the enabled column" in
    withFreshDb { url =>
      val conn = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        AuthQueryPreconditions
          .validate(conn, config(TwoColumnQuery, ThreeColumnQuery))
          .isLeft shouldBe true
      finally conn.close()
    }

  it should "refuse a tenantQuery that omits the enabled column" in
    withFreshDb { url =>
      val conn = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        AuthQueryPreconditions
          .validate(conn, config(ThreeColumnQuery, TwoColumnQuery))
          .isLeft shouldBe true
      finally conn.close()
    }

  it should "skip the check entirely when database auth is disabled" in
    withFreshDb { url =>
      val conn = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        val cfg = config(TwoColumnQuery, TwoColumnQuery).copy(enabled = false)
        AuthQueryPreconditions.validate(conn, cfg) shouldBe Right(())
      finally conn.close()
    }
