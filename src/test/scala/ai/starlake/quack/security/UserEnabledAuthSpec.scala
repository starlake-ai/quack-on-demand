// src/test/scala/ai/starlake/quack/security/UserEnabledAuthSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.auth.{AuthScope, DatabaseAuthenticator}
import ai.starlake.quack.edge.config.DatabaseAuthConfig
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import at.favre.lib.crypto.bcrypt.BCrypt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Enforcement tests for `qodstate_user.enabled` at the two Postgres-backed authentication
  * boundaries that do NOT go through PoolSupervisor:
  *
  *   - [[DatabaseAuthenticator]] (REST/UI password login): the default `systemQuery` /
  *     `tenantQuery` project `enabled` as a third column; a disabled user presenting the CORRECT
  *     password must be rejected with the exact same error as a wrong password so the response does
  *     not reveal which one failed. A custom operator query that projects only two columns must
  *     keep working (enabled defaults to true when not projected).
  *   - [[UserStore.grantsForIdentity]] (OIDC login): a disabled user must yield no grants, so the
  *     OIDC callback hits the not_provisioned gate instead of minting a session.
  *
  * Each test runs against a fresh Liquibase-migrated database (same pattern as
  * PostgresControlPlaneStoreSpec); cancelled cleanly when local Postgres is unreachable.
  *
  * The FlightSQL boundary (PoolSupervisor.authorizeHandshake) is covered in
  * [[FlightHandshakeSecuritySpec]].
  */
class UserEnabledAuthSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qoduen")

  // Mirror the application.conf defaults (three-column projection with `enabled`).
  private val DefaultSystemQuery =
    "SELECT password_hash, role, enabled FROM qodstate_user WHERE tenant IS NULL AND username = ? LIMIT 1"
  private val DefaultTenantQuery =
    "SELECT password_hash, role, enabled FROM qodstate_user WHERE tenant = ? AND username = ? LIMIT 1"

  // A pre-0022-style operator override that does not project `enabled`.
  private val LegacySystemQuery =
    "SELECT password_hash, role FROM qodstate_user WHERE tenant IS NULL AND username = ? LIMIT 1"

  private def authConfig(url: String, systemQuery: String = DefaultSystemQuery) =
    DatabaseAuthConfig(
      enabled = true,
      jdbcUrl = url,
      username = TestPostgres.pgUser,
      password = TestPostgres.pgPass,
      systemQuery = systemQuery,
      tenantQuery = DefaultTenantQuery
    )

  /** Fresh migrated DB; hands the test a control-plane store (to seed users) and the JDBC URL. */
  private def withFreshDb(test: (PostgresControlPlaneStore, String) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qoduen_test_${System.nanoTime()}"
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

  // ------------------------------------------------------------------
  // DatabaseAuthenticator (REST/UI password login)
  // ------------------------------------------------------------------

  "DatabaseAuthenticator" should "authenticate an enabled user with the correct password" in
    withFreshDb { (store, url) =>
      store.upsertUserWithHash(None, "root", hash("rootpw"), "admin", enabled = true)
      val auth = new DatabaseAuthenticator(authConfig(url), roleClaim = "role")
      try
        val r = auth.authenticate(AuthScope.System, "root", "rootpw")
        r.isRight shouldBe true
        r.toOption.get.username shouldBe "root"
      finally auth.close()
    }

  it should "reject a DISABLED user presenting the CORRECT password, with the same error as a wrong password" in
    withFreshDb { (store, url) =>
      store.upsertUserWithHash(None, "root", hash("rootpw"), "admin", enabled = false)
      val auth = new DatabaseAuthenticator(authConfig(url), roleClaim = "role")
      try
        val disabledResult = auth.authenticate(AuthScope.System, "root", "rootpw")
        val badPwResult    = auth.authenticate(AuthScope.System, "root", "wrong-password")
        disabledResult.isLeft shouldBe true
        // Indistinguishable from a wrong password on the wire.
        disabledResult shouldBe badPwResult
      finally auth.close()
    }

  it should "reject a disabled tenant-scoped user through tenantQuery too" in
    withFreshDb { (store, url) =>
      import ai.starlake.quack.model.Tenant
      store.upsertTenant(Tenant(id = "acme", displayName = "acme"))
      store.upsertUserWithHash(Some("acme"), "alice", hash("alicepw"), "admin", enabled = false)
      val auth = new DatabaseAuthenticator(authConfig(url), roleClaim = "role")
      try
        val r = auth.authenticate(AuthScope.Tenant("acme"), "alice", "alicepw")
        r.isLeft shouldBe true
      finally auth.close()
    }

  it should "keep working with a legacy custom query that does not project enabled" in
    withFreshDb { (store, url) =>
      store.upsertUserWithHash(None, "root", hash("rootpw"), "admin", enabled = true)
      val auth = new DatabaseAuthenticator(
        authConfig(url, systemQuery = LegacySystemQuery),
        roleClaim = "role"
      )
      try
        val r = auth.authenticate(AuthScope.System, "root", "rootpw")
        r.isRight shouldBe true
      finally auth.close()
    }

  // ------------------------------------------------------------------
  // UserStore.grantsForIdentity (OIDC login)
  // ------------------------------------------------------------------

  "UserStore.grantsForIdentity" should "yield no grants for a disabled user" in
    withFreshDb { (store, url) =>
      store.upsertUserWithHash(None, "carol", hash("x"), "admin", enabled = false)
      val us = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass, poolSize = 2)
      try us.grantsForIdentity("carol", None) shouldBe Nil
      finally us.close()
    }

  it should "still yield grants for an enabled user" in
    withFreshDb { (store, url) =>
      store.upsertUserWithHash(None, "dave", hash("x"), "admin", enabled = true)
      val us = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass, poolSize = 2)
      try us.grantsForIdentity("dave", None).map(_.role) shouldBe List("admin")
      finally us.close()
    }

  it should "yield no grants when the username row is disabled, even if an enabled email-keyed row exists for the same person" in
    withFreshDb { (store, url) =>
      // A person provisioned twice: once under a username, once under their
      // email address (a common OIDC-provisioning pattern). The username
      // row is disabled; the email row is enabled. Disabling by username
      // must not be bypassable via the email fallback.
      store.upsertUserWithHash(None, "erin", hash("x"), "admin", enabled = false)
      store.upsertUserWithHash(None, "erin@example.com", hash("x"), "admin", enabled = true)
      val us = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass, poolSize = 2)
      try us.grantsForIdentity("erin", Some("erin@example.com")) shouldBe Nil
      finally us.close()
    }
