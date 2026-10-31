package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Tenant, UserS3Credential}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

class UserS3CredentialStoreSpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodus3")

  private final case class Ids(alice: String, gina: String, root: String)

  private def withStore(test: (PostgresControlPlaneStore, Ids) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodus3_${java.util.UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        store.upsertTenant(Tenant("acme"))
        store.upsertTenant(Tenant("globex"))
        val ids = Ids(
          store.upsertUserWithHash(Some("acme"), "alice", "x", "user"),
          store.upsertUserWithHash(Some("globex"), "gina", "x", "user"),
          store.upsertUserWithHash(None, "root", "x", "admin")
        )
        test(store, ids)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def cred(id: String, user: String, scope: String = "s3://acme-etl/", key: String = "K1") =
    UserS3Credential(id, user, scope, key, "s1", None, None, None, None, 0L, 0L)

  "putUserS3Credential" should "insert a credential and read every field back" in withStore {
    (store, ids) =>
      val full = cred("a" * 32, ids.alice).copy(
        sessionToken = Some("tok"),
        region = Some("eu-west-3"),
        endpoint = Some("http://127.0.0.1:9000"),
        urlStyle = Some("path")
      )
      val stored = store.putUserS3Credential(full)
      stored.copy(createdAt = 0L, updatedAt = 0L) shouldBe full
      stored.createdAt should be > 0L
      stored.updatedAt shouldBe stored.createdAt
      store.listUserS3Credentials(ids.alice) shouldBe List(stored)
      store.listUserS3Credentials(ids.gina) shouldBe Nil
      store.listAllUserS3Credentials() shouldBe List(stored)
  }

  it should "replace a scope the user already holds, keeping its id and moving updatedAt" in
    withStore { (store, ids) =>
      val first  = store.putUserS3Credential(cred("a" * 32, ids.alice))
      val second = store.putUserS3Credential(cred("b" * 32, ids.alice, key = "K2"))
      second.id shouldBe first.id
      second.accessKeyId shouldBe "K2"
      second.createdAt shouldBe first.createdAt
      second.updatedAt should be > first.updatedAt
      // Within the same millisecond too: the digest a handle carries must change.
      val third = store.putUserS3Credential(cred("c" * 32, ids.alice, key = "K3"))
      third.updatedAt should be > second.updatedAt
      store.listUserS3Credentials(ids.alice).map(_.accessKeyId) shouldBe List("K3")
    }

  it should "keep one user's scope apart from another user's" in withStore { (store, ids) =>
    store.putUserS3Credential(cred("a" * 32, ids.alice))
    store.putUserS3Credential(cred("b" * 32, ids.gina))
    store.listAllUserS3Credentials().map(c => (c.userId, c.id)).toSet shouldBe
      Set((ids.alice, "a" * 32), (ids.gina, "b" * 32))
  }

  it should "refuse an id that is not 32 lowercase hex digits" in withStore { (store, ids) =>
    a[java.sql.SQLException] should be thrownBy store.putUserS3Credential(cred("X" * 32, ids.alice))
  }

  "deleteUserS3Credential" should "delete only a credential of the named user" in withStore {
    (store, ids) =>
      store.putUserS3Credential(cred("a" * 32, ids.gina))
      store.deleteUserS3Credential(ids.alice, "a" * 32) shouldBe None
      store.listUserS3Credentials(ids.gina).map(_.id) shouldBe List("a" * 32)
      store.deleteUserS3Credential(ids.gina, "a" * 32).map(_.scope) shouldBe Some("s3://acme-etl/")
      store.listUserS3Credentials(ids.gina) shouldBe Nil
  }

  "a user's credentials" should "go with the user" in withStore { (store, ids) =>
    store.putUserS3Credential(cred("a" * 32, ids.alice))
    store.deleteUser(ids.alice)
    store.listAllUserS3Credentials() shouldBe Nil
  }

  they should "bump their user's tenant's policy version, and a superuser's bump nothing" in
    withStore { (store, ids) =>
      val before = store.policyVersions()
      store.putUserS3Credential(cred("a" * 32, ids.alice))
      val afterInsert = store.policyVersions()
      // One global sequence (Liquibase 0050): only the direction is pinned.
      afterInsert("acme") should be > before("acme")
      afterInsert("globex") shouldBe before("globex")
      store.putUserS3Credential(cred("b" * 32, ids.alice, key = "K2"))
      val afterReplace = store.policyVersions()
      afterReplace("acme") should be > afterInsert("acme")
      store.deleteUserS3Credential(ids.alice, "a" * 32)
      store.policyVersions()("acme") should be > afterReplace("acme")
      val quiet = store.policyVersions()
      store.putUserS3Credential(cred("c" * 32, ids.root))
      store.policyVersions() shouldBe quiet
    }
