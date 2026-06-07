package ai.starlake.quack.ondemand.api

import ai.starlake.quack.model.{Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{FederatedSourceStore, LiquibaseRunner, PostgresControlPlaneStore}
import cats.effect.unsafe.implicits.global
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

class FederatedSourceHandlersSpec extends AnyFlatSpec with Matchers with OptionValues:

  TestPostgres.dropStrayTestDatabases("qodh")

  private def withHandlers(test: (FederatedSourceHandlers, String) => Unit): Unit =
    if !TestPostgres.reachable then cancel(
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
      // Seed a tenant + tenant-db
      cp.upsertTenant(Tenant(id = "t-1", name = "acme", displayName = "acme", disabled = false))
      cp.upsertTenantDb(TenantDb(
        id        = "td-1",
        tenantId  = "t-1",
        name      = "acme_prod",
        kind      = TenantDbKind.InMemory,
        metastore = Map.empty,
        dataPath  = ""
      ))
      val fs  = new FederatedSourceStore(url, user, pass)
      // resolver: looks up tenantDbId from (tenantName, tenantDbName)
      val resolver: (String, String) => Option[String] = (tenantName, tenantDbName) =>
        cp.listTenants().find(_.name == tenantName).flatMap { t =>
          cp.listTenantDbs(t.id).find(_.name == tenantDbName).map(_.id)
        }
      val h = new FederatedSourceHandlers(fs, resolver)
      test(h, "td-1")
    finally Try(TestPostgres.dropDatabase(dbName))

  // 1. POST source -> 200, GET it back, alias matches
  "FederatedSourceHandlers.createSource" should
    "create a source and retrieve it by alias" in withHandlers { (h, _) =>
    val created = h.createSource("acme", "acme_prod",
      FederatedSourceCreateRequest(alias = "fedpg", setupSql = "INSTALL postgres;")
    ).unsafeRunSync()
    created.isRight shouldBe true
    val r = created.toOption.value
    r.alias    shouldBe "fedpg"
    r.setupSql shouldBe "INSTALL postgres;"

    val got = h.getSource("acme", "acme_prod", "fedpg").unsafeRunSync()
    got.isRight shouldBe true
    got.toOption.value.alias shouldBe "fedpg"
  }

  // 2. POST source twice with same alias -> upsert, still one row
  it should "upsert on duplicate alias (update in place)" in withHandlers { (h, _) =>
    h.createSource("acme", "acme_prod",
      FederatedSourceCreateRequest(alias = "dup", setupSql = "v1")
    ).unsafeRunSync()
    val second = h.createSource("acme", "acme_prod",
      FederatedSourceCreateRequest(alias = "dup", setupSql = "v2")
    ).unsafeRunSync()
    second.isRight shouldBe true
    second.toOption.value.setupSql shouldBe "v2"

    val list = h.listSources("acme", "acme_prod").unsafeRunSync().toOption.value
    list.sources.count(_.alias == "dup") shouldBe 1
  }

  // 3. POST secret with value -> 200; GET secrets list shows value redacted
  "FederatedSourceHandlers.upsertSecret" should
    "store a secret and return it with value redacted" in withHandlers { (h, _) =>
    h.createSource("acme", "acme_prod",
      FederatedSourceCreateRequest(alias = "src1", setupSql = "...")
    ).unsafeRunSync()

    val r = h.upsertSecret("acme", "acme_prod", "src1",
      FederatedSecretUpsertRequest(name = "PG_PASSWORD", value = Some("hunter2"))
    ).unsafeRunSync()
    r.isRight shouldBe true
    r.toOption.value.value shouldBe Some("***REDACTED***")

    val list = h.listSecrets("acme", "acme_prod", "src1").unsafeRunSync().toOption.value
    list.secrets should have size 1
    list.secrets.head.name  shouldBe "PG_PASSWORD"
    list.secrets.head.value shouldBe Some("***REDACTED***")
  }

  // 4. exportYaml contains "***REDACTED***", NOT the raw value
  "FederatedSourceHandlers.exportYaml" should
    "redact secret values in export" in withHandlers { (h, _) =>
    h.createSource("acme", "acme_prod",
      FederatedSourceCreateRequest(alias = "fedpg", setupSql = "INSTALL postgres;")
    ).unsafeRunSync()
    h.upsertSecret("acme", "acme_prod", "fedpg",
      FederatedSecretUpsertRequest(name = "PWD", value = Some("supersecret"))
    ).unsafeRunSync()

    val yaml = h.exportYaml("acme", "acme_prod").unsafeRunSync().toOption.value
    yaml should     include("***REDACTED***")
    yaml should not include "supersecret"
    yaml should     include("fedpg")
  }

  // 5. importYaml: roundtrip after export reuses the existing value (no error)
  "FederatedSourceHandlers.importYaml" should
    "roundtrip: export then import succeeds and reuses existing secret value" in withHandlers { (h, _) =>
    h.createSource("acme", "acme_prod",
      FederatedSourceCreateRequest(alias = "fedpg", setupSql = "INSTALL postgres;")
    ).unsafeRunSync()
    h.upsertSecret("acme", "acme_prod", "fedpg",
      FederatedSecretUpsertRequest(name = "PWD", value = Some("original"))
    ).unsafeRunSync()

    val yaml = h.exportYaml("acme", "acme_prod").unsafeRunSync().toOption.value
    val result = h.importYaml("acme", "acme_prod", yaml).unsafeRunSync()
    result.isRight shouldBe true
    val summary = result.toOption.value
    summary.sources shouldBe 1
    summary.secrets shouldBe 1

    // Secret value should be preserved (reused from existing)
    val list = h.listSecrets("acme", "acme_prod", "fedpg").unsafeRunSync().toOption.value
    list.secrets.head.value shouldBe Some("***REDACTED***")
  }

  // 6. importYaml: fresh import with "***REDACTED***" for non-existing secret -> 400
  it should "return 400 when REDACTED value has no existing secret to reuse" in withHandlers { (h, _) =>
    val yaml =
      """federatedSources:
        |  - alias: newsrc
        |    disabled: false
        |    setupSql: "INSTALL postgres;"
        |    secrets:
        |      - name: PWD
        |        value: "***REDACTED***"
        |""".stripMargin

    val result = h.importYaml("acme", "acme_prod", yaml).unsafeRunSync()
    result.isLeft shouldBe true
    val (status, err) = result.swap.toOption.value
    status.code shouldBe 400
    err.message should include("no existing value to reuse")
  }

  // 7. importYaml: duplicate alias in payload -> 400
  it should "return 400 on duplicate alias in import payload" in withHandlers { (h, _) =>
    val yaml =
      """federatedSources:
        |  - alias: dup
        |    disabled: false
        |    setupSql: "v1"
        |    secrets: []
        |  - alias: dup
        |    disabled: false
        |    setupSql: "v2"
        |    secrets: []
        |""".stripMargin

    val result = h.importYaml("acme", "acme_prod", yaml).unsafeRunSync()
    result.isLeft shouldBe true
    val (status, err) = result.swap.toOption.value
    status.code shouldBe 400
    err.message should include("duplicate alias")
  }

  // Bonus: deleteSource removes the source
  "FederatedSourceHandlers.deleteSource" should
    "remove an existing source" in withHandlers { (h, _) =>
    h.createSource("acme", "acme_prod",
      FederatedSourceCreateRequest(alias = "todel", setupSql = "...")
    ).unsafeRunSync()
    val del = h.deleteSource("acme", "acme_prod", "todel").unsafeRunSync()
    del.isRight shouldBe true

    val list = h.listSources("acme", "acme_prod").unsafeRunSync().toOption.value
    list.sources.exists(_.alias == "todel") shouldBe false
  }

  it should "return 404 for an unknown alias" in withHandlers { (h, _) =>
    val del = h.deleteSource("acme", "acme_prod", "ghost").unsafeRunSync()
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