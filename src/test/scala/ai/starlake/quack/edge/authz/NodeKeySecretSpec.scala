package ai.starlake.quack.edge.authz

import ai.starlake.quack.ondemand.state.LiquibaseRunner
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** The node-key secret: QOD_NODE_KEY_SECRET when set, else one value created once in the control
  * plane and read back by every boot. The Postgres case cancels without the test Postgres
  * (SL_TEST_PG_*).
  */
class NodeKeySecretSpec extends AnyFlatSpec with Matchers:

  // Reaps any `qodnk_test_%` database an interrupted run left behind.
  TestPostgres.dropStrayTestDatabases("qodnk")

  "a configured secret" should "win without touching the control plane" in {
    val secret = "c" * 40
    NodeKeySecret.resolve(secret, throw new AssertionError("control plane read")) shouldBe
      Right(secret)
  }

  it should "be refused when shorter than the minimum" in {
    NodeKeySecret.resolve("short", throw new AssertionError("control plane read")) match
      case Left(msg) => msg should include("QOD_NODE_KEY_SECRET")
      case other     => fail(s"expected Left, got $other")
  }

  "an unset secret" should "be created once in the control plane and read back after" in {
    TestPostgres.ensureReachable()
    val db = s"qodnk_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$db"""")
    try
      new LiquibaseRunner(TestPostgres.dbUrl(db), TestPostgres.pgUser, TestPostgres.pgPass).run()
      val hc = new HikariConfig()
      hc.setJdbcUrl(TestPostgres.dbUrl(db))
      hc.setUsername(TestPostgres.pgUser)
      hc.setPassword(TestPostgres.pgPass)
      hc.setMaximumPoolSize(2)
      val ds = new HikariDataSource(hc)
      try
        val first  = NodeKeySecret.resolve("", ds)
        val second = NodeKeySecret.resolve("  ", ds)
        first.isRight shouldBe true
        first.toOption.get.length should be >= NodeKeySecret.MinLength
        second shouldBe first
      finally ds.close()
    finally Try(TestPostgres.dropDatabase(db))
  }
