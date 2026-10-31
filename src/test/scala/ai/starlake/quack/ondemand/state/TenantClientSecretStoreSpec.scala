package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Tenant, TenantAcl}
import ai.starlake.quack.ondemand.auth.ParkedSecretRefs
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import java.sql.DriverManager
import scala.util.Try

/** `qodstate_tenant.auth_client_secret` (Liquibase 0048): the store writes it with `auth_config` in
  * one statement on every path, and the changeset parks references stored before it.
  */
class TenantClientSecretStoreSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodtcs")

  private val Issuer = "https://kc.example.com/realms/acme"

  private val oidc = Tenant(
    "acme",
    "Acme",
    authProvider = "keycloak",
    authConfig = Map("issuerUrl" -> Issuer, "clientId" -> "qod"),
    authClientSecret = Some("kc-s3cr3t")
  )

  private def withStore(test: PostgresControlPlaneStore => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodtcs_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def runSql(url: String, sql: String): Unit =
    val c = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      val st = c.createStatement()
      try st.execute(sql)
      finally st.close()
    finally c.close()

  private def row(store: PostgresControlPlaneStore, id: String): Tenant =
    store.listTenants().find(_.id == id).get

  "PostgresControlPlaneStore" should
    "round-trip authClientSecret through upsert, list and snapshot" in withStore { store =>
      store.upsertTenant(oidc)
      row(store, "acme") shouldBe oidc
      store.snapshot().tenants.find(_.id == "acme") shouldBe Some(oidc)
    }

  it should "store no secret for a tenant written without one" in withStore { store =>
    store.upsertTenant(Tenant("globex"))
    row(store, "globex").authClientSecret shouldBe None
  }

  it should "write the secret with auth_config on a full-row upsert of an existing tenant" in
    withStore { store =>
      store.upsertTenant(oidc)
      val moved = oidc.copy(
        authConfig = oidc.authConfig.updated("issuerUrl", "https://other.example.com"),
        authClientSecret = None
      )
      store.upsertTenant(moved)
      row(store, "acme") shouldBe moved
      store.upsertTenant(moved.copy(authClientSecret = Some("n3w-s3cr3t")))
      row(store, "acme").authClientSecret shouldBe Some("n3w-s3cr3t")
    }

  it should "insert the secret in createTenantWithBuiltins" in withStore { store =>
    store.createTenantWithBuiltins(oidc, BuiltinRbac.rowsFor(oidc.id))
    row(store, "acme").authClientSecret shouldBe Some("kc-s3cr3t")
  }

  it should "keep the secret through a column-scoped acl write" in withStore { store =>
    store.upsertTenant(oidc)
    store.updateTenantAcl("acme", TenantAcl(mode = Some("opa"), opaUrl = Some("http://opa:8181")))
    row(store, "acme").authClientSecret shouldBe Some("kc-s3cr3t")
  }

  "changeset 0048" should
    "park a clientSecretRef stored before it and leave a tenant without one untouched" in {
      TestPostgres.ensureReachable()
      val dbName = s"qodtcs_test_${System.nanoTime()}"
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
      try
        val url = TestPostgres.dbUrl(dbName)
        // Migrate to the schema just before 0048, seed rows as an older manager wrote them, then
        // run the full changelog (same two-stage pattern as FleetServerStoreSpec's 0041 case).
        val master =
          Paths.get(getClass.getResource("/db/changelog/db.changelog-master.yaml").toURI)
        val upTo = master.resolveSibling("master-before-0048-test.yaml")
        val text = Files.readString(master)
        val cut  = text.indexOf("  - include:\n      file: db/changelog/0048-")
        cut should be > 0
        Files.writeString(upTo, text.substring(0, cut))
        try
          new LiquibaseRunner(
            url,
            TestPostgres.pgUser,
            TestPostgres.pgPass,
            "db/changelog/master-before-0048-test.yaml"
          ).run()
        finally Files.deleteIfExists(upTo)
        runSql(
          url,
          "INSERT INTO qodstate_tenant (id, display_name, auth_provider, auth_config) VALUES " +
            s"""('acme', 'acme', 'keycloak', '{"issuerUrl":"$Issuer","clientId":"qod",""" +
            """"clientSecretRef":"env:ACME_OIDC_SECRET"}'::jsonb), """ +
            """('globex', 'globex', 'google', '{"clientId":"g"}'::jsonb)"""
        )
        new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
        val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
        try
          val acme = row(store, "acme")
          acme.authConfig shouldBe Map(
            "issuerUrl"                -> Issuer,
            "clientId"                 -> "qod",
            ParkedSecretRefs.ParkedKey -> "env:ACME_OIDC_SECRET"
          )
          acme.authClientSecret shouldBe None
          row(store, "globex").authConfig shouldBe Map("clientId" -> "g")
          // The boot check names that tenant only, with its restore step.
          val msgs = ParkedSecretRefs.messages(store.listTenants())
          msgs.size shouldBe 1
          msgs.head should include("tenant 'acme'")
          msgs.head should include("--auth-config clientSecretRef=env:ACME_OIDC_SECRET")
        finally store.close()
      finally Try(TestPostgres.dropDatabase(dbName))
    }
