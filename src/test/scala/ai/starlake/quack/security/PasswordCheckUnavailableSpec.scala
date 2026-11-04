package ai.starlake.quack.security

import ai.starlake.quack.edge.auth.{AuthFailure, AuthScope, DatabaseAuthenticator}
import ai.starlake.quack.edge.config.DatabaseAuthConfig
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import at.favre.lib.crypto.bcrypt.BCrypt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.util.Try

/** A password the database authenticator could not check is not a wrong password. Each check holds
  * one of 5 connections and a check gives up after 5 s waiting for one, so while they are busy a
  * correct login used to read `UNAUTHENTICATED` "Database error", which sent users after their
  * password. Against a real database, cancelled cleanly when local Postgres is unreachable.
  */
class PasswordCheckUnavailableSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodpcu")

  private val Pool = 5

  private val SystemQuery =
    "SELECT password_hash, kind, enabled, must_change_password FROM qodstate_user " +
      "WHERE tenant IS NULL AND username = ? LIMIT 1"

  private def withFreshDb(test: (PostgresControlPlaneStore, String) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodpcu_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store, url)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def authenticator(url: String, systemQuery: String) =
    new DatabaseAuthenticator(
      DatabaseAuthConfig(
        enabled = true,
        jdbcUrl = url,
        username = TestPostgres.pgUser,
        password = TestPostgres.pgPass,
        systemQuery = systemQuery,
        tenantQuery = SystemQuery
      ),
      roleClaim = "role"
    )

  private def seedRoot(store: PostgresControlPlaneStore): Unit =
    val hash = BCrypt.withDefaults().hashToString(4, "rootpw".toCharArray)
    store.upsertUserWithHash(None, "root", hash, "admin", enabled = true)

  "A correct password" should "be told to retry when no connection comes free, then get in" in
    withFreshDb { (store, url) =>
      seedRoot(store)
      // Every check holds its connection for 7 s, past the 5 s a waiting check gives up after.
      val slow = SystemQuery.replace("FROM qodstate_user", "FROM qodstate_user, pg_sleep(7)")
      val auth = authenticator(url, slow)
      try
        val started = new CountDownLatch(Pool)
        val holders = (1 to Pool).map { _ =>
          val thread = new Thread(() =>
            started.countDown()
            auth.authenticate(AuthScope.System, "root", "rootpw")
            ()
          )
          thread.start()
          thread
        }
        started.await(5, TimeUnit.SECONDS) shouldBe true
        // Long enough for all 5 to have taken their connection, well inside their 7 s.
        Thread.sleep(1000)
        auth.authenticate(AuthScope.System, "root", "rootpw") shouldBe Left(AuthFailure.Unavailable)
        holders.foreach(_.join(15000))
        holders.exists(_.isAlive) shouldBe false
      finally auth.close()
    }

  "A wrong password" should "still be a wrong password while the database answers" in
    withFreshDb { (store, url) =>
      seedRoot(store)
      val auth = authenticator(url, SystemQuery)
      try
        auth.authenticate(AuthScope.System, "root", "nope") shouldBe Left(
          AuthFailure.InvalidCredentials("Invalid password")
        )
      finally auth.close()
    }
