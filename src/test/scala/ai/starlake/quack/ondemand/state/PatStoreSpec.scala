package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import java.time.Instant
import scala.util.Try

/** Persistence semantics of `qodstate_pat` (changelog 0032) through [[PatStore]]: mint stores only
  * the SHA-256 hash of the token, verify enforces expiry/revocation and stamps `last_used_at`, and
  * revoke is scoped to the owning user.
  */
class PatStoreSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodpat")

  private def withFreshDb(test: (UserStore, PatStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodpat_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val pats  = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(users, pats)
      finally
        pats.close()
        users.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def seedUser(users: UserStore): String =
    users.upsertUser(None, "alice", "pw", "admin")
    users.userIdOf(None, "alice").get

  /** Row count for a raw SQL predicate against the fresh test db, via a direct JDBC connection
    * (TestPostgres.psql returns Unit, not the query output, so it cannot back an assertion here).
    */
  private def countWhere(dbName: String, whereSql: String): Int =
    val c = DriverManager.getConnection(
      TestPostgres.dbUrl(dbName),
      TestPostgres.pgUser,
      TestPostgres.pgPass
    )
    try
      val rs =
        c.createStatement().executeQuery(s"SELECT count(*) FROM qodstate_pat WHERE $whereSql")
      rs.next()
      rs.getInt(1)
    finally c.close()

  "mint + verify" should "round-trip and never store the raw token" in withFreshDb {
    (users, pats) =>
      val uid             = seedUser(users)
      val (record, token) = pats.mint(uid, "claude-code", None)
      token should startWith(PatStore.TokenPrefix)
      record.userId shouldBe uid
      pats.verify(token).map(_.id) shouldBe Some(record.id)
      // raw token is not in the table
      countWhere(users.dbName, s"token_hash = '$token'") shouldBe 0
  }

  it should "stamp last_used_at on verify" in withFreshDb { (users, pats) =>
    val uid        = seedUser(users)
    val (_, token) = pats.mint(uid, "t", None)
    pats.verify(token)
    pats.list(uid).head.lastUsedAt should not be empty
  }

  "verify" should "reject an expired token" in withFreshDb { (users, pats) =>
    val uid        = seedUser(users)
    val (_, token) = pats.mint(uid, "old", Some(Instant.now().minusSeconds(60)))
    pats.verify(token) shouldBe None
  }

  it should "reject a revoked token and reject garbage" in withFreshDb { (users, pats) =>
    val uid             = seedUser(users)
    val (record, token) = pats.mint(uid, "t", None)
    pats.revoke(uid, record.id) shouldBe true
    pats.verify(token) shouldBe None
    pats.verify("qod_pat_notarealtoken") shouldBe None
    pats.verify("") shouldBe None
  }

  "revoke" should "refuse another user's token" in withFreshDb { (users, pats) =>
    val uid = seedUser(users)
    users.upsertUser(None, "bob", "pw", "admin")
    val bobId       = users.userIdOf(None, "bob").get
    val (record, _) = pats.mint(uid, "t", None)
    pats.revoke(bobId, record.id) shouldBe false
  }
