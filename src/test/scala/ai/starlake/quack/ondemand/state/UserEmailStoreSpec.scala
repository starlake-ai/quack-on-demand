package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Persistence semantics of `qodstate_user.email` (changelog 0029) through the shared
  * [[UserUpsert]] and [[UserStore.upsertUser]]. Two-level `Option[Option[String]]`: outer `None`
  * leaves the column untouched on update (and NULL by default on insert), `Some(None)` clears it to
  * SQL NULL, `Some(Some(x))` sets it.
  */
class UserEmailStoreSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodeml")

  private def withFreshDb(test: (PostgresControlPlaneStore, UserStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodeml_test_${System.nanoTime()}"
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

  "upsertUser" should "default email to null and round-trip a set email" in withFreshDb {
    (store, users) =>
      users.upsertUser(None, "alice", "pw", "admin")
      store.findUser(None, "alice").get.email shouldBe None
      users.upsertUser(None, "alice", "pw", "admin", email = Some(Some("a@x.io")))
      store.findUser(None, "alice").get.email shouldBe Some("a@x.io")
  }

  it should "leave email unchanged when the outer option is None" in withFreshDb { (store, users) =>
    users.upsertUser(None, "alice", "pw", "admin", email = Some(Some("a@x.io")))
    users.upsertUser(None, "alice", "pw2", "admin") // email arg defaults to None
    store.findUser(None, "alice").get.email shouldBe Some("a@x.io")
  }

  it should "clear email on Some(None)" in withFreshDb { (store, users) =>
    users.upsertUser(None, "alice", "pw", "admin", email = Some(Some("a@x.io")))
    users.upsertUser(None, "alice", "pw", "admin", email = Some(None))
    store.findUser(None, "alice").get.email shouldBe None
  }
