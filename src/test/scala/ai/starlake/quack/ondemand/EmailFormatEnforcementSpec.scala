package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** The email-locking rule enforced through the PoolSupervisor write paths: an email-format username
  * IS its own email, so create auto-derives it, a conflicting supplied email is refused with
  * [[SupervisorError.InvalidEmail]], and a non-email username's email stays free.
  */
class EmailFormatEnforcementSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodefe")

  private def withSup(test: (PoolSupervisor, PostgresControlPlaneStore, UserStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodefe_test_${System.nanoTime()}"
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

  "createUser" should "auto-set email to an email-format username" in withSup {
    (sup, store, users) =>
      sup
        .createUser(None, "root@corp.io", "pw", "admin", users)
        .unsafeRunSync()
        .isRight shouldBe true
      store.findUser(None, "root@corp.io").get.email shouldBe Some("root@corp.io")
  }

  it should "reject a different email on an email-format username" in withSup {
    (sup, store, users) =>
      val out = sup
        .createUser(None, "root@corp.io", "pw", "admin", users, email = Some("other@x.io"))
        .unsafeRunSync()
      out.left.toOption.get shouldBe a[SupervisorError.InvalidEmail]
  }

  it should "accept the same email as an email-format username" in withSup { (sup, store, users) =>
    sup
      .createUser(None, "root@corp.io", "pw", "admin", users, email = Some("root@corp.io"))
      .unsafeRunSync()
      .isRight shouldBe true
    store.findUser(None, "root@corp.io").get.email shouldBe Some("root@corp.io")
  }

  it should "leave a non-email username's email free" in withSup { (sup, store, users) =>
    sup.createUser(None, "alice", "pw", "admin", users, email = Some("alice@x.io")).unsafeRunSync()
    store.findUser(None, "alice").get.email shouldBe Some("alice@x.io")
  }

  "updateUserPassword" should "reject changing an email-format user's email" in withSup {
    (sup, store, users) =>
      sup.createUser(None, "root@corp.io", "pw", "admin", users).unsafeRunSync()
      val id  = store.findUser(None, "root@corp.io").get.id
      val out = sup
        .updateUserPassword(id, None, None, users, email = Some(Some("other@x.io")))
        .unsafeRunSync()
      out.left.toOption.get shouldBe a[SupervisorError.InvalidEmail]
  }
