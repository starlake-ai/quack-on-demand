package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.util.Try

/** Scope persistence and chain semantics on `qodstate_pat` (changelog 0033). */
class PatScopeStoreSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodpsc")

  private def withFreshDb(test: (UserStore, PatStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodpsc_test_${System.nanoTime()}"
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

  "mint" should "round-trip a restriction, distinguishing NULL from an empty array" in
    withFreshDb { (users, pats) =>
      val uid        = seedUser(users)
      val restricted = TokenRestriction.Unrestricted.copy(
        databases = Some(Set("acme_db")),
        tools = Some(Set.empty),
        verbCeiling = Some("RO"),
        maxRows = Some(50)
      )
      val (rec, raw) = pats.mint(uid, "agent", restricted, None, 0)
      rec.restriction.databases shouldBe Some(Set("acme_db"))
      rec.restriction.tools shouldBe Some(Set.empty) // NOT None
      rec.restriction.pools shouldBe None            // NOT Some(Set.empty)

      // `rec` above is the in-memory value mint() was given; re-read through verify() to prove
      // the NULL-vs-empty-array distinction actually survives a real round trip through Postgres,
      // not just an echo of the constructor argument.
      val reread = pats.verify(raw).getOrElse(fail("token did not verify"))
      reread.restriction.databases shouldBe Some(Set("acme_db"))
      reread.restriction.tools shouldBe Some(Set.empty) // NOT None
      reread.restriction.pools shouldBe None            // NOT Some(Set.empty)
      reread.restriction.verbCeiling shouldBe Some("RO")
    }

  it should "default a pre-existing unscoped row to Unrestricted" in
    withFreshDb { (users, pats) =>
      val uid      = seedUser(users)
      val (_, raw) = pats.mint(uid, "plain", TokenRestriction.Unrestricted, None, 0)
      pats.verify(raw).map(_.restriction) shouldBe Some(TokenRestriction.Unrestricted)
    }

  // Parent-liveness re-check: mint's atomic INSERT ... WHERE EXISTS(...) guard must refuse a
  // parentId that is absent, owned by someone else, revoked, or expired, in the same statement as
  // the write -- not as a separate pre-check the caller could race against.

  it should "refuse a parentId owned by a different user" in
    withFreshDb { (users, pats) =>
      val uid = seedUser(users)
      users.upsertUser(None, "bob", "pw", "admin")
      val bobId        = users.userIdOf(None, "bob").get
      val (bobRoot, _) = pats.mint(bobId, "bob-root", TokenRestriction.Unrestricted, None, 0)
      an[PatStore.ParentNotLiveException] should be thrownBy
        pats.mint(uid, "child", TokenRestriction.Unrestricted, Some(bobRoot.id), 1)
    }

  it should "refuse a revoked parent" in
    withFreshDb { (users, pats) =>
      val uid       = seedUser(users)
      val (root, _) = pats.mint(uid, "root", TokenRestriction.Unrestricted, None, 0)
      pats.revoke(uid, root.id) shouldBe true
      an[PatStore.ParentNotLiveException] should be thrownBy
        pats.mint(uid, "child", TokenRestriction.Unrestricted, Some(root.id), 1)
    }

  it should "refuse an expired parent" in
    withFreshDb { (users, pats) =>
      val uid           = seedUser(users)
      val expiredParent =
        TokenRestriction.Unrestricted.copy(expiresAt = Some(Instant.now().minusSeconds(60)))
      val (root, _) = pats.mint(uid, "root", expiredParent, None, 0)
      an[PatStore.ParentNotLiveException] should be thrownBy
        pats.mint(uid, "child", TokenRestriction.Unrestricted, Some(root.id), 1)
    }

  "revoke" should "cascade to the whole subtree" in
    withFreshDb { (users, pats) =>
      val uid               = seedUser(users)
      val (root, rootRaw)   = pats.mint(uid, "root", TokenRestriction.Unrestricted, None, 0)
      val (child, childRaw) =
        pats.mint(uid, "child", TokenRestriction.Unrestricted, Some(root.id), 1)
      val (_, grandRaw) = pats.mint(uid, "grand", TokenRestriction.Unrestricted, Some(child.id), 2)

      pats.revoke(uid, root.id) shouldBe true

      pats.verify(rootRaw) shouldBe None
      pats.verify(childRaw) shouldBe None
      pats.verify(grandRaw) shouldBe None
    }

  it should "leave a sibling subtree alone" in
    withFreshDb { (users, pats) =>
      val uid       = seedUser(users)
      val (a, _)    = pats.mint(uid, "a", TokenRestriction.Unrestricted, None, 0)
      val (_, bRaw) = pats.mint(uid, "b", TokenRestriction.Unrestricted, None, 0)
      pats.revoke(uid, a.id) shouldBe true
      pats.verify(bRaw).isDefined shouldBe true
    }

  // Once a token's whole subtree is already revoked -- the only state reachable through this
  // store's own API, since mint refuses to place a child under a non-live parent -- a second
  // revoke call has nothing left to flip and must report the no-op false, exactly like a bare
  // (childless) token revoked twice.
  it should "no-op on a second call once the token has no live descendants left" in
    withFreshDb { (users, pats) =>
      val uid       = seedUser(users)
      val (root, _) = pats.mint(uid, "root", TokenRestriction.Unrestricted, None, 0)
      pats.revoke(uid, root.id) shouldBe true
      pats.revoke(uid, root.id) shouldBe false
    }

  "isInSubtree" should "accept a descendant and refuse a sibling" in
    withFreshDb { (users, pats) =>
      val uid        = seedUser(users)
      val (root, _)  = pats.mint(uid, "root", TokenRestriction.Unrestricted, None, 0)
      val (child, _) = pats.mint(uid, "child", TokenRestriction.Unrestricted, Some(root.id), 1)
      val (other, _) = pats.mint(uid, "other", TokenRestriction.Unrestricted, None, 0)
      pats.isInSubtree(uid, root.id, child.id) shouldBe true
      pats.isInSubtree(uid, root.id, other.id) shouldBe false
      pats.isInSubtree(uid, root.id, root.id) shouldBe false // never itself
    }

  "findById" should "return the owner-scoped record with depth and restriction" in
    withFreshDb { (users, pats) =>
      val uid        = seedUser(users)
      val restricted = TokenRestriction.Unrestricted.copy(maxRows = Some(10))
      val (root, _)  = pats.mint(uid, "root", TokenRestriction.Unrestricted, None, 0)
      val (child, _) = pats.mint(uid, "child", restricted, Some(root.id), 1)

      pats.findById(uid, child.id).map(_.id) shouldBe Some(child.id)
      pats.findById(uid, child.id).map(_.depth) shouldBe Some(1)
      pats.findById(uid, child.id).map(_.parentId) shouldBe Some(Some(root.id))
      pats.findById(uid, child.id).flatMap(_.restriction.maxRows) shouldBe Some(10)
    }

  it should "return None for another user's token and for an unknown id" in
    withFreshDb { (users, pats) =>
      val uid = seedUser(users)
      users.upsertUser(None, "bob", "pw", "admin")
      val bobId     = users.userIdOf(None, "bob").get
      val (root, _) = pats.mint(uid, "root", TokenRestriction.Unrestricted, None, 0)

      pats.findById(bobId, root.id) shouldBe None
      pats.findById(uid, "pat-doesnotexist") shouldBe None
    }
