package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{
  FederatedSourceStore,
  LiquibaseRunner,
  PostgresControlPlaneStore
}
import cats.effect.unsafe.implicits.global
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

class FederatedSourceHandlersSpec extends AnyFlatSpec with Matchers with OptionValues:

  TestPostgres.dropStrayTestDatabases("qodh")

  /** Yields the store + (tenantName, tenantDbName) => tenantDbId resolver over a
    * freshly-migrated throwaway Postgres, so a test can build a handler with a
    * custom `scopeOf`.
    */
  private def withEnv(
      test: (FederatedSourceStore, (String, String) => Option[String], String) => Unit
  ): Unit =
    if !TestPostgres.reachable then
      cancel(
        s"local Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}; skipping"
      )
    val dbName = s"qodh_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url  = TestPostgres.dbUrl(dbName)
      val user = TestPostgres.pgUser
      val pass = TestPostgres.pgPass
      new LiquibaseRunner(url, user, pass).run()
      val cp = new PostgresControlPlaneStore(url, user, pass)
      // Seed a tenant + tenant-db. The handlers are called with tenant "acme"
      // and the resolver matches on tenant id, so the id IS the slug "acme"
      // (post slug-id refactor: the slug is the one tenant key).
      cp.upsertTenant(Tenant(id = "acme", displayName = "acme", disabled = false))
      cp.upsertTenantDb(
        TenantDb(
          id = "td-1",
          tenantId = "acme",
          name = "acme_prod",
          kind = TenantDbKind.InMemory,
          metastore = Map.empty,
          dataPath = ""
        )
      )
      val fs = new FederatedSourceStore(url, user, pass)
      // resolver: looks up tenantDbId from (tenantName, tenantDbName)
      val resolver: (String, String) => Option[String] = (tenantName, tenantDbName) =>
        cp.listTenants().find(_.id == tenantName).flatMap { t =>
          cp.listTenantDbs(t.id).find(_.name == tenantDbName).map(_.id)
        }
      test(fs, resolver, "td-1")
    finally Try(TestPostgres.dropDatabase(dbName))

  private def withHandlers(test: (FederatedSourceHandlers, String) => Unit): Unit =
    withEnv((fs, resolver, tdId) => test(new FederatedSourceHandlers(fs, resolver), tdId))

  // 1. POST source -> 200, GET it back, alias matches
  "FederatedSourceHandlers.createSource" should
    "create a source and retrieve it by alias" in withHandlers { (h, _) =>
      val created = h
        .createSource(
          "acme",
          "acme_prod",
          FederatedSourceCreateRequest(alias = "fedpg", setupSql = "INSTALL postgres;"),
          None
        )
        .unsafeRunSync()
      created.isRight shouldBe true
      val r = created.toOption.value
      r.alias shouldBe "fedpg"
      r.setupSql shouldBe "INSTALL postgres;"

      val got = h.getSource("acme", "acme_prod", "fedpg").unsafeRunSync()
      got.isRight shouldBe true
      got.toOption.value.alias shouldBe "fedpg"
    }

  // 2. POST source twice with same alias -> upsert, still one row
  it should "upsert on duplicate alias (update in place)" in withHandlers { (h, _) =>
    h.createSource(
      "acme",
      "acme_prod",
      FederatedSourceCreateRequest(alias = "dup", setupSql = "v1"),
      None
    ).unsafeRunSync()
    val second = h
      .createSource(
        "acme",
        "acme_prod",
        FederatedSourceCreateRequest(alias = "dup", setupSql = "v2"),
        None
      )
      .unsafeRunSync()
    second.isRight shouldBe true
    second.toOption.value.setupSql shouldBe "v2"

    val list = h.listSources("acme", "acme_prod").unsafeRunSync().toOption.value
    list.sources.count(_.alias == "dup") shouldBe 1
  }

  // 3. POST secret with value -> 200; GET secrets list shows value redacted
  "FederatedSourceHandlers.upsertSecret" should
    "store a secret and return it with value redacted" in withHandlers { (h, _) =>
      h.createSource(
        "acme",
        "acme_prod",
        FederatedSourceCreateRequest(alias = "src1", setupSql = "..."),
        None
      ).unsafeRunSync()

      val r = h
        .upsertSecret(
          "acme",
          "acme_prod",
          "src1",
          FederatedSecretUpsertRequest(name = "PG_PASSWORD", value = Some("hunter2")),
          None
        )
        .unsafeRunSync()
      r.isRight shouldBe true
      r.toOption.value.value shouldBe Some("***REDACTED***")

      val list = h.listSecrets("acme", "acme_prod", "src1").unsafeRunSync().toOption.value
      list.secrets should have size 1
      list.secrets.head.name shouldBe "PG_PASSWORD"
      list.secrets.head.value shouldBe Some("***REDACTED***")
    }

  // Bonus: deleteSource removes the source
  "FederatedSourceHandlers.deleteSource" should
    "remove an existing source" in withHandlers { (h, _) =>
      h.createSource(
        "acme",
        "acme_prod",
        FederatedSourceCreateRequest(alias = "todel", setupSql = "..."),
        None
      ).unsafeRunSync()
      val del = h.deleteSource("acme", "acme_prod", "todel", None).unsafeRunSync()
      del.isRight shouldBe true

      val list = h.listSources("acme", "acme_prod").unsafeRunSync().toOption.value
      list.sources.exists(_.alias == "todel") shouldBe false
    }

  it should "return 404 for an unknown alias" in withHandlers { (h, _) =>
    val del = h.deleteSource("acme", "acme_prod", "ghost", None).unsafeRunSync()
    del.isLeft shouldBe true
    del.swap.toOption.value._1.code shouldBe 404
  }

  // Bonus: unknown tenant-db -> 404
  "FederatedSourceHandlers.listSources" should
    "return 404 for an unknown tenant-db" in withHandlers { (h, _) =>
      val r = h.listSources("acme", "no_such_db").unsafeRunSync()
      r.isLeft shouldBe true
      r.swap.toOption.value._1.code shouldBe 404
    }

  // --- externalRef secret authoring is superuser-only -----------------------
  // Regression guard for the privilege-escalation finding: a tenant admin
  // must not be able to author an `env:` / KMS externalRef secret (which
  // resolves from the MANAGER's own trust domain at spawn), while a superuser
  // and a value-backed tenant secret both stay allowed.

  private val AdminTok = "admin-token"
  private val SuperTok = "super-token"
  private val scopes: String => Option[SessionScope] = {
    case `AdminTok` => Some(SessionScope(superuser = false, manageableTenants = Set("acme")))
    case `SuperTok` => Some(SessionScope.Superuser)
    case _          => None
  }

  private def seedSource(fs: FederatedSourceStore, resolver: (String, String) => Option[String])(
      scopeOf: String => Option[SessionScope]
  ): FederatedSourceHandlers =
    val h = new FederatedSourceHandlers(fs, resolver, scopeOf = scopeOf)
    h.createSource(
      "acme",
      "acme_prod",
      FederatedSourceCreateRequest(alias = "fedpg", setupSql = "INSTALL postgres;"),
      Some(AdminTok)
    ).unsafeRunSync().isRight shouldBe true
    h

  "FederatedSourceHandlers.upsertSecret" should
    "reject an externalRef secret from a non-superuser (tenant admin) session with 403" in
    withEnv { (fs, resolver, _) =>
      val h = seedSource(fs, resolver)(scopes)
      val r = h
        .upsertSecret(
          "acme",
          "acme_prod",
          "fedpg",
          FederatedSecretUpsertRequest(name = "X", externalRef = Some("env:QOD_SESSION_JWT_SECRET")),
          Some(AdminTok)
        )
        .unsafeRunSync()
      r.isLeft shouldBe true
      val (code, err) = r.swap.toOption.value
      code.code shouldBe 403
      err.error shouldBe "superuser_required"
      // Nothing was written.
      h.listSecrets("acme", "acme_prod", "fedpg")
        .unsafeRunSync()
        .toOption
        .value
        .secrets shouldBe empty
    }

  it should "allow a value-backed secret from a non-superuser (tenant admin) session" in
    withEnv { (fs, resolver, _) =>
      val h = seedSource(fs, resolver)(scopes)
      val r = h
        .upsertSecret(
          "acme",
          "acme_prod",
          "fedpg",
          FederatedSecretUpsertRequest(name = "PW", value = Some("my-own-pw")),
          Some(AdminTok)
        )
        .unsafeRunSync()
      r.isRight shouldBe true
    }

  it should "allow an externalRef secret from a superuser session" in
    withEnv { (fs, resolver, _) =>
      val h = seedSource(fs, resolver)(scopes)
      val r = h
        .upsertSecret(
          "acme",
          "acme_prod",
          "fedpg",
          FederatedSecretUpsertRequest(name = "X", externalRef = Some("env:SL_QOD_SECRET_FOO")),
          Some(SuperTok)
        )
        .unsafeRunSync()
      r.isRight shouldBe true
    }

  it should "allow an externalRef secret for a static-key / open-mode caller (no resolvable scope)" in
    withEnv { (fs, resolver, _) =>
      // Default scopeOf resolves nothing -> perimeter is the gate, handler admits.
      val h = seedSource(fs, resolver)(_ => None)
      val r = h
        .upsertSecret(
          "acme",
          "acme_prod",
          "fedpg",
          FederatedSecretUpsertRequest(name = "X", externalRef = Some("env:SL_QOD_SECRET_FOO")),
          None
        )
        .unsafeRunSync()
      r.isRight shouldBe true
    }
