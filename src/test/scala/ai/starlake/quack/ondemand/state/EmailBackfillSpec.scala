package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.util.Try

/** Pins the exact predicate the `0031-user-email-from-username` backfill changeset ships, so the
  * test and the changeset can't drift apart. The migration itself already ran (as part of
  * `LiquibaseRunner.run()`, which is a no-op on rows that don't exist yet), so this seeds rows
  * AFTER migration and re-runs the backfill UPDATE by hand to prove which rows it matches.
  */
class EmailBackfillSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodbf")

  // The exact SQL the 0031 changeset runs. Keep in sync with
  // db/changelog/0031-user-email-from-username.yaml.
  private val BackfillSql =
    """UPDATE qodstate_user SET email = username
      | WHERE email IS NULL
      |   AND username ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'""".stripMargin

  private def runSql(url: String, sql: String): Unit =
    val c = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      val st = c.createStatement()
      try st.execute(sql)
      finally st.close()
    finally c.close()

  private def withFreshDb(test: (PostgresControlPlaneStore, String) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodbf_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store, url)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  "the 0031 backfill predicate" should "set email=username only for email-format usernames with null email" in
    withFreshDb { (store, url) =>
      store.upsertUserWithHash(
        None,
        "root@corp.io",
        "$2a$10$aaaaaaaaaaaaaaaaaaaaaa",
        "admin",
        email = None
      )
      store.upsertUserWithHash(
        Some("t1"),
        "bob@x.io",
        "$2a$10$bbbbbbbbbbbbbbbbbbbbbb",
        "user",
        email = Some("keep@y.io")
      )
      store.upsertUserWithHash(
        Some("t1"),
        "carol",
        "$2a$10$cccccccccccccccccccccc",
        "user",
        email = None
      )

      runSql(url, BackfillSql)

      store.findUser(None, "root@corp.io").get.email shouldBe Some("root@corp.io")
      store.findUser(Some("t1"), "bob@x.io").get.email shouldBe Some("keep@y.io")
      store.findUser(Some("t1"), "carol").get.email shouldBe None
    }

  it should "be idempotent -- a second run is a no-op" in withFreshDb { (store, url) =>
    store.upsertUserWithHash(
      None,
      "dana@corp.io",
      "$2a$10$dddddddddddddddddddddd",
      "admin",
      email = None
    )

    runSql(url, BackfillSql)
    runSql(url, BackfillSql)

    store.findUser(None, "dana@corp.io").get.email shouldBe Some("dana@corp.io")
  }
