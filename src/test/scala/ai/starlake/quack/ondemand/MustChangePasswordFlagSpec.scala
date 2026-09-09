package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Admin-side semantics of the must-change-password flag through PoolSupervisor: create-time set,
  * reset-time set/clear (an unflagged reset clears a pending flag), role-only updates leave it
  * untouched, and Some(true) without a password is a flag-only update (edge/admin's `ALTER USER ...
  * REQUIRE PASSWORD CHANGE`) that persists the flag against the row's EXISTING hash rather than
  * requiring a fresh credential.
  */
class MustChangePasswordFlagSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodmcf")

  private def withSup(test: (PoolSupervisor, PostgresControlPlaneStore, UserStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodmcf_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store     = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val userStore = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val sup       = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
      try test(sup, store, userStore)
      finally
        userStore.close()
        store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  "createUser" should "persist the flag when requested" in withSup { (sup, store, users) =>
    sup
      .createUser(None, "alice", "temp", "admin", users, mustChangePassword = true)
      .unsafeRunSync()
      .isRight shouldBe true
    store.findUser(None, "alice").get.mustChangePassword shouldBe true
  }

  it should "default the flag to false" in withSup { (sup, store, users) =>
    sup.createUser(None, "alice", "pw", "admin", users).unsafeRunSync().isRight shouldBe true
    store.findUser(None, "alice").get.mustChangePassword shouldBe false
  }

  "updateUserPassword" should "clear a pending flag on an unflagged reset" in
    withSup { (sup, store, users) =>
      sup
        .createUser(None, "alice", "temp", "admin", users, mustChangePassword = true)
        .unsafeRunSync()
      val id = store.findUser(None, "alice").get.id
      sup.updateUserPassword(id, Some("fresh"), None, users).unsafeRunSync().isRight shouldBe true
      store.findUser(None, "alice").get.mustChangePassword shouldBe false
    }

  it should "set the flag on a flagged reset" in withSup { (sup, store, users) =>
    sup.createUser(None, "alice", "pw", "admin", users).unsafeRunSync()
    val id = store.findUser(None, "alice").get.id
    sup
      .updateUserPassword(id, Some("temp2"), None, users, mustChangePassword = Some(true))
      .unsafeRunSync()
      .isRight shouldBe true
    store.findUser(None, "alice").get.mustChangePassword shouldBe true
  }

  it should "leave the flag untouched on a role-only update" in withSup { (sup, store, users) =>
    sup
      .createUser(None, "alice", "temp", "admin", users, mustChangePassword = true)
      .unsafeRunSync()
    val id = store.findUser(None, "alice").get.id
    sup.updateUserPassword(id, None, Some("admin"), users).unsafeRunSync().isRight shouldBe true
    store.findUser(None, "alice").get.mustChangePassword shouldBe true
  }

  it should "accept Some(true) without a password as a flag-only update" in withSup {
    (sup, store, users) =>
      sup.createUser(None, "alice", "pw", "admin", users).unsafeRunSync()
      val id         = store.findUser(None, "alice").get.id
      val hashBefore = store.getPasswordHash(None, "alice")
      sup
        .updateUserPassword(id, None, None, users, mustChangePassword = Some(true))
        .unsafeRunSync()
        .isRight shouldBe true
      // The flag is written, the credential is untouched (same hash the create wrote), and
      // `enabled` - a sibling column reachable through the same rewrite branch - is not
      // incidentally flipped by a request that never mentioned it.
      store.findUser(None, "alice").get.mustChangePassword shouldBe true
      store.getPasswordHash(None, "alice") shouldBe hashBefore
      store.findUser(None, "alice").get.enabled shouldBe true
  }

  it should "accept Some(false) enabled without a password as a flag-only update" in withSup {
    (sup, store, users) =>
      sup
        .createUser(None, "alice", "pw", "admin", users, mustChangePassword = true)
        .unsafeRunSync()
      val id         = store.findUser(None, "alice").get.id
      val hashBefore = store.getPasswordHash(None, "alice")
      sup
        .updateUserPassword(id, None, None, users, enabled = Some(false))
        .unsafeRunSync()
        .isRight shouldBe true
      // The enabled flag flips, the credential is untouched (same hash the create wrote),
      // and mustChangePassword - a sibling column reachable through the same rewrite
      // branch - SURVIVES as true (seeded non-default, so a regression that hardcoded
      // the default false on this path would fail here).
      store.findUser(None, "alice").get.enabled shouldBe false
      store.getPasswordHash(None, "alice") shouldBe hashBefore
      store.findUser(None, "alice").get.mustChangePassword shouldBe true
  }
