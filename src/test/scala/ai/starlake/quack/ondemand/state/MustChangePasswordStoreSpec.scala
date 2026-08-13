package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Persistence semantics of `qodstate_user.must_change_password` (changelog 0028) through the
  * shared [[UserUpsert]] and both store facades. The flag mirrors `enabled`: an explicit `Some(b)`
  * writes it, `None` preserves it on update and leaves the column default (false) on insert.
  */
class MustChangePasswordStoreSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodmcp")

  private def withFreshDb(test: (PostgresControlPlaneStore, UserStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodmcp_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store     = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val userStore = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store, userStore)
      finally
        userStore.close()
        store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  "UserStore.upsertUser" should "default must_change_password to false on insert" in
    withFreshDb { (store, users) =>
      users.upsertUser(None, "alice", "pw1", "admin")
      store.findUser(None, "alice").get.mustChangePassword shouldBe false
    }

  it should "write the flag when Some(true) is passed" in withFreshDb { (store, users) =>
    users.upsertUser(None, "alice", "pw1", "admin", mustChangePassword = Some(true))
    store.findUser(None, "alice").get.mustChangePassword shouldBe true
  }

  it should "preserve the stored flag when None is passed on update" in
    withFreshDb { (store, users) =>
      users.upsertUser(None, "alice", "pw1", "admin", mustChangePassword = Some(true))
      users.upsertUser(None, "alice", "pw2", "admin")
      store.findUser(None, "alice").get.mustChangePassword shouldBe true
    }

  it should "clear the flag when Some(false) is passed on update" in
    withFreshDb { (store, users) =>
      users.upsertUser(None, "alice", "pw1", "admin", mustChangePassword = Some(true))
      users.upsertUser(None, "alice", "pw2", "admin", mustChangePassword = Some(false))
      store.findUser(None, "alice").get.mustChangePassword shouldBe false
    }

  "ControlPlaneStore.upsertUserWithHash" should "write the flag" in withFreshDb { (store, _) =>
    store.upsertUserWithHash(
      None,
      "bob",
      "$2a$10$abcdefghijklmnopqrstuv",
      "user",
      enabled = true,
      mustChangePassword = true
    )
    store.findUser(None, "bob").get.mustChangePassword shouldBe true
    store.getUserById(store.findUser(None, "bob").get.id).get.mustChangePassword shouldBe true
    store.listUsers(None).find(_.username == "bob").get.mustChangePassword shouldBe true
  }
