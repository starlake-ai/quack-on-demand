package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.auth.TokenRestriction
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
      val (record, token) = pats.mint(uid, "claude-code", TokenRestriction.Unrestricted, None, 0)
      token should startWith(PatStore.TokenPrefix)
      record.userId shouldBe uid
      pats.verify(token).map(_.id) shouldBe Some(record.id)
      // raw token is not in the table
      countWhere(users.dbName, s"token_hash = '$token'") shouldBe 0
  }

  it should "stamp last_used_at on verify" in withFreshDb { (users, pats) =>
    val uid        = seedUser(users)
    val (_, token) = pats.mint(uid, "t", TokenRestriction.Unrestricted, None, 0)
    pats.verify(token)
    pats.list(uid).head.lastUsedAt should not be empty
  }

  "verify" should "reject an expired token" in withFreshDb { (users, pats) =>
    val uid        = seedUser(users)
    val (_, token) = pats.mint(
      uid,
      "old",
      TokenRestriction.Unrestricted.copy(expiresAt = Some(Instant.now().minusSeconds(60))),
      None,
      0
    )
    pats.verify(token) shouldBe None
  }

  it should "reject a revoked token and reject garbage" in withFreshDb { (users, pats) =>
    val uid             = seedUser(users)
    val (record, token) = pats.mint(uid, "t", TokenRestriction.Unrestricted, None, 0)
    pats.revoke(uid, record.id) shouldBe List(record.id)
    pats.verify(token) shouldBe None
    pats.verify("qod_pat_notarealtoken") shouldBe None
    pats.verify("") shouldBe None
  }

  "revoke" should "refuse another user's token" in withFreshDb { (users, pats) =>
    val uid = seedUser(users)
    users.upsertUser(None, "bob", "pw", "admin")
    val bobId       = users.userIdOf(None, "bob").get
    val (record, _) = pats.mint(uid, "t", TokenRestriction.Unrestricted, None, 0)
    pats.revoke(bobId, record.id) shouldBe empty
  }

  "delete" should "remove a revoked token row" in withFreshDb { (users, pats) =>
    val uid         = seedUser(users)
    val (record, _) = pats.mint(uid, "dead", TokenRestriction.Unrestricted, None, 0)
    pats.revoke(uid, record.id) shouldBe List(record.id)
    pats.delete(uid, record.id) shouldBe PatStore.DeleteOutcome.Deleted
    countWhere(users.dbName, s"id = '${record.id}'") shouldBe 0
  }

  it should "remove an expired token row" in withFreshDb { (users, pats) =>
    val uid         = seedUser(users)
    val (record, _) = pats.mint(
      uid,
      "old",
      TokenRestriction.Unrestricted.copy(expiresAt = Some(Instant.now().minusSeconds(60))),
      None,
      0
    )
    pats.delete(uid, record.id) shouldBe PatStore.DeleteOutcome.Deleted
    countWhere(users.dbName, s"id = '${record.id}'") shouldBe 0
  }

  it should "refuse a live token" in withFreshDb { (users, pats) =>
    val uid             = seedUser(users)
    val (record, token) = pats.mint(uid, "live", TokenRestriction.Unrestricted, None, 0)
    pats.delete(uid, record.id) shouldBe PatStore.DeleteOutcome.Live
    countWhere(users.dbName, s"id = '${record.id}'") shouldBe 1
    pats.verify(token).map(_.id) shouldBe Some(record.id)
  }

  it should "answer NotFound for another user's token and for an unknown id" in withFreshDb {
    (users, pats) =>
      val uid = seedUser(users)
      users.upsertUser(None, "bob", "pw", "admin")
      val bobId       = users.userIdOf(None, "bob").get
      val (record, _) = pats.mint(uid, "t", TokenRestriction.Unrestricted, None, 0)
      pats.revoke(uid, record.id) shouldBe List(record.id)
      // A dead row owned by someone else is indistinguishable from a missing one.
      pats.delete(bobId, record.id) shouldBe PatStore.DeleteOutcome.NotFound
      countWhere(users.dbName, s"id = '${record.id}'") shouldBe 1
      pats.delete(uid, "pat-doesnotexist") shouldBe PatStore.DeleteOutcome.NotFound
  }

  // Genuine two-connection race: a raw JDBC connection holds an uncommitted revoke of the
  // parent (a plain UPDATE, which takes the same FOR NO KEY UPDATE row lock the production
  // revoke's recursive-CTE UPDATE takes) while a second, separate connection -- PatStore's own
  // pool, driven from a background thread -- attempts to mint a child under that parent. This is
  // exactly the shape the reviewer reproduced against the PG16 container: without `FOR UPDATE` on
  // the mint's EXISTS subquery, the insert is a non-locking read under READ COMMITTED and returns
  // immediately, seeing the pre-revoke row version and inserting a live child. With `FOR UPDATE`,
  // the subquery must acquire the same row lock the uncommitted revoke already holds, so it
  // blocks until the revoke resolves and then re-evaluates against the post-revoke state.
  "mint" should "refuse a child minted while a concurrent revoke of the parent is still uncommitted" in
    withFreshDb { (users, pats) =>
      import scala.concurrent.{Await, Future}
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration._

      val uid         = seedUser(users)
      val (parent, _) = pats.mint(uid, "parent", TokenRestriction.Unrestricted, None, 0)

      // Connection A: the "revoke", held open uncommitted. A plain UPDATE takes a
      // FOR NO KEY UPDATE lock on the touched row -- the same lock shape PatStore.revoke's
      // recursive-CTE UPDATE takes in production; issuing it by hand here just lets the test
      // hold the transaction open instead of committing immediately.
      val revokeConn = DriverManager.getConnection(
        TestPostgres.dbUrl(users.dbName),
        TestPostgres.pgUser,
        TestPostgres.pgPass
      )
      revokeConn.setAutoCommit(false)
      try
        val upd = revokeConn.prepareStatement(
          "UPDATE qodstate_pat SET revoked_at = NOW() WHERE id = ? AND user_id = ?"
        )
        try
          upd.setString(1, parent.id)
          upd.setString(2, uid)
          upd.executeUpdate() shouldBe 1
        finally upd.close()

        // Connection B: PatStore's own pool, driven from a background thread so this thread
        // can observe the mint blocking before the revoke commits.
        val mintResult: Future[Try[(PatRecord, String)]] = Future(
          Try(pats.mint(uid, "child", TokenRestriction.Unrestricted, Some(parent.id), 1))
        )

        // Give the background mint time to reach the FOR UPDATE subquery and block on
        // connection A's still-uncommitted lock. If FOR UPDATE were absent (or a no-op), the
        // mint would race ahead and complete well inside this window instead of blocking.
        Thread.sleep(500)
        mintResult.isCompleted shouldBe false

        // Release the lock: commit the revoke, so the blocked EXISTS subquery re-evaluates
        // against the post-revoke row and finds it dead.
        revokeConn.commit()

        val outcome = Await.result(mintResult, 5.seconds)
        outcome.isFailure shouldBe true
        outcome.failed.get shouldBe a[PatStore.ParentNotLiveException]
      finally
        Try(revokeConn.rollback())
        revokeConn.close()
    }

  // The reported order: a child's mint is under way (it holds the user's PAT lock and is inserting
  // under the parent) when the parent is revoked. A revoke that read the subtree at once would miss
  // the uncommitted child and leave it live under a revoked parent; it must wait, then revoke it.
  "revoke" should "revoke a child whose mint was still uncommitted when the revoke began" in
    withFreshDb { (users, pats) =>
      import scala.concurrent.{Await, Future}
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration._

      val uid         = seedUser(users)
      val (parent, _) = pats.mint(uid, "parent", TokenRestriction.Unrestricted, None, 0)

      // Connection A: a child mint, by hand so it can stay uncommitted: the user's PAT lock, the
      // parent row locked as mint locks it, the child inserted.
      val mintConn = DriverManager.getConnection(
        TestPostgres.dbUrl(users.dbName),
        TestPostgres.pgUser,
        TestPostgres.pgPass
      )
      mintConn.setAutoCommit(false)
      try
        val lock = mintConn.prepareStatement(PatStore.UserLockSql)
        lock.setInt(1, PatStore.LockClass)
        lock.setString(2, uid)
        lock.execute()
        lock.close()
        val rowLock = mintConn.prepareStatement(
          "SELECT 1 FROM qodstate_pat WHERE id = ? AND revoked_at IS NULL FOR UPDATE"
        )
        rowLock.setString(1, parent.id)
        rowLock.executeQuery().next() shouldBe true
        rowLock.close()
        val ins = mintConn.prepareStatement(
          "INSERT INTO qodstate_pat (id, user_id, name, token_hash, created_at, parent_id, depth) " +
            "VALUES ('pat-child-race', ?, 'child', 'h-child-race', NOW(), ?, 1)"
        )
        ins.setString(1, uid)
        ins.setString(2, parent.id)
        ins.executeUpdate() shouldBe 1
        ins.close()

        val revoked: Future[List[String]] = Future(pats.revoke(uid, parent.id))
        Thread.sleep(500)
        revoked.isCompleted shouldBe false

        mintConn.commit()

        Await.result(revoked, 5.seconds).toSet shouldBe Set(parent.id, "pat-child-race")
        countWhere(users.dbName, "id = 'pat-child-race' AND revoked_at IS NULL") shouldBe 0
      finally
        Try(mintConn.rollback())
        mintConn.close()
    }

  it should "leave no live descendant under a revoked parent when mints race the revoke" in
    withFreshDb { (users, pats) =>
      import scala.concurrent.{Await, Future}
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration._

      val uid = seedUser(users)
      for round <- 1 to 20 do
        val (parent, _) = pats.mint(uid, s"p$round", TokenRestriction.Unrestricted, None, 0)
        val mints       = (1 to 4).map { i =>
          Future(
            Try(pats.mint(uid, s"c$round-$i", TokenRestriction.Unrestricted, Some(parent.id), 1))
          )
        }
        val revoke = Future(pats.revoke(uid, parent.id))
        Await.result(Future.sequence(mints :+ revoke), 10.seconds)
        withClue(s"round $round: ") {
          countWhere(
            users.dbName,
            s"parent_id = '${parent.id}' AND revoked_at IS NULL"
          ) shouldBe 0
        }
    }
