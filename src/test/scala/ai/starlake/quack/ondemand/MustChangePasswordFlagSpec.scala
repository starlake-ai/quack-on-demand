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
  * untouched, and Some(true) without a password is refused (there is no temp password for the flag
  * to describe).
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

  it should "refuse Some(true) without a password" in withSup { (sup, store, users) =>
    sup.createUser(None, "alice", "pw", "admin", users).unsafeRunSync()
    val id  = store.findUser(None, "alice").get.id
    val out =
      sup
        .updateUserPassword(id, None, None, users, mustChangePassword = Some(true))
        .unsafeRunSync()
    out.left.toOption.get shouldBe a[SupervisorError.InvalidArgument]
  }
