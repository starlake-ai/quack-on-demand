package ai.starlake.quack.security

import ai.starlake.quack.edge.auth.{AuthFailure, AuthScope, DatabaseAuthenticator}
import ai.starlake.quack.edge.config.DatabaseAuthConfig
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import at.favre.lib.crypto.bcrypt.BCrypt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Enforcement of `qodstate_user.must_change_password` at the shared basic-auth boundary (REST
  * login and FlightSQL handshake both funnel through DatabaseAuthenticator):
  *
  *   - a flagged user presenting the CORRECT temp password gets the distinct typed
  *     [[AuthFailure.PasswordChangeRequired]] -- reachable only after bcrypt verified, so it leaks
  *     nothing to a caller who lacks the password;
  *   - disabled dominates: disabled + flagged still masks as the generic invalid-password failure
  *     (no account-state oracle);
  *   - a legacy 3-column operator query (pre-0028 shape) fails the login outright, mirroring the
  *     0022 mandatory-enabled-column precedent.
  */
class MustChangePasswordAuthSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodmca")

  private val DefaultSystemQuery =
    "SELECT password_hash, role, enabled, must_change_password FROM qodstate_user WHERE tenant IS NULL AND username = ? LIMIT 1"
  private val DefaultTenantQuery =
    "SELECT password_hash, role, enabled, must_change_password FROM qodstate_user WHERE tenant = ? AND username = ? LIMIT 1"
  private val LegacySystemQuery =
    "SELECT password_hash, role, enabled FROM qodstate_user WHERE tenant IS NULL AND username = ? LIMIT 1"

  private def authConfig(url: String, systemQuery: String = DefaultSystemQuery) =
    DatabaseAuthConfig(
      enabled = true,
      jdbcUrl = url,
      username = TestPostgres.pgUser,
      password = TestPostgres.pgPass,
      systemQuery = systemQuery,
      tenantQuery = DefaultTenantQuery
    )

  private def withFreshDb(test: (PostgresControlPlaneStore, String) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodmca_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store, url)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def hash(pw: String): String =
    BCrypt.withDefaults().hashToString(10, pw.toCharArray)

  "DatabaseAuthenticator" should "reject a flagged user with PasswordChangeRequired" in
    withFreshDb { (store, url) =>
      store.upsertUserWithHash(
        None,
        "root",
        hash("temp"),
        "admin",
        enabled = true,
        mustChangePassword = true
      )
      val auth = new DatabaseAuthenticator(authConfig(url), roleClaim = "role")
      try
        auth.authenticate(AuthScope.System, "root", "temp") shouldBe
          Left(AuthFailure.PasswordChangeRequired)
      finally auth.close()
    }

  it should "still mask a flagged wrong password as invalid credentials" in
    withFreshDb { (store, url) =>
      store.upsertUserWithHash(
        None,
        "root",
        hash("temp"),
        "admin",
        enabled = true,
        mustChangePassword = true
      )
      val auth = new DatabaseAuthenticator(authConfig(url), roleClaim = "role")
      try
        auth.authenticate(AuthScope.System, "root", "WRONG") shouldBe
          Left(AuthFailure.InvalidCredentials("Invalid password"))
      finally auth.close()
    }

  it should "mask disabled + flagged as invalid credentials (disabled dominates)" in
    withFreshDb { (store, url) =>
      store.upsertUserWithHash(
        None,
        "root",
        hash("temp"),
        "admin",
        enabled = false,
        mustChangePassword = true
      )
      val auth = new DatabaseAuthenticator(authConfig(url), roleClaim = "role")
      try
        auth.authenticate(AuthScope.System, "root", "temp") shouldBe
          Left(AuthFailure.InvalidCredentials("Invalid password"))
      finally auth.close()
    }

  it should "admit an unflagged user unchanged" in withFreshDb { (store, url) =>
    store.upsertUserWithHash(
      None,
      "root",
      hash("pw"),
      "admin",
      enabled = true,
      mustChangePassword = false
    )
    val auth = new DatabaseAuthenticator(authConfig(url), roleClaim = "role")
    try auth.authenticate(AuthScope.System, "root", "pw").isRight shouldBe true
    finally auth.close()
  }

  it should "hard-fail a legacy 3-column operator query" in withFreshDb { (store, url) =>
    store.upsertUserWithHash(None, "root", hash("pw"), "admin")
    val auth = new DatabaseAuthenticator(authConfig(url, LegacySystemQuery), roleClaim = "role")
    try
      auth.authenticate(AuthScope.System, "root", "pw") shouldBe
        Left(AuthFailure.InvalidCredentials("Invalid password"))
    finally auth.close()
  }

  "AuthFailure.PasswordChangeRequired.message" should "carry the FlightSQL wire text" in {
    // FlightEdgeServer surfaces this verbatim as the UNAUTHENTICATED description.
    AuthFailure.PasswordChangeRequired.message shouldBe
      "password change required; use POST /api/auth/change-password"
  }
