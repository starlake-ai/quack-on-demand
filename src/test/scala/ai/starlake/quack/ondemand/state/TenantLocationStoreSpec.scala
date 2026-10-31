package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Tenant, TenantLocation}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

class TenantLocationStoreSpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodtl")

  private def withStore(test: PostgresControlPlaneStore => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodtl_${java.util.UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        store.upsertTenant(Tenant("acme"))
        store.upsertTenant(Tenant("globex"))
        test(store)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def local(id: String, tenant: String = "acme", loc: String = "/srv/qod/acme/in/") =
    TenantLocation(id, tenant, "local", loc, 1761200000000L, Some("alice"))

  "insertTenantLocation" should "round-trip a location" in withStore { store =>
    store.insertTenantLocation(local("a" * 32)) shouldBe true
    store.listTenantLocations("acme") shouldBe List(local("a" * 32))
    store.listTenantLocations("globex") shouldBe Nil
    store.listAllTenantLocations() shouldBe List(local("a" * 32))
  }

  it should "refuse the same location twice for one tenant, not for another" in withStore { store =>
    store.insertTenantLocation(local("a" * 32)) shouldBe true
    store.insertTenantLocation(local("b" * 32)) shouldBe false
    store.insertTenantLocation(local("c" * 32, tenant = "globex")) shouldBe true
    store.listTenantLocations("acme").map(_.id) shouldBe List("a" * 32)
  }

  it should "refuse a kind other than local, s3 included" in withStore { store =>
    List("s3", "gs").foreach { k =>
      withClue(k)(
        a[java.sql.SQLException] should be thrownBy
          store.insertTenantLocation(local(k.head.toString * 32).copy(kind = k))
      )
    }
  }

  "deleteTenantLocation" should "delete only a location of the named tenant" in withStore { store =>
    store.insertTenantLocation(local("a" * 32, tenant = "globex", loc = "/srv/qod/globex/in/"))
    store.deleteTenantLocation("acme", "a" * 32) shouldBe None
    store.listTenantLocations("globex").map(_.id) shouldBe List("a" * 32)
    store.deleteTenantLocation("globex", "a" * 32).map(_.location) shouldBe Some(
      "/srv/qod/globex/in/"
    )
    store.listTenantLocations("globex") shouldBe Nil
  }

  "a tenant's locations" should "go with the tenant" in withStore { store =>
    store.insertTenantLocation(local("a" * 32))
    store.deleteTenant("acme")
    store.listAllTenantLocations() shouldBe Nil
  }

  they should "bump the tenant's policy version on insert and delete, and only that tenant's" in
    withStore { store =>
      val before = store.policyVersions()
      store.insertTenantLocation(local("a" * 32))
      val afterInsert = store.policyVersions()
      // Every bump takes the next value of one global sequence (Liquibase 0050), so a version
      // moves up by an unknown step: only its direction is pinned.
      afterInsert("acme") should be > before("acme")
      afterInsert("globex") shouldBe before("globex")
      store.deleteTenantLocation("acme", "a" * 32)
      store.policyVersions()("acme") should be > afterInsert("acme")
      store.policyVersions()("globex") shouldBe before("globex")
    }
